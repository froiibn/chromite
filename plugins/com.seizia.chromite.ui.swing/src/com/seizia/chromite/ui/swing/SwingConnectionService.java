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
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.runtime.DBRProgressListener;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBServiceConnections;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.jobs.ConnectJob;

/**
 * 모델 계층의 연결 요청({@link DBServiceConnections})을 처리한다.
 *
 * 설계: 탐색기 노드를 펼치면 {@code DBNDatabaseNode#getChildren} → {@code DBUtils.initDataSource}가 이 서비스로 연결을 요청한다.
 * SWT 버전은 {@code UIServiceConnectionsImpl}({@code com.seizia.chromite.core})가 이 일을 하는데 Swing 제품에는 그 번들이 없어서,
 * 서비스가 없으면 연결하지 않고 탐색기가 "Connection was canceled"로 끝난다. 그래서 같은 서비스를 {@code plugin.xml}의
 * {@code com.seizia.chromite.service} 확장으로 등록한다.
 * 동작은 SWT의 {@code DataSourceHandler.connectToDataSource}에서 UI 없는 부분만 옮겼다:
 * 이미 연결 중인 Job이 있으면 그것을 기다리고, 아니면 모델 계층의 {@link ConnectJob}으로 연결한다.
 * 모니터가 있으면(호출자가 이미 백그라운드 Job) 그 스레드에서 바로 연결하고, 없으면 Job을 예약한다.
 * 결과는 {@code onFinish}로 알리고, 받을 곳이 없으면 오류만 {@code DBPPlatformUI.showError}로 보여 준다.
 * 빠진 것: 공유 자격 증명 확인({@code DataSourceHandlerUtils.resolveSharedCredentials}, 팀/클라우드 기능),
 * 기능 사용 통계({@code ConnectionFeatures}), 전역 프록시 설정({@link #getGlobalProxyConfiguration}은 기본값 null).
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-07 연결 오류 대화상자 제목을 모델 Job 이름("Connect to '…'") 대신 지역화된 문구로 바꿈 (docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-08 드라이버 클래스를 찾지 못한 연결 오류는 드라이버 편집을 권하는 대화상자({@link DatabaseErrorDialogs#showConnectError})
 *   (docs/chromite/connection-lifecycle.md "연결 끊김·드라이버 오류 대화상자")
 */
public class SwingConnectionService implements DBServiceConnections {

    private static final Log log = Log.getLog(SwingConnectionService.class);

    @Override
    public void initConnection(
        @Nullable DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer dataSourceContainer,
        @Nullable DBRProgressListener onFinish
    ) {
        if (dataSourceContainer.isConnected()) {
            notifyFinished(onFinish, Status.OK_STATUS, null);
            return;
        }
        Job[] connectJobs = Job.getJobManager().find(dataSourceContainer);
        if (connectJobs.length > 0) {
            // Already connecting: wait for that job instead of opening a second connection
            IStatus lastResult = null;
            if (monitor != null && connectJobs.length == 1) {
                try {
                    connectJobs[0].join(0, monitor.getNestedMonitor());
                    lastResult = connectJobs[0] instanceof ConnectJob connectJob ? connectJob.getConnectStatus() : connectJobs[0].getResult();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.debug(e);
                }
            }
            if (lastResult == null) {
                lastResult = dataSourceContainer.isConnected() ? Status.OK_STATUS : Status.CANCEL_STATUS;
            }
            notifyFinished(onFinish, lastResult, null);
            return;
        }

        ConnectJob connectJob = new ConnectJob(dataSourceContainer);
        if (monitor != null) {
            connectJob.runSync(monitor);
            notifyFinished(onFinish, connectJob.getConnectStatus(), dataSourceContainer);
        } else {
            connectJob.addJobChangeListener(new JobChangeAdapter() {
                @Override
                public void done(IJobChangeEvent event) {
                    notifyFinished(onFinish, connectJob.getConnectStatus(), dataSourceContainer);
                }
            });
            connectJob.schedule();
        }
    }

    /**
     * Passes the result to the listener, or shows a connection error when nobody listens (as the SWT UI does).
     */
    private static void notifyFinished(
        @Nullable DBRProgressListener onFinish,
        @Nullable IStatus status,
        @Nullable DBPDataSourceContainer container
    ) {
        IStatus result = status != null ? status : Status.CANCEL_STATUS;
        if (onFinish != null) {
            onFinish.onTaskFinished(result);
        } else if (!result.isOK() && result.getSeverity() != IStatus.CANCEL && container != null) {
            // Localized title instead of the model job name ("Connect to '...'")
            String message = NLS.bind(ConnectionMessages.commands_connect_error, container.getName());
            // A missing driver class or license: the dialog that offers to edit the driver (the status no longer has the
            // connect exception that names the data source, so the connection is passed here)
            if (!DatabaseErrorDialogs.showConnectError(container, ConnectionMessages.commands_connect_title, message, result)) {
                DBWorkbench.getPlatformUI().showError(ConnectionMessages.commands_connect_title, message, result);
            }
        }
    }
}
