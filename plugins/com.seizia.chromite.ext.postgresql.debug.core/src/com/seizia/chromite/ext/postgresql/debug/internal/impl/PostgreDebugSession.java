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

package com.seizia.chromite.ext.postgresql.debug.internal.impl;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.debug.*;
import com.seizia.chromite.debug.jdbc.DBGJDBCSession;
import com.seizia.chromite.ext.postgresql.PostgreConstants;
import com.seizia.chromite.ext.postgresql.debug.PostgreDebugConstants;
import com.seizia.chromite.ext.postgresql.debug.core.PostgreSqlDebugCore;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.model.PostgreProcedure;
import com.seizia.chromite.ext.postgresql.model.PostgreProcedureParameter;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;
import org.jkiss.utils.CommonUtils;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Typical scenario for debug session <br/>
 * <br/>
 * 0. create session (now it can only attached to target Procedure)<br/>
 * <br/>
 * 1. attach to target this method attaches to a debugging target and listening
 * on the given port - waiting for run procedure in other session(s) debugger
 * client should invoke this function after creation also created implicit
 * breakpoint for target procedure, after this call debug session in
 * <b>WAITING</b> state - isWaiting returns true<br/>
 * <br/>
 * 2. when target procedure will called debug session implicit breakpoint will
 * be reached and session goes in state <b>READY</b>; in this state possible to call getStack, getVariables, setVariables,
 * setBreakpoint or execStepXXX\continue<br/>
 * <br/>
 * 3. when execStepXXX or continue will called session goes in <b>WAITING</b>
 * state until next breakpoint or end of procedure will be reached <br/>
 * <p>
 * 설계(Swing 디버거, 확인한 pldbgapi 동작은 docs/chromite/procedure-debugger.md "pldbgapi 동작"):
 * <ul>
 *     <li>연결: 제어 연결(pldbg_* 호출)과 대상 연결(로컬 모드에서 함수를 실행)을 연결의 격리 문맥으로 연다. 제어 연결은 디버거 API만
 *     부르므로 자동 커밋으로 둔다(새 연결이라 열린 트랜잭션이 없다). 그러지 않으면 수동 커밋 연결에서 제어 연결에도 트랜잭션이 열려
 *     "커밋하지 않은 트랜잭션" 질문에 나온다. 대상 연결은 연결 설정의 커밋 방식을 그대로 따른다(함수가 바꾼 데이터를 언제 커밋할지는 사용자 몫).</li>
 *     <li>끝내기: SWT 버전은 함수가 끝나면 대상 연결을 바로 닫아 수동 커밋 트랜잭션을 조용히 롤백했다. 여기서는 대상 연결을 열어 두고
 *     {@link #getTransactionState}(대상 pid의 {@code pg_stat_activity.state})로 UI가 묻게 한 뒤 {@link #closeSession}에서 닫는다.</li>
 *     <li>멈추기({@link #abort}): 멈춘 대상은 {@code pldbg_abort_target}, 실행 중인 대상은 대상 문장의 JDBC 취소. 실행 중에는 제어 연결이
 *     단계 명령으로 바쁘고, 실행 중인 {@code pldbg_continue}를 취소하면 대상이 디버거 없이 끝까지 실행되기 때문이다. 대상이 끝나 단계 명령이
 *     실패한 뒤에는 abort를 부르지 않는다(대상이 없으면 그 호출이 돌아오지 않는다).</li>
 *     <li>attach 모드: 전역 중단점(함수 시작, 대상 pid를 주면 그 세션만) 뒤 {@code pldbg_wait_for_target}으로 기다린다. 기다리는 중이나
 *     실행 중에 멈추면 그 명령만 취소한다(다른 세션의 호출은 디버거 없이 계속). 끝에 전역 중단점을 {@code pldbg_drop_breakpoint}로 지우지
 *     않는다: 대상이 끝난 뒤에는 그 호출이 돌아오지 않고, 제어 연결을 닫으면 서버가 그 리스너의 중단점을 지운다(확인함).</li>
 *     <li>중단점 SQL은 SWT 버전처럼 늘 디버깅하는 함수의 OID를 쓰지 않고 중단점의 함수 OID를 쓴다(호출된 다른 함수에도 놓을 수 있게).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제)하며 위 설계로 고침: 대상 연결을 바로 닫지 않음, 제어 연결 자동 커밋,
 *   abort/취소 구분, attach 모드 대상 pid, 중단점의 함수 OID, 메시지·결과·형식 이름, 프로시저 호출은 JDBC escape 대신 CALL
 *   (docs/chromite/procedure-debugger.md)
 */
public class PostgreDebugSession extends DBGJDBCSession {
    private final JDBCExecutionContext controllerConnection;

    private long functionOid = -1;
    private int sessionId = -1;

    private PostgreDebugAttachKind attachKind = PostgreDebugAttachKind.UNKNOWN;
    private DBGSessionInfo sessionInfo;

    /**
     * Local mode: the connection that runs the function, kept open until {@link #closeSession} (see the class comment).
     */
    @Nullable
    private volatile JDBCExecutionContext targetConnection;
    @Nullable
    private volatile Statement targetStatement;
    private volatile Job localWorkerJob = null;
    @Nullable
    private volatile DBGTargetResult targetResult;
    /**
     * The target is gone (ended, canceled): debugger calls that talk to it would not return.
     */
    private volatile boolean targetGone;

    /**
     * Server messages of the target not handed out yet, and the last warning of the target statement already read.
     */
    private final List<String> pendingMessages = new ArrayList<>();
    @Nullable
    private SQLWarning lastWarning;

    private PostgreDebugBreakpointDescriptor bpGlobal;

    private static final int LOCAL_WAIT_MS = 500; // 0.5 sec

    private static final int LOCAL_TIMEOT_MS = 50 * (2 * LOCAL_WAIT_MS); // 50 sec

    /**
     * How long Stop waits for the target to end after aborting it, before it cancels the target's statement.
     */
    private static final int ABORT_WAIT_MS = 5000;

    /**
     * Rows of the call's result kept for the debugger window.
     */
    private static final int MAX_RESULT_ROWS = 200;

    private static final String MAGIC_PORT = "PLDBGBREAK";

    private static final String SQL_CHECK_PLUGIN = "select 'Server version: ' || serverversionstr || '.\nProxy API version: ' ||  proxyapiver from pldbg_get_proxy_info()";

    private static final String SQL_ATTACH = "select pldbg_wait_for_target(?sessionid)";
    private static final String SQL_ATTACH_TO_PORT = "select pldbg_attach_to_port(?portnumber)";
    private static final String SQL_PREPARE_SLOT = " select pldbg_oid_debug(?objectid)";
    private static final String SQL_LISTEN = "select pldbg_create_listener() as sessionid";
    private static final String SQL_GET_SRC = "select pldbg_get_source(?sessionid,?oid)";
    private static final String SQL_GET_VARS = "select name, varclass, linenumber, isunique, isconst, isnotnull, dtype, "
        + "pg_catalog.format_type(dtype, NULL) as typename, value from pldbg_get_variables(?sessionid)";
    private static final String SQL_SET_VAR = "select pldbg_deposit_value(?,?,?,?)";
    private static final String SQL_GET_STACK = "select * from pldbg_get_stack(?sessionid)";
    private static final String SQL_SELECT_FRAME = "select * from pldbg_select_frame(?sessionid,?frameno)";
    private static final String SQL_STEP_OVER = "select pldbg_step_over(?sessionid)";
    private static final String SQL_STEP_INTO = "select pldbg_step_into(?sessionid)";
    private static final String SQL_CONTINUE = "select pldbg_continue(?sessionid)";
    private static final String SQL_ABORT = "select pldbg_abort_target(?sessionid)";
    private static final String SQL_SET_GLOBAL_BREAKPOINT = "select pldbg_set_global_breakpoint(?sessionid, ?obj, ?line, ?target)";
    private static final String SQL_SET_BREAKPOINT = "select pldbg_set_breakpoint(?sessionid, ?obj, ?line)";
    private static final String SQL_DROP_BREAKPOINT = "select pldbg_drop_breakpoint(?sessionid, ?obj, ?line)";

    private static final String SQL_CURRENT_SESSION =
        "SELECT pid,usename,application_name,state,query\n" +
            "FROM pg_stat_activity WHERE pid = pg_backend_pid()"; //$NON-NLS-1$
    private static final String SQL_SESSION_STATE = "SELECT state FROM pg_catalog.pg_stat_activity WHERE pid = ?"; //$NON-NLS-1$
    private static final String STATE_IDLE_IN_TRANSACTION = "idle in transaction"; //$NON-NLS-1$
    private static final String STATE_IDLE_IN_FAILED_TRANSACTION = "idle in transaction (aborted)"; //$NON-NLS-1$

    private static final Log log = Log.getLog(PostgreDebugSession.class);

    /**
     * Create session with two description after creation session need to be
     * attached to postgres procedure by attach method
     */
    PostgreDebugSession(DBRProgressMonitor monitor, DBGBaseController controller)
        throws DBGException {
        super(controller);
        log.debug("Creating controller session.");

        PostgreDataSource dataSource = (PostgreDataSource) controller.getDataSourceContainer().getDataSource();
        try {
            PostgreProcedure function = PostgreSqlDebugCore.resolveFunction(monitor, controller.getDataSourceContainer(), controller.getDebugConfiguration());
            PostgreDatabase instance = function.getDatabase();
            this.controllerConnection = (JDBCExecutionContext) instance.openIsolatedContext(monitor, "Debug controller session", null);
            // Only debugger API calls run here: auto-commit, so that no transaction stays open in it (a new connection has none yet)
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(controllerConnection);
            if (txnManager != null && !txnManager.isAutoCommit()) {
                txnManager.setAutoCommit(monitor, true);
            }
            log.debug("Debug controller session created.");
        } catch (DBException e) {
            log.debug(String.format("Error creating debug session %s", e.getMessage()));
            throw new DBGException(e, dataSource);
        }
    }

    @Override
    public JDBCExecutionContext getControllerConnection() {
        return controllerConnection;
    }

    private PostgreDebugSessionInfo getSessionDescriptor(DBRProgressMonitor monitor, JDBCExecutionContext connection) throws DBGException {
        try (JDBCSession session = connection.openSession(monitor, DBCExecutionPurpose.UTIL, "Read session info")) {
            try (Statement stmt = session.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(SQL_CURRENT_SESSION)) {
                    if (rs.next()) {
                        int pid = rs.getInt("pid");
                        String usename = rs.getString("usename");
                        String applicationName = rs.getString("application_name");
                        String state = rs.getString("state");
                        String query = rs.getString("query");
                        return new PostgreDebugSessionInfo(pid, usename, applicationName, state, query);
                    }
                    throw new DBGException("Error getting session");
                }
            }
        } catch (SQLException e) {
            throw new DBGException("SQL error", e);
        }
    }

    @Nullable
    private Integer tryParsePortNumber(@Nullable String notice) {
        if (notice != null && notice.startsWith(MAGIC_PORT)) {
            try {
                Integer result = Integer.valueOf(notice.substring(MAGIC_PORT.length() + 1).trim());
                log.debug(String.format("Catch local port number %d", result));
                return result;
            } catch (Exception e) {
                log.debug(String.format("Error catching local port number %s", e.getMessage()));
                return null;
            }
        }
        return null;
    }

    private int attachToPort(@NotNull DBRProgressMonitor monitor, int localPortNumber) throws DBGException {
        // Use controller connection
        String sql = SQL_ATTACH_TO_PORT.replaceAll("\\?portnumber", String.valueOf(localPortNumber));
        log.debug(String.format("Attach to local port number %d", localPortNumber));
        try (JDBCSession session = getControllerConnection().openSession(monitor, DBCExecutionPurpose.UTIL, "Attach to port")) {
            try (Statement stmt = session.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    if (rs.next()) {
                        int attResult = rs.getInt(1);
                        log.debug(String.format("Attached to local port %d", attResult));
                        return attResult;
                    }
                    log.debug("Error while attaching to port");
                    throw new DBGException("Error while attaching to port");
                }
            }
        } catch (SQLException e) {
            log.debug("Error while attaching to port");
            throw new DBGException("Error attaching to port", e);
        }
    }

    private String createSlot(DBRProgressMonitor monitor, JDBCExecutionContext connection, PostgreProcedure function) throws DBGException {

        String objId = String.valueOf(function.getObjectId());
        String sql = SQL_PREPARE_SLOT.replaceAll("\\?objectid", objId);
        log.debug(String.format("Create slot for object ID %s", objId));
        try (JDBCSession session = connection.openSession(monitor, DBCExecutionPurpose.UTIL, "Attach to port")) {
            try (Statement stmt = session.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    if (!rs.next()) {
                        log.debug("Error creating target slot");
                        throw new DBGException("Error creating target slot");
                    }
                    String dbgOID = rs.getString(1);
                    log.debug(String.format("Create slot OID %s", dbgOID));
                    return dbgOID;
                }
            }
        } catch (SQLException e) {
            log.debug(String.format("Error creating target %s", e.getMessage()));
            throw new DBGException("Error creating target", e);
        }
    }

    /**
     * Wait for port number passed from main executed statement
     */
    @NotNull
    private Integer waitPortNumber(@NotNull CompletableFuture<Statement> asyncStatement) throws DBGException {
        int totalWaitMs = 0;
        log.debug(String.format("Waiting for port number with timeout %d", LOCAL_TIMEOT_MS));
        try {
            Statement statement = asyncStatement.get();

            log.debug("Start local port waiting....");
            SQLWarning warn = null;
            while (!statement.isClosed() && totalWaitMs < LOCAL_TIMEOT_MS && warn == null) {
                warn = statement.getWarnings(); // poll the first warn on the statement itself
                if (warn == null) {
                    Thread.sleep(LOCAL_WAIT_MS);
                    totalWaitMs += LOCAL_WAIT_MS;
                }
            }

            if (warn != null) {
                log.debug("First warning received");
                do {
                    log.debug(String.format("Parsing warning %s", warn.getMessage()));
                    Integer localPort = tryParsePortNumber(warn.getMessage());
                    if (localPort !=  null) {
                        log.debug("Local port obtained");
                        return localPort;
                    } else {
                        // we cannot use clearWarnings() for two reasons:
                        // 1. warnings added between calls to getWarnings() and clearWarnings() may be missed,
                        //    so Postgresql JDBC Driver repository recommends to poll on getNextWarning()
                        //    (see https://github.com/pgjdbc/pgjdbc/blob/5c9928d81e4a337518c1e1104ea17f4b29269320/pgjdbc/src/main/java/org/postgresql/jdbc/PgStatement.java#L652)
                        // 2. clearWarnings() cannot be used to release handled warns' memory even while polling with the getNextWarning(),
                        //    because the chain of warnings will be disrupted after the clearWarnings() call due to its implementation flaw,
                        //    making the following warnings unobservable with getNextWarning(), and so forcing us to getWarnings() again,
                        //    but see the previous point above.
                        log.debug(String.format("Waiting for the next warning %s", warn.getMessage()));
                        SQLWarning nextWarn = null;
                        while (!statement.isClosed() && totalWaitMs < LOCAL_TIMEOT_MS && nextWarn == null) {
                            nextWarn = warn.getNextWarning(); // poll for the consequent warns on the last processed in a chain
                            if (nextWarn == null) {
                                Thread.sleep(LOCAL_WAIT_MS);
                                totalWaitMs += LOCAL_WAIT_MS;
                            }
                        }
                        warn = nextWarn;
                    }
                } while (!statement.isClosed() && totalWaitMs < LOCAL_TIMEOT_MS);
            }
        } catch (ExecutionException e) {
            log.debug(String.format("Error rcv port number %s", e.getMessage()));
            throw new DBGException(e.getCause() == null ? "Error rcv port number" : e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException | SQLException e) {
            log.debug(String.format("Error rcv port number %s", e.getMessage()));
            throw new DBGException("Error rcv port number", e);
        }

        log.debug("Unable to rcv port number");
        throw new DBGException("Unable to rcv port number");
    }

    /**
     * The statement that calls the function: {@code SELECT f(args)} or {@code CALL p(args)}. Argument values are SQL
     * expressions as the user typed them (as the SWT version and the query window's parameter dialog). A procedure's
     * OUT arguments get NULL (PostgreSQL 14+ requires them in CALL). A plain {@code CALL}, not the JDBC escape
     * {@code {call}}, which the driver turns into a {@code SELECT} that fails for procedures.
     */
    @NotNull
    private static String composeCall(
        @NotNull DBRProgressMonitor monitor,
        @NotNull PostgreProcedure function,
        @NotNull List<String> paramValues
    ) throws DBException {
        List<PostgreProcedureParameter> inputs = function.getInputParameters();
        if (inputs.size() != paramValues.size()) {
            throw new DBGException("Parameter value count (" + paramValues.size() + ") doesn't match actual function parameters (" + inputs.size() + ")");
        }
        boolean procedure = function.getProcedureType() == DBSProcedureType.PROCEDURE;
        StringBuilder query = new StringBuilder(procedure ? "CALL " : "SELECT "); //$NON-NLS-1$ //$NON-NLS-2$
        query.append(function.getFullyQualifiedName(DBPEvaluationContext.DML)).append('(');
        int inputIndex = 0;
        boolean first = true;
        for (PostgreProcedureParameter parameter : CommonUtils.safeCollection(function.getParameters(monitor))) {
            String value;
            if (parameter.getParameterKind().isInput()) {
                value = paramValues.get(inputIndex++);
                if (CommonUtils.isEmptyTrimmed(value)) {
                    throw new DBGException("Function parameter '" + parameter.getName() + "' was not set");
                }
            } else if (procedure && parameter.getParameterKind().isOutput()) {
                value = "NULL"; //$NON-NLS-1$
            } else {
                continue;
            }
            if (!first) {
                query.append(", "); //$NON-NLS-1$
            }
            first = false;
            query.append(value);
        }
        return query.append(')').toString();
    }

    @NotNull
    protected Job runLocalProc(
        @NotNull JDBCExecutionContext connection,
        @NotNull String callSql,
        @NotNull String name,
        @NotNull CompletableFuture<Statement> asyncStatement
    ) {
        Job job = new AbstractJob(name) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<String> columns = new ArrayList<>();
                List<List<String>> rows = new ArrayList<>();
                String error = null;
                boolean canceled = false;
                try (JDBCSession session = connection.openSession(monitor, DBCExecutionPurpose.USER, "Debug: run function")) {
                    try (JDBCStatement statement = session.createStatement()) {
                        targetStatement = statement;
                        log.debug(String.format("Prepared local call %s", callSql));
                        asyncStatement.complete(statement);
                        boolean hasResult = statement.execute(callSql);
                        log.debug("Local statement executed");
                        if (hasResult) {
                            try (ResultSet rs = statement.getResultSet()) {
                                ResultSetMetaData metaData = rs.getMetaData();
                                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                                    columns.add(metaData.getColumnLabel(i));
                                }
                                while (rows.size() < MAX_RESULT_ROWS && rs.next()) {
                                    List<String> row = new ArrayList<>(columns.size());
                                    for (int i = 1; i <= columns.size(); i++) {
                                        row.add(rs.getString(i));
                                    }
                                    rows.add(row);
                                }
                            }
                        }
                    } finally {
                        Statement statement = targetStatement;
                        if (statement != null) {
                            collectMessages(statement);
                        }
                        targetStatement = null;
                    }
                } catch (Exception e) {
                    log.debug("Error execute local statement: " + e.getMessage());
                    if (!asyncStatement.isDone()) {
                        asyncStatement.completeExceptionally(e);
                    }
                    String sqlState = e instanceof SQLException sqlError ? sqlError.getSQLState() : null;
                    canceled = PostgreConstants.EC_QUERY_CANCELED.equals(sqlState);
                    error = e.getMessage();
                } finally {
                    // The connection stays open: its transaction is ended as the user says (getTransactionState, endTransaction)
                    targetGone = true;
                    targetResult = new DBGTargetResult(columns, rows, error, canceled);
                    fireEvent(new DBGEvent(PostgreDebugSession.this, DBGEvent.TERMINATE, DBGEvent.CLIENT_REQUEST));
                }
                return Status.OK_STATUS;
            }
        };
        job.schedule();
        return job;
    }

    private void attachLocal(DBRProgressMonitor monitor, PostgreProcedure function, List<String> parameters) throws DBGException {

        try {
            String callSql = composeCall(monitor, function, parameters);
            JDBCExecutionContext connection = (JDBCExecutionContext) function.getDatabase().openIsolatedContext(monitor, "Debug process session", null);
            targetConnection = connection;
            log.debug("Attaching locally....");
            this.sessionInfo = getSessionDescriptor(monitor, connection);

            createSlot(monitor, connection, function);

            String taskName = "PostgreSQL Debug - Local session " + sessionInfo.getID();

            CompletableFuture<Statement> asyncStatement = new CompletableFuture<>();
            Job job = runLocalProc(connection, callSql, taskName, asyncStatement);
            localWorkerJob = job;

            Integer portNumber;
            try {
                portNumber = waitPortNumber(asyncStatement);
            } catch (DBGException e) {
                // The call failed before it reached the debugger (an argument expression, a permission): show its error
                job.join(ABORT_WAIT_MS, null);
                DBGTargetResult result = targetResult;
                if (result != null && result.errorMessage() != null) {
                    throw new DBGException(result.errorMessage(), e);
                }
                throw e;
            }

            sessionId = attachToPort(monitor, portNumber);
            log.debug(String.format("Attached local session UD = %d", sessionId));
            getController().fireEvent(new DBGEvent(this, DBGEvent.SUSPEND, DBGEvent.MODEL_SPECIFIC));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DBGException("Interrupted while starting the debug session", e);
        } catch (DBException e) {
            if (e instanceof DBGException) {
                throw (DBGException) e;
            }
            throw new DBGException("Error opening debug session", e);
        }

    }

    private void attachGlobal(DBRProgressMonitor monitor, long oid, @Nullable Integer targetPID) throws DBGException {

        log.debug("Attaching globally....");

        try (JDBCSession session = getControllerConnection().openSession(monitor, DBCExecutionPurpose.UTIL, "Attach global")) {
            try (Statement stmt = session.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(SQL_LISTEN)) {

                    if (rs.next()) {
                        sessionId = rs.getInt("sessionid");
                        log.debug(String.format("Global session ID %d", sessionId));
                    } else {
                        log.debug("Unable to create debug instance");
                        throw new DBGException("Unable to create debug instance");
                    }

                }
            }
        } catch (SQLException e) {
            throw new DBGException("SQL error", e);
        }

        bpGlobal = new PostgreDebugBreakpointDescriptor(oid, -1, targetPID == null ? -1 : targetPID);
        addBreakpoint(monitor, bpGlobal);
        log.debug("Global breakpoint added");

        String sessionParam = String.valueOf(getSessionId());
        String taskName = "PostgreSQL Debug - Global session " + sessionParam;
        String sql = SQL_ATTACH.replaceAll("\\?sessionid", sessionParam);
        DBGEvent begin = new DBGEvent(this, DBGEvent.RESUME, DBGEvent.MODEL_SPECIFIC);
        DBGEvent end = new DBGEvent(this, DBGEvent.SUSPEND, DBGEvent.BREAKPOINT);
        runAsync(sql, taskName, begin, end);
        log.debug("Global session started");
    }

    /**
     * This method attach debug session to debug object (procedure) and wait
     * forever while target or any (depend on targetPID) session will run target
     * procedure
     */
    public void attach(DBRProgressMonitor monitor, Map<String, Object> configuration) throws DBException {
        if (!checkDebugPlugin(monitor)) {
            throw new DBGException("The PostgreSQL debugger extension (pldbgapi) is not installed in this database.\n" +
                "Install the pldebugger package on the server, add plugin_debugger to shared_preload_libraries, " +
                "restart the server and run CREATE EXTENSION pldbgapi.");
        }

        log.debug("Attaching...");

        functionOid = CommonUtils.toLong(configuration.get(PostgreDebugConstants.ATTR_FUNCTION_OID));
        log.debug(String.format("Function OID %d", functionOid));

        if (isGlobalSession(configuration)) {
            String process = CommonUtils.toString(configuration.get(DBGConstants.ATTR_ATTACH_PROCESS)).trim();
            attachKind = PostgreDebugAttachKind.GLOBAL;
            attachGlobal(monitor, functionOid, process.isEmpty() ? null : CommonUtils.toInt(process));
            log.debug("Global attached");
        } else {
            attachKind = PostgreDebugAttachKind.LOCAL;
            PostgreProcedure function = PostgreSqlDebugCore.resolveFunction(monitor, controllerConnection.getDataSource().getContainer(), configuration);
            @SuppressWarnings("unchecked")
            List<String> parameterValues = (List<String>) configuration.get(DBGConstants.ATTR_PARAMETERS);

            attachLocal(monitor, function, parameterValues == null ? List.of() : parameterValues);
            log.debug("Local attached");
        }
    }

    private boolean isGlobalSession(Map<String, Object> configuration) {
        return DBGConstants.ATTACH_KIND_GLOBAL.equals(String.valueOf(configuration.get(DBGConstants.ATTR_ATTACH_KIND)));
    }

    private boolean checkDebugPlugin(DBRProgressMonitor monitor) {
        try (JDBCSession session = getControllerConnection().openSession(monitor, DBCExecutionPurpose.UTIL, "Check debug plugin installation")) {
            String version = JDBCUtils.executeQuery(session, SQL_CHECK_PLUGIN);
            log.debug("Debug plugin is installed:\n" + version);
            return true;
        } catch (Exception e) {
            log.debug("Debug plugin not installed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void abort(@NotNull DBRProgressMonitor monitor) throws DBGException {
        switch (attachKind) {
            case LOCAL -> abortLocal(monitor);
            case GLOBAL -> abortGlobal(monitor);
            default -> {
            }
        }
    }

    private void abortLocal(@NotNull DBRProgressMonitor monitor) {
        Job job = localWorkerJob;
        if (targetGone || job == null) {
            return;
        }
        if (isWaiting() || !isAttached()) {
            // Running: the controller connection is busy with the step, and canceling the step would let the target run
            // on without the debugger; cancel the target's own statement
            cancelTarget();
        } else {
            executeAbort(monitor);
        }
        try {
            if (!job.join(ABORT_WAIT_MS, null)) {
                log.debug("The target did not end after abort, canceling its statement");
                cancelTarget();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void abortGlobal(@NotNull DBRProgressMonitor monitor) {
        if (isWaiting() || targetGone) {
            // Waiting for a target, or the other session's call runs: stop waiting, the call goes on without the debugger
            cancelWorker();
        } else if (isAttached()) {
            executeAbort(monitor);
        }
        targetGone = true;
        fireEvent(new DBGEvent(this, DBGEvent.TERMINATE, DBGEvent.CLIENT_REQUEST));
    }

    private void executeAbort(@NotNull DBRProgressMonitor monitor) {
        try (JDBCSession session = getControllerConnection().openSession(monitor, DBCExecutionPurpose.UTIL, "Abort debug target")) {
            JDBCUtils.executeQuery(session, SQL_ABORT.replaceAll("\\?sessionid", String.valueOf(sessionId)));
            log.debug("Target aborted");
        } catch (SQLException e) {
            log.debug("Unable to abort the target: " + e.getMessage());
        }
    }

    private void cancelTarget() {
        Statement statement = targetStatement;
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException e) {
                log.debug("Cannot cancel the target statement: " + e.getMessage());
            }
        }
    }

    @Override
    protected void workerFailed(@NotNull SQLException error) {
        // pldbgapi raises "select() failed waiting for target" once the target has ended
        targetGone = true;
        if (attachKind == PostgreDebugAttachKind.GLOBAL) {
            fireEvent(new DBGEvent(this, DBGEvent.TERMINATE, DBGEvent.UNSPECIFIED));
        }
        // Local mode: the target's job reports the end with the call's result
    }

    @NotNull
    @Override
    public TransactionState getTransactionState(@NotNull DBRProgressMonitor monitor) throws DBGException {
        if (attachKind != PostgreDebugAttachKind.LOCAL || targetConnection == null || !(sessionInfo instanceof PostgreDebugSessionInfo info)) {
            return TransactionState.NONE;
        }
        try (JDBCSession session = getControllerConnection().openSession(monitor, DBCExecutionPurpose.UTIL, "Read debug target transaction state")) {
            String state = JDBCUtils.queryString(session, SQL_SESSION_STATE, info.getPid());
            if (STATE_IDLE_IN_FAILED_TRANSACTION.equals(state)) {
                return TransactionState.FAILED;
            } else if (STATE_IDLE_IN_TRANSACTION.equals(state)) {
                return TransactionState.OPEN;
            }
            return TransactionState.NONE;
        } catch (SQLException e) {
            throw new DBGException("Cannot read the transaction state of the debugged session", e);
        }
    }

    @Override
    public void endTransaction(@NotNull DBRProgressMonitor monitor, boolean commit) throws DBGException {
        JDBCExecutionContext connection = targetConnection;
        DBCTransactionManager txnManager = connection == null ? null : DBUtils.getTransactionManager(connection);
        if (txnManager == null) {
            return;
        }
        try (DBCSession session = connection.openSession(monitor, DBCExecutionPurpose.UTIL, commit ? "Commit debug transaction" : "Roll back debug transaction")) {
            if (commit) {
                txnManager.commit(session);
            } else {
                txnManager.rollback(session, null);
            }
        } catch (DBCException e) {
            throw new DBGException(e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public List<String> readMessages() {
        Statement statement = targetStatement;
        if (statement != null) {
            collectMessages(statement);
        }
        synchronized (pendingMessages) {
            List<String> messages = new ArrayList<>(pendingMessages);
            pendingMessages.clear();
            return messages;
        }
    }

    /**
     * Moves the target statement's new notices (not the debugger's own PLDBGBREAK notice) to the pending messages. The chain
     * is walked from the last warning read, as {@link #waitPortNumber} does (the driver appends to it while the call runs).
     */
    private void collectMessages(@NotNull Statement statement) {
        synchronized (pendingMessages) {
            try {
                SQLWarning warning = lastWarning == null ? statement.getWarnings() : lastWarning.getNextWarning();
                while (warning != null) {
                    lastWarning = warning;
                    String message = warning.getMessage();
                    if (message != null && !message.startsWith(MAGIC_PORT)) {
                        pendingMessages.add(message);
                    }
                    warning = warning.getNextWarning();
                }
            } catch (SQLException e) {
                log.debug("Cannot read the target's messages: " + e.getMessage());
            }
        }
    }

    @Nullable
    @Override
    public DBGTargetResult getTargetResult() {
        return targetResult;
    }

    @Override
    protected void doClose(@NotNull DBRProgressMonitor monitor) {
        JDBCExecutionContext target = targetConnection;
        if (target != null) {
            // Its transaction was ended as the user said before; a call still running ends with the connection
            cancelTarget();
            target.close();
            targetConnection = null;
        }
        if (controllerConnection != null) {
            controllerConnection.close();
        }
        log.debug("Session closed.");
    }

    @Override
    public DBGSessionInfo getSessionInfo() {
        return sessionInfo;
    }

    protected String composeAddBreakpointCommand(DBGBreakpointDescriptor descriptor) {
        PostgreDebugBreakpointDescriptor bp = (PostgreDebugBreakpointDescriptor) descriptor;
        String sqlPattern = bp == bpGlobal ? SQL_SET_GLOBAL_BREAKPOINT : SQL_SET_BREAKPOINT;
        long lineNumber = bp.isOnStart() ? -1 : bp.getLineNo();
        log.debug(String.format("Adding breakpoint to line #%d", lineNumber));
        return sqlPattern.replaceAll("\\?sessionid", String.valueOf(getSessionId()))
            .replaceAll("\\?obj", String.valueOf(bp.getObjectId()))
            .replaceAll("\\?line", String.valueOf(lineNumber))
            .replaceAll("\\?target", bp.isAll() ? "null" : String.valueOf(bp.getTargetId()));
    }

    protected String composeRemoveBreakpointCommand(DBGBreakpointDescriptor breakpointDescriptor) {
        PostgreDebugBreakpointDescriptor bp = (PostgreDebugBreakpointDescriptor) breakpointDescriptor;
        return SQL_DROP_BREAKPOINT.replaceAll("\\?sessionid", String.valueOf(getSessionId()))
            .replaceAll("\\?obj", String.valueOf(bp.getObjectId()))
            .replaceAll("\\?line", bp.isOnStart() ? "-1" : String.valueOf(bp.getLineNo()));
    }

    @Override
    public void execContinue() throws DBGException {
        log.debug("try continue for");
        execStep(SQL_CONTINUE, " continue for ", DBGEvent.RESUME);
        log.debug("continue for realized");
    }

    @Override
    public void execStepInto() throws DBGException {
        log.debug("try step into");
        execStep(SQL_STEP_INTO, " step into for ", DBGEvent.STEP_INTO);
        log.debug("step into realized");
    }

    @Override
    public void execStepOver() throws DBGException {
        log.debug("try step over");
        execStep(SQL_STEP_OVER, " step over for ", DBGEvent.STEP_OVER);
        log.debug("step over realized");
    }

    @Override
    public void execStepReturn() throws DBGException {
        log.debug("Exec return not implemented");
        throw new DBGException("Exec return not implemented");
    }

    /**
     * Execute step SQL command asynchronously, set debug session name to
     * [sessionID] name [managerPID]
     *
     * @param commandPattern - SQL command for execute step
     * @param nameParameter  - session 'name' part
     */
    public void execStep(String commandPattern, String nameParameter, int eventDetail) throws DBGException {
        String sql = commandPattern.replaceAll("\\?sessionid", String.valueOf(sessionId));
        String taskName = sessionId + nameParameter + (sessionInfo == null ? "" : sessionInfo.getID());
        DBGEvent begin = new DBGEvent(this, DBGEvent.RESUME, eventDetail);
        DBGEvent end = new DBGEvent(this, DBGEvent.SUSPEND, eventDetail);
        runAsync(sql, taskName, begin, end);
    }

    @Override
    public List<DBGVariable<?>> getVariables(DBGStackFrame stack) throws DBGException {
        if (stack != null) {
            selectFrame(stack.getLevel());
        }

        log.debug("Get vars values");
        List<DBGVariable<?>> vars = new ArrayList<>();

        String sql = SQL_GET_VARS.replaceAll("\\?sessionid", String.valueOf(sessionId));
        try (JDBCSession session = getControllerConnection().openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Read debug variables")) {
            try (Statement stmt = session.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(sql)) {

                    while (rs.next()) {
                        String name = rs.getString("name");
                        String varclass = rs.getString("varclass");
                        int linenumber = rs.getInt("linenumber");
                        boolean isunique = rs.getBoolean("isunique");
                        boolean isconst = rs.getBoolean("isconst");
                        boolean isnotnull = rs.getBoolean("isnotnull");
                        int dtype = rs.getInt("dtype");
                        String typeName = rs.getString("typename");
                        String value = rs.getString("value");
                        PostgreDebugVariable var = new PostgreDebugVariable(name, varclass, linenumber, isunique, isconst,
                            isnotnull, dtype, typeName, value);
                        vars.add(var);
                    }

                }
            }
        } catch (SQLException e) {
            log.debug("Error getting vars: " + e.getMessage());
            throw new DBGException("SQL error", e);
        }

        log.debug(String.format("Return %d var(s)", vars.size()));
        return vars;

    }

    @Override
    public void setVariableVal(DBGVariable<?> variable, Object value) throws DBGException {
        log.debug("Set var value");
        try (JDBCSession session = getControllerConnection().openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Set debug variable")) {
            try (PreparedStatement stmt = session.prepareStatement(SQL_SET_VAR)) {

                if (variable instanceof PostgreDebugVariable) {

                    if (value instanceof String) {

                        PostgreDebugVariable var = (PostgreDebugVariable) variable;

                        stmt.setInt(1, sessionId);
                        stmt.setString(2, var.getName());
                        stmt.setInt(3, var.getLineNumber());
                        stmt.setString(4, (String) value);

                        try (ResultSet rs = stmt.executeQuery()) {
                            if (!rs.next() || !rs.getBoolean(1)) {
                                throw new DBGException("The server did not accept the value of " + var.getName());
                            }
                        }
                        log.debug("Var value set");
                    } else {
                        log.debug("Incorrect variable value class");
                        throw new DBGException("Incorrect variable value class");
                    }

                } else {
                    log.debug("Incorrect variable class");
                    throw new DBGException("Incorrect variable class");
                }
            }

        } catch (SQLException e) {
            log.debug("Error setting var: " + e.getMessage());
            throw new DBGException("SQL error", e);
        }
    }

    @Override
    public List<DBGStackFrame> getStack() throws DBGException {
        List<DBGStackFrame> stack = new ArrayList<>(1);
        log.debug("Get stack");

        String sql = SQL_GET_STACK.replaceAll("\\?sessionid", String.valueOf(getSessionId()));
        try (JDBCSession session = getControllerConnection().openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Get debug stack frame")) {
            try (Statement stmt = session.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        int level = rs.getInt("level");
                        String targetname = rs.getString("targetname");
                        long func = rs.getLong("func");
                        int linenumber = rs.getInt("linenumber");
                        String args = rs.getString("args");
                        PostgreDebugStackFrame frame = new PostgreDebugStackFrame(level, targetname, func, linenumber, args);
                        stack.add(frame);
                    }

                }
            }
        } catch (SQLException e) {
            log.debug("Error loading stack frame: " + e.getMessage());
            throw new DBGException("SQL error", e);
        }
        log.debug(String.format("Return %d stack frame(s)", stack.size()));
        return stack;
    }

    @Override
    public String getSource(DBGStackFrame stack) throws DBGException {
        log.debug("Get source");
        if (stack instanceof PostgreDebugStackFrame) {
            PostgreDebugStackFrame postgreStack = (PostgreDebugStackFrame) stack;
            return getSource(postgreStack.getOid());
        }
        String message = String.format("Unable to get source for stack %s", stack);
        throw new DBGException(message);
    }

    /**
     * Return source for func OID in debug session
     *
     * @return String
     */

    public String getSource(long OID) throws DBGException {
        log.debug("Get source for func OID in debug session");
        String sql = SQL_GET_SRC.replaceAll("\\?sessionid", String.valueOf(sessionId)).replaceAll("\\?oid",
            String.valueOf(OID));
        try (JDBCSession session = getControllerConnection().openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Get session source")) {
            try (Statement stmt = session.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                    return null;
                }
            }
        } catch (SQLException e) {
            log.debug(String.format("Unable to get source for OID %s", e.getMessage()));
            throw new DBGException("SQL error", e);
        }
    }

    /**
     * This function changes the debugger focus to the indicated frame (in the
     * call stack). Whenever the target stops (at a breakpoint or as the result
     * of a step/into or step/over), the debugger changes focus to most deeply
     * nested function in the call stack (because that's the function that's
     * executing).
     * <p>
     * You can change the debugger focus to other stack frames - once you do
     * that, you can examine the source code for that frame, the variable values
     * in that frame, and the breakpoints in that target.
     * <p>
     * The debugger focus remains on the selected frame until you change it or
     * the target stops at another breakpoint.
     */

    public void selectFrame(int frameNumber) throws DBGException {
        log.debug("Select frame");
        String sql = SQL_SELECT_FRAME.replaceAll("\\?sessionid", String.valueOf(sessionId)).replaceAll("\\?frameno",
            String.valueOf(frameNumber));

        try (JDBCSession session = getControllerConnection().openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Select debug frame")) {
            try (Statement stmt = session.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    if (!rs.next()) {
                        log.debug("Unable to select frame");
                        throw new DBGException("Unable to select frame");
                    }

                    log.debug("Frame selected");

                }
            }
        } catch (SQLException e) {
            log.debug(String.format("Unable to select frame %s", e.getMessage()));
            throw new DBGException("SQL error", e);
        }
    }

    @Override
    public String toString() {
        return "PostgreDebugSession " + (isWaiting() ? "WAITING" : "READY") + " [sessionId=" + sessionId
            + ", breakpoints=" + getBreakpoints() + " Session=(" + sessionInfo + ") " + "]";
    }

    @Override
    public Integer getSessionId() {
        return sessionId;
    }

    @Override
    public boolean canStepInto() {
        return true;
    }

    @Override
    public boolean canStepOver() {
        return true;
    }

    @Override
    public boolean canStepReturn() {
        return false;
    }

    /**
     * Return true if debug session up and running on server
     *
     * @return boolean
     */
    public boolean isAttached() {
        return sessionId > 0;
    }

}
