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
 * PostgreSQL 복제 상태(10+): 주 서버의 {@code pg_stat_replication}(대기 서버마다 지연 바이트·시간), {@code pg_replication_slots}(활성, 붙잡은 WAL),
 * 대기 서버의 {@code pg_stat_wal_receiver}, 논리 복제의 {@code pg_stat_subscription}과 발행 목록.
 *
 * 설계:
 * <ul>
 *     <li>대기 서버에서는 {@code pg_current_wal_lsn()}이 오류를 내므로(복구 중) 기준 위치를 {@code pg_last_wal_receive_lsn()}으로 바꾼다.</li>
 *     <li>지연 바이트 = 기준 위치 − 대기 서버가 재생한 위치({@code pg_wal_lsn_diff}), 지연 시간은 서버가 잰 write/flush/replay_lag를 초로 보인다.</li>
 *     <li>superuser나 pg_read_all_stats(pg_monitor 포함)가 아니면 {@code pg_stat_replication}·{@code pg_stat_wal_receiver}의 위치·상태 열이 비어 있다.
 *     그래서 이 권한을 먼저 보고 없으면 안내한다(행은 보인다).</li>
 *     <li>연결 문자열(primary_conninfo, 비밀번호가 들어 있을 수 있음)은 보이지 않는다.</li>
 *     <li>{@code wal_status}(슬롯이 WAL을 잃었는지)는 13+, {@code written_lsn}·{@code flushed_lsn}은 13+이고 그 전은 {@code received_lsn}이다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 6 복제 상태 (docs/chromite/swing-admin-tools.md)
 */
public class PostgreReplicationDialect extends ReplicationDialect {

    @NotNull
    private final PostgreDataSource dataSource;

    public PostgreReplicationDialect(@NotNull PostgreDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public ReplicationReport read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session) throws DBException {
        boolean v13 = dataSource.isServerVersionAtLeast(13, 0);
        try {
            List<String> notices = new ArrayList<>();
            boolean standby = "t".equals(AdminSql.queryString(session, "SELECT CASE WHEN pg_is_in_recovery() THEN 't' ELSE 'f' END")); //$NON-NLS-1$ //$NON-NLS-2$
            boolean readAll = "t".equals(AdminSql.queryString(session, //$NON-NLS-1$
                "SELECT CASE WHEN r.rolsuper OR pg_has_role('pg_read_all_stats', 'USAGE') THEN 't' ELSE 'f' END " //$NON-NLS-1$
                    + "FROM pg_roles r WHERE r.rolname = current_user")); //$NON-NLS-1$
            if (!readAll) {
                notices.add(ReplicationMessages.repl_pg_partial);
            }
            String position = standby ? "pg_last_wal_receive_lsn()" : "pg_current_wal_lsn()"; //$NON-NLS-1$ //$NON-NLS-2$

            AdminGrid standbys = new AdminGrid("standbys", ReplicationMessages.repl_grid_pg_standbys, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_pid),
                SpaceColumn.text(ReplicationMessages.repl_col_application),
                SpaceColumn.text(ReplicationMessages.repl_col_user),
                SpaceColumn.text(ReplicationMessages.repl_col_client),
                SpaceColumn.text(ReplicationMessages.repl_col_state),
                SpaceColumn.text(ReplicationMessages.repl_col_sync_state),
                SpaceColumn.text(ReplicationMessages.repl_col_sent_lsn),
                SpaceColumn.text(ReplicationMessages.repl_col_replay_lsn),
                new SpaceColumn(ReplicationMessages.repl_col_lag_bytes, SpaceColumn.Kind.BYTES, ReplicationMessages.repl_col_lag_bytes_tip),
                SpaceColumn.text(ReplicationMessages.repl_col_write_lag),
                SpaceColumn.text(ReplicationMessages.repl_col_flush_lag),
                SpaceColumn.text(ReplicationMessages.repl_col_replay_lag));
            standbys.setEmptyText(ReplicationMessages.repl_pg_no_standbys);
            AdminSql.query(session, "SELECT pid::text, application_name, usename, COALESCE(client_addr::text, client_hostname), state, sync_state, " //$NON-NLS-1$
                + "sent_lsn::text, replay_lsn::text, pg_wal_lsn_diff(" + position + ", replay_lsn)::bigint, " //$NON-NLS-1$ //$NON-NLS-2$
                // Lags in seconds (the interval text 00:00:00.075823 is hard to read in a narrow column); NULL when idle
                + seconds("write_lag") + ", " + seconds("flush_lag") + ", " + seconds("replay_lag") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                + " FROM pg_stat_replication ORDER BY application_name, pid", //$NON-NLS-1$
                rs -> standbys.addRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                    rs.getString(7), rs.getString(8), AdminSql.getLong(rs, 9), rs.getString(10), rs.getString(11), rs.getString(12)));

            AdminGrid slots = new AdminGrid("slots", ReplicationMessages.repl_grid_pg_slots, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_slot),
                SpaceColumn.text(ReplicationMessages.repl_col_slot_type),
                SpaceColumn.text(ReplicationMessages.repl_col_plugin),
                SpaceColumn.text(ReplicationMessages.repl_col_database),
                SpaceColumn.text(ReplicationMessages.repl_col_active),
                SpaceColumn.text(ReplicationMessages.repl_col_pid),
                SpaceColumn.text(ReplicationMessages.repl_col_restart_lsn),
                SpaceColumn.text(ReplicationMessages.repl_col_confirmed_lsn),
                new SpaceColumn(ReplicationMessages.repl_col_retained_wal, SpaceColumn.Kind.BYTES, ReplicationMessages.repl_col_retained_wal_tip),
                new SpaceColumn(ReplicationMessages.repl_col_wal_status, SpaceColumn.Kind.TEXT, ReplicationMessages.repl_col_wal_status_tip));
            slots.setEmptyText(ReplicationMessages.repl_pg_no_slots);
            AdminSql.query(session, "SELECT slot_name, slot_type, plugin, database, CASE WHEN active THEN 't' ELSE 'f' END, active_pid::text, " //$NON-NLS-1$
                + "restart_lsn::text, confirmed_flush_lsn::text, pg_wal_lsn_diff(" + position + ", restart_lsn)::bigint, " //$NON-NLS-1$ //$NON-NLS-2$
                + (v13 ? "wal_status" : "NULL") + " FROM pg_replication_slots ORDER BY slot_name", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                rs -> slots.addRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    "t".equals(rs.getString(5)) ? ReplicationMessages.repl_value_yes : ReplicationMessages.repl_value_no, //$NON-NLS-1$
                    rs.getString(6), rs.getString(7), rs.getString(8), AdminSql.getLong(rs, 9), rs.getString(10)));

            AdminGrid receiver = new AdminGrid("receiver", ReplicationMessages.repl_grid_pg_receiver, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_status),
                SpaceColumn.text(ReplicationMessages.repl_col_source),
                SpaceColumn.text(ReplicationMessages.repl_col_slot),
                SpaceColumn.text(ReplicationMessages.repl_col_received_lsn),
                SpaceColumn.text(ReplicationMessages.repl_col_latest_end_lsn),
                SpaceColumn.text(ReplicationMessages.repl_col_last_message),
                SpaceColumn.text(ReplicationMessages.repl_col_latest_end_time));
            receiver.setEmptyText(standby ? ReplicationMessages.repl_pg_no_receiver_standby : ReplicationMessages.repl_pg_no_receiver);
            AdminSql.query(session, "SELECT status, " //$NON-NLS-1$
                + (dataSource.isServerVersionAtLeast(11, 0) ? "sender_host || ':' || sender_port" : "NULL") + ", slot_name, " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (v13 ? "flushed_lsn::text" : "received_lsn::text") + ", latest_end_lsn::text, " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "to_char(last_msg_receipt_time, 'YYYY-MM-DD HH24:MI:SS'), to_char(latest_end_time, 'YYYY-MM-DD HH24:MI:SS') " //$NON-NLS-1$
                + "FROM pg_stat_wal_receiver", //$NON-NLS-1$
                rs -> receiver.addRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                    rs.getString(7)));

            AdminGrid subscriptions = new AdminGrid("subscriptions", ReplicationMessages.repl_grid_pg_subscriptions, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_subscription),
                SpaceColumn.text(ReplicationMessages.repl_col_enabled),
                SpaceColumn.text(ReplicationMessages.repl_col_pid),
                SpaceColumn.text(ReplicationMessages.repl_col_received_lsn),
                SpaceColumn.text(ReplicationMessages.repl_col_latest_end_lsn),
                SpaceColumn.text(ReplicationMessages.repl_col_last_message),
                SpaceColumn.text(ReplicationMessages.repl_col_latest_end_time));
            subscriptions.setEmptyText(ReplicationMessages.repl_pg_no_subscriptions);
            // The apply worker of each subscription (relid is set for table synchronization workers)
            AdminSql.query(session, "SELECT sub.subname, CASE WHEN sub.subenabled THEN 't' ELSE 'f' END, st.pid::text, st.received_lsn::text, " //$NON-NLS-1$
                + "st.latest_end_lsn::text, to_char(st.last_msg_receipt_time, 'YYYY-MM-DD HH24:MI:SS'), " //$NON-NLS-1$
                + "to_char(st.latest_end_time, 'YYYY-MM-DD HH24:MI:SS') " //$NON-NLS-1$
                + "FROM pg_subscription sub LEFT JOIN pg_stat_subscription st ON st.subid = sub.oid AND st.relid IS NULL " //$NON-NLS-1$
                + "WHERE sub.subdbid = (SELECT oid FROM pg_database WHERE datname = current_database()) ORDER BY sub.subname", //$NON-NLS-1$
                rs -> subscriptions.addRow(rs.getString(1),
                    "t".equals(rs.getString(2)) ? ReplicationMessages.repl_value_yes : ReplicationMessages.repl_value_no, //$NON-NLS-1$
                    rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)));

            AdminGrid publications = new AdminGrid("publications", ReplicationMessages.repl_grid_pg_publications, //$NON-NLS-1$
                SpaceColumn.text(ReplicationMessages.repl_col_publication),
                SpaceColumn.text(ReplicationMessages.repl_col_owner),
                SpaceColumn.text(ReplicationMessages.repl_col_all_tables),
                SpaceColumn.count(ReplicationMessages.repl_col_tables),
                SpaceColumn.text(ReplicationMessages.repl_col_operations));
            publications.setEmptyText(ReplicationMessages.repl_pg_no_publications);
            AdminSql.query(session, "SELECT p.pubname, pg_get_userbyid(p.pubowner), CASE WHEN p.puballtables THEN 't' ELSE 'f' END, " //$NON-NLS-1$
                + "(SELECT COUNT(*) FROM pg_publication_tables t WHERE t.pubname = p.pubname), " //$NON-NLS-1$
                + "concat_ws(', ', CASE WHEN p.pubinsert THEN 'INSERT' END, CASE WHEN p.pubupdate THEN 'UPDATE' END, " //$NON-NLS-1$
                + "CASE WHEN p.pubdelete THEN 'DELETE' END" //$NON-NLS-1$
                + (dataSource.isServerVersionAtLeast(11, 0) ? ", CASE WHEN p.pubtruncate THEN 'TRUNCATE' END" : "") //$NON-NLS-1$ //$NON-NLS-2$
                + ") FROM pg_publication p ORDER BY p.pubname", //$NON-NLS-1$
                rs -> publications.addRow(rs.getString(1), rs.getString(2),
                    "t".equals(rs.getString(3)) ? ReplicationMessages.repl_value_yes : ReplicationMessages.repl_value_no, //$NON-NLS-1$
                    AdminSql.getLong(rs, 4), rs.getString(5)));

            AdminGrid summary = createSummary();
            summary.addRow(ReplicationMessages.repl_row_role, standby ? ReplicationMessages.repl_pg_role_standby : ReplicationMessages.repl_pg_role_primary);
            boolean streaming = !standbys.getRows().isEmpty() || !receiver.getRows().isEmpty() || !slots.getRows().isEmpty();
            boolean logical = !subscriptions.getRows().isEmpty() || !publications.getRows().isEmpty();
            summary.addRow(ReplicationMessages.repl_row_physical, streaming
                ? NLS.bind(ReplicationMessages.repl_pg_physical_configured, new Object[] {
                    standbys.getRows().size(), receiver.getRows().size(), slots.getRows().size()})
                : ReplicationMessages.repl_not_configured);
            summary.addRow(ReplicationMessages.repl_row_logical, logical
                ? NLS.bind(ReplicationMessages.repl_pg_logical_configured, publications.getRows().size(), subscriptions.getRows().size())
                : ReplicationMessages.repl_not_configured);
            AdminSql.query(session, "SELECT name, setting FROM pg_settings WHERE name IN ('wal_level', 'max_wal_senders', " //$NON-NLS-1$
                + "'max_replication_slots', 'synchronous_standby_names', 'synchronous_commit', 'hot_standby', 'primary_slot_name') ORDER BY name", //$NON-NLS-1$
                rs -> summary.addRow(rs.getString(1), rs.getString(2)));
            String lsn = AdminSql.queryString(session, "SELECT " + position + "::text"); //$NON-NLS-1$ //$NON-NLS-2$
            summary.addRow(standby ? ReplicationMessages.repl_row_received_lsn : ReplicationMessages.repl_row_current_lsn, lsn);
            if (standby) {
                summary.addRow(ReplicationMessages.repl_row_replayed_lsn, AdminSql.queryString(session, "SELECT pg_last_wal_replay_lsn()::text")); //$NON-NLS-1$
                summary.addRow(ReplicationMessages.repl_row_replay_delay, AdminSql.queryString(session,
                    "SELECT (now() - pg_last_xact_replay_timestamp())::text")); //$NON-NLS-1$
            }
            return new ReplicationReport(List.of(summary, standbys, slots, receiver, subscriptions, publications), notices);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    /**
     * An interval column as seconds with milliseconds ("0.318 s").
     */
    @NotNull
    private static String seconds(@NotNull String column) {
        return "to_char(extract(epoch FROM " + column + "), 'FM999999990.000') || ' s'"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
