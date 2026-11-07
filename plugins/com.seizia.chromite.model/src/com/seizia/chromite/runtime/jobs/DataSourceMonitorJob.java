/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceTask;
import com.seizia.chromite.model.DBPMessageType;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.app.DBPWorkspace;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPConnectionType;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.runtime.DBServiceUnrecordedSessions;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ChromiteNotifications;
import com.seizia.chromite.runtime.OperationSystemState;
import com.seizia.chromite.utils.DurationFormat;
import com.seizia.chromite.utils.DurationFormatter;

import java.time.Duration;
import java.util.*;

/**
 * DataSourceMonitorJob.
 *
 * Performs connection keep-alive ping.
 * Watches idle transactions ({@link IdleTransactionMonitor}).
 * Closes idle connections ({@link #isIdleConnection}).
 *
 * 설계(유휴 연결 닫기): 유휴 시간은 연결마다 마지막으로 쓴 때부터 잰다. 업스트림은 전역 마우스·키보드 활동 시각
 * ({@code DBPApplication.getLastUserActivityTime})을 썼는데, Swing에는 그 값이 없어 유휴 시간이 늘 직전 점검(3초 전)부터였고
 * 그래서 닫지 않았다. 전역 활동 대신 연결별 사용 시각을 쓴 것은 유휴 트랜잭션 감시와 같은 이유다: 다른 연결로 일하는 동안 놓인
 * 연결도 닫혀야 한다. 열린 트랜잭션이 있는 연결은 닫지 않는다(닫으면 묻지 않고 롤백된다, 사용자 규칙 "트랜잭션 안전").
 * 그 트랜잭션은 {@link IdleTransactionMonitor}가 알린다. 닫기 직전에 한 번 더 확인하고, 닫은 뒤 알림을 보낸다.
 *
 * 변경 이력:
 * - 2026-11-07 유휴 트랜잭션: 업스트림의 롤백 경로(전역 사용자 활동 시각 기준, 확인 서비스가 없으면 묻지 않고 롤백,
 *   데이터 소스 설정 {@code transaction.auto.close.*})를 지우고 {@link IdleTransactionMonitor}를 부름 (docs/chromite/idle-transactions.md)
 * - 2026-11-07 유휴 연결 닫기가 동작하게 함: 전역 사용자 활동 시각 대신 연결별 마지막 사용 시각, 열린 트랜잭션·실행 중이면 닫지 않음,
 *   닫기 직전 다시 확인, 닫은 뒤 경고 알림(지역화 문구, 연결이 끊긴 뒤이므로 데이터 소스 없이 보냄)
 *   (docs/chromite/idle-transactions.md "유휴 연결 닫기")
 */
public class DataSourceMonitorJob extends AbstractJob {
    private static final int MONITOR_INTERVAL = 3000; // once per 3 seconds

    private static final Log log = Log.getLog(DataSourceMonitorJob.class);

    private static final int MAX_FAILED_ATTEMPTS_BEFORE_DISCONNECT = 5;
    private static final int MAX_FAILED_ATTEMPTS_BEFORE_IGNORE = 10;
    // Triggers datasources invalidate after sleep
    // Disabled because we use different approach - close connections on sleep
    private static final boolean INVALIDATE_AFTER_SLEEP = true;
    private static final long SYSTEM_SUSPEND_INTERVAL = 20000; // 20 seconds of inactivity - most likely a system suspend

    private final DBPPlatform platform;
    private final Map<String, Long> checkCache = new HashMap<>();
    private final Set<String> pingCache = new HashSet<>();
    private long lastPingTime = -1;
    private boolean isSleeping = false;

    public DataSourceMonitorJob(DBPPlatform platform) {
        super("Connections monitoring");
        setUser(false);
        setSystem(true);
        this.platform = platform;
    }

    @NotNull
    @Override
    protected IStatus run(@NotNull DBRProgressMonitor monitor) {
        if (platform.isShuttingDown()) {
            return Status.OK_STATUS;
        }
        boolean invalidateOnSleep = DBWorkbench.getPlatform().getPreferenceStore().getBoolean(ModelPreferences.CONNECTION_CLOSE_ON_SLEEP);
        boolean wasSleeping = isSleeping;
        isSleeping = OperationSystemState.isInSleepMode();

        if (!isSleeping) {
            if (invalidateOnSleep && lastPingTime > 0 && System.currentTimeMillis() - lastPingTime > SYSTEM_SUSPEND_INTERVAL) {
                if (INVALIDATE_AFTER_SLEEP) {
                    invalidateSleptConnections(monitor);
                }
            }

            doJob();
        } else if (!wasSleeping) {
            // Sleep mode triggered
            if (invalidateOnSleep) {
                // Disconnect all datasources
                closeAllConnections(monitor);
            }
        }
        lastPingTime = System.currentTimeMillis();
        if (!platform.isShuttingDown()) {
            scheduleMonitor();
        }
        return Status.OK_STATUS;
    }

    private void closeAllConnections(DBRProgressMonitor monitor) {
        log.debug("System suspend detected. Close all remote connections.");
        final DBPWorkspace workspace = platform.getWorkspace();
        for (DBPProject project : new ArrayList<>(workspace.getProjects())) {
            if (project.isOpen() && project.isRegistryLoaded()) {
                for (DBPDataSourceContainer ds : project.getDataSourceRegistry().getDataSources()) {
                    if (ds.isConnected() && !ds.getDriver().isEmbedded()) {
                        log.debug("Close connection '" + ds.getName() + "' for sleep mode");
                        try {
                            ds.disconnect(monitor);
                        } catch (Exception e) {
                            log.debug("Error closing connection in sleep mode");
                        }
                    }
                }
            }
        }
    }

    private void invalidateSleptConnections(DBRProgressMonitor monitor) {
        if (Platform.inDevelopmentMode()) {
            return;
        }
        log.debug("System awake detected. Reinitialize all remote connections.");

        Set<DBPDataSource> invalidated = new HashSet<>();

        final DBPWorkspace workspace = platform.getWorkspace();
        for (DBPProject project : new ArrayList<>(workspace.getProjects())) {
            if (project.isOpen() && project.isRegistryLoaded()) {
                DBPDataSourceRegistry dataSourceRegistry = project.getDataSourceRegistry();
                List<DBPDataSourceContainer> dataSources = new ArrayList<>(dataSourceRegistry.getDataSources());
                for (DBPDataSourceContainer ds : dataSources) {
                    if (ds.isConnected() && !ds.getDriver().isEmbedded()) {
                        DBPDataSource dataSource = ds.getDataSource();
                        if (dataSource != null && !invalidated.contains(dataSource)) {
                            log.debug("Invalidate connection '" + ds.getName() + "'");
                            List<InvalidateJob.ContextInvalidateResult> results = InvalidateJob.invalidateDataSource(
                                monitor,
                                dataSource,
                                true,
                                true,
                                null);
                            for (InvalidateJob.ContextInvalidateResult result : results) {
                                invalidated.add(result.getDataSource());
                            }
                        }
                    }
                }
            }
        }

    }

    protected void doJob() {
        final DBPWorkspace workspace = platform.getWorkspace();
        IdleTransactionMonitor.beginPass();
        checkDataSourceAliveInWorkspace(workspace);
        IdleTransactionMonitor.sweep();
    }

    protected void checkDataSourceAliveInWorkspace(DBPWorkspace workspace) {
        List<DBPProject> projects = new ArrayList<>(workspace.getProjects());
        for (DBPProject project : projects) {
            if (project.isOpen() && project.isRegistryLoaded()) {
                DBPDataSourceRegistry dataSourceRegistry = project.getDataSourceRegistry();
                List<DBPDataSourceContainer> dataSources = new ArrayList<>(dataSourceRegistry.getDataSources());
                for (DBPDataSourceContainer ds : dataSources) {
                    checkDataSourceAlive(ds);
                }
            }
        }
    }

    private void checkDataSourceAlive(final DBPDataSourceContainer dataSourceDescriptor) {
        if (!dataSourceDescriptor.isConnected()) {
            return;
        }

        IdleTransactionMonitor.check(dataSourceDescriptor);

        final String dsId = dataSourceDescriptor.getId();
        synchronized (this) {
            if (pingCache.contains(dsId)) {
                // Ping is still in progress. Hanged?
                // Anyway - just skip it
                return;
            }
        }

        // End idle connections
        if (getDisconnectTimeoutSeconds(dataSourceDescriptor) > 0) {
            if (endIdleConnection(dataSourceDescriptor)) {
                return;
            }
        }

        // Perform keep alive request
        final int keepAliveInterval = dataSourceDescriptor.getConnectionConfiguration().getKeepAliveInterval();
        if (keepAliveInterval <= 0) {
            return;
        }

        final DBPDataSource dataSource = dataSourceDescriptor.getDataSource();
        if (dataSource == null) {
            return;
        }
        Long lastCheckTime;
        synchronized (this) {
            lastCheckTime = checkCache.get(dsId);
        }
        if (lastCheckTime == null) {
            final Date connectTime = dataSourceDescriptor.getConnectTime();
            if (connectTime != null) {
                lastCheckTime = connectTime.getTime();
            }
        }
        if (lastCheckTime == null) {
            log.debug("Can't determine last check time for " + dsId);
            return;
        }
        long curTime = System.currentTimeMillis();
        if ((curTime - lastCheckTime) / 1000 > keepAliveInterval) {
            boolean disconnectOnError = false;
            int failedAttemptCount = KeepAlivePingJob.getFailedAttemptCount(dataSource);
            if (failedAttemptCount >= MAX_FAILED_ATTEMPTS_BEFORE_IGNORE) {
                return;
            }
            if (failedAttemptCount > MAX_FAILED_ATTEMPTS_BEFORE_DISCONNECT) {
                disconnectOnError = true;
            }
            final KeepAlivePingJob pingJob = new KeepAlivePingJob(dataSource, disconnectOnError);
            pingJob.addJobChangeListener(new JobChangeAdapter() {
                @Override
                public void done(IJobChangeEvent event) {
                    synchronized (DataSourceMonitorJob.this) {
                        checkCache.put(dsId, System.currentTimeMillis());
                        pingCache.remove(dsId);
                    }
                }
            });
            synchronized (this) {
                pingCache.add(dsId);
            }
            pingJob.schedule();
        }
    }

    private boolean endIdleConnection(@NotNull DBPDataSourceContainer container) {
        final long timeoutSeconds = getDisconnectTimeoutSeconds(container);
        // A connect or disconnect job of the connection is scheduled or running
        if (DisconnectJob.isInProcess(container) || Job.getJobManager().find(container).length > 0
            || !isIdleConnection(container, timeoutSeconds)) {
            return false;
        }
        DisconnectJob disconnectJob = new DisconnectJob(container) {
            @Override
            protected IStatus updateDataSource(DBRProgressMonitor monitor) {
                // Checked again: a statement or a transaction may have started since the check
                if (!isIdleConnection(container, timeoutSeconds)) {
                    return Status.OK_STATUS;
                }
                log.debug("Close idle connection '" + container.getName() + "'");
                IStatus result = super.updateDataSource(monitor);
                if (!container.isConnected()) {
                    showNotification(container, timeoutSeconds);
                }
                return result;
            }
        };
        disconnectJob.schedule();
        return true;
    }

    /**
     * The connection was not used for longer than the timeout, and nothing would be lost by closing it.
     * <ul>
     *     <li>Last use: the latest of its connection time and of the last use of each of its sessions as the query manager saw
     *     it ({@link QMUtils#getLastUseTime}: statements of any purpose, commit, rollback), and of the sessions the query
     *     manager does not see ({@link DBServiceUnrecordedSessions}, the AI agent). The keep-alive ping is not a use.</li>
     *     <li>Never idle while a session has an open transaction: closing the JDBC connection would roll it back without
     *     asking. {@link IdleTransactionMonitor} reminds about it (or rolls it back when the user turned that on), after
     *     which the idle time starts again from the commit or rollback.</li>
     *     <li>Never idle while a statement or a task (SQL editor execution, data editing) works on it.</li>
     * </ul>
     */
    private static boolean isIdleConnection(@NotNull DBPDataSourceContainer container, long timeoutSeconds) {
        DBPDataSource dataSource = container.getDataSource();
        Date connectTime = container.getConnectTime();
        if (timeoutSeconds <= 0 || dataSource == null || connectTime == null) {
            return false;
        }
        long lastUse = connectTime.getTime();
        for (DBSInstance instance : dataSource.getAvailableInstances()) {
            for (DBCExecutionContext context : instance.getAllContexts()) {
                QMTransactionState transaction = QMUtils.getTransactionState(context);
                if (transaction.isOpen() || transaction.isExecuting() || QMUtils.isTransactionActive(context)
                    || Job.getJobManager().find(context).length > 0) {
                    return false;
                }
                lastUse = Math.max(lastUse, QMUtils.getLastUseTime(context));
            }
        }
        for (DBPDataSourceTask task : container.getTasks()) {
            if (task.isActiveTask()) {
                return false;
            }
        }
        DBServiceUnrecordedSessions unrecorded = DBWorkbench.findService(DBServiceUnrecordedSessions.class);
        if (unrecorded != null) {
            if (unrecorded.isConnectionInUse(container)) {
                return false;
            }
            lastUse = Math.max(lastUse, unrecorded.getLastUseTime(container));
        }
        return System.currentTimeMillis() - lastUse > timeoutSeconds * 1000;
    }

    public void scheduleMonitor() {
        schedule(MONITOR_INTERVAL);
    }

    public static long getDisconnectTimeoutSeconds(@NotNull DBPDataSourceContainer container) {
        DBPConnectionConfiguration config = container.getConnectionConfiguration();
        if (!config.isCloseIdleConnection()) {
            return 0;
        }
        final int timeout = config.getCloseIdleInterval();
        if (timeout > 0) {
            return timeout;
        }
        final DBPConnectionType connectionType = config.getConnectionType();
        if (connectionType.isAutoCloseConnections()) {
            return connectionType.getCloseIdleConnectionPeriod();
        }
        return 0;
    }

    private static void showNotification(@NotNull DBPDataSourceContainer container, long timeoutSeconds) {
        ChromiteNotifications.showNotification(
            ChromiteNotifications.NT_DISCONNECT_IDLE,
            container.getName(),
            NLS.bind(ModelMessages.model_connection_idle_closed,
                DurationFormatter.format(Duration.ofSeconds(timeoutSeconds), DurationFormat.LONG)),
            DBPMessageType.WARNING,
            null
        );
    }
}