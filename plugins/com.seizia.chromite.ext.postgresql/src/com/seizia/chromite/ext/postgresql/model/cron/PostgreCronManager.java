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
package com.seizia.chromite.ext.postgresql.model.cron;

import com.seizia.chromite.Log;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads and changes pg_cron jobs: the job list, run history, databases for the dialog, and the execution of
 * {@code cron.schedule} / {@code cron.alter_job} / {@code cron.unschedule} calls.
 *
 * 설계: SQL Server 에이전트의 {@code SQLServerAgentManager}와 같은 역할이다. 읽기는 데이터베이스의 메타 세션이나 탭의 연결에서 하고,
 * 바꾸는 문은 그 데이터베이스의 격리된 실행 문맥(새 연결)에서 자동 커밋을 끄고 한 트랜잭션으로 실행한다: 실패하면 모두 되돌리고,
 * 사용자의 편집기 세션과 그 트랜잭션은 건드리지 않는다(트랜잭션 안전 규칙). 연결은 실행 뒤 닫는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public final class PostgreCronManager {

    private static final Log log = Log.getLog(PostgreCronManager.class);

    /** Rows of the run history read at most; pg_cron keeps every run until someone deletes them */
    public static final int MAX_HISTORY_ROWS = 1000;

    private PostgreCronManager() {
    }

    @NotNull
    static List<PostgreCronJob> readJobs(@NotNull DBRProgressMonitor monitor, @NotNull PostgreCronScheduler scheduler) throws DBException {
        List<PostgreCronJob> jobs = new ArrayList<>();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, scheduler.getDatabase(), "Read pg_cron jobs")) { //$NON-NLS-1$
            try (JDBCStatement statement = session.createStatement();
                 // Row security shows a login its own jobs (superusers see all)
                 JDBCResultSet resultSet = statement.executeQuery("SELECT * FROM cron.job ORDER BY jobid")) { //$NON-NLS-1$
                while (resultSet.next()) {
                    jobs.add(new PostgreCronJob(scheduler, resultSet));
                }
            }
        } catch (SQLException e) {
            throw PostgreCronException.wrap(e, scheduler.getDataSource(), null);
        }
        return jobs;
    }

    /**
     * Runs from {@code cron.job_run_details}, newest first.
     *
     * @param jobId a job, or null for all jobs the login sees
     */
    @NotNull
    public static List<PostgreCronRunDetail> readRunDetails(
        @NotNull JDBCSession session,
        @NotNull PostgreCronScheduler scheduler,
        @Nullable Long jobId
    ) throws DBException {
        List<PostgreCronRunDetail> runs = new ArrayList<>();
        String sql = "SELECT d.*" + (scheduler.hasJobName() ? ", j.jobname" : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " FROM cron.job_run_details d LEFT JOIN cron.job j ON j.jobid = d.jobid" //$NON-NLS-1$
            + (jobId == null ? "" : " WHERE d.jobid = ?") //$NON-NLS-1$ //$NON-NLS-2$
            + " ORDER BY d.start_time DESC NULLS LAST, d.runid DESC LIMIT " + MAX_HISTORY_ROWS; //$NON-NLS-1$
        try (JDBCPreparedStatement statement = session.prepareStatement(sql)) {
            if (jobId != null) {
                statement.setLong(1, jobId);
            }
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    runs.add(new PostgreCronRunDetail(resultSet));
                }
            }
        } catch (SQLException e) {
            throw PostgreCronException.wrap(e, scheduler.getDataSource(), null);
        }
        return runs;
    }

    /**
     * Databases a job can run in (the dialog's combo).
     */
    @NotNull
    public static List<String> readDatabases(@NotNull DBRProgressMonitor monitor, @NotNull PostgreCronScheduler scheduler) throws DBException {
        List<String> names = new ArrayList<>();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, scheduler.getDatabase(), "Read databases for pg_cron")) { //$NON-NLS-1$
            try (JDBCStatement statement = session.createStatement();
                 JDBCResultSet resultSet = statement.executeQuery(
                     "SELECT datname FROM pg_catalog.pg_database WHERE datallowconn AND NOT datistemplate ORDER BY datname")) { //$NON-NLS-1$
                while (resultSet.next()) {
                    names.add(resultSet.getString(1));
                }
            }
        } catch (SQLException e) {
            throw PostgreCronException.wrap(e, scheduler.getDataSource(), null);
        }
        return names;
    }

    //////////////////////////////////////////////////////////
    // Statements

    @NotNull
    static String literal(@NotNull PostgreCronScheduler scheduler, @NotNull String value) {
        return SQLUtils.quoteString(scheduler.getDataSource(), value);
    }

    @NotNull
    public static String unscheduleStatement(@NotNull PostgreCronJob job) {
        return "SELECT cron.unschedule(" + job.getJobId() + "::bigint)"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Enable / Disable: {@code cron.alter_job(active := …)} (pg_cron 1.4 and later).
     */
    @NotNull
    public static String setActiveStatement(@NotNull PostgreCronJob job, boolean active) {
        return "SELECT cron.alter_job(job_id := " + job.getJobId() + "::bigint, active := " + active + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The statements as a script: what the preview shows and a query window opens.
     */
    @NotNull
    public static String formatScript(@NotNull List<String> statements) {
        StringBuilder script = new StringBuilder();
        for (String statement : statements) {
            script.append(statement).append(";\n"); //$NON-NLS-1$
        }
        return script.toString();
    }

    /**
     * Runs statements in an isolated connection to the database, in one transaction (rolled back if one fails).
     *
     * @param messages receives the server's notices, or null
     * @throws PostgreCronException with the failed statement
     */
    public static void execute(
        @NotNull DBRProgressMonitor monitor,
        @NotNull PostgreDatabase database,
        @NotNull List<String> statements,
        @Nullable Consumer<String> messages
    ) throws DBException {
        DBCExecutionContext context = database.openIsolatedContext(monitor, "pg_cron changes", null); //$NON-NLS-1$
        try (JDBCSession session = (JDBCSession) context.openSession(monitor, DBCExecutionPurpose.UTIL, "Apply pg_cron changes")) { //$NON-NLS-1$
            monitor.beginTask("Apply pg_cron changes", statements.size()); //$NON-NLS-1$
            String current = null;
            boolean autoCommit = true;
            try {
                autoCommit = session.getAutoCommit();
                session.setAutoCommit(false);
                for (String statement : statements) {
                    if (monitor.isCanceled()) {
                        break;
                    }
                    current = statement;
                    monitor.subTask(statement);
                    try (JDBCStatement dbStatement = session.createStatement()) {
                        dbStatement.execute(statement);
                        for (SQLWarning w = dbStatement.getWarnings(); w != null && messages != null; w = w.getNextWarning()) {
                            if (!CommonUtils.isEmpty(w.getMessage())) {
                                messages.accept(w.getMessage());
                            }
                        }
                    }
                    monitor.worked(1);
                }
                current = null;
                if (monitor.isCanceled()) {
                    session.rollback();
                } else {
                    session.commit();
                }
            } catch (SQLException e) {
                try {
                    session.rollback();
                } catch (SQLException rollbackError) {
                    log.debug("Rollback failed", rollbackError); //$NON-NLS-1$
                }
                throw PostgreCronException.wrap(e, database.getDataSource(), current);
            } finally {
                try {
                    session.setAutoCommit(autoCommit);
                } catch (SQLException e) {
                    log.debug("Can't restore auto-commit", e); //$NON-NLS-1$
                }
            }
        } finally {
            monitor.done();
            context.close();
        }
    }
}
