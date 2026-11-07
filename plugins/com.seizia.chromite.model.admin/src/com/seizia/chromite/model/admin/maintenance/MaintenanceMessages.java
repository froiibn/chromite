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
package com.seizia.chromite.model.admin.maintenance;

import org.eclipse.osgi.util.NLS;

/**
 * 유지 관리(DB 관리 축 4)의 모델 문구: 동작·옵션 이름과 설명, 경고, 권한 안내, 유지 관리 탭의 표 제목·열 머리글.
 *
 * 설계: 동작과 표는 DB별 방언이 만들므로 모델 번들에 둔다(공간 사용량의 {@code SpaceMessages}와 같은 방식). 다른 작업과 같은 문구 파일을
 * 고치지 않도록 유지 관리 문구만 이 묶음에 모은다. 키 접두사는 {@code maint_}다. SQL 키워드(VACUUM, FULL, DBCC CHECKDB 등)는 번역하지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-08 Oracle 구조 검증(VALIDATE STRUCTURE) 문구 (docs/chromite/swing-admin-tools.md)
 */
public class MaintenanceMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.model.admin.maintenance.MaintenanceMessages"; //$NON-NLS-1$

    public static String maint_progress_running;
    public static String maint_progress_statement;
    public static String maint_msg_statement_done;
    public static String maint_msg_canceled;
    public static String maint_result_title;
    public static String maint_not_found;
    public static String maint_scope_database;
    public static String maint_scope_schema;
    public static String maint_scope_owner;
    public static String maint_value_yes;
    public static String maint_value_no;
    public static String maint_value_never;
    public static String maint_value_default;
    public static String maint_col_schema;
    public static String maint_col_database;
    public static String maint_col_owner;
    public static String maint_col_table;
    public static String maint_col_index;
    public static String maint_col_object;
    public static String maint_col_type;
    public static String maint_col_status;
    public static String maint_col_state;
    public static String maint_col_recovery;
    public static String maint_col_engine;
    public static String maint_col_size;
    public static String maint_col_data;
    public static String maint_col_free;
    public static String maint_col_free_tip;
    public static String maint_col_free_pct;
    public static String maint_col_rows_est;
    public static String maint_col_live_rows;
    public static String maint_col_dead_rows;
    public static String maint_col_dead_pct;
    public static String maint_col_mod_since_analyze;
    public static String maint_col_mod_since_analyze_tip;
    public static String maint_col_last_vacuum;
    public static String maint_col_last_autovacuum;
    public static String maint_col_last_analyze;
    public static String maint_col_last_autoanalyze;
    public static String maint_col_autovacuum_due;
    public static String maint_col_autovacuum_due_tip;
    public static String maint_col_suggestion;
    public static String maint_col_bloat;
    public static String maint_col_bloat_pct;
    public static String maint_col_bloat_tip;
    public static String maint_col_updated;
    public static String maint_col_checked;
    public static String maint_col_stat_rows;
    public static String maint_col_stat_updated;
    public static String maint_col_last_ddl;
    public static String maint_col_errors;
    public static String maint_col_last_analyzed;
    public static String maint_col_stale;
    public static String maint_col_stale_tip;
    public static String maint_col_fragmentation;
    public static String maint_col_pages;
    public static String maint_col_partition;
    public static String maint_col_statistics;
    public static String maint_col_modifications;
    public static String maint_col_modifications_pct;
    public static String maint_col_last_checkdb;
    public static String maint_col_last_checkdb_tip;
    public static String maint_empty_tables;
    public static String maint_empty_bloat;
    public static String maint_empty_invalid;
    public static String maint_empty_stale;
    public static String maint_empty_unusable;
    public static String maint_empty_fragmentation;
    public static String maint_empty_statistics;
    public static String maint_opt_verbose_tip;
    public static String maint_opt_index;
    public static String maint_opt_check_mode;
    public static String maint_opt_repair_mode;
    public static String maint_opt_sample;
    public static String maint_opt_gather;
    public static String maint_opt_method;
    public static String maint_opt_scan;
    public static String maint_grid_pg_vacuum;
    public static String maint_grid_pg_bloat;
    public static String maint_pg_vacuum_desc;
    public static String maint_pg_bloat_desc;
    public static String maint_pg_vacuum_op_desc;
    public static String maint_pg_analyze_op_desc;
    public static String maint_pg_reindex_op_desc;
    public static String maint_pg_cluster_desc;
    public static String maint_pg_cluster_database_desc;
    public static String maint_pg_cluster_previous;
    public static String maint_pg_cluster_index_tip;
    public static String maint_pg_full_tip;
    public static String maint_pg_freeze_tip;
    public static String maint_pg_vacuum_analyze_tip;
    public static String maint_pg_concurrently_tip;
    public static String maint_pg_concurrently_version;
    public static String maint_pg_warn_full;
    public static String maint_pg_warn_cluster;
    public static String maint_pg_warn_reindex;
    public static String maint_pg_warn_concurrently;
    public static String maint_pg_denied_table;
    public static String maint_pg_denied_table_17;
    public static String maint_pg_denied_schema;
    public static String maint_pg_denied_database;
    public static String maint_pg_schema_empty;
    public static String maint_pg_other_database;
    public static String maint_pg_fact_rows;
    public static String maint_pg_fact_times;
    public static String maint_pg_fact_clustered;
    public static String maint_pg_fact_schema_tables;
    public static String maint_pg_fact_database_skips;
    public static String maint_grid_my_tables;
    public static String maint_grid_my_statistics;
    public static String maint_my_tables_desc;
    public static String maint_my_statistics_desc;
    public static String maint_my_statistics_denied;
    public static String maint_my_partial;
    public static String maint_my_analyze_desc;
    public static String maint_my_optimize_desc;
    public static String maint_my_check_desc;
    public static String maint_my_repair_desc;
    public static String maint_my_local_tip;
    public static String maint_my_check_mode_tip;
    public static String maint_my_warn_optimize;
    public static String maint_my_warn_repair;
    public static String maint_my_denied;
    public static String maint_my_schema_empty;
    public static String maint_my_fact_table;
    public static String maint_my_fact_times;
    public static String maint_my_fact_schema_tables;
    public static String maint_my_fact_partial;
    public static String maint_grid_ora_invalid;
    public static String maint_grid_ora_stale;
    public static String maint_grid_ora_unusable;
    public static String maint_ora_invalid_desc;
    public static String maint_ora_stale_desc;
    public static String maint_ora_unusable_desc;
    public static String maint_ora_missing_views;
    public static String maint_ora_gather_table;
    public static String maint_ora_gather_index;
    public static String maint_ora_gather_schema;
    public static String maint_ora_gather_desc;
    public static String maint_ora_sample_auto;
    public static String maint_ora_sample_tip;
    public static String maint_ora_cascade;
    public static String maint_ora_cascade_tip;
    public static String maint_ora_gather_all;
    public static String maint_ora_gather_stale;
    public static String maint_ora_gather_empty;
    public static String maint_ora_rebuild;
    public static String maint_ora_rebuild_desc;
    public static String maint_ora_online_tip;
    public static String maint_ora_warn_rebuild;
    public static String maint_ora_validate;
    public static String maint_ora_validate_desc;
    public static String maint_ora_validate_cascade;
    public static String maint_ora_validate_cascade_tip;
    public static String maint_ora_validate_table_only;
    public static String maint_ora_warn_validate;
    public static String maint_ora_compile;
    public static String maint_ora_compile_desc;
    public static String maint_ora_compile_schema;
    public static String maint_ora_compile_schema_desc;
    public static String maint_ora_method_invalid;
    public static String maint_ora_method_each;
    public static String maint_ora_method_all;
    public static String maint_ora_method_tip;
    public static String maint_ora_warn_compile;
    public static String maint_ora_warn_compile_all;
    public static String maint_ora_compile_unsupported;
    public static String maint_ora_denied;
    public static String maint_ora_fact_table;
    public static String maint_ora_fact_index;
    public static String maint_ora_fact_object;
    public static String maint_ora_fact_schema;
    public static String maint_grid_ms_fragmentation;
    public static String maint_grid_ms_statistics;
    public static String maint_grid_ms_checkdb;
    public static String maint_ms_fragmentation_desc;
    public static String maint_ms_statistics_desc;
    public static String maint_ms_checkdb_desc;
    public static String maint_ms_denied_view_state;
    public static String maint_ms_denied_alter;
    public static String maint_ms_denied_owner;
    public static String maint_ms_rebuild;
    public static String maint_ms_rebuild_all;
    public static String maint_ms_rebuild_desc;
    public static String maint_ms_reorganize;
    public static String maint_ms_reorganize_all;
    public static String maint_ms_reorganize_desc;
    public static String maint_ms_update_statistics;
    public static String maint_ms_update_statistics_desc;
    public static String maint_ms_update_all_statistics;
    public static String maint_ms_update_all_statistics_desc;
    public static String maint_ms_checkdb_op_desc;
    public static String maint_ms_online_tip;
    public static String maint_ms_online_edition;
    public static String maint_ms_sort_tip;
    public static String maint_ms_lob_tip;
    public static String maint_ms_scan_default;
    public static String maint_ms_scan_tip;
    public static String maint_ms_no_infomsgs_tip;
    public static String maint_ms_physical_only_tip;
    public static String maint_ms_warn_offline;
    public static String maint_ms_warn_fullscan;
    public static String maint_ms_warn_checkdb;
    public static String maint_ms_suggest_none;
    public static String maint_ms_locked;
    public static String maint_ms_fact_fragmentation;
    public static String maint_ms_fact_statistics;
    public static String maint_ms_fact_checkdb;

    static {
        NLS.initializeMessages(BUNDLE_NAME, MaintenanceMessages.class);
    }

    private MaintenanceMessages() {
    }
}
