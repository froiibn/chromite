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
package com.seizia.chromite.registry.task;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.runtime.DBRRunnable;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.runtime.DBRRunnableWithProgress;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.model.task.DBTTaskRun;
import com.seizia.chromite.utils.GeneralUtils;
import org.jkiss.utils.StandardConstants;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.UUID;

/** File-backed logging for a task executed outside the managed task runner. */
public final class TaskExecutionLog implements DBRRunnableContext, AutoCloseable {
    private static final Log log = Log.getLog(TaskExecutionLog.class);

    private final DBTTask task;
    private final DBTTaskRun run;
    private final DBRRunnableContext context;
    private final PrintStream logStream;

    private TaskExecutionLog(
        @NotNull DBTTask task,
        @NotNull DBTTaskRun run,
        @NotNull DBRRunnableContext context,
        @NotNull PrintStream logStream
    ) {
        this.task = task;
        this.run = run;
        this.context = context;
        this.logStream = logStream;
    }

    @Nullable
    public static TaskExecutionLog open(@NotNull DBTTask task, @NotNull DBRRunnableContext context) {
        TaskRunImpl run = new TaskRunImpl(
            UUID.randomUUID().toString(), new Date(),
            System.getProperty(StandardConstants.ENV_USER_NAME), GeneralUtils.getProductTitle(), null, null);
        Path logFile = task.getRunLog(run);
        if (logFile == null) {
            return null;
        }
        try {
            Files.createDirectories(logFile.getParent());
            PrintStream stream = new PrintStream(Files.newOutputStream(logFile), true, StandardCharsets.UTF_8);
            stream.println("Task '" + task.getName() + "' started at " + run.getStartTime());
            return new TaskExecutionLog(task, run, context, stream);
        } catch (IOException e) {
            // Logging is optional and must not prevent the task from running.
            log.warn("Error preparing task execution log", e);
            return null;
        }
    }

    @NotNull
    public DBTTaskRun getRun() {
        return run;
    }

    @NotNull
    public PrintStream getLogStream() {
        return logStream;
    }

    /** Captures failures propagated outside a progress runnable, after its log writer has been restored. */
    public void execute(@NotNull DBRRunnable operation) throws DBException {
        try {
            operation.run();
        } catch (Throwable e) {
            PrintStream oldLogWriter = Log.getLogWriter();
            Log.setLogWriter(logStream);
            try {
                log.error("Task execution failed", e);
            } finally {
                Log.setLogWriter(oldLogWriter);
            }
            throw e;
        }
    }

    @Override
    public void run(boolean fork, boolean cancelable, @NotNull DBRRunnableWithProgress runnable)
        throws InvocationTargetException, InterruptedException {
        context.run(fork, cancelable, monitor -> {
            // Runnable contexts can fork onto a worker; install the thread-local writer there.
            PrintStream oldLogWriter = Log.getLogWriter();
            Log.setLogWriter(logStream);
            try {
                runnable.run(new TaskLoggingProgressMonitor(monitor, task));
            } catch (InvocationTargetException e) {
                log.error("Task execution failed", e.getTargetException());
                throw e;
            } finally {
                Log.setLogWriter(oldLogWriter);
            }
        });
    }

    @Override
    public void close() {
        logStream.println("Task '" + task.getName() + "' finished at " + new Date());
        logStream.close();
    }
}
