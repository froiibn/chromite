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
import com.seizia.chromite.debug.DBGBaseController;
import com.seizia.chromite.debug.DBGBreakpointDescriptor;
import com.seizia.chromite.debug.DBGException;
import com.seizia.chromite.debug.DBGPrivilegeException;
import com.seizia.chromite.debug.DBGSession;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleProcedureBase;
import com.seizia.chromite.ext.oracle.model.OracleProcedurePackaged;
import com.seizia.chromite.ext.oracle.model.OracleUtils;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PL/SQL debugger ({@code DBMS_DEBUG}) of one Oracle procedure or function, standalone or packaged.
 *
 * 설계 (docs/chromite/procedure-debugger.md "Oracle"):
 * <ul>
 *     <li>{@code DBMS_DEBUG_JDWP}가 아니라 {@code DBMS_DEBUG}: JDWP는 DB 서버가 사용자 PC의 포트로 접속해야 해서(네트워크 ACL, 방화벽
 *     인바운드) 닫힌 고객망에서 쓰기 어렵다. {@code DBMS_DEBUG}는 보통 클라이언트 연결 두 개(대상·디버거)로 되고 PostgreSQL과 같은
 *     {@code DBGController} 모양에 맞는다. Oracle이 사용 중단 예고한 API이지만 26ai(23.26.3)에서 끝까지 동작함을 확인했다.</li>
 *     <li>원본과 줄 번호: 단위({@link OracleDebugUnit})의 {@code ALL_SOURCE} 줄이다. 패키지 루틴은 패키지 본문 전체를 보인다.</li>
 *     <li>디버그 컴파일: 줄에서 멈추고 변수를 읽으려면 단위가 {@code COMPILE DEBUG}여야 하고, 변수 이름 목록은 PL/Scope
 *     ({@code PLSCOPE_SETTINGS='IDENTIFIERS:ALL'})에서 얻는다({@code DBMS_DEBUG}에는 변수 목록 API가 없다). 다시 컴파일은 의존 객체를 잠시
 *     무효로 만들 수 있어 {@link #getUnitsToCompile}로 알리고 사용자가 동의한 뒤에만 {@link #compileForDebugging}이 한다.
 *     {@code REUSE SETTINGS}로 다른 컴파일 설정(PLSQL_CCFLAGS 등)을 지킨다. 다시 컴파일(DDL)은 암묵 커밋하므로 열린 트랜잭션이 없는 새
 *     격리 연결에서 한다.</li>
 *     <li>권한: 디버깅에는 {@code DEBUG CONNECT SESSION}, 남의 단위에는 그 단위의 {@code DEBUG} 또는 {@code DEBUG ANY PROCEDURE}가
 *     필요하다. 열 때 확인해 빠진 권한의 GRANT 문을 {@link DBGPrivilegeException}으로 넘긴다. GRANT는 실행하지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/procedure-debugger.md)
 */
class OracleDebugController extends DBGBaseController {

    private static final Log log = Log.getLog(OracleDebugController.class);

    private static final String PRIV_DEBUG_CONNECT_SESSION = "DEBUG CONNECT SESSION"; //$NON-NLS-1$
    private static final String PRIV_DEBUG_ANY_PROCEDURE = "DEBUG ANY PROCEDURE"; //$NON-NLS-1$
    private static final String PLSCOPE_ALL = "IDENTIFIERS:ALL"; //$NON-NLS-1$
    private static final String STATUS_INVALID = "INVALID"; //$NON-NLS-1$
    private static final Pattern WRAPPED = Pattern.compile("(?i)\\bwrapped\\s*$"); //$NON-NLS-1$
    private static final int MAX_COMPILE_ERRORS = 10;

    private final OracleProcedureBase<?> routine;
    private final OracleDebugUnit unit;

    OracleDebugController(@NotNull OracleProcedureBase<?> routine, @NotNull Map<String, Object> configuration) {
        super(routine.getDataSource().getContainer(), configuration);
        this.routine = routine;
        String owner = routine.getSchema().getName();
        if (routine instanceof OracleProcedurePackaged packaged) {
            unit = new OracleDebugUnit(owner, OracleDebugUnit.TYPE_PACKAGE_BODY, packaged.getParentObject().getName());
        } else {
            unit = new OracleDebugUnit(owner,
                routine.getProcedureType() == DBSProcedureType.FUNCTION ? OracleDebugUnit.TYPE_FUNCTION : OracleDebugUnit.TYPE_PROCEDURE,
                routine.getName());
        }
    }

    @NotNull
    OracleProcedureBase<?> getRoutine() {
        return routine;
    }

    @NotNull
    @Override
    public DBGSession createSession(DBRProgressMonitor monitor, Map<String, Object> configuration) throws DBGException {
        OracleDebugSession session = new OracleDebugSession(this);
        try {
            session.attach(monitor, configuration);
            return session;
        } catch (DBGException e) {
            // Nothing of the routine ran (the call did not start, or it failed before the debugger got it): no transaction to ask about
            session.closeSession(monitor);
            throw e;
        }
    }

    /**
     * Checks the user's debugging privileges, then reads the unit's source.
     */
    @NotNull
    @Override
    public String readSource(@NotNull DBRProgressMonitor monitor) throws DBGException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, routine, "Read source for debugger")) { //$NON-NLS-1$
            checkPrivileges(session);
            String source = readUnitSource(session, unit);
            if (source.isEmpty()) {
                throw new DBGException(OracleDebugUnit.TYPE_PACKAGE_BODY.equals(unit.type())
                    ? "Package " + unit.owner() + "." + unit.name() + " has no body"
                    : "No source of " + unit + " is visible to this user");
            }
            String firstLine = source.lines().findFirst().orElse(""); //$NON-NLS-1$
            if (WRAPPED.matcher(firstLine).find()) {
                throw new DBGException(unit + " is wrapped (obfuscated): it cannot be debugged");
            }
            return source;
        } catch (DBGException e) {
            throw e;
        } catch (DBException | SQLException e) {
            throw new DBGException("Cannot read the source of " + unit, e);
        }
    }

    @NotNull
    @Override
    public Object getSourceIdentifier() {
        return unit;
    }

    @NotNull
    @Override
    public DBGBreakpointDescriptor describeBreakpoint(@NotNull Object sourceIdentifier, int lineNumber) {
        return new OracleDebugBreakpoint((OracleDebugUnit) sourceIdentifier, lineNumber);
    }

    /**
     * The unit, unless it is compiled with debug information and PL/Scope identifiers (variable names).
     */
    @NotNull
    @Override
    public List<String> getUnitsToCompile(@NotNull DBRProgressMonitor monitor) throws DBGException {
        String sql = "SELECT PLSQL_DEBUG, PLSCOPE_SETTINGS FROM " + sysPrefix() + "ALL_PLSQL_OBJECT_SETTINGS " //$NON-NLS-1$ //$NON-NLS-2$
            + "WHERE OWNER = ? AND NAME = ? AND TYPE = ?"; //$NON-NLS-1$
        try (JDBCSession session = DBUtils.openMetaSession(monitor, routine, "Read debug compile settings"); //$NON-NLS-1$
             JDBCPreparedStatement statement = session.prepareStatement(sql)) {
            statement.setString(1, unit.owner());
            statement.setString(2, unit.name());
            statement.setString(3, unit.type());
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next() && "TRUE".equalsIgnoreCase(resultSet.getString(1)) //$NON-NLS-1$
                    && String.valueOf(resultSet.getString(2)).toUpperCase().contains(PLSCOPE_ALL)) {
                    return List.of();
                }
            }
            return List.of(unit.toString());
        } catch (DBException | SQLException e) {
            throw new DBGException("Cannot read the compile settings of " + unit, e);
        }
    }

    /**
     * {@code ALTER … COMPILE DEBUG} with PL/Scope identifiers, in a new isolated connection (the DDL commits implicitly).
     */
    @Override
    public void compileForDebugging(@NotNull DBRProgressMonitor monitor) throws DBGException {
        String ddl = composeCompileStatement();
        JDBCExecutionContext context = null;
        try {
            context = (JDBCExecutionContext) routine.getDataSource().getDefaultInstance().openIsolatedContext(monitor, "Compile for debugging", null); //$NON-NLS-1$
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
            if (txnManager != null && !txnManager.isAutoCommit()) {
                txnManager.setAutoCommit(monitor, true);
            }
            try (JDBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Compile for debugging")) { //$NON-NLS-1$
                try (JDBCStatement statement = session.createStatement()) {
                    statement.execute(ddl);
                } catch (SQLException e) {
                    if (e.getErrorCode() == OracleDebugSession.ORA_INSUFFICIENT_PRIVILEGES) {
                        throw new DBGPrivilegeException(readSessionUser(session), List.of(ddl + ";")); //$NON-NLS-1$
                    }
                    throw new DBGException("Cannot compile " + unit + " for debugging", e);
                }
                checkCompiled(session);
            }
            log.debug("Compiled for debugging: " + unit); //$NON-NLS-1$
        } catch (DBGException e) {
            throw e;
        } catch (DBException | SQLException e) {
            throw new DBGException("Cannot compile " + unit + " for debugging", e);
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    @NotNull
    private String composeCompileStatement() {
        String name = DBUtils.getQuotedIdentifier(routine.getDataSource(), unit.owner()) + "." //$NON-NLS-1$
            + DBUtils.getQuotedIdentifier(routine.getDataSource(), unit.name());
        String settings = " PLSCOPE_SETTINGS = '" + PLSCOPE_ALL + "' REUSE SETTINGS"; //$NON-NLS-1$ //$NON-NLS-2$
        return switch (unit.type()) {
            case OracleDebugUnit.TYPE_PACKAGE_BODY -> "ALTER PACKAGE " + name + " COMPILE DEBUG BODY" + settings; //$NON-NLS-1$ //$NON-NLS-2$
            case OracleDebugUnit.TYPE_FUNCTION -> "ALTER FUNCTION " + name + " COMPILE DEBUG" + settings; //$NON-NLS-1$ //$NON-NLS-2$
            default -> "ALTER PROCEDURE " + name + " COMPILE DEBUG" + settings; //$NON-NLS-1$ //$NON-NLS-2$
        };
    }

    /**
     * A compile with errors only warns (ORA-24344); the unit is then INVALID.
     */
    private void checkCompiled(@NotNull JDBCSession session) throws SQLException, DBGException {
        String statusSql = "SELECT STATUS FROM " + sysPrefix() + "ALL_OBJECTS WHERE OWNER = ? AND OBJECT_NAME = ? AND OBJECT_TYPE = ?"; //$NON-NLS-1$ //$NON-NLS-2$
        try (JDBCPreparedStatement statement = session.prepareStatement(statusSql)) {
            statement.setString(1, unit.owner());
            statement.setString(2, unit.name());
            statement.setString(3, unit.type());
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || !STATUS_INVALID.equals(resultSet.getString(1))) {
                    return;
                }
            }
        }
        List<String> errors = new ArrayList<>();
        String errorsSql = "SELECT LINE, TEXT FROM " + sysPrefix() + "ALL_ERRORS WHERE OWNER = ? AND NAME = ? AND TYPE = ? ORDER BY SEQUENCE"; //$NON-NLS-1$ //$NON-NLS-2$
        try (JDBCPreparedStatement statement = session.prepareStatement(errorsSql)) {
            statement.setString(1, unit.owner());
            statement.setString(2, unit.name());
            statement.setString(3, unit.type());
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                while (errors.size() < MAX_COMPILE_ERRORS && resultSet.next()) {
                    errors.add("Line " + resultSet.getInt(1) + ": " + resultSet.getString(2)); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        throw new DBGException(unit + " has compilation errors:\n" + String.join("\n", errors)); //$NON-NLS-1$
    }

    /**
     * Missing privileges become a {@link DBGPrivilegeException} with the GRANT statements a DBA must run.
     */
    private void checkPrivileges(@NotNull JDBCSession session) throws SQLException, DBGException {
        String sql = "SELECT SYS_CONTEXT('USERENV', 'SESSION_USER'), " //$NON-NLS-1$
            + "(SELECT COUNT(*) FROM " + sysPrefix() + "SESSION_PRIVS WHERE PRIVILEGE = ?), " //$NON-NLS-1$ //$NON-NLS-2$
            + "(SELECT COUNT(*) FROM " + sysPrefix() + "SESSION_PRIVS WHERE PRIVILEGE = ?), " //$NON-NLS-1$ //$NON-NLS-2$
            + "(SELECT COUNT(*) FROM " + sysPrefix() + "ALL_TAB_PRIVS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND PRIVILEGE = 'DEBUG') " //$NON-NLS-1$ //$NON-NLS-2$
            + "FROM DUAL"; //$NON-NLS-1$
        try (JDBCPreparedStatement statement = session.prepareStatement(sql)) {
            statement.setString(1, PRIV_DEBUG_CONNECT_SESSION);
            statement.setString(2, PRIV_DEBUG_ANY_PROCEDURE);
            statement.setString(3, unit.owner());
            statement.setString(4, unit.name());
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return;
                }
                String user = resultSet.getString(1);
                boolean connectSession = resultSet.getInt(2) > 0;
                boolean anyProcedure = resultSet.getInt(3) > 0;
                boolean objectGrant = resultSet.getInt(4) > 0;
                List<String> grants = new ArrayList<>();
                String quotedUser = DBUtils.getQuotedIdentifier(routine.getDataSource(), user);
                if (!connectSession) {
                    grants.add("GRANT " + PRIV_DEBUG_CONNECT_SESSION + " TO " + quotedUser + ";"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                if (!unit.owner().equals(user) && !anyProcedure && !objectGrant) {
                    grants.add("GRANT DEBUG ON " + DBUtils.getQuotedIdentifier(routine.getDataSource(), unit.owner()) + "." //$NON-NLS-1$ //$NON-NLS-2$
                        + DBUtils.getQuotedIdentifier(routine.getDataSource(), unit.name()) + " TO " + quotedUser + ";"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (!grants.isEmpty()) {
                    throw new DBGPrivilegeException(user, grants);
                }
            }
        }
    }

    @NotNull
    private static String readSessionUser(@NotNull JDBCSession session) throws SQLException {
        try (JDBCPreparedStatement statement = session.prepareStatement("SELECT SYS_CONTEXT('USERENV', 'SESSION_USER') FROM DUAL"); //$NON-NLS-1$
             JDBCResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getString(1) : ""; //$NON-NLS-1$
        }
    }

    /**
     * The unit's source as {@code ALL_SOURCE} numbers its lines (empty if not visible).
     */
    @NotNull
    static String readUnitSource(@NotNull JDBCSession session, @NotNull OracleDebugUnit unit) throws SQLException {
        String sql = "SELECT TEXT FROM " + OracleUtils.getSysSchemaPrefix((OracleDataSource) session.getDataSource()) //$NON-NLS-1$
            + "ALL_SOURCE WHERE OWNER = ? AND NAME = ? AND TYPE = ? ORDER BY LINE"; //$NON-NLS-1$
        StringBuilder source = new StringBuilder();
        try (JDBCPreparedStatement statement = session.prepareStatement(sql)) {
            statement.setString(1, unit.owner());
            statement.setString(2, unit.name());
            statement.setString(3, unit.type());
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String text = resultSet.getString(1);
                    if (text == null) {
                        text = ""; //$NON-NLS-1$
                    }
                    // Each ALL_SOURCE row is one line and ends with its line break, except sometimes the last one
                    if (!source.isEmpty() && source.charAt(source.length() - 1) != '\n') {
                        source.append('\n');
                    }
                    source.append(text);
                }
            }
        }
        // No empty line after the last one (the editor would show one more line than ALL_SOURCE has)
        if (!source.isEmpty() && source.charAt(source.length() - 1) == '\n') {
            source.setLength(source.length() - 1);
        }
        return source.toString();
    }

    @NotNull
    private String sysPrefix() {
        return OracleUtils.getSysSchemaPrefix(routine.getDataSource());
    }
}
