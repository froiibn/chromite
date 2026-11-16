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
 * Swing 도구 화면(데이터 내보내기/가져오기 마법사, 테이블 디자이너, 쿼리 라이브러리와 예약 실행)의 화면 문구.
 *
 * 설계: DBeaver의 *Messages 관례(SQLEditorMessages 등)를 따라 {@link NLS} 묶음 하나에 둔다. 영어가 기본
 * ({@code ToolsMessages.properties}), 한국어는 {@code ToolsMessages_ko.properties}이고, 플랫폼이 OS 언어로 고른다
 * (언어 설정은 따로 두지 않는다). 키는 {@code 영역_구성요소_항목} 형식이고 영역 접두사는 export, import, transfer, wizard,
 * designer, script, library, param, schedule, scheduled, history, button이다. {0} 자리표시자가 있는 값은
 * {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 * 파일 형식(쿼리 라이브러리 머리말 키, 기록 .tsv의 상태 이름)과 로그, 작업(Job) 이름은 옮기지 않는다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 도구 화면 문구를 이 묶음으로 옮김(OS 언어를 따름)
 * - 2026-11-12 아이콘 단추의 도구 설명에만 쓰던 설명문 키를 지움(도구 설명이 메뉴의 이름이 됨) (앱 변경 #103, docs/chromite/swing-frontend.md "아이콘 단추의 도구 설명")
 */
public class ToolsMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.ToolsMessages"; //$NON-NLS-1$

    public static String button_ok;
    public static String button_cancel;
    public static String button_close;
    public static String button_browse;

    public static String transfer_state_waiting;
    public static String transfer_state_running;
    public static String transfer_state_success;
    public static String transfer_state_error;
    public static String transfer_state_canceled;
    public static String transfer_state_not_run;

    public static String wizard_button_back;
    public static String wizard_button_next;
    public static String wizard_button_finish;

    public static String transfer_progress_canceling;
    public static String transfer_progress_starting;
    public static String transfer_progress_success_one;
    public static String transfer_progress_success;
    public static String transfer_progress_canceled;
    public static String transfer_progress_failed;
    public static String transfer_page_progress_title;
    public static String transfer_page_progress_description;
    public static String transfer_column_source;
    public static String transfer_column_destination;
    public static String transfer_column_status;
    public static String transfer_column_rows;
    public static String transfer_column_message;

    public static String export_output_bom;
    public static String export_output_single_file;
    public static String export_output_compress;
    public static String export_output_clipboard;
    public static String export_output_open_folder;
    public static String export_output_delete_on_failure;
    public static String export_title;
    public static String export_error_read_tables;
    public static String export_error_nothing;
    public static String export_filter_line;
    public static String export_error_configure;
    public static String export_target_clipboard;
    public static String export_source_query_note;
    public static String export_source_filter_note;
    public static String export_source_select_all;
    public static String export_source_deselect_all;
    public static String export_source_query_title;
    public static String export_source_tables_title;
    public static String export_source_query_description;
    public static String export_source_tables_description;
    public static String export_source_error_none;
    public static String export_source_column_source;
    public static String export_source_column_type;
    public static String export_format_title;
    public static String export_format_description;
    public static String export_format_error_none;
    public static String export_lob_skip;
    public static String export_lob_files;
    public static String export_lob_inline;
    public static String export_encoding_base64;
    public static String export_encoding_hex;
    public static String export_encoding_binary;
    public static String export_encoding_native;
    public static String export_settings_value_format;
    public static String export_settings_value_format_note;
    public static String export_settings_lob;
    public static String export_settings_lob_note;
    public static String export_settings_lob_encoding;
    public static String export_settings_lob_encoding_note;
    public static String export_settings_title;
    public static String export_settings_description;
    public static String export_settings_description_format;
    public static String export_output_conflict_ask;
    public static String export_output_directory;
    public static String export_output_pattern;
    public static String export_output_variables;
    public static String export_output_encoding;
    public static String export_output_conflict;
    public static String export_output_choose_title;
    public static String export_output_choose_button;
    public static String export_output_title;
    public static String export_output_description;
    public static String export_output_error_directory;
    public static String export_output_error_pattern;
    public static String export_output_error_encoding;

    public static String wizard_summary_title;

    public static String export_summary_description;
    public static String export_summary_source;
    public static String export_summary_query_rerun;
    public static String export_summary_format;
    public static String export_summary_value_format;
    public static String export_summary_output;
    public static String export_summary_clipboard;
    public static String export_summary_encoding_bom;
    public static String export_summary_encoding;
    public static String export_summary_single_file;
    public static String export_summary_compress;
    public static String export_summary_open_folder;
    public static String export_value_format_display;
    public static String export_value_format_edit;
    public static String export_value_format_native;

    public static String import_load_truncate;
    public static String import_load_new_connection;
    public static String import_load_transactions;
    public static String import_load_multi_row;
    public static String import_load_disable_batches;
    public static String import_title;
    public static String import_error_no_importers;
    public static String import_error_start;
    public static String import_confirm_truncate;
    public static String import_progress_read;
    public static String import_error_no_columns;
    public static String import_error_read_canceled;
    public static String import_error_read_columns_of;
    public static String import_error_read_columns;
    public static String import_source_refresh_preview;
    public static String import_source_file;
    public static String import_source_format;
    public static String import_source_preview;
    public static String import_source_choose_title;
    public static String import_source_preview_error;
    public static String import_source_title;
    public static String import_source_description;
    public static String import_source_error_none;
    public static String import_source_error_missing;
    public static String import_mapping_note;
    public static String import_mapping_title;
    public static String import_mapping_description;
    public static String import_mapping_error_duplicate;
    public static String import_mapping_error_none;
    public static String import_mapping_column_source;
    public static String import_mapping_column_source_type;
    public static String import_mapping_column_target;
    public static String import_mapping_column_target_type;
    public static String import_load_commit;
    public static String import_load_commit_note;
    public static String import_load_rows_per_insert;
    public static String import_load_note;
    public static String import_load_title;
    public static String import_load_description;
    public static String import_summary_description;
    public static String import_summary_source;
    public static String import_summary_destination;
    public static String import_summary_truncate;
    public static String import_summary_commit;
    public static String import_summary_no_transactions;
    public static String import_summary_multi_row;
    public static String import_summary_one_by_one;

    public static String script_preview_execute_question;
    public static String script_preview_header;
    public static String script_preview_execute;
    public static String script_preview_open;
    public static String script_preview_open_tip;
    public static String script_preview_copy;

    public static String designer_save;
    public static String designer_dont_save;
    public static String designer_save_tip;
    public static String designer_generate_script;
    public static String designer_generate_script_tip;
    public static String designer_revert;
    public static String designer_progress_open;
    public static String designer_new_table;
    public static String designer_error_open;
    public static String designer_label_name;
    public static String designer_tab_title;
    public static String designer_tab_tooltip_new;
    public static String designer_progress_read;
    public static String designer_state_read_only;
    public static String designer_state_new;
    public static String designer_state_dirty;
    public static String designer_title;
    public static String designer_error_read;
    public static String designer_progress_set_primary_key;
    public static String designer_progress_edit;
    public static String designer_progress_rename;
    public static String designer_new_column;
    public static String designer_add_key;
    public static String designer_add_index;
    public static String designer_add_foreign_key;
    public static String designer_progress_delete;
    public static String designer_error_script;
    public static String designer_script_none;
    public static String designer_change_script_title;
    public static String designer_save_changes_title;
    public static String designer_progress_save;
    public static String designer_error_save;
    public static String designer_error_save_partial;
    public static String designer_confirm_revert;
    public static String designer_confirm_close;
    public static String designer_progress_discard;
    public static String designer_error_discard;
    public static String designer_action_add_key;
    public static String designer_action_add_foreign_key;
    public static String designer_action_add_index;
    public static String designer_action_add_tip;
    public static String designer_action_delete;
    public static String designer_action_delete_tip;
    public static String designer_action_read_only;
    public static String designer_action_not_supported;
    public static String designer_sheet_properties;
    public static String designer_sheet_column;
    public static String designer_sheet_key;
    public static String designer_sheet_foreign_key;
    public static String designer_sheet_index;
    public static String designer_section_columns;
    public static String designer_section_keys;
    public static String designer_section_foreign_keys;
    public static String designer_section_indexes;
    public static String designer_section_table;
    public static String designer_error_edit_not_supported;
    public static String designer_error_table_type;
    public static String designer_error_create_not_supported;
    public static String designer_error_not_connected;
    public static String designer_this_connection;
    public static String designer_error_section_not_supported;
    public static String designer_error_key_not_supported;
    public static String designer_error_check_empty;
    public static String designer_error_index_not_supported;
    public static String designer_error_foreign_key_not_supported;
    public static String designer_progress_generate_script;
    public static String designer_error_metadata_restricted;
    public static String designer_dialog_type;
    public static String designer_dialog_key_columns;
    public static String designer_dialog_error_key_type;
    public static String designer_dialog_error_key_name;
    public static String designer_dialog_error_check;
    public static String designer_dialog_error_columns;
    public static String designer_dialog_unique;
    public static String designer_dialog_index_columns;
    public static String designer_dialog_error_index_name;
    public static String designer_dialog_generated_name_tip;
    public static String designer_dialog_no_unique_key;
    public static String designer_dialog_progress_read_keys;
    public static String designer_dialog_error_read_keys;
    public static String designer_dialog_referenced_table;
    public static String designer_dialog_referenced_key;
    public static String designer_dialog_on_delete;
    public static String designer_dialog_on_update;
    public static String designer_dialog_columns;
    public static String designer_dialog_error_no_key;
    public static String designer_dialog_error_mapping;
    public static String designer_dialog_column;
    public static String designer_dialog_order;
    public static String designer_dialog_referenced_column;
    public static String designer_sheet_column_property;
    public static String designer_sheet_column_value;

    public static String library_root_default;
    public static String library_error_move_into_itself;
    public static String library_copy_name;
    public static String library_error_delete_root;
    public static String library_error_not_in_root;
    public static String library_error_read_only_root;
    public static String library_action_run;
    public static String library_action_run_tip;
    public static String library_action_run_on;
    public static String library_action_run_on_tip;
    public static String library_action_edit;
    public static String library_action_edit_tip;
    public static String library_action_new_query;
    public static String library_action_new_query_tip;
    public static String library_action_new_folder;
    public static String library_action_new_folder_tip;
    public static String library_duplicate;
    public static String library_action_duplicate_tip;
    public static String library_action_rename;
    public static String library_action_rename_tip;
    public static String library_confirm_delete_query;
    public static String library_confirm_delete_folder;
    public static String library_delete;
    public static String library_action_delete_tip;
    public static String library_open_folder;
    public static String library_action_open_folder_tip;
    public static String library_action_refresh;
    public static String library_action_refresh_tip;
    public static String library_action_roots;
    public static String library_action_roots_tip;
    public static String library_action_save;
    public static String library_action_save_tip;
    public static String library_action_schedule;
    public static String library_action_schedule_tip;
    public static String library_action_history;
    public static String library_action_history_tip;
    public static String library_action_scheduled;
    public static String library_action_scheduled_tip;
    public static String library_search_placeholder;
    public static String library_run_on_title;
    public static String library_schedule_title;
    public static String library_error_save_schedule;
    public static String library_new_query;
    public static String library_new_query_prompt;
    public static String library_error_create_query;
    public static String library_new_folder;
    public static String library_new_folder_prompt;
    public static String library_error_create_folder;
    public static String library_error_duplicate;
    public static String library_rename;
    public static String library_rename_query_prompt;
    public static String library_rename_folder_prompt;
    public static String library_error_rename;
    public static String library_error_delete;
    public static String library_error_open_folder;
    public static String library_save_title;
    public static String library_save_error_no_window;
    public static String library_save_error_empty;
    public static String library_save_error_no_root;
    public static String library_save_run_on_default;
    public static String library_save_run_on_current;
    public static String library_field_name;
    public static String library_field_description;
    public static String library_field_folder;
    public static String library_error_enter_name;
    public static String library_error_save;
    public static String library_move_title;
    public static String library_error_move;
    public static String library_tree_folder_missing;
    public static String library_tree_read_only;
    public static String library_tree_ask;
    public static String library_editor_connection_missing;
    public static String library_editor_no_current;
    public static String library_editor_run_title;
    public static String library_editor_no_connections;
    public static String library_editor_connection;
    public static String library_editor_read_only;
    public static String library_editor_save;
    public static String library_error_read;
    public static String library_error_save_to;
    public static String library_editor_target_tip;
    public static String library_editor_name_tip;
    public static String library_editor_description_tip;
    public static String library_editor_database_tip;
    public static String library_editor_database;
    public static String library_editor_schema_tip;
    public static String library_editor_schema;
    public static String library_editor_parameters_tip;
    public static String library_editor_target;
    public static String library_editor_parameters_count;
    public static String library_editor_parameters;
    public static String library_editor_list_target;
    public static String library_editor_list_window;
    public static String library_editor_list_missing;
    public static String library_editor_database_unused;
    public static String library_editor_schema_unused;
    public static String library_connection_not_found;
    public static String library_target_ask;
    public static String library_target_current;
    public static String library_target_connection;

    public static String param_type_text;
    public static String param_type_number;
    public static String param_type_date;
    public static String param_type_datetime;
    public static String param_type_boolean;
    public static String param_type_choice;
    public static String param_type_lookup;
    public static String param_error_required;
    public static String param_error_number;
    public static String param_error_date;
    public static String param_error_datetime;
    public static String param_error_boolean;

    public static String history_status_ok;
    public static String history_status_alert;
    public static String history_status_error;
    public static String history_status_skipped;

    public static String library_roots_column_read_only;
    public static String library_roots_title;
    public static String library_roots_add;
    public static String library_roots_choose_title;
    public static String library_roots_remove;
    public static String library_roots_remove_tip;
    public static String library_roots_add_default;
    public static String library_roots_add_default_tip;
    public static String library_roots_hint;

    public static String schedule_condition_rows;
    public static String schedule_condition_first;
    public static String schedule_every_hour;
    public static String schedule_every_hours;
    public static String schedule_every_minute;
    public static String schedule_every_minutes;
    public static String schedule_daily_at;
    public static String schedule_weekdays_at;

    public static String scheduler_skipped_running;
    public static String scheduler_no_target;
    public static String scheduler_connection_not_found;
    public static String scheduler_password_not_saved;

    public static String library_not_connected;

    public static String scheduler_query_empty;
    public static String scheduler_canceled;
    public static String scheduler_result_row;
    public static String scheduler_result_rows;
    public static String scheduler_result_condition_met;
    public static String scheduler_result_condition_not_met;
    public static String scheduler_confirmation_needed;

    public static String schedule_condition_always;
    public static String schedule_choice_rows;
    public static String schedule_choice_first;
    public static String schedule_enabled;
    public static String schedule_every;
    public static String schedule_daily;
    public static String schedule_weekdays;
    public static String schedule_unit_minutes;
    public static String schedule_unit_hours;
    public static String schedule_column_parameter;
    public static String schedule_column_value;
    public static String schedule_active;
    public static String schedule_title;
    public static String schedule_active_tip;
    public static String schedule_condition_tip;
    public static String schedule_when;
    public static String schedule_connections;
    public static String schedule_parameters;
    public static String schedule_alert_when;
    public static String schedule_remove;
    public static String schedule_error_time;
    public static String schedule_error_connections;
    public static String schedule_error_condition;

    public static String scheduled_column_query;
    public static String scheduled_column_schedule;
    public static String scheduled_column_connections;
    public static String scheduled_column_active;
    public static String scheduled_column_next_run;
    public static String scheduled_column_last_run;
    public static String scheduled_column_last_status;
    public static String scheduled_title;
    public static String scheduled_hint;
    public static String scheduled_run_now;
    public static String scheduled_disabled;
    public static String scheduled_running;
    public static String scheduled_in_seconds;
    public static String scheduled_in_minutes;
    public static String scheduled_in_hours;

    public static String history_column_time;
    public static String history_column_server;
    public static String history_column_status;
    public static String history_column_rows;
    public static String history_column_duration;
    public static String history_column_message;
    public static String history_title;
    public static String history_snapshot_label;

    public static String param_dialog_text_tip;
    public static String param_dialog_title;
    public static String param_dialog_hint_text;
    public static String param_dialog_hint_number;
    public static String param_dialog_hint_date;
    public static String param_dialog_hint_datetime;
    public static String param_dialog_loading;
    public static String param_dialog_error_values;
    public static String param_dialog_values;
    public static String param_definitions_column_parameter;
    public static String param_definitions_column_label;
    public static String param_definitions_column_type;
    public static String param_definitions_column_default;
    public static String param_definitions_column_required;
    public static String param_definitions_column_choices;
    public static String param_definitions_title;
    public static String param_definitions_hint_none;
    public static String param_definitions_hint;
    public static String param_definitions_text_parameter;
    public static String param_error_unnamed;
    public static String param_error_no_value;
    public static String param_error_value;

    public static String library_action_save_sql;
    public static String library_action_save_sql_tip;
    public static String library_action_export;
    public static String library_action_export_tip;
    public static String library_action_import;
    public static String library_action_import_tip;
    public static String library_archive_filter;
    public static String library_export_title;
    public static String library_export_empty;
    public static String library_export_done;
    public static String library_export_error;
    public static String library_import_title;
    public static String library_import_error;
    public static String library_import_empty;
    public static String library_import_error_too_many;
    public static String library_import_error_too_large;
    public static String library_import_error_file_too_large;
    public static String library_import_error_bad_path;
    public static String library_import_clash;
    public static String library_import_clash_read_only;
    public static String library_import_clash_all;
    public static String library_import_overwrite;
    public static String library_import_copy;
    public static String library_import_skip;
    public static String library_import_done;
    public static String library_import_failed;
    public static String library_save_sql_title;
    public static String library_save_sql_filter;
    public static String library_save_sql_exists;
    public static String library_save_sql_in_root;
    public static String library_conflict_title;
    public static String library_conflict_message;
    public static String library_conflict_overwrite;
    public static String library_conflict_reload;
    public static String library_conflict_deleted;
    public static String library_disk_reloaded;
    public static String library_disk_changed;
    public static String library_disk_missing;
    public static String library_disk_reload;
    public static String library_disk_reload_tip;
    public static String library_disk_dismiss;
    public static String library_git_pull;
    public static String library_git_pull_tip;
    public static String library_git_commit_push;
    public static String library_git_commit_push_title;
    public static String library_git_commit_push_tip;
    public static String library_git_missing;
    public static String library_git_read_only;
    public static String library_git_pull_done;
    public static String library_git_pull_failed;
    public static String library_git_conflicts;
    public static String library_git_unresolved;
    public static String library_git_nothing;
    public static String library_git_commit_title;
    public static String library_git_message;
    public static String library_git_default_message;
    public static String library_git_changes;
    public static String library_git_no_changes;
    public static String library_git_credentials_hint;
    public static String library_git_pushed;
    public static String library_git_push_failed;
    public static String library_git_failed;
    public static String library_tree_conflict;
    public static String library_tree_conflict_tip;
    public static String library_tree_git_detached;
    public static String library_tree_git_conflicts;
    public static String library_roots_secrets_note;
    public static String param_definitions_secret_default;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ToolsMessages.class);
    }

    private ToolsMessages() {
    }
}
