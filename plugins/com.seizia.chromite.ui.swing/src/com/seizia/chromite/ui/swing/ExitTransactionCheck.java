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
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.ai.tools.AgentSql;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.registry.DataSourceRegistry;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.NavigatorMessages;
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
 * disconnected (Disconnect, Reconnect, Delete, reconnect after editing it), and before Object Explorer changes database
 * objects (Delete, Rename): asks what to do with the transactions that are not committed — Commit / Roll back / Cancel.
 * Nothing is committed or rolled back silently.
 *
 * 설계: 사용자 규칙 "트랜잭션 안전"(2026-11-07): 세션을 닫는 기능은 커밋하지 않은 트랜잭션을 먼저 찾아 커밋/롤백/취소를 묻는다.
 * 끝낼 때 플랫폼이 모든 연결을 닫으므로 그 전에 묻는다. SWT의 {@code ApplicationWorkbenchAdvisor.closeActiveTransactions}와
 * {@code DataSourceHandler.checkAndCloseActiveTransaction}(com.seizia.chromite.ui.editors.connection, #68에서 삭제)을 옮겼다.
 * <ul>
 *     <li>찾기: 열린 모든 연결의 모든 실행 문맥에 {@link QMUtils#isTransactionActive}(쿼리 관리자가 본 트랜잭션 안의 실행이 있음).
 *     AI 도우미·MCP 도구의 격리 문맥은 JDBC 연결을 직접 써서 쿼리 관리자에 기록이 없으므로 {@link AgentSql#hasPendingTransaction}으로
 *     따로 찾고, 같은 답으로 {@link AgentSql#endTransaction}이 끝낸다(도구의 커밋·롤백과 같은 경로).</li>
 *     <li>묻기: SWT는 문맥마다 물었지만 끝낼 때는 목록을 한 번에 보이고 답 하나를 모두에 적용한다(편집기 저장 질문과 같은 방식).
 *     취소하면 앱이 닫히지 않는다.</li>
 *     <li>끝내기: {@link DBCTransactionManager#commit}/{@code rollback}을 진행 대화상자에서 한다. 하나라도 실패하면 오류를 보이고
 *     앱을 닫지 않는다(사용자가 직접 정하게).</li>
 *     <li>연결 끊기({@link #confirmDisconnect}): 연결을 끊으면 JDBC 연결을 닫으며 열린 트랜잭션이 롤백된다
 *     ({@code JDBCExecutionContext.close}). SWT는 끊기 전에 {@code DataSourceHandler.checkAndCloseActiveTransaction}으로 물었다.
 *     같은 찾기·질문·끝내기를 그 연결 하나에 한다. 취소하면 끊지 않고 트랜잭션도 그대로 둔다. 끊기, 다시 연결, 삭제,
 *     연결 편집 뒤 다시 연결이 모두 {@code ConnectionCommands}의 한 끊기 경로를 지나므로 그곳에서 부른다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md "끝낼 때 트랜잭션")
 * - 2026-11-07 AI 에이전트 격리 문맥의 열린 트랜잭션도 찾아 같은 질문으로 끝냄(전에는 쿼리 관리자만 봐서 놓침, docs/chromite/ai-agent.md "트랜잭션 안전")
 * - 2026-11-07 연결 끊기 전 질문({@link #confirmDisconnect}): 끊기·다시 연결·삭제·편집 뒤 다시 연결이 열린 트랜잭션을 묻지 않고
 *   롤백하던 것을 막음. 찾기·질문·끝내기는 끝낼 때와 같은 코드 (docs/chromite/idle-transactions.md "연결 끊기 전 질문")
 * - 2026-11-07 Object Explorer 개체 변경 전 질문({@link #confirmObjectChange}): 삭제·이름 바꾸기 DDL이 열린 트랜잭션을 함께 커밋하거나
 *   그 잠금을 기다리지 않게 (docs/chromite/swing-explorer-object-actions.md "트랜잭션")
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
     * Before Object Explorer runs DDL on the connection (Delete, Rename): asks Commit / Roll back / Cancel when it has
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
        List<DBCExecutionContext> active = findActive(containers);
        List<DBPDataSourceContainer> agentActive = findAgentActive(containers);
        if (active.isEmpty() && agentActive.isEmpty()) {
            return true;
        }
        Set<String> names = new LinkedHashSet<>();
        for (DBCExecutionContext context : active) {
            names.add(context.getDataSource().getContainer().getName() + " (" + context.getContextName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (DBPDataSourceContainer container : agentActive) {
            names.add(NLS.bind(UpdateMessages.exit_txn_agent_item, container.getName()));
        }
        String[] options = {UpdateMessages.exit_txn_commit, UpdateMessages.exit_txn_rollback, CoreMessages.common_cancel};
        int answer = JOptionPane.showOptionDialog(parent, message.apply(String.join("\n", names)), //$NON-NLS-1$
            UpdateMessages.exit_txn_title, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[2]);
        if (answer != 0 && answer != 1) {
            return false;
        }
        boolean commit = answer == 0;
        for (DBCExecutionContext context : active) {
            try {
                ProgressDialog.runTask(UpdateMessages.exit_txn_progress, monitor -> {
                    try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "End active transaction")) { //$NON-NLS-1$
                        DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
                        if (txnManager != null) {
                            if (commit) {
                                txnManager.commit(session);
                            } else {
                                txnManager.rollback(session, null);
                            }
                        }
                    }
                    return null;
                });
            } catch (DBException e) {
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
        return true;
    }

    /**
     * Connections where the AI agent tools left a transaction open. The agent runs SQL on raw JDBC connections of its own
     * isolated contexts, so the query manager never sees those statements and {@link QMUtils#isTransactionActive} is false
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
                        if (QMUtils.isTransactionActive(context)) {
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
