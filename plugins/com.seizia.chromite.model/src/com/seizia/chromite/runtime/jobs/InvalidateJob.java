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
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPExclusiveResource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.*;
import com.seizia.chromite.model.net.DBWNetworkHandler;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.runtime.DBWorkbench;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Invalidate datasource job.
 * Invalidates all datasource contexts (not just the one passed in constructor).
 *
 * 변경 이력:
 * - 2026-11-07 {@link #collectDependentDataSources}를 패키지 안에 공개: 자동 복구 전에 다시 연결이 끝낼 열린 트랜잭션을 같은
 *   범위에서 찾기 위해({@link TransactionRecoveryGuard}, docs/chromite/transactions.md "오류 복구와 열린 트랜잭션")
 * - 2026-11-08 {@link #collectDependentDataSources}를 공개: Swing의 무효화·다시 연결 명령이 다시 열 연결 모두의 열린 트랜잭션을
 *   먼저 묻기 위해 (docs/chromite/connection-lifecycle.md "무효화·다시 연결")
 * - 2026-11-08 결과를 마지막 단계만이 아니라 모든 단계로 셈({@code mergeResults}): 다시 열지 못한 문맥(INVALIDATE 실패)이
 *   AFTER_INVALIDATE 성공 때문에 "다시 연결함"(n/n)으로 알려지고 호출자도 성공으로 보던 것 (docs/chromite/connection-lifecycle.md "무효화·다시 연결")
 */
public class InvalidateJob extends DataSourceJob {
    private static final Log log = Log.getLog(InvalidateJob.class);

    private static final String TASK_INVALIDATE = "dsInvalidate";

    public static class ContextInvalidateResult {

        private final DBPDataSource dataSource;
        private final Exception exception;

        public ContextInvalidateResult(@NotNull DBPDataSource dataSource) {
            this.dataSource = dataSource;
            this.exception = null;
        }

        public ContextInvalidateResult(@NotNull DBPDataSource dataSource, @NotNull Exception exception) {
            this.dataSource = dataSource;
            this.exception = exception;
        }

        @NotNull
        public static ContextInvalidateResult newSuccess(@NotNull DBPDataSource dataSource) {
            return new ContextInvalidateResult(dataSource);
        }

        @NotNull
        public static ContextInvalidateResult newError(@NotNull DBPDataSource dataSource, @NotNull Exception exception) {
            return new ContextInvalidateResult(dataSource, exception);
        }

        @NotNull
        public DBPDataSource getDataSource() {
            return dataSource;
        }

        @Nullable
        public Exception getException() {
            return exception;
        }

        public boolean isSuccess() {
            return exception == null;
        }

        public boolean isError() {
            return exception != null;
        }

        @Override
        public String toString() {
            return exception == null ? "Success" : exception.getMessage();
        }
    }

    public interface InvalidationFeedbackHandler {
        boolean confirmInvalidate(@NotNull Set<DBPDataSourceContainer> containersToInvalidate);

        void onInvalidateSuccess(
            @NotNull DBPDataSourceContainer container,
            @NotNull Collection<ContextInvalidateResult> results
        );

        void onInvalidateFailure(
            @NotNull DBPDataSourceContainer container,
            @NotNull Collection<ContextInvalidateResult> results
        );
    }

    private List<ContextInvalidateResult> invalidateResults = new ArrayList<>();
    private InvalidationFeedbackHandler feedbackHandler;

    public InvalidateJob(@NotNull DBPDataSource dataSource) {
        super(
            "Invalidate " + dataSource.getContainer().getName(),
            DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false)
        );
    }

    @NotNull
    public List<ContextInvalidateResult> getInvalidateResults() {
        return invalidateResults;
    }

    public void setFeedbackHandler(@Nullable InvalidationFeedbackHandler feedbackHandler) {
        this.feedbackHandler = feedbackHandler;
    }

    @NotNull
    @Override
    protected IStatus run(@NotNull DBRProgressMonitor monitor) {
        DBPDataSource dataSource = getExecutionContext().getDataSource();

        // Disable disconnect on failure. It is the worst case anyway.
        // Not sure that we should force disconnect even here.
        this.invalidateResults = invalidateDataSource(monitor, dataSource, false, true, feedbackHandler);

        return Status.OK_STATUS;
    }

    @NotNull
    public static List<ContextInvalidateResult> invalidateDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        boolean disconnectOnFailure,
        boolean showErrors,
        @Nullable InvalidationFeedbackHandler feedbackHandler
    ) {
        final Set<DBPDataSourceContainer> containers = new LinkedHashSet<>();
        collectDependentDataSources(dataSource, containers);

        final Map<DBPDataSourceContainer, Object> locks = new HashMap<>();
        for (var it = containers.iterator(); it.hasNext(); ) {
            DBPDataSourceContainer container = it.next();
            monitor.subTask("Obtain exclusive datasource lock for '" + container.getName() + "'");

            final Object lock = container.getExclusiveLock().acquireTaskLock(TASK_INVALIDATE, true);
            if (lock == DBPExclusiveResource.TASK_PROCESED) {
                log.debug("Datasource '" + container.getName() + "' was already invalidated");
                it.remove();
            } else {
                locks.put(container, lock);
            }
        }

        if (locks.isEmpty()) {
            log.debug("Nothing to invalidate");
            monitor.done();
            return List.of();
        }

        try {
            if (feedbackHandler == null || feedbackHandler.confirmInvalidate(containers)) {
                return invalidateDataSources0(monitor, containers, disconnectOnFailure, showErrors, feedbackHandler);
            } else {
                log.debug("Invalidate cancelled by user");
                return List.of();
            }
        } finally {
            monitor.subTask("Release exclusive datasource locks");
            for (Map.Entry<DBPDataSourceContainer, Object> entry : locks.entrySet()) {
                entry.getKey().getExclusiveLock().releaseTaskLock(TASK_INVALIDATE, entry.getValue());
            }

            monitor.done();
        }
    }

    @NotNull
    private static List<ContextInvalidateResult> invalidateDataSources0(
        @NotNull DBRProgressMonitor monitor,
        @NotNull Set<DBPDataSourceContainer> containers,
        boolean disconnectOnFailure,
        boolean showErrors,
        @Nullable InvalidationFeedbackHandler feedbackHandler
    ) {
        monitor.beginTask("Invalidate data sources", containers.size());

        // The result of each network handler and each context over all phases: a failure in any phase stays a failure.
        // Each phase lists them in the same order. Before, only the last phase counted, and a context that could not be
        // reopened (INVALIDATE failed) was reported as reconnected because nothing fails in AFTER_INVALIDATE.
        final Map<DBPDataSourceContainer, List<ContextInvalidateResult>> handlerResults = new LinkedHashMap<>();
        final Map<DBPDataSourceContainer, List<ContextInvalidateResult>> instanceResults = new LinkedHashMap<>();

        for (DBCInvalidatePhase phase : DBCInvalidatePhase.values()) {
            enum Severity {
                FINE,
                SEVERE
            }

            final Map<DBPDataSourceContainer, Severity> failed = new HashMap<>();

            for (DBPDataSourceContainer container : containers) {
                if (failed.get(container) == Severity.SEVERE) {
                    continue;
                }

                DBPDataSource dataSource = container.getDataSource();
                if (dataSource == null) {
                    continue;
                }

                var results = invalidateNetworkHandlers(monitor, dataSource, phase);

                if (anyFailed(results)) {
                    failed.put(container, Severity.SEVERE);
                }

                mergeResults(handlerResults, container, results);
            }

            for (DBPDataSourceContainer container : containers) {
                if (failed.containsKey(container)) {
                    continue;
                }

                DBPDataSource dataSource = container.getDataSource();
                if (dataSource == null) {
                    continue;
                }
                var results = invalidateInstances(monitor, dataSource, phase);
                List<ContextInvalidateResult> merged = mergeResults(instanceResults, container, results);

                if (anyFailed(results)) {
                    failed.put(container, Severity.FINE);
                }

                if (disconnectOnFailure && allFailed(results)) {
                    // Close whole datasource. Target host seems to be unavailable
                    try {
                        container.disconnect(monitor);
                    } catch (Exception e) {
                        log.error("Error closing inaccessible datasource", e);
                    }
                    String errors = results.stream()
                        .filter(ContextInvalidateResult::isError)
                        .map(result ->
                            result.getException() == null ? "Unknow error" : result.getException().getMessage())
                        .collect(Collectors.joining("\n"));
                    DBWorkbench.getPlatformUI().showError(
                        "Forced disconnect",
                        "Datasource '" + container.getName() +
                            "' was disconnected: destination database unreachable.\n" + errors
                    );
                }

                if (phase == DBCInvalidatePhase.AFTER_INVALIDATE) {
                    if (feedbackHandler != null) {
                        if (anySucceeded(merged)) {
                            feedbackHandler.onInvalidateSuccess(container, merged);
                        } else if (showErrors) {
                            feedbackHandler.onInvalidateFailure(container, merged);
                        }
                    }

                    monitor.worked(1);
                }
            }
        }

        monitor.done();

        final List<ContextInvalidateResult> finalResults = new ArrayList<>();
        handlerResults.values().forEach(finalResults::addAll);
        instanceResults.values().forEach(finalResults::addAll);
        return finalResults;
    }

    /**
     * Combines a phase's results of a container with those of the earlier phases, position by position (the same
     * handler or context): an earlier failure is kept.
     *
     * @return the combined results of the container
     */
    @NotNull
    private static List<ContextInvalidateResult> mergeResults(
        @NotNull Map<DBPDataSourceContainer, List<ContextInvalidateResult>> all,
        @NotNull DBPDataSourceContainer container,
        @NotNull Collection<ContextInvalidateResult> results
    ) {
        List<ContextInvalidateResult> previous = all.get(container);
        List<ContextInvalidateResult> merged = new ArrayList<>(results);
        if (previous != null && previous.size() == merged.size()) {
            for (int i = 0; i < merged.size(); i++) {
                if (previous.get(i).isError()) {
                    merged.set(i, previous.get(i));
                }
            }
        }
        all.put(container, merged);
        return merged;
    }

    /**
     * The data source and the data sources that use its network handlers (an SSH tunnel shared by several connections):
     * the ones whose contexts an invalidation reopens.
     */
    public static void collectDependentDataSources(
        @NotNull DBPDataSource dataSource,
        @NotNull Set<DBPDataSourceContainer> result
    ) {
        final Deque<DBPDataSourceContainer> pending = new ArrayDeque<>();
        pending.add(dataSource.getContainer());

        while (!pending.isEmpty()) {
            final DBPDataSourceContainer container = pending.remove();
            if (!result.add(container)) {
                continue;
            }
            for (DBWNetworkHandler handler : container.getActiveNetworkHandlers()) {
                final DBPDataSourceContainer[] dataSources = handler.getDependentDataSources();
                for (DBPDataSourceContainer other : dataSources) {
                    pending.offer(other);
                }
            }
        }
    }

    @NotNull
    private static Collection<ContextInvalidateResult> invalidateNetworkHandlers(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull DBCInvalidatePhase phase
    ) {
        final List<ContextInvalidateResult> results = new ArrayList<>();

        monitor.subTask("Invalidate network connection");

        for (DBWNetworkHandler nh : dataSource.getContainer().getActiveNetworkHandlers()) {
            log.debug("\tInvalidate network handler '" + nh.getClass().getSimpleName() + "' for " + dataSource.getContainer().getId());
            monitor.subTask("Invalidate handler [" + nh.getClass().getSimpleName() + "]");

            try {
                nh.invalidateHandler(monitor, dataSource, phase);
                results.add(ContextInvalidateResult.newSuccess(dataSource));
            } catch (Exception e) {
                results.add(ContextInvalidateResult.newError(dataSource, e));
            }
        }

        return results;
    }

    @NotNull
    private static Collection<ContextInvalidateResult> invalidateInstances(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull DBCInvalidatePhase phase
    ) {
        List<ContextInvalidateResult> results = new ArrayList<>();

        monitor.subTask("Invalidate connections of " + dataSource.getContainer().getName());

        for (DBSInstance instance : dataSource.getAvailableInstances()) {
            for (DBCExecutionContext context : instance.getAllContexts()) {
                log.debug("\tInvalidate context '" + context.getContextName()
                    + "' for " + dataSource.getContainer().getId() + " (" + phase + ")");

                final Object exclusiveLock = instance.getExclusiveLock().acquireExclusiveLock();
                try {
                    context.invalidateContext(monitor, phase);
                    results.add(ContextInvalidateResult.newSuccess(dataSource));
                } catch (Exception e) {
                    log.debug("\tFailed: " + e.getMessage());
                    results.add(ContextInvalidateResult.newError(dataSource, e));
                } finally {
                    instance.getExclusiveLock().releaseExclusiveLock(exclusiveLock);
                }
            }
        }

        return results;
    }

    public static void invalidateTransaction(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @Nullable DBCExecutionContext executionContext
    ) {
        // Invalidate transactions
        if (executionContext != null) {
            monitor.subTask("Invalidate context [" + executionContext.getDataSource().getContainer().getName() +
                "/" + executionContext.getContextName() + "] transactions");
            invalidateTransaction(monitor, executionContext);
        } else {
            monitor.subTask("Invalidate datasource [" + dataSource.getContainer().getName() + "] transactions");
            for (DBSInstance instance : dataSource.getAvailableInstances()) {
                for (DBCExecutionContext context : instance.getAllContexts()) {
                    invalidateTransaction(monitor, context);
                }
            }
        }
    }

    public static void invalidateTransaction(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context
    ) {
        DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
        if (txnManager != null) {
            try {
                if (!txnManager.isAutoCommit()) {
                    try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Rollback failed transaction")) {
                        // Disable logging to avoid QM handlers notifications.
                        // These notifications may trigger smart commit mode during txn recover. See #9066
                        session.enableLogging(false);
                        txnManager.rollback(session, null);
                    }
                }
            } catch (DBException e) {
                log.error("Error invalidating aborted transaction", e);
            }
        }
    }

    public static boolean allSucceeded(@NotNull Collection<ContextInvalidateResult> results) {
        return results.stream().allMatch(ContextInvalidateResult::isSuccess);
    }

    public static boolean anySucceeded(@NotNull Collection<ContextInvalidateResult> results) {
        return results.stream().anyMatch(ContextInvalidateResult::isSuccess);
    }

    public static boolean allFailed(@NotNull Collection<ContextInvalidateResult> results) {
        return results.stream().allMatch(ContextInvalidateResult::isError);
    }

    public static boolean anyFailed(@NotNull Collection<ContextInvalidateResult> results) {
        return results.stream().anyMatch(ContextInvalidateResult::isError);
    }

    public static int getSucceededCount(@NotNull Collection<ContextInvalidateResult> results) {
        return (int) results.stream().filter(ContextInvalidateResult::isSuccess).count();
    }

    @Override
    protected void canceling()
    {
        getThread().interrupt();
    }

}