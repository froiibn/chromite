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
 * Swing 화면 문구: 연결 대화상자(Connect, 네트워크 핸들러 탭, 드라이버 고르기, 시작 쿼리), 연결 명령, 작업 모니터, 개체 찾기.
 *
 * 설계: DBeaver의 {@code *Messages} 관례(예: {@code SQLEditorMessages})를 따른다. 영어가 기본({@code ConnectionMessages.properties}),
 * 한국어는 {@code ConnectionMessages_ko.properties}이고 언어는 OS 언어를 따른다(따로 언어 설정을 두지 않는다).
 * 화면 영역마다 클래스 하나를 두어 여러 작업이 같은 파일을 고치지 않게 한다. {@code {0}}이 있는 문구는 {@code NLS.bind}로 쓰므로
 * 그 문구 안의 작은따옴표는 {@code ''}로 적는다. 드라이버·속성·인증 방식의 이름과 설명은 모델 번들이 이미 번역하므로 여기 두지 않는다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 연결 영역 화면 문구를 옮김(OS 언어를 따름)
 * - 2026-11-07 열린 세션에 자동 커밋을 적용할 때의 문구({@code autocommit_*}) (docs/chromite/swing-frontend.md "자동 커밋 상태")
 * - 2026-11-07 유휴 트랜잭션 설정({@code settings_idle_txn_*})과 알림({@code idle_txn_*}) 문구 (docs/chromite/idle-transactions.md)
 * - 2026-11-07 끊기 전 열린 트랜잭션 질문({@code disconnect_txn_*}) (docs/chromite/idle-transactions.md "연결 끊기 전 질문")
 * - 2026-11-07 인증 방식 자격 증명 질문({@code auth_prompt_*})과 JSch SSH 질문({@code ssh_prompt_*}) (docs/chromite/swing-auth-prompts.md)
 * - 2026-11-07 유휴 연결 닫기 도구 설명({@code settings_close_idle_tip}) (docs/chromite/idle-transactions.md "유휴 연결 닫기")
 * - 2026-11-07 트랜잭션 격리 수준({@code settings_isolation*}) (docs/chromite/swing-options.md)
 * - 2026-11-07 쿼리 창마다 별도 연결: 연결별 설정({@code settings_separate_connection*}), 창 세션을 끝내기 전 질문({@code window_txn_*})
 *   (docs/chromite/transactions.md "쿼리 창마다 별도 연결")
 * - 2026-11-07 오류 복구가 멈췄다는 알림({@code recovery_*}) (docs/chromite/transactions.md "오류 복구와 열린 트랜잭션")
 * - 2026-11-08 Oracle 세션 설정 탭({@code driver_oracle_session_tab}, {@code driver_oracle_nls_*}) (docs/chromite/swing-conn-advanced.md "Oracle 세션 설정")
 * - 2026-11-08 끊긴 세션만 다시 열었다는 알림({@code recovery_session_*})과 끊긴 세션의 트랜잭션을 버리는 질문({@code lost_txn_*})
 *   (docs/chromite/transactions.md "끊긴 세션의 쿼리 창")
 * - 2026-11-08 연결별 설정 탭({@code settings_tab_settings}) (docs/chromite/swing-options.md "연결별 설정")
 * - 2026-11-08 연결 설명({@code settings_description*}), DB별 칸({@code driver_bigquery_*}, {@code driver_snowflake_*}, {@code driver_hana_*},
 *   {@code driver_firebird_*}, {@code driver_mimer_*}), 인증 칸({@code auth_bigquery_*}, {@code auth_snowflake_*}, {@code auth_spanner_*},
 *   {@code auth_key_file}), SSL({@code ssl_proxy_server*}, {@code ssl_clickhouse_mode_tip}, {@code ssl_enable_only})
 *   (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-08 로컬 클라이언트({@code settings_local_client*}) (docs/chromite/swing-database-tasks.md "연결 편집기의 로컬 클라이언트")
 * - 2026-11-08 셸 명령 탭({@code shell_*}), 비밀번호 바꾸기({@code commands_change_password*}), 점프 서버({@code ssh_jump_*}),
 *   네트워크 프로필({@code profiles_*}) (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-08 연결별 제한({@code settings_restrictions*}, {@code restrictions_*})과 잠긴 연결({@code lock_*})
 *   (docs/chromite/swing-conn-advanced.md "연결별 제한과 연결 잠금")
 * - 2026-11-09 조회만 한 트랜잭션을 끝내려 할 때 변경이 생겼으면 묻는 문구({@code idle_txn_read_only_changed}), 조회 트랜잭션의
 *   유휴 알림({@code idle_txn_reminder_message_read_only})
 *   (docs/chromite/idle-transactions.md "조회만 한 트랜잭션 끝내기")
 * - 2026-11-12 JSch 호스트 키·known_hosts 질문의 본문({@code ssh_prompt_host_key_unknown}, {@code ssh_prompt_host_key_changed},
 *   {@code ssh_prompt_create_file}, {@code ssh_prompt_create_folder}) (docs/chromite/swing-auth-prompts.md, 앱 변경 #81)
 * - 2026-11-12 아이콘 단추의 도구 설명에만 쓰던 설명문 키를 지움(도구 설명이 메뉴의 이름이 됨) (앱 변경 #103, docs/chromite/swing-frontend.md "아이콘 단추의 도구 설명")
 */
public class ConnectionMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.ConnectionMessages"; //$NON-NLS-1$

    public static String common_ok;
    public static String common_cancel;
    public static String common_browse;
    public static String common_add;
    public static String common_edit;
    public static String common_remove;
    public static String common_refresh;
    public static String common_connecting;
    public static String common_port;
    public static String common_user_name;
    public static String common_status_error;
    public static String common_status_updated;

    public static String dialog_title_connect;
    public static String dialog_title_edit;
    public static String dialog_button_test;
    public static String dialog_button_connect;
    public static String dialog_button_save;
    public static String dialog_button_options_show;
    public static String dialog_button_options_hide;
    public static String dialog_button_options_tip;
    public static String dialog_new_connection;
    public static String dialog_no_drivers;
    public static String dialog_test_title;
    public static String dialog_test_connect_error;
    public static String dialog_test_result;
    public static String dialog_test_result_network;
    public static String dialog_test_error;
    public static String dialog_create_failed_title;
    public static String dialog_create_failed;
    public static String dialog_save_failed_title;
    public static String dialog_save_failed;
    public static String dialog_changed_title;
    public static String dialog_changed_reconnect;

    public static String settings_database_type;
    public static String settings_server_name;
    public static String settings_server_instance;
    public static String settings_server_placeholder;
    public static String settings_server_placeholder_port;
    public static String settings_server_tip;
    public static String settings_server_tip_port;
    public static String settings_database;
    public static String settings_database_list_tip;
    public static String settings_database_list_tip_optional;
    public static String settings_database_list_loading;
    public static String settings_database_list_error;
    public static String settings_database_list_empty;
    public static String settings_folder;
    public static String settings_file;
    public static String settings_jdbc_url;
    public static String settings_authentication;
    public static String settings_login;
    public static String settings_password;
    public static String settings_remember_password;
    public static String settings_connection_name;
    public static String settings_connection_name_placeholder;
    public static String settings_tab_connection_properties;
    public static String settings_tab_settings;
    public static String settings_tab_driver_properties;
    public static String settings_tab_provider_properties;
    public static String settings_tab_additional;
    public static String settings_driver_properties_deferred;
    public static String settings_driver_properties_loading;
    public static String settings_driver_properties_task;
    public static String settings_driver_properties_error;
    public static String settings_enter_url;
    public static String settings_enter_server_name;
    public static String settings_enter_port;
    public static String settings_enter_database;
    public static String settings_enter_server;
    public static String settings_enter_file;
    public static String settings_enter_folder;
    public static String settings_port_not_number;
    public static String settings_description;
    public static String settings_description_tip;
    public static String settings_connection_type;
    public static String settings_auto_commit;
    public static String settings_auto_commit_default;
    public static String settings_auto_commit_auto;
    public static String settings_auto_commit_manual;
    public static String settings_isolation;
    public static String settings_isolation_tip;
    public static String settings_isolation_default;
    public static String settings_separate_connection;
    public static String settings_separate_connection_tip;
    public static String settings_separate_connection_global;
    public static String settings_separate_connection_always;
    public static String settings_separate_connection_never;
    public static String settings_default_database;
    public static String settings_default_database_tip;
    public static String settings_default_schema;
    public static String settings_default_schema_tip;
    public static String settings_read_only;
    public static String settings_restrictions;
    public static String settings_restrictions_tip;
    public static String settings_restrictions_type;
    public static String settings_restrictions_own;
    public static String restrictions_title;
    public static String restrictions_use_type;
    public static String restrictions_own;
    public static String restrictions_none_note;
    public static String lock_title;
    public static String lock_prompt;
    public static String lock_password;
    public static String lock_wrong_password;
    public static String lock_check_error;
    public static String settings_color;
    public static String settings_custom_color;
    public static String settings_custom_color_tip;
    public static String settings_color_change;
    public static String settings_color_none;
    public static String settings_color_type;
    public static String settings_color_dialog;
    public static String settings_bootstrap;
    public static String settings_bootstrap_edit_count;
    public static String settings_local_client;
    public static String settings_local_client_none;
    public static String settings_local_client_missing;
    public static String settings_local_client_tip;
    public static String settings_keep_alive;
    public static String settings_keep_alive_tip;
    public static String settings_close_idle;
    public static String settings_close_idle_tip;
    public static String settings_idle_txn_remind;
    public static String settings_idle_txn_remind_tip;
    public static String settings_idle_txn_rollback;
    public static String settings_idle_txn_rollback_warning;
    public static String settings_connect_with;
    public static String settings_mode_fields;
    public static String settings_url_note;
    public static String settings_chooser_folder;
    public static String settings_chooser_file;

    public static String driver_encrypt;
    public static String driver_encrypt_tip;
    public static String driver_encrypt_optional;
    public static String driver_encrypt_mandatory;
    public static String driver_encrypt_strict;
    public static String driver_trust_certificate;
    public static String driver_trust_certificate_tip;
    public static String driver_oracle_basic;
    public static String driver_oracle_type_tip;
    public static String driver_oracle_sid_service_tip;
    public static String driver_oracle_alias;
    public static String driver_oracle_tns_folder;
    public static String driver_oracle_tns_folder_placeholder;
    public static String driver_oracle_tns_folder_tip;
    public static String driver_oracle_enter_alias;
    public static String driver_oracle_session_tab;
    public static String driver_oracle_nls_default;
    public static String driver_oracle_nls_language;
    public static String driver_oracle_nls_language_tip;
    public static String driver_oracle_nls_territory;
    public static String driver_oracle_nls_territory_tip;
    public static String driver_oracle_nls_date_format;
    public static String driver_oracle_nls_date_format_tip;
    public static String driver_oracle_nls_timestamp_format;
    public static String driver_oracle_nls_timestamp_format_tip;
    public static String driver_oracle_nls_length;
    public static String driver_oracle_nls_length_tip;
    public static String driver_oracle_nls_currency;
    public static String driver_oracle_nls_currency_tip;
    public static String driver_oracle_nls_hint;
    public static String driver_bigquery_project;
    public static String driver_bigquery_project_tip;
    public static String driver_bigquery_enter_project;
    public static String driver_snowflake_warehouse;
    public static String driver_snowflake_warehouse_tip;
    public static String driver_snowflake_schema;
    public static String driver_snowflake_schema_tip;
    public static String driver_hana_edition;
    public static String driver_hana_edition_generic;
    public static String driver_hana_edition_single;
    public static String driver_hana_edition_system;
    public static String driver_hana_edition_tenant;
    public static String driver_hana_edition_express;
    public static String driver_hana_edition_cloud;
    public static String driver_hana_instance;
    public static String driver_hana_instance_tip;
    public static String driver_hana_tenant_database;
    public static String driver_hana_enter_instance;
    public static String driver_hana_enter_database;
    public static String driver_hana_port_hint;
    public static String driver_firebird_library;
    public static String driver_firebird_library_tip;
    public static String driver_firebird_library_placeholder;
    public static String driver_mimer_protocol;
    public static String driver_mimer_protocol_tip;
    public static String driver_mimer_protocol_tcp;
    public static String driver_mimer_protocol_local;
    public static String driver_mimer_program;
    public static String driver_mimer_program_tip;
    public static String driver_mimer_program_password;

    public static String auth_integrated_security;
    public static String auth_integrated_security_tip;
    public static String auth_role;
    public static String auth_role_tip;
    public static String auth_pgpass_override;
    public static String auth_pgpass_override_tip;
    public static String auth_key_file;
    public static String auth_bigquery_oauth_type;
    public static String auth_bigquery_service_based;
    public static String auth_bigquery_user_based;
    public static String auth_bigquery_key_file_tip;
    public static String auth_snowflake_role_tip;
    public static String auth_snowflake_authenticator;
    public static String auth_snowflake_authenticator_tip;
    public static String auth_snowflake_authenticator_snowflake;
    public static String auth_snowflake_authenticator_browser;
    public static String auth_spanner_key_file_tip;

    public static String commands_no_project;
    public static String commands_connect_title;
    public static String commands_connect_error;
    public static String commands_delete_title;
    public static String commands_delete_confirm;
    public static String commands_rename_title;
    public static String commands_rename_prompt;
    public static String commands_rename_error;

    public static String network_use_handler;
    public static String network_profile;

    public static String properties_user_category;
    public static String properties_column_category;
    public static String properties_column_property;
    public static String properties_column_value;
    public static String properties_add_tip;
    public static String properties_remove_tip;
    public static String properties_reset;
    public static String properties_reset_tip;
    public static String properties_add_title;
    public static String properties_add_prompt;

    public static String ssh_host;
    public static String ssh_auth_public_key;
    public static String ssh_auth_agent;
    public static String ssh_private_key;
    public static String ssh_private_key_file;
    public static String ssh_passphrase;
    public static String ssh_agent_socket;
    public static String ssh_agent_socket_tip;
    public static String ssh_jump_hosts;
    public static String ssh_implementation;
    public static String ssh_keep_alive;
    public static String ssh_connect_timeout;
    public static String ssh_local_host;
    public static String ssh_local_host_tip;
    public static String ssh_local_port;
    public static String ssh_local_port_tip;
    public static String ssh_remote_host;
    public static String ssh_remote_host_tip;
    public static String ssh_remote_port;
    public static String ssh_remote_port_tip;
    public static String ssh_bypass_verification;
    public static String ssh_bypass_verification_tip;
    public static String ssh_share_tunnel;
    public static String ssh_share_tunnel_tip;
    public static String ssh_test;
    public static String ssh_enter_host;
    public static String ssh_choose_private_key;
    public static String ssh_tunnel_title;
    public static String ssh_test_task;
    public static String ssh_test_open;
    public static String ssh_test_close;
    public static String ssh_test_result;
    public static String ssh_test_error;
    public static String ssh_prompt_title;
    public static String ssh_prompt_password;
    public static String ssh_prompt_passphrase;
    public static String ssh_prompt_host_key_title;
    public static String ssh_prompt_host_key_unknown;
    public static String ssh_prompt_host_key_changed;
    public static String ssh_prompt_create_file;
    public static String ssh_prompt_create_folder;
    public static String auth_prompt_title;
    public static String auth_prompt_description;

    public static String ssl_keystore;
    public static String ssl_keystore_file;
    public static String ssl_keystore_tip;
    public static String ssl_keystore_password;
    public static String ssl_certificate_host;
    public static String ssl_certificate_host_tip;
    public static String ssl_ca_certificate;
    public static String ssl_ca_certificate_file;
    public static String ssl_client_certificate;
    public static String ssl_client_certificate_file;
    public static String ssl_client_key;
    public static String ssl_client_key_file;
    public static String ssl_cipher_suites;
    public static String ssl_cipher_suites_tip;
    public static String ssl_require;
    public static String ssl_require_tip;
    public static String ssl_verify_certificate;
    public static String ssl_verify_certificate_tip;
    public static String ssl_public_key_retrieval;
    public static String ssl_public_key_retrieval_tip;
    public static String ssl_mode;
    public static String ssl_mode_driver_default;
    public static String ssl_factory;
    public static String ssl_factory_tip;
    public static String ssl_proxy_server;
    public static String ssl_proxy_server_tip;
    public static String ssl_clickhouse_mode_tip;
    public static String ssl_enable_only;

    public static String socks_host;
    public static String socks_enter_host;

    public static String bootstrap_title;
    public static String bootstrap_hint;
    public static String bootstrap_ignore_errors;
    public static String bootstrap_query_title;

    public static String driver_select_title;
    public static String driver_select_search;
    public static String driver_select_all_categories;

    public static String database_type_more;
    public static String database_type_more_tip;
    public static String database_type_dialog_title;

    public static String drivers_download_title;
    public static String drivers_download_error;
    public static String drivers_download_missing;
    public static String drivers_download_question;
    public static String drivers_download_button;

    public static String activity_title;
    public static String activity_tooltip;
    public static String activity_tab_processes;
    public static String activity_tab_locks;
    public static String activity_not_connected;

    public static String auto_refresh_off;
    public static String auto_refresh_seconds;
    public static String auto_refresh_minutes;
    public static String auto_refresh_tip;

    public static String sessions_search_placeholder;
    public static String sessions_only_mine;
    public static String sessions_only_mine_no_user;
    public static String sessions_only_mine_tip;
    public static String sessions_tab_sql;
    public static String sessions_tab_details;
    public static String sessions_reading;
    public static String sessions_not_supported;
    public static String sessions_open_query;
    public static String sessions_action_confirm_one;
    public static String sessions_action_confirm_many;
    public static String sessions_action_running;
    public static String sessions_action_error_one;
    public static String sessions_action_error_many;
    public static String sessions_details_error;
    public static String sessions_count_one;
    public static String sessions_count_many;
    public static String sessions_count_shown;

    public static String locks_kill;
    public static String locks_kill_tip;
    public static String locks_blocking_chains;
    public static String locks_reading;
    public static String locks_not_available;
    public static String locks_none_waiting;
    public static String locks_waiting_one;
    public static String locks_waiting_many;
    public static String locks_of_session_waiting;
    public static String locks_of_session_blocking;
    public static String locks_of_blocking_session;
    public static String locks_kill_confirm;
    public static String locks_kill_error;
    public static String locks_tree_blocking;
    public static String locks_tree_waiting;

    public static String search_title;
    public static String search_tooltip;
    public static String search_type_views;
    public static String search_type_tables;
    public static String search_type_procedures;
    public static String search_type_columns;
    public static String search_type_other;
    public static String search_match_contains;
    public static String search_match_starts_with;
    public static String search_match_like;
    public static String search_case_sensitive;
    public static String search_button;
    public static String search_button_stop;
    public static String search_name_placeholder;
    public static String search_label_name;
    public static String search_label_types;
    public static String search_label_connections;
    public static String search_label_scope;
    public static String search_connects_when_searching;
    public static String search_only_in;
    public static String search_result_tip;
    public static String search_select_type;
    public static String search_select_connection;
    public static String search_searching;
    public static String search_searching_count;
    public static String search_not_connected;
    public static String search_not_supported;
    public static String search_none_found;
    public static String search_found_one;
    public static String search_found_many;
    public static String search_stopped;
    public static String search_column_name;
    public static String search_column_type;
    public static String search_column_full_name;
    public static String search_column_connection;

    // Auto-commit applied to open sessions (AutoCommitApplier)
    public static String autocommit_title;
    public static String autocommit_pending_message;
    public static String autocommit_pending_cancel_note;
    public static String autocommit_commit_apply;
    public static String autocommit_rollback_apply;
    public static String autocommit_error_message;
    public static String autocommit_other_session;

    // Idle open transactions (SwingIdleTransactionService)
    public static String idle_txn_reminder_title;
    public static String idle_txn_reminder_message;
    public static String idle_txn_reminder_message_read_only;
    public static String idle_txn_commit;
    public static String idle_txn_rollback;
    public static String idle_txn_keep_open;
    public static String idle_txn_rolled_back_title;
    public static String idle_txn_rolled_back_message;
    public static String idle_txn_rollback_failed_title;
    public static String idle_txn_rollback_failed_message;
    public static String idle_txn_windows;
    public static String idle_txn_session;
    public static String idle_txn_seconds;
    public static String idle_txn_minutes;
    public static String idle_txn_hours;
    public static String idle_txn_title;
    public static String idle_txn_not_ended;
    public static String idle_txn_read_only_changed;
    public static String idle_txn_error;
    public static String recovery_aborted_title;
    public static String recovery_aborted_message;
    public static String recovery_reconnect_title;
    public static String recovery_reconnect_message;
    public static String recovery_reconnect_item;
    public static String recovery_reconnect_agent;
    public static String recovery_reconnect;
    public static String recovery_reconnect_error;
    public static String recovery_session_lost_title;
    public static String recovery_session_lost_message;
    public static String recovery_session_reopened_title;
    public static String recovery_session_reopened_message;

    // Open transactions before a disconnect (ExitTransactionCheck.confirmDisconnect)
    public static String disconnect_txn_message;
    public static String disconnect_txn_error;
    // ... and before a query window's own session ends (ExitTransactionCheck.confirmEndSession)
    public static String window_txn_message;
    public static String window_txn_error;
    // ... when sessions with open transactions lost their connection (ExitTransactionCheck)
    public static String lost_txn_checking;
    public static String lost_txn_message;
    public static String lost_txn_note;
    public static String lost_txn_discard;

    public static String settings_tab_shell_commands;
    public static String shell_event;
    public static String shell_command;
    public static String shell_command_tip;
    public static String shell_working_directory;
    public static String shell_wait_finish;
    public static String shell_wait_timeout;
    public static String shell_wait_timeout_tip;
    public static String shell_terminate_at_disconnect;
    public static String shell_terminate_at_disconnect_tip;
    public static String shell_pause;
    public static String shell_pause_tip;
    public static String shell_clear;
    public static String shell_approval_note;
    public static String commands_change_password;
    public static String commands_change_password_menu;
    public static String commands_change_password_error;
    public static String ssh_jump_servers;
    public static String ssh_jump_none;
    public static String ssh_jump_edit;
    public static String ssh_jump_title;
    public static String ssh_jump_server_title;
    public static String ssh_jump_hint;
    public static String ssh_jump_up;
    public static String ssh_jump_down;
    public static String profiles_menu;
    public static String profiles_menu_tip;
    public static String profiles_title;
    public static String profiles_scope;
    public static String profiles_scope_global;
    public static String profiles_scope_project;
    public static String profiles_copy;
    public static String profiles_name;
    public static String profiles_name_used;
    public static String profiles_delete_used;
    public static String profiles_delete_confirm;
    public static String profiles_save_error;
    public static String profiles_none;
    public static String profiles_global_item;
    public static String profiles_connection;
    public static String profiles_connection_tip;
    public static String profiles_manage;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ConnectionMessages.class);
    }

    private ConnectionMessages() {
    }
}
