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
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.registry.DataSourceRegistry;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.UpdateMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Before Chromite closes (window close, File &gt; Exit, [Restart now] of an update): asks what to do with the
 * transactions that are not committed — Commit / Roll back / Cancel. Nothing is committed or rolled back silently.
 *
 * 설계: 사용자 규칙 "트랜잭션 안전"(2026-11-07): 세션을 닫는 기능은 커밋하지 않은 트랜잭션을 먼저 찾아 커밋/롤백/취소를 묻는다.
 * 끝낼 때 플랫폼이 모든 연결을 닫으므로 그 전에 묻는다. SWT의 {@code ApplicationWorkbenchAdvisor.closeActiveTransactions}와
 * {@code DataSourceHandler.checkAndCloseActiveTransaction}(com.seizia.chromite.ui.editors.connection, #68에서 삭제)을 옮겼다.
 * <ul>
 *     <li>찾기: 열린 모든 연결의 모든 실행 문맥에 {@link QMUtils#isTransactionActive}(쿼리 관리자가 본 트랜잭션 안의 실행이 있음).</li>
 *     <li>묻기: SWT는 문맥마다 물었지만 끝낼 때는 목록을 한 번에 보이고 답 하나를 모두에 적용한다(편집기 저장 질문과 같은 방식).
 *     취소하면 앱이 닫히지 않는다.</li>
 *     <li>끝내기: {@link DBCTransactionManager#commit}/{@code rollback}을 진행 대화상자에서 한다. 하나라도 실패하면 오류를 보이고
 *     앱을 닫지 않는다(사용자가 직접 정하게).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md "끝낼 때 트랜잭션")
 */
final class ExitTransactionCheck {

    private static final Log log = Log.getLog(ExitTransactionCheck.class);

    private ExitTransactionCheck() {
    }

    /**
     * @return true when Chromite may close: no open transaction, or each one was committed or rolled back as asked
     */
    static boolean confirm(@NotNull Component parent) {
        List<DBCExecutionContext> active = findActive();
        if (active.isEmpty()) {
            return true;
        }
        Set<String> names = new LinkedHashSet<>();
        for (DBCExecutionContext context : active) {
            names.add(context.getDataSource().getContainer().getName() + " (" + context.getContextName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String[] options = {UpdateMessages.exit_txn_commit, UpdateMessages.exit_txn_rollback, CoreMessages.common_cancel};
        int answer = JOptionPane.showOptionDialog(parent, NLS.bind(UpdateMessages.exit_txn_message, String.join("\n", names)), //$NON-NLS-1$
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
                    NLS.bind(UpdateMessages.exit_txn_error, context.getDataSource().getContainer().getName()), e);
                return false;
            }
        }
        return true;
    }

    @NotNull
    private static List<DBCExecutionContext> findActive() {
        List<DBCExecutionContext> active = new ArrayList<>();
        for (DBPDataSourceContainer container : DataSourceRegistry.getAllDataSources()) {
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
