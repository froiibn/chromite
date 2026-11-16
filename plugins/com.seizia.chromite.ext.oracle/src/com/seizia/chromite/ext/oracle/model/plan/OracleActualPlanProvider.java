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
package com.seizia.chromite.ext.oracle.model.plan;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.exec.plan.DBCPlan;
import com.seizia.chromite.model.sql.exec.SQLActualPlanProvider;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Oracle actual plans: the script runs with {@code statistics_level = ALL} for the session, and after each statement
 * the plan of its cursor is read with the row source statistics of that execution
 * ({@code V$SQL_PLAN_STATISTICS_ALL}, {@code DBMS_XPLAN.DISPLAY_CURSOR(sql_id, child, 'ALLSTATS LAST')}).
 *
 * 설계:
 * <ul>
 *     <li>문장은 한 번, 쿼리 창의 세션·트랜잭션에서 그대로 실행된다. 통계는 세션 설정으로 모은다. Chromite Agent의 {@code getActualPlan}
 *     ({@code PlanTools})처럼 {@code GATHER_PLAN_STATISTICS} 힌트를 끼우면 사용자 문장 글자가 바뀌어 오류 위치(글자 위치)가 어긋나고
 *     원본 보기에 힌트가 보이므로 쓰지 않았다. {@code PlanTools}에서는 같은 계획 행 뷰, {@code ALLSTATS LAST} 글자, 권한 안내를 가져왔다.</li>
 *     <li>켜기 전 값({@code V$PARAMETER}는 이 세션의 값)을 읽어 두었다가 끝날 때(오류·취소 포함) 그 값으로 되돌린다. 읽지 못하면(권한 없음)
 *     되돌릴 값을 모르므로 켜지 않는다. {@code ALTER SESSION}은 트랜잭션을 끝내지 않는다.</li>
 *     <li>계획은 문장을 닫은 직후 읽는다(커서를 닫아야 그 실행의 통계가 끝난다: 200행만 가져와도 A-Rows는 200).
 *     커서의 SQL_ID는 문장이 아직 열려 있을 때 드라이버에게 받는다({@code OracleStatement.getSqlId()}, ojdbc 23부터). 닫은 뒤의
 *     {@code V$SESSION.PREV_SQL_ID}는 결과 행 수 제한({@code setMaxRows}, 모든 쿼리에 걸림)에 닿아 끝까지 읽지 않은 커서를 가리키지
 *     않아서(앞 문장을 가리킨다), 최대 행 수 이상을 돌려준 쿼리의 계획을 잃었다. 자식 커서는 PREV가 같은 SQL_ID면 그 번호, 아니면 그
 *     SQL_ID에서 가장 최근에 쓰인 자식이다(statistics_level = ALL 세션은 따로 자식 커서를 쓴다). SQL_ID를 주지 않는 옛 드라이버는
 *     PREV_SQL_ID로 찾는다.</li>
 *     <li>그 사이 같은 세션에서 다른 문장이 돌았으면 엉뚱한 계획을 보이지 않도록, 커서 글자
 *     ({@code V$SQL.SQL_TEXT}, 앞 1,000자)가 실행한 글자와 같은지 확인한다(공백 차이, 드라이버가 {@code ?}를 바꾼 {@code :1}은 같게 본다).</li>
 *     <li>계획이 없는 문장(DDL, PL/SQL 블록, COMMIT 등)은 첫 낱말로 걸러 서버에 묻지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-sql-editor-plus.md "실제 계획 토글을 Altibase·Oracle로")
 * - 2026-11-12 커서를 드라이버가 준 SQL_ID로 찾음: 최대 행 수(기본 200)에 닿은 쿼리는 PREV_SQL_ID가 앞 문장을 가리켜 "another statement
 *   ran" 오류로 계획이 나오지 않았다(앱 변경 #58, docs/chromite/swing-sql-editor-plus.md "실제 계획 토글 점검")
 */
public class OracleActualPlanProvider implements SQLActualPlanProvider {

    private static final Log log = Log.getLog(OracleActualPlanProvider.class);

    private static final String ALL = "ALL"; //$NON-NLS-1$
    private static final String ORACLE_STATEMENT_CLASS = "oracle.jdbc.OracleStatement"; //$NON-NLS-1$
    private static final String PRIVILEGE_HINT =
        "Actual plans need SELECT on V$PARAMETER, V$SESSION, V$SQL and V$SQL_PLAN_STATISTICS_ALL: " +
            "GRANT SELECT_CATALOG_ROLE (or SELECT ANY DICTIONARY) TO <user>";
    // Statements that have a plan, after leading comments and parentheses
    private static final Pattern PLANNED_STATEMENT = Pattern.compile("(SELECT|WITH|INSERT|UPDATE|DELETE|MERGE)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE);
    // The parameter values of statistics_level (BASIC, TYPICAL, ALL)
    private static final Pattern SETTING_VALUE = Pattern.compile("[A-Za-z]+"); //$NON-NLS-1$
    private static final Pattern WHITESPACE = Pattern.compile("\\s+"); //$NON-NLS-1$
    // The thin driver sends JDBC "?" markers as ":1", ":2"…
    private static final Pattern BIND_MARKER = Pattern.compile(":\\d+"); //$NON-NLS-1$

    private final OracleDataSource dataSource;

    public OracleActualPlanProvider(@NotNull OracleDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public Run startRun(@NotNull DBCSession session) throws DBException {
        JDBCSession jdbcSession = (JDBCSession) session;
        String previous;
        try (JDBCPreparedStatement statement = jdbcSession.prepareStatement(
            "SELECT VALUE FROM V$PARAMETER WHERE NAME = 'statistics_level'")) {
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                previous = resultSet.next() ? resultSet.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new DBException(PRIVILEGE_HINT + ". " + e.getMessage(), e);
        }
        if (previous == null || !SETTING_VALUE.matcher(previous).matches()) {
            throw new DBException("Can't read the session's statistics_level, so it could not be restored afterwards");
        }
        boolean changed = !ALL.equalsIgnoreCase(previous);
        if (changed) {
            setStatisticsLevel(jdbcSession, ALL);
        }
        return new Run() {
            // The SQL_ID the driver gave for the statement while it was open, and that statement's text
            @Nullable
            private String openSqlId;
            @Nullable
            private String openQuery;

            @Nullable
            @Override
            public DBCPlan readOpenStatementPlan(
                @NotNull DBCSession session,
                @NotNull DBCStatement statement,
                @NotNull String query
            ) {
                // Only remembered: the statistics of the execution are complete after the cursor is closed (readStatementPlan)
                openSqlId = hasPlan(query) ? getDriverSqlId(statement) : null;
                openQuery = query;
                return null;
            }

            @Nullable
            @Override
            public DBCPlan readStatementPlan(@NotNull DBCSession session, @NotNull String query) throws DBException {
                String sqlId = query.equals(openQuery) ? openSqlId : null;
                openSqlId = null;
                openQuery = null;
                return readPlan((JDBCSession) session, query, sqlId);
            }

            @Override
            public void finish(@NotNull DBCSession session) throws DBException {
                if (changed) {
                    setStatisticsLevel((JDBCSession) session, previous);
                }
            }
        };
    }

    @Nullable
    private DBCPlan readPlan(@NotNull JDBCSession session, @NotNull String query, @Nullable String driverSqlId) throws DBException {
        if (!hasPlan(query)) {
            return null;
        }
        String sqlId;
        long child;
        String cursorText;
        try {
            // First: anything else this session runs becomes its previous statement
            try (JDBCPreparedStatement statement = session.prepareStatement(
                "SELECT PREV_SQL_ID, PREV_CHILD_NUMBER FROM V$SESSION WHERE SID = SYS_CONTEXT('USERENV', 'SID')")) {
                try (JDBCResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next() || resultSet.getString(1) == null && driverSqlId == null) {
                        throw new DBException("The statement ran, but the session reports no previous statement");
                    }
                    sqlId = resultSet.getString(1);
                    child = resultSet.getLong(2);
                }
            }
            if (driverSqlId != null && !driverSqlId.equals(sqlId)) {
                // A cursor left before its end (max rows) is not the session's previous statement: its latest child
                sqlId = driverSqlId;
                try (JDBCPreparedStatement statement = session.prepareStatement(
                    "SELECT CHILD_NUMBER FROM (SELECT CHILD_NUMBER FROM V$SQL WHERE SQL_ID = ? " +
                        "ORDER BY LAST_ACTIVE_TIME DESC, CHILD_NUMBER DESC) WHERE ROWNUM = 1")) {
                    statement.setString(1, sqlId);
                    try (JDBCResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new DBException("The statement ran, but its cursor was not found in V$SQL (aged out of the shared pool)");
                        }
                        child = resultSet.getLong(1);
                    }
                }
            }
            try (JDBCPreparedStatement statement = session.prepareStatement(
                "SELECT SQL_TEXT FROM V$SQL WHERE SQL_ID = ? AND CHILD_NUMBER = ?")) {
                statement.setString(1, sqlId);
                statement.setLong(2, child);
                try (JDBCResultSet resultSet = statement.executeQuery()) {
                    cursorText = resultSet.next() ? resultSet.getString(1) : null;
                }
            }
        } catch (SQLException e) {
            throw new DBException("The statement ran, but its plan can't be read. " + PRIVILEGE_HINT + ". " + e.getMessage(), e);
        }
        if (cursorText == null) {
            throw new DBException("The statement ran, but its cursor was not found in V$SQL (aged out of the shared pool)");
        }
        if (!isSameStatement(query, cursorText)) {
            throw new DBException("The statement ran, but another statement ran on the session before its plan was read");
        }
        OracleExecutionPlan plan = new OracleExecutionPlan(dataSource, session, query, sqlId, child);
        return plan.readCursorPlan() ? plan : null;
    }

    /**
     * The SQL_ID of the statement's cursor as the driver reports it ({@code OracleStatement.getSqlId()}, ojdbc 23 and
     * later; through reflection, the driver is not a dependency of this bundle), or null when the driver can't tell.
     */
    @Nullable
    private static String getDriverSqlId(@NotNull DBCStatement statement) {
        if (!(statement instanceof JDBCStatement jdbcStatement)) {
            return null;
        }
        try {
            Statement original = jdbcStatement.unwrap(Statement.class);
            Class<?> api = Class.forName(ORACLE_STATEMENT_CLASS, false, original.getClass().getClassLoader());
            if (!api.isInstance(original)) {
                return null;
            }
            Object sqlId = api.getMethod("getSqlId").invoke(original); //$NON-NLS-1$
            return sqlId instanceof String text && !text.isEmpty() ? text : null;
        } catch (ReflectiveOperationException | SQLException | LinkageError e) {
            log.debug("The driver gives no SQL_ID: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static void setStatisticsLevel(@NotNull JDBCSession session, @NotNull String value) throws DBException {
        try (JDBCStatement statement = session.createStatement()) {
            statement.execute("ALTER SESSION SET STATISTICS_LEVEL = " + value.toUpperCase(Locale.ENGLISH)); //$NON-NLS-1$
        } catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        }
    }

    /**
     * Whether the statement is one Oracle makes a plan for, by its first word after comments and parentheses.
     */
    static boolean hasPlan(@NotNull String query) {
        int pos = 0;
        int length = query.length();
        while (pos < length) {
            char c = query.charAt(pos);
            if (Character.isWhitespace(c) || c == '(') {
                pos++;
            } else if (query.startsWith("--", pos)) { //$NON-NLS-1$
                int end = query.indexOf('\n', pos + 2);
                if (end < 0) {
                    return false;
                }
                pos = end + 1;
            } else if (query.startsWith("/*", pos)) { //$NON-NLS-1$
                int end = query.indexOf("*/", pos + 2); //$NON-NLS-1$
                if (end < 0) {
                    return false;
                }
                pos = end + 2;
            } else {
                break;
            }
        }
        return PLANNED_STATEMENT.matcher(query).region(pos, length).lookingAt();
    }

    /**
     * Whether the cursor's text (its first 1,000 characters in V$SQL) is the text that was executed.
     */
    static boolean isSameStatement(@NotNull String executed, @NotNull String cursorText) {
        String expected = normalize(executed);
        String actual = normalize(cursorText);
        return !actual.isEmpty() && expected.startsWith(actual);
    }

    @NotNull
    private static String normalize(@NotNull String text) {
        String markers = BIND_MARKER.matcher(text.replace("?", ":0")).replaceAll(":0"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return WHITESPACE.matcher(markers).replaceAll(" ").strip(); //$NON-NLS-1$
    }
}
