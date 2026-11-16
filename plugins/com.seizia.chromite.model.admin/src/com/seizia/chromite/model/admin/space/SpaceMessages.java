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
package com.seizia.chromite.model.admin.space;

import org.eclipse.osgi.util.NLS;

/**
 * 공간 사용량 화면의 모델 문구: 표 제목, 열 머리글, 값, 권한 안내.
 *
 * 설계: 열과 안내는 DB별 방언이 만들므로 모델 번들에 둔다(Activity Monitor의 {@code ActivityAccessMessages}와 같은 방식).
 * 여러 작업이 같은 문구 파일을 고치면 병합이 충돌하므로 공간 사용량 문구만 이 묶음에 모은다. 키 접두사는 {@code space_}다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 공간 사용량 (docs/chromite/swing-admin-tools.md)
 */
public class SpaceMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.model.admin.space.SpaceMessages"; //$NON-NLS-1$

    public static String space_scope_database;
    public static String space_scope_schema;
    public static String space_scope_owner;
    public static String space_grid_databases;
    public static String space_grid_schemas;
    public static String space_grid_files;
    public static String space_grid_tablespaces;
    public static String space_grid_data_files;
    public static String space_grid_tables;
    public static String space_grid_tables_in;
    public static String space_col_database;
    public static String space_col_schema;
    public static String space_col_owner;
    public static String space_col_table;
    public static String space_col_tables;
    public static String space_col_tablespace;
    public static String space_col_file;
    public static String space_col_file_id;
    public static String space_col_path;
    public static String space_col_type;
    public static String space_col_kind;
    public static String space_col_contents;
    public static String space_col_state;
    public static String space_col_status;
    public static String space_col_recovery;
    public static String space_col_log_reuse_wait;
    public static String space_col_log_reuse_wait_tip;
    public static String space_col_filegroup;
    public static String space_col_encoding;
    public static String space_col_location;
    public static String space_col_engine;
    public static String space_col_row_format;
    public static String space_col_size;
    public static String space_col_used;
    public static String space_col_free;
    public static String space_col_free_pct;
    public static String space_col_used_pct;
    public static String space_col_max_size;
    public static String space_col_max_size_tip;
    public static String space_col_used_of_max_pct;
    public static String space_col_autogrowth;
    public static String space_col_autoextend;
    public static String space_col_increment;
    public static String space_col_data_size;
    public static String space_col_data_used;
    public static String space_col_data_free;
    public static String space_col_data_free_tip;
    public static String space_col_log_size;
    public static String space_col_log_used;
    public static String space_col_log_used_pct;
    public static String space_col_temp_size;
    public static String space_col_rows;
    public static String space_col_rows_est;
    public static String space_col_rows_est_tip;
    public static String space_col_rows_stats_tip;
    public static String space_col_dead_rows;
    public static String space_col_dead_rows_tip;
    public static String space_col_data;
    public static String space_col_index;
    public static String space_col_toast;
    public static String space_col_toast_tip;
    public static String space_col_lob;
    public static String space_col_unused;
    public static String space_col_total;
    public static String space_col_reserved_tip;
    public static String space_value_yes;
    public static String space_value_no;
    public static String space_value_table;
    public static String space_value_materialized_view;
    public static String space_value_growth_none;
    public static String space_value_growth_percent;
    public static String space_value_growth_size;
    public static String space_mssql_edition;
    public static String space_mssql_hidden_databases;
    public static String space_mssql_tables_partial;
    public static String space_mysql_partial;
    public static String space_postgres_database_size;
    public static String space_postgres_tablespace_size;
    public static String space_oracle_missing_views;
    public static String space_oracle_missing_views_own_tables;
    public static String space_oracle_unavailable;

    static {
        NLS.initializeMessages(BUNDLE_NAME, SpaceMessages.class);
    }

    private SpaceMessages() {
    }
}
