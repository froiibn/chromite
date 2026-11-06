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

import com.seizia.chromite.model.admin.ServerAdminProfile;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import javax.swing.*;
import java.util.function.Consumer;

/**
 * 관리 도구(Activity Monitor의 세션·잠금 탭)가 서버를 읽고 명령을 보내는 연결.
 *
 * 설계: SWT 세션 표({@code SessionTable.LoadSessionsService})는 읽을 때마다 격리된 실행 컨텍스트를 새로 열고 닫는다
 * ({@code DBSInstance#openIsolatedContext}, 연결 설정이 "단일 연결"이면 기본 컨텍스트). 자동 새로 고침(SSMS 기본 10초)마다
 * 서버 연결을 새로 만들면 비싸고 세션 목록에 매번 새 세션이 생기므로, 창 하나가 격리된 컨텍스트 하나를 열어 두고 창을 닫을 때 닫는다.
 * SQL 편집기의 트랜잭션과 섞이지 않는 점은 SWT와 같다.
 * <ul>
 *     <li>작업은 {@link AbstractJob}에서 하고 결과와 오류는 EDT로 넘긴다. 같은 컨텍스트를 쓰는 작업은 차례로 실행한다(JDBC 연결은 동시에 쓸 수 없다).</li>
 *     <li>연결이 아직 안 되어 있으면 SQL 편집기처럼 작업 안에서 {@link DBUtils#initDataSource}로 연결한다.</li>
 *     <li>컨텍스트가 끊겼으면(서버가 끊음, 재연결) 다음 작업에서 다시 연다.</li>
 *     <li>DB에 맞춘 도구({@link ServerAdminProfile})를 만든 뒤 첫 작업에서 로그인 권한을 확인한다({@link ServerAdminProfile#checkAccess}).
 *     두 탭이 같은 결과를 쓰고, 첫 읽기 결과가 화면에 닿을 때는 이미 확인되어 있다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 첫 작업에서 로그인 권한 확인(PostgreSQL·Oracle 세션·잠금, docs/chromite/swing-admin-tools.md)
 */
final class AdminConnection {

    private static final Log log = Log.getLog(AdminConnection.class);

    /**
     * Work done with a database session of the tool.
     */
    interface Task<T> {
        T run(@NotNull DBRProgressMonitor monitor, @NotNull DBCSession session, @NotNull ServerAdminProfile profile) throws DBException;
    }

    private final DBPDataSourceContainer container;
    private final Object lock = new Object();
    @Nullable
    private DBCExecutionContext isolatedContext;
    @Nullable
    private ServerAdminProfile profile;
    @Nullable
    private DBPDataSource profileDataSource;
    private volatile boolean closed;

    AdminConnection(@NotNull DBPDataSourceContainer container) {
        this.container = container;
    }

    @NotNull
    DBPDataSourceContainer getContainer() {
        return container;
    }

    /**
     * The tools of the database, once a task has connected; null before.
     */
    @Nullable
    ServerAdminProfile getProfile() {
        synchronized (lock) {
            return profile;
        }
    }

    /**
     * Runs a task in a background job.
     *
     * @param onResult called on the EDT with the result
     * @param onError  called on the EDT with the error
     */
    <T> void run(@NotNull String title, @NotNull Task<T> task, @NotNull Consumer<T> onResult, @NotNull Consumer<Throwable> onError) {
        new AbstractJob(title) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    T result = execute(monitor, title, task);
                    SwingUtilities.invokeLater(() -> {
                        if (!closed) {
                            onResult.accept(result);
                        }
                    });
                } catch (Throwable e) {
                    SwingUtilities.invokeLater(() -> {
                        if (!closed) {
                            onError.accept(e);
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private <T> T execute(@NotNull DBRProgressMonitor monitor, @NotNull String title, @NotNull Task<T> task) throws DBException {
        synchronized (lock) {
            if (closed) {
                throw new DBException("The window is closed");
            }
            if (!container.isConnected()) {
                DBUtils.initDataSource(monitor, container, null);
            }
            DBPDataSource dataSource = container.getDataSource();
            if (dataSource == null || !container.isConnected()) {
                // Shown in the status line of the Activity Monitor tabs
                throw new DBException(NLS.bind(ConnectionMessages.activity_not_connected, container.getName()));
            }
            if (profile == null || profileDataSource != dataSource) {
                // First task, or the connection was reopened (a new data source object)
                profile = ServerAdminProfile.forDataSource(dataSource);
                profileDataSource = dataSource;
                closeIsolatedContext();
            }
            DBCExecutionContext context = getContext(monitor, dataSource);
            try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, title)) {
                // Once per profile, before its first read: the tabs show what the login may do
                profile.checkAccess(session);
                return task.run(monitor, session, profile);
            } catch (DBException e) {
                // The connection may be broken (e.g. its own session was killed): open a new one next time
                closeIsolatedContext();
                throw e;
            }
        }
    }

    @NotNull
    private DBCExecutionContext getContext(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSource dataSource) throws DBException {
        if (container.isForceUseSingleConnection()) {
            return DBUtils.getDefaultContext(dataSource, false);
        }
        if (isolatedContext != null && (!isolatedContext.isConnected() || isolatedContext.getDataSource() != dataSource)) {
            closeIsolatedContext();
        }
        if (isolatedContext == null) {
            isolatedContext = dataSource.getDefaultInstance().openIsolatedContext(monitor, "Activity Monitor", null);
        }
        return isolatedContext;
    }

    private void closeIsolatedContext() {
        if (isolatedContext != null) {
            try {
                isolatedContext.close();
            } catch (Throwable e) {
                log.debug("Error closing the Activity Monitor context", e);
            }
            isolatedContext = null;
        }
    }

    /**
     * Closes the isolated context (the window is closing). Running tasks finish first.
     */
    void close() {
        closed = true;
        new AbstractJob("Close Activity Monitor connection") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                synchronized (lock) {
                    closeIsolatedContext();
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }
}
