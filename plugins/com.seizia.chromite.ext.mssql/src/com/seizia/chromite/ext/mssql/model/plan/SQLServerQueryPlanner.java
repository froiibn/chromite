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
package com.seizia.chromite.ext.mssql.model.plan;

import com.seizia.chromite.model.sql.exec.SQLActualPlanProvider;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.exec.plan.DBCPlan;
import com.seizia.chromite.model.exec.plan.DBCPlanStyle;
import com.seizia.chromite.model.exec.plan.DBCQueryPlanner;
import com.seizia.chromite.model.exec.plan.DBCQueryPlannerConfiguration;

import java.sql.SQLException;
import java.util.List;

/**
 * SQL Server execution plans: estimated plans with {@code SET SHOWPLAN_XML ON}, and actual plans returned during
 * execution with {@code SET STATISTICS XML ON} ({@link SQLActualPlanProvider}).
 *
 * 설계: DBeaver CE에는 SQL Server 계획기가 없다(다른 DB는 {@code PostgreQueryPlaner}, {@code MySQLPlanAnalyser}처럼 데이터 소스 어댑터로 제공).
 * 같은 방식으로 {@link SQLServerDataSource#getAdapter}가 이 객체를 돌려주므로 SWT 편집기의 계획 보기에서도 쓰인다.
 * <ul>
 *     <li>예상 계획: {@code SHOWPLAN_XML}이 켜진 동안 서버는 문장을 실행하지 않고 일괄 처리마다 계획 XML 한 행을 돌려준다.
 *     SET 문은 일괄 처리에 혼자 있어야 하므로 따로 실행하고, 오류가 나도 끄는 문장은 반드시 실행한다(연결이 계획 모드로 남지 않게).</li>
 *     <li>실제 계획: {@code STATISTICS XML}이 켜진 동안 서버는 문장을 실행하고 문장마다 계획 XML 결과 집합 하나를 더 돌려준다.
 *     그 결과 집합의 열 이름이 {@value #SHOWPLAN_COLUMN}이다.</li>
 *     <li>실제 계획을 켜기 전에 세션의 현재 DB에서 SHOWPLAN 권한을 확인한다. 권한이 없어도 {@code SET STATISTICS XML ON}은
 *     성공하지만, 그 뒤의 문장이 모두 오류 262로 실패해 사용자가 실행한 문장이 돌지 않는다. 권한이 없으면 켜지 않고 이유를
 *     알리며, 문장은 계획 없이 그대로 실행된다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-08 실제 계획을 {@link SQLActualPlanProvider#startRun}의 실행 단위로 켜고 끔(어댑터 모양이 바뀜, 동작은 같음)
 * - 2026-11-12 SHOWPLAN 권한이 없는 로그인: 켜기 전에 확인하고 이유를 알림. 전에는 실제 계획을 켠 실행의 모든 문장이 오류 262로
 *   실패했다(앱 변경 #58, docs/chromite/swing-sql-editor-plus.md "실제 계획 토글 점검")
 */
public class SQLServerQueryPlanner implements DBCQueryPlanner, SQLActualPlanProvider {

    private static final Log log = Log.getLog(SQLServerQueryPlanner.class);

    /**
     * Column label of the showplan result sets.
     */
    public static final String SHOWPLAN_COLUMN = "Microsoft SQL Server 2005 XML Showplan"; //$NON-NLS-1$

    private static final String SHOWPLAN_ON = "SET SHOWPLAN_XML ON"; //$NON-NLS-1$
    private static final String SHOWPLAN_OFF = "SET SHOWPLAN_XML OFF"; //$NON-NLS-1$
    private static final String STATISTICS_ON = "SET STATISTICS XML ON"; //$NON-NLS-1$
    private static final String STATISTICS_OFF = "SET STATISTICS XML OFF"; //$NON-NLS-1$
    private static final String SHOWPLAN_PERMISSION_QUERY =
        "SELECT DB_NAME(), HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'SHOWPLAN')"; //$NON-NLS-1$

    private final SQLServerDataSource dataSource;

    public SQLServerQueryPlanner(@NotNull SQLServerDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public SQLServerDataSource getDataSource() {
        return dataSource;
    }

    @NotNull
    @Override
    public DBCPlan planQueryExecution(
        @NotNull DBCSession session,
        @NotNull String query,
        @NotNull DBCQueryPlannerConfiguration configuration
    ) throws DBException {
        JDBCSession jdbcSession = (JDBCSession) session;
        StringBuilder planXml = new StringBuilder();
        try (JDBCStatement statement = jdbcSession.createStatement()) {
            statement.execute(SHOWPLAN_ON);
            try {
                boolean hasResultSet = statement.execute(query);
                // One plan row per batch; read every result in case the server splits it
                while (true) {
                    if (hasResultSet) {
                        try (JDBCResultSet resultSet = statement.getResultSet()) {
                            while (resultSet.next()) {
                                String xml = resultSet.getString(1);
                                if (xml != null && planXml.isEmpty()) {
                                    planXml.append(xml);
                                }
                            }
                        }
                    } else if (statement.getUpdateCount() < 0) {
                        break;
                    }
                    hasResultSet = statement.getMoreResults();
                }
            } finally {
                try {
                    statement.execute(SHOWPLAN_OFF);
                } catch (SQLException e) {
                    log.warn("Can't turn the showplan mode off", e);
                }
            }
        } catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        }
        if (planXml.isEmpty()) {
            throw new DBCException("The server returned no execution plan");
        }
        return SQLServerExecutionPlan.parse(query, planXml.toString());
    }

    @NotNull
    @Override
    public DBCPlanStyle getPlanStyle() {
        return DBCPlanStyle.PLAN;
    }

    @NotNull
    @Override
    public Run startRun(@NotNull DBCSession session) throws DBException {
        checkShowplanPermission(session);
        executeSetting(session, STATISTICS_ON);
        return new Run() {
            @Override
            public boolean isPlanResultSet(@NotNull List<String> columnLabels) {
                return columnLabels.size() == 1 && SHOWPLAN_COLUMN.equals(columnLabels.getFirst());
            }

            @NotNull
            @Override
            public DBCPlan parsePlan(@NotNull String query, @NotNull String planData) throws DBException {
                // The plan names its own statement; the query is the whole batch that was executed
                SQLServerExecutionPlan plan = SQLServerExecutionPlan.parse(null, planData);
                return plan.getQueryString().isEmpty() ? SQLServerExecutionPlan.parse(query, planData) : plan;
            }

            @Override
            public void finish(@NotNull DBCSession session) throws DBException {
                executeSetting(session, STATISTICS_OFF);
            }
        };
    }

    /**
     * Fails when the login lacks SHOWPLAN in the session's current database: with STATISTICS XML on, every statement would
     * then fail with error 262 instead of running. A USE later in the script into a database without the permission still
     * fails there, as in SSMS. The check reads no table, so it begins no implicit transaction in manual commit mode.
     */
    private static void checkShowplanPermission(@NotNull DBCSession session) throws DBException {
        String database;
        boolean permitted;
        try (JDBCStatement statement = ((JDBCSession) session).createStatement();
             JDBCResultSet resultSet = statement.executeQuery(SHOWPLAN_PERMISSION_QUERY)) {
            if (!resultSet.next()) {
                return;
            }
            database = resultSet.getString(1);
            // NULL: the server could not tell; the run then shows what the server says
            permitted = resultSet.getObject(2) == null || resultSet.getInt(2) != 0;
        } catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        }
        if (!permitted) {
            throw new DBException("Actual plans need the SHOWPLAN permission in database '" + database
                + "' (GRANT SHOWPLAN TO <user>); the statements run without it");
        }
    }

    /**
     * Runs a SET statement alone (SET statements must be alone in their batch).
     */
    private static void executeSetting(@NotNull DBCSession session, @NotNull String sql) throws DBException {
        try (JDBCStatement statement = ((JDBCSession) session).createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        }
    }
}
