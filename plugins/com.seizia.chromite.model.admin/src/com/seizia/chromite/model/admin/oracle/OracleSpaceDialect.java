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
import com.seizia.chromite.model.admin.AdminSql;
import com.seizia.chromite.model.admin.space.ServerSpaceDialect;
import com.seizia.chromite.model.admin.space.SpaceColumn;
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

/**
 * Oracle 공간 사용량: 데이터베이스(PDB) 크기, 테이블스페이스와 데이터 파일(크기, 사용, 빈 공간, 자동 확장, 최대 크기), 큰 테이블.
 *
 * 설계:
 * <ul>
 *     <li>DBA 뷰({@code DBA_TABLESPACES}, {@code DBA_DATA_FILES}, {@code DBA_FREE_SPACE}, {@code DBA_TEMP_FILES}, {@code DBA_TEMP_FREE_SPACE},
 *     {@code DBA_SEGMENTS}, {@code DBA_INDEXES}, {@code DBA_LOBS}, {@code DBA_TABLES})를 하나씩 읽어 보고(SELECT_CATALOG_ROLE,
 *     SELECT ANY DICTIONARY, 개별 GRANT 어느 쪽이든 실제로 읽히는지가 기준) 읽을 수 있는 것만 질의한다. 읽을 수 없으면 데이터베이스·
 *     테이블스페이스·데이터 파일 표는 "읽을 수 없음"이고, 큰 테이블은 USER 뷰로 로그인 자신의 스키마만 보인다.</li>
 *     <li>라이선스: Diagnostics Pack이 필요한 AWR의 공간 기록({@code DBA_HIST_TBSPC_SPACE_USAGE} 등)은 쓰지 않는다. 위 뷰는 팩이 필요 없다.</li>
 *     <li>테이블의 크기는 세그먼트를 테이블로 모은다: 데이터 = 테이블(파티션) 세그먼트, 인덱스 = 그 테이블 인덱스의 세그먼트(LOB 인덱스 제외),
 *     LOB = LOB 세그먼트와 LOB 인덱스. 행 수는 통계({@code NUM_ROWS})다. 휴지통의 테이블({@code BIN$})은 뺀다.</li>
 *     <li>임시 테이블스페이스의 크기와 빈 공간은 {@code DBA_TEMP_FREE_SPACE}(11g+)에서 읽는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 공간 사용량 (docs/chromite/swing-admin-tools.md)
 */
public class OracleSpaceDialect extends ServerSpaceDialect {

    private static final String[] FILE_VIEWS = {"DBA_TABLESPACES", "DBA_DATA_FILES", "DBA_FREE_SPACE", "DBA_TEMP_FILES"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    private static final String TEMP_FREE_VIEW = "DBA_TEMP_FREE_SPACE"; //$NON-NLS-1$
    private static final String[] SEGMENT_VIEWS = {"DBA_SEGMENTS", "DBA_INDEXES", "DBA_LOBS", "DBA_TABLES"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    // Segments added up per table. {0}: DBA or USER views; {1}, {3}: owner of a segment; {2}: owner filter; {4}: index column of
    // the segment owner; {5}: owner of a LOB; {6}: owner of a table. The USER views have no owner columns: USER stands for them
    private static final String TABLES_SQL = "SELECT * FROM (SELECT x.owner, x.table_name, t.num_rows, " //$NON-NLS-1$
        + "SUM(CASE x.kind WHEN 'T' THEN x.bytes END), SUM(CASE x.kind WHEN 'I' THEN x.bytes END), SUM(CASE x.kind WHEN 'L' THEN x.bytes END), " //$NON-NLS-1$
        + "SUM(x.bytes) FROM (" //$NON-NLS-1$
        + "SELECT {1} owner, s.segment_name table_name, 'T' kind, s.bytes FROM {0}_SEGMENTS s " //$NON-NLS-1$
        + "WHERE s.segment_type IN ('TABLE', 'TABLE PARTITION', 'TABLE SUBPARTITION') " //$NON-NLS-1$
        + "UNION ALL SELECT i.table_owner, i.table_name, 'I', s.bytes FROM {0}_INDEXES i JOIN {0}_SEGMENTS s " //$NON-NLS-1$
        + "ON {3} = i.{4} AND s.segment_name = i.index_name WHERE s.segment_type IN ('INDEX', 'INDEX PARTITION', 'INDEX SUBPARTITION') " //$NON-NLS-1$
        + "UNION ALL SELECT {5}, l.table_name, 'L', s.bytes FROM {0}_LOBS l JOIN {0}_SEGMENTS s " //$NON-NLS-1$
        + "ON {3} = {5} AND s.segment_name IN (l.segment_name, l.index_name)" //$NON-NLS-1$
        + ") x LEFT JOIN {0}_TABLES t ON {6} = x.owner AND t.table_name = x.table_name " //$NON-NLS-1$
        + "WHERE x.table_name NOT LIKE 'BIN$%'{2} GROUP BY x.owner, x.table_name, t.num_rows ORDER BY 7 DESC) WHERE ROWNUM <= ?"; //$NON-NLS-1$

    @NotNull
    private final OracleDataSource dataSource;

    public OracleSpaceDialect(@NotNull OracleDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public String getScopeLabel() {
        return SpaceMessages.space_scope_owner;
    }

    @NotNull
    @Override
    public SpaceReport read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @Nullable String scope, int tableLimit)
        throws DBException {
        SpaceGrid databases = new SpaceGrid(SpaceGrid.Kind.DATABASES, SpaceMessages.space_grid_databases,
            SpaceColumn.text(SpaceMessages.space_col_database),
            SpaceColumn.bytes(SpaceMessages.space_col_data_size),
            SpaceColumn.bytes(SpaceMessages.space_col_data_used),
            SpaceColumn.bytes(SpaceMessages.space_col_data_free),
            SpaceColumn.bytes(SpaceMessages.space_col_temp_size),
            SpaceColumn.bytes(SpaceMessages.space_col_total));
        SpaceGrid tablespaces = new SpaceGrid(SpaceGrid.Kind.TABLESPACES, SpaceMessages.space_grid_tablespaces,
            SpaceColumn.text(SpaceMessages.space_col_tablespace),
            SpaceColumn.text(SpaceMessages.space_col_contents),
            SpaceColumn.text(SpaceMessages.space_col_status),
            SpaceColumn.bytes(SpaceMessages.space_col_size),
            SpaceColumn.bytes(SpaceMessages.space_col_used),
            SpaceColumn.bytes(SpaceMessages.space_col_free),
            SpaceColumn.percent(SpaceMessages.space_col_used_pct),
            new SpaceColumn(SpaceMessages.space_col_max_size, SpaceColumn.Kind.BYTES_LIMIT, SpaceMessages.space_col_max_size_tip),
            SpaceColumn.percent(SpaceMessages.space_col_used_of_max_pct),
            SpaceColumn.text(SpaceMessages.space_col_autoextend));
        SpaceGrid dataFiles = new SpaceGrid(SpaceGrid.Kind.DATA_FILES, SpaceMessages.space_grid_data_files,
            SpaceColumn.count(SpaceMessages.space_col_file_id),
            SpaceColumn.text(SpaceMessages.space_col_tablespace),
            SpaceColumn.text(SpaceMessages.space_col_path),
            SpaceColumn.bytes(SpaceMessages.space_col_size),
            SpaceColumn.bytes(SpaceMessages.space_col_used),
            SpaceColumn.bytes(SpaceMessages.space_col_free),
            SpaceColumn.percent(SpaceMessages.space_col_used_pct),
            SpaceColumn.text(SpaceMessages.space_col_autoextend),
            SpaceColumn.bytes(SpaceMessages.space_col_increment),
            new SpaceColumn(SpaceMessages.space_col_max_size, SpaceColumn.Kind.BYTES_LIMIT, null),
            SpaceColumn.text(SpaceMessages.space_col_status));
        SpaceGrid tables = new SpaceGrid(SpaceGrid.Kind.TABLES, SpaceMessages.space_grid_tables,
            SpaceColumn.text(SpaceMessages.space_col_owner),
            SpaceColumn.text(SpaceMessages.space_col_table),
            new SpaceColumn(SpaceMessages.space_col_rows_est, SpaceColumn.Kind.COUNT, SpaceMessages.space_col_rows_stats_tip),
            SpaceColumn.bytes(SpaceMessages.space_col_data),
            SpaceColumn.bytes(SpaceMessages.space_col_index),
            SpaceColumn.bytes(SpaceMessages.space_col_lob),
            SpaceColumn.bytes(SpaceMessages.space_col_total));
        List<String> notices = new ArrayList<>();
        try {
            List<String> missing = new ArrayList<>();
            boolean fileViews = canReadAll(session, FILE_VIEWS, missing);
            // 11g+; without it the temporary tablespaces show their file sizes only
            boolean tempFree = canRead(session, TEMP_FREE_VIEW);
            boolean segmentViews = canReadAll(session, SEGMENT_VIEWS, missing);
            String user = AdminSql.queryString(session, "SELECT USER FROM DUAL"); //$NON-NLS-1$
            if (!missing.isEmpty()) {
                notices.add(NLS.bind(segmentViews ? SpaceMessages.space_oracle_missing_views
                    : SpaceMessages.space_oracle_missing_views_own_tables, String.join(", ", missing), user)); //$NON-NLS-1$
            }

            if (fileViews) {
                readFiles(session, databases, tablespaces, dataFiles, tempFree);
            } else {
                String reason = NLS.bind(SpaceMessages.space_oracle_unavailable, String.join(", ", FILE_VIEWS)); //$NON-NLS-1$
                databases.setUnavailable(reason);
                tablespaces.setUnavailable(reason);
                dataFiles.setUnavailable(reason);
            }

            List<String> scopes;
            String tableScope;
            if (segmentViews) {
                scopes = AdminSql.queryStrings(session, "SELECT DISTINCT owner FROM DBA_SEGMENTS ORDER BY owner"); //$NON-NLS-1$
                tableScope = scope != null && scopes.contains(scope) ? scope : null;
            } else {
                // The login's own schema only
                scopes = user == null ? List.of() : List.of(user);
                tableScope = user;
            }
            String[] parts = {
                segmentViews ? "DBA" : "USER", //$NON-NLS-1$ //$NON-NLS-2$
                segmentViews ? "s.owner" : "USER", //$NON-NLS-1$ //$NON-NLS-2$
                segmentViews && tableScope != null ? " AND x.owner = ?" : "", //$NON-NLS-1$ //$NON-NLS-2$
                segmentViews ? "s.owner" : "USER", //$NON-NLS-1$ //$NON-NLS-2$
                segmentViews ? "owner" : "table_owner", //$NON-NLS-1$ //$NON-NLS-2$
                segmentViews ? "l.owner" : "USER", //$NON-NLS-1$ //$NON-NLS-2$
                segmentViews ? "t.owner" : "USER" //$NON-NLS-1$ //$NON-NLS-2$
            };
            // Plain replacement: NLS.bind would treat quotes in the SQL
            String sql = TABLES_SQL;
            for (int i = 0; i < parts.length; i++) {
                sql = sql.replace("{" + i + "}", parts[i]); //$NON-NLS-1$ //$NON-NLS-2$
            }
            AdminSql.RowReader reader = rs -> tables.addRow(rs.getString(1), rs.getString(2), AdminSql.getLong(rs, 3),
                AdminSql.getLong(rs, 4), AdminSql.getLong(rs, 5), AdminSql.getLong(rs, 6), AdminSql.getLong(rs, 7));
            if (segmentViews && tableScope != null) {
                AdminSql.query(session, sql, reader, tableScope, tableLimit);
            } else {
                AdminSql.query(session, sql, reader, tableLimit);
            }
            return new SpaceReport(List.of(databases, tablespaces, dataFiles, tables), scopes, tableScope, notices);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    private static void readFiles(@NotNull JDBCSession session, @NotNull SpaceGrid databases, @NotNull SpaceGrid tablespaces,
                                  @NotNull SpaceGrid dataFiles, boolean tempFree) throws SQLException {
        // Permanent and undo tablespaces from their data files; temporary ones from DBA_TEMP_FREE_SPACE (or their temp files)
        String temp = tempFree
            ? "(SELECT tablespace_size FROM DBA_TEMP_FREE_SPACE x WHERE x.tablespace_name = t.tablespace_name), " //$NON-NLS-1$
                + "(SELECT free_space FROM DBA_TEMP_FREE_SPACE x WHERE x.tablespace_name = t.tablespace_name), " //$NON-NLS-1$
            : "(SELECT SUM(bytes) FROM DBA_TEMP_FILES x WHERE x.tablespace_name = t.tablespace_name), NULL, "; //$NON-NLS-1$
        AdminSql.query(session, "SELECT t.tablespace_name, t.contents, t.status, " //$NON-NLS-1$
            + "(SELECT SUM(bytes) FROM DBA_DATA_FILES f WHERE f.tablespace_name = t.tablespace_name), " //$NON-NLS-1$
            + "(SELECT SUM(bytes) FROM DBA_FREE_SPACE s WHERE s.tablespace_name = t.tablespace_name), " //$NON-NLS-1$
            + temp
            + "(SELECT SUM(CASE WHEN autoextensible = 'YES' THEN GREATEST(maxbytes, bytes) ELSE bytes END) FROM " //$NON-NLS-1$
            + "(SELECT tablespace_name, autoextensible, maxbytes, bytes FROM DBA_DATA_FILES UNION ALL " //$NON-NLS-1$
            + "SELECT tablespace_name, autoextensible, maxbytes, bytes FROM DBA_TEMP_FILES) f WHERE f.tablespace_name = t.tablespace_name), " //$NON-NLS-1$
            + "(SELECT MAX(autoextensible) FROM (SELECT tablespace_name, autoextensible FROM DBA_DATA_FILES UNION ALL " //$NON-NLS-1$
            + "SELECT tablespace_name, autoextensible FROM DBA_TEMP_FILES) f WHERE f.tablespace_name = t.tablespace_name) " //$NON-NLS-1$
            + "FROM DBA_TABLESPACES t ORDER BY t.tablespace_name", //$NON-NLS-1$
            rs -> {
                boolean temporary = "TEMPORARY".equals(rs.getString(2)); //$NON-NLS-1$
                Long size = AdminSql.getLong(rs, temporary ? 6 : 4);
                Long free = temporary ? AdminSql.getLong(rs, 7) : AdminSql.getLong(rs, 5);
                if (!temporary && size != null && free == null) {
                    // A full tablespace has no free extents
                    free = 0L;
                }
                Long used = size != null && free != null ? size - free : null;
                Long max = AdminSql.getLong(rs, 8);
                tablespaces.addRow(rs.getString(1), rs.getString(2), rs.getString(3), size, used, free, percent(used, size),
                    max, percent(used, max), yesNo(rs.getString(9)));
            });
        AdminSql.query(session, "SELECT f.file_id, f.tablespace_name, f.file_name, f.bytes, " //$NON-NLS-1$
            + "(SELECT SUM(s.bytes) FROM DBA_FREE_SPACE s WHERE s.file_id = f.file_id), f.autoextensible, f.increment_by * t.block_size, " //$NON-NLS-1$
            + "f.maxbytes, f.status, 'D' FROM DBA_DATA_FILES f JOIN DBA_TABLESPACES t ON t.tablespace_name = f.tablespace_name " //$NON-NLS-1$
            + "UNION ALL SELECT f.file_id, f.tablespace_name, f.file_name, f.bytes, NULL, f.autoextensible, f.increment_by * t.block_size, " //$NON-NLS-1$
            + "f.maxbytes, f.status, 'T' FROM DBA_TEMP_FILES f JOIN DBA_TABLESPACES t ON t.tablespace_name = f.tablespace_name " //$NON-NLS-1$
            + "ORDER BY 10, 1", //$NON-NLS-1$
            rs -> {
                Long size = AdminSql.getLong(rs, 4);
                boolean dataFile = "D".equals(rs.getString(10)); //$NON-NLS-1$
                Long free = AdminSql.getLong(rs, 5);
                if (dataFile && size != null && free == null) {
                    free = 0L;
                }
                Long used = size != null && free != null ? size - free : null;
                boolean autoextend = "YES".equals(rs.getString(6)); //$NON-NLS-1$
                dataFiles.addRow(AdminSql.getLong(rs, 1), rs.getString(2), rs.getString(3), size, used, free, percent(used, size),
                    yesNo(rs.getString(6)), autoextend ? AdminSql.getLong(rs, 7) : null,
                    autoextend ? AdminSql.getLong(rs, 8) : size, rs.getString(9));
            });
        AdminSql.query(session, "SELECT SYS_CONTEXT('USERENV', 'CON_NAME'), " //$NON-NLS-1$
            + "(SELECT SUM(bytes) FROM DBA_DATA_FILES), (SELECT SUM(bytes) FROM DBA_FREE_SPACE), (SELECT SUM(bytes) FROM DBA_TEMP_FILES) FROM DUAL", //$NON-NLS-1$
            rs -> {
                Long data = AdminSql.getLong(rs, 2);
                Long free = AdminSql.getLong(rs, 3);
                Long tempSize = AdminSql.getLong(rs, 4);
                databases.addRow(rs.getString(1), data, data != null && free != null ? data - free : null, free, tempSize,
                    data == null ? null : data + (tempSize == null ? 0 : tempSize));
            });
    }

    @Nullable
    private static Double percent(@Nullable Long part, @Nullable Long whole) {
        return part == null || whole == null || whole == 0 ? null : part * 100.0 / whole;
    }

    @Nullable
    private static String yesNo(@Nullable String value) {
        return value == null ? null : "YES".equals(value) ? SpaceMessages.space_value_yes : SpaceMessages.space_value_no; //$NON-NLS-1$
    }

    private static boolean canReadAll(@NotNull JDBCSession session, @NotNull String[] views, @NotNull List<String> missing) {
        boolean all = true;
        for (String view : views) {
            if (!canRead(session, view)) {
                missing.add(view);
                all = false;
            }
        }
        return all;
    }

    private static boolean canRead(@NotNull JDBCSession session, @NotNull String view) {
        return AdminSql.probe(session, "SELECT 1 FROM " + view + " WHERE 1 = 0") == null; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
