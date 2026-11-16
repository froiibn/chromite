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
 * Oracle Data Guard 기본 상태: {@code V$DATABASE}의 역할·보호 모드, {@code V$ARCHIVE_DEST_STATUS}의 켜진 보관 대상, 읽을 수 있으면 {@code V$DATAGUARD_STATS}.
 *
 * 설계:
 * <ul>
 *     <li>Data Guard가 구성된 것으로 보는 경우: 역할이 PRIMARY가 아니거나, 원격(LOCAL이 아닌) 보관 대상이 있거나, Broker가 켜져 있다.
 *     그 밖은 "구성되지 않음"이다(대부분의 서버). 로컬 보관 대상도 표에 보인다(보관 로그 모드 확인용).</li>
 *     <li>뷰마다 {@code WHERE 1 = 0}으로 읽어 보고 읽을 수 없는 뷰는 표를 "읽을 수 없음"으로 두고 SELECT_CATALOG_ROLE을 안내한다.</li>
 *     <li>ORACLE LICENSE GUARD: 쓰는 뷰는 Diagnostics/Tuning Pack이 필요 없다. Active Data Guard 옵션이 필요한 기능(대기 DB의 실시간 조회)을 쓰지 않고
 *     상태 뷰를 읽기만 한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 6 복제 상태 (docs/chromite/swing-admin-tools.md)
 */
public class OracleReplicationDialect extends ReplicationDialect {

    @NotNull
    private final OracleDataSource dataSource;

    public OracleReplicationDialect(@NotNull OracleDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public ReplicationReport read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session) throws DBException {
        try {
            List<String> notices = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            AdminGrid summary = createSummary();
            String[] database = null;
            if (canRead(session, "V$DATABASE", missing)) { //$NON-NLS-1$
                String[] row = new String[9];
                AdminSql.query(session, "SELECT database_role, protection_mode, protection_level, switchover_status, dataguard_broker, " //$NON-NLS-1$
                    + "force_logging, log_mode, open_mode, db_unique_name FROM v$database", rs -> { //$NON-NLS-1$
                        for (int i = 0; i < row.length; i++) {
                            row[i] = rs.getString(i + 1);
                        }
                    });
                database = row;
            }

            AdminGrid destinations = new AdminGrid("destinations", ReplicationMessages.repl_grid_ora_destinations, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_dest_id),
                SpaceColumn.text(ReplicationMessages.repl_col_dest_name),
                SpaceColumn.text(ReplicationMessages.repl_col_status),
                SpaceColumn.text(ReplicationMessages.repl_col_type),
                SpaceColumn.text(ReplicationMessages.repl_col_database_mode),
                SpaceColumn.text(ReplicationMessages.repl_col_recovery_mode),
                SpaceColumn.text(ReplicationMessages.repl_col_destination),
                SpaceColumn.text(ReplicationMessages.repl_col_archived_seq),
                SpaceColumn.text(ReplicationMessages.repl_col_applied_seq),
                SpaceColumn.text(ReplicationMessages.repl_col_gap),
                SpaceColumn.text(ReplicationMessages.repl_col_error));
            destinations.setEmptyText(ReplicationMessages.repl_ora_no_destinations);
            int[] remote = {0};
            if (canRead(session, "V$ARCHIVE_DEST_STATUS", missing)) { //$NON-NLS-1$
                AdminSql.query(session, "SELECT dest_id, dest_name, status, type, database_mode, recovery_mode, destination, " //$NON-NLS-1$
                    + "archived_seq#, applied_seq#, " + (dataSource.isAtLeastV11() ? "gap_status" : "NULL") + ", error " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    + "FROM v$archive_dest_status WHERE status <> 'INACTIVE' ORDER BY dest_id", rs -> { //$NON-NLS-1$
                        if (!"LOCAL".equals(rs.getString(4))) { //$NON-NLS-1$
                            remote[0]++;
                        }
                        destinations.addRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11));
                    });
            } else {
                destinations.setUnavailable(NLS.bind(ReplicationMessages.repl_ora_unavailable, "V$ARCHIVE_DEST_STATUS")); //$NON-NLS-1$
            }

            AdminGrid stats = new AdminGrid("dataguard", ReplicationMessages.repl_grid_ora_stats, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_name),
                SpaceColumn.text(ReplicationMessages.repl_col_value),
                SpaceColumn.text(ReplicationMessages.repl_col_unit),
                SpaceColumn.text(ReplicationMessages.repl_col_time_computed));
            stats.setEmptyText(ReplicationMessages.repl_ora_no_stats);
            if (canRead(session, "V$DATAGUARD_STATS", missing)) { //$NON-NLS-1$
                AdminSql.query(session, "SELECT name, value, unit, time_computed FROM v$dataguard_stats ORDER BY name", //$NON-NLS-1$
                    rs -> stats.addRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            } else {
                stats.setUnavailable(NLS.bind(ReplicationMessages.repl_ora_unavailable, "V$DATAGUARD_STATS")); //$NON-NLS-1$
            }

            if (database != null) {
                boolean configured = !"PRIMARY".equals(database[0]) || remote[0] > 0 || "ENABLED".equals(database[4]); //$NON-NLS-1$ //$NON-NLS-2$
                summary.addRow(ReplicationMessages.repl_row_data_guard, configured
                    ? NLS.bind(ReplicationMessages.repl_ora_configured, remote[0]) : ReplicationMessages.repl_ora_not_configured);
                summary.addRow(ReplicationMessages.repl_row_role, database[0]);
                summary.addRow("DB_UNIQUE_NAME", database[8]); //$NON-NLS-1$
                summary.addRow("PROTECTION_MODE", database[1]); //$NON-NLS-1$
                summary.addRow("PROTECTION_LEVEL", database[2]); //$NON-NLS-1$
                summary.addRow("SWITCHOVER_STATUS", database[3]); //$NON-NLS-1$
                summary.addRow("DATAGUARD_BROKER", database[4]); //$NON-NLS-1$
                summary.addRow("FORCE_LOGGING", database[5]); //$NON-NLS-1$
                summary.addRow("LOG_MODE", database[6]); //$NON-NLS-1$
                summary.addRow("OPEN_MODE", database[7]); //$NON-NLS-1$
            } else {
                summary.setUnavailable(NLS.bind(ReplicationMessages.repl_ora_unavailable, "V$DATABASE")); //$NON-NLS-1$
            }
            if (!missing.isEmpty()) {
                notices.add(NLS.bind(ReplicationMessages.repl_ora_missing_views, String.join(", ", missing))); //$NON-NLS-1$
            }
            return new ReplicationReport(List.of(summary, destinations, stats), notices);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    private static boolean canRead(@NotNull JDBCSession session, @NotNull String view, @NotNull List<String> missing) {
        if (AdminSql.probe(session, "SELECT 1 FROM " + view + " WHERE 1 = 0") == null) { //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
        missing.add(view);
        return false;
    }
}
