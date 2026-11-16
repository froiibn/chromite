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
import com.seizia.chromite.model.admin.AdminSql;
import com.seizia.chromite.model.admin.space.ServerSpaceDialect;
import com.seizia.chromite.model.admin.space.SpaceColumn;
import com.seizia.chromite.model.admin.space.SpaceFormat;
import com.seizia.chromite.model.admin.space.SpaceGrid;
import com.seizia.chromite.model.admin.space.SpaceMessages;
import com.seizia.chromite.model.admin.space.SpaceReport;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SQL Server 공간 사용량: 데이터베이스마다 데이터·로그 파일의 크기, 사용량, 빈 공간, 자동 증가, 로그 재사용 대기와 큰 테이블.
 *
 * 설계:
 * <ul>
 *     <li>파일의 사용량은 {@code FILEPROPERTY(name, 'SpaceUsed')}만 알려 주고, 이 함수는 현재 데이터베이스에서만 된다. 세션의 데이터베이스를
 *     {@code USE}로 바꾸지 않도록 데이터베이스마다 {@code [db].sys.sp_executesql}로 그 데이터베이스 안에서 실행한다(SSMS 파일 축소 창과 같은 값).
 *     로그 파일도 같은 함수로 읽는다(2022에서 확인).</li>
 *     <li>열 수 없는 데이터베이스(권한 없음, 오프라인)는 {@code sys.master_files}의 크기만 보인다. 이 뷰는 VIEW ANY DEFINITION
 *     (또는 CREATE DATABASE, ALTER ANY DATABASE)이 없으면 다른 데이터베이스 행을 숨기므로 그때 안내한다.</li>
 *     <li>테이블 크기는 {@code sp_spaceused}와 같은 계산이다: 예약 = 할당 단위의 total_pages, 데이터 = 힙·클러스터형 인덱스의 in-row data_pages와
 *     LOB·행 오버플로 used_pages, 인덱스 = 사용 − 데이터, 미사용 = 예약 − 사용. {@code sys.dm_db_partition_stats}(VIEW DATABASE STATE 필요) 대신
 *     메타데이터 권한만 필요한 {@code sys.partitions}·{@code sys.allocation_units}를 쓴다.</li>
 *     <li>Azure SQL Database·Synapse(엔진 에디션 5, 6, 11)는 서버 단위 파일 정보가 없어 지원하지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 공간 사용량 (docs/chromite/swing-admin-tools.md)
 */
public class SQLServerSpaceDialect extends ServerSpaceDialect {

    private static final Log log = Log.getLog(SQLServerSpaceDialect.class);

    private static final long PAGE_BYTES = 8192;
    private static final int MAX_DATABASES = 500;
    // SERVERPROPERTY('EngineEdition'): Azure SQL Database, Azure Synapse, Synapse serverless
    private static final Set<Integer> UNSUPPORTED_EDITIONS = Set.of(5, 6, 11);
    // sys.database_files.type
    private static final int TYPE_ROWS = 0;
    private static final int TYPE_LOG = 1;

    private static final String DATABASES_SQL = "SELECT TOP (" + MAX_DATABASES + ") d.name, d.state_desc, d.recovery_model_desc, d.log_reuse_wait_desc, " //$NON-NLS-1$ //$NON-NLS-2$
        + "HAS_DBACCESS(d.name), " //$NON-NLS-1$
        + "(SELECT SUM(CAST(mf.size AS bigint)) FROM sys.master_files mf WHERE mf.database_id = d.database_id AND mf.type <> 1), " //$NON-NLS-1$
        + "(SELECT SUM(CAST(mf.size AS bigint)) FROM sys.master_files mf WHERE mf.database_id = d.database_id AND mf.type = 1) " //$NON-NLS-1$
        + "FROM sys.databases d ORDER BY d.name"; //$NON-NLS-1$

    // Run inside each database (FILEPROPERTY reads the current database only)
    private static final String FILES_SQL = "SELECT f.name, f.type, f.type_desc, fg.name, CAST(f.size AS bigint), " //$NON-NLS-1$
        + "CAST(FILEPROPERTY(f.name, 'SpaceUsed') AS bigint), f.growth, f.is_percent_growth, f.max_size, f.physical_name " //$NON-NLS-1$
        + "FROM sys.database_files f LEFT JOIN sys.filegroups fg ON fg.data_space_id = f.data_space_id ORDER BY f.file_id"; //$NON-NLS-1$

    // As sp_spaceused; heaps (index 0) and clustered indexes (1) hold the data, LOB units (type 2) belong to the partition
    private static final String TABLES_SQL = "SELECT TOP (@n) s.name, o.name, " //$NON-NLS-1$
        + "SUM(CASE WHEN p.index_id < 2 AND au.type = 1 THEN p.rows END), " //$NON-NLS-1$
        + "SUM(au.total_pages), SUM(au.used_pages), " //$NON-NLS-1$
        + "SUM(CASE WHEN au.type = 1 THEN (CASE WHEN p.index_id < 2 THEN au.data_pages ELSE 0 END) ELSE au.used_pages END) " //$NON-NLS-1$
        + "FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id JOIN sys.partitions p ON p.object_id = o.object_id " //$NON-NLS-1$
        + "JOIN sys.allocation_units au ON au.container_id = CASE WHEN au.type = 2 THEN p.partition_id ELSE p.hobt_id END " //$NON-NLS-1$
        + "WHERE o.type IN ('U', 'V') GROUP BY s.name, o.name ORDER BY SUM(au.total_pages) DESC"; //$NON-NLS-1$

    @NotNull
    private final SQLServerDataSource dataSource;

    public SQLServerSpaceDialect(@NotNull SQLServerDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public String getScopeLabel() {
        return SpaceMessages.space_scope_database;
    }

    @Override
    public boolean supportsAllScopes() {
        return false;
    }

    private record Database(@NotNull String name, String state, String recovery, String logReuseWait, boolean open, Long dataPages, Long logPages) {
    }

    @NotNull
    @Override
    public SpaceReport read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @Nullable String scope, int tableLimit)
        throws DBException {
        SpaceGrid databases = new SpaceGrid(SpaceGrid.Kind.DATABASES, SpaceMessages.space_grid_databases,
            SpaceColumn.text(SpaceMessages.space_col_database),
            SpaceColumn.text(SpaceMessages.space_col_state),
            SpaceColumn.text(SpaceMessages.space_col_recovery),
            SpaceColumn.bytes(SpaceMessages.space_col_data_size),
            SpaceColumn.bytes(SpaceMessages.space_col_data_used),
            SpaceColumn.bytes(SpaceMessages.space_col_data_free),
            SpaceColumn.bytes(SpaceMessages.space_col_log_size),
            SpaceColumn.bytes(SpaceMessages.space_col_log_used),
            SpaceColumn.percent(SpaceMessages.space_col_log_used_pct),
            SpaceColumn.bytes(SpaceMessages.space_col_total),
            new SpaceColumn(SpaceMessages.space_col_log_reuse_wait, SpaceColumn.Kind.TEXT, SpaceMessages.space_col_log_reuse_wait_tip));
        SpaceGrid files = new SpaceGrid(SpaceGrid.Kind.FILES, SpaceMessages.space_grid_files,
            SpaceColumn.text(SpaceMessages.space_col_database),
            SpaceColumn.text(SpaceMessages.space_col_file),
            SpaceColumn.text(SpaceMessages.space_col_type),
            SpaceColumn.text(SpaceMessages.space_col_filegroup),
            SpaceColumn.bytes(SpaceMessages.space_col_size),
            SpaceColumn.bytes(SpaceMessages.space_col_used),
            SpaceColumn.bytes(SpaceMessages.space_col_free),
            SpaceColumn.percent(SpaceMessages.space_col_free_pct),
            SpaceColumn.text(SpaceMessages.space_col_autogrowth),
            new SpaceColumn(SpaceMessages.space_col_max_size, SpaceColumn.Kind.BYTES_LIMIT, null),
            SpaceColumn.text(SpaceMessages.space_col_path));
        SpaceGrid tables = new SpaceGrid(SpaceGrid.Kind.TABLES, SpaceMessages.space_grid_tables,
            SpaceColumn.text(SpaceMessages.space_col_schema),
            SpaceColumn.text(SpaceMessages.space_col_table),
            SpaceColumn.count(SpaceMessages.space_col_rows),
            SpaceColumn.bytes(SpaceMessages.space_col_data),
            SpaceColumn.bytes(SpaceMessages.space_col_index),
            SpaceColumn.bytes(SpaceMessages.space_col_unused),
            new SpaceColumn(SpaceMessages.space_col_total, SpaceColumn.Kind.BYTES, SpaceMessages.space_col_reserved_tip));
        List<String> notices = new ArrayList<>();
        List<String> scopes = new ArrayList<>();
        try {
            String edition = AdminSql.queryString(session, "SELECT CAST(SERVERPROPERTY('EngineEdition') AS int)"); //$NON-NLS-1$
            if (edition != null && UNSUPPORTED_EDITIONS.contains(Integer.parseInt(edition))) {
                databases.setUnavailable(SpaceMessages.space_mssql_edition);
                files.setUnavailable(SpaceMessages.space_mssql_edition);
                tables.setUnavailable(SpaceMessages.space_mssql_edition);
                return new SpaceReport(List.of(databases, files, tables), scopes, null, List.of(SpaceMessages.space_mssql_edition));
            }
            boolean viewAnyDefinition = "1".equals(AdminSql.queryString(session, //$NON-NLS-1$
                "SELECT HAS_PERMS_BY_NAME(NULL, NULL, 'VIEW ANY DEFINITION')")); //$NON-NLS-1$
            String currentDatabase = AdminSql.queryString(session, "SELECT DB_NAME()"); //$NON-NLS-1$

            List<Database> list = new ArrayList<>();
            AdminSql.query(session, DATABASES_SQL, rs -> list.add(new Database(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                "ONLINE".equals(rs.getString(2)) && rs.getInt(5) == 1, AdminSql.getLong(rs, 6), AdminSql.getLong(rs, 7)))); //$NON-NLS-1$
            boolean hidden = false;
            for (Database database : list) {
                if (monitor.isCanceled()) {
                    break;
                }
                long[] totals = {0, 0, 0, 0}; // data size, data used, log size, log used (pages)
                boolean[] usedKnown = {false, false};
                if (database.open()) {
                    scopes.add(database.name());
                    try {
                        readFiles(session, database.name(), files, totals, usedKnown);
                    } catch (SQLException e) {
                        // E.g. the database went offline or its guest user is disabled since the list was read
                        log.debug("Error reading the files of " + database.name(), e); //$NON-NLS-1$
                        totals = null;
                    }
                } else {
                    totals = null;
                }
                Long dataSize;
                Long logSize;
                Long dataUsed = null;
                Long logUsed = null;
                if (totals != null) {
                    dataSize = totals[0] * PAGE_BYTES;
                    logSize = totals[2] * PAGE_BYTES;
                    dataUsed = usedKnown[0] ? totals[1] * PAGE_BYTES : null;
                    logUsed = usedKnown[1] ? totals[3] * PAGE_BYTES : null;
                } else {
                    dataSize = database.dataPages() == null ? null : database.dataPages() * PAGE_BYTES;
                    logSize = database.logPages() == null ? null : database.logPages() * PAGE_BYTES;
                    hidden |= dataSize == null;
                }
                databases.addRow(database.name(), database.state(), database.recovery(),
                    dataSize, dataUsed, dataSize != null && dataUsed != null ? dataSize - dataUsed : null,
                    logSize, logUsed, percent(logUsed, logSize),
                    dataSize != null && logSize != null ? dataSize + logSize : null,
                    database.logReuseWait());
            }
            if (hidden && !viewAnyDefinition) {
                notices.add(SpaceMessages.space_mssql_hidden_databases);
            }

            String tableScope = scope != null && scopes.contains(scope) ? scope
                : currentDatabase != null && scopes.contains(currentDatabase) ? currentDatabase
                : scopes.isEmpty() ? null : scopes.get(0);
            if (tableScope != null) {
                AdminSql.query(session, "EXEC " + quote(tableScope) + ".sys.sp_executesql " + unicodeLiteral(TABLES_SQL) //$NON-NLS-1$ //$NON-NLS-2$
                    + ", N'@n int', @n = ?", rs -> { //$NON-NLS-1$
                        long reserved = rs.getLong(4);
                        long used = rs.getLong(5);
                        long data = rs.getLong(6);
                        tables.addRow(rs.getString(1), rs.getString(2), AdminSql.getLong(rs, 3), data * PAGE_BYTES,
                            Math.max(0, used - data) * PAGE_BYTES, Math.max(0, reserved - used) * PAGE_BYTES, reserved * PAGE_BYTES);
                    }, tableLimit);
                // Without VIEW DEFINITION only the tables the login has a permission on are visible
                if (!"1".equals(AdminSql.queryString(session, "SELECT HAS_PERMS_BY_NAME(?, 'DATABASE', 'VIEW DEFINITION')", tableScope))) { //$NON-NLS-1$ //$NON-NLS-2$
                    notices.add(NLS.bind(SpaceMessages.space_mssql_tables_partial, tableScope));
                }
            }
            return new SpaceReport(List.of(databases, files, tables), scopes, tableScope, notices);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    private static void readFiles(@NotNull JDBCSession session, @NotNull String database, @NotNull SpaceGrid files,
                                  @NotNull long[] totals, @NotNull boolean[] usedKnown) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        AdminSql.query(session, "EXEC " + quote(database) + ".sys.sp_executesql " + unicodeLiteral(FILES_SQL), rs -> { //$NON-NLS-1$ //$NON-NLS-2$
            int type = rs.getInt(2);
            long size = rs.getLong(5);
            Long used = AdminSql.getLong(rs, 6);
            int growth = rs.getInt(7);
            boolean percentGrowth = rs.getBoolean(8);
            int maxSize = rs.getInt(9);
            if (type == TYPE_ROWS || type == TYPE_LOG) {
                int offset = type == TYPE_ROWS ? 0 : 2;
                totals[offset] += size;
                if (used != null) {
                    totals[offset + 1] += used;
                    usedKnown[offset / 2] = true;
                }
            }
            String autogrowth = growth == 0 ? SpaceMessages.space_value_growth_none
                : percentGrowth ? NLS.bind(SpaceMessages.space_value_growth_percent, growth)
                : NLS.bind(SpaceMessages.space_value_growth_size, SpaceFormat.bytes(growth * PAGE_BYTES));
            // max_size: -1 unlimited, 0 no growth, otherwise pages (the log's default 268435456 pages is 2 TB)
            Long max = maxSize == -1 ? SpaceColumn.UNLIMITED : maxSize == 0 ? size * PAGE_BYTES : (long) maxSize * PAGE_BYTES;
            rows.add(new Object[]{database, rs.getString(1), rs.getString(3), rs.getString(4), size * PAGE_BYTES,
                used == null ? null : used * PAGE_BYTES, used == null ? null : (size - used) * PAGE_BYTES,
                used == null ? null : percent(size - used, size), autogrowth, max, rs.getString(10)});
        });
        for (Object[] row : rows) {
            files.addRow(row);
        }
    }

    @Nullable
    private static Double percent(@Nullable Long part, @Nullable Long whole) {
        return part == null || whole == null || whole == 0 ? null : part * 100.0 / whole;
    }

    @NotNull
    private static String quote(@NotNull String name) {
        return "[" + name.replace("]", "]]") + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @NotNull
    private static String unicodeLiteral(@NotNull String sql) {
        return "N'" + sql.replace("'", "''") + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
