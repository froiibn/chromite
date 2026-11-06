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
import com.seizia.chromite.model.DBPEvent;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 연결 설정에서 바꾼 자동 커밋을 이미 열려 있는 세션(실행 컨텍스트)에도 적용한다.
 *
 * 설계:
 * <ul>
 *     <li>사용자 결정(2026-11-07): 자동 커밋은 설정 화면(연결 편집의 Auto-commit)에서만 바꾸고, 바꾸면 다시 연결하지 않아도 열린 세션에 반영한다.
 *     모델은 연결 설정의 기본값을 컨텍스트를 열 때만 읽는다({@code JDBCExecutionContext.connect}의 {@code isDefaultAutoCommit}). 그래서 저장 뒤
 *     연결의 모든 인스턴스의 모든 컨텍스트(주 컨텍스트, 메타데이터, 관리 도구의 격리 컨텍스트)에 SWT 자동 커밋 전환
 *     ({@code DataSourceAutoCommitHandler})과 같은 {@link DBCTransactionManager#setAutoCommit}을 부른다. 다시 연결했을 때와 같은 결과다.</li>
 *     <li>안전: 자동 커밋을 켜면 JDBC가 열린 트랜잭션을 조용히 커밋한다. 그래서 먼저 쿼리 관리자의 트랜잭션 추적({@link QMUtils#isTransactionActive})으로
 *     커밋하지 않은 변경이 있는 컨텍스트를 찾고, 있으면 그 목록(쿼리 창 이름과 연결)을 보이고 "커밋하고 적용 / 롤백하고 적용 / 취소"를 묻는다.
 *     취소하면 열린 세션은 이전 방식 그대로이고 저장한 설정은 다음 연결부터 적용된다. 끄기는 묻지 않는다(트랜잭션이 생기지 않는다).</li>
 *     <li>JDBC 호출(현재 상태 읽기, 커밋·롤백, 전환)은 모두 작업 스레드에서 하고, 질문과 결과는 EDT에서 보인다.</li>
 *     <li>전환하지 못한 컨텍스트(드라이버 오류)는 창 이름과 함께 오류로 알린다. 끝나면 연결 갱신 이벤트를 보내 쿼리 창들이 실제 상태를 다시 읽는다
 *     ({@link SqlEditorPanel#refreshTransactionMode}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 연결 설정의 자동 커밋을 열린 세션에 적용(사용자 요청) (docs/chromite/swing-frontend.md "자동 커밋 상태")
 */
final class AutoCommitApplier {

    private static final Log log = Log.getLog(AutoCommitApplier.class);

    /**
     * An execution context whose commit mode differs from the setting.
     */
    private record Target(@NotNull DBCExecutionContext context, @NotNull DBCTransactionManager txnManager, @NotNull String label,
                          boolean pendingChanges) {
    }

    private enum Pending {
        COMMIT, ROLLBACK
    }

    private AutoCommitApplier() {
    }

    /**
     * Applies the connection's (saved) default commit mode to its open execution contexts, then runs {@code afterwards}
     * in the EDT (also when nothing was open or the user canceled).
     */
    static void apply(@Nullable Window owner, @NotNull DBPDataSourceContainer container, @NotNull Runnable afterwards) {
        boolean autoCommit = container.isDefaultAutoCommit();
        DBPDataSource dataSource = container.getDataSource();
        if (!container.isConnected() || dataSource == null) {
            afterwards.run();
            return;
        }
        // Names of the query windows, which all run in the connection's main context
        List<String> windows = new ArrayList<>();
        if (findMainFrame() instanceof MainFrame frame) {
            for (SqlEditorPanel editor : frame.getQueryWindows()) {
                if (editor.getDataSourceContainer() == container) {
                    // Without the modified marker of the tab title
                    String title = editor.getTitle();
                    windows.add(title.endsWith("*") ? title.substring(0, title.length() - 1) : title); //$NON-NLS-1$
                }
            }
        }
        new AbstractJob("Read commit mode of open sessions of " + container.getName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<Target> targets = findTargets(monitor, dataSource, autoCommit, windows);
                SwingUtilities.invokeLater(() -> confirmAndSwitch(owner, container, autoCommit, targets, afterwards));
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    @NotNull
    private static List<Target> findTargets(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSource dataSource, boolean autoCommit,
                                            @NotNull List<String> windows) {
        List<Target> targets = new ArrayList<>();
        DBCExecutionContext mainContext = DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false);
        for (DBSInstance instance : dataSource.getAvailableInstances()) {
            for (DBCExecutionContext context : instance.getAllContexts()) {
                DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
                if (txnManager == null) {
                    continue;
                }
                try {
                    if (txnManager.isAutoCommit() == autoCommit) {
                        continue;
                    }
                } catch (DBCException e) {
                    log.debug("Can't read the auto-commit state of " + context.getContextName(), e);
                }
                String label = context == mainContext && !windows.isEmpty() ? String.join(", ", windows)
                    : NLS.bind(ConnectionMessages.autocommit_other_session, context.getContextName(), dataSource.getContainer().getName());
                // Only switching to auto-commit ends a transaction
                targets.add(new Target(context, txnManager, label, autoCommit && QMUtils.isTransactionActive(context)));
            }
        }
        monitor.done();
        return targets;
    }

    private static void confirmAndSwitch(@Nullable Window owner, @NotNull DBPDataSourceContainer container, boolean autoCommit,
                                         @NotNull List<Target> targets, @NotNull Runnable afterwards) {
        if (targets.isEmpty()) {
            afterwards.run();
            return;
        }
        Pending pending = null;
        StringBuilder list = new StringBuilder();
        for (Target target : targets) {
            if (target.pendingChanges()) {
                list.append("\n  • ").append(target.label()); //$NON-NLS-1$
            }
        }
        if (!list.isEmpty()) {
            String[] options = {ConnectionMessages.autocommit_commit_apply, ConnectionMessages.autocommit_rollback_apply, ConnectionMessages.common_cancel};
            int choice = JOptionPane.showOptionDialog(owner, ConnectionMessages.autocommit_pending_message + "\n" + list //$NON-NLS-1$
                    + "\n\n" + ConnectionMessages.autocommit_pending_cancel_note, //$NON-NLS-1$
                ConnectionMessages.autocommit_title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[2]);
            if (choice == 0) {
                pending = Pending.COMMIT;
            } else if (choice == 1) {
                pending = Pending.ROLLBACK;
            } else {
                // Open sessions keep their mode; the saved setting applies from the next connect
                afterwards.run();
                return;
            }
        }
        Pending finalPending = pending;
        new AbstractJob("Apply auto-commit to open sessions of " + container.getName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<String> failures = new ArrayList<>();
                for (Target target : targets) {
                    try {
                        if (target.pendingChanges() && finalPending != null) {
                            try (DBCSession session = target.context().openSession(monitor, DBCExecutionPurpose.UTIL,
                                finalPending == Pending.COMMIT ? "Commit before auto-commit" : "Roll back before auto-commit")) {
                                if (finalPending == Pending.COMMIT) {
                                    target.txnManager().commit(session);
                                } else {
                                    target.txnManager().rollback(session, null);
                                }
                            }
                        }
                        target.txnManager().setAutoCommit(monitor, autoCommit);
                    } catch (Exception e) {
                        log.debug("Can't change auto-commit of " + target.context().getContextName(), e);
                        failures.add(target.label() + ": " + ErrorText.describe(e)); //$NON-NLS-1$
                    }
                }
                SwingUtilities.invokeLater(() -> {
                    if (!failures.isEmpty()) {
                        JOptionPane.showMessageDialog(owner, NLS.bind(ConnectionMessages.autocommit_error_message, container.getName())
                            + "\n\n" + String.join("\n", failures), //$NON-NLS-1$ //$NON-NLS-2$
                            ConnectionMessages.autocommit_title, JOptionPane.ERROR_MESSAGE);
                    }
                    // Query windows read the actual state again (connectionChanged)
                    container.getRegistry().notifyDataSourceListeners(new DBPEvent(DBPEvent.Action.OBJECT_UPDATE, container));
                    afterwards.run();
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    @Nullable
    private static Frame findMainFrame() {
        for (Frame frame : Frame.getFrames()) {
            if (frame instanceof MainFrame && frame.isDisplayable()) {
                return frame;
            }
        }
        return null;
    }
}
