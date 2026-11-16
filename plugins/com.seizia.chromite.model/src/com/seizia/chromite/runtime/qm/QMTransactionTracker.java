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
package com.seizia.chromite.runtime.qm;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSavepoint;
import com.seizia.chromite.model.exec.DBCServerTransaction;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.DBCTransactionProbe;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.qm.meta.QMMConnectionInfo;
import com.seizia.chromite.model.qm.meta.QMMStatementExecuteInfo;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.jobs.IdleTransactionMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Keeps, per execution context, what the database last said about the session's transaction
 * ({@link DBCTransactionProbe}), and asks again after the user's work.
 *
 * 설계(docs/chromite/idle-transactions.md "트랜잭션 상태"): 사용자 원칙 "실제로 쥐고 있을 때만 하라"(2026-11-08).
 * <ul>
 *     <li>언제 묻나: 사용자 문장(메타데이터 읽기·내부 작업이 아닌 문장, {@code QMUtils}와 같은 기준)이 실행된 문맥은 "다시 물을 것"으로
 *     표시하고, 사용자 문장을 실행한 세션이 모두 닫힐 때 묻는다. SQL 실행 작업, 결과 더 읽기, 표 저장이 모두 세션을 열고 닫으므로 작업 하나가
 *     끝날 때 한 번이고, 스크립트의 문장 사이나 그 안에서 열고 닫는 내부 세션 뒤에는 묻지 않는다. 그 스레드는 실행 작업 스레드다(EDT가 아님,
 *     주기적으로 묻지 않음). 모든 세션을 세지 않는 것은 기록을 끈 세션은 닫힐 때 알려지지 않기 때문이다.</li>
 *     <li>커밋·롤백·커밋 방식 변경 뒤에도 묻는다. 세션 밖에서 일어나면(열린 세션이 없으면) 작은 작업으로 묻는다. 커밋·롤백 직후에는 묻기 전에
 *     "트랜잭션 없음"으로 둔다: 다른 기능(스마트 커밋)이 커밋 직후 상태를 읽기 때문이다. 중첩 BEGIN TRAN처럼 커밋 뒤에도 남는 트랜잭션은
 *     곧 이어 묻는 답이 고친다.</li>
 *     <li>시작 시각({@link Snapshot#since()}): 트랜잭션이 없다고 확인된 뒤 처음 실행된 사용자 문장의 시작 시각. 서버의 트랜잭션은 그 문장에서
 *     시작했다(쥔 것이 없던 SELECT라도). 쥔 것이 생긴 뒤에도 같은 값을 써서 커밋·롤백 메뉴와 알림이 같은 트랜잭션을 가리킨다.</li>
 *     <li>답이 늦게 오면: 묻는 동안 새 작업이 시작되었으면 답은 저장하되 "다시 물을 것"을 지우지 않는다.</li>
 *     <li>질의는 문맥의 JDBC 연결에 직접 보내므로 쿼리 관리자에 기록되지 않고 이 처리기를 다시 부르지 않는다.</li>
 *     <li>조회만 한 트랜잭션 자동으로 끝내기(2026-11-09, 앱 변경 #31): 사용자 작업 뒤에 물은 답이 그 사이 새 작업 없이 그대로이면
 *     {@code IdleTransactionMonitor.endReadOnlyAfterWork}가 연결 종류의 설정을 보고 같은 스레드에서 끝낸다. 문장이 끝난 바로 그 자리라서
 *     다른 작업이 끼어들 틈이 가장 작고, 별도 작업으로 미루면 끝나 가는 실행 작업 자신 때문에 늘 "실행 중"으로 보이기 때문이다. 끝낸 시각은
 *     다음 사용자 문장까지 남겨 상태 줄이 짧게 알린다({@link Snapshot#readOnlyEnded()}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/idle-transactions.md "트랜잭션 상태")
 * - 2026-11-09 다시 묻기({@link #refresh}), 조회만 한 트랜잭션 자동으로 끝내기와 끝낸 시각
 *   (docs/chromite/idle-transactions.md "조회만 한 트랜잭션 끝내기")
 */
public class QMTransactionTracker extends DefaultExecutionHandler {

    private static final Log log = Log.getLog(QMTransactionTracker.class);

    /**
     * What is known about the transaction of one context.
     *
     * @param server  the database's last answer; null when it has none (no probe for this database, or not asked yet)
     * @param pending user work, a commit, a rollback or a commit mode change happened after the last answer
     * @param since   start time of the first user statement since the transaction was last seen ended, 0 when none
     * @param readOnlyEnded when a read-only transaction was ended automatically after the user's work, 0 when not since
     *                      the last user statement
     */
    public record Snapshot(@Nullable DBCServerTransaction server, boolean pending, long since, long readOnlyEnded) {
    }

    private static final class Tracked {
        // Open sessions that ran user statements. Not a count of all sessions: a session whose logging is turned off
        // is not reported when it closes.
        final Set<DBCSession> workingSessions = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean pending;
        long generation;
        @Nullable
        DBCServerTransaction server;
        long since;
        long readOnlyEnded;
    }

    // Guarded by itself
    private final Map<DBCExecutionContext, Tracked> contexts = new IdentityHashMap<>();

    @NotNull
    @Override
    public String getHandlerName() {
        return "Transaction state tracker";
    }

    @Nullable
    public Snapshot get(@NotNull DBCExecutionContext context) {
        synchronized (contexts) {
            Tracked tracked = contexts.get(context);
            return tracked == null ? null : new Snapshot(tracked.server, tracked.pending, tracked.since, tracked.readOnlyEnded);
        }
    }

    @Override
    public void handleContextOpen(@NotNull DBCExecutionContext context, boolean transactional) {
        // A new or reopened server session has no transaction
        synchronized (contexts) {
            contexts.put(context, new Tracked());
        }
    }

    @Override
    public void handleContextClose(@NotNull DBCExecutionContext context) {
        synchronized (contexts) {
            contexts.remove(context);
        }
    }

    @Override
    public void handleSessionClose(@NotNull DBCSession session) {
        DBCExecutionContext context = session.getExecutionContext();
        long generation;
        synchronized (contexts) {
            Tracked tracked = contexts.get(context);
            if (tracked == null || !tracked.workingSessions.remove(session) || !tracked.workingSessions.isEmpty() || !tracked.pending) {
                return;
            }
            generation = tracked.generation;
        }
        if (probe(context, generation) && IdleTransactionMonitor.endReadOnlyAfterWork(context)) {
            synchronized (contexts) {
                Tracked tracked = contexts.get(context);
                if (tracked != null) {
                    tracked.readOnlyEnded = System.currentTimeMillis();
                }
            }
        }
    }

    /**
     * Asks the database about the context's transaction now, on the calling thread (not the UI thread).
     */
    public void refresh(@NotNull DBCExecutionContext context) {
        long generation;
        synchronized (contexts) {
            Tracked tracked = contexts.get(context);
            if (tracked == null) {
                return;
            }
            generation = tracked.generation;
        }
        probe(context, generation);
    }

    @Override
    public void handleStatementExecuteBegin(@NotNull DBCStatement statement) {
        DBCSession session = statement.getSession();
        DBCExecutionPurpose purpose = session.getPurpose();
        if (purpose == DBCExecutionPurpose.META || purpose == DBCExecutionPurpose.UTIL) {
            return;
        }
        DBCExecutionContext context = session.getExecutionContext();
        // The query manager recorded the execution just before this handler (it was registered first)
        QMMConnectionInfo connectionInfo = QMUtils.getCurrentConnection(context);
        QMMStatementExecuteInfo exec = connectionInfo == null ? null : connectionInfo.getExecutionStack();
        long start = exec != null ? exec.getOpenTime() : System.currentTimeMillis();
        synchronized (contexts) {
            Tracked tracked = tracked(context);
            tracked.workingSessions.add(session);
            tracked.readOnlyEnded = 0;
            markPending(tracked);
            if (tracked.since == 0) {
                tracked.since = start;
            }
        }
    }

    @Override
    public void handleTransactionCommit(@NotNull DBCExecutionContext context) {
        transactionEnded(context);
    }

    @Override
    public void handleTransactionRollback(@NotNull DBCExecutionContext context, @Nullable DBCSavepoint savepoint) {
        if (savepoint == null) {
            transactionEnded(context);
        } else {
            changed(context);
        }
    }

    @Override
    public void handleTransactionAutocommit(@NotNull DBCExecutionContext context, boolean autoCommit) {
        changed(context);
    }

    private void transactionEnded(@NotNull DBCExecutionContext context) {
        synchronized (contexts) {
            Tracked tracked = tracked(context);
            if (tracked.server != null) {
                tracked.server = DBCServerTransaction.NONE;
            }
            tracked.since = 0;
        }
        changed(context);
    }

    // Asks after the change: at the end of the user's work it happened in (a COMMIT in a script), else now in a job
    private void changed(@NotNull DBCExecutionContext context) {
        long generation;
        synchronized (contexts) {
            Tracked tracked = tracked(context);
            markPending(tracked);
            if (!tracked.workingSessions.isEmpty()) {
                return;
            }
            generation = tracked.generation;
        }
        new AbstractJob("Check the transaction of " + context.getContextName()) { //$NON-NLS-1$
            {
                setSystem(true);
                setUser(false);
            }

            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                probe(context, generation);
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * @return true when the answer is current: no user work, commit or rollback happened while asking
     */
    private boolean probe(@NotNull DBCExecutionContext context, long generation) {
        DBCServerTransaction answer = null;
        boolean current;
        long asked = System.currentTimeMillis();
        if (context instanceof DBCTransactionProbe probe && context.isConnected()) {
            answer = probe.probeTransaction();
        }
        synchronized (contexts) {
            Tracked tracked = contexts.get(context);
            if (tracked == null) {
                return false;
            }
            tracked.server = answer;
            if (answer != null) {
                if (answer.kind() == DBCServerTransaction.Kind.NONE) {
                    tracked.since = 0;
                } else if (tracked.since == 0 && answer.kind() != DBCServerTransaction.Kind.EMPTY) {
                    // Still open after a commit (nested BEGIN TRAN), or open without a statement of the user seen
                    tracked.since = asked;
                }
            }
            current = tracked.generation == generation;
            if (current) {
                tracked.pending = false;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Transaction of " + context.getDataSource().getContainer().getName() + "::" + context.getContextName()
                + ": " + (answer == null ? "no probe" : answer.kind() + " " + answer.holds() + (answer.readsOnly() ? " reads only" : "")));
        }
        return current;
    }

    private static void markPending(@NotNull Tracked tracked) {
        tracked.pending = true;
        tracked.generation++;
    }

    // Contexts opened before the tracker (none in practice) are tracked from their first event
    @NotNull
    private Tracked tracked(@NotNull DBCExecutionContext context) {
        return contexts.computeIfAbsent(context, c -> new Tracked());
    }
}
