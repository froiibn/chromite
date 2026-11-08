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
package com.seizia.chromite.model.runtime;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.utils.GeneralUtils;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract job
 *
 * 변경 이력:
 * - 2026-11-08 취소가 듣지 않으면 {@code execute.cancel.checkTimeout} 뒤 바깥 블록(세션)까지 취소하던 것을 지움. 세션 단위 취소는
 *   연결과 열린 트랜잭션을 묻지 않고 잃을 수 있어 트랜잭션 안전 규칙에 어긋나고, 설정 파일이나 설정 가져오기로 다시 켜지지 않게 키를 읽지
 *   않는다 (docs/chromite/swing-options.md "Errors and Timeouts")
 */
public abstract class AbstractJob extends Job {
    private static final Log log = Log.getLog(AbstractJob.class);

    private DBRProgressMonitor progressMonitor;
    private volatile boolean finished = false;
    private volatile boolean blockCanceled = false;
    private volatile long cancelTimestamp = -1;
    private AbstractJob attachedJob = null;
    private boolean skipErrorOnCanceling;
    private volatile boolean runDirectly = false;

    // Attached job may be used to "overwrite" current job.
    // It happens if some other AbstractJob runs in sync mode
    protected final static ThreadLocal<AbstractJob> CURRENT_JOB = new ThreadLocal<>();

    protected AbstractJob(@NotNull String name)
    {
        super(name);
    }

    public boolean isFinished() {
        return finished;
    }

    private boolean isSkipErrorOnCanceling() {
        return skipErrorOnCanceling;
    }

    protected void setSkipErrorOnCanceling(boolean skipErrorOnCanceling) {
        this.skipErrorOnCanceling = skipErrorOnCanceling;
    }

    public void setAttachedJob(@Nullable AbstractJob attachedJob) {
        this.attachedJob = attachedJob;
    }

    public final IStatus runDirectly(@NotNull DBRProgressMonitor monitor) {
        progressMonitor = monitor;
        blockCanceled = false;
        runDirectly = true;
        try {
            finished = false;
            IStatus result;
            try {
                result = this.run(progressMonitor);
            } catch (Throwable e) {
                result = GeneralUtils.makeExceptionStatus(e);
            }
            return result;
        } finally {
            finished = true;
        }
    }

    @Override
    @NotNull
    protected final IStatus run(@NotNull IProgressMonitor monitor) {
        progressMonitor = RuntimeUtils.makeMonitor(monitor);
        blockCanceled = false;
        CURRENT_JOB.set(this);
        final Thread currentThread = Thread.currentThread();
        final String oldThreadName = currentThread.getName();
        try {
            finished = false;
            RuntimeUtils.setThreadName(getName());

            IStatus result = this.run(progressMonitor);
            if (!logErrorStatus(result)) {
                if (!result.isOK() && result != Status.CANCEL_STATUS) {
                    log.error("Error running job '" + getName() + "' execution: " + result.getMessage());
                }
            }
            return result;
        } catch (Throwable e) {
            log.error(e);
            return GeneralUtils.makeExceptionStatus(e);
        } finally {
            CURRENT_JOB.remove();
            finished = true;
            currentThread.setName(oldThreadName);
        }
    }

    public final void schedule(@NotNull Duration delay) {
        schedule(delay.toMillis());
    }

    private boolean logErrorStatus(@NotNull IStatus status) {
        if (status.getException() != null) {
            log.error("Error during job '" + getName() + "' execution", status.getException());
            return true;
        } else if (status instanceof MultiStatus) {
            for (IStatus cStatus : status.getChildren()) {
                if (logErrorStatus(cStatus)) {
                    return true;
                }
            }
        }
        return false;
    }

    @NotNull
    protected abstract IStatus run(@NotNull DBRProgressMonitor monitor);

    public boolean isCanceled() {
        return cancelTimestamp > 0;
    }

    public long getCancelTimestamp() {
        return cancelTimestamp;
    }

    @Override
    protected void canceling() {
        if (cancelTimestamp == -1) {
            cancelTimestamp = System.currentTimeMillis();
        }
        if (attachedJob != null) {
            attachedJob.canceling();
            return;
        }
        // Run canceling job
        if (!blockCanceled) {
            runBlockCanceler();
        }
    }

    public boolean isForceCancel() {
        return true;
    }

    @Nullable
    protected DBRProgressMonitor getCancellationMonitor() {
        return progressMonitor;
    }

    private void runBlockCanceler() {
        DBRProgressMonitor cancellationMonitor = getCancellationMonitor();
        if (cancellationMonitor == null) {
            return;
        }
        final List<DBRBlockingObject> activeBlocks = new ArrayList<>(
            CommonUtils.safeList(cancellationMonitor.getActiveBlocks()));
        if (activeBlocks.isEmpty()) {
            // Nothing to cancel
            return;
        }

        if (!isForceCancel() && activeBlocks.size() < 2) {
            return;
        }

        final DBRBlockingObject lastBlock = activeBlocks.removeLast();

        // Only the innermost block (the statement) is canceled. Upstream could escalate to the outer blocks (the session)
        // after execute.cancel.checkTimeout; Chromite removed that because a session-level cancel can drop the connection
        // and its open transaction without asking.
        try {
            new JobCanceler(lastBlock, cancellationMonitor).schedule();
        } catch (Exception e) {
            // If this happens during shutdown and job manager is not active
            log.debug(e);
        }
    }

    private class JobCanceler extends Job {
        private final DBRBlockingObject block;
        private final DBRProgressMonitor cancellationMonitor;

        public JobCanceler(DBRBlockingObject block, DBRProgressMonitor cancellationMonitor) {
            super("Operation cancel"); //$NON-N LS-1$
            this.block = block;
            this.cancellationMonitor = cancellationMonitor;
            setSystem(true);
            setUser(false);
        }

        @Override
        @NotNull
        protected IStatus run(@NotNull IProgressMonitor monitor) {
            if (!finished) {
                try {
                    BlockCanceler.cancelBlock(cancellationMonitor, block);
                } catch (DBException e) {
                    log.debug("Block cancel error", e); //$NON-N LS-1$
                    if (!isSkipErrorOnCanceling()) {
                        return GeneralUtils.makeExceptionStatus(e);
                    }
                } catch (Throwable e) {
                    log.debug("Block cancel internal error: " + e.getMessage()); //$NON-N LS-1$
                    return Status.CANCEL_STATUS;
                }
                blockCanceled = true;
            }
            return Status.OK_STATUS;
        }
    }

    public boolean isRunDirectly() {
        return runDirectly;
    }
}
