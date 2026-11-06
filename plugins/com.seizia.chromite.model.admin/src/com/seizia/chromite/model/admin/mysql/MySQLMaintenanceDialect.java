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
package com.seizia.chromite.model.admin.mysql;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mysql.model.MySQLCatalog;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.mysql.model.MySQLTable;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.admin.AdminGrid;
import com.seizia.chromite.model.admin.AdminSql;
import com.seizia.chromite.model.admin.maintenance.MaintenanceAction;
import com.seizia.chromite.model.admin.maintenance.MaintenanceCommand;
import com.seizia.chromite.model.admin.maintenance.MaintenanceDialect;
import com.seizia.chromite.model.admin.maintenance.MaintenanceMessages;
import com.seizia.chromite.model.admin.maintenance.MaintenanceOperation;
import com.seizia.chromite.model.admin.maintenance.MaintenanceOption;
import com.seizia.chromite.model.admin.maintenance.MaintenancePlan;
import com.seizia.chromite.model.admin.maintenance.MaintenanceReport;
import com.seizia.chromite.model.admin.maintenance.MaintenanceTarget;
import com.seizia.chromite.model.admin.space.SpaceColumn;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MySQL·MariaDB 유지 관리: ANALYZE, OPTIMIZE, CHECK, REPAIR TABLE과 유지 관리 탭의 빈 공간(DATA_FREE)·통계 목록.
 *
 * 설계:
 * <ul>
 *     <li>문장 모양은 DBeaver {@code MySQLToolTableAnalyze}/{@code MySQLToolTableCheck}/{@code MySQLToolTableOptimize}/{@code MySQLToolTableRepair}를
 *     따른다. 서버가 테이블마다 Table/Op/Msg_type/Msg_text 행을 돌려주므로 결과 표를 그대로 보인다(오류도 예외가 아니라 Msg_type=error 행이다).</li>
 *     <li>데이터베이스(스키마) 대상은 기본 테이블을 {@value #TABLES_PER_STATEMENT}개씩 한 문장에 넣는다(한 문장이 여러 테이블을 받는다, 문장 사이에서 취소를 본다).</li>
 *     <li>권한: ANALYZE·OPTIMIZE·REPAIR는 테이블의 SELECT와 INSERT가 필요하다(MySQL 문서). {@code information_schema}의 USER/SCHEMA/TABLE_PRIVILEGES에서
 *     현재 계정의 권한을 모아 본다. 역할이 켜져 있으면(ENABLED_ROLES) 역할의 권한까지 계산하지 않고 서버의 판단에 맡긴다.
 *     CHECK는 테이블에 아무 권한이 있으면 되고, 목록에 보이는 테이블은 이미 권한이 있다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
public class MySQLMaintenanceDialect extends MaintenanceDialect {

    private static final Log log = Log.getLog(MySQLMaintenanceDialect.class);

    public static final String OP_ANALYZE = "analyze"; //$NON-NLS-1$
    public static final String OP_OPTIMIZE = "optimize"; //$NON-NLS-1$
    public static final String OP_CHECK = "check"; //$NON-NLS-1$
    public static final String OP_REPAIR = "repair"; //$NON-NLS-1$

    private static final String OPT_LOCAL = "local"; //$NON-NLS-1$
    private static final String OPT_MODE = "mode"; //$NON-NLS-1$

    private static final int TABLES_PER_STATEMENT = 50;
    /**
     * Free space above this share of the table makes OPTIMIZE the suggestion.
     */
    private static final double FREE_RATIO_SUGGESTION = 0.1;

    private static final String SYSTEM_SCHEMAS = "('mysql', 'information_schema', 'performance_schema', 'sys')"; //$NON-NLS-1$

    @NotNull
    private final MySQLDataSource dataSource;

    public MySQLMaintenanceDialect(@NotNull MySQLDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Quoted, schema-qualified tables the statements name.
     *
     * @param checkTables tables for CHECK (any privilege)
     * @param writeTables tables for ANALYZE, OPTIMIZE and REPAIR (SELECT and INSERT)
     */
    private record Data(@NotNull List<String> checkTables, @NotNull List<String> writeTables) {
    }

    @Nullable
    @Override
    public MaintenanceTarget getTarget(@NotNull DBSObject object) {
        if (object instanceof MySQLTable table) {
            return MaintenanceTarget.table(null, table.getContainer().getName(), table.getName());
        }
        if (object instanceof MySQLCatalog catalog) {
            return MaintenanceTarget.schema(null, catalog.getName());
        }
        return null;
    }

    @NotNull
    @Override
    public String getScopeLabel() {
        return MaintenanceMessages.maint_scope_database;
    }

    @NotNull
    @Override
    public MaintenanceReport readOverview(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @Nullable String scope, int limit)
        throws DBException {
        try {
            List<String> notices = new ArrayList<>();
            List<String> scopes = AdminSql.queryStrings(session, "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA " //$NON-NLS-1$
                + "WHERE SCHEMA_NAME NOT IN " + SYSTEM_SCHEMAS + " ORDER BY SCHEMA_NAME"); //$NON-NLS-1$ //$NON-NLS-2$
            String listScope = scope != null && scopes.contains(scope) ? scope : null;

            AdminGrid tables = new AdminGrid("tables", MaintenanceMessages.maint_grid_my_tables, //$NON-NLS-1$
                SpaceColumn.text(MaintenanceMessages.maint_col_database),
                SpaceColumn.text(MaintenanceMessages.maint_col_table),
                SpaceColumn.text(MaintenanceMessages.maint_col_engine),
                SpaceColumn.count(MaintenanceMessages.maint_col_rows_est),
                SpaceColumn.bytes(MaintenanceMessages.maint_col_data),
                SpaceColumn.bytes(MaintenanceMessages.maint_col_index),
                new SpaceColumn(MaintenanceMessages.maint_col_free, SpaceColumn.Kind.BYTES, MaintenanceMessages.maint_col_free_tip),
                SpaceColumn.percent(MaintenanceMessages.maint_col_free_pct),
                SpaceColumn.text(MaintenanceMessages.maint_col_updated),
                SpaceColumn.text(MaintenanceMessages.maint_col_checked),
                SpaceColumn.text(MaintenanceMessages.maint_col_suggestion));
            tables.setDescription(MaintenanceMessages.maint_my_tables_desc);
            tables.setEmptyText(MaintenanceMessages.maint_empty_tables);
            String sql = "SELECT TABLE_SCHEMA, TABLE_NAME, ENGINE, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH, DATA_FREE, " //$NON-NLS-1$
                + "DATE_FORMAT(UPDATE_TIME, '%Y-%m-%d %H:%i:%s'), DATE_FORMAT(CHECK_TIME, '%Y-%m-%d %H:%i:%s') " //$NON-NLS-1$
                + "FROM information_schema.TABLES WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_SCHEMA NOT IN " + SYSTEM_SCHEMAS //$NON-NLS-1$
                + (listScope != null ? " AND TABLE_SCHEMA = ?" : "") //$NON-NLS-1$ //$NON-NLS-2$
                + " ORDER BY DATA_FREE DESC, DATA_LENGTH + INDEX_LENGTH DESC LIMIT ?"; //$NON-NLS-1$
            AdminSql.RowReader reader = rs -> {
                String schema = rs.getString(1);
                String table = rs.getString(2);
                Long data = AdminSql.getLong(rs, 5);
                Long index = AdminSql.getLong(rs, 6);
                Long free = AdminSql.getLong(rs, 7);
                Double freePercent = freePercent(data, index, free);
                String operation = freePercent != null && freePercent >= FREE_RATIO_SUGGESTION * 100 ? OP_OPTIMIZE : OP_ANALYZE;
                tables.addTaggedRow(new MaintenanceAction(MaintenanceTarget.table(null, schema, table), operation), null,
                    schema, table, rs.getString(3), AdminSql.getLong(rs, 4), data, index, free, freePercent, rs.getString(8), rs.getString(9),
                    OP_OPTIMIZE.equals(operation) ? "OPTIMIZE" : "ANALYZE"); //$NON-NLS-1$ //$NON-NLS-2$
            };
            if (listScope != null) {
                AdminSql.query(session, sql, reader, listScope, limit);
            } else {
                AdminSql.query(session, sql, reader, limit);
            }

            AdminGrid statistics = new AdminGrid("statistics", MaintenanceMessages.maint_grid_my_statistics, //$NON-NLS-1$
                SpaceColumn.text(MaintenanceMessages.maint_col_database),
                SpaceColumn.text(MaintenanceMessages.maint_col_table),
                SpaceColumn.count(MaintenanceMessages.maint_col_stat_rows),
                SpaceColumn.text(MaintenanceMessages.maint_col_stat_updated));
            statistics.setDescription(MaintenanceMessages.maint_my_statistics_desc);
            statistics.setEmptyText(MaintenanceMessages.maint_empty_tables);
            if (AdminSql.probe(session, "SELECT 1 FROM mysql.innodb_table_stats WHERE 1 = 0") != null) { //$NON-NLS-1$
                statistics.setUnavailable(MaintenanceMessages.maint_my_statistics_denied);
            } else {
                String statsSql = "SELECT database_name, table_name, n_rows, DATE_FORMAT(last_update, '%Y-%m-%d %H:%i:%s') " //$NON-NLS-1$
                    + "FROM mysql.innodb_table_stats WHERE database_name NOT IN " + SYSTEM_SCHEMAS //$NON-NLS-1$
                    + (listScope != null ? " AND database_name = ?" : "") //$NON-NLS-1$ //$NON-NLS-2$
                    + " ORDER BY last_update, database_name, table_name LIMIT ?"; //$NON-NLS-1$
                AdminSql.RowReader statsReader = rs -> {
                    String schema = rs.getString(1);
                    String table = rs.getString(2);
                    statistics.addTaggedRow(new MaintenanceAction(MaintenanceTarget.table(null, schema, table), OP_ANALYZE), null,
                        schema, table, AdminSql.getLong(rs, 3), rs.getString(4));
                };
                if (listScope != null) {
                    AdminSql.query(session, statsSql, statsReader, listScope, limit);
                } else {
                    AdminSql.query(session, statsSql, statsReader, limit);
                }
            }
            if (!hasGlobal(session, "SELECT")) { //$NON-NLS-1$
                notices.add(MaintenanceMessages.maint_my_partial);
            }
            return new MaintenanceReport(List.of(tables, statistics), scopes, listScope, notices);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    @Nullable
    private static Double freePercent(@Nullable Long data, @Nullable Long index, @Nullable Long free) {
        if (free == null) {
            return null;
        }
        long total = (data == null ? 0 : data) + (index == null ? 0 : index) + free;
        return total > 0 ? 100.0 * free / total : null;
    }

    @NotNull
    @Override
    public MaintenancePlan prepare(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @NotNull MaintenanceTarget target)
        throws DBException {
        MaintenancePlan plan = new MaintenancePlan(target);
        try {
            String schema = target.schema();
            List<String> names = new ArrayList<>();
            if (target.kind() == MaintenanceTarget.Kind.TABLE) {
                String[] state = new String[8];
                AdminSql.query(session, "SELECT TABLE_NAME, ENGINE, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH, DATA_FREE, " //$NON-NLS-1$
                    + "DATE_FORMAT(UPDATE_TIME, '%Y-%m-%d %H:%i:%s'), DATE_FORMAT(CHECK_TIME, '%Y-%m-%d %H:%i:%s') " //$NON-NLS-1$
                    + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", rs -> { //$NON-NLS-1$
                        for (int i = 0; i < state.length; i++) {
                            state[i] = rs.getString(i + 1);
                        }
                    }, schema, target.name());
                if (state[0] == null) {
                    throw new DBException(NLS.bind(MaintenanceMessages.maint_not_found, target.getDisplayName()));
                }
                names.add(state[0]);
                Long data = parseLong(state[3]);
                Long index = parseLong(state[4]);
                Long free = parseLong(state[5]);
                Double freePercent = freePercent(data, index, free);
                plan.addFact(NLS.bind(MaintenanceMessages.maint_my_fact_table, new Object[] {
                    dash(state[1]), dash(state[2]), dash(state[5]), freePercent == null ? "-" : String.format("%.1f", freePercent)})); //$NON-NLS-1$ //$NON-NLS-2$
                plan.addFact(NLS.bind(MaintenanceMessages.maint_my_fact_times, never(state[6]), never(state[7])));
                plan.setSuggestedOperation(freePercent != null && freePercent >= FREE_RATIO_SUGGESTION * 100 ? OP_OPTIMIZE : OP_ANALYZE);
            } else {
                names.addAll(AdminSql.queryStrings(session, "SELECT TABLE_NAME FROM information_schema.TABLES " //$NON-NLS-1$
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME", schema)); //$NON-NLS-1$
                plan.addFact(NLS.bind(MaintenanceMessages.maint_my_fact_schema_tables, names.size()));
            }

            plan.addOperation(new MaintenanceOperation(OP_ANALYZE, "ANALYZE TABLE", MaintenanceMessages.maint_my_analyze_desc, List.of( //$NON-NLS-1$
                MaintenanceOption.flag(OPT_LOCAL, "NO_WRITE_TO_BINLOG", false, MaintenanceMessages.maint_my_local_tip)))); //$NON-NLS-1$
            plan.addOperation(new MaintenanceOperation(OP_OPTIMIZE, "OPTIMIZE TABLE", MaintenanceMessages.maint_my_optimize_desc, List.of( //$NON-NLS-1$
                MaintenanceOption.flag(OPT_LOCAL, "NO_WRITE_TO_BINLOG", false, MaintenanceMessages.maint_my_local_tip)))); //$NON-NLS-1$
            plan.addOperation(new MaintenanceOperation(OP_CHECK, "CHECK TABLE", MaintenanceMessages.maint_my_check_desc, List.of( //$NON-NLS-1$
                MaintenanceOption.choice(OPT_MODE, MaintenanceMessages.maint_opt_check_mode, List.of(
                    new MaintenanceOption.Choice("", MaintenanceMessages.maint_value_default), //$NON-NLS-1$
                    new MaintenanceOption.Choice("QUICK", "QUICK"), //$NON-NLS-1$ //$NON-NLS-2$
                    new MaintenanceOption.Choice("FAST", "FAST"), //$NON-NLS-1$ //$NON-NLS-2$
                    new MaintenanceOption.Choice("MEDIUM", "MEDIUM"), //$NON-NLS-1$ //$NON-NLS-2$
                    new MaintenanceOption.Choice("EXTENDED", "EXTENDED"), //$NON-NLS-1$ //$NON-NLS-2$
                    new MaintenanceOption.Choice("CHANGED", "CHANGED"), //$NON-NLS-1$ //$NON-NLS-2$
                    new MaintenanceOption.Choice("FOR UPGRADE", "FOR UPGRADE")), //$NON-NLS-1$ //$NON-NLS-2$
                    "", MaintenanceMessages.maint_my_check_mode_tip)))); //$NON-NLS-1$
            plan.addOperation(new MaintenanceOperation(OP_REPAIR, "REPAIR TABLE", MaintenanceMessages.maint_my_repair_desc, List.of( //$NON-NLS-1$
                MaintenanceOption.flag(OPT_LOCAL, "NO_WRITE_TO_BINLOG", false, MaintenanceMessages.maint_my_local_tip), //$NON-NLS-1$
                MaintenanceOption.choice(OPT_MODE, MaintenanceMessages.maint_opt_repair_mode, List.of(
                    new MaintenanceOption.Choice("", MaintenanceMessages.maint_value_default), //$NON-NLS-1$
                    new MaintenanceOption.Choice("QUICK", "QUICK"), //$NON-NLS-1$ //$NON-NLS-2$
                    new MaintenanceOption.Choice("EXTENDED", "EXTENDED")), //$NON-NLS-1$ //$NON-NLS-2$
                    "", null)))); //$NON-NLS-1$

            // SELECT and INSERT per table for ANALYZE, OPTIMIZE and REPAIR; CHECK needs any privilege (every listed table)
            Set<String> allowed = writableTables(session, schema, names);
            List<String> all = new ArrayList<>();
            List<String> writable = new ArrayList<>();
            for (String name : names) {
                String qualified = quote(schema) + "." + quote(name); //$NON-NLS-1$
                all.add(qualified);
                if (allowed == null || allowed.contains(name)) {
                    writable.add(qualified);
                }
            }
            if (names.isEmpty()) {
                for (MaintenanceOperation operation : plan.getOperations()) {
                    plan.deny(operation.id(), MaintenanceMessages.maint_my_schema_empty);
                }
            } else if (writable.isEmpty()) {
                plan.deny(OP_ANALYZE, MaintenanceMessages.maint_my_denied);
                plan.deny(OP_OPTIMIZE, MaintenanceMessages.maint_my_denied);
                plan.deny(OP_REPAIR, MaintenanceMessages.maint_my_denied);
            } else if (writable.size() < names.size()) {
                plan.addFact(NLS.bind(MaintenanceMessages.maint_my_fact_partial, writable.size(), names.size()));
            }
            plan.setData(new Data(List.copyOf(all), List.copyOf(writable)));
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
        return plan;
    }

    /**
     * The tables the current account may ANALYZE, OPTIMIZE and REPAIR (SELECT and INSERT).
     *
     * @return null when it cannot be told (roles, or the privilege views could not be read): the server decides
     */
    @Nullable
    private Set<String> writableTables(@NotNull JDBCSession session, @Nullable String schema, @NotNull List<String> names) {
        try {
            String grantee = grantee(session);
            if (grantee == null || hasEnabledRoles(session)) {
                return null;
            }
            Set<String> global = new HashSet<>(AdminSql.queryStrings(session,
                "SELECT PRIVILEGE_TYPE FROM information_schema.USER_PRIVILEGES WHERE GRANTEE = ? " //$NON-NLS-1$
                    + "UNION SELECT PRIVILEGE_TYPE FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE = ? AND TABLE_SCHEMA = ?", //$NON-NLS-1$
                grantee, grantee, schema));
            Map<String, Set<String>> perTable = new HashMap<>();
            AdminSql.query(session, "SELECT TABLE_NAME, PRIVILEGE_TYPE FROM information_schema.TABLE_PRIVILEGES " //$NON-NLS-1$
                + "WHERE GRANTEE = ? AND TABLE_SCHEMA = ?", //$NON-NLS-1$
                rs -> perTable.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2)), grantee, schema);
            Set<String> allowed = new HashSet<>();
            for (String name : names) {
                Set<String> privileges = new HashSet<>(global);
                privileges.addAll(perTable.getOrDefault(name, Set.of()));
                if (privileges.contains("SELECT") && privileges.contains("INSERT")) { //$NON-NLS-1$ //$NON-NLS-2$
                    allowed.add(name);
                }
            }
            return allowed;
        } catch (SQLException e) {
            log.debug("Cannot read the privileges of the account", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * The current account as information_schema names grantees: {@code 'user'@'host'}.
     */
    @Nullable
    private static String grantee(@NotNull JDBCSession session) throws SQLException {
        String user = AdminSql.queryString(session, "SELECT CURRENT_USER()"); //$NON-NLS-1$
        if (user == null) {
            return null;
        }
        int at = user.lastIndexOf('@');
        return at < 0 ? "'" + user + "'" //$NON-NLS-1$ //$NON-NLS-2$
            : "'" + user.substring(0, at) + "'@'" + user.substring(at + 1) + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static boolean hasEnabledRoles(@NotNull JDBCSession session) {
        try {
            // MariaDB returns one row with a NULL role when no role is enabled: count the names only
            String count = AdminSql.queryString(session, "SELECT COUNT(ROLE_NAME) FROM information_schema.ENABLED_ROLES"); //$NON-NLS-1$
            return count != null && !"0".equals(count); //$NON-NLS-1$
        } catch (SQLException e) {
            // No such view before MariaDB 10.0.5 / MySQL 8.0.19: no roles
            return false;
        }
    }

    private static boolean hasGlobal(@NotNull JDBCSession session, @NotNull String privilege) {
        try {
            String grantee = grantee(session);
            return grantee != null && AdminSql.queryString(session,
                "SELECT 1 FROM information_schema.USER_PRIVILEGES WHERE GRANTEE = ? AND PRIVILEGE_TYPE = ?", grantee, privilege) != null; //$NON-NLS-1$
        } catch (SQLException e) {
            return true;
        }
    }

    @NotNull
    @Override
    public MaintenanceCommand buildCommand(@NotNull MaintenancePlan plan, @NotNull String operationId, @NotNull Map<String, Object> values) {
        List<String> tables = plan.getData() instanceof Data data
            ? (OP_CHECK.equals(operationId) ? data.checkTables() : data.writeTables())
            : List.<String>of();
        String local = flag(plan, operationId, values, OPT_LOCAL) ? "NO_WRITE_TO_BINLOG " : ""; //$NON-NLS-1$ //$NON-NLS-2$
        String mode = choice(plan, operationId, values, OPT_MODE);
        String prefix = switch (operationId) {
            case OP_OPTIMIZE -> "OPTIMIZE " + local + "TABLE "; //$NON-NLS-1$ //$NON-NLS-2$
            case OP_CHECK -> "CHECK TABLE "; //$NON-NLS-1$
            case OP_REPAIR -> "REPAIR " + local + "TABLE "; //$NON-NLS-1$ //$NON-NLS-2$
            default -> "ANALYZE " + local + "TABLE "; //$NON-NLS-1$ //$NON-NLS-2$
        };
        String suffix = (OP_CHECK.equals(operationId) || OP_REPAIR.equals(operationId)) && !mode.isEmpty() ? " " + mode : ""; //$NON-NLS-1$ //$NON-NLS-2$
        List<String> statements = new ArrayList<>();
        for (int i = 0; i < tables.size(); i += TABLES_PER_STATEMENT) {
            statements.add(prefix + String.join(", ", tables.subList(i, Math.min(tables.size(), i + TABLES_PER_STATEMENT))) + suffix); //$NON-NLS-1$
        }
        List<String> warnings = new ArrayList<>();
        if (OP_OPTIMIZE.equals(operationId)) {
            warnings.add(MaintenanceMessages.maint_my_warn_optimize);
        } else if (OP_REPAIR.equals(operationId)) {
            warnings.add(MaintenanceMessages.maint_my_warn_repair);
        }
        return MaintenanceCommand.of(statements, ";", warnings); //$NON-NLS-1$
    }

    @NotNull
    private String quote(@Nullable String name) {
        return DBUtils.getQuotedIdentifier(dataSource, name == null ? "" : name); //$NON-NLS-1$
    }

    @NotNull
    private static String dash(@Nullable String value) {
        return value == null ? "-" : value; //$NON-NLS-1$
    }

    @NotNull
    private static String never(@Nullable String value) {
        return value == null ? MaintenanceMessages.maint_value_never : value;
    }

    @Nullable
    private static Long parseLong(@Nullable String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
