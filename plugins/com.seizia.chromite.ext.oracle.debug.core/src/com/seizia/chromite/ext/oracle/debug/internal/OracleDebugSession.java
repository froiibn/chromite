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
package com.seizia.chromite.ext.oracle.debug.internal;

import com.seizia.chromite.Log;
import com.seizia.chromite.debug.DBGBreakpointDescriptor;
import com.seizia.chromite.debug.DBGConstants;
import com.seizia.chromite.debug.DBGEvent;
import com.seizia.chromite.debug.DBGException;
import com.seizia.chromite.debug.DBGPrivilegeException;
import com.seizia.chromite.debug.DBGSession;
import com.seizia.chromite.debug.DBGSessionInfo;
import com.seizia.chromite.debug.DBGStackFrame;
import com.seizia.chromite.debug.DBGTargetResult;
import com.seizia.chromite.debug.DBGVariable;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleProcedureBase;
import com.seizia.chromite.ext.oracle.model.OracleUtils;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.exec.jdbc.JDBCCallableStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * A {@code DBMS_DEBUG} session: the target connection runs the routine, the debugger (controller) connection drives it.
 *
 * 설계 (확인한 동작은 docs/chromite/procedure-debugger.md "DBMS_DEBUG 동작"):
 * <ul>
 *     <li>연결: 대상 연결은 연결 설정의 커밋 방식을 따르고(루틴이 바꾼 데이터를 언제 커밋할지는 사용자 몫), 디버거 연결은
 *     {@code DBMS_DEBUG}만 부르므로 자동 커밋이다. 대상은 {@code INITIALIZE}·{@code DEBUG_ON} 뒤 루틴을 부르는 익명 블록
 *     ({@link OracleDebugCall})을 작업에서 실행하고, 디버거는 {@code ATTACH_SESSION}·{@code SYNCHRONIZE} 뒤 {@code break_any_call}로
 *     루틴 첫 줄에서 멈춘다. {@code DBMS_DEBUG} 블록은 원래 JDBC 연결로 준비한다: Chromite의 CallableStatement 래퍼는 준비할 때마다
 *     문장에서 프로시저를 찾아 매개 변수 메타데이터를 읽으려 해(Oracle 익명 블록에는 실패, 디버그 로그에 오류) 단계마다 쓸모없는 일을 한다.</li>
 *     <li>단계 명령: {@code CONTINUE}의 breakflags — Step Over {@code break_next_line}, Step Into {@code break_any_call}, Step Out
 *     {@code break_any_return}(호출한 쪽으로 돌아가 멈춘다. {@code break_return}은 안쪽 호출의 return에서도 멈춰 Step Out이 아니다),
 *     계속 0(중단점까지). 디버거 대기 시간 제한을 짧게({@value #POLL_SECONDS}초, {@code SET_TIMEOUT}) 두고 {@code error_timeout}이면
 *     {@code SYNCHRONIZE}로 다시 기다린다. JDBC 취소는 디버거의 파이프 대기를 멈추지 못해, 이렇게 해야 닫기·중지가 디버거 연결을 오래
 *     막지 않는다. 디버거가 실행한 익명 블록 프레임에서 멈추면(루틴에서 돌아옴) 원본이 없으므로 같은 명령으로 계속 간다.</li>
 *     <li>끝: 대상이 끝나면 {@code CONTINUE}가 {@code reason_knl_exit}를 돌려준다. 그 뒤에는 디버거 호출(특히 {@code PRINT_BACKTRACE})이
 *     돌아오지 않을 수 있어 부르지 않는다. 대상 작업이 결과를 알린다(TERMINATE).</li>
 *     <li>디버그 모드 끄기: 대상 세션은 {@code DEBUG_OFF} 전까지 모든 PL/SQL 호출에서 디버거를 기다린다(트랜잭션 확인·DBMS_OUTPUT도).
 *     중지({@code abort_execution})는 블록의 예외 처리기도 건너뛰므로 블록 안에서 끄지 않고, 호출이 끝난 뒤 대상이 {@code DEBUG_OFF}를
 *     부르는 동안 디버거가 그 호출을 {@code SYNCHRONIZE}·{@code CONTINUE}로 통과시킨다({@link #endDebugMode}). 대상 쪽 시간 제한
 *     동작은 {@code nodebug_on_timeout}이라 앱이 죽어도 대상은 기본 시간 제한(1시간) 뒤 디버그 없이 끝난다.</li>
 *     <li>중지({@link #abort}): 멈춘 대상은 {@code CONTINUE(abort_execution)}(ORA-06543으로 끝남), 실행 중인 대상은 대상 문장의 JDBC
 *     취소(ORA-01013). 서버가 취소할 수 없는 대기(예: {@code DBMS_SESSION.SLEEP})에서는 대상이 그 대기를 마칠 때까지 끝나지 않는다.</li>
 *     <li>트랜잭션 안전: 대상 연결은 호출 뒤에도 열어 두고, 디버그 모드를 끈 뒤 {@code DBMS_TRANSACTION.LOCAL_TRANSACTION_ID}로 열린
 *     트랜잭션을 확인한다. 끄지 못했으면 확인할 수 없으므로 열린 것으로 보고 사용자에게 묻는다. 조용히 커밋·롤백하지 않는다.</li>
 *     <li>변수: {@code DBMS_DEBUG}에는 변수 목록 API가 없어 PL/Scope({@code ALL_IDENTIFIERS})의 선언 중 프레임의 줄 앞에 선언된 것을
 *     {@code GET_VALUE}로 읽고, 그 프레임에 없는 이름({@code error_no_such_object})은 뺀다. 패키지 전역 변수는 보이지 않는다.</li>
 *     <li>디버거 연결은 한 번에 한 작업만 쓴다({@link #controllerLock}): 단계 작업, 중지, 디버그 모드 끄기가 겹치지 않게 한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/procedure-debugger.md)
 */
final class OracleDebugSession implements DBGSession {

    private static final Log log = Log.getLog(OracleDebugSession.class);

    static final int ORA_INSUFFICIENT_PRIVILEGES = 1031;
    private static final Pattern ORA_CANCELED = Pattern.compile("ORA-0(1013|6543)\\b"); //$NON-NLS-1$

    // DBMS_DEBUG constants (SYS.DBMS_DEBUG package specification, checked on 23.26.3)
    private static final int SUCCESS = 0;
    private static final int ERROR_NO_DEBUG_INFO = 2;
    private static final int ERROR_NO_SUCH_OBJECT = 3;
    private static final int ERROR_UNKNOWN_TYPE = 4;
    private static final int ERROR_ILLEGAL_VALUE = 5;
    private static final int ERROR_ILLEGAL_NULL = 6;
    private static final int ERROR_VALUE_MALFORMED = 7;
    private static final int ERROR_NAME_INCOMPLETE = 11;
    private static final int ERROR_ILLEGAL_LINE = 12;
    private static final int ERROR_BAD_HANDLE = 16;
    private static final int ERROR_INDEXED_TABLE = 18;
    private static final int ERROR_TIMEOUT = 31;
    private static final int ERROR_NULLVALUE = 32;
    private static final int BREAK_CONTINUE = 0;
    private static final int BREAK_ANY_CALL = 12;
    private static final int BREAK_NEXT_LINE = 32;
    private static final int BREAK_ANY_RETURN = 512;
    private static final int ABORT_EXECUTION = 8192;
    private static final int REASON_EXIT = 15;
    private static final int REASON_KNL_EXIT = 25;

    /**
     * How long one debugger wait lasts before it is repeated ({@code DBMS_DEBUG.SET_TIMEOUT} of the debugger session).
     */
    private static final int POLL_SECONDS = 2;
    /**
     * How long Stop waits for the target to end.
     */
    private static final long ABORT_WAIT_MS = 10_000;
    /**
     * How long ending the debug mode of the target session may take.
     */
    private static final long DEBUG_OFF_WAIT_MS = 15_000;
    private static final int OUTPUT_CHUNK = 100;
    private static final int MAX_OUTPUT_LINES = 2000;

    private static final String SQL_INITIALIZE = "BEGIN\n" //$NON-NLS-1$
        + "  SYS.DBMS_OUTPUT.ENABLE(NULL);\n" //$NON-NLS-1$
        + "  ? := SYS.DBMS_DEBUG.INITIALIZE();\n" //$NON-NLS-1$
        + "  SYS.DBMS_DEBUG.SET_TIMEOUT_BEHAVIOUR(SYS.DBMS_DEBUG.NODEBUG_ON_TIMEOUT);\n" //$NON-NLS-1$
        + "  SYS.DBMS_DEBUG.DEBUG_ON(TRUE, FALSE);\n" //$NON-NLS-1$
        + "  ? := SYS_CONTEXT('USERENV', 'SID');\n" //$NON-NLS-1$
        + "END;"; //$NON-NLS-1$
    private static final String SQL_ATTACH = "BEGIN SYS.DBMS_DEBUG.ATTACH_SESSION(?); ? := SYS.DBMS_DEBUG.SET_TIMEOUT(?); END;"; //$NON-NLS-1$
    private static final String RUN_INFO = "SYS.DBMS_DEBUG.INFO_GETSTACKDEPTH + SYS.DBMS_DEBUG.INFO_GETLINEINFO"; //$NON-NLS-1$
    private static final String SQL_SYNCHRONIZE = "DECLARE ri SYS.DBMS_DEBUG.RUNTIME_INFO; BEGIN " //$NON-NLS-1$
        + "? := SYS.DBMS_DEBUG.SYNCHRONIZE(ri, " + RUN_INFO + "); ? := ri.REASON; ? := ri.TERMINATED; ? := ri.PROGRAM.NAME; END;"; //$NON-NLS-1$ //$NON-NLS-2$
    private static final String SQL_CONTINUE = "DECLARE ri SYS.DBMS_DEBUG.RUNTIME_INFO; BEGIN " //$NON-NLS-1$
        + "? := SYS.DBMS_DEBUG.CONTINUE(ri, ?, " + RUN_INFO + "); ? := ri.REASON; ? := ri.TERMINATED; ? := ri.PROGRAM.NAME; END;"; //$NON-NLS-1$ //$NON-NLS-2$
    private static final String SQL_BACKTRACE = "DECLARE bt SYS.DBMS_DEBUG.BACKTRACE_TABLE; s VARCHAR2(32767); i BINARY_INTEGER; BEGIN\n" //$NON-NLS-1$
        + "  SYS.DBMS_DEBUG.PRINT_BACKTRACE(bt);\n" //$NON-NLS-1$
        + "  i := bt.FIRST;\n" //$NON-NLS-1$
        + "  WHILE i IS NOT NULL LOOP\n" //$NON-NLS-1$
        + "    s := s || i || CHR(9) || bt(i).OWNER || CHR(9) || bt(i).NAME || CHR(9) || bt(i).LIBUNITTYPE || CHR(9) || bt(i).LINE# || CHR(9)\n" //$NON-NLS-1$
        + "      || bt(i).ENTRYPOINTNAME || CHR(10);\n" //$NON-NLS-1$
        + "    i := bt.NEXT(i);\n" //$NON-NLS-1$
        + "  END LOOP;\n" //$NON-NLS-1$
        + "  ? := s;\n" //$NON-NLS-1$
        + "END;"; //$NON-NLS-1$
    private static final String SQL_GET_VALUE = "DECLARE v VARCHAR2(32767); BEGIN ? := SYS.DBMS_DEBUG.GET_VALUE(?, ?, v, NULL); ? := v; END;"; //$NON-NLS-1$
    private static final String SQL_SET_VALUE = "BEGIN ? := SYS.DBMS_DEBUG.SET_VALUE(?, ?); END;"; //$NON-NLS-1$
    private static final String SQL_SET_BREAKPOINT = "DECLARE p SYS.DBMS_DEBUG.PROGRAM_INFO; b BINARY_INTEGER; BEGIN " //$NON-NLS-1$
        + "p.NAMESPACE := ?; p.OWNER := ?; p.NAME := ?; ? := SYS.DBMS_DEBUG.SET_BREAKPOINT(p, ?, b); ? := b; END;"; //$NON-NLS-1$
    private static final String SQL_DELETE_BREAKPOINT = "BEGIN ? := SYS.DBMS_DEBUG.DELETE_BREAKPOINT(?); END;"; //$NON-NLS-1$
    private static final String SQL_DEBUG_OFF = "BEGIN SYS.DBMS_DEBUG.DEBUG_OFF; END;"; //$NON-NLS-1$
    private static final String SQL_GET_OUTPUT = "DECLARE l SYS.DBMS_OUTPUT.CHARARR; n INTEGER := ?; c CLOB; BEGIN\n" //$NON-NLS-1$
        + "  SYS.DBMS_OUTPUT.GET_LINES(l, n);\n" //$NON-NLS-1$
        + "  FOR i IN 1 .. n LOOP c := c || l(i) || CHR(10); END LOOP;\n" //$NON-NLS-1$
        + "  ? := n; ? := c;\n" //$NON-NLS-1$
        + "END;"; //$NON-NLS-1$
    private static final String SQL_TRANSACTION = "SELECT SYS.DBMS_TRANSACTION.LOCAL_TRANSACTION_ID FROM DUAL"; //$NON-NLS-1$

    private static final Set<String> NUMERIC_TYPES = Set.of("NUMBER", "INTEGER", "INT", "SMALLINT", "DECIMAL", "NUMERIC", "FLOAT", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "REAL", "PLS_INTEGER", "BINARY_INTEGER", "SIMPLE_INTEGER", "NATURAL", "NATURALN", "POSITIVE", "POSITIVEN", "SIGNTYPE", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
        "BINARY_FLOAT", "BINARY_DOUBLE"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Pattern NUMBER_LITERAL = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?"); //$NON-NLS-1$
    private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_$#]*"); //$NON-NLS-1$

    /**
     * Where the target stopped, as {@code SYNCHRONIZE}/{@code CONTINUE} reported it.
     *
     * @param result  function result ({@code success}, {@code error_timeout}, …)
     * @param reason  {@code reason_*}
     * @param program unit name, null in the debugger's anonymous block
     */
    private record RunState(int result, int reason, boolean terminated, @Nullable String program) {
        boolean ended() {
            return result == SUCCESS && (terminated || reason == REASON_KNL_EXIT || reason == REASON_EXIT);
        }
    }

    /**
     * A PL/Scope declaration.
     */
    private record Declaration(@NotNull String name, @NotNull String typeName, boolean argument, int line) {
    }

    private final OracleDebugController controller;
    private final ReentrantLock controllerLock = new ReentrantLock();
    private final Map<OracleDebugBreakpoint, Integer> breakpoints = new LinkedHashMap<>();
    private final Map<OracleDebugUnit, List<Declaration>> declarations = new ConcurrentHashMap<>();
    private final Map<OracleDebugUnit, String> sources = new ConcurrentHashMap<>();
    private final List<String> pendingMessages = new ArrayList<>();

    @Nullable
    private volatile JDBCExecutionContext controllerConnection;
    @Nullable
    private volatile JDBCExecutionContext targetConnection;
    @Nullable
    private OracleDebugSessionInfo sessionInfo;
    @Nullable
    private volatile Job targetJob;
    @Nullable
    private volatile JDBCCallableStatement targetStatement;
    @Nullable
    private volatile DBGTargetResult targetResult;
    private volatile List<OracleDebugStackFrame> lastFrames = List.of();
    /**
     * A step runs (the target runs until it stops again).
     */
    private volatile boolean running;
    /**
     * The target's call has returned (debug mode may still be on).
     */
    private volatile boolean targetCallDone;
    /**
     * The target's job has ended: the call returned and its debug mode was ended (or could not be).
     */
    private volatile boolean targetGone;
    /**
     * The target session left debug mode: PL/SQL can run in it again.
     */
    private volatile boolean debugOff;
    private volatile boolean closing;

    OracleDebugSession(@NotNull OracleDebugController controller) {
        this.controller = controller;
    }

    /**
     * Opens both connections, starts the routine in the target and stops at its first line.
     */
    void attach(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> configuration) throws DBGException {
        if (DBGConstants.ATTACH_KIND_GLOBAL.equals(String.valueOf(configuration.get(DBGConstants.ATTR_ATTACH_KIND)))) {
            throw new DBGException("The Oracle debugger can only run the routine itself (DBMS_DEBUG cannot wait for another session's call)");
        }
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) configuration.get(DBGConstants.ATTR_PARAMETERS);
        OracleProcedureBase<?> routine = controller.getRoutine();
        try {
            JDBCExecutionContext debugger = (JDBCExecutionContext) routine.getDataSource().getDefaultInstance()
                .openIsolatedContext(monitor, "Debug controller session", null); //$NON-NLS-1$
            controllerConnection = debugger;
            // Only DBMS_DEBUG calls run here: auto-commit, so that no transaction stays open in it (a new connection has none yet)
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(debugger);
            if (txnManager != null && !txnManager.isAutoCommit()) {
                txnManager.setAutoCommit(monitor, true);
            }
            JDBCExecutionContext target = (JDBCExecutionContext) routine.getDataSource().getDefaultInstance()
                .openIsolatedContext(monitor, "Debug process session", null); //$NON-NLS-1$
            targetConnection = target;

            OracleDebugCall call;
            try (JDBCSession session = debugger.openSession(monitor, DBCExecutionPurpose.UTIL, "Compose debug call")) { //$NON-NLS-1$
                call = OracleDebugCall.compose(session, routine, values == null ? List.of() : values);
            }
            log.debug("Debug call:\n" + call.block()); //$NON-NLS-1$

            String debugId;
            try (JDBCSession session = target.openSession(monitor, DBCExecutionPurpose.UTIL, "Initialize debug target"); //$NON-NLS-1$
                 CallableStatement statement = session.getOriginal().prepareCall(SQL_INITIALIZE)) {
                statement.registerOutParameter(1, Types.VARCHAR);
                statement.registerOutParameter(2, Types.VARCHAR);
                statement.execute();
                debugId = statement.getString(1);
                sessionInfo = new OracleDebugSessionInfo(CommonUtils.notEmpty(statement.getString(2)), debugId);
            } catch (SQLException e) {
                if (e.getErrorCode() == ORA_INSUFFICIENT_PRIVILEGES) {
                    // DEBUG CONNECT SESSION was revoked after the debugger was opened (readSource checks it)
                    throw new DBGPrivilegeException(routine.getDataSource().getContainer().getActualConnectionConfiguration().getUserName(),
                        List.of("GRANT DEBUG CONNECT SESSION TO " + DBUtils.getQuotedIdentifier(routine.getDataSource(), //$NON-NLS-1$
                            routine.getDataSource().getContainer().getActualConnectionConfiguration().getUserName()) + ";")); //$NON-NLS-1$
                }
                throw e;
            }
            try (JDBCSession session = debugger.openSession(monitor, DBCExecutionPurpose.UTIL, "Attach debugger"); //$NON-NLS-1$
                 CallableStatement statement = session.getOriginal().prepareCall(SQL_ATTACH)) {
                statement.setString(1, debugId);
                statement.registerOutParameter(2, Types.INTEGER);
                statement.setInt(3, POLL_SECONDS);
                statement.execute();
            }

            Job job = runTarget(target, call);
            targetJob = job;

            RunState state;
            controllerLock.lock();
            try {
                // The target starts the call: it waits for the debugger at the start of the anonymous block
                state = runCommand(SQL_SYNCHRONIZE, null);
                while (state.result() == ERROR_TIMEOUT && !targetCallDone) {
                    state = runCommand(SQL_SYNCHRONIZE, null);
                }
                if (state.result() == SUCCESS && !state.ended()) {
                    state = continueTarget(BREAK_ANY_CALL);
                }
            } finally {
                controllerLock.unlock();
            }
            if (state.result() != SUCCESS && targetCallDone) {
                // The call failed before it reached the debugger (an argument expression, a wrong argument type): show its error
                job.join(ABORT_WAIT_MS, null);
                DBGTargetResult result = targetResult;
                throw new DBGException(result != null && result.errorMessage() != null ? result.errorMessage() : "The debugged call ended before it started");
            }
            if (state.result() != SUCCESS) {
                throw new DBGException("The debugger could not take control of the call (DBMS_DEBUG result " + state.result() + ")");
            }
            if (!state.ended()) {
                fireEvent(new DBGEvent(this, DBGEvent.SUSPEND, DBGEvent.MODEL_SPECIFIC));
            }
            // Ended right away (no line of the routine to stop at): the target job reports the end
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DBGException("Interrupted while starting the debug session", e);
        } catch (DBGException e) {
            throw e;
        } catch (DBException | SQLException e) {
            throw new DBGException("Cannot start the debug session", e);
        }
    }

    /**
     * Runs the call in the target connection; at its end, ends the debug mode, reads DBMS_OUTPUT and reports TERMINATE.
     */
    @NotNull
    private Job runTarget(@NotNull JDBCExecutionContext target, @NotNull OracleDebugCall call) {
        Job job = new AbstractJob("Oracle debug - run " + controller.getRoutine().getName()) { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<String> columns = new ArrayList<>();
                List<List<String>> rows = new ArrayList<>();
                String error = null;
                boolean canceled = false;
                try (JDBCSession session = target.openSession(monitor, DBCExecutionPurpose.USER, "Debug: run routine"); //$NON-NLS-1$
                     JDBCCallableStatement statement = session.prepareCall(call.block())) {
                    int index = 1;
                    for (OracleDebugCall.Output output : call.outputs()) {
                        if (output.placeholder() == null) {
                            statement.registerOutParameter(index++, Types.VARCHAR);
                        }
                    }
                    targetStatement = statement;
                    statement.execute();
                    List<String> row = new ArrayList<>();
                    index = 1;
                    for (OracleDebugCall.Output output : call.outputs()) {
                        columns.add(output.column());
                        row.add(output.placeholder() == null ? statement.getString(index++) : output.placeholder());
                    }
                    if (!row.isEmpty()) {
                        rows.add(row);
                    }
                } catch (Exception e) {
                    log.debug("The debugged call ended with an error: " + e.getMessage()); //$NON-NLS-1$
                    error = e.getMessage();
                    canceled = error != null && ORA_CANCELED.matcher(error).find();
                } finally {
                    targetStatement = null;
                    targetCallDone = true;
                    endDebugMode();
                    if (debugOff) {
                        readOutput(target);
                    }
                    // The connection stays open: its transaction is ended as the user says (getTransactionState, endTransaction)
                    targetResult = new DBGTargetResult(columns, rows, error, canceled);
                    targetGone = true;
                    fireEvent(new DBGEvent(OracleDebugSession.this, DBGEvent.TERMINATE, DBGEvent.CLIENT_REQUEST));
                }
                return Status.OK_STATUS;
            }
        };
        job.schedule();
        return job;
    }

    /**
     * The target session stays in debug mode after the call: its next PL/SQL call would wait for the debugger. The target
     * calls {@code DEBUG_OFF} while the debugger lets that call run through (it is debugged like any other call).
     */
    private void endDebugMode() {
        JDBCExecutionContext target = targetConnection;
        if (target == null || controllerConnection == null) {
            return;
        }
        controllerLock.lock();
        try {
            if (closing) {
                return;
            }
            // A thread of its own: the call blocks until the debugger lets it through
            CompletableFuture<Void> off = new CompletableFuture<>();
            Thread thread = new Thread(() -> {
                try (JDBCSession session = target.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "End debug mode"); //$NON-NLS-1$
                     JDBCStatement statement = session.createStatement()) {
                    statement.execute(SQL_DEBUG_OFF);
                    off.complete(null);
                } catch (Throwable e) {
                    off.completeExceptionally(e);
                }
            }, "Oracle debugger - end debug mode"); //$NON-NLS-1$
            thread.setDaemon(true);
            thread.start();
            long deadline = System.currentTimeMillis() + DEBUG_OFF_WAIT_MS;
            while (!off.isDone() && System.currentTimeMillis() < deadline && !closing) {
                RunState state = runCommand(SQL_SYNCHRONIZE, null);
                if (state.result() == SUCCESS && !state.ended()) {
                    // DEBUG_OFF started in the target: let it run to its end
                    state = runCommand(SQL_CONTINUE, BREAK_CONTINUE);
                    while (state.result() == ERROR_TIMEOUT && !off.isDone() && System.currentTimeMillis() < deadline) {
                        state = runCommand(SQL_SYNCHRONIZE, null);
                    }
                } else if (state.result() != SUCCESS && state.result() != ERROR_TIMEOUT) {
                    log.debug("DBMS_DEBUG result while ending debug mode: " + state.result()); //$NON-NLS-1$
                    break;
                }
            }
            off.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            debugOff = true;
        } catch (Exception e) {
            log.warn("Cannot end the debug mode of the debugged session; its transaction state cannot be read", e); //$NON-NLS-1$
        } finally {
            controllerLock.unlock();
        }
    }

    /**
     * DBMS_OUTPUT of the call (enabled at the start), into the messages.
     */
    private void readOutput(@NotNull JDBCExecutionContext target) {
        try (JDBCSession session = target.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Read debug output"); //$NON-NLS-1$
             CallableStatement statement = session.getOriginal().prepareCall(SQL_GET_OUTPUT)) {
            int total = 0;
            while (total < MAX_OUTPUT_LINES) {
                statement.setInt(1, OUTPUT_CHUNK);
                statement.registerOutParameter(2, Types.INTEGER);
                statement.registerOutParameter(3, Types.CLOB);
                statement.execute();
                int count = statement.getInt(2);
                String text = statement.getString(3);
                if (count > 0 && text != null) {
                    synchronized (pendingMessages) {
                        text.lines().forEach(pendingMessages::add);
                    }
                }
                total += count;
                if (count < OUTPUT_CHUNK) {
                    break;
                }
            }
        } catch (SQLException e) {
            log.debug("Cannot read DBMS_OUTPUT of the debugged call: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Continues the target with the break flags until it stops in a routine or ends. Hold {@link #controllerLock}.
     */
    @NotNull
    private RunState continueTarget(int flags) throws SQLException {
        RunState state = runCommand(SQL_CONTINUE, flags);
        while (true) {
            if (state.result() == ERROR_TIMEOUT) {
                if (closing || targetCallDone) {
                    return state;
                }
                // The target still runs: wait again
                state = runCommand(SQL_SYNCHRONIZE, null);
                continue;
            }
            if (state.result() != SUCCESS || state.ended()) {
                return state;
            }
            if (state.program() == null) {
                // Back in the debugger's anonymous block (no source): go on to the end of the call
                state = runCommand(SQL_CONTINUE, flags);
                continue;
            }
            return state;
        }
    }

    /**
     * Runs {@code SYNCHRONIZE} ({@code flags} null) or {@code CONTINUE}. Hold {@link #controllerLock}.
     */
    @NotNull
    private RunState runCommand(@NotNull String sql, @Nullable Integer flags) throws SQLException {
        JDBCExecutionContext debugger = controllerConnection;
        if (debugger == null) {
            throw new SQLException("The debugger connection is closed");
        }
        try (JDBCSession session = debugger.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Debugger command"); //$NON-NLS-1$
             CallableStatement statement = session.getOriginal().prepareCall(sql)) {
            int index = 1;
            statement.registerOutParameter(index++, Types.INTEGER);
            if (flags != null) {
                statement.setInt(index++, flags);
            }
            statement.registerOutParameter(index++, Types.INTEGER);
            statement.registerOutParameter(index++, Types.INTEGER);
            statement.registerOutParameter(index, Types.VARCHAR);
            statement.execute();
            int base = flags == null ? 2 : 3;
            return new RunState(statement.getInt(1), statement.getInt(base), statement.getInt(base + 1) != 0, statement.getString(base + 2));
        }
    }

    private void runStep(int flags, int detail) throws DBGException {
        if (targetCallDone) {
            throw new DBGException("The debugged call has ended");
        }
        if (running) {
            throw new DBGException("The debugger is busy");
        }
        running = true;
        fireEvent(new DBGEvent(this, DBGEvent.RESUME, detail));
        new AbstractJob("Oracle debug - step") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                RunState state = null;
                controllerLock.lock();
                try {
                    state = continueTarget(flags);
                } catch (SQLException e) {
                    log.debug("Debugger command failed: " + e.getMessage()); //$NON-NLS-1$
                    synchronized (pendingMessages) {
                        pendingMessages.add(e.getMessage());
                    }
                    // The debugger lost the target: stop the call so that the session can end
                    cancelTarget();
                } finally {
                    controllerLock.unlock();
                    running = false;
                }
                if (state != null && state.result() == SUCCESS && !state.ended()) {
                    fireEvent(new DBGEvent(OracleDebugSession.this, DBGEvent.SUSPEND, detail));
                } else if (state != null && state.result() != SUCCESS && state.result() != ERROR_TIMEOUT) {
                    synchronized (pendingMessages) {
                        pendingMessages.add("DBMS_DEBUG returned error " + state.result()); //$NON-NLS-1$
                    }
                    cancelTarget();
                }
                // Ended: the target job reports the end with the call's result
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    @Override
    public void execContinue() throws DBGException {
        runStep(BREAK_CONTINUE, DBGEvent.CLIENT_REQUEST);
    }

    @Override
    public void execStepInto() throws DBGException {
        runStep(BREAK_ANY_CALL, DBGEvent.STEP_INTO);
    }

    @Override
    public void execStepOver() throws DBGException {
        runStep(BREAK_NEXT_LINE, DBGEvent.STEP_OVER);
    }

    @Override
    public void execStepReturn() throws DBGException {
        runStep(BREAK_ANY_RETURN, DBGEvent.STEP_RETURN);
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
        return true;
    }

    @Override
    public void abort(@NotNull DBRProgressMonitor monitor) throws DBGException {
        Job job = targetJob;
        if (job == null || targetGone) {
            return;
        }
        if (!targetCallDone) {
            if (running) {
                // A step runs: the target runs on the server; cancel its statement (CONTINUE returns when it has ended)
                cancelTarget();
            } else {
                controllerLock.lock();
                try {
                    RunState state = runCommand(SQL_CONTINUE, ABORT_EXECUTION);
                    while (state.result() == ERROR_TIMEOUT && !targetCallDone && !closing) {
                        state = runCommand(SQL_SYNCHRONIZE, null);
                    }
                } catch (SQLException e) {
                    log.debug("Cannot abort the debugged call: " + e.getMessage()); //$NON-NLS-1$
                    cancelTarget();
                } finally {
                    controllerLock.unlock();
                }
            }
        }
        try {
            if (!job.join(ABORT_WAIT_MS, null)) {
                log.debug("The debugged call did not end after Stop"); //$NON-NLS-1$
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void cancelTarget() {
        JDBCCallableStatement statement = targetStatement;
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException e) {
                log.debug("Cannot cancel the debugged call: " + e.getMessage()); //$NON-NLS-1$
            }
        }
    }

    @NotNull
    @Override
    public TransactionState getTransactionState(@NotNull DBRProgressMonitor monitor) throws DBGException {
        JDBCExecutionContext target = targetConnection;
        if (target == null || targetJob == null) {
            return TransactionState.NONE;
        }
        if (!targetGone) {
            throw new DBGException("The debugged call has not ended yet (it may be in a wait the server cannot interrupt). Press Stop again later.");
        }
        if (!debugOff) {
            // PL/SQL cannot run in a session that is still in debug mode: the transaction cannot be checked, so the user decides
            return TransactionState.OPEN;
        }
        try (JDBCSession session = target.openSession(monitor, DBCExecutionPurpose.UTIL, "Read debug target transaction state"); //$NON-NLS-1$
             JDBCPreparedStatement statement = session.prepareStatement(SQL_TRANSACTION);
             JDBCResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() && resultSet.getString(1) != null ? TransactionState.OPEN : TransactionState.NONE;
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
        try (DBCSession session = connection.openSession(monitor, DBCExecutionPurpose.UTIL, commit ? "Commit debug transaction" : "Roll back debug transaction")) { //$NON-NLS-1$ //$NON-NLS-2$
            if (commit) {
                txnManager.commit(session);
            } else {
                txnManager.rollback(session, null);
            }
        } catch (DBCException e) {
            throw new DBGException(e.getMessage(), e);
        }
    }

    @Override
    public void closeSession(DBRProgressMonitor monitor) {
        closing = true;
        cancelTarget();
        JDBCExecutionContext target = targetConnection;
        if (target != null) {
            // Its transaction was ended as the user said before; a session still in debug mode ends with the connection
            target.close();
            targetConnection = null;
        }
        JDBCExecutionContext debugger = controllerConnection;
        if (debugger != null) {
            debugger.close();
            controllerConnection = null;
        }
        log.debug("Oracle debug session closed"); //$NON-NLS-1$
    }

    @NotNull
    @Override
    public List<String> readMessages() {
        synchronized (pendingMessages) {
            List<String> messages = new ArrayList<>(pendingMessages);
            pendingMessages.clear();
            return messages;
        }
    }

    @Nullable
    @Override
    public DBGTargetResult getTargetResult() {
        return targetResult;
    }

    @Override
    public DBGSessionInfo getSessionInfo() {
        return sessionInfo;
    }

    @Override
    public Object getSessionId() {
        return sessionInfo == null ? null : sessionInfo.debugId();
    }

    //////////////////////////////////////////////////////////
    // Breakpoints

    @Override
    public List<? extends DBGBreakpointDescriptor> getBreakpoints() {
        synchronized (breakpoints) {
            return new ArrayList<>(breakpoints.keySet());
        }
    }

    @Override
    public void addBreakpoint(DBRProgressMonitor monitor, DBGBreakpointDescriptor descriptor) throws DBGException {
        OracleDebugBreakpoint breakpoint = (OracleDebugBreakpoint) descriptor;
        OracleDebugUnit unit = breakpoint.unit();
        int result;
        int number;
        controllerLock.lock();
        try (JDBCSession session = openController(monitor, "Set breakpoint"); //$NON-NLS-1$
             CallableStatement statement = session.getOriginal().prepareCall(SQL_SET_BREAKPOINT)) {
            statement.setInt(1, unit.namespace());
            statement.setString(2, unit.owner());
            statement.setString(3, unit.name());
            statement.registerOutParameter(4, Types.INTEGER);
            statement.setInt(5, breakpoint.lineNumber());
            statement.registerOutParameter(6, Types.INTEGER);
            statement.execute();
            result = statement.getInt(4);
            number = statement.getInt(6);
        } catch (SQLException e) {
            throw new DBGException("Cannot set the breakpoint at line " + breakpoint.lineNumber() + " of " + unit, e);
        } finally {
            controllerLock.unlock();
        }
        switch (result) {
            case SUCCESS -> {
                synchronized (breakpoints) {
                    breakpoints.put(breakpoint, number);
                }
            }
            case ERROR_ILLEGAL_LINE -> throw new DBGException("Line " + breakpoint.lineNumber() + " of " + unit + " has no statement to stop at");
            case ERROR_BAD_HANDLE -> throw new DBGException(unit + " does not exist or is not visible to the debugger");
            default -> throw new DBGException("Cannot set the breakpoint at line " + breakpoint.lineNumber() + " of " + unit
                + " (DBMS_DEBUG result " + result + ")");
        }
    }

    @Override
    public void removeBreakpoint(DBRProgressMonitor monitor, DBGBreakpointDescriptor descriptor) throws DBGException {
        Integer number;
        synchronized (breakpoints) {
            number = breakpoints.remove(descriptor);
        }
        if (number == null) {
            return;
        }
        controllerLock.lock();
        try (JDBCSession session = openController(monitor, "Remove breakpoint"); //$NON-NLS-1$
             CallableStatement statement = session.getOriginal().prepareCall(SQL_DELETE_BREAKPOINT)) {
            statement.registerOutParameter(1, Types.INTEGER);
            statement.setInt(2, number);
            statement.execute();
            if (statement.getInt(1) != SUCCESS) {
                log.debug("DELETE_BREAKPOINT " + number + " returned " + statement.getInt(1)); //$NON-NLS-1$ //$NON-NLS-2$
            }
        } catch (SQLException e) {
            throw new DBGException("Cannot remove the breakpoint", e);
        } finally {
            controllerLock.unlock();
        }
    }

    //////////////////////////////////////////////////////////
    // Stack, variables, source

    @Override
    public List<? extends DBGStackFrame> getStack() throws DBGException {
        String text;
        controllerLock.lock();
        try (JDBCSession session = openController(new VoidProgressMonitor(), "Read debug stack"); //$NON-NLS-1$
             CallableStatement statement = session.getOriginal().prepareCall(SQL_BACKTRACE)) {
            statement.registerOutParameter(1, Types.VARCHAR);
            statement.execute();
            text = CommonUtils.notEmpty(statement.getString(1));
        } catch (SQLException e) {
            throw new DBGException("Cannot read the call stack", e);
        } finally {
            controllerLock.unlock();
        }
        // Backtrace entries: oldest first; the last one is where the target is stopped
        List<String[]> entries = new ArrayList<>();
        for (String line : text.split("\n")) { //$NON-NLS-1$
            String[] fields = line.split("\t", -1); //$NON-NLS-1$
            if (fields.length >= 6) {
                entries.add(fields);
            }
        }
        List<OracleDebugStackFrame> frames = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            String[] fields = entries.get(i);
            OracleDebugUnit unit = OracleDebugUnit.ofFrame(CommonUtils.nullIfEmpty(fields[1]), CommonUtils.nullIfEmpty(fields[2]),
                CommonUtils.toInt(fields[3], -1));
            if (unit == null) {
                // The debugger's anonymous block
                continue;
            }
            String entryPoint = CommonUtils.nullIfEmpty(fields[5]);
            String name = unit.owner() + "." + unit.name() + (entryPoint == null ? "" : "." + entryPoint); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            frames.add(new OracleDebugStackFrame(name, CommonUtils.toInt(fields[0]), CommonUtils.toInt(fields[4]), unit, i == entries.size() - 1));
        }
        lastFrames = frames;
        return frames;
    }

    @Override
    public List<? extends DBGVariable<?>> getVariables(DBGStackFrame stack) throws DBGException {
        OracleDebugStackFrame frame = stack instanceof OracleDebugStackFrame oracleFrame ? oracleFrame
            : lastFrames.isEmpty() ? null : lastFrames.getFirst();
        if (frame == null) {
            return List.of();
        }
        // In scope: declared before the frame's line; of names declared more than once (other subprograms), the nearest
        Map<String, Declaration> candidates = new LinkedHashMap<>();
        for (Declaration declaration : readDeclarations(frame.unit())) {
            if (declaration.line() <= frame.getLineNumber()) {
                candidates.remove(declaration.name());
                candidates.put(declaration.name(), declaration);
            }
        }
        List<OracleDebugVariable> variables = new ArrayList<>();
        controllerLock.lock();
        try (JDBCSession session = openController(new VoidProgressMonitor(), "Read debug variables"); //$NON-NLS-1$
             CallableStatement statement = session.getOriginal().prepareCall(SQL_GET_VALUE)) {
            for (Declaration declaration : candidates.values()) {
                statement.registerOutParameter(1, Types.INTEGER);
                statement.setString(2, declaration.name());
                statement.setInt(3, frame.valueFrame());
                statement.registerOutParameter(4, Types.VARCHAR);
                statement.execute();
                int result = statement.getInt(1);
                String value;
                switch (result) {
                    case SUCCESS -> value = statement.getString(4);
                    case ERROR_NULLVALUE -> value = null;
                    case ERROR_NO_SUCH_OBJECT -> {
                        // Declared in another subprogram
                        continue;
                    }
                    case ERROR_NO_DEBUG_INFO -> {
                        return List.of();
                    }
                    case ERROR_INDEXED_TABLE -> value = "(collection)"; //$NON-NLS-1$
                    case ERROR_UNKNOWN_TYPE, ERROR_NAME_INCOMPLETE -> value = "(not a scalar value)"; //$NON-NLS-1$
                    default -> value = "(DBMS_DEBUG result " + result + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                variables.add(new OracleDebugVariable(declaration.name(), declaration.typeName(), declaration.argument(), declaration.line(),
                    value, frame.valueFrame()));
            }
        } catch (SQLException e) {
            throw new DBGException("Cannot read the variables", e);
        } finally {
            controllerLock.unlock();
        }
        return variables;
    }

    /**
     * Variables, constants, loop iterators and parameters the unit declares (PL/Scope), by line.
     */
    @NotNull
    private List<Declaration> readDeclarations(@NotNull OracleDebugUnit unit) throws DBGException {
        List<Declaration> cached = declarations.get(unit);
        if (cached != null) {
            return cached;
        }
        String prefix = OracleUtils.getSysSchemaPrefix((OracleDataSource) controller.getRoutine().getDataSource());
        // Each declaration with the references inside it, in source order: the declared type comes first (NUMBER; T, C of
        // T.C%TYPE; T of T%ROWTYPE), an initial value's references after it
        String sql = "SELECT d.USAGE_ID, d.NAME, d.TYPE, d.LINE, r.NAME, r.TYPE " //$NON-NLS-1$
            + "FROM " + prefix + "ALL_IDENTIFIERS d LEFT JOIN " + prefix + "ALL_IDENTIFIERS r " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "ON r.OWNER = d.OWNER AND r.OBJECT_NAME = d.OBJECT_NAME AND r.OBJECT_TYPE = d.OBJECT_TYPE " //$NON-NLS-1$
            + "AND r.USAGE_CONTEXT_ID = d.USAGE_ID AND r.USAGE = 'REFERENCE' " //$NON-NLS-1$
            + "WHERE d.OWNER = ? AND d.OBJECT_NAME = ? AND d.OBJECT_TYPE = ? AND d.USAGE = 'DECLARATION' " //$NON-NLS-1$
            + "AND d.TYPE IN ('VARIABLE', 'CONSTANT', 'ITERATOR', 'FORMAL IN', 'FORMAL OUT', 'FORMAL IN OUT') " //$NON-NLS-1$
            + "ORDER BY d.LINE, d.COL, d.USAGE_ID, r.USAGE_ID"; //$NON-NLS-1$
        List<Declaration> result = new ArrayList<>();
        controllerLock.lock();
        try (JDBCSession session = openController(new VoidProgressMonitor(), "Read PL/Scope declarations"); //$NON-NLS-1$
             JDBCPreparedStatement statement = session.prepareStatement(sql)) {
            statement.setString(1, unit.owner());
            statement.setString(2, unit.name());
            statement.setString(3, unit.type());
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                long declarationId = -1;
                String name = null;
                String kind = null;
                int line = 0;
                List<String[]> references = new ArrayList<>();
                while (resultSet.next()) {
                    long id = resultSet.getLong(1);
                    if (id != declarationId) {
                        if (name != null) {
                            result.add(new Declaration(name, describeType(kind, references), kind.startsWith("FORMAL"), line)); //$NON-NLS-1$
                        }
                        declarationId = id;
                        name = resultSet.getString(2);
                        kind = CommonUtils.notEmpty(resultSet.getString(3));
                        line = resultSet.getInt(4);
                        references.clear();
                    }
                    if (resultSet.getString(5) != null) {
                        references.add(new String[]{resultSet.getString(5), CommonUtils.notEmpty(resultSet.getString(6))});
                    }
                }
                if (name != null) {
                    result.add(new Declaration(name, describeType(kind, references), kind.startsWith("FORMAL"), line)); //$NON-NLS-1$
                }
            }
        } catch (SQLException e) {
            throw new DBGException("Cannot read the declarations of " + unit, e);
        } finally {
            controllerLock.unlock();
        }
        declarations.put(unit, result);
        return result;
    }

    /**
     * The declared type from the first references inside a declaration ({@code {name, type}} of {@code ALL_IDENTIFIERS}).
     * A loop iterator has no declared type (its references are the loop bounds).
     */
    @NotNull
    private static String describeType(@NotNull String declarationKind, @NotNull List<String[]> references) {
        if (references.isEmpty() || declarationKind.equals("ITERATOR")) { //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }
        String name = references.getFirst()[0];
        String type = references.getFirst()[1];
        return switch (type) {
            case "TABLE", "VIEW" -> references.size() > 1 && references.get(1)[1].equals("COLUMN") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                ? name + "." + references.get(1)[0] + "%TYPE" : name + "%ROWTYPE"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "CURSOR" -> name + "%ROWTYPE"; //$NON-NLS-1$ //$NON-NLS-2$
            case "VARIABLE", "CONSTANT", "FORMAL IN", "FORMAL OUT", "FORMAL IN OUT" -> name + "%TYPE"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            default -> name;
        };
    }

    /**
     * Sets a variable with {@code SET_VALUE}: the text is the value as the Variables view shows it, made a PL/SQL literal
     * (quoted, unless it is NULL, a number of a numeric variable or TRUE/FALSE of a BOOLEAN); the server converts it.
     */
    @Override
    public void setVariableVal(DBGVariable<?> variable, Object value) throws DBGException {
        if (!(variable instanceof OracleDebugVariable oracleVariable)) {
            throw new DBGException("Not a variable of this debugger");
        }
        String name = PLAIN_IDENTIFIER.matcher(oracleVariable.name()).matches() ? oracleVariable.name()
            : '"' + oracleVariable.name().replace("\"", "") + '"'; //$NON-NLS-1$ //$NON-NLS-2$
        String assignment = name + " := " + toLiteral(oracleVariable.typeName(), CommonUtils.toString(value)) + ";"; //$NON-NLS-1$ //$NON-NLS-2$
        int result;
        controllerLock.lock();
        try (JDBCSession session = openController(new VoidProgressMonitor(), "Set debug variable"); //$NON-NLS-1$
             CallableStatement statement = session.getOriginal().prepareCall(SQL_SET_VALUE)) {
            statement.registerOutParameter(1, Types.INTEGER);
            statement.setInt(2, oracleVariable.frame());
            statement.setString(3, assignment);
            statement.execute();
            result = statement.getInt(1);
        } catch (SQLException e) {
            throw new DBGException("Cannot set " + oracleVariable.name(), e);
        } finally {
            controllerLock.unlock();
        }
        switch (result) {
            case SUCCESS -> {
            }
            case ERROR_ILLEGAL_VALUE -> throw new DBGException("The value does not fit the variable's type or constraint");
            case ERROR_ILLEGAL_NULL -> throw new DBGException("The variable cannot be NULL");
            case ERROR_VALUE_MALFORMED -> throw new DBGException("The value is not valid for the variable's type");
            case ERROR_NAME_INCOMPLETE -> throw new DBGException("Only scalar variables can be set");
            default -> throw new DBGException("The server did not accept the value (DBMS_DEBUG result " + result + ")");
        }
    }

    @NotNull
    private static String toLiteral(@NotNull String typeName, @NotNull String text) {
        String trimmed = text.trim();
        if (trimmed.equalsIgnoreCase("NULL")) { //$NON-NLS-1$
            return "NULL"; //$NON-NLS-1$
        }
        if (typeName.equals("BOOLEAN") && (trimmed.equalsIgnoreCase("TRUE") || trimmed.equalsIgnoreCase("FALSE"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return trimmed.toUpperCase();
        }
        if (NUMERIC_TYPES.contains(typeName) && NUMBER_LITERAL.matcher(trimmed).matches()) {
            return trimmed;
        }
        return "'" + text.replace("'", "''") + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Override
    public String getSource(DBGStackFrame stack) throws DBGException {
        if (!(stack instanceof OracleDebugStackFrame frame)) {
            throw new DBGException("Unable to get source for stack " + stack);
        }
        String cached = sources.get(frame.unit());
        if (cached != null) {
            return cached;
        }
        String source;
        controllerLock.lock();
        try (JDBCSession session = openController(new VoidProgressMonitor(), "Read debug source")) { //$NON-NLS-1$
            source = OracleDebugController.readUnitSource(session, frame.unit());
        } catch (SQLException e) {
            throw new DBGException("Cannot read the source of " + frame.unit(), e);
        } finally {
            controllerLock.unlock();
        }
        sources.put(frame.unit(), source);
        return source;
    }

    @NotNull
    private JDBCSession openController(@NotNull DBRProgressMonitor monitor, @NotNull String task) throws SQLException {
        JDBCExecutionContext debugger = controllerConnection;
        if (debugger == null) {
            throw new SQLException("The debugger connection is closed");
        }
        return debugger.openSession(monitor, DBCExecutionPurpose.UTIL, task);
    }

    private void fireEvent(@NotNull DBGEvent event) {
        controller.fireEvent(event);
    }

    @Override
    public String toString() {
        return "OracleDebugSession [" + sessionInfo + ", breakpoints=" + getBreakpoints() + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
