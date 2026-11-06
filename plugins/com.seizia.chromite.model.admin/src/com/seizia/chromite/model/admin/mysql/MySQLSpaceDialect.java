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
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.model.admin.AdminSql;
import com.seizia.chromite.model.admin.space.ServerSpaceDialect;
import com.seizia.chromite.model.admin.space.SpaceColumn;
import com.seizia.chromite.model.admin.space.SpaceGrid;
import com.seizia.chromite.model.admin.space.SpaceMessages;
import com.seizia.chromite.model.admin.space.SpaceReport;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL·MariaDB 공간 사용량: 스키마 크기와 큰 테이블(데이터, 인덱스, 빈 공간, 엔진).
 *
 * 설계:
 * <ul>
 *     <li>크기는 {@code information_schema.TABLES}의 DATA_LENGTH, INDEX_LENGTH, DATA_FREE다(InnoDB는 file-per-table 파일 안의 빈 공간,
 *     공유 테이블스페이스는 그 테이블스페이스의 빈 공간). 행 수는 엔진의 추정치다. MySQL 8은 이 값을 통계 캐시
 *     ({@code information_schema_stats_expiry}, 기본 하루)에서 읽는다. 세션 변수를 바꾸지 않으려고 캐시 값을 그대로 보인다.</li>
 *     <li>information_schema는 로그인이 권한을 가진 객체만 보인다. 전역 SELECT 권한이 없으면 일부만 보인다고 안내한다
 *     ({@code USER_PRIVILEGES}에서 현재 계정의 전역 권한을 본다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 공간 사용량 (docs/chromite/swing-admin-tools.md)
 */
public class MySQLSpaceDialect extends ServerSpaceDialect {

    // 'user'@'host' as USER_PRIVILEGES.GRANTEE writes the current account
    private static final String CURRENT_GRANTEE = "CONCAT('''', SUBSTRING_INDEX(CURRENT_USER(), '@', 1), '''@''', " //$NON-NLS-1$
        + "SUBSTRING_INDEX(CURRENT_USER(), '@', -1), '''')"; //$NON-NLS-1$

    @NotNull
    private final MySQLDataSource dataSource;

    public MySQLSpaceDialect(@NotNull MySQLDataSource dataSource) {
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
        SpaceGrid schemas = new SpaceGrid(SpaceGrid.Kind.DATABASES, SpaceMessages.space_grid_schemas,
            SpaceColumn.text(SpaceMessages.space_col_schema),
            SpaceColumn.count(SpaceMessages.space_col_tables),
            new SpaceColumn(SpaceMessages.space_col_rows_est, SpaceColumn.Kind.COUNT, SpaceMessages.space_col_rows_est_tip),
            SpaceColumn.bytes(SpaceMessages.space_col_data),
            SpaceColumn.bytes(SpaceMessages.space_col_index),
            new SpaceColumn(SpaceMessages.space_col_free, SpaceColumn.Kind.BYTES, SpaceMessages.space_col_data_free_tip),
            SpaceColumn.bytes(SpaceMessages.space_col_total));
        SpaceGrid tables = new SpaceGrid(SpaceGrid.Kind.TABLES, SpaceMessages.space_grid_tables,
            SpaceColumn.text(SpaceMessages.space_col_schema),
            SpaceColumn.text(SpaceMessages.space_col_table),
            SpaceColumn.text(SpaceMessages.space_col_engine),
            SpaceColumn.text(SpaceMessages.space_col_row_format),
            new SpaceColumn(SpaceMessages.space_col_rows_est, SpaceColumn.Kind.COUNT, SpaceMessages.space_col_rows_est_tip),
            SpaceColumn.bytes(SpaceMessages.space_col_data),
            SpaceColumn.bytes(SpaceMessages.space_col_index),
            new SpaceColumn(SpaceMessages.space_col_free, SpaceColumn.Kind.BYTES, SpaceMessages.space_col_data_free_tip),
            SpaceColumn.bytes(SpaceMessages.space_col_total));
        List<String> notices = new ArrayList<>();
        try {
            String globalSelect = AdminSql.queryString(session, "SELECT COUNT(*) FROM information_schema.USER_PRIVILEGES " //$NON-NLS-1$
                + "WHERE GRANTEE = " + CURRENT_GRANTEE + " AND PRIVILEGE_TYPE = 'SELECT'"); //$NON-NLS-1$ //$NON-NLS-2$
            if ("0".equals(globalSelect)) { //$NON-NLS-1$
                notices.add(SpaceMessages.space_mysql_partial);
            }
            List<String> scopes = new ArrayList<>();
            AdminSql.query(session, "SELECT s.SCHEMA_NAME, COUNT(t.TABLE_NAME), SUM(t.TABLE_ROWS), SUM(t.DATA_LENGTH), SUM(t.INDEX_LENGTH), " //$NON-NLS-1$
                + "SUM(t.DATA_FREE), SUM(t.DATA_LENGTH + t.INDEX_LENGTH) FROM information_schema.SCHEMATA s " //$NON-NLS-1$
                + "LEFT JOIN information_schema.TABLES t ON t.TABLE_SCHEMA = s.SCHEMA_NAME AND t.TABLE_TYPE = 'BASE TABLE' " //$NON-NLS-1$
                + "GROUP BY s.SCHEMA_NAME ORDER BY s.SCHEMA_NAME", //$NON-NLS-1$
                rs -> {
                    scopes.add(rs.getString(1));
                    schemas.addRow(rs.getString(1), AdminSql.getLong(rs, 2), AdminSql.getLong(rs, 3), AdminSql.getLong(rs, 4),
                        AdminSql.getLong(rs, 5), AdminSql.getLong(rs, 6), AdminSql.getLong(rs, 7));
                });
            String tableScope = scope != null && scopes.contains(scope) ? scope : null;
            String sql = "SELECT TABLE_SCHEMA, TABLE_NAME, ENGINE, ROW_FORMAT, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH, DATA_FREE, " //$NON-NLS-1$
                + "DATA_LENGTH + INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_TYPE = 'BASE TABLE'" //$NON-NLS-1$
                + (tableScope != null ? " AND TABLE_SCHEMA = ?" : "") //$NON-NLS-1$ //$NON-NLS-2$
                + " ORDER BY DATA_LENGTH + INDEX_LENGTH DESC LIMIT ?"; //$NON-NLS-1$
            AdminSql.RowReader reader = rs -> tables.addRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                AdminSql.getLong(rs, 5), AdminSql.getLong(rs, 6), AdminSql.getLong(rs, 7), AdminSql.getLong(rs, 8), AdminSql.getLong(rs, 9));
            if (tableScope != null) {
                AdminSql.query(session, sql, reader, tableScope, tableLimit);
            } else {
                AdminSql.query(session, sql, reader, tableLimit);
            }
            return new SpaceReport(List.of(schemas, tables), scopes, tableScope, notices);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }
}
