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
package com.seizia.chromite.ext.mssql.model.agent;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads and changes SQL Server Agent objects through the msdb procedures: the lists of the navigator folders, job
 * history, the agent error log, the values the dialogs offer, and the execution of generated statements.
 *
 * 설계: 모든 DB 호출은 진행 모니터를 받는 작업 스레드에서 부른다(UI는 Job이나 진행 대화상자에서 부른다). 읽기는 탐색기와 같은 메타 세션이나
 * 호출하는 쪽이 넘긴 세션(작업 활동 모니터의 연결)에서 하고, 바꾸는 문은 SSMS 스크립트처럼 한 트랜잭션으로 실행한다. 바꾸는 문은 격리된
 * 실행 문맥에서 실행해 편집기의 트랜잭션과 섞이지 않게 한다(보안 편집기와 같은 이유). 서버가 보내는 안내 메시지(예: 에이전트가 멈춰 있어
 * 알리지 못함)는 경고로 받아 호출하는 쪽에 넘긴다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public final class SQLServerAgentManager {

    private static final Log log = Log.getLog(SQLServerAgentManager.class);

    /**
     * One row of {@code sp_help_jobhistory @mode = 'FULL'}: a job outcome (step 0) or a step of a run.
     */
    public record HistoryEntry(
        long instanceId,
        @NotNull String jobId,
        @NotNull String jobName,
        int stepId,
        @NotNull String stepName,
        int sqlMessageId,
        int sqlSeverity,
        @NotNull String message,
        int runStatus,
        @Nullable Timestamp runDate,
        long durationSeconds,
        int retriesAttempted
    ) {
        public boolean isJobOutcome() {
            return stepId == 0;
        }
    }

    /**
     * One line of the agent error log ({@code xp_readerrorlog}); level 1 error, 2 warning, 3 information.
     */
    public record LogEntry(@Nullable Timestamp date, int level, @NotNull String text) {
    }

    /**
     * Values the job and alert dialogs offer in their combos.
     */
    public record Lookups(
        @NotNull String currentLogin,
        @NotNull List<String> logins,
        @NotNull List<String> categories,
        @NotNull List<String> databases,
        @NotNull List<String> operators,
        @NotNull List<String> jobs
    ) {
    }

    private SQLServerAgentManager() {
    }

    //////////////////////////////////////////////////////////
    // Navigator lists

    @NotNull
    static List<SQLServerAgentJob> readJobs(@NotNull DBRProgressMonitor monitor, @NotNull SQLServerAgent agent) throws DBException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, agent.getDataSource(), "Read SQL Server Agent jobs")) { //$NON-NLS-1$
            return readJobs(session, agent);
        }
    }

    /**
     * The jobs with their current state, read fresh (Job Activity Monitor).
     */
    @NotNull
    public static List<SQLServerAgentJob> readJobs(@NotNull JDBCSession session, @NotNull SQLServerAgent agent) throws DBException {
        List<SQLServerAgentJob> jobs = new ArrayList<>();
        SQLServerAgentSql.readRows(session, "EXEC msdb.dbo.sp_help_job", new Object[0], row -> jobs.add(new SQLServerAgentJob(agent, row))); //$NON-NLS-1$
        return jobs;
    }

    @NotNull
    static List<SQLServerAgentAlert> readAlerts(@NotNull DBRProgressMonitor monitor, @NotNull SQLServerAgent agent) throws DBException {
        List<SQLServerAgentAlert> alerts = new ArrayList<>();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, agent.getDataSource(), "Read SQL Server Agent alerts")) { //$NON-NLS-1$
            SQLServerAgentSql.readRows(session, "EXEC msdb.dbo.sp_help_alert", new Object[0], row -> alerts.add(new SQLServerAgentAlert(agent, row))); //$NON-NLS-1$
        }
        return alerts;
    }

    @NotNull
    static List<SQLServerAgentOperator> readOperators(@NotNull DBRProgressMonitor monitor, @NotNull SQLServerAgent agent) throws DBException {
        List<SQLServerAgentOperator> operators = new ArrayList<>();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, agent.getDataSource(), "Read SQL Server Agent operators")) { //$NON-NLS-1$
            SQLServerAgentSql.readRows(session, "EXEC msdb.dbo.sp_help_operator", new Object[0], row -> operators.add(new SQLServerAgentOperator(agent, row))); //$NON-NLS-1$
        }
        return operators;
    }

    @NotNull
    static List<SQLServerAgentProxy> readProxies(@NotNull DBRProgressMonitor monitor, @NotNull SQLServerAgent agent) throws DBException {
        List<SQLServerAgentProxy> proxies = new ArrayList<>();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, agent.getDataSource(), "Read SQL Server Agent proxies")) { //$NON-NLS-1$
            SQLServerAgentSql.readRows(session, "EXEC msdb.dbo.sp_help_proxy", new Object[0], row -> proxies.add(new SQLServerAgentProxy(agent, row))); //$NON-NLS-1$
        }
        return proxies;
    }

    /**
     * The agent log files ({@code xp_enumerrorlogs 2}); the current log alone when the server does not list them (Linux)
     * or the login may not.
     */
    @NotNull
    static List<SQLServerAgentErrorLog> readErrorLogs(@NotNull DBRProgressMonitor monitor, @NotNull SQLServerAgent agent) {
        List<SQLServerAgentErrorLog> logs = new ArrayList<>();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, agent.getDataSource(), "Read SQL Server Agent error logs")) { //$NON-NLS-1$
            SQLServerAgentSql.readRows(session, "EXEC master.dbo.xp_enumerrorlogs 2", new Object[0], row -> logs.add(new SQLServerAgentErrorLog( //$NON-NLS-1$
                agent,
                row.getInt(1),
                CommonUtils.nullIfEmpty(CommonUtils.notEmpty(row.getString(2)).replaceAll("\\s+", " ").trim()), //$NON-NLS-1$ //$NON-NLS-2$
                row.getLong(3))));
        } catch (DBException e) {
            log.debug("Can't list the SQL Server Agent error logs: " + e.getMessage()); //$NON-NLS-1$
        }
        if (logs.stream().noneMatch(errorLog -> errorLog.getArchiveNumber() == 0)) {
            logs.add(0, new SQLServerAgentErrorLog(agent, 0, null, 0));
        }
        logs.sort((a, b) -> Integer.compare(a.getArchiveNumber(), b.getArchiveNumber()));
        return logs;
    }

    //////////////////////////////////////////////////////////
    // History and log

    /**
     * Job history, newest first: job outcomes and the steps of each run.
     *
     * @param jobId a job, or null for all jobs
     */
    @NotNull
    public static List<HistoryEntry> readHistory(@NotNull JDBCSession session, @Nullable String jobId) throws DBException {
        List<HistoryEntry> entries = new ArrayList<>();
        String sql = jobId == null
            ? "EXEC msdb.dbo.sp_help_jobhistory @mode = N'FULL'" //$NON-NLS-1$
            : "EXEC msdb.dbo.sp_help_jobhistory @job_id = ?, @mode = N'FULL'"; //$NON-NLS-1$
        SQLServerAgentSql.readRows(session, sql, jobId == null ? new Object[0] : new Object[] {jobId}, row -> entries.add(new HistoryEntry(
            JDBCUtils.safeGetLong(row, "instance_id"), //$NON-NLS-1$
            CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "job_id")), //$NON-NLS-1$
            CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "job_name")), //$NON-NLS-1$
            JDBCUtils.safeGetInt(row, "step_id"), //$NON-NLS-1$
            CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "step_name")), //$NON-NLS-1$
            JDBCUtils.safeGetInt(row, "sql_message_id"), //$NON-NLS-1$
            JDBCUtils.safeGetInt(row, "sql_severity"), //$NON-NLS-1$
            CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "message")), //$NON-NLS-1$
            JDBCUtils.safeGetInt(row, "run_status"), //$NON-NLS-1$
            SQLServerAgentSql.toTimestamp(JDBCUtils.safeGetInt(row, "run_date"), JDBCUtils.safeGetInt(row, "run_time")), //$NON-NLS-1$ //$NON-NLS-2$
            SQLServerAgentSql.durationSeconds(JDBCUtils.safeGetInt(row, "run_duration")), //$NON-NLS-1$
            JDBCUtils.safeGetInt(row, "retries_attempted")))); //$NON-NLS-1$
        // Newest first; the procedure's order depends on its @oldest_first default
        entries.sort((a, b) -> Long.compare(b.instanceId(), a.instanceId()));
        return entries;
    }

    /**
     * Lines of an agent error log file, newest first.
     *
     * @param archive 0 for the current log
     */
    @NotNull
    public static List<LogEntry> readErrorLog(@NotNull JDBCSession session, int archive) throws DBException {
        List<LogEntry> entries = new ArrayList<>();
        SQLServerAgentSql.readRows(session, "EXEC master.dbo.xp_readerrorlog ?, 2", new Object[] {archive}, row -> entries.add(new LogEntry( //$NON-NLS-1$
            row.getTimestamp(1),
            row.getInt(2),
            CommonUtils.notEmpty(row.getString(3)))));
        // The procedure returns the file in order; the newest lines matter most, as in the log viewers this follows
        Collections.reverse(entries);
        return entries;
    }

    //////////////////////////////////////////////////////////
    // Dialog values

    @NotNull
    public static Lookups readLookups(@NotNull DBRProgressMonitor monitor, @NotNull SQLServerAgent agent) throws DBException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, agent.getDataSource(), "Read SQL Server Agent dialog values")) { //$NON-NLS-1$
            List<String> logins = new ArrayList<>();
            List<String> categories = new ArrayList<>();
            List<String> databases = new ArrayList<>();
            List<String> operators = new ArrayList<>();
            List<String> jobs = new ArrayList<>();
            String[] currentLogin = {""}; //$NON-NLS-1$
            SQLServerAgentSql.readRows(session, "SELECT SUSER_SNAME()", new Object[0], row -> currentLogin[0] = CommonUtils.notEmpty(row.getString(1))); //$NON-NLS-1$
            SQLServerAgentSql.readRows(session,
                "SELECT name FROM sys.server_principals WHERE type IN ('S', 'U', 'G') AND name NOT LIKE N'##%' ORDER BY name", //$NON-NLS-1$
                new Object[0], row -> logins.add(row.getString(1)));
            SQLServerAgentSql.readRows(session, "SELECT name FROM sys.databases ORDER BY name", new Object[0], row -> databases.add(row.getString(1))); //$NON-NLS-1$
            SQLServerAgentSql.readRows(session, "EXEC msdb.dbo.sp_help_category @class = N'JOB', @type = N'LOCAL'", new Object[0], //$NON-NLS-1$
                row -> categories.add(JDBCUtils.safeGetString(row, "name"))); //$NON-NLS-1$
            SQLServerAgentSql.readRows(session, "EXEC msdb.dbo.sp_help_job", new Object[0], row -> jobs.add(JDBCUtils.safeGetString(row, "name"))); //$NON-NLS-1$ //$NON-NLS-2$
            try {
                SQLServerAgentSql.readRows(session, "EXEC msdb.dbo.sp_help_operator", new Object[0], //$NON-NLS-1$
                    row -> operators.add(JDBCUtils.safeGetString(row, "name"))); //$NON-NLS-1$
            } catch (DBException e) {
                // Logins with only SQLAgentUserRole may not list operators: the dialogs then offer none
                log.debug("Can't list SQL Server Agent operators: " + e.getMessage()); //$NON-NLS-1$
            }
            categories.sort(String.CASE_INSENSITIVE_ORDER);
            operators.sort(String.CASE_INSENSITIVE_ORDER);
            jobs.sort(String.CASE_INSENSITIVE_ORDER);
            return new Lookups(currentLogin[0], logins, categories, databases, operators, jobs);
        }
    }

    //////////////////////////////////////////////////////////
    // Changes

    /**
     * The statements as a script: what the preview shows, a query window opens and {@link #execute} runs.
     */
    @NotNull
    public static String formatScript(@NotNull List<String> statements) {
        StringBuilder script = new StringBuilder();
        for (String statement : statements) {
            if (!script.isEmpty()) {
                script.append('\n');
            }
            script.append(statement).append(";\n"); //$NON-NLS-1$
        }
        return script.toString();
    }

    @NotNull
    public static String startJobStatement(@NotNull SQLServerAgentJob job, @Nullable String stepName) {
        SQLServerAgentSql.Call call = new SQLServerAgentSql.Call("sp_start_job").arg("job_id", job.getJobId()); //$NON-NLS-1$ //$NON-NLS-2$
        if (stepName != null) {
            call.arg("step_name", stepName); //$NON-NLS-1$
        }
        return call.toSql();
    }

    @NotNull
    public static String stopJobStatement(@NotNull SQLServerAgentJob job) {
        return new SQLServerAgentSql.Call("sp_stop_job").arg("job_id", job.getJobId()).toSql(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    public static String enableJobStatement(@NotNull SQLServerAgentJob job, boolean enable) {
        return new SQLServerAgentSql.Call("sp_update_job").arg("job_id", job.getJobId()).arg("enabled", enable).toSql(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @NotNull
    public static String enableAlertStatement(@NotNull SQLServerAgentAlert alert, boolean enable) {
        return new SQLServerAgentSql.Call("sp_update_alert").arg("name", alert.getName()).arg("enabled", enable).toSql(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Runs statements in an isolated connection, in one transaction (rolled back if one fails).
     *
     * @param messages receives the server's informational messages, or null
     * @throws SQLServerAgentException with the failed statement
     */
    public static void execute(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLServerDataSource dataSource,
        @NotNull List<String> statements,
        @Nullable Consumer<String> messages
    ) throws DBException {
        DBCExecutionContext context = dataSource.getDefaultInstance().openIsolatedContext(monitor, "SQL Server Agent changes", null); //$NON-NLS-1$
        try (JDBCSession session = (JDBCSession) context.openSession(monitor, DBCExecutionPurpose.UTIL, "Apply SQL Server Agent changes")) { //$NON-NLS-1$
            execute(monitor, session, statements, messages);
        } finally {
            context.close();
        }
    }

    /**
     * Runs statements in a session, in one transaction (rolled back if one fails).
     */
    public static void execute(
        @NotNull DBRProgressMonitor monitor,
        @NotNull JDBCSession session,
        @NotNull List<String> statements,
        @Nullable Consumer<String> messages
    ) throws DBException {
        monitor.beginTask("Apply SQL Server Agent changes", statements.size()); //$NON-NLS-1$
        String current = null;
        try {
            JDBCUtils.executeStatement(session, "BEGIN TRANSACTION"); //$NON-NLS-1$
            for (String statement : statements) {
                if (monitor.isCanceled()) {
                    break;
                }
                current = statement;
                monitor.subTask(statement);
                try (JDBCStatement dbStatement = session.createStatement()) {
                    dbStatement.execute(statement);
                    reportWarnings(dbStatement.getWarnings(), messages);
                }
                monitor.worked(1);
            }
            current = null;
            JDBCUtils.executeStatement(session, monitor.isCanceled() ? "ROLLBACK TRANSACTION" : "COMMIT TRANSACTION"); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (SQLException e) {
            try {
                JDBCUtils.executeStatement(session, "IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION"); //$NON-NLS-1$
            } catch (SQLException rollbackError) {
                log.debug("Rollback failed", rollbackError); //$NON-NLS-1$
            }
            throw SQLServerAgentException.wrap(e, session.getDataSource(), current);
        } finally {
            monitor.done();
        }
    }

    private static void reportWarnings(@Nullable SQLWarning warning, @Nullable Consumer<String> messages) {
        for (SQLWarning w = warning; w != null && messages != null; w = w.getNextWarning()) {
            if (!CommonUtils.isEmpty(w.getMessage())) {
                messages.accept(w.getMessage());
            }
        }
    }
}
