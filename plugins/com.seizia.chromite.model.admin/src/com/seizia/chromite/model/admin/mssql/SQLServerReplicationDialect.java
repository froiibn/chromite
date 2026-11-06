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
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.model.admin.AdminGrid;
import com.seizia.chromite.model.admin.AdminSql;
import com.seizia.chromite.model.admin.replication.ReplicationDialect;
import com.seizia.chromite.model.admin.replication.ReplicationMessages;
import com.seizia.chromite.model.admin.replication.ReplicationReport;
import com.seizia.chromite.model.admin.space.SpaceColumn;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL Server 복제 상태: Always On 가용성 그룹(복제본의 역할·연결·동기화 상태, 데이터베이스마다 동기화 상태와 로그 전송·다시 실행 큐)과
 * 트랜잭션·병합 복제의 기본 정보(게시된 데이터베이스, 배포 데이터베이스).
 *
 * 설계:
 * <ul>
 *     <li>Always On은 {@code SERVERPROPERTY('IsHadrEnabled')}가 1일 때만 {@code sys.dm_hadr_*}를 읽는다. 0이면 "구성되지 않음"이다.</li>
 *     <li>{@code sys.dm_hadr_*}는 VIEW SERVER STATE(2022+는 VIEW SERVER PERFORMANCE STATE로도 됨)가 필요하다. 없으면 표를 "읽을 수 없음"으로 두고 안내한다.</li>
 *     <li>복제는 {@code sys.databases}의 is_published, is_merge_published, is_distributor만 본다(기본 정보). 구독자 쪽 상태와 에이전트 기록은
 *     배포 데이터베이스의 복제 모니터 프로시저가 필요해 범위 밖이다.</li>
 *     <li>큐 크기는 서버가 KB로 주는 값을 바이트로 바꿔 크기 열로 보인다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 6 복제 상태 (docs/chromite/swing-admin-tools.md)
 */
public class SQLServerReplicationDialect extends ReplicationDialect {

    private static final long KB = 1024;

    @NotNull
    private final SQLServerDataSource dataSource;

    public SQLServerReplicationDialect(@NotNull SQLServerDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public ReplicationReport read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session) throws DBException {
        try {
            List<String> notices = new ArrayList<>();
            boolean hadr = "1".equals(AdminSql.queryString(session, "SELECT CAST(SERVERPROPERTY('IsHadrEnabled') AS int)")); //$NON-NLS-1$ //$NON-NLS-2$
            boolean serverState = "1".equals(AdminSql.queryString(session, //$NON-NLS-1$
                "SELECT CASE WHEN HAS_PERMS_BY_NAME(NULL, NULL, 'VIEW SERVER STATE') = 1 " //$NON-NLS-1$
                    + "OR HAS_PERMS_BY_NAME(NULL, NULL, 'VIEW SERVER PERFORMANCE STATE') = 1 THEN 1 ELSE 0 END")); //$NON-NLS-1$

            AdminGrid replicas = new AdminGrid("ag_replicas", ReplicationMessages.repl_grid_ms_replicas, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_group),
                SpaceColumn.text(ReplicationMessages.repl_col_replica),
                SpaceColumn.text(ReplicationMessages.repl_col_role),
                SpaceColumn.text(ReplicationMessages.repl_col_connected),
                SpaceColumn.text(ReplicationMessages.repl_col_sync_health),
                SpaceColumn.text(ReplicationMessages.repl_col_availability_mode),
                SpaceColumn.text(ReplicationMessages.repl_col_failover_mode));
            AdminGrid databases = new AdminGrid("ag_databases", ReplicationMessages.repl_grid_ms_databases, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_group),
                SpaceColumn.text(ReplicationMessages.repl_col_replica),
                SpaceColumn.text(ReplicationMessages.repl_col_database),
                SpaceColumn.text(ReplicationMessages.repl_col_sync_state),
                SpaceColumn.text(ReplicationMessages.repl_col_sync_health),
                SpaceColumn.text(ReplicationMessages.repl_col_suspended),
                new SpaceColumn(ReplicationMessages.repl_col_send_queue, SpaceColumn.Kind.BYTES, ReplicationMessages.repl_col_send_queue_tip),
                new SpaceColumn(ReplicationMessages.repl_col_redo_queue, SpaceColumn.Kind.BYTES, ReplicationMessages.repl_col_redo_queue_tip),
                SpaceColumn.text(ReplicationMessages.repl_col_last_commit));
            if (!hadr) {
                replicas.setEmptyText(ReplicationMessages.repl_ms_hadr_disabled);
                databases.setEmptyText(ReplicationMessages.repl_ms_hadr_disabled);
            } else if (!serverState) {
                replicas.setUnavailable(ReplicationMessages.repl_ms_denied_server_state);
                databases.setUnavailable(ReplicationMessages.repl_ms_denied_server_state);
                notices.add(ReplicationMessages.repl_ms_denied_server_state);
            } else {
                replicas.setEmptyText(ReplicationMessages.repl_ms_no_groups);
                databases.setEmptyText(ReplicationMessages.repl_ms_no_groups);
                AdminSql.query(session, "SELECT ag.name, ar.replica_server_name, ars.role_desc, ars.connected_state_desc, " //$NON-NLS-1$
                    + "ars.synchronization_health_desc, ar.availability_mode_desc, ar.failover_mode_desc " //$NON-NLS-1$
                    + "FROM sys.availability_groups ag JOIN sys.availability_replicas ar ON ar.group_id = ag.group_id " //$NON-NLS-1$
                    + "LEFT JOIN sys.dm_hadr_availability_replica_states ars ON ars.replica_id = ar.replica_id " //$NON-NLS-1$
                    + "ORDER BY ag.name, ar.replica_server_name", //$NON-NLS-1$
                    rs -> replicas.addRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7)));
                AdminSql.query(session, "SELECT ag.name, ar.replica_server_name, DB_NAME(drs.database_id), drs.synchronization_state_desc, " //$NON-NLS-1$
                    + "drs.synchronization_health_desc, CASE WHEN drs.is_suspended = 1 THEN drs.suspend_reason_desc END, " //$NON-NLS-1$
                    + "drs.log_send_queue_size, drs.redo_queue_size, CONVERT(varchar(19), drs.last_commit_time, 120) " //$NON-NLS-1$
                    + "FROM sys.dm_hadr_database_replica_states drs " //$NON-NLS-1$
                    + "JOIN sys.availability_replicas ar ON ar.replica_id = drs.replica_id " //$NON-NLS-1$
                    + "JOIN sys.availability_groups ag ON ag.group_id = drs.group_id " //$NON-NLS-1$
                    + "ORDER BY ag.name, ar.replica_server_name, DB_NAME(drs.database_id)", rs -> { //$NON-NLS-1$
                        Long sendQueue = AdminSql.getLong(rs, 7);
                        Long redoQueue = AdminSql.getLong(rs, 8);
                        databases.addRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            sendQueue == null ? null : sendQueue * KB, redoQueue == null ? null : redoQueue * KB, rs.getString(9));
                    });
            }

            AdminGrid published = new AdminGrid("replication", ReplicationMessages.repl_grid_ms_replication, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_database),
                SpaceColumn.text(ReplicationMessages.repl_col_published),
                SpaceColumn.text(ReplicationMessages.repl_col_merge_published),
                SpaceColumn.text(ReplicationMessages.repl_col_distributor),
                SpaceColumn.text(ReplicationMessages.repl_col_log_reuse_wait));
            published.setDescription(ReplicationMessages.repl_ms_replication_desc);
            published.setEmptyText(ReplicationMessages.repl_ms_no_replication);
            List<String> distributors = new ArrayList<>();
            AdminSql.query(session, "SELECT name, is_published, is_merge_published, is_distributor, log_reuse_wait_desc FROM sys.databases " //$NON-NLS-1$
                + "WHERE is_published = 1 OR is_merge_published = 1 OR is_distributor = 1 ORDER BY name", rs -> { //$NON-NLS-1$
                    if (rs.getBoolean(4)) {
                        distributors.add(rs.getString(1));
                    }
                    published.addRow(rs.getString(1), yesNo(rs.getBoolean(2)), yesNo(rs.getBoolean(3)), yesNo(rs.getBoolean(4)), rs.getString(5));
                });

            AdminGrid summary = createSummary();
            summary.addRow(ReplicationMessages.repl_row_always_on, !hadr ? ReplicationMessages.repl_not_configured
                : serverState ? NLS.bind(ReplicationMessages.repl_ms_groups, countDistinct(replicas)) : ReplicationMessages.repl_ms_enabled);
            summary.addRow(ReplicationMessages.repl_row_replication, published.getRows().isEmpty() ? ReplicationMessages.repl_not_configured
                : NLS.bind(ReplicationMessages.repl_ms_replication_databases, published.getRows().size()));
            summary.addRow(ReplicationMessages.repl_row_distributor, distributors.isEmpty() ? ReplicationMessages.repl_ms_no_distributor
                : String.join(", ", distributors)); //$NON-NLS-1$
            summary.addRow("IsHadrEnabled", hadr ? "1" : "0"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (hadr) {
                AdminSql.query(session, "SELECT CAST(SERVERPROPERTY('HadrManagerStatus') AS nvarchar(10))", //$NON-NLS-1$
                    rs -> summary.addRow("HadrManagerStatus", rs.getString(1))); //$NON-NLS-1$
            }
            return new ReplicationReport(List.of(summary, replicas, databases, published), notices);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    private static int countDistinct(@NotNull AdminGrid replicas) {
        return (int) replicas.getRows().stream().map(row -> row[0]).distinct().count();
    }

    @NotNull
    private static String yesNo(boolean value) {
        return value ? ReplicationMessages.repl_value_yes : ReplicationMessages.repl_value_no;
    }
}
