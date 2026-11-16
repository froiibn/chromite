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
package com.seizia.chromite.ui.swing.internal;

import org.eclipse.osgi.util.NLS;

/**
 * Tools &gt; Options의 SWT 설정 페이지 대응 문구: Query Execution 탭, Connection Types 탭, Oracle 탭, General 탭 Query Results의 결과 형식 행.
 *
 * 설계: {@link CoreMessages}와 같은 NLS 관례(영어 기본, {@code _ko} 한국어, 자리 표시자가 있으면 작은따옴표 두 번)를 따른다.
 * 기능 영역마다 번들을 나누는 관례대로, 다른 Options 탭과 병합 충돌이 나지 않게 이 번들을 따로 두었다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-options.md)
 * - 2026-11-07 Query Execution 탭의 쿼리 창 세션({@code exec_separate_connection*}) (docs/chromite/transactions.md "쿼리 창마다 별도 연결")
 * - 2026-11-08 연결별 설정({@code overrides_*}), Data Formats·Errors and Timeouts·Metadata·Client Identification 탭
 *   ({@code dataformat_*}, {@code errors_*}, {@code metadata_*}, {@code client_*}) (docs/chromite/swing-options.md "연결별 설정")
 * - 2026-11-08 남은 설정 키: Query Results의 읽기와 정렬({@code results_*}), Binary and LOB({@code content_*}), Connections({@code conn_*}),
 *   Drivers({@code drivers_*}), Logs({@code logs_*}), Object Explorer({@code nav_*}), Notifications({@code notify_*}), Data Transfer
 *   ({@code transfer_*}), Code Completion({@code completion_*}), Altibase·CUBRID·Mimer SQL 탭 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 * - 2026-11-08 Logs 탭의 기록할 것이 Query Manager 창의 보일 항목이기도 하다는 안내({@code logs_objects_hint}) (docs/chromite/swing-query-manager.md)
 * - 2026-11-09 Connection Types의 조회만 한 트랜잭션 자동으로 끝내기({@code types_end_read_only*})
 *   (docs/chromite/idle-transactions.md "조회만 한 트랜잭션 끝내기")
 */
public class SettingsMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.SettingsMessages"; //$NON-NLS-1$

    public static String exec_tab;
    public static String exec_default_hint;
    public static String exec_section_delimiters;
    public static String exec_delimiter;
    public static String exec_delimiter_hint;
    public static String exec_delimiter_tip;
    public static String exec_ignore_native_delimiter;
    public static String exec_ignore_native_delimiter_tip;
    public static String exec_blank_line;
    public static String exec_blank_line_tip;
    public static String exec_blank_line_smart;
    public static String exec_blank_line_always;
    public static String exec_blank_line_never;
    public static String exec_remove_trailing_delimiter;
    public static String exec_remove_trailing_delimiter_tip;
    public static String exec_section_parameters;
    public static String exec_parameters_enabled;
    public static String exec_named_prefix;
    public static String exec_anonymous_parameters;
    public static String exec_anonymous_mark;
    public static String exec_parameters_in_embedded_code;
    public static String exec_parameters_in_embedded_code_tip;
    public static String exec_variables_enabled;
    public static String exec_command_prefix;
    public static String exec_command_prefix_tip;
    public static String exec_section_errors;
    public static String exec_on_error;
    public static String exec_on_error_hint;
    public static String exec_on_error_stop;
    public static String exec_on_error_ignore;
    public static String exec_section_sessions;
    public static String exec_separate_connection;
    public static String exec_separate_connection_hint;
    public static String exec_separate_connection_tip;
    public static String exec_separate_connection_default;
    public static String exec_separate_connection_always;
    public static String exec_separate_connection_never;

    public static String results_use_fetch_size;
    public static String results_use_fetch_size_tip;
    public static String results_native_numeric;
    public static String results_native_numeric_tip;
    public static String results_scientific_numeric;
    public static String results_binary_format;
    public static String results_binary_string;
    public static String results_binary_hex;
    public static String results_binary_base64;
    public static String results_binary_max_length;
    public static String results_binary_max_length_hint;

    public static String types_tab;
    public static String types_new;
    public static String types_new_name;
    public static String types_delete;
    public static String types_delete_title;
    public static String types_delete_confirm;
    public static String types_delete_confirm_unused;
    public static String types_save_failed;
    public static String types_section_type;
    public static String types_name;
    public static String types_description;
    public static String types_color;
    public static String types_color_dark;
    public static String types_color_change;
    public static String types_color_none;
    public static String types_section_transactions;
    public static String types_auto_commit;
    public static String types_auto_commit_tip;
    public static String types_confirm_execute;
    public static String types_confirm_execute_tip;
    public static String types_confirm_data_change;
    public static String types_confirm_data_change_tip;
    public static String types_idle_transactions;
    public static String types_idle_transactions_tip;
    public static String types_idle_transactions_period;
    public static String types_end_read_only;
    public static String types_end_read_only_tip;
    public static String types_minutes;
    public static String types_section_connections;
    public static String types_close_idle;
    public static String types_close_idle_tip;
    public static String types_close_idle_period;
    public static String types_seconds;
    public static String types_section_restrictions;

    public static String oracle_tab;
    public static String oracle_section_execution;
    public static String oracle_section_metadata;
    public static String oracle_plan_table;
    public static String oracle_plan_table_hint;
    public static String oracle_dbms_output;
    public static String oracle_dbms_output_tip;
    public static String oracle_disable_escape;
    public static String oracle_disable_escape_tip;
    public static String oracle_rowid;
    public static String oracle_rowid_tip;
    public static String oracle_read_all_synonyms;
    public static String oracle_read_all_synonyms_tip;
    public static String overrides_use_connection;
    public static String overrides_hint;
    public static String dataformat_tab;
    public static String dataformat_section;
    public static String dataformat_locale;
    public static String dataformat_locale_tip;
    public static String dataformat_type;
    public static String dataformat_sample;
    public static String errors_tab;
    public static String errors_section_timeouts;
    public static String errors_ms_hint;
    public static String errors_open_timeout;
    public static String errors_open_timeout_hint;
    public static String errors_open_timeout_tip;
    public static String errors_close_timeout;
    public static String errors_close_timeout_tip;
    public static String errors_validation_timeout;
    public static String errors_validation_timeout_tip;
    public static String errors_section_recovery;
    public static String errors_recover;
    public static String errors_recover_tip;
    public static String errors_recover_retry;
    public static String errors_recover_retry_tip;
    public static String metadata_tab;
    public static String metadata_section_ddl;
    public static String metadata_section_reading;
    public static String metadata_case_sensitive;
    public static String metadata_case_sensitive_tip;
    public static String metadata_extra_ddl_info;
    public static String metadata_extra_ddl_info_tip;
    public static String metadata_server_side_filters;
    public static String metadata_server_side_filters_tip;
    public static String metadata_expensive_properties;
    public static String metadata_expensive_properties_tip;
    public static String metadata_disable_extra_read;
    public static String metadata_disable_extra_read_tip;
    public static String client_tab;
    public static String client_section_name;
    public static String client_disable;
    public static String client_disable_tip;
    public static String client_override;
    public static String client_name;
    public static String client_name_tip;
    public static String client_section_parameters;
    public static String client_use_variables;
    public static String client_use_variables_tip;
    public static String results_section_reading;
    public static String results_max_rows_sql;
    public static String results_max_rows_sql_tip;
    public static String results_autofetch;
    public static String results_order_mode;
    public static String results_order_mode_tip;
    public static String results_order_smart;
    public static String results_order_client;
    public static String results_order_server;
    public static String results_force_subselect;
    public static String results_force_subselect_tip;
    public static String results_ignore_label;
    public static String results_ignore_label_tip;
    public static String results_transform_complex;
    public static String content_tab;
    public static String content_section_reading;
    public static String content_cache_clob;
    public static String content_cache_blob;
    public static String content_cache_tip;
    public static String content_cache_max;
    public static String content_bytes_hint;
    public static String content_memory_max;
    public static String content_memory_max_tip;
    public static String content_encoding;
    public static String content_encoding_tip;
    public static String conn_tab;
    public static String conn_section_security;
    public static String conn_sanitize;
    public static String conn_sanitize_tip;
    public static String conn_win_trust_store;
    public static String conn_win_trust_store_tip;
    public static String conn_restart_hint;
    public static String conn_section_time;
    public static String conn_timezone;
    public static String conn_timezone_default;
    public static String conn_timezone_hint;
    public static String drivers_tab;
    public static String drivers_section_files;
    public static String drivers_home;
    public static String drivers_home_hint;
    public static String drivers_browse;
    public static String drivers_sources;
    public static String drivers_sources_tip;
    public static String drivers_version_update;
    public static String drivers_version_update_tip;
    public static String drivers_section_proxy;
    public static String drivers_proxy_host;
    public static String drivers_proxy_host_hint;
    public static String drivers_proxy_port;
    public static String drivers_proxy_user;
    public static String drivers_proxy_password;
    public static String drivers_proxy_hint;
    public static String drivers_section_maven;
    public static String drivers_maven_enabled;
    public static String drivers_maven_name;
    public static String drivers_maven_url;
    public static String drivers_maven_type;
    public static String drivers_maven_type_global;
    public static String drivers_maven_type_custom;
    public static String drivers_maven_add;
    public static String drivers_maven_edit;
    public static String drivers_maven_remove;
    public static String drivers_maven_dialog_add;
    public static String drivers_maven_dialog_edit;
    public static String drivers_maven_id;
    public static String drivers_maven_snapshot;
    public static String drivers_maven_scopes;
    public static String drivers_maven_scopes_hint;
    public static String drivers_maven_user;
    public static String drivers_maven_password;
    public static String drivers_maven_required;
    public static String drivers_maven_id_used;
    public static String drivers_section_libraries;
    public static String drivers_libraries_hint;
    public static String drivers_open_manager;
    public static String logs_tab;
    public static String logs_section_sql;
    public static String logs_store;
    public static String logs_store_tip;
    public static String logs_days;
    public static String logs_folder;
    public static String logs_folder_hint;
    public static String logs_objects;
    public static String logs_objects_hint;
    public static String logs_object_session;
    public static String logs_object_txn;
    public static String logs_object_query;
    public static String logs_query_types;
    public static String logs_purpose_user;
    public static String logs_purpose_user_filtered;
    public static String logs_purpose_user_script;
    public static String logs_purpose_util;
    public static String logs_purpose_meta;
    public static String logs_purpose_meta_ddl;
    public static String logs_section_debug;
    public static String logs_debug_size;
    public static String logs_debug_count;
    public static String logs_debug_hint;
    public static String nav_tab;
    public static String nav_section_sort;
    public static String nav_sort_alpha;
    public static String nav_sort_alpha_tip;
    public static String nav_sort_ignore_case;
    public static String nav_folders_first;
    public static String nav_refresh_hint;
    public static String notify_tab;
    public static String notify_long;
    public static String notify_long_tip;
    public static String notify_timeout;
    public static String transfer_tab;
    public static String transfer_section_names;
    public static String transfer_name_case;
    public static String transfer_replace;
    public static String transfer_max_length;
    public static String transfer_max_length_hint;
    public static String transfer_hint;
    public static String completion_tab;
    public static String completion_section_names;
    public static String completion_alias;
    public static String completion_alias_none;
    public static String completion_short_names;
    public static String completion_long_names;
    public static String completion_hint;
    public static String altibase_tab;
    public static String altibase_dbms_output;
    public static String altibase_plan_prefix;
    public static String cubrid_tab;
    public static String cubrid_dbms_output;
    public static String cubrid_buffer;
    public static String mimer_tab;
    public static String mimer_section_cascade;
    public static String mimer_cascade_hint;
    public static String mimer_section_gis;
    public static String mimer_gis_location;
    public static String mimer_gis_coordinate;
    public static String mimer_gis_latlong;
    public static String mimer_gis_grid_text;

    static {
        NLS.initializeMessages(BUNDLE_NAME, SettingsMessages.class);
    }

    private SettingsMessages() {
    }
}
