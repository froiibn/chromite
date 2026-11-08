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
package com.seizia.chromite.model.admin.replication;

import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.impls.PostgreServerPostgreSQL;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.admin.AdminGrid;
import com.seizia.chromite.model.admin.altibase.AltibaseReplicationDialect;
import com.seizia.chromite.model.admin.mssql.SQLServerReplicationDialect;
import com.seizia.chromite.model.admin.mysql.MySQLReplicationDialect;
import com.seizia.chromite.model.admin.oracle.OracleReplicationDialect;
import com.seizia.chromite.model.admin.postgresql.PostgreReplicationDialect;
import com.seizia.chromite.model.admin.space.SpaceColumn;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 복제 상태(DB 관리 축 6, 읽기 전용)의 DB별 부분.
 *
 * 설계: 공간 사용량·유지 관리와 같이 DB마다 하위 클래스 하나를 둔다. DBeaver에는 이 화면이 없다.
 * <ul>
 *     <li>읽기 전용이다: 복제를 시작·멈추거나 슬롯을 지우는 명령은 없다(Altibase 복제의 시작·중지·동기화는 Object Explorer의 복제 노드 명령).</li>
 *     <li>복제가 구성되지 않은 서버는 오류가 아니다. 요약에 "구성되지 않음"을 보이고 표는 빈 표 문구({@code AdminGrid#getEmptyText()})로 같은 말을 한다.</li>
 *     <li>권한을 먼저 본다: 읽을 수 없는 표는 질의하지 않거나(뷰 권한) 서버가 거부한 이유를 표의 "읽을 수 없음"으로 보이고, 무엇을 받아야 하는지 안내한다.</li>
 *     <li>Oracle은 Diagnostics/Tuning Pack이 필요한 뷰를 쓰지 않는다({@code V$DATABASE}, {@code V$ARCHIVE_DEST_STATUS}, {@code V$DATAGUARD_STATS}는 팩이 필요 없다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 6 복제 상태 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-08 Altibase({@link AltibaseReplicationDialect}) (docs/chromite/altibase.md "복제 시작·중지·동기화")
 */
public abstract class ReplicationDialect {

    /**
     * Replication status support of a data source.
     *
     * @return null if the database is not supported
     */
    @Nullable
    public static ReplicationDialect forDataSource(@NotNull DBPDataSource dataSource) {
        if (dataSource instanceof SQLServerDataSource sqlServer) {
            return new SQLServerReplicationDialect(sqlServer);
        }
        // pg_stat_replication with *_lsn columns and pg_current_wal_lsn(): PostgreSQL 10+
        if (dataSource instanceof PostgreDataSource postgre && postgre.getServerType() instanceof PostgreServerPostgreSQL
            && postgre.isServerVersionAtLeast(10, 0)) {
            return new PostgreReplicationDialect(postgre);
        }
        if (dataSource instanceof MySQLDataSource mysql) {
            return new MySQLReplicationDialect(mysql);
        }
        if (dataSource instanceof OracleDataSource oracle) {
            return new OracleReplicationDialect(oracle);
        }
        if (dataSource instanceof AltibaseDataSource altibase) {
            return new AltibaseReplicationDialect(altibase);
        }
        return null;
    }

    /**
     * Reads the replication status.
     */
    @NotNull
    public abstract ReplicationReport read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session) throws DBException;

    /**
     * The summary grid: one row per fact (property, value).
     */
    @NotNull
    protected static AdminGrid createSummary() {
        return new AdminGrid("summary", ReplicationMessages.repl_grid_summary, //$NON-NLS-1$
            SpaceColumn.text(ReplicationMessages.repl_col_property),
            SpaceColumn.text(ReplicationMessages.repl_col_value));
    }

    /**
     * Rows of a query as maps by column label (case-insensitive), for statements whose columns differ between versions
     * ({@code SHOW REPLICA STATUS} / {@code SHOW SLAVE STATUS}).
     */
    @NotNull
    protected static List<Map<String, String>> queryRows(@NotNull JDBCSession session, @NotNull String sql) throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (JDBCPreparedStatement statement = session.prepareStatement(sql);
             JDBCResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData meta = resultSet.getMetaData();
            while (resultSet.next()) {
                Map<String, String> row = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i), resultSet.getString(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * The first value present among column names (old and new names of the same column).
     */
    @Nullable
    protected static String value(@NotNull Map<String, String> row, @NotNull String... names) {
        for (String name : names) {
            if (row.containsKey(name)) {
                return row.get(name);
            }
        }
        return null;
    }
}
