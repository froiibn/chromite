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
 * PostgreSQL 공간 사용량: 데이터베이스와 테이블스페이스 크기, 연결한 데이터베이스의 큰 테이블(데이터, TOAST, 인덱스).
 *
 * 설계:
 * <ul>
 *     <li>크기 함수의 권한 규칙을 그대로 따른다: {@code pg_database_size}는 그 데이터베이스의 CONNECT 권한, {@code pg_tablespace_size}는
 *     그 테이블스페이스의 CREATE 권한(현재 데이터베이스의 기본 테이블스페이스는 누구나)이 필요하고, pg_read_all_stats(10+, pg_monitor 포함)는
 *     둘 다 허용한다. 권한이 없으면 함수가 오류를 내고 트랜잭션을 깨므로(수동 커밋 연결) 미리 확인해 크기를 비우고 안내한다.</li>
 *     <li>테이블은 연결한 데이터베이스의 것만 읽을 수 있다(PostgreSQL 카탈로그는 데이터베이스마다 따로다). 데이터 = {@code pg_table_size} − TOAST,
 *     TOAST = TOAST 테이블과 그 인덱스, 인덱스 = {@code pg_indexes_size}, 합계 = {@code pg_total_relation_size}다.
 *     행 수는 통계의 추정치({@code reltuples})이고 죽은 행은 {@code pg_stat_all_tables.n_dead_tup}이다(파일 안의 빈 공간을 대신 보인다).</li>
 *     <li>PostgreSQL은 디스크의 남은 공간을 SQL로 알려 주지 않아 빈 공간 열이 없다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 공간 사용량 (docs/chromite/swing-admin-tools.md)
 */
public class PostgreSpaceDialect extends ServerSpaceDialect {

    @NotNull
    private final PostgreDataSource dataSource;

    public PostgreSpaceDialect(@NotNull PostgreDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public String getScopeLabel() {
        return SpaceMessages.space_scope_schema;
    }

    @NotNull
    @Override
    public SpaceReport read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @Nullable String scope, int tableLimit)
        throws DBException {
        // pg_read_all_stats exists from PostgreSQL 10; pg_has_role fails on a role that does not exist
        String readAllStats = dataSource.isServerVersionAtLeast(10, 0) ? "pg_has_role('pg_read_all_stats', 'USAGE')" : "false"; //$NON-NLS-1$ //$NON-NLS-2$
        SpaceGrid databases = new SpaceGrid(SpaceGrid.Kind.DATABASES, SpaceMessages.space_grid_databases,
            SpaceColumn.text(SpaceMessages.space_col_database),
            SpaceColumn.text(SpaceMessages.space_col_owner),
            SpaceColumn.text(SpaceMessages.space_col_encoding),
            SpaceColumn.text(SpaceMessages.space_col_tablespace),
            SpaceColumn.bytes(SpaceMessages.space_col_size));
        SpaceGrid tablespaces = new SpaceGrid(SpaceGrid.Kind.TABLESPACES, SpaceMessages.space_grid_tablespaces,
            SpaceColumn.text(SpaceMessages.space_col_tablespace),
            SpaceColumn.text(SpaceMessages.space_col_owner),
            SpaceColumn.text(SpaceMessages.space_col_location),
            SpaceColumn.bytes(SpaceMessages.space_col_size));
        String currentDatabase;
        try {
            currentDatabase = AdminSql.queryString(session, "SELECT current_database()"); //$NON-NLS-1$
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
        // Tables of the connected database only: the title says which
        SpaceGrid tables = new SpaceGrid(SpaceGrid.Kind.TABLES, NLS.bind(SpaceMessages.space_grid_tables_in, currentDatabase),
            SpaceColumn.text(SpaceMessages.space_col_schema),
            SpaceColumn.text(SpaceMessages.space_col_table),
            SpaceColumn.text(SpaceMessages.space_col_kind),
            new SpaceColumn(SpaceMessages.space_col_rows_est, SpaceColumn.Kind.COUNT, SpaceMessages.space_col_rows_est_tip),
            new SpaceColumn(SpaceMessages.space_col_dead_rows, SpaceColumn.Kind.COUNT, SpaceMessages.space_col_dead_rows_tip),
            SpaceColumn.bytes(SpaceMessages.space_col_data),
            new SpaceColumn(SpaceMessages.space_col_toast, SpaceColumn.Kind.BYTES, SpaceMessages.space_col_toast_tip),
            SpaceColumn.bytes(SpaceMessages.space_col_index),
            SpaceColumn.bytes(SpaceMessages.space_col_total));
        List<String> notices = new ArrayList<>();
        try {
            boolean[] hidden = {false, false};
            AdminSql.query(session, "SELECT d.datname, pg_get_userbyid(d.datdba), pg_encoding_to_char(d.encoding), t.spcname, " //$NON-NLS-1$
                + "CASE WHEN has_database_privilege(d.oid, 'CONNECT') OR " + readAllStats + " THEN pg_database_size(d.oid) END " //$NON-NLS-1$ //$NON-NLS-2$
                + "FROM pg_database d LEFT JOIN pg_tablespace t ON t.oid = d.dattablespace WHERE NOT d.datistemplate ORDER BY d.datname", //$NON-NLS-1$
                rs -> {
                    Long size = AdminSql.getLong(rs, 5);
                    hidden[0] |= size == null;
                    databases.addRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), size);
                });
            AdminSql.query(session, "SELECT t.spcname, pg_get_userbyid(t.spcowner), pg_tablespace_location(t.oid), " //$NON-NLS-1$
                + "CASE WHEN has_tablespace_privilege(t.oid, 'CREATE') OR " + readAllStats //$NON-NLS-1$
                + " OR t.oid = (SELECT dattablespace FROM pg_database WHERE datname = current_database()) THEN pg_tablespace_size(t.oid) END " //$NON-NLS-1$
                + "FROM pg_tablespace t ORDER BY t.spcname", //$NON-NLS-1$
                rs -> {
                    Long size = AdminSql.getLong(rs, 4);
                    hidden[1] |= size == null;
                    tablespaces.addRow(rs.getString(1), rs.getString(2), rs.getString(3), size);
                });
            if (hidden[0]) {
                notices.add(SpaceMessages.space_postgres_database_size);
            }
            if (hidden[1]) {
                notices.add(SpaceMessages.space_postgres_tablespace_size);
            }

            List<String> scopes = AdminSql.queryStrings(session, "SELECT nspname FROM pg_namespace " //$NON-NLS-1$
                + "WHERE nspname NOT LIKE 'pg\\_toast%' AND nspname NOT LIKE 'pg\\_temp\\_%' ORDER BY nspname"); //$NON-NLS-1$
            String tableScope = scope != null && scopes.contains(scope) ? scope : null;
            String sql = "SELECT n.nspname, c.relname, c.relkind, c.reltuples::bigint, s.n_dead_tup, " //$NON-NLS-1$
                + "pg_table_size(c.oid) - COALESCE(pg_total_relation_size(NULLIF(c.reltoastrelid, 0)), 0), " //$NON-NLS-1$
                + "COALESCE(pg_total_relation_size(NULLIF(c.reltoastrelid, 0)), 0), pg_indexes_size(c.oid), pg_total_relation_size(c.oid) " //$NON-NLS-1$
                + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace LEFT JOIN pg_stat_all_tables s ON s.relid = c.oid " //$NON-NLS-1$
                + "WHERE c.relkind IN ('r', 'm')" + (tableScope != null ? " AND n.nspname = ?" : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " ORDER BY 9 DESC LIMIT ?"; //$NON-NLS-1$
            AdminSql.RowReader reader = rs -> {
                Long rows = AdminSql.getLong(rs, 4);
                // -1: never analyzed (PostgreSQL 14+)
                tables.addRow(rs.getString(1), rs.getString(2),
                    "m".equals(rs.getString(3)) ? SpaceMessages.space_value_materialized_view : SpaceMessages.space_value_table, //$NON-NLS-1$
                    rows != null && rows < 0 ? null : rows, AdminSql.getLong(rs, 5),
                    AdminSql.getLong(rs, 6), AdminSql.getLong(rs, 7), AdminSql.getLong(rs, 8), AdminSql.getLong(rs, 9));
            };
            if (tableScope != null) {
                AdminSql.query(session, sql, reader, tableScope, tableLimit);
            } else {
                AdminSql.query(session, sql, reader, tableLimit);
            }
            return new SpaceReport(List.of(databases, tablespaces, tables), scopes, tableScope, notices);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }
}
