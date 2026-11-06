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
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.ext.mssql.model.SQLServerDatabase;
import com.seizia.chromite.ext.mssql.model.SQLServerTable;
import com.seizia.chromite.ext.mssql.model.SQLServerTableIndex;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL Server 유지 관리: 인덱스 다시 작성·다시 구성(조각화 기준 제안, 에디션이 허용하면 ONLINE), 통계 업데이트(FULLSCAN·표본), DBCC CHECKDB와
 * 유지 관리 탭의 조각난 인덱스·통계·CHECKDB 목록.
 *
 * 설계:
 * <ul>
 *     <li>조각화는 {@code sys.dm_db_index_physical_stats}의 LIMITED 모드로 읽는다(가장 가벼운 모드, 상위 페이지만 읽음).
 *     제안은 널리 쓰이는 기준: 5% 미만은 그대로, 5–30%는 REORGANIZE, 30% 초과는 REBUILD다(SSMS 문서의 권고와 같음).
 *     페이지가 적은 인덱스(1,000 미만)는 조각화가 성능에 거의 영향이 없다는 것을 설명에 적는다.</li>
 *     <li>다른 데이터베이스의 개체도 세션의 데이터베이스를 바꾸지 않는다(트랜잭션 안전 규칙). 문장은 세 부분 이름({@code [db].[schema].[table]})을 쓰고,
 *     목록과 권한은 공간 사용량과 같이 {@code EXEC [db].sys.sp_executesql}로 그 데이터베이스 안에서 읽는다.</li>
 *     <li>ONLINE 다시 작성은 엔진 에디션 3(Enterprise, Developer, Evaluation), 5(Azure SQL Database), 8(Managed Instance)에서만 된다.
 *     다른 에디션은 옵션을 끄고 이유를 보인다.</li>
 *     <li>권한: ALTER INDEX·UPDATE STATISTICS는 테이블의 ALTER, DBCC CHECKDB와 sp_updatestats는 sysadmin 또는 db_owner,
 *     조각화·통계 목록은 VIEW DATABASE STATE가 필요하다. 모두 {@code HAS_PERMS_BY_NAME}·{@code IS_MEMBER}로 먼저 본다.</li>
 *     <li>DBCC CHECKDB는 오래 걸리는 작업이다. 진행 대화상자의 취소는 JDBC 문장 취소(주의 신호)로 서버의 검사를 멈춘다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
public class SQLServerMaintenanceDialect extends MaintenanceDialect {

    private static final Log log = Log.getLog(SQLServerMaintenanceDialect.class);

    public static final String OP_REBUILD = "rebuild"; //$NON-NLS-1$
    public static final String OP_REORGANIZE = "reorganize"; //$NON-NLS-1$
    public static final String OP_UPDATE_STATISTICS = "update_statistics"; //$NON-NLS-1$
    public static final String OP_CHECKDB = "checkdb"; //$NON-NLS-1$
    public static final String OP_UPDATE_ALL_STATISTICS = "update_all_statistics"; //$NON-NLS-1$

    private static final String OPT_ONLINE = "online"; //$NON-NLS-1$
    private static final String OPT_SORT_IN_TEMPDB = "sort_in_tempdb"; //$NON-NLS-1$
    private static final String OPT_LOB_COMPACTION = "lob_compaction"; //$NON-NLS-1$
    private static final String OPT_SCAN = "scan"; //$NON-NLS-1$
    private static final String OPT_NO_INFOMSGS = "no_infomsgs"; //$NON-NLS-1$
    private static final String OPT_PHYSICAL_ONLY = "physical_only"; //$NON-NLS-1$

    private static final String SCAN_DEFAULT = ""; //$NON-NLS-1$
    private static final String SCAN_FULL = "FULLSCAN"; //$NON-NLS-1$
    private static final String SCAN_RESAMPLE = "RESAMPLE"; //$NON-NLS-1$

    /**
     * Fragmentation thresholds (percent): below REORGANIZE nothing is suggested, above REBUILD a rebuild is.
     */
    private static final double REORGANIZE_THRESHOLD = 5;
    private static final double REBUILD_THRESHOLD = 30;
    /**
     * Editions that allow ONLINE index operations: Enterprise/Developer/Evaluation, Azure SQL Database, Managed Instance.
     */
    private static final Set<Integer> ONLINE_EDITIONS = Set.of(3, 5, 8);
    /**
     * Error 1222: the lock request time out period was exceeded.
     */
    private static final int LOCK_TIMEOUT_ERROR = 1222;
    /**
     * Reads of index and statistics state wait at most this long for a lock (a table locked by a long transaction or an
     * offline rebuild would otherwise hold the read). SET in a dynamic batch lasts for that batch only.
     */
    private static final String LOCK_TIMEOUT = "SET LOCK_TIMEOUT 5000; "; //$NON-NLS-1$

    private static final String FRAGMENTATION_SQL = LOCK_TIMEOUT + "SELECT TOP (@n) s.name, o.name, i.name, i.type_desc, " //$NON-NLS-1$
        + "ps.avg_fragmentation_in_percent, ps.page_count, ps.partition_number " //$NON-NLS-1$
        + "FROM sys.dm_db_index_physical_stats(DB_ID(), NULL, NULL, NULL, 'LIMITED') ps " //$NON-NLS-1$
        + "JOIN sys.indexes i ON i.object_id = ps.object_id AND i.index_id = ps.index_id " //$NON-NLS-1$
        + "JOIN sys.objects o ON o.object_id = ps.object_id JOIN sys.schemas s ON s.schema_id = o.schema_id " //$NON-NLS-1$
        + "WHERE ps.index_id > 0 AND o.is_ms_shipped = 0 AND ps.alloc_unit_type_desc = 'IN_ROW_DATA' " //$NON-NLS-1$
        + "AND ps.avg_fragmentation_in_percent >= " + (int) REORGANIZE_THRESHOLD //$NON-NLS-1$
        + " ORDER BY ps.avg_fragmentation_in_percent DESC, ps.page_count DESC"; //$NON-NLS-1$

    private static final String STATISTICS_SQL = LOCK_TIMEOUT + "SELECT TOP (@n) s.name, o.name, st.name, CONVERT(varchar(19), sp.last_updated, 120), " //$NON-NLS-1$
        + "sp.rows, sp.modification_counter, CASE WHEN sp.rows > 0 THEN 100.0 * sp.modification_counter / sp.rows END " //$NON-NLS-1$
        + "FROM sys.stats st JOIN sys.objects o ON o.object_id = st.object_id JOIN sys.schemas s ON s.schema_id = o.schema_id " //$NON-NLS-1$
        + "CROSS APPLY sys.dm_db_stats_properties(st.object_id, st.stats_id) sp " //$NON-NLS-1$
        + "WHERE o.is_ms_shipped = 0 AND o.type = 'U' AND sp.modification_counter > 0 " //$NON-NLS-1$
        + "ORDER BY sp.modification_counter DESC"; //$NON-NLS-1$

    @NotNull
    private final SQLServerDataSource dataSource;

    public SQLServerMaintenanceDialect(@NotNull SQLServerDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * @param onlineAllowed the edition allows ONLINE = ON
     */
    private record Data(boolean onlineAllowed) {
    }

    @Nullable
    @Override
    public MaintenanceTarget getTarget(@NotNull DBSObject object) {
        if (object instanceof SQLServerTable table) {
            return MaintenanceTarget.table(table.getDatabase().getName(), table.getSchema().getName(), table.getName());
        }
        if (object instanceof SQLServerTableIndex index && index.getTable() instanceof SQLServerTable table && index.getName() != null) {
            return MaintenanceTarget.index(table.getDatabase().getName(), table.getSchema().getName(), table.getName(), index.getName());
        }
        if (object instanceof SQLServerDatabase database) {
            return MaintenanceTarget.database(database.getName());
        }
        return null;
    }

    @NotNull
    @Override
    public String getScopeLabel() {
        return MaintenanceMessages.maint_scope_database;
    }

    @Override
    public boolean supportsAllScopes() {
        return false;
    }

    @NotNull
    @Override
    public MaintenanceReport readOverview(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @Nullable String scope, int limit)
        throws DBException {
        try {
            List<String> notices = new ArrayList<>();
            List<String> scopes = AdminSql.queryStrings(session, "SELECT name FROM sys.databases " //$NON-NLS-1$
                + "WHERE state_desc = 'ONLINE' AND HAS_DBACCESS(name) = 1 ORDER BY name"); //$NON-NLS-1$
            String current = AdminSql.queryString(session, "SELECT DB_NAME()"); //$NON-NLS-1$
            String database = scope != null && scopes.contains(scope) ? scope
                : current != null && scopes.contains(current) ? current : scopes.isEmpty() ? null : scopes.get(0);

            AdminGrid fragmentation = new AdminGrid("fragmentation", //$NON-NLS-1$
                NLS.bind(MaintenanceMessages.maint_grid_ms_fragmentation, database == null ? "-" : database), //$NON-NLS-1$
                SpaceColumn.text(MaintenanceMessages.maint_col_schema),
                SpaceColumn.text(MaintenanceMessages.maint_col_table),
                SpaceColumn.text(MaintenanceMessages.maint_col_index),
                SpaceColumn.text(MaintenanceMessages.maint_col_type),
                SpaceColumn.percent(MaintenanceMessages.maint_col_fragmentation),
                SpaceColumn.count(MaintenanceMessages.maint_col_pages),
                SpaceColumn.count(MaintenanceMessages.maint_col_partition),
                SpaceColumn.text(MaintenanceMessages.maint_col_suggestion));
            fragmentation.setDescription(MaintenanceMessages.maint_ms_fragmentation_desc);
            fragmentation.setEmptyText(MaintenanceMessages.maint_empty_fragmentation);
            AdminGrid statistics = new AdminGrid("statistics", //$NON-NLS-1$
                NLS.bind(MaintenanceMessages.maint_grid_ms_statistics, database == null ? "-" : database), //$NON-NLS-1$
                SpaceColumn.text(MaintenanceMessages.maint_col_schema),
                SpaceColumn.text(MaintenanceMessages.maint_col_table),
                SpaceColumn.text(MaintenanceMessages.maint_col_statistics),
                SpaceColumn.text(MaintenanceMessages.maint_col_stat_updated),
                SpaceColumn.count(MaintenanceMessages.maint_col_stat_rows),
                SpaceColumn.count(MaintenanceMessages.maint_col_modifications),
                SpaceColumn.percent(MaintenanceMessages.maint_col_modifications_pct));
            statistics.setDescription(MaintenanceMessages.maint_ms_statistics_desc);
            statistics.setEmptyText(MaintenanceMessages.maint_empty_statistics);
            if (database != null) {
                String viewState = AdminSql.queryString(session, "EXEC " + quote(database) + ".sys.sp_executesql " //$NON-NLS-1$ //$NON-NLS-2$
                    + "N'SELECT HAS_PERMS_BY_NAME(NULL, ''DATABASE'', ''VIEW DATABASE STATE'')'"); //$NON-NLS-1$
                if (!"1".equals(viewState)) { //$NON-NLS-1$
                    String reason = NLS.bind(MaintenanceMessages.maint_ms_denied_view_state, database);
                    fragmentation.setUnavailable(reason);
                    statistics.setUnavailable(reason);
                    notices.add(reason);
                } else {
                    String db = database;
                    try {
                        AdminSql.query(session, "EXEC " + quote(database) + ".sys.sp_executesql " + unicodeLiteral(FRAGMENTATION_SQL) //$NON-NLS-1$ //$NON-NLS-2$
                            + ", N'@n int', @n = ?", rs -> { //$NON-NLS-1$
                                String schema = rs.getString(1);
                                String table = rs.getString(2);
                                String index = rs.getString(3);
                                double percent = rs.getDouble(5);
                                String operation = percent > REBUILD_THRESHOLD ? OP_REBUILD : OP_REORGANIZE;
                                fragmentation.addTaggedRow(new MaintenanceAction(MaintenanceTarget.index(db, schema, table, index), operation), null,
                                    schema, table, index, rs.getString(4), percent, AdminSql.getLong(rs, 6), AdminSql.getLong(rs, 7),
                                    OP_REBUILD.equals(operation) ? "REBUILD" : "REORGANIZE"); //$NON-NLS-1$ //$NON-NLS-2$
                            }, limit);
                    } catch (SQLException e) {
                        fragmentation.setUnavailable(lockedReason(e));
                    }
                    try {
                        AdminSql.query(session, "EXEC " + quote(database) + ".sys.sp_executesql " + unicodeLiteral(STATISTICS_SQL) //$NON-NLS-1$ //$NON-NLS-2$
                            + ", N'@n int', @n = ?", rs -> { //$NON-NLS-1$
                                String schema = rs.getString(1);
                                String table = rs.getString(2);
                                Object percent = rs.getObject(7);
                                statistics.addTaggedRow(new MaintenanceAction(MaintenanceTarget.table(db, schema, table), OP_UPDATE_STATISTICS), null,
                                    schema, table, rs.getString(3), rs.getString(4), AdminSql.getLong(rs, 5), AdminSql.getLong(rs, 6),
                                    percent instanceof Number number ? number.doubleValue() : null);
                            }, limit);
                    } catch (SQLException e) {
                        statistics.setUnavailable(lockedReason(e));
                    }
                }
            }

            AdminGrid databases = new AdminGrid("checkdb", MaintenanceMessages.maint_grid_ms_checkdb, //$NON-NLS-1$
                SpaceColumn.text(MaintenanceMessages.maint_col_database),
                SpaceColumn.text(MaintenanceMessages.maint_col_state),
                SpaceColumn.text(MaintenanceMessages.maint_col_recovery),
                new SpaceColumn(MaintenanceMessages.maint_col_last_checkdb, SpaceColumn.Kind.TEXT, MaintenanceMessages.maint_col_last_checkdb_tip));
            databases.setDescription(MaintenanceMessages.maint_ms_checkdb_desc);
            AdminSql.query(session, "SELECT name, state_desc, recovery_model_desc, " //$NON-NLS-1$
                + "CONVERT(varchar(19), CAST(DATABASEPROPERTYEX(name, 'LastGoodCheckDbTime') AS datetime), 120) " //$NON-NLS-1$
                + "FROM sys.databases WHERE HAS_DBACCESS(name) = 1 AND name <> 'tempdb' " //$NON-NLS-1$
                + "ORDER BY CAST(DATABASEPROPERTYEX(name, 'LastGoodCheckDbTime') AS datetime), name", rs -> { //$NON-NLS-1$
                    String name = rs.getString(1);
                    String last = rs.getString(4);
                    databases.addTaggedRow(new MaintenanceAction(MaintenanceTarget.database(name), OP_CHECKDB), null,
                        name, rs.getString(2), rs.getString(3),
                        last == null || last.startsWith("1900") ? MaintenanceMessages.maint_value_never : last); //$NON-NLS-1$
                });
            return new MaintenanceReport(List.of(fragmentation, statistics, databases), scopes, database, notices);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    @NotNull
    @Override
    public MaintenancePlan prepare(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @NotNull MaintenanceTarget target)
        throws DBException {
        MaintenancePlan plan = new MaintenancePlan(target);
        String database = target.database() == null ? "" : target.database(); //$NON-NLS-1$
        try {
            String edition = AdminSql.queryString(session, "SELECT CAST(SERVERPROPERTY('EngineEdition') AS int)"); //$NON-NLS-1$
            boolean onlineAllowed = edition != null && ONLINE_EDITIONS.contains(Integer.parseInt(edition));
            plan.setData(new Data(onlineAllowed));
            boolean sysadmin = "1".equals(AdminSql.queryString(session, "SELECT IS_SRVROLEMEMBER('sysadmin')")); //$NON-NLS-1$ //$NON-NLS-2$
            if (target.kind() == MaintenanceTarget.Kind.DATABASE) {
                boolean owner = sysadmin || "1".equals(inDatabase(session, database, "SELECT IS_MEMBER(''db_owner'')")); //$NON-NLS-1$ //$NON-NLS-2$
                plan.addOperation(new MaintenanceOperation(OP_CHECKDB, "DBCC CHECKDB", MaintenanceMessages.maint_ms_checkdb_op_desc, List.of( //$NON-NLS-1$
                    MaintenanceOption.flag(OPT_NO_INFOMSGS, "NO_INFOMSGS", true, MaintenanceMessages.maint_ms_no_infomsgs_tip), //$NON-NLS-1$
                    MaintenanceOption.flag(OPT_PHYSICAL_ONLY, "PHYSICAL_ONLY", false, MaintenanceMessages.maint_ms_physical_only_tip)))); //$NON-NLS-1$
                plan.addOperation(new MaintenanceOperation(OP_UPDATE_ALL_STATISTICS, MaintenanceMessages.maint_ms_update_all_statistics,
                    MaintenanceMessages.maint_ms_update_all_statistics_desc, List.of()));
                String last = AdminSql.queryString(session, "SELECT CONVERT(varchar(19), " //$NON-NLS-1$
                    + "CAST(DATABASEPROPERTYEX(?, 'LastGoodCheckDbTime') AS datetime), 120)", database); //$NON-NLS-1$
                plan.addFact(NLS.bind(MaintenanceMessages.maint_ms_fact_checkdb,
                    last == null || last.startsWith("1900") ? MaintenanceMessages.maint_value_never : last)); //$NON-NLS-1$
                if (!owner) {
                    plan.deny(OP_CHECKDB, MaintenanceMessages.maint_ms_denied_owner);
                    plan.deny(OP_UPDATE_ALL_STATISTICS, MaintenanceMessages.maint_ms_denied_owner);
                }
                return plan;
            }
            String table = quote(target.schema()) + "." + quote(target.table()); //$NON-NLS-1$
            boolean alter = "1".equals(inDatabase(session, database, //$NON-NLS-1$
                "SELECT HAS_PERMS_BY_NAME(" + quoteLiteral(table).replace("'", "''") + ", ''OBJECT'', ''ALTER'')")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            List<MaintenanceOption> rebuildOptions = new ArrayList<>();
            rebuildOptions.add(onlineAllowed
                ? MaintenanceOption.flag(OPT_ONLINE, "ONLINE = ON", false, MaintenanceMessages.maint_ms_online_tip) //$NON-NLS-1$
                : MaintenanceOption.disabledFlag(OPT_ONLINE, "ONLINE = ON", MaintenanceMessages.maint_ms_online_edition)); //$NON-NLS-1$
            rebuildOptions.add(MaintenanceOption.flag(OPT_SORT_IN_TEMPDB, "SORT_IN_TEMPDB = ON", false, MaintenanceMessages.maint_ms_sort_tip)); //$NON-NLS-1$
            boolean index = target.kind() == MaintenanceTarget.Kind.INDEX;
            plan.addOperation(new MaintenanceOperation(OP_REBUILD, index ? MaintenanceMessages.maint_ms_rebuild : MaintenanceMessages.maint_ms_rebuild_all,
                MaintenanceMessages.maint_ms_rebuild_desc, rebuildOptions));
            plan.addOperation(new MaintenanceOperation(OP_REORGANIZE, index ? MaintenanceMessages.maint_ms_reorganize : MaintenanceMessages.maint_ms_reorganize_all,
                MaintenanceMessages.maint_ms_reorganize_desc, List.of(
                MaintenanceOption.flag(OPT_LOB_COMPACTION, "LOB_COMPACTION = ON", true, MaintenanceMessages.maint_ms_lob_tip)))); //$NON-NLS-1$
            plan.addOperation(new MaintenanceOperation(OP_UPDATE_STATISTICS, MaintenanceMessages.maint_ms_update_statistics,
                MaintenanceMessages.maint_ms_update_statistics_desc, List.of(
                MaintenanceOption.choice(OPT_SCAN, MaintenanceMessages.maint_opt_scan, List.of(
                    new MaintenanceOption.Choice(SCAN_DEFAULT, MaintenanceMessages.maint_ms_scan_default),
                    new MaintenanceOption.Choice(SCAN_FULL, "FULLSCAN"), //$NON-NLS-1$
                    new MaintenanceOption.Choice("10", "SAMPLE 10 PERCENT"), //$NON-NLS-1$ //$NON-NLS-2$
                    new MaintenanceOption.Choice("25", "SAMPLE 25 PERCENT"), //$NON-NLS-1$ //$NON-NLS-2$
                    new MaintenanceOption.Choice("50", "SAMPLE 50 PERCENT"), //$NON-NLS-1$ //$NON-NLS-2$
                    new MaintenanceOption.Choice(SCAN_RESAMPLE, "RESAMPLE")), //$NON-NLS-1$
                    SCAN_DEFAULT, MaintenanceMessages.maint_ms_scan_tip))));
            if (!alter) {
                String reason = NLS.bind(MaintenanceMessages.maint_ms_denied_alter, target.schema() + "." + target.table()); //$NON-NLS-1$
                plan.deny(OP_REBUILD, reason);
                plan.deny(OP_REORGANIZE, reason);
                plan.deny(OP_UPDATE_STATISTICS, reason);
            }
            readFragmentation(session, plan, database, table, index ? target.name() : null);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
        return plan;
    }

    /**
     * The current fragmentation of the target (highest of its indexes for a table) and the suggestion.
     */
    private void readFragmentation(@NotNull JDBCSession session, @NotNull MaintenancePlan plan, @NotNull String database, @NotNull String table,
                                   @Nullable String index) throws SQLException {
        String viewState = inDatabase(session, database, "SELECT HAS_PERMS_BY_NAME(NULL, ''DATABASE'', ''VIEW DATABASE STATE'')"); //$NON-NLS-1$
        if (!"1".equals(viewState)) { //$NON-NLS-1$
            plan.addFact(NLS.bind(MaintenanceMessages.maint_ms_denied_view_state, database));
            return;
        }
        String sql = LOCK_TIMEOUT + "SELECT MAX(ps.avg_fragmentation_in_percent), SUM(ps.page_count), " //$NON-NLS-1$
            + "CONVERT(varchar(19), MIN(STATS_DATE(i.object_id, i.index_id)), 120) " //$NON-NLS-1$
            + "FROM sys.indexes i CROSS APPLY sys.dm_db_index_physical_stats(DB_ID(), i.object_id, i.index_id, NULL, ''LIMITED'') ps " //$NON-NLS-1$
            + "WHERE i.object_id = OBJECT_ID(" + quoteLiteral(table).replace("'", "''") + ") AND i.index_id > 0 " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + "AND ps.alloc_unit_type_desc = ''IN_ROW_DATA''" //$NON-NLS-1$
            + (index != null ? " AND i.name = " + quoteLiteral(index).replace("'", "''") : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Object[] state = new Object[3];
        try {
            AdminSql.query(session, "EXEC " + quote(database) + ".sys.sp_executesql N'" + sql + "'", rs -> { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Object percent = rs.getObject(1);
                state[0] = percent instanceof Number number ? number.doubleValue() : null;
                state[1] = AdminSql.getLong(rs, 2);
                state[2] = rs.getString(3);
            });
        } catch (SQLException e) {
            // A long transaction or rebuild locks the table: open the dialog without the current state
            plan.addFact(lockedReason(e));
            return;
        }
        if (state[0] instanceof Double percent) {
            String suggestion = percent > REBUILD_THRESHOLD ? "REBUILD" //$NON-NLS-1$
                : percent >= REORGANIZE_THRESHOLD ? "REORGANIZE" : MaintenanceMessages.maint_ms_suggest_none; //$NON-NLS-1$
            plan.addFact(NLS.bind(MaintenanceMessages.maint_ms_fact_fragmentation, new Object[] {
                String.format("%.1f", percent), state[1], suggestion})); //$NON-NLS-1$
            plan.setSuggestedOperation(percent > REBUILD_THRESHOLD ? OP_REBUILD : percent >= REORGANIZE_THRESHOLD ? OP_REORGANIZE : OP_UPDATE_STATISTICS);
        }
        plan.addFact(NLS.bind(MaintenanceMessages.maint_ms_fact_statistics,
            state[2] == null ? MaintenanceMessages.maint_value_never : state[2]));
    }

    /**
     * Runs a one-value query inside a database without changing the session's database. The query is put into an N'' literal:
     * single quotes in it must already be doubled.
     */
    @Nullable
    private static String inDatabase(@NotNull JDBCSession session, @NotNull String database, @NotNull String quotedQuery) {
        try {
            return AdminSql.queryString(session, "EXEC " + quote(database) + ".sys.sp_executesql N'" + quotedQuery + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } catch (SQLException e) {
            log.debug("Error reading in database " + database, e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * The reason shown for a list that waited too long for a lock.
     *
     * @throws SQLException any other error
     */
    @NotNull
    private static String lockedReason(@NotNull SQLException e) throws SQLException {
        if (e.getErrorCode() != LOCK_TIMEOUT_ERROR) {
            throw e;
        }
        return MaintenanceMessages.maint_ms_locked;
    }

    @NotNull
    @Override
    public MaintenanceCommand buildCommand(@NotNull MaintenancePlan plan, @NotNull String operationId, @NotNull Map<String, Object> values) {
        MaintenanceTarget target = plan.getTarget();
        String database = quote(target.database());
        List<String> statements = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String table = database + "." + quote(target.schema()) + "." + quote(target.table()); //$NON-NLS-1$ //$NON-NLS-2$
        String index = target.kind() == MaintenanceTarget.Kind.INDEX ? quote(target.name()) : "ALL"; //$NON-NLS-1$
        switch (operationId) {
            case OP_REBUILD -> {
                List<String> options = new ArrayList<>();
                boolean online = flag(plan, operationId, values, OPT_ONLINE);
                if (online) {
                    options.add("ONLINE = ON"); //$NON-NLS-1$
                }
                if (flag(plan, operationId, values, OPT_SORT_IN_TEMPDB)) {
                    options.add("SORT_IN_TEMPDB = ON"); //$NON-NLS-1$
                }
                statements.add("ALTER INDEX " + index + " ON " + table + " REBUILD" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + (options.isEmpty() ? "" : " WITH (" + String.join(", ", options) + ")")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                if (!online) {
                    warnings.add(MaintenanceMessages.maint_ms_warn_offline);
                }
            }
            case OP_REORGANIZE -> statements.add("ALTER INDEX " + index + " ON " + table + " REORGANIZE" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (flag(plan, operationId, values, OPT_LOB_COMPACTION) ? "" : " WITH (LOB_COMPACTION = OFF)")); //$NON-NLS-1$ //$NON-NLS-2$
            case OP_UPDATE_STATISTICS -> {
                String scan = choice(plan, operationId, values, OPT_SCAN);
                String with = switch (scan) {
                    case SCAN_DEFAULT -> ""; //$NON-NLS-1$
                    case SCAN_FULL -> " WITH FULLSCAN"; //$NON-NLS-1$
                    case SCAN_RESAMPLE -> " WITH RESAMPLE"; //$NON-NLS-1$
                    default -> " WITH SAMPLE " + scan + " PERCENT"; //$NON-NLS-1$ //$NON-NLS-2$
                };
                statements.add("UPDATE STATISTICS " + table + (target.kind() == MaintenanceTarget.Kind.INDEX ? " " + index : "") + with); //$NON-NLS-1$ //$NON-NLS-2$
                if (SCAN_FULL.equals(scan)) {
                    warnings.add(MaintenanceMessages.maint_ms_warn_fullscan);
                }
            }
            case OP_CHECKDB -> {
                List<String> options = new ArrayList<>();
                if (flag(plan, operationId, values, OPT_NO_INFOMSGS)) {
                    options.add("NO_INFOMSGS"); //$NON-NLS-1$
                }
                if (flag(plan, operationId, values, OPT_PHYSICAL_ONLY)) {
                    options.add("PHYSICAL_ONLY"); //$NON-NLS-1$
                }
                statements.add("DBCC CHECKDB (" + quoteLiteral(target.name()) + ")" //$NON-NLS-1$ //$NON-NLS-2$
                    + (options.isEmpty() ? "" : " WITH " + String.join(", ", options))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                warnings.add(MaintenanceMessages.maint_ms_warn_checkdb);
            }
            case OP_UPDATE_ALL_STATISTICS -> statements.add("EXEC " + database + ".sys.sp_updatestats"); //$NON-NLS-1$ //$NON-NLS-2$
            default -> {
            }
        }
        return MaintenanceCommand.of(statements, ";", warnings); //$NON-NLS-1$
    }

    @NotNull
    private static String quote(@Nullable String name) {
        return "[" + (name == null ? "" : name.replace("]", "]]")) + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    /**
     * An N'' literal.
     */
    @NotNull
    private static String quoteLiteral(@Nullable String value) {
        return "N'" + (value == null ? "" : value.replace("'", "''")) + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @NotNull
    private static String unicodeLiteral(@NotNull String sql) {
        return "N'" + sql.replace("'", "''") + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
