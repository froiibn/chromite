/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KeepAlivePingJob
 *
 * 변경 이력:
 * - 2026-11-07 끊긴 문맥을 찾아도 다시 연결이 열린 트랜잭션을 끝내게 되면 다시 연결하지 않고 알림
 *   ({@link TransactionRecoveryGuard}, docs/chromite/transactions.md "오류 복구와 열린 트랜잭션")
 */
class KeepAlivePingJob extends AbstractJob {
    private static final Log log = Log.getLog(KeepAlivePingJob.class);

    private static final Map<String, Integer> failedAttempts = new HashMap<>();

    private final DBPDataSource dataSource;
    private final boolean disconnectOnError;

    KeepAlivePingJob(DBPDataSource dataSource, boolean disconnectOnError) {
        super("Connection ping (" + dataSource.getContainer().getName() + ")");
        setUser(false);
        setSystem(true);
        this.dataSource = dataSource;
        this.disconnectOnError = disconnectOnError;
    }

    @NotNull
    @Override
    protected IStatus run(@NotNull DBRProgressMonitor monitor) {
        Exception deadContextError = null;
        for (final DBSInstance instance : dataSource.getAvailableInstances()) {
            for (final DBCExecutionContext context : instance.getAllContexts()) {
                try {
                    context.checkContextAlive(monitor);
                } catch (Exception e) {
                    log.debug("Context [" + dataSource.getName() + "::" + context.getContextName() + "] check failed: " + e.getMessage());
                    deadContextError = e;
                    break;
                }
            }
        }
        if (deadContextError != null && TransactionRecoveryGuard.holdReconnect(dataSource, deadContextError, null, true)) {
            // Reconnecting would end open transactions without asking: leave the connection as it is, the user was told
            return Status.OK_STATUS;
        }
        if (deadContextError != null) {
            // Invalidate whole datasource. Do not log errors (as it can spam tons of logs)
            final List<InvalidateJob.ContextInvalidateResult> results = InvalidateJob.invalidateDataSource(
                monitor,
                dataSource,
                disconnectOnError,
                false,
                new DefaultInvalidationFeedbackHandler()
            );
            synchronized (failedAttempts) {
                String dsId = dataSource.getContainer().getId();
                if (InvalidateJob.anySucceeded(results) || disconnectOnError) {
                    log.debug("Datasource " + dataSource.getName() + " invalidated: " + results);
                    failedAttempts.remove(dsId);
                } else {
                    log.debug("Datasource " + dataSource.getName() + " invalidate failed: " + results);
                    Integer curAttempts = failedAttempts.get(dsId);
                    if (curAttempts == null) {
                        curAttempts = 1;
                    } else {
                        curAttempts++;
                    }
                    failedAttempts.put(dsId, curAttempts);
                }
            }
        }
        return Status.OK_STATUS;
    }

    public static int getFailedAttemptCount(DBPDataSource dataSource) {
        synchronized (failedAttempts) {
            Integer attempts = failedAttempts.get(dataSource.getContainer().getId());
            return attempts == null ? 0 : attempts;
        }
    }

}