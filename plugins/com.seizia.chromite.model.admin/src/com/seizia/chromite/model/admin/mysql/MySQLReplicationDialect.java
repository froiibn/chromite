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
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
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
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MySQL·MariaDB 복제 상태: 복제본 연결({@code SHOW REPLICA STATUS}/{@code SHOW SLAVE STATUS}, MariaDB {@code SHOW ALL SLAVES STATUS}),
 * 바이너리 로그 상태와 GTID, 연결된 복제본({@code SHOW REPLICAS}/{@code SHOW SLAVE HOSTS}).
 *
 * 설계:
 * <ul>
 *     <li>문장과 열 이름이 버전마다 다르다(MySQL 8.0.22에서 SLAVE → REPLICA, Master → Source; 8.2에서 SHOW MASTER STATUS → SHOW BINARY LOG STATUS).
 *     패치 번호까지 버전을 맞추는 대신 새 문장을 먼저 실행해 보고 문법 오류(1064)면 옛 문장을 쓴다. 열은 새 이름과 옛 이름을 차례로 찾는다.
 *     MySQL·MariaDB는 실패한 문장이 트랜잭션을 깨지 않는다.</li>
 *     <li>MariaDB는 다중 원본 복제의 모든 연결을 보이는 {@code SHOW ALL SLAVES STATUS}를 쓴다(이름 있는 연결도 보인다).</li>
 *     <li>권한: 복제본 상태와 바이너리 로그 상태는 REPLICATION CLIENT(MariaDB 10.5.2+는 REPLICA MONITOR·BINLOG MONITOR), 연결된 복제본 목록은
 *     REPLICATION SLAVE(MariaDB 10.5.2+는 REPLICATION MASTER ADMIN)가 필요하다. 서버가 거부하면(1227) 그 표를 "읽을 수 없음"으로 두고 안내한다.</li>
 *     <li>요약에는 server_id, log_bin, binlog_format, GTID 설정과 현재 위치(MySQL gtid_executed, MariaDB gtid_current_pos)를 보인다
 *     ({@code SHOW GLOBAL VARIABLES}: 없는 변수는 행이 없을 뿐 오류가 아니다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 6 복제 상태 (docs/chromite/swing-admin-tools.md)
 */
public class MySQLReplicationDialect extends ReplicationDialect {

    private static final Log log = Log.getLog(MySQLReplicationDialect.class);

    private static final int ER_PARSE_ERROR = 1064;
    private static final int ER_SPECIFIC_ACCESS_DENIED = 1227;

    @NotNull
    private final MySQLDataSource dataSource;

    public MySQLReplicationDialect(@NotNull MySQLDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public ReplicationReport read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session) throws DBException {
        boolean mariaDB = dataSource.isMariaDB();
        List<String> notices = new ArrayList<>();
        try {
            AdminGrid replicas = new AdminGrid("sources", ReplicationMessages.repl_grid_my_sources, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_channel),
                SpaceColumn.text(ReplicationMessages.repl_col_source),
                SpaceColumn.text(ReplicationMessages.repl_col_io_thread),
                SpaceColumn.text(ReplicationMessages.repl_col_sql_thread),
                new SpaceColumn(ReplicationMessages.repl_col_seconds_behind, SpaceColumn.Kind.COUNT, ReplicationMessages.repl_col_seconds_behind_tip),
                SpaceColumn.text(ReplicationMessages.repl_col_source_log),
                SpaceColumn.text(ReplicationMessages.repl_col_exec_position),
                SpaceColumn.text(ReplicationMessages.repl_col_gtid),
                SpaceColumn.text(ReplicationMessages.repl_col_last_io_error),
                SpaceColumn.text(ReplicationMessages.repl_col_last_sql_error));
            replicas.setEmptyText(ReplicationMessages.repl_my_no_source);
            List<Map<String, String>> sourceRows = null;
            try {
                sourceRows = mariaDB ? queryRows(session, "SHOW ALL SLAVES STATUS") //$NON-NLS-1$
                    : queryFirst(session, "SHOW REPLICA STATUS", "SHOW SLAVE STATUS"); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (SQLException e) {
                String reason = denied(e, mariaDB ? ReplicationMessages.repl_my_denied_status_maria : ReplicationMessages.repl_my_denied_status);
                replicas.setUnavailable(reason);
                notices.add(reason);
            }
            if (sourceRows != null) {
                for (Map<String, String> row : sourceRows) {
                    String host = value(row, "Source_Host", "Master_Host"); //$NON-NLS-1$ //$NON-NLS-2$
                    String port = value(row, "Source_Port", "Master_Port"); //$NON-NLS-1$ //$NON-NLS-2$
                    String behind = value(row, "Seconds_Behind_Source", "Seconds_Behind_Master"); //$NON-NLS-1$ //$NON-NLS-2$
                    replicas.addRow(
                        value(row, "Connection_name", "Channel_Name"), //$NON-NLS-1$ //$NON-NLS-2$
                        host == null ? null : host + ":" + port, //$NON-NLS-1$
                        value(row, "Replica_IO_Running", "Slave_IO_Running"), //$NON-NLS-1$ //$NON-NLS-2$
                        value(row, "Replica_SQL_Running", "Slave_SQL_Running"), //$NON-NLS-1$ //$NON-NLS-2$
                        parseLong(behind),
                        value(row, "Relay_Source_Log_File", "Relay_Master_Log_File"), //$NON-NLS-1$ //$NON-NLS-2$
                        value(row, "Exec_Source_Log_Pos", "Exec_Master_Log_Pos"), //$NON-NLS-1$ //$NON-NLS-2$
                        value(row, "Gtid_IO_Pos", "Retrieved_Gtid_Set"), //$NON-NLS-1$ //$NON-NLS-2$
                        error(value(row, "Last_IO_Errno"), value(row, "Last_IO_Error")), //$NON-NLS-1$ //$NON-NLS-2$
                        error(value(row, "Last_SQL_Errno"), value(row, "Last_SQL_Error"))); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }

            AdminGrid connected = new AdminGrid("replicas", ReplicationMessages.repl_grid_my_replicas, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_server_id),
                SpaceColumn.text(ReplicationMessages.repl_col_host),
                SpaceColumn.text(ReplicationMessages.repl_col_port),
                SpaceColumn.text(ReplicationMessages.repl_col_source_id));
            connected.setDescription(ReplicationMessages.repl_my_replicas_desc);
            connected.setEmptyText(ReplicationMessages.repl_my_no_replicas);
            try {
                List<Map<String, String>> rows = mariaDB ? queryRows(session, "SHOW SLAVE HOSTS") //$NON-NLS-1$
                    : queryFirst(session, "SHOW REPLICAS", "SHOW SLAVE HOSTS"); //$NON-NLS-1$ //$NON-NLS-2$
                for (Map<String, String> row : rows) {
                    connected.addRow(value(row, "Server_id"), value(row, "Host"), value(row, "Port"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        value(row, "Source_id", "Master_id")); //$NON-NLS-1$ //$NON-NLS-2$
                }
            } catch (SQLException e) {
                connected.setUnavailable(denied(e, mariaDB ? ReplicationMessages.repl_my_denied_hosts_maria : ReplicationMessages.repl_my_denied_hosts));
            }

            AdminGrid summary = createSummary();
            boolean replica = sourceRows != null && !sourceRows.isEmpty();
            String logBin = AdminSql.queryString(session, "SELECT @@GLOBAL.log_bin"); //$NON-NLS-1$
            boolean binaryLog = "1".equals(logBin) || "ON".equalsIgnoreCase(logBin); //$NON-NLS-1$ //$NON-NLS-2$
            summary.addRow(ReplicationMessages.repl_row_role, replica
                ? NLS.bind(ReplicationMessages.repl_my_role_replica, sourceRows.size())
                : sourceRows == null ? ReplicationMessages.repl_my_role_unknown : ReplicationMessages.repl_my_role_not_replica);
            summary.addRow(ReplicationMessages.repl_row_source, binaryLog
                ? (connected.getRows().isEmpty() ? ReplicationMessages.repl_my_source_binlog : NLS.bind(ReplicationMessages.repl_my_source_replicas,
                    connected.getRows().size()))
                : ReplicationMessages.repl_my_no_binlog);
            if (!replica && connected.getRows().isEmpty() && sourceRows != null) {
                summary.addRow(ReplicationMessages.repl_row_replication, ReplicationMessages.repl_not_configured);
            }
            AdminSql.query(session, "SHOW GLOBAL VARIABLES WHERE Variable_name IN ('server_id', 'log_bin', 'binlog_format', 'read_only', " //$NON-NLS-1$
                + "'gtid_mode', 'enforce_gtid_consistency', 'gtid_executed', 'gtid_strict_mode', 'gtid_current_pos', 'gtid_binlog_pos', 'gtid_slave_pos')", //$NON-NLS-1$
                rs -> summary.addRow(rs.getString(1), rs.getString(2)));
            if (binaryLog) {
                try {
                    List<Map<String, String>> status = mariaDB ? queryRows(session, "SHOW MASTER STATUS") //$NON-NLS-1$
                        : queryFirst(session, "SHOW BINARY LOG STATUS", "SHOW MASTER STATUS"); //$NON-NLS-1$ //$NON-NLS-2$
                    for (Map<String, String> row : status) {
                        summary.addRow(ReplicationMessages.repl_row_binlog_position, value(row, "File") + " : " + value(row, "Position")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    }
                } catch (SQLException e) {
                    summary.addRow(ReplicationMessages.repl_row_binlog_position,
                        denied(e, mariaDB ? ReplicationMessages.repl_my_denied_binlog_maria : ReplicationMessages.repl_my_denied_status));
                }
            }
            return new ReplicationReport(List.of(summary, replicas, connected), notices);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    /**
     * Runs the first statement the server knows: the new syntax, or the old one when the new one is a syntax error.
     */
    @NotNull
    private static List<Map<String, String>> queryFirst(@NotNull JDBCSession session, @NotNull String sql, @NotNull String oldSql) throws SQLException {
        try {
            return queryRows(session, sql);
        } catch (SQLException e) {
            if (e.getErrorCode() != ER_PARSE_ERROR) {
                throw e;
            }
            log.debug("Statement not known, using " + oldSql + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return queryRows(session, oldSql);
        }
    }

    /**
     * The notice for a refused statement (missing privilege), or the server's message for another error.
     */
    @NotNull
    private static String denied(@NotNull SQLException e, @NotNull String privilegeNotice) {
        return e.getErrorCode() == ER_SPECIFIC_ACCESS_DENIED ? privilegeNotice
            : NLS.bind(ReplicationMessages.repl_error, e.getMessage());
    }

    @Nullable
    private static String error(@Nullable String number, @Nullable String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        return number == null || "0".equals(number) ? message : number + ": " + message; //$NON-NLS-1$ //$NON-NLS-2$
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
