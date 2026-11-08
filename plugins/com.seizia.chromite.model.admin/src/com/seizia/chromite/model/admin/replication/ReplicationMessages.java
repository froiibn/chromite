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

import org.eclipse.osgi.util.NLS;

/**
 * 복제 상태(DB 관리 축 6)의 모델 문구: 표 제목, 열 머리글, 요약 행, "구성되지 않음" 문구, 권한 안내.
 *
 * 설계: 표는 DB별 방언이 만들므로 모델 번들에 둔다(공간 사용량의 {@code SpaceMessages}와 같은 방식). 키 접두사는 {@code repl_}다.
 * 서버가 쓰는 이름(LSN, GTID, WAL, PROTECTION_MODE 등)은 번역하지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 6 복제 상태 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-08 Altibase 복제 상태 문구 (docs/chromite/altibase.md "복제 시작·중지·동기화")
 */
public class ReplicationMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.model.admin.replication.ReplicationMessages"; //$NON-NLS-1$

    public static String repl_grid_summary;
    public static String repl_col_property;
    public static String repl_col_value;
    public static String repl_value_yes;
    public static String repl_value_no;
    public static String repl_not_configured;
    public static String repl_error;
    public static String repl_row_role;
    public static String repl_row_physical;
    public static String repl_row_logical;
    public static String repl_row_current_lsn;
    public static String repl_row_received_lsn;
    public static String repl_row_replayed_lsn;
    public static String repl_row_replay_delay;
    public static String repl_row_source;
    public static String repl_row_replication;
    public static String repl_row_binlog_position;
    public static String repl_row_data_guard;
    public static String repl_row_always_on;
    public static String repl_row_distributor;
    public static String repl_col_pid;
    public static String repl_col_application;
    public static String repl_col_user;
    public static String repl_col_client;
    public static String repl_col_state;
    public static String repl_col_status;
    public static String repl_col_sync_state;
    public static String repl_col_sent_lsn;
    public static String repl_col_replay_lsn;
    public static String repl_col_lag_bytes;
    public static String repl_col_lag_bytes_tip;
    public static String repl_col_write_lag;
    public static String repl_col_flush_lag;
    public static String repl_col_replay_lag;
    public static String repl_col_slot;
    public static String repl_col_slot_type;
    public static String repl_col_plugin;
    public static String repl_col_database;
    public static String repl_col_active;
    public static String repl_col_restart_lsn;
    public static String repl_col_confirmed_lsn;
    public static String repl_col_retained_wal;
    public static String repl_col_retained_wal_tip;
    public static String repl_col_wal_status;
    public static String repl_col_wal_status_tip;
    public static String repl_col_source;
    public static String repl_col_received_lsn;
    public static String repl_col_latest_end_lsn;
    public static String repl_col_last_message;
    public static String repl_col_latest_end_time;
    public static String repl_col_subscription;
    public static String repl_col_enabled;
    public static String repl_col_publication;
    public static String repl_col_owner;
    public static String repl_col_all_tables;
    public static String repl_col_tables;
    public static String repl_col_operations;
    public static String repl_col_channel;
    public static String repl_col_io_thread;
    public static String repl_col_sql_thread;
    public static String repl_col_seconds_behind;
    public static String repl_col_seconds_behind_tip;
    public static String repl_col_source_log;
    public static String repl_col_exec_position;
    public static String repl_col_gtid;
    public static String repl_col_last_io_error;
    public static String repl_col_last_sql_error;
    public static String repl_col_server_id;
    public static String repl_col_host;
    public static String repl_col_port;
    public static String repl_col_source_id;
    public static String repl_col_dest_id;
    public static String repl_col_dest_name;
    public static String repl_col_type;
    public static String repl_col_database_mode;
    public static String repl_col_recovery_mode;
    public static String repl_col_destination;
    public static String repl_col_archived_seq;
    public static String repl_col_applied_seq;
    public static String repl_col_gap;
    public static String repl_col_error;
    public static String repl_col_name;
    public static String repl_col_unit;
    public static String repl_col_time_computed;
    public static String repl_col_group;
    public static String repl_col_replica;
    public static String repl_col_role;
    public static String repl_col_connected;
    public static String repl_col_sync_health;
    public static String repl_col_availability_mode;
    public static String repl_col_failover_mode;
    public static String repl_col_suspended;
    public static String repl_col_send_queue;
    public static String repl_col_send_queue_tip;
    public static String repl_col_redo_queue;
    public static String repl_col_redo_queue_tip;
    public static String repl_col_last_commit;
    public static String repl_col_published;
    public static String repl_col_merge_published;
    public static String repl_col_distributor;
    public static String repl_col_log_reuse_wait;
    public static String repl_grid_pg_standbys;
    public static String repl_grid_pg_slots;
    public static String repl_grid_pg_receiver;
    public static String repl_grid_pg_subscriptions;
    public static String repl_grid_pg_publications;
    public static String repl_pg_role_primary;
    public static String repl_pg_role_standby;
    public static String repl_pg_physical_configured;
    public static String repl_pg_logical_configured;
    public static String repl_pg_no_standbys;
    public static String repl_pg_no_slots;
    public static String repl_pg_no_receiver;
    public static String repl_pg_no_receiver_standby;
    public static String repl_pg_no_subscriptions;
    public static String repl_pg_no_publications;
    public static String repl_pg_partial;
    public static String repl_grid_my_sources;
    public static String repl_grid_my_replicas;
    public static String repl_my_no_source;
    public static String repl_my_no_replicas;
    public static String repl_my_replicas_desc;
    public static String repl_my_role_replica;
    public static String repl_my_role_not_replica;
    public static String repl_my_role_unknown;
    public static String repl_my_source_binlog;
    public static String repl_my_source_replicas;
    public static String repl_my_no_binlog;
    public static String repl_my_denied_status;
    public static String repl_my_denied_status_maria;
    public static String repl_my_denied_binlog_maria;
    public static String repl_my_denied_hosts;
    public static String repl_my_denied_hosts_maria;
    public static String repl_grid_ora_destinations;
    public static String repl_grid_ora_stats;
    public static String repl_ora_configured;
    public static String repl_ora_not_configured;
    public static String repl_ora_no_destinations;
    public static String repl_ora_no_stats;
    public static String repl_ora_unavailable;
    public static String repl_ora_missing_views;
    public static String repl_grid_ms_replicas;
    public static String repl_grid_ms_databases;
    public static String repl_grid_ms_replication;
    public static String repl_ms_hadr_disabled;
    public static String repl_ms_no_groups;
    public static String repl_ms_denied_server_state;
    public static String repl_ms_enabled;
    public static String repl_ms_groups;
    public static String repl_ms_no_replication;
    public static String repl_ms_replication_desc;
    public static String repl_ms_replication_databases;
    public static String repl_ms_no_distributor;
    public static String repl_alti_row_port;
    public static String repl_alti_disabled;
    public static String repl_alti_configured;
    public static String repl_grid_alti_replications;
    public static String repl_grid_alti_senders;
    public static String repl_grid_alti_receivers;
    public static String repl_alti_col_remote;
    public static String repl_alti_col_started;
    public static String repl_alti_col_mode;
    public static String repl_alti_col_xsn;
    public static String repl_alti_col_commit_xsn;
    public static String repl_alti_col_apply_xsn;
    public static String repl_alti_col_fault_time;
    public static String repl_alti_col_give_up_time;
    public static String repl_alti_col_start_mode;
    public static String repl_alti_col_network_error;
    public static String repl_alti_col_peer;
    public static String repl_alti_col_gap_tip;
    public static String repl_alti_col_applied;
    public static String repl_alti_col_failed;
    public static String repl_alti_replications_desc;
    public static String repl_alti_no_replications;
    public static String repl_alti_no_senders;
    public static String repl_alti_no_receivers;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ReplicationMessages.class);
    }

    private ReplicationMessages() {
    }
}
