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
 * Swing 프런트엔드 공통 화면(메인 창, 메뉴, Object Explorer, Registered Servers, Properties, Options, About,
 * Find/Replace, 플랫폼 대화상자)의 화면 문구.
 *
 * 설계: DBeaver의 NLS 관례({@code SQLEditorMessages} 등)를 따른다. 영어가 기본({@code CoreMessages.properties})이고
 * 한국어는 {@code CoreMessages_ko.properties}다. 언어 설정은 따로 두지 않고 OSGi 로캘(OS 언어, {@code -nl})을 따른다.
 * 자리 표시자가 있는 문구는 {@link NLS#bind}로 채우므로 작은따옴표를 두 번 쓴다('').
 * 기능 영역마다 메시지 클래스를 따로 두어(SQL 편집기·결과, 연결 대화상자·관리 도구, 데이터 도구) 병합 충돌을 줄인다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 공통 화면 문구를 이 번들로 옮김(OS 언어를 따름)
 * - 2026-11-07 Results 자동 숨김: 쓰지 않게 된 빈 Results 자리 표시 문구를 지우고, 쿼리 창이 없을 때 Results 토글의 도구 설명을 추가
 * - 2026-11-07 Options의 프로시저·함수 제안 설정 문구 추가
 * - 2026-11-07 Options의 날짜/시간 데이터베이스 형식 설정 문구 추가
 * - 2026-11-07 진행 대화상자의 취소 중 문구, 탭 넘침 단추의 도구 설명 추가
 * - 2026-11-07 Options의 결과 표 저장 전 스크립트 미리 보기 설정 문구 추가
 * - 2026-11-07 Options의 위험한 문(WHERE 없는 UPDATE/DELETE, DROP) 확인 설정 문구 추가 (docs/chromite/swing-sql-exec-safety.md)
 * - 2026-11-07 Query 메뉴의 Commit / Roll Back / Transaction Log, Tools 메뉴의 Open Transactions 문구 추가 (docs/chromite/transactions.md)
 * - 2026-11-07 Options의 위험한 SQL 확인 문구에 TRUNCATE를 더함 (docs/chromite/swing-sql-exec-safety.md)
 * - 2026-11-08 파일 덮어쓰기 확인의 바꾸기 단추 문구 추가 (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 * - 2026-11-08 View DDL의 Oracle DDL 형식 콤보 문구 추가 (docs/chromite/swing-routines.md)
 * - 2026-11-08 View &gt; Compact Mode 문구 추가 (docs/chromite/design-spec.md "적용 현황 — 촘촘한 모드")
 * - 2026-11-08 Outline 도구 창, Query &gt; Pin Results, View &gt; Results Beside Editor·Maximize Results 문구
 *   (docs/chromite/swing-sql-editor-ergonomics.md)
 */
public class CoreMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.CoreMessages"; //$NON-NLS-1$

    public static String common_ok;
    public static String common_cancel;
    public static String common_save;
    public static String common_dont_save;
    public static String common_replace;
    public static String common_error;
    public static String common_input;
    public static String common_loading;
    public static String common_no_active_project;
    public static String common_select_connection_first;
    public static String progress_canceling;

    public static String main_results;
    public static String main_messages;
    public static String main_execution_plan;
    public static String main_results_unavailable_tip;
    public static String main_object_explorer;
    public static String main_registered_servers;
    public static String main_properties;
    public static String main_query_library;
    public static String main_new_query;
    public static String main_new_query_tip;
    public static String main_open_file;
    public static String main_open_file_tip;
    public static String main_save_tip;
    public static String main_save_as;
    public static String main_save_as_tip;
    public static String main_execute;
    public static String main_execute_tip;
    public static String main_cancel_query;
    public static String main_complete;
    public static String main_complete_tip;
    public static String main_estimated_plan;
    public static String main_estimated_plan_tip;
    public static String main_actual_plan;
    public static String main_actual_plan_tip;
    public static String main_status_connection;
    public static String main_status_connections;
    public static String main_activity_monitor;
    public static String main_open_file_title;
    public static String main_file_not_found;
    public static String main_cant_open;
    public static String main_save_as_title;
    public static String main_file_exists;
    public static String main_cant_save;
    public static String main_sql_files;
    public static String main_save_changes;
    public static String main_save_result_changes;
    public static String main_results_being_saved;
    public static String main_result_change;
    public static String main_result_changes;
    public static String main_save_following;
    public static String main_welcome_hint;
    public static String main_welcome_next;
    public static String main_menu_file;
    public static String main_menu_exit;
    public static String main_menu_recent_files;
    public static String main_menu_query;
    public static String main_auto_complete;
    public static String main_auto_complete_tip;
    public static String main_query_history;
    public static String main_query_history_tip;
    public static String main_commit;
    public static String main_commit_tip;
    public static String main_rollback;
    public static String main_rollback_tip;
    public static String main_transaction_log;
    public static String main_transaction_log_tip;
    public static String main_outline;
    public static String main_pin_results;
    public static String main_pin_results_tip;
    public static String main_results_beside;
    public static String main_results_beside_tip;
    public static String main_maximize_results;
    public static String main_maximize_results_tip;
    public static String main_menu_view;
    public static String main_about;
    public static String main_menu_help;
    public static String main_options;
    public static String main_locks;
    public static String main_find_objects;
    public static String main_open_transactions;
    public static String main_open_transactions_tip;
    public static String main_menu_tools;
    public static String main_menu_window;
    public static String main_reset_layout;
    public static String main_show_hidden_tabs;
    public static String main_menu_theme;
    public static String main_menu_icon_style;
    public static String main_menu_compact_mode;

    public static String explorer_refresh;
    public static String explorer_refresh_tip;
    public static String explorer_collapse_all;
    public static String explorer_filter;
    public static String explorer_filter_tip;
    public static String explorer_filter_title;
    public static String explorer_filter_prompt;
    public static String explorer_select_top;
    public static String explorer_view_ddl;
    public static String explorer_activity_monitor_tip;
    public static String explorer_locks_tip;
    public static String explorer_find_objects_tip;
    public static String explorer_design;
    public static String explorer_design_tip;
    public static String explorer_new_table;
    public static String explorer_script_as;
    public static String explorer_create_to;
    public static String explorer_select_to;
    public static String explorer_new_query_window;
    public static String explorer_clipboard;
    public static String explorer_script_create;
    public static String explorer_script_select;
    public static String explorer_cant_generate;
    public static String explorer_tasks;
    public static String explorer_import_data;
    public static String explorer_export_data;
    public static String explorer_new_connection;
    public static String explorer_new_connection_tip;
    public static String explorer_connect_server;
    public static String explorer_connect_server_tip;
    public static String explorer_connect;
    public static String explorer_disconnect;
    public static String explorer_disconnect_tip;
    public static String explorer_reconnect;
    public static String explorer_edit_connection;
    public static String explorer_rename;
    public static String explorer_delete;
    public static String explorer_cant_read;

    public static String properties_no_selection;
    public static String properties_none;
    public static String properties_error;
    public static String properties_categorized;
    public static String properties_alphabetical;

    public static String options_title;
    public static String options_general;
    public static String options_editor;
    public static String options_editor_colors;
    public static String options_templates;
    public static String options_environment;
    public static String options_theme;
    public static String options_icon_style;
    public static String options_query_editor;
    public static String options_auto_complete;
    public static String options_completion_delay;
    public static String options_completion_delay_hint;
    public static String options_show_procedures;
    public static String options_show_procedures_tip;
    public static String options_confirm_dangerous_sql;
    public static String options_confirm_dangerous_sql_tip;
    public static String options_confirm_drop_sql;
    public static String options_confirm_drop_sql_tip;
    public static String options_query_results;
    public static String options_max_rows;
    public static String options_max_rows_hint;
    public static String options_native_datetime;
    public static String options_native_datetime_tip;
    public static String options_confirm_before_save;
    public static String options_confirm_before_save_tip;
    public static String options_query_history;
    public static String options_keep_history;
    public static String options_keep_history_hint;
    public static String options_query_library;
    public static String options_keep_runs;
    public static String options_keep_runs_hint;
    public static String options_keep_days;
    public static String options_keep_days_hint;
    public static String options_library_refresh;
    public static String options_library_refresh_hint;
    public static String options_font;
    public static String options_family;
    public static String options_family_hint;
    public static String options_size;
    public static String options_size_hint;
    public static String options_formatting;
    public static String options_style;
    public static String options_style_hint;
    public static String options_formatter_default;
    public static String options_formatter_compact;
    public static String options_keyword_case;
    public static String options_keyword_case_hint;
    public static String options_case_database;
    public static String options_case_upper;
    public static String options_case_lower;
    public static String options_case_as_typed;
    public static String options_reset_defaults;
    public static String options_reset_defaults_tip;
    public static String options_color;
    public static String options_bold;
    public static String options_italic;
    public static String options_preview;

    public static String editor_colors_text;
    public static String editor_colors_keyword;
    public static String editor_colors_operator;
    public static String editor_colors_function;
    public static String editor_colors_string;
    public static String editor_colors_number;
    public static String editor_colors_comment;
    public static String editor_colors_data_type;
    public static String editor_colors_variable;
    public static String editor_colors_background;
    public static String editor_colors_current_line;
    public static String editor_colors_selection;
    public static String editor_colors_line_numbers;
    public static String editor_colors_current_line_number;

    public static String edit_menu;
    public static String edit_undo;
    public static String edit_redo;
    public static String edit_cut;
    public static String edit_copy;
    public static String edit_paste;
    public static String edit_select_all;
    public static String edit_find;
    public static String edit_replace;

    public static String find_title;
    public static String find_find;
    public static String find_find_what;
    public static String find_replace_with;
    public static String find_match_case;
    public static String find_whole_word;
    public static String find_regex;
    public static String find_find_next;
    public static String find_find_previous;
    public static String find_replace;
    public static String find_replace_all;
    public static String find_close;
    public static String find_not_found;
    public static String find_wrapped_beginning;
    public static String find_wrapped_end;
    public static String find_match;
    public static String find_matches;
    public static String find_invalid_regex;
    public static String find_replaced_one;
    public static String find_replaced_many;

    public static String about_version;
    public static String about_open_in_browser;

    public static String app_error_init;
    public static String app_cant_lock_title;
    public static String app_cant_lock_message;

    public static String platform_stop;
    public static String platform_retry;
    public static String platform_skip;
    public static String platform_skip_all;
    public static String platform_error_during;
    public static String platform_execution_error;
    public static String platform_user_name;
    public static String platform_password;
    public static String platform_save_password;
    public static String platform_authentication;
    public static String platform_old_password;
    public static String platform_new_password;
    public static String platform_confirm_password;
    public static String platform_change_password;
    public static String platform_passwords_mismatch;
    public static String platform_accept;
    public static String platform_decline;
    public static String platform_license_agreement;
    public static String platform_execute_process;
    public static String platform_not_supported;
    public static String platform_external_fs_not_supported;

    public static String theme_light;
    public static String theme_dark;
    public static String icon_style_duotone;
    public static String icon_style_color;

    public static String toolwindow_hide;
    public static String toast_click_details;

    public static String servers_new_registration;
    public static String servers_new_registration_tip;
    public static String servers_new_group;
    public static String servers_new_group_tip;
    public static String servers_properties;
    public static String servers_expand_all;
    public static String servers_move_to;
    public static String servers_top_level;
    public static String servers_new_group_title;
    public static String servers_group_name;
    public static String servers_group_name_in;
    public static String servers_rename_group_title;
    public static String servers_new_name;
    public static String servers_cant_rename_group;
    public static String servers_delete_group_title;
    public static String servers_delete_group_question;
    public static String servers_delete_subgroup;
    public static String servers_delete_subgroups;
    public static String servers_delete_server_moves;
    public static String servers_delete_server_moves_sub;
    public static String servers_delete_servers_move;
    public static String servers_delete_servers_move_sub;
    public static String servers_target_top_level;
    public static String servers_target_group;
    public static String servers_move_group_title;
    public static String servers_top_level_has_group;
    public static String servers_group_has_group;
    public static String servers_cant_move_group;
    public static String servers_connect_title;
    public static String servers_none_registered;
    public static String servers_server;
    public static String servers_enter_group_name;
    public static String servers_group_exists;

    public static String ddl_header;
    public static String ddl_format;
    public static String ddl_format_tip;
    public static String ddl_format_full;
    public static String ddl_format_no_storage;
    public static String ddl_format_compact;
    public static String scripts_cant_generate;
    public static String scripts_none_generated;
    public static String sql_file_invalid_text;
    public static String sql_file_neither;
    public static String sql_file_unmappable;

    static {
        NLS.initializeMessages(BUNDLE_NAME, CoreMessages.class);
    }

    private CoreMessages() {
    }
}
