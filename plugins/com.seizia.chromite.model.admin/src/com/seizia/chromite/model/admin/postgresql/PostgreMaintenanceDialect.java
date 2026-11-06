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
package com.seizia.chromite.model.admin.postgresql;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.model.PostgreIndex;
import com.seizia.chromite.ext.postgresql.model.PostgreMaterializedView;
import com.seizia.chromite.ext.postgresql.model.PostgreSchema;
import com.seizia.chromite.ext.postgresql.model.PostgreTable;
import com.seizia.chromite.ext.postgresql.model.PostgreTableBase;
import com.seizia.chromite.ext.postgresql.model.PostgreTableForeign;
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
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 유지 관리: VACUUM(FULL, FREEZE, ANALYZE, VERBOSE), ANALYZE, REINDEX(TABLE, INDEX, SCHEMA, DATABASE, CONCURRENTLY 12+), CLUSTER와
 * 유지 관리 탭의 자동 VACUUM 상태·부풀림 추정.
 *
 * 설계:
 * <ul>
 *     <li>문장 모양은 DBeaver {@code PostgreToolBaseVacuum}(괄호 옵션 목록, 자동 커밋 실행)을 따른다. 옵션 이름이 하나도 없으면 괄호를 쓰지 않는다.</li>
 *     <li>권한: VACUUM·ANALYZE·REINDEX·CLUSTER는 테이블 소유자(소유 역할의 구성원)나 superuser만 할 수 있고, 17부터는 MAINTAIN 권한
 *     (pg_maintain 역할 포함)도 된다. VACUUM·ANALYZE는 16까지 데이터베이스 소유자도 된다. 권한이 없는 테이블에 문장을 보내면 서버가 오류 없이
 *     경고만 하고 건너뛰므로("skipping"), 미리 확인해 실행을 끄고 이유를 보인다. 스키마 대상은 할 수 있는 테이블만 문장으로 만든다.</li>
 *     <li>스키마 대상의 VACUUM·ANALYZE는 테이블마다 문장 하나다(여러 테이블을 한 문장에 넣는 것은 11+이고, 문장 사이에서 취소를 볼 수 있게).</li>
 *     <li>부풀림은 추정치다: 플래너 통계({@code pg_stats}의 열 평균 너비와 NULL 비율, {@code pg_class.reltuples})로 행 크기와 필요한 페이지 수를
 *     계산해 실제 페이지 수와 비교한다(check_postgres, pgsql-bloat-estimation에 널리 쓰이는 방법을 이 코드에서 다시 썼다). 정확한 값은
 *     pgstattuple 확장이 필요하다. 모든 열의 통계를 읽을 수 있는 테이블만 계산한다(pg_stats는 SELECT 권한이 있는 열만 보인다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
public class PostgreMaintenanceDialect extends MaintenanceDialect {

    public static final String OP_VACUUM = "vacuum"; //$NON-NLS-1$
    public static final String OP_ANALYZE = "analyze"; //$NON-NLS-1$
    public static final String OP_REINDEX = "reindex"; //$NON-NLS-1$
    public static final String OP_CLUSTER = "cluster"; //$NON-NLS-1$

    private static final String OPT_FULL = "full"; //$NON-NLS-1$
    private static final String OPT_FREEZE = "freeze"; //$NON-NLS-1$
    private static final String OPT_ANALYZE = "analyze"; //$NON-NLS-1$
    private static final String OPT_VERBOSE = "verbose"; //$NON-NLS-1$
    private static final String OPT_CONCURRENTLY = "concurrently"; //$NON-NLS-1$
    private static final String OPT_INDEX = "index"; //$NON-NLS-1$

    /**
     * Dead rows above this share of the live rows make VACUUM the suggestion.
     */
    private static final double DEAD_RATIO_SUGGESTION = 0.1;

    private static final String VACUUM_SQL = "SELECT s.schemaname, s.relname, s.n_live_tup, s.n_dead_tup, " //$NON-NLS-1$
        + "CASE WHEN s.n_live_tup + s.n_dead_tup > 0 THEN 100.0 * s.n_dead_tup / (s.n_live_tup + s.n_dead_tup) END, " //$NON-NLS-1$
        + "s.n_mod_since_analyze, " //$NON-NLS-1$
        + "to_char(s.last_vacuum, 'YYYY-MM-DD HH24:MI:SS'), to_char(s.last_autovacuum, 'YYYY-MM-DD HH24:MI:SS'), " //$NON-NLS-1$
        + "to_char(s.last_analyze, 'YYYY-MM-DD HH24:MI:SS'), to_char(s.last_autoanalyze, 'YYYY-MM-DD HH24:MI:SS'), " //$NON-NLS-1$
        // Autovacuum's own trigger: threshold + scale factor x rows (server settings; per table settings are not read)
        + "s.n_dead_tup > current_setting('autovacuum_vacuum_threshold')::numeric " //$NON-NLS-1$
        + "+ current_setting('autovacuum_vacuum_scale_factor')::numeric * GREATEST(c.reltuples, 0) " //$NON-NLS-1$
        + "FROM pg_stat_user_tables s JOIN pg_class c ON c.oid = s.relid "; //$NON-NLS-1$

    private static final String BLOAT_SQL = "WITH cols AS (" //$NON-NLS-1$
        + "SELECT schemaname, tablename, SUM((1 - COALESCE(null_frac, 0)) * COALESCE(avg_width, 0)) AS width, " //$NON-NLS-1$
        + "MAX(COALESCE(null_frac, 0)) AS max_null, COUNT(*) AS stat_cols FROM pg_stats GROUP BY schemaname, tablename), " //$NON-NLS-1$
        + "t AS (SELECT n.nspname, c.relname, c.relpages, c.reltuples, cols.width, cols.max_null, cols.stat_cols, " //$NON-NLS-1$
        + "(SELECT COUNT(*) FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped) AS ncols, " //$NON-NLS-1$
        + "COALESCE(substring(array_to_string(c.reloptions, ' ') FROM 'fillfactor=([0-9]+)')::numeric, 100) AS fill, " //$NON-NLS-1$
        + "current_setting('block_size')::numeric AS bs " //$NON-NLS-1$
        + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " //$NON-NLS-1$
        + "JOIN cols ON cols.schemaname = n.nspname AND cols.tablename = c.relname " //$NON-NLS-1$
        + "WHERE c.relkind = 'r' AND c.relpages > 0 AND c.reltuples > 0 " //$NON-NLS-1$
        + "AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\\_toast%%'%s), " //$NON-NLS-1$
        // Row: 23 byte header + null bitmap when a column has nulls, 8 byte aligned; data 8 byte aligned; 4 byte line pointer
        + "e AS (SELECT nspname, relname, relpages, reltuples, bs, CEIL(reltuples * (" //$NON-NLS-1$
        + "CEIL((23 + CASE WHEN max_null > 0 THEN (ncols + 7) / 8 ELSE 0 END) / 8.0) * 8 + CEIL(width / 8.0) * 8 + 4" //$NON-NLS-1$
        + ") / ((bs - 24) * fill / 100)) AS expected FROM t WHERE stat_cols = ncols) " //$NON-NLS-1$
        + "SELECT nspname, relname, (relpages * bs)::bigint, reltuples::bigint, (GREATEST(relpages - expected, 0) * bs)::bigint, " //$NON-NLS-1$
        + "100.0 * GREATEST(relpages - expected, 0) / relpages FROM e WHERE relpages > expected " //$NON-NLS-1$
        + "ORDER BY 5 DESC LIMIT ?"; //$NON-NLS-1$

    @NotNull
    private final PostgreDataSource dataSource;

    public PostgreMaintenanceDialect(@NotNull PostgreDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * What prepare read for the statements.
     *
     * @param tables tables of a schema target this login may maintain (schema-qualified, quoted), empty otherwise
     */
    private record Data(@NotNull List<String> tables) {
    }

    @Nullable
    @Override
    public MaintenanceTarget getTarget(@NotNull DBSObject object) {
        if (object instanceof PostgreTableForeign) {
            return null;
        }
        if ((object instanceof PostgreTable || object instanceof PostgreMaterializedView) && object instanceof PostgreTableBase table) {
            return MaintenanceTarget.table(table.getDatabase().getName(), table.getSchema().getName(), table.getName());
        }
        if (object instanceof PostgreIndex index && index.getTable() != null) {
            PostgreTableBase table = index.getTable();
            return MaintenanceTarget.index(table.getDatabase().getName(), table.getSchema().getName(), table.getName(), index.getName());
        }
        if (object instanceof PostgreSchema schema) {
            return MaintenanceTarget.schema(schema.getDatabase().getName(), schema.getName());
        }
        if (object instanceof PostgreDatabase database) {
            return MaintenanceTarget.database(database.getName());
        }
        return null;
    }

    @NotNull
    @Override
    public String getScopeLabel() {
        return MaintenanceMessages.maint_scope_schema;
    }

    @NotNull
    @Override
    public MaintenanceReport readOverview(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @Nullable String scope, int limit)
        throws DBException {
        try {
            String database = AdminSql.queryString(session, "SELECT current_database()"); //$NON-NLS-1$
            List<String> scopes = AdminSql.queryStrings(session, "SELECT nspname FROM pg_namespace " //$NON-NLS-1$
                + "WHERE nspname NOT LIKE 'pg\\_toast%' AND nspname NOT LIKE 'pg\\_temp\\_%' ORDER BY nspname"); //$NON-NLS-1$
            String listScope = scope != null && scopes.contains(scope) ? scope : null;

            AdminGrid vacuum = new AdminGrid("vacuum", NLS.bind(MaintenanceMessages.maint_grid_pg_vacuum, database), //$NON-NLS-1$
                SpaceColumn.text(MaintenanceMessages.maint_col_schema),
                SpaceColumn.text(MaintenanceMessages.maint_col_table),
                SpaceColumn.count(MaintenanceMessages.maint_col_live_rows),
                SpaceColumn.count(MaintenanceMessages.maint_col_dead_rows),
                SpaceColumn.percent(MaintenanceMessages.maint_col_dead_pct),
                new SpaceColumn(MaintenanceMessages.maint_col_mod_since_analyze, SpaceColumn.Kind.COUNT, MaintenanceMessages.maint_col_mod_since_analyze_tip),
                SpaceColumn.text(MaintenanceMessages.maint_col_last_vacuum),
                SpaceColumn.text(MaintenanceMessages.maint_col_last_autovacuum),
                SpaceColumn.text(MaintenanceMessages.maint_col_last_analyze),
                SpaceColumn.text(MaintenanceMessages.maint_col_last_autoanalyze),
                new SpaceColumn(MaintenanceMessages.maint_col_autovacuum_due, SpaceColumn.Kind.TEXT, MaintenanceMessages.maint_col_autovacuum_due_tip),
                SpaceColumn.text(MaintenanceMessages.maint_col_suggestion));
            String autovacuum = AdminSql.queryString(session, "SELECT current_setting('autovacuum') || ' / ' || " //$NON-NLS-1$
                + "current_setting('autovacuum_vacuum_threshold') || ' / ' || current_setting('autovacuum_vacuum_scale_factor')"); //$NON-NLS-1$
            vacuum.setDescription(NLS.bind(MaintenanceMessages.maint_pg_vacuum_desc, autovacuum));
            vacuum.setEmptyText(MaintenanceMessages.maint_empty_tables);
            String vacuumSql = VACUUM_SQL + (listScope != null ? "WHERE s.schemaname = ? " : "") //$NON-NLS-1$ //$NON-NLS-2$
                + "ORDER BY s.n_dead_tup DESC, s.n_mod_since_analyze DESC NULLS LAST LIMIT ?"; //$NON-NLS-1$
            AdminSql.RowReader vacuumReader = rs -> {
                String schema = rs.getString(1);
                String table = rs.getString(2);
                Long live = AdminSql.getLong(rs, 3);
                Long dead = AdminSql.getLong(rs, 4);
                Long modified = AdminSql.getLong(rs, 6);
                String operation = suggestOperation(live, dead, modified);
                vacuum.addTaggedRow(new MaintenanceAction(MaintenanceTarget.table(database, schema, table), operation), null,
                    schema, table, live, dead, percentValue(rs.getObject(5)), modified,
                    rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                    rs.getBoolean(11) ? MaintenanceMessages.maint_value_yes : MaintenanceMessages.maint_value_no,
                    OP_ANALYZE.equals(operation) ? "ANALYZE" : "VACUUM"); //$NON-NLS-1$ //$NON-NLS-2$
            };
            if (listScope != null) {
                AdminSql.query(session, vacuumSql, vacuumReader, listScope, limit);
            } else {
                AdminSql.query(session, vacuumSql, vacuumReader, limit);
            }

            AdminGrid bloat = new AdminGrid("bloat", MaintenanceMessages.maint_grid_pg_bloat, //$NON-NLS-1$
                SpaceColumn.text(MaintenanceMessages.maint_col_schema),
                SpaceColumn.text(MaintenanceMessages.maint_col_table),
                SpaceColumn.bytes(MaintenanceMessages.maint_col_size),
                SpaceColumn.count(MaintenanceMessages.maint_col_rows_est),
                new SpaceColumn(MaintenanceMessages.maint_col_bloat, SpaceColumn.Kind.BYTES, MaintenanceMessages.maint_col_bloat_tip),
                new SpaceColumn(MaintenanceMessages.maint_col_bloat_pct, SpaceColumn.Kind.PERCENT, MaintenanceMessages.maint_col_bloat_tip));
            bloat.setDescription(MaintenanceMessages.maint_pg_bloat_desc);
            bloat.setEmptyText(MaintenanceMessages.maint_empty_bloat);
            String bloatSql = String.format(BLOAT_SQL, listScope != null ? " AND n.nspname = ?" : ""); //$NON-NLS-1$ //$NON-NLS-2$
            AdminSql.RowReader bloatReader = rs -> {
                String schema = rs.getString(1);
                String table = rs.getString(2);
                bloat.addTaggedRow(new MaintenanceAction(MaintenanceTarget.table(database, schema, table), OP_VACUUM), null,
                    schema, table, AdminSql.getLong(rs, 3), AdminSql.getLong(rs, 4), AdminSql.getLong(rs, 5), percentValue(rs.getObject(6)));
            };
            if (listScope != null) {
                AdminSql.query(session, bloatSql, bloatReader, listScope, limit);
            } else {
                AdminSql.query(session, bloatSql, bloatReader, limit);
            }
            return new MaintenanceReport(List.of(vacuum, bloat), scopes, listScope, List.of());
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    @NotNull
    private static String suggestOperation(@Nullable Long live, @Nullable Long dead, @Nullable Long modified) {
        long liveRows = live == null ? 0 : live;
        long deadRows = dead == null ? 0 : dead;
        long modifiedRows = modified == null ? 0 : modified;
        if (deadRows > 0 && deadRows >= DEAD_RATIO_SUGGESTION * Math.max(liveRows, 1)) {
            return OP_VACUUM;
        }
        return modifiedRows > deadRows ? OP_ANALYZE : OP_VACUUM;
    }

    @Nullable
    private static Double percentValue(@Nullable Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    @NotNull
    @Override
    public MaintenancePlan prepare(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @NotNull MaintenanceTarget target)
        throws DBException {
        MaintenancePlan plan = new MaintenancePlan(target);
        boolean v12 = dataSource.isServerVersionAtLeast(12, 0);
        boolean v17 = dataSource.isServerVersionAtLeast(17, 0);
        List<String> tables = new ArrayList<>();
        try {
            String database = AdminSql.queryString(session, "SELECT current_database()"); //$NON-NLS-1$
            boolean superuser = "t".equals(AdminSql.queryString(session, //$NON-NLS-1$
                "SELECT CASE WHEN rolsuper THEN 't' ELSE 'f' END FROM pg_roles WHERE rolname = current_user")); //$NON-NLS-1$
            boolean databaseOwner = "t".equals(AdminSql.queryString(session, //$NON-NLS-1$
                "SELECT CASE WHEN pg_has_role(datdba, 'USAGE') THEN 't' ELSE 'f' END FROM pg_database WHERE datname = current_database()")); //$NON-NLS-1$
            boolean maintainRole = v17 && "t".equals(AdminSql.queryString(session, //$NON-NLS-1$
                "SELECT CASE WHEN pg_has_role('pg_maintain', 'USAGE') THEN 't' ELSE 'f' END")); //$NON-NLS-1$
            String deniedTable = v17 ? MaintenanceMessages.maint_pg_denied_table_17 : MaintenanceMessages.maint_pg_denied_table;
            if (target.database() != null && !target.database().equals(database)) {
                // Statements apply to the connected database only
                throw new DBException(NLS.bind(MaintenanceMessages.maint_pg_other_database, target.database(), database));
            }
            switch (target.kind()) {
                case TABLE, INDEX -> {
                    String table = target.table();
                    String[] state = new String[11];
                    // Owner, MAINTAIN (17+), statistics of the table
                    AdminSql.query(session, "SELECT CASE WHEN pg_has_role(c.relowner, 'USAGE') THEN 't' ELSE 'f' END, " //$NON-NLS-1$
                        + (v17 ? "CASE WHEN has_table_privilege(c.oid, 'MAINTAIN') THEN 't' ELSE 'f' END, " : "'f', ") //$NON-NLS-1$ //$NON-NLS-2$
                        + "s.n_live_tup, s.n_dead_tup, s.n_mod_since_analyze, " //$NON-NLS-1$
                        + "to_char(s.last_vacuum, 'YYYY-MM-DD HH24:MI:SS'), to_char(s.last_autovacuum, 'YYYY-MM-DD HH24:MI:SS'), " //$NON-NLS-1$
                        + "to_char(s.last_analyze, 'YYYY-MM-DD HH24:MI:SS'), to_char(s.last_autoanalyze, 'YYYY-MM-DD HH24:MI:SS'), " //$NON-NLS-1$
                        + "c.oid::text, pg_size_pretty(pg_total_relation_size(c.oid)) " //$NON-NLS-1$
                        + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace LEFT JOIN pg_stat_all_tables s ON s.relid = c.oid " //$NON-NLS-1$
                        + "WHERE n.nspname = ? AND c.relname = ?", rs -> { //$NON-NLS-1$
                            for (int i = 0; i < state.length; i++) {
                                state[i] = rs.getString(i + 1);
                            }
                        }, target.schema(), table);
                    if (state[9] == null) {
                        throw new DBException(NLS.bind(MaintenanceMessages.maint_not_found, target.getDisplayName()));
                    }
                    boolean maintain = superuser || "t".equals(state[0]) || "t".equals(state[1]); //$NON-NLS-1$ //$NON-NLS-2$
                    plan.addFact(NLS.bind(MaintenanceMessages.maint_pg_fact_rows, new Object[] {
                        dash(state[2]), dash(state[3]), dash(state[4]), state[10]}));
                    plan.addFact(NLS.bind(MaintenanceMessages.maint_pg_fact_times, new Object[] {
                        never(state[5]), never(state[6]), never(state[7]), never(state[8])}));
                    List<MaintenanceOption.Choice> indexes = new ArrayList<>();
                    if (target.kind() == MaintenanceTarget.Kind.TABLE) {
                        indexes.add(new MaintenanceOption.Choice("", MaintenanceMessages.maint_pg_cluster_previous)); //$NON-NLS-1$
                    }
                    String clustered = null;
                    List<String[]> indexRows = new ArrayList<>();
                    AdminSql.query(session, "SELECT i.relname, CASE WHEN x.indisclustered THEN 't' ELSE 'f' END FROM pg_index x " //$NON-NLS-1$
                        + "JOIN pg_class i ON i.oid = x.indexrelid WHERE x.indrelid = ?::oid ORDER BY i.relname", //$NON-NLS-1$
                        rs -> indexRows.add(new String[] {rs.getString(1), rs.getString(2)}), state[9]);
                    for (String[] row : indexRows) {
                        if (target.kind() == MaintenanceTarget.Kind.TABLE || row[0].equals(target.name())) {
                            indexes.add(new MaintenanceOption.Choice(row[0], row[0]));
                        }
                        if ("t".equals(row[1])) { //$NON-NLS-1$
                            clustered = row[0];
                        }
                    }
                    if (clustered != null) {
                        plan.addFact(NLS.bind(MaintenanceMessages.maint_pg_fact_clustered, clustered));
                    }
                    if (target.kind() == MaintenanceTarget.Kind.TABLE) {
                        plan.addOperation(vacuumOperation());
                        plan.addOperation(analyzeOperation());
                    }
                    plan.addOperation(reindexOperation(v12));
                    if (!indexes.isEmpty()) {
                        plan.addOperation(new MaintenanceOperation(OP_CLUSTER, "CLUSTER", MaintenanceMessages.maint_pg_cluster_desc, List.of( //$NON-NLS-1$
                            MaintenanceOption.choice(OPT_INDEX, MaintenanceMessages.maint_opt_index, indexes,
                                target.kind() == MaintenanceTarget.Kind.INDEX ? target.name() : clustered != null ? clustered : "", //$NON-NLS-1$
                                MaintenanceMessages.maint_pg_cluster_index_tip),
                            MaintenanceOption.flag(OPT_VERBOSE, "VERBOSE", true, MaintenanceMessages.maint_opt_verbose_tip)))); //$NON-NLS-1$
                    }
                    if (target.kind() == MaintenanceTarget.Kind.TABLE) {
                        Long live = parseLong(state[2]);
                        Long dead = parseLong(state[3]);
                        Long modified = parseLong(state[4]);
                        plan.setSuggestedOperation(suggestOperation(live, dead, modified));
                    }
                    if (!maintain) {
                        for (MaintenanceOperation operation : plan.getOperations()) {
                            boolean byDatabaseOwner = !v17 && databaseOwner && (OP_VACUUM.equals(operation.id()) || OP_ANALYZE.equals(operation.id()));
                            if (!byDatabaseOwner) {
                                plan.deny(operation.id(), deniedTable);
                            }
                        }
                    }
                }
                case SCHEMA -> {
                    int[] counts = {0};
                    AdminSql.query(session, "SELECT quote_ident(n.nspname) || '.' || quote_ident(c.relname), " //$NON-NLS-1$
                        + "CASE WHEN pg_has_role(c.relowner, 'USAGE')" + (v17 ? " OR has_table_privilege(c.oid, 'MAINTAIN')" : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + " THEN 't' ELSE 'f' END FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " //$NON-NLS-1$
                        + "WHERE n.nspname = ? AND c.relkind IN ('r', 'm') ORDER BY c.relname", rs -> { //$NON-NLS-1$
                            counts[0]++;
                            if (superuser || (!v17 && databaseOwner) || "t".equals(rs.getString(2))) { //$NON-NLS-1$
                                tables.add(rs.getString(1));
                            }
                        }, target.schema());
                    boolean schemaOwner = "t".equals(AdminSql.queryString(session, //$NON-NLS-1$
                        "SELECT CASE WHEN pg_has_role(nspowner, 'USAGE') THEN 't' ELSE 'f' END FROM pg_namespace WHERE nspname = ?", target.schema())); //$NON-NLS-1$
                    plan.addFact(NLS.bind(MaintenanceMessages.maint_pg_fact_schema_tables, tables.size(), counts[0]));
                    plan.addOperation(vacuumOperation());
                    plan.addOperation(analyzeOperation());
                    if (dataSource.isServerVersionAtLeast(9, 5)) {
                        plan.addOperation(reindexOperation(v12));
                        if (!superuser && !schemaOwner && !maintainRole) {
                            plan.deny(OP_REINDEX, MaintenanceMessages.maint_pg_denied_schema);
                        }
                    }
                    if (tables.isEmpty()) {
                        String reason = counts[0] == 0 ? MaintenanceMessages.maint_pg_schema_empty : deniedTable;
                        plan.deny(OP_VACUUM, reason);
                        plan.deny(OP_ANALYZE, reason);
                    }
                }
                case DATABASE -> {
                    plan.addOperation(vacuumOperation());
                    plan.addOperation(analyzeOperation());
                    plan.addOperation(reindexOperation(v12));
                    plan.addOperation(new MaintenanceOperation(OP_CLUSTER, "CLUSTER", MaintenanceMessages.maint_pg_cluster_database_desc, List.of( //$NON-NLS-1$
                        MaintenanceOption.flag(OPT_VERBOSE, "VERBOSE", true, MaintenanceMessages.maint_opt_verbose_tip)))); //$NON-NLS-1$
                    if (!superuser && !databaseOwner) {
                        plan.addFact(MaintenanceMessages.maint_pg_fact_database_skips);
                        if (!maintainRole) {
                            plan.deny(OP_REINDEX, MaintenanceMessages.maint_pg_denied_database);
                        }
                    }
                }
                default -> {
                }
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
        plan.setData(new Data(List.copyOf(tables)));
        return plan;
    }

    @NotNull
    private static List<String> tablesOf(@NotNull MaintenancePlan plan) {
        return plan.getData() instanceof Data data ? data.tables() : List.of();
    }

    @NotNull
    private static MaintenanceOperation vacuumOperation() {
        return new MaintenanceOperation(OP_VACUUM, "VACUUM", MaintenanceMessages.maint_pg_vacuum_op_desc, List.of( //$NON-NLS-1$
            MaintenanceOption.flag(OPT_FULL, "FULL", false, MaintenanceMessages.maint_pg_full_tip), //$NON-NLS-1$
            MaintenanceOption.flag(OPT_FREEZE, "FREEZE", false, MaintenanceMessages.maint_pg_freeze_tip), //$NON-NLS-1$
            MaintenanceOption.flag(OPT_ANALYZE, "ANALYZE", true, MaintenanceMessages.maint_pg_vacuum_analyze_tip), //$NON-NLS-1$
            MaintenanceOption.flag(OPT_VERBOSE, "VERBOSE", true, MaintenanceMessages.maint_opt_verbose_tip))); //$NON-NLS-1$
    }

    @NotNull
    private static MaintenanceOperation analyzeOperation() {
        return new MaintenanceOperation(OP_ANALYZE, "ANALYZE", MaintenanceMessages.maint_pg_analyze_op_desc, List.of( //$NON-NLS-1$
            MaintenanceOption.flag(OPT_VERBOSE, "VERBOSE", true, MaintenanceMessages.maint_opt_verbose_tip))); //$NON-NLS-1$
    }

    @NotNull
    private static MaintenanceOperation reindexOperation(boolean concurrently) {
        return new MaintenanceOperation(OP_REINDEX, "REINDEX", MaintenanceMessages.maint_pg_reindex_op_desc, List.of( //$NON-NLS-1$
            concurrently
                ? MaintenanceOption.flag(OPT_CONCURRENTLY, "CONCURRENTLY", false, MaintenanceMessages.maint_pg_concurrently_tip) //$NON-NLS-1$
                : MaintenanceOption.disabledFlag(OPT_CONCURRENTLY, "CONCURRENTLY", MaintenanceMessages.maint_pg_concurrently_version), //$NON-NLS-1$
            MaintenanceOption.flag(OPT_VERBOSE, "VERBOSE", true, MaintenanceMessages.maint_opt_verbose_tip))); //$NON-NLS-1$
    }

    @NotNull
    @Override
    public MaintenanceCommand buildCommand(@NotNull MaintenancePlan plan, @NotNull String operationId, @NotNull Map<String, Object> values) {
        MaintenanceTarget target = plan.getTarget();
        boolean verbose = flag(plan, operationId, values, OPT_VERBOSE);
        List<String> statements = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> tables = target.kind() == MaintenanceTarget.Kind.SCHEMA ? tablesOf(plan)
            : target.kind() == MaintenanceTarget.Kind.DATABASE ? List.of("") //$NON-NLS-1$
            : List.of(qualified(target.schema(), target.table()));
        switch (operationId) {
            case OP_VACUUM -> {
                List<String> options = new ArrayList<>();
                if (flag(plan, operationId, values, OPT_FULL)) {
                    options.add("FULL"); //$NON-NLS-1$
                    warnings.add(MaintenanceMessages.maint_pg_warn_full);
                }
                if (flag(plan, operationId, values, OPT_FREEZE)) {
                    options.add("FREEZE"); //$NON-NLS-1$
                }
                if (verbose) {
                    options.add("VERBOSE"); //$NON-NLS-1$
                }
                if (flag(plan, operationId, values, OPT_ANALYZE)) {
                    options.add("ANALYZE"); //$NON-NLS-1$
                }
                String prefix = options.isEmpty() ? "VACUUM" : "VACUUM (" + String.join(", ", options) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                for (String table : tables) {
                    statements.add(table.isEmpty() ? prefix : prefix + " " + table); //$NON-NLS-1$
                }
            }
            case OP_ANALYZE -> {
                String prefix = verbose ? "ANALYZE VERBOSE" : "ANALYZE"; //$NON-NLS-1$ //$NON-NLS-2$
                for (String table : tables) {
                    statements.add(table.isEmpty() ? prefix : prefix + " " + table); //$NON-NLS-1$
                }
            }
            case OP_REINDEX -> {
                boolean concurrently = flag(plan, operationId, values, OPT_CONCURRENTLY);
                // REINDEX (VERBOSE) is 9.5+, the same version as REINDEX SCHEMA
                String prefix = verbose && dataSource.isServerVersionAtLeast(9, 5) ? "REINDEX (VERBOSE) " : "REINDEX "; //$NON-NLS-1$ //$NON-NLS-2$
                String what = switch (target.kind()) {
                    case INDEX -> "INDEX "; //$NON-NLS-1$
                    case SCHEMA -> "SCHEMA "; //$NON-NLS-1$
                    case DATABASE -> "DATABASE "; //$NON-NLS-1$
                    default -> "TABLE "; //$NON-NLS-1$
                };
                String name = switch (target.kind()) {
                    case INDEX -> qualified(target.schema(), target.name());
                    case SCHEMA, DATABASE -> quote(target.name());
                    default -> qualified(target.schema(), target.table());
                };
                statements.add(prefix + what + (concurrently ? "CONCURRENTLY " : "") + name); //$NON-NLS-1$ //$NON-NLS-2$
                warnings.add(concurrently ? MaintenanceMessages.maint_pg_warn_concurrently : MaintenanceMessages.maint_pg_warn_reindex);
            }
            case OP_CLUSTER -> {
                String prefix = verbose ? "CLUSTER VERBOSE" : "CLUSTER"; //$NON-NLS-1$ //$NON-NLS-2$
                if (target.kind() == MaintenanceTarget.Kind.DATABASE) {
                    statements.add(prefix);
                } else {
                    String index = choice(plan, operationId, values, OPT_INDEX);
                    statements.add(prefix + " " + qualified(target.schema(), target.table()) //$NON-NLS-1$
                        + (index.isEmpty() ? "" : " USING " + quote(index))); //$NON-NLS-1$ //$NON-NLS-2$
                }
                warnings.add(MaintenanceMessages.maint_pg_warn_cluster);
            }
            default -> {
            }
        }
        return MaintenanceCommand.of(statements, ";", warnings); //$NON-NLS-1$
    }

    @NotNull
    private String qualified(@Nullable String schema, @Nullable String name) {
        return quote(schema) + "." + quote(name); //$NON-NLS-1$
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
