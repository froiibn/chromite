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
package com.seizia.chromite.ui.swing;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPErrorAssistant;
import com.seizia.chromite.model.ai.tools.AgentSql;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCServerTransaction;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.registry.DataSourceRegistry;
import com.seizia.chromite.runtime.jobs.IdleTransactionMonitor;
import com.seizia.chromite.runtime.jobs.TransactionRecoveryGuard;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.LifecycleMessages;
import com.seizia.chromite.ui.swing.internal.NavigatorMessages;
import com.seizia.chromite.ui.swing.internal.ObjectExplorerMessages;
import com.seizia.chromite.ui.swing.internal.UpdateMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Before Chromite closes (window close, File &gt; Exit, [Restart now] of an update) and before a connection is
 * disconnected (Disconnect, Disconnect All / Others, a server group's Disconnect, Reconnect, Invalidate/Reconnect, Delete,
 * reconnect after editing it, Disconnect of the connection lost dialog), and before Object Explorer changes database
 * objects (Delete, Rename): asks what to do with the transactions that are not committed — Commit / Roll back / Cancel.
 * Nothing is committed or rolled back silently.
 *
 * 설계: 사용자 규칙 "트랜잭션 안전"(2026-11-07): 세션을 닫는 기능은 커밋하지 않은 트랜잭션을 먼저 찾아 커밋/롤백/취소를 묻는다.
 * 끝낼 때 플랫폼이 모든 연결을 닫으므로 그 전에 묻는다. SWT의 {@code ApplicationWorkbenchAdvisor.closeActiveTransactions}와
 * {@code DataSourceHandler.checkAndCloseActiveTransaction}(com.seizia.chromite.ui.editors.connection, #68에서 삭제)을 옮겼다.
 * <ul>
 *     <li>찾기: 열린 모든 연결의 모든 실행 문맥에 {@link QMTransactionState#isOpen()}(상태 줄·유휴 감시와 같은 규칙: DB가 무언가를
 *     쥐었다고 답했거나 명시적으로 시작한 트랜잭션, 자동 커밋 모드 포함. DB에 확인할 수 없는 것도 물으며 목록에 그렇게 적는다).
 *     AI 도우미·MCP 도구의 격리 문맥은 JDBC 연결을 직접 써서 쿼리 관리자에 기록이 없으므로 {@link AgentSql#hasPendingTransaction}으로
 *     따로 찾고, 같은 답으로 {@link AgentSql#endTransaction}이 끝낸다(도구의 커밋·롤백과 같은 경로).</li>
 *     <li>묻기: SWT는 문맥마다 물었지만 끝낼 때는 목록을 한 번에 보이고 답 하나를 모두에 적용한다(편집기 저장 질문과 같은 방식).
 *     취소하면 앱이 닫히지 않는다.</li>
 *     <li>끝내기: 다른 커밋·롤백과 같은 경로({@link IdleTransactionMonitor#commitOrRollback})를 진행 대화상자에서 한다. 하나라도 실패하면 오류를 보이고
 *     앱을 닫지 않는다(사용자가 직접 정하게).</li>
 *     <li>연결 끊기({@link #confirmDisconnect}): 연결을 끊으면 JDBC 연결을 닫으며 열린 트랜잭션이 롤백된다
 *     ({@code JDBCExecutionContext.close}). SWT는 끊기 전에 {@code DataSourceHandler.checkAndCloseActiveTransaction}으로 물었다.
 *     같은 찾기·질문·끝내기를 그 연결 하나에 한다. 취소하면 끊지 않고 트랜잭션도 그대로 둔다. 끊기, 다시 연결, 삭제,
 *     연결 편집 뒤 다시 연결이 모두 {@code ConnectionCommands}의 한 끊기 경로를 지나므로 그곳에서 부른다.</li>
 *     <li>연결이 끊긴 세션({@link TransactionRecoveryGuard#isSessionLost}): 커밋·롤백이 끊긴 연결에서 실패해 창을 닫거나 연결을 끊을 수 없었다.
 *     끊긴 세션의 트랜잭션은 서버가 이미 롤백했으므로 커밋·롤백 대신 버리기·취소를 묻는다. 살아 있는 세션과 섞이면 한 질문에서 살아 있는 세션은
 *     커밋·롤백을 묻고 끊긴 세션은 버린다고 적는다. 확인이 답하지 않아 살아 있다고 본 세션이 커밋·롤백에서 연결 끊김 오류를 내면 그때 버리기를 묻는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md "끝낼 때 트랜잭션")
 * - 2026-11-07 AI 에이전트 격리 문맥의 열린 트랜잭션도 찾아 같은 질문으로 끝냄(전에는 쿼리 관리자만 봐서 놓침, docs/chromite/ai-agent.md "트랜잭션 안전")
 * - 2026-11-07 연결 끊기 전 질문({@link #confirmDisconnect}): 끊기·다시 연결·삭제·편집 뒤 다시 연결이 열린 트랜잭션을 묻지 않고
 *   롤백하던 것을 막음. 찾기·질문·끝내기는 끝낼 때와 같은 코드 (docs/chromite/idle-transactions.md "연결 끊기 전 질문")
 * - 2026-11-07 쿼리 창 자기 세션이 끝나기 전 질문({@link #confirmEndSession}: 창 닫기, 연결 바꾸기, DB 인스턴스 바꾸기). 목록은 문맥 이름 대신
 *   그 문맥을 쓰는 쿼리 창 이름으로 보임(창마다 세션이 따로라 끊을 때 창별로 보임) (docs/chromite/transactions.md "쿼리 창마다 별도 연결")
 * - 2026-11-07 Object Explorer 개체 변경 전 질문({@link #confirmObjectChange}): 삭제·이름 바꾸기 DDL이 열린 트랜잭션을 함께 커밋하거나
 *   그 잠금을 기다리지 않게 (docs/chromite/swing-explorer-object-actions.md "트랜잭션")
 * - 2026-11-08 연결이 끊긴 세션은 커밋·롤백 대신 버리기·취소를 물음(전에는 실패해 창을 닫거나 끊을 수 없었음)
 *   (docs/chromite/transactions.md "끊긴 세션의 쿼리 창")
 * - 2026-11-08 Object Explorer Set as Default가 주 세션을 다른 데이터베이스 인스턴스로 옮기기 전 질문({@link #confirmDefaultChange})
 *   (docs/chromite/swing-explorer-productivity.md "기본 스키마")
 * - 2026-11-08 여러 연결 끊기(모두 끊기, 다른 연결 끊기, 서버 그룹 끊기)는 한 질문으로, 무효화·다시 연결(세션을 다시 엶) 전 질문
 *   ({@link #confirmReconnect}) (docs/chromite/connection-lifecycle.md)
 * - 2026-11-08 주석만: {@link #confirmObjectChange}를 새 개체 만들기와 테이블 디자이너 저장도 씀 (docs/chromite/swing-create-objects.md)
 * - 2026-11-08 열린 트랜잭션을 상태 줄과 같은 규칙(DB의 답)으로 찾음: 쥔 것 없는 SELECT만 한 세션은 묻지 않고, 자동 커밋 모드의
 *   BEGIN TRAN은 물음. 끝내기는 공용 경로(자동 커밋의 COMMIT/ROLLBACK 문장, 중첩 BEGIN TRAN). 확인할 수 없는 세션은 목록에 그렇게 적음
 *   (docs/chromite/idle-transactions.md "트랜잭션 상태")
 * - 2026-11-09 목록의 줄마다 종류를 적음(조회 트랜잭션: 읽기 잠금·스냅숏만, 변경 있음, 그 밖의 열린 트랜잭션). 둘 다 무언가를 쥐므로
 *   계속 묻는다 (docs/chromite/idle-transactions.md "조회만 한 트랜잭션 끝내기")
 * - 2026-11-12 주석만: 쿼리 창이 연결을 바꾸는 경우를 지움(쿼리 창은 한 서버의 창, 앱 변경 #69) (docs/chromite/swing-frontend.md "쿼리 창은 한 서버의 창")
 */
final class ExitTransactionCheck {

    private static final Log log = Log.getLog(ExitTransactionCheck.class);

    private ExitTransactionCheck() {
    }

    /**
     * @return true when Chromite may close: no open transaction, or each one was committed or rolled back as asked
     */
    static boolean confirm(@NotNull Component parent) {
        return confirm(parent, DataSourceRegistry.getAllDataSources(), list -> NLS.bind(UpdateMessages.exit_txn_message, list), UpdateMessages.exit_txn_error);
    }

    /**
     * Before the connection is disconnected (Disconnect, Reconnect, Delete, reconnect after editing): asks Commit /
     * Roll back / Cancel when it has transactions that are not committed. Call in the EDT.
     *
     * @return true when it may be disconnected: no open transaction, or each one was committed or rolled back as asked
     */
    static boolean confirmDisconnect(@Nullable Component parent, @NotNull DBPDataSourceContainer container) {
        return confirm(parent, List.of(container), list -> NLS.bind(ConnectionMessages.disconnect_txn_message, container.getName(), list),
            ConnectionMessages.disconnect_txn_error);
    }

    /**
     * Before several connections are disconnected at once (Disconnect All, Disconnect Others, a server group's
     * Disconnect): one question for all of them. Call in the EDT.
     *
     * @return true when they may be disconnected: no open transaction, or each one was committed or rolled back as asked
     */
    static boolean confirmDisconnect(@Nullable Component parent, @NotNull Collection<? extends DBPDataSourceContainer> containers) {
        return confirm(parent, containers, list -> NLS.bind(LifecycleMessages.disconnect_many_txn_message, list),
            ConnectionMessages.disconnect_txn_error);
    }

    /**
     * Before Invalidate/Reconnect reopens the sessions of the connections (the connection and those that share its
     * network tunnel): reopening closes the old JDBC connections, which ends their transactions. Call in the EDT.
     *
     * @return true when they may be reconnected: no open transaction, or each one was committed or rolled back as asked
     */
    static boolean confirmReconnect(@Nullable Component parent, @NotNull Collection<? extends DBPDataSourceContainer> containers) {
        return confirm(parent, containers, list -> NLS.bind(LifecycleMessages.reconnect_txn_message, list),
            LifecycleMessages.reconnect_txn_error);
    }

    /**
     * Before a query window's own session ends (the window closes or changes its database instance):
     * asks Commit / Roll back / Cancel when the session has a transaction that is not committed. Call in the EDT.
     *
     * @param windowName the window's file name
     * @return true when the session may end: no open transaction, or it was committed or rolled back as asked
     */
    static boolean confirmEndSession(@Nullable Component parent, @NotNull DBCExecutionContext context, @NotNull String windowName) {
        if (!QMUtils.getTransactionState(context).isOpen()) {
            return true;
        }
        return confirm(parent, List.of(context), List.of(), list -> NLS.bind(ConnectionMessages.window_txn_message, windowName, list),
            ConnectionMessages.window_txn_error);
    }

    /**
     * Before Object Explorer's Set as Default moves the connection's main session to another database instance (a
     * PostgreSQL database: the windows that share the main session then use another server session, and the open
     * transaction would stay behind unseen): asks Commit / Roll back / Cancel when the session has a transaction that is
     * not committed. Call in the EDT.
     *
     * @param target the database or schema that becomes the default
     * @return true when the default may change: no open transaction, or it was committed or rolled back as asked
     */
    static boolean confirmDefaultChange(@Nullable Component parent, @NotNull DBCExecutionContext context, @NotNull String target) {
        if (!QMUtils.getTransactionState(context).isOpen()) {
            return true;
        }
        DBPDataSourceContainer container = context.getDataSource().getContainer();
        return confirm(parent, List.of(context), List.of(),
            list -> NLS.bind(ObjectExplorerMessages.oe_set_default_txn_message, new Object[]{container.getName(), list, target}),
            ObjectExplorerMessages.oe_set_default_txn_error);
    }

    /**
     * Before Object Explorer or the table designer runs DDL on the connection (Delete, Rename, maintenance commands,
     * New object, designer Save): asks Commit / Roll back / Cancel when it has
     * transactions that are not committed. The model's command path commits after each command
     * ({@code AbstractCommandContext.saveChanges}) and some databases commit DDL implicitly, so an open transaction of the
     * same session would be committed with it; one of another session may hold locks the DDL waits for. Call in the EDT.
     *
     * @return true when the change may run: no open transaction, or each one was committed or rolled back as asked
     */
    static boolean confirmObjectChange(@Nullable Component parent, @NotNull DBPDataSourceContainer container) {
        return confirm(parent, List.of(container), list -> NLS.bind(NavigatorMessages.navigator_change_txn_message, container.getName(), list),
            NavigatorMessages.navigator_change_txn_error);
    }

    /**
     * @param message the question for the list of open transactions
     * @param error   the error of a failed commit or rollback, {@code {0}} = the connection
     */
    private static boolean confirm(
        @Nullable Component parent,
        @NotNull Collection<? extends DBPDataSourceContainer> containers,
        @NotNull Function<String, String> message,
        @NotNull String error
    ) {
        return confirm(parent, findActive(containers), findAgentActive(containers), message, error);
    }

    private static boolean confirm(
        @Nullable Component parent,
        @NotNull List<DBCExecutionContext> active,
        @NotNull List<DBPDataSourceContainer> agentActive,
        @NotNull Function<String, String> message,
        @NotNull String error
    ) {
        if (active.isEmpty() && agentActive.isEmpty()) {
            return true;
        }
        // Sessions whose connection is gone: Commit and Roll back cannot work there, and nothing is left to keep
        List<DBCExecutionContext> lost = findLost(active);
        List<DBCExecutionContext> live = new ArrayList<>(active);
        live.removeAll(lost);
        if (live.isEmpty() && agentActive.isEmpty()) {
            return confirmDiscard(parent, lost);
        }
        Set<String> names = new LinkedHashSet<>(describe(live));
        for (DBPDataSourceContainer container : agentActive) {
            names.add(NLS.bind(UpdateMessages.exit_txn_agent_item, container.getName()));
        }
        String question = message.apply(String.join("\n", names)); //$NON-NLS-1$
        if (!lost.isEmpty()) {
            question += NLS.bind(ConnectionMessages.lost_txn_note, String.join("\n", describe(lost))); //$NON-NLS-1$
        }
        String[] options = {UpdateMessages.exit_txn_commit, UpdateMessages.exit_txn_rollback, CoreMessages.common_cancel};
        int answer = JOptionPane.showOptionDialog(parent, question,
            UpdateMessages.exit_txn_title, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[2]);
        if (answer != 0 && answer != 1) {
            return false;
        }
        boolean commit = answer == 0;
        for (DBCExecutionContext context : live) {
            try {
                ProgressDialog.runTask(UpdateMessages.exit_txn_progress, monitor -> {
                    try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "End active transaction")) { //$NON-NLS-1$
                        IdleTransactionMonitor.commitOrRollback(session, commit);
                    }
                    return null;
                });
            } catch (DBException e) {
                if (isConnectionLost(context, e)) {
                    // The session was lost although the check did not tell (no answer in time, or lost since)
                    log.debug("The session of " + context.getDataSource().getContainer().getName() + " was lost: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                    if (!confirmDiscard(parent, List.of(context))) {
                        return false;
                    }
                    continue;
                }
                log.warn("Cannot end the transaction of " + context.getDataSource().getContainer().getName(), e); //$NON-NLS-1$
                ErrorDialog.show(parent, UpdateMessages.exit_txn_title,
                    NLS.bind(error, context.getDataSource().getContainer().getName()), e);
                return false;
            }
        }
        for (DBPDataSourceContainer container : agentActive) {
            try {
                ProgressDialog.runTask(UpdateMessages.exit_txn_progress, monitor -> AgentSql.endTransaction(monitor, container, commit));
            } catch (DBException e) {
                log.warn("Cannot end the AI agent transaction of " + container.getName(), e); //$NON-NLS-1$
                ErrorDialog.show(parent, UpdateMessages.exit_txn_title, NLS.bind(error, container.getName()), e);
                return false;
            }
        }
        discard(lost);
        return true;
    }

    /**
     * Asks Discard / Cancel for sessions whose connection was lost, and on Discard records that their transactions ended.
     *
     * @return true when they were discarded
     */
    private static boolean confirmDiscard(@Nullable Component parent, @NotNull List<DBCExecutionContext> lost) {
        String[] options = {ConnectionMessages.lost_txn_discard, CoreMessages.common_cancel};
        int answer = JOptionPane.showOptionDialog(parent,
            NLS.bind(ConnectionMessages.lost_txn_message, String.join("\n", describe(lost))), //$NON-NLS-1$
            UpdateMessages.exit_txn_title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (answer != 0) {
            return false;
        }
        discard(lost);
        return true;
    }

    private static void discard(@NotNull List<DBCExecutionContext> lost) {
        for (DBCExecutionContext context : lost) {
            log.info("Discarded the transaction of the lost session " + context.getDataSource().getContainer().getName() //$NON-NLS-1$
                + " (" + context.getContextName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            TransactionRecoveryGuard.discardLostTransaction(context);
        }
    }

    /**
     * The sessions among {@code active} whose server session is gone ({@link TransactionRecoveryGuard#isSessionLost}).
     * A session that cannot be checked counts as live, so it is asked about as before.
     */
    @NotNull
    private static List<DBCExecutionContext> findLost(@NotNull List<DBCExecutionContext> active) {
        if (active.isEmpty()) {
            return List.of();
        }
        try {
            return ProgressDialog.runTask(ConnectionMessages.lost_txn_checking, monitor -> {
                List<DBCExecutionContext> lost = new ArrayList<>();
                for (DBCExecutionContext context : active) {
                    if (TransactionRecoveryGuard.isSessionLost(monitor, context)) {
                        lost.add(context);
                    }
                }
                return lost;
            });
        } catch (DBException e) {
            log.debug("Cannot check the sessions: " + e.getMessage()); //$NON-NLS-1$
            return List.of();
        }
    }

    private static boolean isConnectionLost(@NotNull DBCExecutionContext context, @NotNull Throwable error) {
        for (Throwable cause = error; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (DBExecUtils.discoverErrorType(context.getDataSource(), cause) == DBPErrorAssistant.ErrorType.CONNECTION_LOST) {
                return true;
            }
        }
        return false;
    }

    /**
     * The sessions as listed in the questions: the connection and the query windows that run in the session (a window's
     * own session, or the main context), else the context's name.
     */
    @NotNull
    private static List<String> describe(@NotNull List<DBCExecutionContext> contexts) {
        List<String> names = new ArrayList<>();
        for (DBCExecutionContext context : contexts) {
            String windows = SwingIdleTransactionService.describeQueryWindows(context);
            String name = context.getDataSource().getContainer().getName() + " (" + (windows != null ? windows : context.getContextName()) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            QMTransactionState state = QMUtils.getTransactionState(context);
            String item;
            if (state.isUnknown()) {
                item = UpdateMessages.exit_txn_unknown_item;
            } else if (state.isReadOnly()) {
                item = UpdateMessages.exit_txn_read_only_item;
            } else if (state.getHolds().contains(DBCServerTransaction.Hold.CHANGES)) {
                item = UpdateMessages.exit_txn_changes_item;
            } else {
                item = UpdateMessages.exit_txn_open_item;
            }
            names.add(NLS.bind(item, name));
        }
        return names;
    }

    /**
     * Connections where the AI agent tools left a transaction open. The agent runs SQL on raw JDBC connections of its own
     * isolated contexts, so the query manager never sees those statements and {@link QMUtils#getTransactionState} is closed
     * for them; {@link AgentSql} keeps the state itself (asked from the database after each write).
     */
    @NotNull
    private static List<DBPDataSourceContainer> findAgentActive(@NotNull Collection<? extends DBPDataSourceContainer> containers) {
        List<DBPDataSourceContainer> active = new ArrayList<>();
        for (DBPDataSourceContainer container : containers) {
            if (container.isConnected() && AgentSql.hasPendingTransaction(container)) {
                active.add(container);
            }
        }
        return active;
    }

    @NotNull
    private static List<DBCExecutionContext> findActive(@NotNull Collection<? extends DBPDataSourceContainer> containers) {
        List<DBCExecutionContext> active = new ArrayList<>();
        for (DBPDataSourceContainer container : containers) {
            DBPDataSource dataSource = container.getDataSource();
            if (!container.isConnected() || dataSource == null) {
                continue;
            }
            try {
                for (DBSInstance instance : dataSource.getAvailableInstances()) {
                    for (DBCExecutionContext context : instance.getAllContexts()) {
                        if (QMUtils.getTransactionState(context).isOpen()) {
                            active.add(context);
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.debug("Cannot check the transactions of " + container.getName() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return active;
    }
}
