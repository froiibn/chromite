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
package com.seizia.chromite.model.admin.oracle;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.ext.oracle.model.OracleTable;
import com.seizia.chromite.ext.oracle.model.OracleTableIndex;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Oracle 유지 관리: DBMS_STATS로 테이블·인덱스·스키마 통계 수집, 무효(INVALID) 객체 다시 컴파일(객체 하나, 스키마 전체), 인덱스 다시 작성과
 * 유지 관리 탭의 무효 객체(컴파일 오류 포함)·오래된 통계·사용할 수 없는 인덱스 목록.
 *
 * 설계:
 * <ul>
 *     <li>통계 수집 문장은 DBeaver {@code OracleToolsTableGatherStatistics}와 같은 PL/SQL 블록이다. 이름은 큰따옴표로 감싸 넘겨
 *     (DBMS_STATS는 따옴표가 없으면 대문자로 바꾼다) 소문자·특수 문자 이름도 그대로 찾게 한다.</li>
 *     <li>다시 컴파일: 객체 하나는 종류에 맞는 {@code ALTER … COMPILE}(패키지·형식 본문은 {@code COMPILE BODY}), 스키마는
 *     {@code DBMS_UTILITY.COMPILE_SCHEMA}(무효인 것만, 또는 전체) 또는 무효 객체마다 {@code ALTER … COMPILE}을 고른다. 컴파일 오류는 예외가 아니라
 *     경고(ORA-17110)로 오므로, 실행 뒤에 {@code ALL_ERRORS}를 읽는 문장을 덧붙여 줄·위치·메시지를 결과 표로 보인다(미리 보기에도 보인다).</li>
 *     <li>권한: 남의 스키마는 ANALYZE ANY(통계), ALTER ANY INDEX(다시 작성), 종류별 ALTER ANY …(컴파일)가 필요하다({@code SESSION_PRIVS}).
 *     목록은 DBA 뷰를 먼저 쓰고 읽을 수 없으면 ALL 뷰(접근할 수 있는 객체만)로 읽고 안내한다.</li>
 *     <li>ORACLE LICENSE GUARD: Diagnostics/Tuning Pack이 필요한 뷰(AWR {@code DBA_HIST_*}, ASH, SQL Tuning Advisor, SQL Access Advisor의
 *     인덱스 권고)는 쓰지 않는다. 쓰는 뷰({@code *_OBJECTS}, {@code *_ERRORS}, {@code *_TAB_STATISTICS}, {@code *_INDEXES}, {@code SESSION_PRIVS})는
 *     팩이 필요 없다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
public class OracleMaintenanceDialect extends MaintenanceDialect {

    public static final String OP_GATHER_TABLE = "gather_table"; //$NON-NLS-1$
    public static final String OP_GATHER_INDEX = "gather_index"; //$NON-NLS-1$
    public static final String OP_GATHER_SCHEMA = "gather_schema"; //$NON-NLS-1$
    public static final String OP_REBUILD = "rebuild"; //$NON-NLS-1$
    public static final String OP_COMPILE = "compile"; //$NON-NLS-1$
    public static final String OP_COMPILE_SCHEMA = "compile_schema"; //$NON-NLS-1$

    private static final String OPT_SAMPLE = "sample"; //$NON-NLS-1$
    private static final String OPT_CASCADE = "cascade"; //$NON-NLS-1$
    private static final String OPT_GATHER = "gather"; //$NON-NLS-1$
    private static final String OPT_ONLINE = "online"; //$NON-NLS-1$
    private static final String OPT_METHOD = "method"; //$NON-NLS-1$

    private static final String SAMPLE_AUTO = "AUTO"; //$NON-NLS-1$
    private static final String METHOD_INVALID = "invalid"; //$NON-NLS-1$
    private static final String METHOD_ALL = "all"; //$NON-NLS-1$
    private static final String METHOD_EACH = "each"; //$NON-NLS-1$

    /**
     * Error lines kept per object in the detail of the invalid object list.
     */
    private static final int MAX_ERROR_LINES = 50;

    /**
     * System privilege needed to compile an object of another schema, by object type.
     */
    private static final Map<String, String> COMPILE_PRIVILEGES = new LinkedHashMap<>();

    static {
        COMPILE_PRIVILEGES.put("PROCEDURE", "ALTER ANY PROCEDURE"); //$NON-NLS-1$ //$NON-NLS-2$
        COMPILE_PRIVILEGES.put("FUNCTION", "ALTER ANY PROCEDURE"); //$NON-NLS-1$ //$NON-NLS-2$
        COMPILE_PRIVILEGES.put("PACKAGE", "ALTER ANY PROCEDURE"); //$NON-NLS-1$ //$NON-NLS-2$
        COMPILE_PRIVILEGES.put("PACKAGE BODY", "ALTER ANY PROCEDURE"); //$NON-NLS-1$ //$NON-NLS-2$
        COMPILE_PRIVILEGES.put("TRIGGER", "ALTER ANY TRIGGER"); //$NON-NLS-1$ //$NON-NLS-2$
        COMPILE_PRIVILEGES.put("TYPE", "ALTER ANY TYPE"); //$NON-NLS-1$ //$NON-NLS-2$
        COMPILE_PRIVILEGES.put("TYPE BODY", "ALTER ANY TYPE"); //$NON-NLS-1$ //$NON-NLS-2$
        COMPILE_PRIVILEGES.put("VIEW", "ALTER ANY TABLE"); //$NON-NLS-1$ //$NON-NLS-2$
        COMPILE_PRIVILEGES.put("MATERIALIZED VIEW", "ALTER ANY MATERIALIZED VIEW"); //$NON-NLS-1$ //$NON-NLS-2$
        COMPILE_PRIVILEGES.put("DIMENSION", "ALTER ANY DIMENSION"); //$NON-NLS-1$ //$NON-NLS-2$
        COMPILE_PRIVILEGES.put("SYNONYM", "CREATE ANY SYNONYM"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    private final OracleDataSource dataSource;

    public OracleMaintenanceDialect(@NotNull OracleDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * @param invalidObjects invalid objects of the schema ({@code [name, type]}) for compiling one by one
     */
    private record Data(@NotNull List<String[]> invalidObjects) {
    }

    @Nullable
    @Override
    public MaintenanceTarget getTarget(@NotNull DBSObject object) {
        if (object instanceof OracleTable table) {
            return MaintenanceTarget.table(null, table.getSchema().getName(), table.getName());
        }
        if (object instanceof OracleTableIndex index && index.getTable() != null) {
            return MaintenanceTarget.index(null, index.getContainer().getName(), index.getTable().getName(), index.getName());
        }
        if (object instanceof OracleSchema schema) {
            return MaintenanceTarget.schema(null, schema.getName());
        }
        return null;
    }

    @NotNull
    @Override
    public String getScopeLabel() {
        return MaintenanceMessages.maint_scope_owner;
    }

    /**
     * DBA view if the login can read it, otherwise the ALL view (objects it can access).
     */
    @NotNull
    private static String view(@NotNull JDBCSession session, @NotNull String name, @NotNull List<String> missing) {
        if (AdminSql.probe(session, "SELECT 1 FROM DBA_" + name + " WHERE 1 = 0") == null) { //$NON-NLS-1$ //$NON-NLS-2$
            return "DBA_" + name; //$NON-NLS-1$
        }
        missing.add("DBA_" + name); //$NON-NLS-1$
        return "ALL_" + name; //$NON-NLS-1$
    }

    /**
     * Condition that leaves out the schemas installed with the database.
     */
    @NotNull
    private String userSchemas(@NotNull String column) {
        if (dataSource.isAtLeastV12()) {
            return column + " IN (SELECT username FROM all_users WHERE oracle_maintained = 'N')"; //$NON-NLS-1$
        }
        return column + " NOT IN ('SYS', 'SYSTEM', 'OUTLN', 'DBSNMP', 'XDB', 'CTXSYS', 'MDSYS', 'ORDSYS', 'ORDDATA', 'WMSYS', " //$NON-NLS-1$
            + "'EXFSYS', 'APPQOSSYS', 'OLAPSYS', 'SYSMAN', 'APEX_PUBLIC_USER', 'ANONYMOUS', 'PUBLIC')"; //$NON-NLS-1$
    }

    @NotNull
    @Override
    public MaintenanceReport readOverview(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @Nullable String scope, int limit)
        throws DBException {
        try {
            List<String> missing = new ArrayList<>();
            String objectsView = view(session, "OBJECTS", missing); //$NON-NLS-1$
            String errorsView = view(session, "ERRORS", missing); //$NON-NLS-1$
            String statsView = view(session, "TAB_STATISTICS", missing); //$NON-NLS-1$
            String indexesView = view(session, "INDEXES", missing); //$NON-NLS-1$
            List<String> notices = new ArrayList<>();
            if (!missing.isEmpty()) {
                notices.add(NLS.bind(MaintenanceMessages.maint_ora_missing_views, String.join(", ", missing))); //$NON-NLS-1$
            }
            List<String> scopes = AdminSql.queryStrings(session, "SELECT username FROM all_users WHERE " + userSchemas("username") //$NON-NLS-1$ //$NON-NLS-2$
                + " ORDER BY username"); //$NON-NLS-1$
            String listScope = scope != null && scopes.contains(scope) ? scope : null;
            String ownerFilter = listScope != null ? " AND o.owner = ?" : " AND " + userSchemas("o.owner"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            AdminGrid invalid = new AdminGrid("invalid", MaintenanceMessages.maint_grid_ora_invalid, //$NON-NLS-1$
                SpaceColumn.text(MaintenanceMessages.maint_col_owner),
                SpaceColumn.text(MaintenanceMessages.maint_col_object),
                SpaceColumn.text(MaintenanceMessages.maint_col_type),
                SpaceColumn.text(MaintenanceMessages.maint_col_last_ddl),
                SpaceColumn.count(MaintenanceMessages.maint_col_errors));
            invalid.setDescription(MaintenanceMessages.maint_ora_invalid_desc);
            invalid.setEmptyText(MaintenanceMessages.maint_empty_invalid);
            List<Object[]> invalidRows = new ArrayList<>();
            String invalidSql = "SELECT * FROM (SELECT o.owner, o.object_name, o.object_type, TO_CHAR(o.last_ddl_time, 'YYYY-MM-DD HH24:MI:SS'), " //$NON-NLS-1$
                + "(SELECT COUNT(*) FROM " + errorsView + " e WHERE e.owner = o.owner AND e.name = o.object_name AND e.type = o.object_type) " //$NON-NLS-1$ //$NON-NLS-2$
                + "FROM " + objectsView + " o WHERE o.status = 'INVALID'" + ownerFilter //$NON-NLS-1$ //$NON-NLS-2$
                + " ORDER BY o.owner, o.object_type, o.object_name) WHERE ROWNUM <= ?"; //$NON-NLS-1$
            AdminSql.RowReader invalidReader = rs -> invalidRows.add(new Object[] {
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), AdminSql.getLong(rs, 5)});
            if (listScope != null) {
                AdminSql.query(session, invalidSql, invalidReader, listScope, limit);
            } else {
                AdminSql.query(session, invalidSql, invalidReader, limit);
            }
            for (Object[] row : invalidRows) {
                String owner = (String) row[0];
                String name = (String) row[1];
                String type = (String) row[2];
                String detail = row[4] instanceof Long count && count > 0 ? readErrors(session, errorsView, owner, name, type) : null;
                invalid.addTaggedRow(new MaintenanceAction(MaintenanceTarget.object(owner, name, type), OP_COMPILE), detail, row);
            }

            AdminGrid stale = new AdminGrid("stale", MaintenanceMessages.maint_grid_ora_stale, //$NON-NLS-1$
                SpaceColumn.text(MaintenanceMessages.maint_col_owner),
                SpaceColumn.text(MaintenanceMessages.maint_col_table),
                SpaceColumn.count(MaintenanceMessages.maint_col_stat_rows),
                SpaceColumn.text(MaintenanceMessages.maint_col_last_analyzed),
                new SpaceColumn(MaintenanceMessages.maint_col_stale, SpaceColumn.Kind.TEXT, MaintenanceMessages.maint_col_stale_tip));
            stale.setDescription(MaintenanceMessages.maint_ora_stale_desc);
            stale.setEmptyText(MaintenanceMessages.maint_empty_stale);
            String staleSql = "SELECT * FROM (SELECT o.owner, o.table_name, o.num_rows, TO_CHAR(o.last_analyzed, 'YYYY-MM-DD HH24:MI:SS'), o.stale_stats " //$NON-NLS-1$
                + "FROM " + statsView + " o WHERE o.object_type = 'TABLE' AND o.table_name NOT LIKE 'BIN$%' " //$NON-NLS-1$ //$NON-NLS-2$
                + "AND (o.stale_stats = 'YES' OR o.last_analyzed IS NULL)" + ownerFilter //$NON-NLS-1$
                + " ORDER BY o.last_analyzed NULLS FIRST, o.owner, o.table_name) WHERE ROWNUM <= ?"; //$NON-NLS-1$
            AdminSql.RowReader staleReader = rs -> {
                String owner = rs.getString(1);
                String table = rs.getString(2);
                stale.addTaggedRow(new MaintenanceAction(MaintenanceTarget.table(null, owner, table), OP_GATHER_TABLE), null,
                    owner, table, AdminSql.getLong(rs, 3), rs.getString(4), rs.getString(5) == null ? null : yesNo(rs.getString(5)));
            };
            if (listScope != null) {
                AdminSql.query(session, staleSql, staleReader, listScope, limit);
            } else {
                AdminSql.query(session, staleSql, staleReader, limit);
            }

            AdminGrid unusable = new AdminGrid("unusable", MaintenanceMessages.maint_grid_ora_unusable, //$NON-NLS-1$
                SpaceColumn.text(MaintenanceMessages.maint_col_owner),
                SpaceColumn.text(MaintenanceMessages.maint_col_index),
                SpaceColumn.text(MaintenanceMessages.maint_col_table),
                SpaceColumn.text(MaintenanceMessages.maint_col_status));
            unusable.setDescription(MaintenanceMessages.maint_ora_unusable_desc);
            unusable.setEmptyText(MaintenanceMessages.maint_empty_unusable);
            String unusableSql = "SELECT * FROM (SELECT o.owner, o.index_name, o.table_owner, o.table_name, o.status FROM " + indexesView //$NON-NLS-1$
                + " o WHERE o.status = 'UNUSABLE'" + ownerFilter + " ORDER BY o.owner, o.index_name) WHERE ROWNUM <= ?"; //$NON-NLS-1$ //$NON-NLS-2$
            AdminSql.RowReader unusableReader = rs -> {
                String owner = rs.getString(1);
                String index = rs.getString(2);
                String table = rs.getString(4);
                unusable.addTaggedRow(new MaintenanceAction(MaintenanceTarget.index(null, owner, table, index), OP_REBUILD), null,
                    owner, index, rs.getString(3) + "." + table, rs.getString(5)); //$NON-NLS-1$
            };
            if (listScope != null) {
                AdminSql.query(session, unusableSql, unusableReader, listScope, limit);
            } else {
                AdminSql.query(session, unusableSql, unusableReader, limit);
            }
            return new MaintenanceReport(List.of(invalid, stale, unusable), scopes, listScope, notices);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    @Nullable
    private static String readErrors(@NotNull JDBCSession session, @NotNull String errorsView, @NotNull String owner, @NotNull String name,
                                     @NotNull String type) throws SQLException {
        StringBuilder text = new StringBuilder();
        AdminSql.query(session, "SELECT * FROM (SELECT line, position, attribute, text FROM " + errorsView //$NON-NLS-1$
            + " WHERE owner = ? AND name = ? AND type = ? ORDER BY sequence) WHERE ROWNUM <= " + MAX_ERROR_LINES, rs -> { //$NON-NLS-1$
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(rs.getString(1)).append('/').append(rs.getString(2)).append(' ')
                    .append(rs.getString(3)).append(": ").append(rs.getString(4).strip()); //$NON-NLS-1$
            }, owner, name, type);
        return text.isEmpty() ? null : text.toString();
    }

    @NotNull
    @Override
    public MaintenancePlan prepare(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @NotNull MaintenanceTarget target)
        throws DBException {
        MaintenancePlan plan = new MaintenancePlan(target);
        List<String[]> invalidObjects = new ArrayList<>();
        try {
            String user = AdminSql.queryString(session, "SELECT USER FROM DUAL"); //$NON-NLS-1$
            Set<String> privileges = new HashSet<>(AdminSql.queryStrings(session, "SELECT privilege FROM session_privs")); //$NON-NLS-1$
            String schema = target.schema();
            boolean own = schema != null && schema.equals(user);
            switch (target.kind()) {
                case TABLE -> {
                    String[] state = new String[3];
                    AdminSql.query(session, "SELECT num_rows, TO_CHAR(last_analyzed, 'YYYY-MM-DD HH24:MI:SS'), stale_stats " //$NON-NLS-1$
                        + "FROM all_tab_statistics WHERE owner = ? AND table_name = ? AND object_type = 'TABLE'", rs -> { //$NON-NLS-1$
                            state[0] = rs.getString(1);
                            state[1] = rs.getString(2);
                            state[2] = rs.getString(3);
                        }, schema, target.name());
                    plan.addFact(NLS.bind(MaintenanceMessages.maint_ora_fact_table, new Object[] {
                        dash(state[0]), never(state[1]), state[2] == null ? "-" : yesNo(state[2])})); //$NON-NLS-1$
                    plan.addOperation(gatherOperation(OP_GATHER_TABLE, MaintenanceMessages.maint_ora_gather_table, true, false));
                    if (!own && !privileges.contains("ANALYZE ANY")) { //$NON-NLS-1$
                        plan.deny(OP_GATHER_TABLE, NLS.bind(MaintenanceMessages.maint_ora_denied, "ANALYZE ANY")); //$NON-NLS-1$
                    }
                }
                case INDEX -> {
                    String[] state = new String[4];
                    AdminSql.query(session, "SELECT status, TO_CHAR(last_analyzed, 'YYYY-MM-DD HH24:MI:SS'), blevel, leaf_blocks " //$NON-NLS-1$
                        + "FROM all_indexes WHERE owner = ? AND index_name = ?", rs -> { //$NON-NLS-1$
                            for (int i = 0; i < state.length; i++) {
                                state[i] = rs.getString(i + 1);
                            }
                        }, schema, target.name());
                    plan.addFact(NLS.bind(MaintenanceMessages.maint_ora_fact_index, new Object[] {
                        dash(state[0]), never(state[1]), dash(state[2]), dash(state[3])}));
                    plan.addOperation(new MaintenanceOperation(OP_REBUILD, MaintenanceMessages.maint_ora_rebuild, MaintenanceMessages.maint_ora_rebuild_desc,
                        List.of(MaintenanceOption.flag(OPT_ONLINE, "ONLINE", false, MaintenanceMessages.maint_ora_online_tip)))); //$NON-NLS-1$
                    plan.addOperation(gatherOperation(OP_GATHER_INDEX, MaintenanceMessages.maint_ora_gather_index, false, false));
                    plan.setSuggestedOperation("UNUSABLE".equals(state[0]) ? OP_REBUILD : OP_GATHER_INDEX); //$NON-NLS-1$
                    if (!own && !privileges.contains("ALTER ANY INDEX")) { //$NON-NLS-1$
                        plan.deny(OP_REBUILD, NLS.bind(MaintenanceMessages.maint_ora_denied, "ALTER ANY INDEX")); //$NON-NLS-1$
                    }
                    if (!own && !privileges.contains("ANALYZE ANY")) { //$NON-NLS-1$
                        plan.deny(OP_GATHER_INDEX, NLS.bind(MaintenanceMessages.maint_ora_denied, "ANALYZE ANY")); //$NON-NLS-1$
                    }
                }
                case SCHEMA, OBJECT -> {
                    AdminSql.query(session, "SELECT object_name, object_type FROM all_objects WHERE owner = ? AND status = 'INVALID' " //$NON-NLS-1$
                        + "ORDER BY object_type, object_name", rs -> invalidObjects.add(new String[] {rs.getString(1), rs.getString(2)}), schema); //$NON-NLS-1$
                    if (target.kind() == MaintenanceTarget.Kind.OBJECT) {
                        String[] state = new String[2];
                        AdminSql.query(session, "SELECT status, TO_CHAR(last_ddl_time, 'YYYY-MM-DD HH24:MI:SS') FROM all_objects " //$NON-NLS-1$
                            + "WHERE owner = ? AND object_name = ? AND object_type = ?", rs -> { //$NON-NLS-1$
                                state[0] = rs.getString(1);
                                state[1] = rs.getString(2);
                            }, schema, target.name(), target.objectType());
                        plan.addFact(NLS.bind(MaintenanceMessages.maint_ora_fact_object, new Object[] {target.objectType(), dash(state[0]), dash(state[1])}));
                        plan.addOperation(new MaintenanceOperation(OP_COMPILE, MaintenanceMessages.maint_ora_compile,
                            MaintenanceMessages.maint_ora_compile_desc, List.of()));
                        String type = target.objectType();
                        String privilege = type == null ? null : COMPILE_PRIVILEGES.get(type);
                        if (compileStatement(schema, target.name(), type) == null) {
                            plan.deny(OP_COMPILE, NLS.bind(MaintenanceMessages.maint_ora_compile_unsupported, type));
                        } else if (!own && !"PUBLIC".equals(schema) && privilege != null && !privileges.contains(privilege)) { //$NON-NLS-1$
                            plan.deny(OP_COMPILE, NLS.bind(MaintenanceMessages.maint_ora_denied, privilege));
                        }
                    } else {
                        String[] state = new String[1];
                        state[0] = AdminSql.queryString(session, "SELECT COUNT(*) FROM all_tab_statistics WHERE owner = ? " //$NON-NLS-1$
                            + "AND object_type = 'TABLE' AND (stale_stats = 'YES' OR last_analyzed IS NULL)", schema); //$NON-NLS-1$
                        plan.addFact(NLS.bind(MaintenanceMessages.maint_ora_fact_schema, invalidObjects.size(), dash(state[0])));
                        plan.addOperation(gatherOperation(OP_GATHER_SCHEMA, MaintenanceMessages.maint_ora_gather_schema, true, true));
                        if (!own && !privileges.contains("ANALYZE ANY")) { //$NON-NLS-1$
                            plan.deny(OP_GATHER_SCHEMA, NLS.bind(MaintenanceMessages.maint_ora_denied, "ANALYZE ANY")); //$NON-NLS-1$
                        }
                    }
                    plan.addOperation(new MaintenanceOperation(OP_COMPILE_SCHEMA, NLS.bind(MaintenanceMessages.maint_ora_compile_schema, schema),
                        MaintenanceMessages.maint_ora_compile_schema_desc, List.of(
                        MaintenanceOption.choice(OPT_METHOD, MaintenanceMessages.maint_opt_method, List.of(
                            new MaintenanceOption.Choice(METHOD_INVALID, MaintenanceMessages.maint_ora_method_invalid),
                            new MaintenanceOption.Choice(METHOD_EACH, MaintenanceMessages.maint_ora_method_each),
                            new MaintenanceOption.Choice(METHOD_ALL, MaintenanceMessages.maint_ora_method_all)),
                            METHOD_INVALID, MaintenanceMessages.maint_ora_method_tip))));
                    if (!own && !privileges.contains("ALTER ANY PROCEDURE")) { //$NON-NLS-1$
                        plan.deny(OP_COMPILE_SCHEMA, NLS.bind(MaintenanceMessages.maint_ora_denied, "ALTER ANY PROCEDURE")); //$NON-NLS-1$
                    }
                    if (target.kind() == MaintenanceTarget.Kind.SCHEMA) {
                        plan.setSuggestedOperation(invalidObjects.isEmpty() ? OP_GATHER_SCHEMA : OP_COMPILE_SCHEMA);
                    }
                }
                default -> {
                }
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
        plan.setData(new Data(List.copyOf(invalidObjects)));
        return plan;
    }

    @NotNull
    private static MaintenanceOperation gatherOperation(@NotNull String id, @NotNull String label, boolean cascade, boolean schema) {
        List<MaintenanceOption> options = new ArrayList<>();
        options.add(MaintenanceOption.choice(OPT_SAMPLE, MaintenanceMessages.maint_opt_sample, List.of(
            new MaintenanceOption.Choice(SAMPLE_AUTO, MaintenanceMessages.maint_ora_sample_auto),
            new MaintenanceOption.Choice("1", "1%"), //$NON-NLS-1$ //$NON-NLS-2$
            new MaintenanceOption.Choice("10", "10%"), //$NON-NLS-1$ //$NON-NLS-2$
            new MaintenanceOption.Choice("30", "30%"), //$NON-NLS-1$ //$NON-NLS-2$
            new MaintenanceOption.Choice("100", "100%")), //$NON-NLS-1$ //$NON-NLS-2$
            SAMPLE_AUTO, MaintenanceMessages.maint_ora_sample_tip));
        if (cascade) {
            options.add(MaintenanceOption.flag(OPT_CASCADE, MaintenanceMessages.maint_ora_cascade, true, MaintenanceMessages.maint_ora_cascade_tip));
        }
        if (schema) {
            options.add(MaintenanceOption.choice(OPT_GATHER, MaintenanceMessages.maint_opt_gather, List.of(
                new MaintenanceOption.Choice("GATHER", MaintenanceMessages.maint_ora_gather_all), //$NON-NLS-1$
                new MaintenanceOption.Choice("GATHER STALE", MaintenanceMessages.maint_ora_gather_stale), //$NON-NLS-1$
                new MaintenanceOption.Choice("GATHER EMPTY", MaintenanceMessages.maint_ora_gather_empty)), //$NON-NLS-1$
                "GATHER STALE", null)); //$NON-NLS-1$
        }
        return new MaintenanceOperation(id, label, MaintenanceMessages.maint_ora_gather_desc, options);
    }

    @NotNull
    @Override
    public MaintenanceCommand buildCommand(@NotNull MaintenancePlan plan, @NotNull String operationId, @NotNull Map<String, Object> values) {
        MaintenanceTarget target = plan.getTarget();
        String schema = target.schema() == null ? "" : target.schema(); //$NON-NLS-1$
        List<String> statements = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String sample = choice(plan, operationId, values, OPT_SAMPLE);
        String percent = SAMPLE_AUTO.equals(sample) || sample.isEmpty() ? "DBMS_STATS.AUTO_SAMPLE_SIZE" : sample; //$NON-NLS-1$
        String cascade = flag(plan, operationId, values, OPT_CASCADE) ? "TRUE" : "FALSE"; //$NON-NLS-1$ //$NON-NLS-2$
        switch (operationId) {
            case OP_GATHER_TABLE -> statements.add("BEGIN DBMS_STATS.GATHER_TABLE_STATS(ownname => " + name(schema) //$NON-NLS-1$
                + ", tabname => " + name(target.name()) + ", estimate_percent => " + percent + ", cascade => " + cascade + "); END;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case OP_GATHER_INDEX -> statements.add("BEGIN DBMS_STATS.GATHER_INDEX_STATS(ownname => " + name(schema) //$NON-NLS-1$
                + ", indname => " + name(target.name()) + ", estimate_percent => " + percent + "); END;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case OP_GATHER_SCHEMA -> statements.add("BEGIN DBMS_STATS.GATHER_SCHEMA_STATS(ownname => " + name(schema) //$NON-NLS-1$
                + ", estimate_percent => " + percent + ", cascade => " + cascade //$NON-NLS-1$ //$NON-NLS-2$
                + ", options => " + literal(choice(plan, operationId, values, OPT_GATHER)) + "); END;"); //$NON-NLS-1$ //$NON-NLS-2$
            case OP_REBUILD -> {
                boolean online = flag(plan, operationId, values, OPT_ONLINE);
                statements.add("ALTER INDEX " + identifier(schema) + "." + identifier(target.name()) + " REBUILD" + (online ? " ONLINE" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                if (!online) {
                    warnings.add(MaintenanceMessages.maint_ora_warn_rebuild);
                }
            }
            case OP_COMPILE -> {
                String statement = compileStatement(schema, target.name(), target.objectType());
                if (statement != null) {
                    statements.add(statement);
                    statements.add("SELECT type, line, position, attribute, text FROM all_errors WHERE owner = " + literal(schema) //$NON-NLS-1$
                        + " AND name = " + literal(target.name()) + " ORDER BY type, sequence"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            case OP_COMPILE_SCHEMA -> {
                String method = choice(plan, operationId, values, OPT_METHOD);
                if (METHOD_EACH.equals(method)) {
                    List<String[]> objects = plan.getData() instanceof Data data ? data.invalidObjects() : List.of();
                    for (String[] object : objects) {
                        String statement = compileStatement(schema, object[0], object[1]);
                        if (statement != null) {
                            statements.add(statement);
                        }
                    }
                } else {
                    statements.add("BEGIN DBMS_UTILITY.COMPILE_SCHEMA(schema => " + literal(schema) //$NON-NLS-1$
                        + ", compile_all => " + (METHOD_ALL.equals(method) ? "TRUE" : "FALSE") + "); END;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    if (METHOD_ALL.equals(method)) {
                        warnings.add(MaintenanceMessages.maint_ora_warn_compile_all);
                    }
                }
                statements.add("SELECT name, type, line, position, attribute, text FROM all_errors WHERE owner = " + literal(schema) //$NON-NLS-1$
                    + " ORDER BY name, type, sequence"); //$NON-NLS-1$
            }
            default -> {
            }
        }
        if (OP_COMPILE.equals(operationId) || OP_COMPILE_SCHEMA.equals(operationId)) {
            warnings.add(MaintenanceMessages.maint_ora_warn_compile);
        }
        // A PL/SQL block ends with "/" in a script; other statements with ";"
        StringBuilder preview = new StringBuilder();
        for (String statement : statements) {
            if (!preview.isEmpty()) {
                preview.append('\n');
            }
            preview.append(statement).append(statement.startsWith("BEGIN ") ? "\n/\n" : ";\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return new MaintenanceCommand(List.copyOf(statements), preview.toString(), List.copyOf(warnings));
    }

    /**
     * The ALTER … COMPILE statement of an object type, or null for a type that cannot be compiled this way.
     */
    @Nullable
    private static String compileStatement(@Nullable String schema, @NotNull String name, @Nullable String type) {
        if (type == null) {
            return null;
        }
        String object = identifier(schema) + "." + identifier(name); //$NON-NLS-1$
        return switch (type) {
            case "PACKAGE BODY" -> "ALTER PACKAGE " + object + " COMPILE BODY"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "TYPE BODY" -> "ALTER TYPE " + object + " COMPILE BODY"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "SYNONYM" -> "PUBLIC".equals(schema) //$NON-NLS-1$ //$NON-NLS-2$
                ? "ALTER PUBLIC SYNONYM " + identifier(name) + " COMPILE" //$NON-NLS-1$ //$NON-NLS-2$
                : "ALTER SYNONYM " + object + " COMPILE"; //$NON-NLS-1$ //$NON-NLS-2$
            case "PACKAGE", "PROCEDURE", "FUNCTION", "TRIGGER", "TYPE", "VIEW", "MATERIALIZED VIEW", "DIMENSION" -> //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
                "ALTER " + type + " " + object + " COMPILE"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> null;
        };
    }

    /**
     * A quoted identifier: exact case, as the dictionary stores it.
     */
    @NotNull
    private static String identifier(@Nullable String name) {
        return "\"" + (name == null ? "" : name.replace("\"", "\"\"")) + "\""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    /**
     * A name for DBMS_STATS: a string holding the quoted identifier (DBMS_STATS upper-cases names that are not quoted).
     */
    @NotNull
    private static String name(@Nullable String name) {
        return literal(identifier(name));
    }

    @NotNull
    private static String literal(@Nullable String value) {
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @NotNull
    private static String yesNo(@Nullable String value) {
        return "YES".equals(value) ? MaintenanceMessages.maint_value_yes : MaintenanceMessages.maint_value_no; //$NON-NLS-1$
    }

    @NotNull
    private static String dash(@Nullable String value) {
        return value == null ? "-" : value; //$NON-NLS-1$
    }

    @NotNull
    private static String never(@Nullable String value) {
        return value == null ? MaintenanceMessages.maint_value_never : value;
    }
}
