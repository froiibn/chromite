/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.jkiss.code.NotNull;
import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPMessageType;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.app.DBPWorkspace;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPConnectionType;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ChromiteNotifications;
import com.seizia.chromite.runtime.OperationSystemState;

import java.util.*;

/**
 * DataSourceMonitorJob.
 *
 * Performs connection keep-alive ping.
 * Ends idle transactions.
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
        checkDataSourceAliveInWorkspace(workspace, getLastUserActivityTime(lastPingTime));
    }

    protected void checkDataSourceAliveInWorkspace(DBPWorkspace workspace, long lastUserActivityTime) {
        List<DBPProject> projects = new ArrayList<>(workspace.getProjects());
        for (DBPProject project : projects) {
            if (project.isOpen() && project.isRegistryLoaded()) {
                DBPDataSourceRegistry dataSourceRegistry = project.getDataSourceRegistry();
                List<DBPDataSourceContainer> dataSources = new ArrayList<>(dataSourceRegistry.getDataSources());
                for (DBPDataSourceContainer ds : dataSources) {
                    checkDataSourceAlive(ds, lastUserActivityTime);
                }
            }
        }
    }

    private void checkDataSourceAlive(final DBPDataSourceContainer dataSourceDescriptor, long lastUserActivityTime) {
        if (!dataSourceDescriptor.isConnected()) {
            return;
        }

        final String dsId = dataSourceDescriptor.getId();
        synchronized (this) {
            if (pingCache.contains(dsId)) {
                // Ping is still in progress. Hanged?
                // Anyway - just skip it
                return;
            }
        }

        // End long transactions or connections
        if (getDisconnectTimeoutSeconds(dataSourceDescriptor) > 0 || getTransactionTimeoutSeconds(dataSourceDescriptor) > 0) {
            if (endIdleTransactionOrConnection(dataSourceDescriptor, lastUserActivityTime)) {
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

    private boolean endIdleTransactionOrConnection(DBPDataSourceContainer dsDescriptor, long lastUserActivityTime) {
        if (!dsDescriptor.isConnected()) {
            return false;
        }

        if (lastUserActivityTime < 0) {
            return false;
        }

        final long idleInterval = (System.currentTimeMillis() - lastUserActivityTime) / 1000;
        final long disconnectTimeoutSeconds = getDisconnectTimeoutSeconds(dsDescriptor);
        final long rollbackTimeoutSeconds = getTransactionTimeoutSeconds(dsDescriptor);

        DBPDataSource dataSource = dsDescriptor.getDataSource();

        if (dataSource != null && disconnectTimeoutSeconds > 0 && idleInterval > disconnectTimeoutSeconds) {
            if (DisconnectJob.isInProcess(dsDescriptor)) {
                return false;
            }
            // Kill idle connection
            DisconnectJob disconnectJob = new DisconnectJob(dsDescriptor);
            disconnectJob.schedule();

            showNotification(dataSource);
            return true;
        }

        if (dataSource != null && rollbackTimeoutSeconds > 0 && idleInterval > rollbackTimeoutSeconds) {
            if (EndIdleTransactionsJob.isInProcess(dsDescriptor) || DBExecUtils.isExecutionInProgress(dataSource)) {
                return false;
            }
            try {
                Map<DBCExecutionContext, DBCTransactionManager> txnToEnd = new IdentityHashMap<>();
                for (DBSInstance instance : dataSource.getAvailableInstances()) {
                    for (DBCExecutionContext ec : instance.getAllContexts()) {
                        if (ec.isConnected()) {
                            DBCTransactionManager txnManager = DBUtils.getTransactionManager(ec);
                            if (txnManager != null && txnManager.isSupportsTransactions() && !txnManager.isAutoCommit()) {
                                QMTransactionState txnState = QMUtils.getTransactionState(ec);
                                if (txnState.getUpdateCount() > 0 && txnState.getTransactionStartTime() <= lastUserActivityTime) {
                                    txnToEnd.put(ec, txnManager);
                                }
                            }
                        }
                    }
                }

                if (!txnToEnd.isEmpty()) {
                    new EndIdleTransactionsJob(dataSource, txnToEnd).schedule();
                }
            } catch (DBCException e) {
                log.error(e);
            }
            return true;
        }

        return false;
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

    public static long getTransactionTimeoutSeconds(@NotNull DBPDataSourceContainer container) {
        final DBPPreferenceStore pref = container.getPreferenceStore();
        final DBPConnectionConfiguration config = container.getConnectionConfiguration();
        int ttlSeconds = 0;

        if (pref.contains(ModelPreferences.TRANSACTIONS_AUTO_CLOSE_ENABLED)) {
            // First check datasource settings from the "Transactions" preference page
            ttlSeconds = pref.getInt(ModelPreferences.TRANSACTIONS_AUTO_CLOSE_TTL);
        }

        if (ttlSeconds == 0) {
            // Or get this info from the current connection type
            final DBPConnectionType connectionType = config.getConnectionType();
            if (connectionType.isAutoCloseTransactions()) {
                ttlSeconds = connectionType.getCloseIdleTransactionPeriod();
            }
        }

        return Math.max(0, ttlSeconds);
    }

    public static long getLastUserActivityTime() {
        return getLastUserActivityTime(-1);
    }

    public static long getLastUserActivityTime(long lastUserActivityTime) {
        long lat = DBWorkbench.getPlatform().getApplication().getLastUserActivityTime();
        if (lat <= 0) {
            return lastUserActivityTime;
        }
        return lat;
    }

    protected void showNotification(@NotNull DBPDataSource dataSource) {
        ChromiteNotifications.showNotification(
            dataSource,
            ChromiteNotifications.NT_DISCONNECT_IDLE,
            "Connection '" + dataSource.getContainer().getName() + "' has been closed after long idle period",
            DBPMessageType.ERROR
        );
    }
}