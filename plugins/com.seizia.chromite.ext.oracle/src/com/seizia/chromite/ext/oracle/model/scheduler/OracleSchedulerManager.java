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
package com.seizia.chromite.ext.oracle.model.scheduler;

import com.seizia.chromite.Log;
import com.seizia.chromite.ext.oracle.model.OracleConstants;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleSchedulerJob;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.ext.oracle.model.OracleUtils;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.jdbc.JDBCCallableStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reads and changes Oracle Scheduler objects: schedules, job run details, running jobs, the login's scheduler access,
 * and the execution of generated {@code DBMS_SCHEDULER} blocks.
 *
 * 설계: SQL Server 에이전트의 {@code SQLServerAgentManager}와 같은 역할이다. 모든 DB 호출은 진행 모니터를 받는 작업 스레드에서 부른다.
 * <ul>
 *     <li>뷰는 권한에 맞춰 고른다: {@code DBA_} 뷰를 읽을 수 있으면(SELECT ANY DICTIONARY, SELECT_CATALOG_ROLE) 그것을, 아니면 {@code ALL_} 뷰를
 *     쓴다. 스키마 노드 아래의 기능이라 늘 {@code OWNER = 스키마}로 거르므로 {@code USER_} 뷰는 {@code ALL_} 뷰와 결과가 같아 따로 쓰지 않는다.</li>
 *     <li>AWR·ASH·SQL Monitor 같은 Diagnostics/Tuning Pack 뷰는 읽지 않는다(라이선스 가드). 스케줄러 뷰와 {@code V$PARAMETER}만 쓴다.</li>
 *     <li>바꾸는 블록은 격리된 실행 문맥(새 연결)에서 실행한다. {@code DBMS_SCHEDULER}는 호출마다 스스로 커밋하므로(Oracle 문서) 사용자의
 *     편집기 세션에서 부르면 그 세션의 진행 중인 트랜잭션까지 커밋된다. 트랜잭션 안전 규칙 때문에 사용자 세션은 쓰지 않는다. 같은 이유로
 *     여러 블록을 한 트랜잭션으로 되돌릴 수 없으므로, 실패하면 실패한 문을 알리고 앞의 문은 적용된 채로 둔다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public final class OracleSchedulerManager {

    private static final Log log = Log.getLog(OracleSchedulerManager.class);

    /** Rows of the run history read at most; the view keeps LOG_HISTORY days (30 by default) of runs */
    public static final int MAX_HISTORY_ROWS = 1000;

    /** Date format of the date fields of the dialogs: TZR keeps a region name (DST) or an offset */
    public static final String DATE_FORMAT = "YYYY-MM-DD HH24:MI:SS TZR"; //$NON-NLS-1$

    public static final String PRIV_CREATE_JOB = "CREATE JOB"; //$NON-NLS-1$
    public static final String PRIV_CREATE_ANY_JOB = "CREATE ANY JOB"; //$NON-NLS-1$
    public static final String PRIV_MANAGE_SCHEDULER = "MANAGE SCHEDULER"; //$NON-NLS-1$

    public enum SchedulerState {
        RUNNING,
        /** job_queue_processes = 0: no job runs */
        STOPPED,
        /** The SCHEDULER_DISABLED attribute is TRUE */
        DISABLED,
        /** The login may not read V$PARAMETER */
        UNKNOWN;

        @NotNull
        public String getLabel() {
            return switch (this) {
                case RUNNING -> OracleSchedulerMessages.state_running;
                case STOPPED -> OracleSchedulerMessages.state_stopped;
                case DISABLED -> OracleSchedulerMessages.state_disabled;
                case UNKNOWN -> OracleSchedulerMessages.state_unknown;
            };
        }
    }

    /**
     * What the login may do with the scheduler, read once per connection (and again after a change).
     *
     * @param privileges the scheduler system privileges the session has (directly or through roles)
     */
    public record Access(@NotNull String currentUser, @NotNull Set<String> privileges, @NotNull SchedulerState state) {

        /**
         * Creating a job in a schema: CREATE JOB in one's own schema, CREATE ANY JOB elsewhere.
         */
        public boolean canCreate(@NotNull String owner) {
            return privileges.contains(PRIV_CREATE_ANY_JOB) || owner.equals(currentUser) && privileges.contains(PRIV_CREATE_JOB);
        }

        /**
         * Altering, running, stopping and dropping a job: the owner may, others need CREATE ANY JOB (or an object grant the
         * server checks; this check only turns the commands off when the login surely may not).
         */
        public boolean canAlter(@NotNull String owner) {
            return owner.equals(currentUser) || privileges.contains(PRIV_CREATE_ANY_JOB);
        }

        public boolean canStopWithForce() {
            return privileges.contains(PRIV_MANAGE_SCHEDULER);
        }

        /**
         * False when jobs surely do not run (no job queue processes, scheduler disabled); unknown lets the server decide.
         */
        public boolean canRunJobs() {
            return state == SchedulerState.RUNNING || state == SchedulerState.UNKNOWN;
        }
    }

    private OracleSchedulerManager() {
    }

    //////////////////////////////////////////////////////////
    // Views and access

    /**
     * The DBA_ view when the login may read it, the ALL_ view otherwise.
     *
     * @param name view name without the prefix, e.g. {@code SCHEDULER_JOB_RUN_DETAILS}
     */
    @NotNull
    static String getView(@NotNull DBRProgressMonitor monitor, @NotNull OracleDataSource dataSource, @NotNull String name) {
        String dbaView = "DBA_" + name; //$NON-NLS-1$
        if (dataSource.isViewAvailable(monitor, OracleConstants.SCHEMA_SYS, dbaView)) {
            return OracleUtils.getSysSchemaPrefix(dataSource) + dbaView;
        }
        return OracleUtils.getSysSchemaPrefix(dataSource) + "ALL_" + name; //$NON-NLS-1$
    }

    /**
     * Reads the current user, its scheduler privileges and whether the scheduler runs jobs.
     */
    @NotNull
    public static Access readAccess(@NotNull DBRProgressMonitor monitor, @NotNull OracleDataSource dataSource) throws DBException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, dataSource, "Read Oracle Scheduler access")) { //$NON-NLS-1$
            String user = ""; //$NON-NLS-1$
            Set<String> privileges = new HashSet<>();
            try (JDBCStatement statement = session.createStatement();
                 JDBCResultSet resultSet = statement.executeQuery("SELECT USER FROM DUAL")) { //$NON-NLS-1$
                if (resultSet.next()) {
                    user = CommonUtils.notEmpty(resultSet.getString(1));
                }
            }
            // SESSION_PRIVS includes the privileges of enabled roles
            try (JDBCPreparedStatement statement = session.prepareStatement(
                "SELECT PRIVILEGE FROM SESSION_PRIVS WHERE PRIVILEGE IN (?, ?, ?)")) { //$NON-NLS-1$
                statement.setString(1, PRIV_CREATE_JOB);
                statement.setString(2, PRIV_CREATE_ANY_JOB);
                statement.setString(3, PRIV_MANAGE_SCHEDULER);
                try (JDBCResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        privileges.add(resultSet.getString(1));
                    }
                }
            }
            return new Access(user, privileges, readState(session));
        } catch (SQLException e) {
            throw OracleSchedulerException.wrap(e, dataSource, null);
        }
    }

    @NotNull
    private static SchedulerState readState(@NotNull JDBCSession session) {
        String disabled = queryValue(session, "SELECT VALUE FROM " + OracleUtils.getSysSchemaPrefix((OracleDataSource) session.getDataSource()) //$NON-NLS-1$
            + "ALL_SCHEDULER_GLOBAL_ATTRIBUTE WHERE ATTRIBUTE_NAME = 'SCHEDULER_DISABLED'"); //$NON-NLS-1$
        if ("TRUE".equalsIgnoreCase(disabled)) { //$NON-NLS-1$
            return SchedulerState.DISABLED;
        }
        // V$PARAMETER needs SELECT_CATALOG_ROLE or a grant; without it the state stays unknown
        String processes = queryValue(session, "SELECT VALUE FROM V$PARAMETER WHERE NAME = 'job_queue_processes'"); //$NON-NLS-1$
        if (processes == null) {
            return SchedulerState.UNKNOWN;
        }
        return "0".equals(processes.trim()) ? SchedulerState.STOPPED : SchedulerState.RUNNING; //$NON-NLS-1$
    }

    @Nullable
    private static String queryValue(@NotNull JDBCSession session, @NotNull String sql) {
        try (JDBCStatement statement = session.createStatement();
             JDBCResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        } catch (SQLException e) {
            log.debug("Can't read the scheduler state: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    //////////////////////////////////////////////////////////
    // Lists

    @NotNull
    static List<OracleSchedulerSchedule> readSchedules(@NotNull DBRProgressMonitor monitor, @NotNull OracleSchema schema) throws DBException {
        List<OracleSchedulerSchedule> schedules = new ArrayList<>();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, schema, "Read Oracle Scheduler schedules")) { //$NON-NLS-1$
            try (JDBCPreparedStatement statement = session.prepareStatement(
                "SELECT SCHEDULE_NAME, SCHEDULE_TYPE, TO_CHAR(START_DATE, '" + DATE_FORMAT + "') AS START_DATE, REPEAT_INTERVAL, " //$NON-NLS-1$ //$NON-NLS-2$
                    + "TO_CHAR(END_DATE, '" + DATE_FORMAT + "') AS END_DATE, COMMENTS FROM " //$NON-NLS-1$ //$NON-NLS-2$
                    + getView(monitor, schema.getDataSource(), "SCHEDULER_SCHEDULES") + " WHERE OWNER = ? ORDER BY SCHEDULE_NAME")) { //$NON-NLS-1$ //$NON-NLS-2$
                statement.setString(1, schema.getName());
                try (JDBCResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        schedules.add(new OracleSchedulerSchedule(schema, resultSet));
                    }
                }
            }
        } catch (SQLException e) {
            throw OracleSchedulerException.wrap(e, schema.getDataSource(), null);
        }
        return schedules;
    }

    /**
     * Job runs of a schema, newest first ({@code *_SCHEDULER_JOB_RUN_DETAILS}).
     *
     * @param jobName a job, or null for all jobs of the schema
     */
    @NotNull
    public static List<OracleSchedulerRunDetail> readRunDetails(
        @NotNull JDBCSession session,
        @NotNull OracleSchema schema,
        @Nullable String jobName
    ) throws DBException {
        List<OracleSchedulerRunDetail> runs = new ArrayList<>();
        String view = getView(session.getProgressMonitor(), schema.getDataSource(), "SCHEDULER_JOB_RUN_DETAILS"); //$NON-NLS-1$
        String sql = "SELECT * FROM (SELECT * FROM " + view + " WHERE OWNER = ?" //$NON-NLS-1$ //$NON-NLS-2$
            + (jobName == null ? "" : " AND JOB_NAME = ?") //$NON-NLS-1$ //$NON-NLS-2$
            + " ORDER BY LOG_DATE DESC, LOG_ID DESC) WHERE ROWNUM <= " + MAX_HISTORY_ROWS; //$NON-NLS-1$
        try (JDBCPreparedStatement statement = session.prepareStatement(sql)) {
            statement.setString(1, schema.getName());
            if (jobName != null) {
                statement.setString(2, jobName);
            }
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    runs.add(new OracleSchedulerRunDetail(resultSet));
                }
            }
        } catch (SQLException e) {
            throw OracleSchedulerException.wrap(e, schema.getDataSource(), null);
        }
        return runs;
    }

    /**
     * Jobs of a schema running now ({@code *_SCHEDULER_RUNNING_JOBS}).
     */
    @NotNull
    public static List<OracleSchedulerRunningJob> readRunningJobs(@NotNull JDBCSession session, @NotNull OracleSchema schema) throws DBException {
        List<OracleSchedulerRunningJob> jobs = new ArrayList<>();
        String view = getView(session.getProgressMonitor(), schema.getDataSource(), "SCHEDULER_RUNNING_JOBS"); //$NON-NLS-1$
        try (JDBCPreparedStatement statement = session.prepareStatement("SELECT * FROM " + view + " WHERE OWNER = ? ORDER BY JOB_NAME")) { //$NON-NLS-1$ //$NON-NLS-2$
            statement.setString(1, schema.getName());
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    jobs.add(new OracleSchedulerRunningJob(schema, resultSet));
                }
            }
        } catch (SQLException e) {
            throw OracleSchedulerException.wrap(e, schema.getDataSource(), null);
        }
        return jobs;
    }

    /**
     * Standalone procedures of a schema, offered by the job dialog for stored procedure jobs.
     */
    @NotNull
    public static List<String> readProcedures(@NotNull DBRProgressMonitor monitor, @NotNull OracleSchema schema) throws DBException {
        List<String> names = new ArrayList<>();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, schema, "Read procedures for Oracle Scheduler")) { //$NON-NLS-1$
            try (JDBCPreparedStatement statement = session.prepareStatement(
                "SELECT OBJECT_NAME FROM " + OracleUtils.getSysSchemaPrefix(schema.getDataSource()) //$NON-NLS-1$
                    + "ALL_OBJECTS WHERE OWNER = ? AND OBJECT_TYPE = 'PROCEDURE' ORDER BY OBJECT_NAME")) { //$NON-NLS-1$
                statement.setString(1, schema.getName());
                try (JDBCResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        names.add(DBUtils.getQuotedIdentifier(schema.getDataSource(), schema.getName()) + "." //$NON-NLS-1$
                            + DBUtils.getQuotedIdentifier(schema.getDataSource(), resultSet.getString(1)));
                    }
                }
            }
        } catch (SQLException e) {
            throw OracleSchedulerException.wrap(e, schema.getDataSource(), null);
        }
        return names;
    }

    /**
     * The next run dates of a calendar expression ({@code DBMS_SCHEDULER.EVALUATE_CALENDAR_STRING}); it only computes
     * dates and changes nothing, so it runs in the metadata session.
     *
     * @param startDate start date in {@link #DATE_FORMAT}, or null for now
     */
    @NotNull
    public static List<String> evaluateCalendar(
        @NotNull DBRProgressMonitor monitor,
        @NotNull OracleDataSource dataSource,
        @NotNull String repeatInterval,
        @Nullable String startDate,
        int count
    ) throws DBException {
        String block = "DECLARE\n" //$NON-NLS-1$
            + "  start_date TIMESTAMP WITH TIME ZONE := NVL(TO_TIMESTAMP_TZ(?, '" + DATE_FORMAT + "'), SYSTIMESTAMP);\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "  after_date TIMESTAMP WITH TIME ZONE := GREATEST(start_date, SYSTIMESTAMP);\n" //$NON-NLS-1$
            + "  next_date TIMESTAMP WITH TIME ZONE;\n" //$NON-NLS-1$
            + "  result VARCHAR2(4000);\n" //$NON-NLS-1$
            + "BEGIN\n" //$NON-NLS-1$
            + "  FOR i IN 1 .. ? LOOP\n" //$NON-NLS-1$
            + "    DBMS_SCHEDULER.EVALUATE_CALENDAR_STRING(?, start_date, after_date, next_date);\n" //$NON-NLS-1$
            + "    EXIT WHEN next_date IS NULL;\n" //$NON-NLS-1$
            + "    result := result || TO_CHAR(next_date, '" + DATE_FORMAT + "') || CHR(10);\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "    after_date := next_date;\n" //$NON-NLS-1$
            + "  END LOOP;\n" //$NON-NLS-1$
            + "  ? := result;\n" //$NON-NLS-1$
            + "END;"; //$NON-NLS-1$
        try (JDBCSession session = DBUtils.openMetaSession(monitor, dataSource, "Evaluate Oracle Scheduler calendar"); //$NON-NLS-1$
             JDBCCallableStatement statement = session.prepareCall(block)) {
            statement.setString(1, CommonUtils.nullIfEmpty(startDate));
            statement.setInt(2, count);
            statement.setString(3, repeatInterval);
            statement.registerOutParameter(4, Types.VARCHAR);
            statement.execute();
            List<String> dates = new ArrayList<>();
            for (String line : CommonUtils.notEmpty(statement.getString(4)).split("\n")) { //$NON-NLS-1$
                if (!line.isBlank()) {
                    dates.add(line.trim());
                }
            }
            return dates;
        } catch (SQLException e) {
            throw OracleSchedulerException.wrap(e, dataSource, null);
        }
    }

    //////////////////////////////////////////////////////////
    // Statements

    /**
     * The name of a job as DBMS_SCHEDULER takes it: {@code OWNER.NAME}, each part quoted when it needs to be.
     */
    @NotNull
    public static String qualifiedName(@NotNull OracleDataSource dataSource, @NotNull String owner, @NotNull String name) {
        return DBUtils.getQuotedIdentifier(dataSource, owner) + "." + DBUtils.getQuotedIdentifier(dataSource, name); //$NON-NLS-1$
    }

    @NotNull
    static String jobName(@NotNull OracleSchedulerJob job) {
        return qualifiedName(job.getDataSource(), job.getSchema().getName(), job.getName());
    }

    /**
     * A PL/SQL string literal.
     */
    @NotNull
    public static String literal(@Nullable String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @NotNull
    static String block(@NotNull String call) {
        return "BEGIN\n  " + call + ";\nEND;"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Run now: in a scheduler job session ({@code use_current_session => FALSE}, returns at once) or in the session of
     * the call, which waits until the job ends and reports its error.
     */
    @NotNull
    public static String runJobStatement(@NotNull OracleSchedulerJob job, boolean wait) {
        return block("DBMS_SCHEDULER.RUN_JOB(job_name => " + literal(jobName(job)) //$NON-NLS-1$
            + ", use_current_session => " + (wait ? "TRUE" : "FALSE") + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @NotNull
    public static String stopJobStatement(@NotNull OracleDataSource dataSource, @NotNull String owner, @NotNull String name, boolean force) {
        return block("DBMS_SCHEDULER.STOP_JOB(job_name => " + literal(qualifiedName(dataSource, owner, name)) //$NON-NLS-1$
            + ", force => " + (force ? "TRUE" : "FALSE") + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @NotNull
    public static String enableJobStatement(@NotNull OracleSchedulerJob job, boolean enable) {
        return enable
            ? block("DBMS_SCHEDULER.ENABLE(name => " + literal(jobName(job)) + ")") //$NON-NLS-1$ //$NON-NLS-2$
            : block("DBMS_SCHEDULER.DISABLE(name => " + literal(jobName(job)) + ", force => FALSE)"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    public static String dropJobStatement(@NotNull OracleSchedulerJob job) {
        return block("DBMS_SCHEDULER.DROP_JOB(job_name => " + literal(jobName(job)) + ", force => FALSE)"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The statements as a script: what the preview shows and a query window opens. Each block ends with a "/" line, the
     * PL/SQL block delimiter of Oracle scripts.
     */
    @NotNull
    public static String formatScript(@NotNull List<String> statements) {
        StringBuilder script = new StringBuilder();
        for (String statement : statements) {
            script.append(statement).append("\n/\n"); //$NON-NLS-1$
        }
        return script.toString();
    }

    /**
     * Runs PL/SQL blocks in an isolated connection, one after the other; stops at the first failure.
     *
     * @param messages receives the server's warnings, or null
     * @throws OracleSchedulerException with the failed statement
     */
    public static void execute(
        @NotNull DBRProgressMonitor monitor,
        @NotNull OracleDataSource dataSource,
        @NotNull List<String> statements,
        @Nullable Consumer<String> messages
    ) throws DBException {
        DBCExecutionContext context = dataSource.getDefaultInstance().openIsolatedContext(monitor, "Oracle Scheduler changes", null); //$NON-NLS-1$
        try (JDBCSession session = (JDBCSession) context.openSession(monitor, DBCExecutionPurpose.UTIL, "Apply Oracle Scheduler changes")) { //$NON-NLS-1$
            monitor.beginTask("Apply Oracle Scheduler changes", statements.size()); //$NON-NLS-1$
            for (String statement : statements) {
                if (monitor.isCanceled()) {
                    break;
                }
                monitor.subTask(statement);
                try (JDBCStatement dbStatement = session.createStatement()) {
                    dbStatement.execute(statement);
                    for (SQLWarning w = dbStatement.getWarnings(); w != null && messages != null; w = w.getNextWarning()) {
                        if (!CommonUtils.isEmpty(w.getMessage())) {
                            messages.accept(w.getMessage());
                        }
                    }
                } catch (SQLException e) {
                    throw OracleSchedulerException.wrap(e, dataSource, statement);
                }
                monitor.worked(1);
            }
        } finally {
            monitor.done();
            context.close();
        }
    }

    /**
     * Upper case as Oracle stores unquoted names.
     */
    @NotNull
    public static String normalizeName(@NotNull String name) {
        String trimmed = name.trim();
        if (trimmed.length() > 1 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) { //$NON-NLS-1$ //$NON-NLS-2$
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
