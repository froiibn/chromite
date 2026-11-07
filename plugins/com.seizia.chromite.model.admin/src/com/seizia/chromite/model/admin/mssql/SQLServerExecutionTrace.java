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
package com.seizia.chromite.model.admin.mssql;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mssql.SQLServerUtils;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.ext.mssql.model.SQLServerProcedure;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SQL Server 실행 추적: 프로시저를 인수와 함께 한 번 실행하고, 실행된 문장마다 줄·시간·행 수·읽기를 확장 이벤트(XE)로 모은다.
 *
 * 설계 (docs/chromite/sqlserver-execution-trace.md):
 * <ul>
 *     <li>SQL Server에는 JDBC로 쓸 수 있는 디버그 API가 없다(docs/chromite/procedure-debugger.md "다른 데이터베이스"). 그래서 단계 실행
 *     대신, 실행이 끝난 뒤 문장별 기록을 보인다. 이벤트는 {@code sp_statement_completed}(모듈·동적 SQL의 문장, 줄은 모듈 정의 기준)와
 *     {@code sql_statement_completed}(호출 일괄 처리의 EXEC 문 = 전체 시간)다. 필터는 실행 세션의 {@code session_id} 하나다.</li>
 *     <li>연결 두 개(연결의 격리 문맥): 실행 세션은 연결 설정의 커밋 방식을 따르고 끝난 뒤에도 열어 둔다(트랜잭션 질문,
 *     {@link #getTransactionState}·{@link #endTransaction}). 제어 세션은 자동 커밋이고 XE 세션 만들기·시작·읽기·삭제와 모듈 원본 읽기만
 *     한다. 실행 세션에서 XE DDL을 하지 않는 이유: 수동 커밋 연결의 열린 트랜잭션과 섞이지 않게, 그리고 추적 읽기가 추적되지 않게.</li>
 *     <li>XE 대상은 {@code ring_buffer}다. {@code event_file}은 서버 디스크 경로가 필요하고 남는 파일을 지워야 한다. ring_buffer는 세션을
 *     멈추면 비워지므로 세션을 지우기 전에 읽는다. 이벤트는 {@code MAX_DISPATCH_LATENCY}(1초) 안에 버퍼로 가므로, 호출 뒤 실행 세션에서
 *     고유한 표지 문장을 실행하고 그 이벤트가 버퍼에 보일 때까지 기다린 뒤 읽는다(시간 제한 {@link #MARKER_WAIT_MS}).</li>
 *     <li>권한: XE 세션을 만들고 시작하고(ALTER ANY EVENT SESSION, 2022의 세분 권한) 버퍼를 읽는(VIEW SERVER STATE, 2022의 VIEW SERVER
 *     PERFORMANCE STATE) 것을 호출 <b>전에</b> 실제로 해 본다. 이름만 보는 사전 확인({@code HAS_PERMS_BY_NAME})은 2022의 세분 권한을 가진
 *     사용자를 막을 수 있어서, 실패했을 때 빠진 권한과 GRANT 문을 알리는 데에만 쓴다({@link PrivilegeException}). 추적할 수 없으면
 *     프로시저를 실행하지 않는다.</li>
 *     <li>정리: XE 세션은 {@link #run}의 finally에서 늘 지운다(성공, 오류, 취소, 권한 실패). 앱이 실행 중에 죽은 경우만 남을 수 있어
 *     이름에 {@link #SESSION_PREFIX}를 붙인다(STARTUP_STATE OFF라 서버를 다시 시작하면 멈춘 채 정의만 남는다).</li>
 *     <li>호출 일괄 처리: 인수는 사용자가 쓴 T-SQL 식이다. {@code EXEC}는 식을 인수로 받지 않으므로 매개 변수 형식의 변수에 넣고 넘긴다.
 *     값이 빈 입력 매개 변수는 빼서 서버 기본값을 쓰고, OUTPUT 매개 변수는 변수로 받아 반환 값과 함께 마지막 SELECT로 돌려준다.
 *     사용자 정의 형식은 그 데이터베이스에서만 선언할 수 있어 실행 세션은 먼저 {@code USE}로 루틴의 데이터베이스로 간다.</li>
 *     <li>대상: SQL Server 드라이버의 프로시저만(Babelfish, Synapse, Sybase 제외). Azure SQL Database(EngineEdition 5)는 서버 범위 XE가 없어
 *     실행 전에 알린다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/sqlserver-execution-trace.md)
 */
public final class SQLServerExecutionTrace {

    private static final Log log = Log.getLog(SQLServerExecutionTrace.class);

    /**
     * Prefix of the XE sessions this class creates (a leftover can be recognized by it).
     */
    public static final String SESSION_PREFIX = "chromite_trace_"; //$NON-NLS-1$
    /**
     * Ring buffer limits: the newest events are kept when a call runs more statements.
     */
    public static final int MAX_EVENTS = 20000;
    private static final int MAX_MEMORY_KB = 16384;
    private static final long MARKER_WAIT_MS = 10000;
    private static final long POLL_INTERVAL_MS = 250;
    private static final int MAX_OUTPUT_LENGTH = 4000;
    private static final int ENGINE_AZURE_SQL_DATABASE = 5;

    private static final String CALL_RETURN_VARIABLE = "@chromite_return"; //$NON-NLS-1$
    private static final String CALL_VARIABLE_PREFIX = "@chromite_p"; //$NON-NLS-1$
    /**
     * First column of the call batch's output SELECT: tells it from the procedure's result sets.
     */
    private static final String OUTPUT_RETURN_COLUMN = "chromite_return_value"; //$NON-NLS-1$
    /**
     * Statement errors read before the rest of the call's results are given up.
     */
    private static final int MAX_ERRORS = 50;

    /**
     * How a traced statement relates to the routine.
     */
    public enum StatementKind {
        /**
         * The EXEC of the call batch: the whole call.
         */
        CALL,
        /**
         * A statement of a module (procedure, function, trigger): its line is a line of the module's definition.
         */
        MODULE,
        /**
         * A statement of dynamic SQL ({@code EXEC(...)}, {@code sp_executesql}): no module source.
         */
        DYNAMIC
    }

    public enum TransactionState {
        NONE,
        OPEN,
        /**
         * Uncommittable ({@code XACT_STATE() = -1}): it can only be rolled back.
         */
        FAILED
    }

    /**
     * A parameter of the routine as the call declares it.
     *
     * @param name            name with {@code @}
     * @param declaration     T-SQL type of the variable that carries the value
     * @param output          an OUTPUT parameter (its value comes back)
     * @param valueAccepted   a value can be given (not a table-valued or cursor parameter)
     */
    public record Parameter(@NotNull String name, @NotNull String declaration, boolean output, boolean valueAccepted) {
    }

    /**
     * A module (procedure, function, trigger) in a database.
     */
    public record ModuleKey(int databaseId, long objectId) {
    }

    /**
     * @param name   database.schema.name
     * @param source the definition (sys.sql_modules), or null when it cannot be read (encrypted, no VIEW DEFINITION)
     */
    public record Module(@NotNull ModuleKey key, @NotNull String name, @Nullable String source) {
    }

    /**
     * What the trace tab needs before the first run.
     */
    public record Target(@NotNull Module routine, @NotNull List<Parameter> parameters) {
    }

    /**
     * A completed statement.
     *
     * @param sequence       order of completion, from 1
     * @param module         the module of a {@link StatementKind#MODULE} statement
     * @param objectType     XE object type (PROC, TRIGGER, ADHOC, ...), empty for the call
     * @param line           line in the module's definition (or in the dynamic SQL / call batch)
     * @param endMicros      completion time relative to the start of the call
     */
    public record TracedStatement(
        int sequence,
        @NotNull StatementKind kind,
        int nestLevel,
        @Nullable ModuleKey module,
        @NotNull String objectType,
        int line,
        @NotNull String text,
        long endMicros,
        long durationMicros,
        long cpuMicros,
        long rows,
        long logicalReads,
        long physicalReads,
        long writes
    ) {
        public long startMicros() {
            return Math.max(0, endMicros - durationMicros);
        }
    }

    /**
     * Result of a run.
     *
     * @param totalEvents    events the XE session recorded
     * @param droppedEvents  events the ring buffer did not keep (oldest first, or lost under load)
     * @param complete       the end marker was seen (all events of the call were read)
     * @param messages       server messages (PRINT, RAISERROR below severity 11)
     * @param resultSetRows  row counts of the result sets the procedure returned, in order
     * @param outputColumns  return value and OUTPUT parameters (empty if the batch was aborted)
     * @param error          the call's errors (one per line), or null
     */
    public record Result(
        @NotNull List<TracedStatement> statements,
        @NotNull Map<ModuleKey, Module> modules,
        long totalEvents,
        long droppedEvents,
        boolean complete,
        @NotNull List<String> messages,
        @NotNull List<Integer> resultSetRows,
        @NotNull List<String> outputColumns,
        @NotNull List<String> outputValues,
        @Nullable String error,
        boolean canceled
    ) {
    }

    /**
     * The login cannot create or read the trace's XE session.
     */
    public static final class PrivilegeException extends DBException {
        private final String loginName;
        private final List<String> statements;

        PrivilegeException(@NotNull String loginName, @NotNull List<String> statements, @NotNull Throwable cause) {
            super("Login " + loginName + " cannot use Extended Events sessions: " + String.join(" ", statements), cause);
            this.loginName = loginName;
            this.statements = statements;
        }

        @NotNull
        public String getLoginName() {
            return loginName;
        }

        /**
         * GRANT statements a server administrator runs.
         */
        @NotNull
        public List<String> getStatements() {
            return statements;
        }
    }

    private final SQLServerProcedure routine;
    @Nullable
    private JDBCExecutionContext controlContext;
    @Nullable
    private JDBCExecutionContext targetContext;

    public SQLServerExecutionTrace(@NotNull SQLServerProcedure routine) {
        this.routine = routine;
    }

    /**
     * True for a stored procedure of SQL Server (not Babelfish, Synapse or Sybase). No database access.
     */
    public static boolean canTrace(@Nullable DBSObject object) {
        if (!(object instanceof SQLServerProcedure procedure) || !procedure.isPersisted()
            || procedure.getProcedureType() != DBSProcedureType.PROCEDURE) {
            return false;
        }
        SQLServerDataSource dataSource = procedure.getDataSource();
        return SQLServerUtils.isDriverSqlServer(dataSource.getContainer().getDriver())
            && !SQLServerUtils.isDriverBabelfish(dataSource.getContainer().getDriver())
            && !dataSource.isSynapseDatabase();
    }

    /**
     * Reads the routine's definition and parameters.
     */
    @NotNull
    public static Target readTarget(@NotNull DBRProgressMonitor monitor, @NotNull SQLServerProcedure routine) throws DBException {
        SQLServerDataSource dataSource = routine.getDataSource();
        String database = getDatabaseName(routine);
        try (JDBCSession session = DBUtils.openMetaSession(monitor, dataSource, "Read routine to trace")) { //$NON-NLS-1$
            Connection connection = session.getOriginal();
            int databaseId;
            long objectId;
            try (PreparedStatement statement = connection.prepareStatement("SELECT DB_ID(?), OBJECT_ID(?)")) { //$NON-NLS-1$
                statement.setString(1, database);
                statement.setString(2, getRoutineName(routine));
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    databaseId = resultSet.getInt(1);
                    objectId = resultSet.getLong(2);
                    if (resultSet.wasNull() || databaseId == 0) {
                        throw new DBException("Procedure " + getRoutineName(routine) + " not found");
                    }
                }
            }
            ModuleKey key = new ModuleKey(databaseId, objectId);
            Module module = readModules(connection, databaseId, Set.of(objectId)).get(key);
            List<Parameter> parameters = new ArrayList<>();
            String query =
                "SELECT p.name, p.is_output, p.is_readonly, t.name AS type_name, ts.name AS type_schema, t.is_user_defined,\n" + //$NON-NLS-1$
                "t.is_table_type, p.max_length, p.precision, p.scale, p.is_cursor_ref\n" + //$NON-NLS-1$
                "FROM " + quoteName(database) + ".sys.parameters p\n" + //$NON-NLS-1$
                "JOIN " + quoteName(database) + ".sys.types t ON t.user_type_id = p.user_type_id\n" + //$NON-NLS-1$
                "JOIN " + quoteName(database) + ".sys.schemas ts ON ts.schema_id = t.schema_id\n" + //$NON-NLS-1$
                "WHERE p.object_id = ? AND p.parameter_id > 0 ORDER BY p.parameter_id"; //$NON-NLS-1$
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setLong(1, objectId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        boolean tableType = resultSet.getBoolean("is_table_type"); //$NON-NLS-1$
                        boolean cursor = resultSet.getBoolean("is_cursor_ref"); //$NON-NLS-1$
                        String declaration = cursor ? "CURSOR" : formatType( //$NON-NLS-1$
                            CommonUtils.notEmpty(resultSet.getString("type_name")), //$NON-NLS-1$
                            CommonUtils.notEmpty(resultSet.getString("type_schema")), //$NON-NLS-1$
                            resultSet.getBoolean("is_user_defined"), //$NON-NLS-1$
                            resultSet.getInt("max_length"), //$NON-NLS-1$
                            resultSet.getInt("precision"), //$NON-NLS-1$
                            resultSet.getInt("scale")); //$NON-NLS-1$
                        parameters.add(new Parameter(CommonUtils.notEmpty(resultSet.getString("name")), declaration, //$NON-NLS-1$
                            resultSet.getBoolean("is_output"), !tableType && !cursor)); //$NON-NLS-1$
                    }
                }
            }
            return new Target(module != null ? module : new Module(key, getRoutineName(routine), null), parameters);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    /**
     * Runs the routine once with the arguments and returns what the trace recorded. The XE session is always dropped
     * before this returns. The execution session stays open: ask {@link #getTransactionState}, then {@link #close}.
     *
     * @param values T-SQL expressions, one per parameter of {@link Target#parameters()}; empty: not passed (an input
     *               parameter takes its default) or NULL (an OUTPUT parameter)
     */
    @NotNull
    public Result run(
        @NotNull DBRProgressMonitor monitor,
        @NotNull List<Parameter> parameters,
        @NotNull List<String> values
    ) throws DBException {
        if (controlContext != null || targetContext != null) {
            throw new DBException("The trace has already run; close it first");
        }
        SQLServerDataSource dataSource = routine.getDataSource();
        String database = getDatabaseName(routine);
        String sessionName = null;
        boolean callStarted = false;
        boolean success = false;
        try {
            monitor.subTask("Open trace sessions"); //$NON-NLS-1$
            JDBCExecutionContext control = (JDBCExecutionContext) dataSource.getDefaultInstance()
                .openIsolatedContext(monitor, "Chromite execution trace control", null); //$NON-NLS-1$
            controlContext = control;
            // XE DDL and reads only: auto-commit, so that no transaction stays open in it
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(control);
            if (txnManager != null && !txnManager.isAutoCommit()) {
                txnManager.setAutoCommit(monitor, true);
            }
            JDBCExecutionContext target = (JDBCExecutionContext) dataSource.getDefaultInstance()
                .openIsolatedContext(monitor, "Chromite execution trace", null); //$NON-NLS-1$
            targetContext = target;

            int spid;
            try (JDBCSession session = target.openSession(monitor, DBCExecutionPurpose.UTIL, "Prepare traced session"); //$NON-NLS-1$
                 Statement statement = session.getOriginal().createStatement()) {
                // User-defined parameter types can only be declared in their database
                statement.execute("USE " + quoteName(database)); //$NON-NLS-1$
                try (ResultSet resultSet = statement.executeQuery("SELECT @@SPID")) { //$NON-NLS-1$
                    resultSet.next();
                    spid = resultSet.getInt(1);
                }
            }

            String marker = "chromite_trace_end_" + UUID.randomUUID().toString().replace("-", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sessionName = SESSION_PREFIX + spid + "_" + marker.substring(marker.length() - 12); //$NON-NLS-1$
            try (JDBCSession session = control.openSession(monitor, DBCExecutionPurpose.UTIL, "Create trace session")) { //$NON-NLS-1$
                Connection connection = session.getOriginal();
                checkEdition(connection);
                monitor.subTask("Create the Extended Events session"); //$NON-NLS-1$
                startEventSession(connection, sessionName, spid);
            }

            CallBatch batch = composeCall(parameters, values);
            log.debug("Traced call:\n" + batch.text()); //$NON-NLS-1$
            monitor.subTask("Run " + getRoutineName(routine)); //$NON-NLS-1$
            callStarted = true;
            CallOutcome outcome = executeCall(monitor, target, batch.text());

            // Every statement of the call is in the buffer once the marker's event is (one session, in order)
            boolean complete = runMarker(target, marker) && waitForMarker(monitor, control, sessionName, marker);
            RingBuffer buffer;
            try (JDBCSession session = control.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Read trace")) { //$NON-NLS-1$
                buffer = readRingBuffer(session.getOriginal(), sessionName);
            }
            dropEventSession(control, sessionName);
            sessionName = null;

            List<TracedStatement> statements = toStatements(buffer.events(), batch, marker);
            Map<ModuleKey, Module> modules;
            try (JDBCSession session = control.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Read traced modules")) { //$NON-NLS-1$
                modules = readModules(session.getOriginal(), statements);
            }
            success = true;
            return new Result(statements, modules, buffer.totalEvents(), buffer.droppedEvents(), complete, outcome.messages(),
                outcome.resultSetRows(), outcome.outputColumns(), outcome.outputValues(), outcome.error(), outcome.canceled());
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        } finally {
            if (sessionName != null && controlContext != null) {
                dropEventSession(controlContext, sessionName);
            }
            if (!success && !callStarted) {
                // The routine did not run: no transaction of its own to ask about
                close();
            }
            // After the call the execution session stays open even if reading the trace failed: its transaction is
            // ended only as the user says (isOpen, getTransactionState)
        }
    }

    /**
     * Transaction state of the execution session after {@link #run}.
     */
    @NotNull
    public TransactionState getTransactionState(@NotNull DBRProgressMonitor monitor) throws DBException {
        JDBCExecutionContext target = targetContext;
        if (target == null) {
            return TransactionState.NONE;
        }
        try (JDBCSession session = target.openSession(monitor, DBCExecutionPurpose.UTIL, "Read traced session transaction state"); //$NON-NLS-1$
             Statement statement = session.getOriginal().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT @@TRANCOUNT, XACT_STATE()")) { //$NON-NLS-1$
            resultSet.next();
            if (resultSet.getInt(2) == -1) {
                return TransactionState.FAILED;
            }
            return resultSet.getInt(1) > 0 ? TransactionState.OPEN : TransactionState.NONE;
        } catch (SQLException e) {
            throw new DBDatabaseException(e, routine.getDataSource());
        }
    }

    /**
     * Commits or rolls back the execution session's transaction. Statements, not the JDBC calls: a procedure can leave
     * a {@code BEGIN TRAN} open in an auto-commit session, where {@link Connection#commit} does nothing.
     */
    public void endTransaction(@NotNull DBRProgressMonitor monitor, boolean commit) throws DBException {
        JDBCExecutionContext target = targetContext;
        if (target == null) {
            return;
        }
        try (JDBCSession session = target.openSession(monitor, DBCExecutionPurpose.UTIL,
            commit ? "Commit traced transaction" : "Roll back traced transaction"); //$NON-NLS-1$ //$NON-NLS-2$
             Statement statement = session.getOriginal().createStatement()) {
            // COMMIT ends one level of nested BEGIN TRAN; ROLLBACK ends them all
            statement.execute(commit ? "WHILE @@TRANCOUNT > 0 COMMIT TRANSACTION" : "IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION"); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (SQLException e) {
            throw new DBDatabaseException(e, routine.getDataSource());
        }
    }

    /**
     * Closes both sessions. Ask about the execution session's transaction first: closing it rolls the transaction back.
     */
    public void close() {
        JDBCExecutionContext target = targetContext;
        if (target != null) {
            target.close();
            targetContext = null;
        }
        JDBCExecutionContext control = controlContext;
        if (control != null) {
            control.close();
            controlContext = null;
        }
    }

    public boolean isOpen() {
        return targetContext != null;
    }

    //////////////////////////////////////////////////////////
    // Event session

    private void checkEdition(@NotNull Connection connection) throws SQLException, DBException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT CAST(SERVERPROPERTY('EngineEdition') AS INT)")) { //$NON-NLS-1$
            if (resultSet.next() && resultSet.getInt(1) == ENGINE_AZURE_SQL_DATABASE) {
                throw new DBException("Azure SQL Database has no server-scoped Extended Events sessions; execution trace needs SQL Server or Azure SQL Managed Instance");
            }
        }
    }

    /**
     * Creates and starts the session, then reads its buffer once, before the call runs: a missing permission stops the
     * trace before the routine changes anything.
     */
    private void startEventSession(@NotNull Connection connection, @NotNull String sessionName, int spid) throws SQLException, DBException {
        String filter = "WHERE (sqlserver.session_id = (" + spid + "))"; //$NON-NLS-1$ //$NON-NLS-2$
        String ddl =
            "CREATE EVENT SESSION " + quoteName(sessionName) + " ON SERVER\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "ADD EVENT sqlserver.sp_statement_completed (" + filter + "),\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "ADD EVENT sqlserver.sql_statement_completed (" + filter + ")\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "ADD TARGET package0.ring_buffer (SET max_events_limit = (" + MAX_EVENTS + "), max_memory = (" + MAX_MEMORY_KB + "))\n" + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "WITH (MAX_DISPATCH_LATENCY = 1 SECONDS, STARTUP_STATE = OFF)"; //$NON-NLS-1$
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            statement.execute("ALTER EVENT SESSION " + quoteName(sessionName) + " ON SERVER STATE = START"); //$NON-NLS-1$ //$NON-NLS-2$
            readRingBuffer(connection, sessionName);
        } catch (SQLException e) {
            PrivilegeException privilege = describeMissingPrivileges(connection, e);
            if (privilege != null) {
                throw privilege;
            }
            throw e;
        }
    }

    /**
     * The missing permissions as a {@link PrivilegeException}, or null if the login has them (another error).
     */
    @Nullable
    private PrivilegeException describeMissingPrivileges(@NotNull Connection connection, @NotNull SQLException error) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                 "SELECT HAS_PERMS_BY_NAME(NULL, NULL, 'ALTER ANY EVENT SESSION'), HAS_PERMS_BY_NAME(NULL, NULL, 'VIEW SERVER STATE'), " + //$NON-NLS-1$
                 "SUSER_SNAME()")) { //$NON-NLS-1$
            if (!resultSet.next()) {
                return null;
            }
            String login = CommonUtils.notEmpty(resultSet.getString(3));
            List<String> grants = new ArrayList<>();
            if (resultSet.getInt(1) != 1) {
                grants.add("GRANT ALTER ANY EVENT SESSION TO " + quoteName(login) + ";"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (resultSet.getInt(2) != 1) {
                grants.add("GRANT VIEW SERVER STATE TO " + quoteName(login) + ";"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return grants.isEmpty() ? null : new PrivilegeException(login, grants, error);
        } catch (SQLException e) {
            log.debug("Cannot read the trace permissions", e); //$NON-NLS-1$
            return null;
        }
    }

    private static void dropEventSession(@NotNull JDBCExecutionContext control, @NotNull String sessionName) {
        try (JDBCSession session = control.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Drop trace session"); //$NON-NLS-1$
             PreparedStatement statement = session.getOriginal().prepareStatement(
                 "IF EXISTS (SELECT 1 FROM sys.server_event_sessions WHERE name = ?) DROP EVENT SESSION " + //$NON-NLS-1$
                 quoteName(sessionName) + " ON SERVER")) { //$NON-NLS-1$
            statement.setString(1, sessionName);
            statement.execute();
        } catch (SQLException e) {
            log.warn("Cannot drop the Extended Events session " + sessionName + " (drop it on the server)", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Runs the marker statement in the traced session.
     *
     * @return false if it could not run (the session is gone): the buffer is read without waiting
     */
    private static boolean runMarker(@NotNull JDBCExecutionContext target, @NotNull String marker) {
        // No table: it does not start an implicit transaction in a manual-commit session
        try (JDBCSession session = target.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "End trace"); //$NON-NLS-1$
             Statement statement = session.getOriginal().createStatement();
             ResultSet ignored = statement.executeQuery("SELECT 1 AS " + marker)) { //$NON-NLS-1$
            return true;
        } catch (SQLException e) {
            log.debug("Cannot run the trace end marker: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    private static boolean waitForMarker(
        @NotNull DBRProgressMonitor monitor,
        @NotNull JDBCExecutionContext control,
        @NotNull String sessionName,
        @NotNull String marker
    ) throws SQLException {
        monitor.subTask("Read the trace"); //$NON-NLS-1$
        long deadline = System.currentTimeMillis() + MARKER_WAIT_MS;
        try (JDBCSession session = control.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Wait for trace"); //$NON-NLS-1$
             PreparedStatement statement = session.getOriginal().prepareStatement(
                 "SELECT CHARINDEX(?, CAST(t.target_data AS NVARCHAR(MAX))) FROM sys.dm_xe_session_targets t\n" + //$NON-NLS-1$
                 "JOIN sys.dm_xe_sessions s ON s.address = t.event_session_address\n" + //$NON-NLS-1$
                 "WHERE s.name = ? AND t.target_name = N'ring_buffer'")) { //$NON-NLS-1$
            statement.setString(1, marker);
            statement.setString(2, sessionName);
            while (true) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next() && resultSet.getLong(1) > 0) {
                        return true;
                    }
                }
                if (System.currentTimeMillis() > deadline || monitor.isCanceled()) {
                    return false;
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    private record RawEvent(@NotNull String name, @Nullable Instant timestamp, @NotNull Map<String, String> data) {
        long getLong(@NotNull String field) {
            String value = data.get(field);
            if (value == null || value.isEmpty()) {
                return 0;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    private record RingBuffer(@NotNull List<RawEvent> events, long totalEvents, long droppedEvents) {
    }

    /**
     * Reads the ring buffer's XML in document order (the order the events completed).
     */
    @NotNull
    private static RingBuffer readRingBuffer(@NotNull Connection connection, @NotNull String sessionName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT CAST(t.target_data AS NVARCHAR(MAX)) FROM sys.dm_xe_session_targets t\n" + //$NON-NLS-1$
            "JOIN sys.dm_xe_sessions s ON s.address = t.event_session_address\n" + //$NON-NLS-1$
            "WHERE s.name = ? AND t.target_name = N'ring_buffer'")) { //$NON-NLS-1$
            statement.setString(1, sessionName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("The Extended Events session " + sessionName + " is not running");
                }
                try (Reader reader = resultSet.getCharacterStream(1)) {
                    return reader == null ? new RingBuffer(List.of(), 0, 0) : parseRingBuffer(reader);
                } catch (XMLStreamException | IOException e) {
                    throw new SQLException("Cannot read the Extended Events ring buffer", e);
                }
            }
        }
    }

    @NotNull
    private static RingBuffer parseRingBuffer(@NotNull Reader reader) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader xml = factory.createXMLStreamReader(reader);
        List<RawEvent> events = new ArrayList<>();
        long total = 0;
        long dropped = 0;
        long kept = 0;
        String eventName = null;
        Instant timestamp = null;
        Map<String, String> data = null;
        String dataName = null;
        StringBuilder value = null;
        try {
            while (xml.hasNext()) {
                int type = xml.next();
                if (type == XMLStreamConstants.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "RingBufferTarget" -> { //$NON-NLS-1$
                            total = parseLong(xml.getAttributeValue(null, "totalEventsProcessed")); //$NON-NLS-1$
                            kept = parseLong(xml.getAttributeValue(null, "eventCount")); //$NON-NLS-1$
                            dropped = parseLong(xml.getAttributeValue(null, "droppedCount")); //$NON-NLS-1$
                        }
                        case "event" -> { //$NON-NLS-1$
                            eventName = xml.getAttributeValue(null, "name"); //$NON-NLS-1$
                            timestamp = parseInstant(xml.getAttributeValue(null, "timestamp")); //$NON-NLS-1$
                            data = new HashMap<>();
                        }
                        case "data" -> dataName = xml.getAttributeValue(null, "name"); //$NON-NLS-1$ //$NON-NLS-2$
                        // object_type has a number <value> and its name in <text>: the name is read
                        case "value", "text" -> value = new StringBuilder(); //$NON-NLS-1$ //$NON-NLS-2$
                        default -> {
                        }
                    }
                } else if (type == XMLStreamConstants.CHARACTERS || type == XMLStreamConstants.CDATA) {
                    if (value != null) {
                        value.append(xml.getText());
                    }
                } else if (type == XMLStreamConstants.END_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "value", "text" -> { //$NON-NLS-1$ //$NON-NLS-2$
                            if (data != null && dataName != null && value != null) {
                                data.put(dataName, value.toString());
                            }
                            value = null;
                        }
                        case "data" -> dataName = null; //$NON-NLS-1$
                        case "event" -> { //$NON-NLS-1$
                            if (eventName != null && data != null) {
                                events.add(new RawEvent(eventName, timestamp, data));
                            }
                            eventName = null;
                            data = null;
                        }
                        default -> {
                        }
                    }
                }
            }
        } finally {
            xml.close();
        }
        // Events pushed out of the ring buffer by newer ones are not "dropped" for XE, but they are missing here
        return new RingBuffer(events, total, Math.max(dropped, total - Math.max(kept, events.size())));
    }

    private static long parseLong(@Nullable String text) {
        try {
            return text == null ? 0 : Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Nullable
    private static Instant parseInstant(@Nullable String text) {
        try {
            return text == null ? null : Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Turns the events into statements: the call batch's EXEC becomes the {@link StatementKind#CALL} row, the batch's
     * other statements (argument variables, the output SELECT) and the marker are left out.
     */
    @NotNull
    private static List<TracedStatement> toStatements(@NotNull List<RawEvent> events, @NotNull CallBatch batch, @NotNull String marker) {
        Instant base = null;
        for (RawEvent event : events) {
            if (event.timestamp() == null) {
                continue;
            }
            Instant start = event.timestamp().minusNanos(event.getLong("duration") * 1000); //$NON-NLS-1$
            if (base == null || start.isBefore(base)) {
                base = start;
            }
        }
        List<TracedStatement> statements = new ArrayList<>();
        for (RawEvent event : events) {
            String text = CommonUtils.notEmpty(event.data().get("statement")).strip(); //$NON-NLS-1$
            StatementKind kind;
            ModuleKey module = null;
            String objectType = ""; //$NON-NLS-1$
            int nestLevel;
            if (event.name().equals("sql_statement_completed")) { //$NON-NLS-1$
                if (text.contains(marker) || event.getLong("offset") / 2 != batch.callOffset()) { //$NON-NLS-1$
                    continue;
                }
                kind = StatementKind.CALL;
                nestLevel = 0;
            } else {
                objectType = CommonUtils.notEmpty(event.data().get("object_type")); //$NON-NLS-1$
                nestLevel = (int) event.getLong("nest_level"); //$NON-NLS-1$
                // Dynamic SQL has a hash of its text as object_id
                if (objectType.equals("ADHOC") || objectType.equals("PREPARED")) { //$NON-NLS-1$ //$NON-NLS-2$
                    kind = StatementKind.DYNAMIC;
                } else {
                    kind = StatementKind.MODULE;
                    module = new ModuleKey((int) event.getLong("source_database_id"), event.getLong("object_id")); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            long end = event.timestamp() == null || base == null ? 0
                : (event.timestamp().getEpochSecond() - base.getEpochSecond()) * 1_000_000
                + (event.timestamp().getNano() - base.getNano()) / 1000;
            statements.add(new TracedStatement(statements.size() + 1, kind, nestLevel, module, objectType,
                (int) event.getLong("line_number"), text, end, event.getLong("duration"), event.getLong("cpu_time"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                event.getLong("row_count"), event.getLong("logical_reads"), event.getLong("physical_reads"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                event.getLong("writes"))); //$NON-NLS-1$
        }
        return statements;
    }

    //////////////////////////////////////////////////////////
    // Call

    /**
     * @param callOffset character offset of the EXEC statement in the batch (XE reports byte offsets: twice this)
     */
    private record CallBatch(@NotNull String text, int callOffset) {
    }

    private record CallOutcome(
        @NotNull List<String> messages,
        @NotNull List<Integer> resultSetRows,
        @NotNull List<String> outputColumns,
        @NotNull List<String> outputValues,
        @Nullable String error,
        boolean canceled
    ) {
    }

    @NotNull
    private CallBatch composeCall(@NotNull List<Parameter> parameters, @NotNull List<String> values) {
        StringBuilder text = new StringBuilder();
        text.append("DECLARE ").append(CALL_RETURN_VARIABLE).append(" INT;\n"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> arguments = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = parameters.get(i);
            String value = i < values.size() ? CommonUtils.notEmpty(values.get(i)).strip() : ""; //$NON-NLS-1$
            boolean passed = !value.isEmpty() || parameter.output() || !parameter.valueAccepted();
            if (!passed) {
                // The parameter's default (an error from the server if it has none)
                continue;
            }
            String variable = CALL_VARIABLE_PREFIX + (i + 1);
            text.append("DECLARE ").append(variable).append(' ').append(parameter.declaration()); //$NON-NLS-1$
            if (!value.isEmpty() && parameter.valueAccepted()) {
                text.append(" = ").append(value); //$NON-NLS-1$
            }
            text.append(";\n"); //$NON-NLS-1$
            arguments.add(parameter.name() + " = " + variable + (parameter.output() ? " OUTPUT" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (parameter.output() && parameter.valueAccepted()) {
                outputs.add(variable + " AS " + quoteName(parameter.name())); //$NON-NLS-1$
            }
        }
        int callOffset = text.length();
        text.append("EXEC ").append(CALL_RETURN_VARIABLE).append(" = ").append(getRoutineName(routine)); //$NON-NLS-1$ //$NON-NLS-2$
        if (!arguments.isEmpty()) {
            text.append(' ').append(String.join(", ", arguments)); //$NON-NLS-1$
        }
        text.append(";\n"); //$NON-NLS-1$
        text.append("SELECT ").append(CALL_RETURN_VARIABLE).append(" AS ").append(quoteName(OUTPUT_RETURN_COLUMN)); //$NON-NLS-1$ //$NON-NLS-2$
        for (String output : outputs) {
            text.append(", ").append(output); //$NON-NLS-1$
        }
        text.append(';');
        return new CallBatch(text.toString(), callOffset);
    }

    /**
     * Runs the call batch, reading every result and the server messages, also past statement errors. The batch's output
     * SELECT (first column {@link #OUTPUT_RETURN_COLUMN}) carries the return value and OUTPUT parameters; the other result
     * sets are the procedure's (only their row counts are kept).
     * A canceled monitor cancels the statement (a watcher thread, as in {@link SQLServerBackupRestore}).
     */
    @NotNull
    private static CallOutcome executeCall(
        @NotNull DBRProgressMonitor monitor,
        @NotNull JDBCExecutionContext target,
        @NotNull String batch
    ) {
        List<String> messages = new ArrayList<>();
        List<Integer> resultSetRows = new ArrayList<>();
        List<String> outputColumns = new ArrayList<>();
        List<String> outputValues = new ArrayList<>();
        String error = null;
        AtomicBoolean canceled = new AtomicBoolean();
        try (JDBCSession session = target.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.USER, "Trace: run routine"); //$NON-NLS-1$
             Statement statement = session.getOriginal().createStatement()) {
            AtomicBoolean finished = new AtomicBoolean();
            Thread watcher = new Thread(() -> {
                while (!finished.get()) {
                    if (monitor.isCanceled()) {
                        canceled.set(true);
                        try {
                            statement.cancel();
                        } catch (SQLException e) {
                            log.debug("Cannot cancel the traced call", e); //$NON-NLS-1$
                        }
                        return;
                    }
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "Execution trace cancel"); //$NON-NLS-1$
            watcher.setDaemon(true);
            watcher.start();
            List<ResultData> results = new ArrayList<>();
            Set<String> errors = new LinkedHashSet<>();
            try {
                boolean hasResults;
                try {
                    hasResults = statement.execute(batch);
                } catch (SQLException e) {
                    errors.add(describe(e));
                    hasResults = canceled.get() ? false : statement.getMoreResults();
                }
                // A statement error does not stop the batch on the server: the driver reports it in place and the
                // following results (messages after it, the output SELECT) are read on
                while (true) {
                    try {
                        collectWarnings(statement, messages);
                        if (hasResults) {
                            results.add(readResultSet(statement.getResultSet()));
                        } else if (statement.getUpdateCount() == -1) {
                            break;
                        }
                        hasResults = statement.getMoreResults();
                    } catch (SQLException e) {
                        collectWarnings(statement, messages);
                        errors.add(describe(e));
                        if (canceled.get() || errors.size() >= MAX_ERRORS) {
                            break;
                        }
                        hasResults = statement.getMoreResults();
                    }
                }
            } catch (SQLException e) {
                // The results cannot be read further (canceled, connection lost)
                errors.add(describe(e));
            } finally {
                finished.set(true);
                watcher.interrupt();
            }
            // The output SELECT is missing when the batch was aborted (or canceled): then all result sets are the procedure's
            if (!results.isEmpty() && !results.getLast().columns().isEmpty()
                && OUTPUT_RETURN_COLUMN.equals(results.getLast().columns().getFirst())) {
                ResultData outputs = results.removeLast();
                outputColumns.addAll(outputs.columns());
                outputValues.addAll(outputs.firstRow());
            }
            for (ResultData result : results) {
                resultSetRows.add(result.rows());
            }
            if (!errors.isEmpty()) {
                error = String.join("\n", errors); //$NON-NLS-1$
            }
        } catch (SQLException e) {
            error = describe(e);
        }
        return new CallOutcome(messages, resultSetRows, outputColumns, outputValues, error, canceled.get());
    }

    private record ResultData(@NotNull List<String> columns, @NotNull List<String> firstRow, int rows) {
    }

    @NotNull
    private static ResultData readResultSet(@Nullable ResultSet resultSet) throws SQLException {
        List<String> columns = new ArrayList<>();
        List<String> firstRow = new ArrayList<>();
        if (resultSet == null) {
            return new ResultData(columns, firstRow, 0);
        }
        int rows = 0;
        try (resultSet) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                columns.add(metaData.getColumnLabel(i));
            }
            while (resultSet.next()) {
                if (rows == 0) {
                    for (int i = 1; i <= columns.size(); i++) {
                        firstRow.add(CommonUtils.truncateString(resultSet.getString(i), MAX_OUTPUT_LENGTH));
                    }
                }
                rows++;
            }
        }
        return new ResultData(columns, firstRow, rows);
    }

    private static void collectWarnings(@NotNull Statement statement, @NotNull List<String> messages) {
        try {
            for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning()) {
                messages.add(warning.getMessage());
            }
            statement.clearWarnings();
        } catch (SQLException e) {
            log.debug("Cannot read the messages of the traced call", e); //$NON-NLS-1$
        }
    }

    @NotNull
    private static String describe(@NotNull SQLException error) {
        Set<String> messages = new LinkedHashSet<>();
        for (SQLException e = error; e != null; e = e.getNextException()) {
            messages.add(CommonUtils.notEmpty(e.getMessage()));
        }
        return String.join("\n", messages); //$NON-NLS-1$
    }

    //////////////////////////////////////////////////////////
    // Modules

    @NotNull
    private static Map<ModuleKey, Module> readModules(@NotNull Connection connection, @NotNull List<TracedStatement> statements) {
        Map<Integer, Set<Long>> objects = new LinkedHashMap<>();
        for (TracedStatement statement : statements) {
            if (statement.module() != null) {
                objects.computeIfAbsent(statement.module().databaseId(), id -> new LinkedHashSet<>()).add(statement.module().objectId());
            }
        }
        Map<ModuleKey, Module> modules = new HashMap<>();
        for (Map.Entry<Integer, Set<Long>> entry : objects.entrySet()) {
            try {
                modules.putAll(readModules(connection, entry.getKey(), entry.getValue()));
            } catch (SQLException e) {
                log.debug("Cannot read the traced modules of database " + entry.getKey() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        // Modules that could not be read are shown by number
        for (Map.Entry<Integer, Set<Long>> entry : objects.entrySet()) {
            for (Long objectId : entry.getValue()) {
                ModuleKey key = new ModuleKey(entry.getKey(), objectId);
                modules.putIfAbsent(key, new Module(key, "#" + entry.getKey() + "." + objectId, null)); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return modules;
    }

    @NotNull
    private static Map<ModuleKey, Module> readModules(@NotNull Connection connection, int databaseId, @NotNull Set<Long> objectIds)
        throws SQLException {
        String database;
        try (PreparedStatement statement = connection.prepareStatement("SELECT DB_NAME(?)")) { //$NON-NLS-1$
            statement.setInt(1, databaseId);
            try (ResultSet resultSet = statement.executeQuery()) {
                database = resultSet.next() ? resultSet.getString(1) : null;
            }
        }
        Map<ModuleKey, Module> modules = new HashMap<>();
        if (database == null) {
            return modules;
        }
        StringBuilder ids = new StringBuilder();
        for (Long id : objectIds) {
            if (!ids.isEmpty()) {
                ids.append(',');
            }
            ids.append(id.longValue());
        }
        String query =
            "SELECT o.object_id, s.name AS schema_name, o.name, m.definition\n" + //$NON-NLS-1$
            "FROM " + quoteName(database) + ".sys.objects o\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "JOIN " + quoteName(database) + ".sys.schemas s ON s.schema_id = o.schema_id\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "LEFT JOIN " + quoteName(database) + ".sys.sql_modules m ON m.object_id = o.object_id\n" + //$NON-NLS-1$ //$NON-NLS-2$
            "WHERE o.object_id IN (" + ids + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(query)) {
            while (resultSet.next()) {
                ModuleKey key = new ModuleKey(databaseId, resultSet.getLong(1));
                modules.put(key, new Module(key, database + "." + resultSet.getString(2) + "." + resultSet.getString(3), //$NON-NLS-1$ //$NON-NLS-2$
                    resultSet.getString(4)));
            }
        }
        return modules;
    }

    //////////////////////////////////////////////////////////
    // Names

    @NotNull
    private static String getDatabaseName(@NotNull SQLServerProcedure routine) {
        return routine.getContainer().getDatabase().getName();
    }

    /**
     * [database].[schema].[name]
     */
    @NotNull
    private static String getRoutineName(@NotNull SQLServerProcedure routine) {
        return quoteName(getDatabaseName(routine)) + "." + quoteName(routine.getContainer().getName()) + "." //$NON-NLS-1$ //$NON-NLS-2$
            + quoteName(routine.getName());
    }

    @NotNull
    private static String quoteName(@NotNull String name) {
        return SQLServerBackupRestore.quoteName(name);
    }

    /**
     * The T-SQL type of a parameter as a variable declaration (lengths of sys.parameters are bytes, -1 = MAX).
     */
    @NotNull
    static String formatType(@NotNull String typeName, @NotNull String typeSchema, boolean userDefined, int maxLength, int precision, int scale) {
        if (userDefined) {
            return quoteName(typeSchema) + "." + quoteName(typeName); //$NON-NLS-1$
        }
        return switch (typeName.toLowerCase(Locale.ROOT)) {
            case "varchar", "char", "varbinary", "binary" -> //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                typeName + "(" + (maxLength == -1 ? "MAX" : String.valueOf(maxLength)) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "nvarchar", "nchar" -> //$NON-NLS-1$ //$NON-NLS-2$
                typeName + "(" + (maxLength == -1 ? "MAX" : String.valueOf(maxLength / 2)) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "decimal", "numeric" -> typeName + "(" + precision + ", " + scale + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            case "datetime2", "time", "datetimeoffset" -> typeName + "(" + scale + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            default -> typeName;
        };
    }
}
