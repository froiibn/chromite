/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.seizia.chromite.runtime.jobs;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.runtime.DBServiceUnrecordedSessions;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceIdleTransactions;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the automatic error recovery from ending a transaction the user has: the rollback after PostgreSQL's
 * "current transaction is aborted" and the reconnect after a lost connection ({@code DBExecUtils.tryExecuteRecover},
 * {@link KeepAlivePingJob}). When an open transaction would be ended, the recovery stops and the user is told, with
 * Roll back or Reconnect offered as an explicit choice.
 *
 * 설계: 사용자 규칙 "트랜잭션 안전"(2026-11-07): 커밋·롤백을 조용히 하지 않는다.
 * <ul>
 *     <li>업스트림 복구는 묻지 않았다. 트랜잭션이 중단되면 문맥을 롤백(쿼리 관리자에 기록하지 않음)하고 문장을 다시 실행했고,
 *     연결이 끊기면 데이터 소스의 모든 문맥을 닫고(수동 커밋이면 닫기 전에 롤백) 다시 연결했다. 그래서 사용자는 앞 문장이 아직
 *     트랜잭션에 있다고 믿지만 이미 없었고, 끊기지 않은 다른 문맥(다른 연결 쪽 작업, Chromite Agent 세션)의 트랜잭션까지 롤백되었다.</li>
 *     <li>"열린 트랜잭션"은 상태 줄·유휴 감시·열린 트랜잭션 목록과 같은 기준이다({@link QMTransactionState#isOpen()}: DB가 무언가를
 *     쥐었다고 답했거나 명시적으로 시작했거나 확인할 수 없음). 쥔 것 없는 문맥과 메타데이터 읽기만 한 문맥은 복구해도 잃을 것이 없으므로
 *     업스트림대로 복구한다.
 *     다시 연결은 쿼리 관리자 밖 세션(Chromite Agent, {@link DBServiceUnrecordedSessions})도 닫으므로 그것도 본다.</li>
 *     <li>멈추고 묻기를 골랐다(알림만 하고 복구하는 방법보다 안전). 멈추면 원래 오류가 호출자에게 그대로 간다. 사용자에게는
 *     UI 서비스({@link UIServiceIdleTransactions})가 남는 알림으로 이유와 잃는 문장 수를 보이고, 롤백·다시 연결을 고르게 한다.
 *     롤백은 다른 커밋·롤백과 같은 경로({@link IdleTransactionMonitor#endTransaction})이고, 다시 연결은 업스트림 복구와 같은
 *     {@link InvalidateJob#invalidateDataSource}를 사용자가 고른 뒤에 한다({@link #reconnect}).</li>
 *     <li>연결 유지 핑은 몇 초마다 다시 실패하므로 같은 연결 객체에 한 번만 알린다(새로 연결하면 새 객체라 다시 알린다).
 *     실행에서 생긴 오류는 매번 알리고 UI가 떠 있는 알림을 겹쳐 띄우지 않는다.</li>
 *     <li>격리 문맥(쿼리 창의 자기 세션, 도구의 세션) 하나만 끊겼으면 그 문맥만 다시 연다({@link #reopenLostSession}). 데이터 소스 전체를
 *     다시 연결하면 끊기지 않은 다른 창의 세션과 그 열린 트랜잭션까지 끝나고, 위의 멈춤 때문에 끊긴 창은 다른 창의 트랜잭션이 끝날 때까지
 *     쓸 수 없었다. 주·메타데이터 문맥은 여러 창과 탐색기가 함께 쓰므로 그대로 전체 다시 연결과 멈춤을 거친다.</li>
 *     <li>세션이 정말 끊겼는지는 짧은 시간 안의 연결 확인으로 본다({@link #isSessionLost}). 끊긴 세션의 트랜잭션은 서버가 이미 롤백했으므로
 *     버려도 잃을 것이 없지만, 살아 있는 세션을 끊긴 것으로 보면 트랜잭션을 묻지 않고 끝내게 되므로 확인이 답하지 않으면 살아 있다고 본다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/transactions.md "오류 복구와 열린 트랜잭션")
 * - 2026-11-08 끊긴 격리 문맥만 다시 열기({@link #reopenLostSession}), 세션이 끊겼는지 확인({@link #isSessionLost}), 끊긴 세션의
 *   트랜잭션을 쿼리 관리자에 롤백으로 기록({@link #discardLostTransaction}) (docs/chromite/transactions.md "끊긴 세션의 쿼리 창")
 * - 2026-11-08 주석만: 열린 트랜잭션의 기준이 DB의 답으로 바뀜 (docs/chromite/idle-transactions.md "트랜잭션 상태")
 */
public final class TransactionRecoveryGuard {

    private static final Log log = Log.getLog(TransactionRecoveryGuard.class);

    // How long the session check may take; the connection's own validation timeout is 10 s by default
    private static final long SESSION_CHECK_TIMEOUT_MS = 3000;

    // Data sources whose held reconnect the keep-alive ping already reported; weak so that a new connection reports again
    private static final Set<DBPDataSource> reportedByPing = Collections.newSetFromMap(new WeakHashMap<>());

    private TransactionRecoveryGuard() {
    }

    /**
     * The context a recovered operation ran in and its transaction just before the first try ({@code tryExecuteRecover}
     * with a session).
     */
    public record Attempt(@NotNull DBCExecutionContext context, @NotNull QMTransactionState transactionBefore) {
        @NotNull
        public static Attempt start(@NotNull DBCExecutionContext context) {
            return new Attempt(context, QMUtils.getTransactionState(context));
        }
    }

    /**
     * Before the recovery rolls back a transaction the server aborted after an error (PostgreSQL 25P02). The recovery
     * rolls back the failed context, or all contexts of the data source when the error does not name one.
     *
     * @param attempt the context of the failed attempt and its transaction before it, or null when not known
     * @return true when the recovery must stop: one of those contexts has the user's statements (the user was told)
     */
    public static boolean holdAbortedTransactionRollback(
        @NotNull DBPDataSource dataSource,
        @Nullable DBCExecutionContext context,
        @Nullable Attempt attempt
    ) {
        Map<DBCExecutionContext, QMTransactionState> open = findOpenTransactions(
            context != null ? List.of(context) : getContexts(Set.of(dataSource.getContainer())), attempt);
        if (open.isEmpty()) {
            return false;
        }
        log.info("Transaction aborted by the server in " + dataSource.getContainer().getName()
            + ": not rolled back automatically, " + open.size() + " session(s) with the user's statements");
        UIServiceIdleTransactions ui = DBWorkbench.findService(UIServiceIdleTransactions.class);
        if (ui != null) {
            for (Map.Entry<DBCExecutionContext, QMTransactionState> entry : open.entrySet()) {
                ui.showTransactionAborted(entry.getKey(), entry.getValue());
            }
        }
        return true;
    }

    /**
     * Before the recovery reconnects a data source whose connection failed. Reconnecting closes every context of the
     * data source and of the data sources using its network handlers, and closing rolls back their transactions.
     *
     * @param attempt  the context of the failed attempt and its transaction before it, or null when not known
     * @param fromPing the keep-alive ping found the dead context (reported once per connection)
     * @return true when the reconnect must not happen automatically: an open transaction would be ended (the user was told)
     */
    public static boolean holdReconnect(
        @NotNull DBPDataSource dataSource,
        @NotNull Throwable error,
        @Nullable Attempt attempt,
        boolean fromPing
    ) {
        Set<DBPDataSourceContainer> containers = new LinkedHashSet<>();
        InvalidateJob.collectDependentDataSources(dataSource, containers);
        Map<DBCExecutionContext, QMTransactionState> open = findOpenTransactions(getContexts(containers), attempt);
        List<DBPDataSourceContainer> agentSessions = new ArrayList<>();
        DBServiceUnrecordedSessions unrecorded = DBWorkbench.findService(DBServiceUnrecordedSessions.class);
        if (unrecorded != null) {
            for (DBPDataSourceContainer container : containers) {
                if (container.isConnected() && unrecorded.isConnectionInUse(container)) {
                    agentSessions.add(container);
                }
            }
        }
        if (open.isEmpty() && agentSessions.isEmpty()) {
            synchronized (reportedByPing) {
                reportedByPing.remove(dataSource);
            }
            return false;
        }
        if (fromPing) {
            synchronized (reportedByPing) {
                if (!reportedByPing.add(dataSource)) {
                    return true;
                }
            }
        }
        log.info("Connection " + dataSource.getContainer().getName() + " failed: not reconnected automatically, "
            + open.size() + " session(s) with open transactions, Chromite Agent sessions in " + agentSessions.size() + " connection(s)");
        UIServiceIdleTransactions ui = DBWorkbench.findService(UIServiceIdleTransactions.class);
        if (ui != null) {
            ui.showReconnectHeld(dataSource, open, agentSessions, error);
        }
        return true;
    }

    /**
     * The reconnect the user chose after {@link #holdReconnect}: the same invalidation the recovery does, so it ends
     * the open transactions it was held for. Records their end in the query manager, which the invalidation does not
     * (it keeps the context objects and closes the old connections without logging), so the open transaction
     * indicators do not keep showing statements that are gone.
     *
     * @return the results of the invalidation
     */
    @NotNull
    public static List<InvalidateJob.ContextInvalidateResult> reconnect(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSource dataSource) {
        Set<DBPDataSourceContainer> containers = new LinkedHashSet<>();
        InvalidateJob.collectDependentDataSources(dataSource, containers);
        Map<DBCExecutionContext, QMTransactionState> open = findOpenTransactions(getContexts(containers), null);
        List<InvalidateJob.ContextInvalidateResult> results = InvalidateJob.invalidateDataSource(
            monitor, dataSource, false, true, new DefaultInvalidationFeedbackHandler());
        if (!results.isEmpty()) {
            // The old connections were closed (rolled back first), whether or not the new ones could be opened
            for (DBCExecutionContext context : open.keySet()) {
                QMUtils.getDefaultHandler().handleTransactionRollback(context, null);
            }
        }
        synchronized (reportedByPing) {
            reportedByPing.remove(dataSource);
        }
        log.info("Reconnected " + dataSource.getContainer().getName() + " as the user chose: " + results);
        return results;
    }

    /**
     * After the connection of a context failed: when it is an isolated context (a query window's own session, a tool's
     * session) and its server session is really gone, reopens that context alone. The main and metadata contexts and
     * the other isolated contexts keep their sessions and transactions. The user is told which session was lost and,
     * when it had the user's statements, that its uncommitted transaction is gone.
     *
     * @param attempt the context of the failed attempt and its transaction before it, or null when not known
     * @return true when the context was reopened (the caller may try again in it); false when it is the main or metadata
     * context, its session still answers, or it could not be reopened: the caller goes on with the whole connection
     */
    public static boolean reopenLostSession(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull Throwable error,
        @Nullable Attempt attempt
    ) {
        DBSInstance instance = context.getOwnerInstance();
        if (context == instance.getDefaultContext(monitor, false) || context == instance.getDefaultContext(monitor, true)) {
            // Shared by windows and metadata reads: the whole connection is reconnected, held for open transactions
            return false;
        }
        if (!isSessionLost(monitor, context)) {
            // Reopening a live session would end its transaction without asking
            return false;
        }
        // What is lost: the transaction before the failed attempt (a transaction only the attempt opened holds nothing)
        QMTransactionState lost = attempt != null && attempt.context() == context
            ? attempt.transactionBefore() : QMUtils.getTransactionState(context);
        Exception reopenError = null;
        Object lock = instance.getExclusiveLock().acquireExclusiveLock();
        try {
            context.invalidateContext(monitor);
        } catch (Exception e) {
            reopenError = e;
        } finally {
            instance.getExclusiveLock().releaseExclusiveLock(lock);
        }
        // The old connection is closed either way, so its transaction has ended
        discardLostTransaction(context);
        String name = context.getDataSource().getContainer().getName() + "::" + context.getContextName();
        if (reopenError != null) {
            log.debug("Lost session " + name + " could not be reopened: " + reopenError.getMessage());
            return false;
        }
        log.info("Session " + name + " was lost and reopened alone; uncommitted statements lost: "
            + (lost.isOpen() ? lost.getExecuteCount() : 0));
        UIServiceIdleTransactions ui = DBWorkbench.findService(UIServiceIdleTransactions.class);
        if (ui != null) {
            ui.showSessionReopened(context, lost.isOpen() ? lost : null, error);
        }
        return true;
    }

    /**
     * Whether the context's server session is gone: it has no connection, or the connection check
     * ({@link DBCExecutionContext#checkContextAlive}) fails within a short time. A check that does not answer in time,
     * or a context that runs a statement, counts as alive: only a session known to be gone may be treated as such.
     * Runs a server round-trip; call in a job.
     */
    public static boolean isSessionLost(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext context) {
        if (!context.isConnected()) {
            return true;
        }
        if (QMUtils.getTransactionState(context).isExecuting()) {
            return false;
        }
        AtomicBoolean lost = new AtomicBoolean();
        boolean answered = RuntimeUtils.runTask(
            checkMonitor -> {
                try {
                    context.checkContextAlive(checkMonitor);
                } catch (Exception e) {
                    log.debug("Session " + context.getContextName() + " check failed: " + e.getMessage());
                    lost.set(true);
                }
            },
            "Check session " + context.getContextName(),
            SESSION_CHECK_TIMEOUT_MS,
            true);
        return answered && lost.get();
    }

    /**
     * Records in the query manager that the transaction of a lost session ended (the server rolled it back with the
     * session), so the open transaction indicators, lists and questions no longer show its statements.
     */
    public static void discardLostTransaction(@NotNull DBCExecutionContext context) {
        if (QMUtils.isTransactionActive(context) || QMUtils.getTransactionState(context).isOpen()) {
            QMUtils.getDefaultHandler().handleTransactionRollback(context, null);
        }
    }

    @NotNull
    private static List<DBCExecutionContext> getContexts(@NotNull Set<DBPDataSourceContainer> containers) {
        List<DBCExecutionContext> contexts = new ArrayList<>();
        for (DBPDataSourceContainer container : containers) {
            DBPDataSource dataSource = container.getDataSource();
            if (dataSource == null) {
                continue;
            }
            for (DBSInstance instance : dataSource.getAvailableInstances()) {
                Collections.addAll(contexts, instance.getAllContexts());
            }
        }
        return contexts;
    }

    /**
     * The open transactions among the contexts. The failed attempt's own statement is recorded in its context's
     * transaction; a transaction that only this statement opened loses nothing, so for that context the state before
     * the attempt decides.
     */
    @NotNull
    private static Map<DBCExecutionContext, QMTransactionState> findOpenTransactions(
        @NotNull List<DBCExecutionContext> contexts,
        @Nullable Attempt attempt
    ) {
        Map<DBCExecutionContext, QMTransactionState> open = new LinkedHashMap<>();
        for (DBCExecutionContext context : contexts) {
            QMTransactionState transaction = QMUtils.getTransactionState(context);
            boolean wasOpen = attempt == null || attempt.context() != context || attempt.transactionBefore().isOpen();
            if (transaction.isOpen() && wasOpen) {
                open.put(context, transaction);
            }
        }
        return open;
    }
}
