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
package com.seizia.chromite.model.admin.maintenance;

import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.impls.PostgreServerPostgreSQL;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.admin.AdminGrid;
import com.seizia.chromite.model.admin.mssql.SQLServerMaintenanceDialect;
import com.seizia.chromite.model.admin.mysql.MySQLMaintenanceDialect;
import com.seizia.chromite.model.admin.oracle.OracleMaintenanceDialect;
import com.seizia.chromite.model.admin.postgresql.PostgreMaintenanceDialect;
import com.seizia.chromite.model.admin.space.SpaceColumn;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 유지 관리(DB 관리 축 4)의 DB별 부분: 대상에 할 수 있는 동작과 권한, 문장 만들기, 유지 관리 탭의 후보·상태 목록.
 *
 * 설계: 공간 사용량({@code ServerSpaceDialect})과 같이 DB마다 하위 클래스 하나를 둔다. DBeaver에는 같은 일을 하는 SWT 도구
 * ({@code PostgreToolBaseVacuum}, {@code MySQLToolTableAnalyze} 등 {@code SQLToolExecuteHandler})가 있지만 SWT 도구 대화상자에 묶여
 * 있고 권한 확인·후보 목록이 없어, 문장 모양만 따르고 이 번들에서 새로 만든다.
 * <ul>
 *     <li>권한을 먼저 본다: {@link #prepare}가 로그인이 할 수 없는 동작과 이유를 담고, 화면은 그 동작의 실행을 끈다.</li>
 *     <li>미리 보기에 보인 문장({@link #buildCommand})을 그대로 실행한다({@link #execute}). 서버가 돌려준 표와 메시지를 모두 보인다.</li>
 *     <li>실행은 사용자의 트랜잭션과 섞이지 않게 자동 커밋인 격리된 연결에서 한다({@link MaintenanceRunner}). VACUUM과 REINDEX CONCURRENTLY는
 *     트랜잭션 안에서 실행할 수 없고(DBeaver {@code PostgreToolBaseVacuum#isRunInAutoCommit}도 같은 이유), Oracle DDL은 열린 트랜잭션을 커밋한다.</li>
 *     <li>Oracle은 Diagnostics/Tuning Pack이 필요한 뷰({@code DBA_HIST_*}, {@code V$ACTIVE_SESSION_HISTORY}, SQL Tuning Advisor)를 쓰지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
public abstract class MaintenanceDialect {

    /**
     * Rows kept from one result set of a maintenance statement.
     */
    private static final int MAX_RESULT_ROWS = 10_000;

    /**
     * Maintenance support of a data source.
     *
     * @return null if the database is not supported
     */
    @Nullable
    public static MaintenanceDialect forDataSource(@NotNull DBPDataSource dataSource) {
        if (dataSource instanceof SQLServerDataSource sqlServer) {
            return new SQLServerMaintenanceDialect(sqlServer);
        }
        // pg_stat_user_tables.n_mod_since_analyze is 9.4+; PostgreSQL based servers (Greenplum, Redshift) have their own commands
        if (dataSource instanceof PostgreDataSource postgre && postgre.getServerType() instanceof PostgreServerPostgreSQL
            && postgre.isServerVersionAtLeast(9, 4)) {
            return new PostgreMaintenanceDialect(postgre);
        }
        if (dataSource instanceof MySQLDataSource mysql) {
            return new MySQLMaintenanceDialect(mysql);
        }
        if (dataSource instanceof OracleDataSource oracle) {
            return new OracleMaintenanceDialect(oracle);
        }
        return null;
    }

    /**
     * The maintenance target of an Object Explorer object.
     *
     * @return null if no maintenance applies to it
     */
    @Nullable
    public abstract MaintenanceTarget getTarget(@NotNull DBSObject object);

    /**
     * Label of the scope of the lists ("Database", "Schema", "Owner").
     */
    @NotNull
    public abstract String getScopeLabel();

    /**
     * Whether the lists can cover all scopes at once (SQL Server reads one database at a time).
     */
    public boolean supportsAllScopes() {
        return true;
    }

    /**
     * Reads the candidate and status lists of the maintenance tab.
     *
     * @param scope database, schema or owner; null for all (or the current database when all is not supported)
     * @param limit rows per list
     */
    @NotNull
    public abstract MaintenanceReport readOverview(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @Nullable String scope, int limit)
        throws DBException;

    /**
     * Reads what the dialog needs for a target: its operations, what the login may not run, the current state of the target.
     */
    @NotNull
    public abstract MaintenancePlan prepare(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @NotNull MaintenanceTarget target)
        throws DBException;

    /**
     * Builds the statements of an operation. Reads nothing from the server.
     *
     * @param values option values by {@link MaintenanceOption#id()} (missing ones take their defaults)
     */
    @NotNull
    public abstract MaintenanceCommand buildCommand(@NotNull MaintenancePlan plan, @NotNull String operationId, @NotNull Map<String, Object> values);

    /**
     * Runs the statements one by one and collects the result sets and server messages. Stops at the first error or when canceled
     * (the job's cancel also cancels the running statement: the JDBC statement is a blocking object of the monitor).
     */
    @NotNull
    public MaintenanceResult execute(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @NotNull MaintenanceCommand command) {
        List<AdminGrid> grids = new ArrayList<>();
        List<MaintenanceResult.Message> messages = new ArrayList<>();
        MaintenanceResult.Status status = MaintenanceResult.Status.SUCCEEDED;
        long start = System.currentTimeMillis();
        List<String> statements = command.statements();
        monitor.beginTask(MaintenanceMessages.maint_progress_running, statements.size());
        for (int i = 0; i < statements.size(); i++) {
            if (monitor.isCanceled()) {
                status = MaintenanceResult.Status.CANCELED;
                break;
            }
            String sql = statements.get(i);
            monitor.subTask(NLS.bind(MaintenanceMessages.maint_progress_statement, i + 1, statements.size()));
            messages.add(new MaintenanceResult.Message(MaintenanceResult.Level.INFO, sql));
            long statementStart = System.currentTimeMillis();
            try (JDBCStatement statement = session.createStatement()) {
                boolean hasResult = statement.execute(sql);
                while (true) {
                    readWarnings(statement, messages);
                    if (hasResult) {
                        try (ResultSet resultSet = statement.getResultSet()) {
                            if (resultSet != null) {
                                grids.add(readGrid(resultSet, grids.size() + 1));
                            }
                        }
                    } else if (statement.getUpdateCount() == -1) {
                        break;
                    }
                    hasResult = statement.getMoreResults();
                }
                readWarnings(statement, messages);
                messages.add(new MaintenanceResult.Message(MaintenanceResult.Level.INFO,
                    NLS.bind(MaintenanceMessages.maint_msg_statement_done, formatSeconds(System.currentTimeMillis() - statementStart))));
            } catch (SQLException e) {
                if (monitor.isCanceled()) {
                    status = MaintenanceResult.Status.CANCELED;
                    messages.add(new MaintenanceResult.Message(MaintenanceResult.Level.WARNING, MaintenanceMessages.maint_msg_canceled));
                } else {
                    status = MaintenanceResult.Status.FAILED;
                    messages.add(new MaintenanceResult.Message(MaintenanceResult.Level.ERROR, describe(e)));
                }
                break;
            }
            monitor.worked(1);
        }
        monitor.done();
        if (status == MaintenanceResult.Status.CANCELED && (messages.isEmpty()
            || !MaintenanceMessages.maint_msg_canceled.equals(messages.get(messages.size() - 1).text()))) {
            messages.add(new MaintenanceResult.Message(MaintenanceResult.Level.WARNING, MaintenanceMessages.maint_msg_canceled));
        }
        return new MaintenanceResult(grids, messages, status, System.currentTimeMillis() - start);
    }

    private static void readWarnings(@NotNull JDBCStatement statement, @NotNull List<MaintenanceResult.Message> messages) throws SQLException {
        // PostgreSQL notices (VACUUM VERBOSE), SQL Server informational messages (DBCC) and Oracle "completed with warning"
        for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning()) {
            String text = warning.getMessage();
            if (text != null && !text.isBlank()) {
                messages.add(new MaintenanceResult.Message(MaintenanceResult.Level.WARNING, text.strip()));
            }
        }
        statement.clearWarnings();
    }

    @NotNull
    private static AdminGrid readGrid(@NotNull ResultSet resultSet, int number) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        int count = meta.getColumnCount();
        List<SpaceColumn> columns = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            columns.add(SpaceColumn.text(meta.getColumnLabel(i)));
        }
        AdminGrid grid = new AdminGrid("result" + number, NLS.bind(MaintenanceMessages.maint_result_title, number), columns); //$NON-NLS-1$
        int rows = 0;
        while (resultSet.next() && rows++ < MAX_RESULT_ROWS) {
            Object[] values = new Object[count];
            for (int i = 0; i < count; i++) {
                values[i] = resultSet.getString(i + 1);
            }
            grid.addRow(values);
        }
        return grid;
    }

    @NotNull
    private static String describe(@NotNull SQLException e) {
        StringBuilder text = new StringBuilder();
        for (SQLException error = e; error != null; error = error.getNextException()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage().strip());
            if (error.getNextException() == error) {
                break;
            }
        }
        return text.toString();
    }

    @NotNull
    protected static String formatSeconds(long millis) {
        return String.format("%.1f", millis / 1000.0); //$NON-NLS-1$
    }

    /**
     * A flag option value.
     */
    protected static boolean flag(@NotNull MaintenancePlan plan, @NotNull String operationId, @NotNull Map<String, Object> values,
                                  @NotNull String optionId) {
        return value(plan, operationId, values, optionId) instanceof Boolean value && value;
    }

    /**
     * A choice option value.
     */
    @NotNull
    protected static String choice(@NotNull MaintenancePlan plan, @NotNull String operationId, @NotNull Map<String, Object> values,
                                   @NotNull String optionId) {
        Object value = value(plan, operationId, values, optionId);
        return value == null ? "" : value.toString(); //$NON-NLS-1$
    }

    @Nullable
    private static Object value(@NotNull MaintenancePlan plan, @NotNull String operationId, @NotNull Map<String, Object> values,
                                @NotNull String optionId) {
        MaintenanceOperation operation = plan.getOperation(operationId);
        if (operation == null) {
            return null;
        }
        for (MaintenanceOption option : operation.options()) {
            if (option.id().equals(optionId)) {
                if (!option.enabled()) {
                    return option.defaultValue();
                }
                Object value = values.get(optionId);
                return value != null ? value : option.defaultValue();
            }
        }
        return null;
    }
}
