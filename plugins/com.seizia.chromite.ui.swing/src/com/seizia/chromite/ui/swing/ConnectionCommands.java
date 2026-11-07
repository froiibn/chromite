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

import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.ErrorMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceFolder;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.navigator.DBNDataSource;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.registry.DataSourceDescriptor;
import com.seizia.chromite.runtime.DBServiceConnections;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.jobs.DisconnectJob;
import org.jkiss.utils.ArrayUtils;

import javax.swing.*;
import java.awt.*;

/**
 * 연결 명령: 새로 만들기, 편집, 이름 바꾸기, 삭제, 연결, 연결 끊기, 다시 연결. Object Explorer와 메뉴가 함께 쓴다.
 *
 * 설계: SWT의 명령 처리기({@code DataSourceHandler}, {@code DataSourceDescriptorManager.deleteObject},
 * {@code NavigatorHandlerObjectRename})에서 UI와 무관한 부분을 모델 코드로 그대로 따른다.
 * <ul>
 *     <li>연결: {@link SwingConnectionService}({@code DBServiceConnections})로 연결 Job을 예약한다(탐색기 펼치기와 같은 경로).</li>
 *     <li>연결 끊기: 모델의 {@link DisconnectJob}. 이미 연결·연결 끊기 중인 Job이 있으면 아무것도 하지 않는다
 *     ({@code DataSourceHandler.disconnectDataSource}와 같음). 끊으면 열린 트랜잭션이 롤백되므로 먼저 커밋/롤백/취소를 묻는다
 *     ({@link ExitTransactionCheck#confirmDisconnect}, SWT {@code checkAndCloseActiveTransaction}). 취소하면 끊지 않는다.
 *     다시 연결, 삭제, 연결 편집 뒤 다시 연결도 이 경로를 지난다.</li>
 *     <li>다시 연결: 연결 끊기가 끝난 뒤 연결한다({@code DataSourceHandler.reconnectDataSource}와 같음).</li>
 *     <li>삭제: 확인 뒤, 연결 중이면 끊고 나서 {@code DBPDataSourceRegistry.removeDataSource}(설정 파일 저장, 비밀 정보 삭제, 이벤트).</li>
 *     <li>이름 바꾸기: {@link DBNDataSource#rename}(이름 저장과 갱신 이벤트).</li>
 * </ul>
 * 결과는 모델 이벤트로 Object Explorer와 상태 표시줄에 반영된다. 화면을 직접 고치지 않는다.
 * SWT에서 끊기 전에 하던 열린 편집기 저장 확인은 아직 없다(Swing 편집기는 저장 기능이 없다).
 *
 * 변경 이력:
 * - 2026-11-06 D단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 * - 2026-11-06 Registered Servers: 새 연결 명령이 만든 연결을 돌려준다(docs/chromite/swing-registered-servers.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 연결 끊기 오류 대화상자 제목을 모델 Job 이름("Disconnect from '…'") 대신 지역화된 문구로 바꿈 (docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-07 {@link #editAutoCommit}: 연결 편집을 Auto-commit 칸에 초점을 두고 엶(쿼리 상태 줄의 커밋 방식 칸) (docs/chromite/swing-frontend.md "자동 커밋 상태")
 * - 2026-11-07 끊기 전 열린 트랜잭션 질문: 끊기·다시 연결·삭제가 수동 커밋의 열린 트랜잭션을 묻지 않고 롤백하던 것을 막음
 *   (docs/chromite/idle-transactions.md "연결 끊기 전 질문")
 */
final class ConnectionCommands {

    private ConnectionCommands() {
    }

    /**
     * @param folder server group of the new connection, or null for the top level
     * @return the new connection, or null if canceled
     */
    @Nullable
    static DBPDataSourceContainer newConnection(@Nullable Component parent, @Nullable DBPDataSourceFolder folder) {
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        if (project == null) {
            DBWorkbench.getPlatformUI().showError(ConnectionMessages.dialog_new_connection, ConnectionMessages.commands_no_project);
            return null;
        }
        return ConnectionDialog.openNew(getWindow(parent), project, folder);
    }

    static void edit(@Nullable Component parent, @NotNull DBPDataSourceContainer dataSource) {
        if (dataSource instanceof DataSourceDescriptor descriptor) {
            ConnectionDialog.openEdit(getWindow(parent), descriptor);
        }
    }

    /**
     * Edit Connection with the Auto-commit field focused: where the commit mode is changed.
     */
    static void editAutoCommit(@Nullable Component parent, @NotNull DBPDataSourceContainer dataSource) {
        if (dataSource instanceof DataSourceDescriptor descriptor) {
            ConnectionDialog.openEditAutoCommit(getWindow(parent), descriptor);
        }
    }

    static void connect(@NotNull DBPDataSourceContainer dataSource) {
        if (dataSource.isConnected()) {
            return;
        }
        DBServiceConnections service = DBWorkbench.getService(DBServiceConnections.class);
        if (service != null) {
            try {
                // Schedules a connect job; its errors are shown by the service
                service.initConnection(null, dataSource, null);
            } catch (DBException e) {
                DBWorkbench.getPlatformUI().showError(ConnectionMessages.commands_connect_title,
                    NLS.bind(ConnectionMessages.commands_connect_error, dataSource.getName()), e);
            }
        }
    }

    /**
     * @return false when the user canceled because of an open transaction (the connection stays)
     */
    static boolean disconnect(@NotNull DBPDataSourceContainer dataSource) {
        return disconnect(dataSource, null);
    }

    static void reconnect(@NotNull DBPDataSourceContainer dataSource) {
        if (dataSource.isConnected()) {
            disconnect(dataSource, () -> connect(dataSource));
        } else {
            connect(dataSource);
        }
    }

    /**
     * Asks for confirmation, disconnects the connection if needed and removes it from its project.
     */
    static void delete(@Nullable Component parent, @NotNull DBPDataSourceContainer dataSource) {
        int answer = JOptionPane.showConfirmDialog(
            getWindow(parent),
            NLS.bind(ConnectionMessages.commands_delete_confirm, dataSource.getName()),
            ConnectionMessages.commands_delete_title,
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        Runnable remover = () -> dataSource.getRegistry().removeDataSource(dataSource);
        if (dataSource.isConnected()) {
            disconnect(dataSource, remover);
        } else {
            remover.run();
        }
    }

    static void rename(@Nullable Component parent, @NotNull DBNDataSource node) {
        DBPDataSourceContainer dataSource = node.getDataSourceContainer();
        Object input = JOptionPane.showInputDialog(
            getWindow(parent),
            ConnectionMessages.commands_rename_prompt,
            ConnectionMessages.commands_rename_title,
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            dataSource.getName());
        String newName = input == null ? null : input.toString().trim();
        if (newName == null || newName.isEmpty() || newName.equals(dataSource.getName())) {
            return;
        }
        try {
            // Only changes and saves the connection configuration: no database access
            node.rename(new VoidProgressMonitor(), newName);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ConnectionMessages.commands_rename_title,
                NLS.bind(ConnectionMessages.commands_rename_error, dataSource.getName()), e);
        }
    }

    /**
     * @param onFinish runs after the disconnect job (in the job's thread); without it errors are shown
     * @return false when the user canceled because of an open transaction (nothing is done, onFinish does not run)
     */
    private static boolean disconnect(@NotNull DBPDataSourceContainer dataSource, @Nullable Runnable onFinish) {
        if (!dataSource.isConnected()) {
            if (onFinish != null) {
                onFinish.run();
            }
            return true;
        }
        if (!ArrayUtils.isEmpty(Job.getJobManager().find(dataSource))) {
            // Already connecting or disconnecting
            return true;
        }
        // Closing the sessions rolls back their open transactions: ask first (user rule "transaction safety")
        if (!ExitTransactionCheck.confirmDisconnect(KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(), dataSource)) {
            return false;
        }
        DisconnectJob disconnectJob = new DisconnectJob(dataSource);
        disconnectJob.addJobChangeListener(new JobChangeAdapter() {
            @Override
            public void done(IJobChangeEvent event) {
                IStatus result = disconnectJob.getConnectStatus();
                if (onFinish != null) {
                    onFinish.run();
                } else if (result != null && !result.isOK()) {
                    // Localized title instead of the model job name ("Disconnect from '...'")
                    DBWorkbench.getPlatformUI().showError(CoreMessages.explorer_disconnect,
                        NLS.bind(ErrorMessages.error_disconnect, dataSource.getName()), result);
                }
            }
        });
        disconnectJob.schedule();
        return true;
    }

    @Nullable
    private static Window getWindow(@Nullable Component component) {
        return component == null ? null : component instanceof Window window ? window : SwingUtilities.getWindowAncestor(component);
    }
}
