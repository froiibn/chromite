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
 * 백업·복원 화면(데이터베이스 작업 대화상자, SQL Server 백업·복원, PostgreSQL·MySQL 로컬 클라이언트 백업·복원)의 화면 문구.
 *
 * 설계: 다른 기능과 병합 충돌을 피하려고 이 기능만의 묶음으로 둔다(docs/chromite/swing-l10n.md의 영역 나눔과 같은 방식).
 * 영어가 기본({@code BackupRestoreMessages.properties}), 한국어는 {@code BackupRestoreMessages_ko.properties}이고
 * 플랫폼이 OS 언어로 고른다. 키 접두사는 task(공통 틀), backup, restore(SQL Server), native(로컬 클라이언트 도구)다.
 * {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 * 서버가 보내는 메시지(STATS 진행률, 오류)와 도구 출력은 받은 그대로 보여 주므로 옮기지 않는다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 백업·복원 (docs/chromite/swing-backup-restore.md)
 * - 2026-11-06 pg_restore가 오류로 끝났을 때의 메시지(native_error_pg_restore_errors) 추가
 * - 2026-11-07 PostgreSQL 복원 옵션 "첫 오류에서 멈춤"(native_pg_restore_stop_on_error)과 일반 형식 안내 문구 수정
 * - 2026-11-08 네이티브 클라이언트로 스크립트 실행(native_script_*), 작업으로 저장(native_task_*), 표준 입력 미리 보기(native_stdin)
 *   (docs/chromite/swing-database-tasks.md)
 */
public class BackupRestoreMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.BackupRestoreMessages"; //$NON-NLS-1$

    // Commands and dialog frame
    public static String backup_menu;
    public static String restore_menu;
    public static String backup_title;
    public static String restore_title;
    public static String task_select_page;
    public static String task_page_general;
    public static String task_page_messages;
    public static String task_connection;
    public static String task_connection_server;
    public static String task_connection_name;
    public static String task_connection_user;
    public static String task_progress;
    public static String task_status_ready;
    public static String task_status_executing;
    public static String task_status_percent;
    public static String task_status_completed;
    public static String task_status_failed;
    public static String task_status_canceling;
    public static String task_status_canceled;
    public static String task_message_canceled;
    public static String task_button_script;
    public static String task_button_ok;
    public static String task_button_cancel;
    public static String task_button_close;
    public static String task_script_new_query;
    public static String task_script_clipboard;
    public static String task_progress_reading;
    public static String task_error_read_server;

    // Back Up (SQL Server)
    public static String backup_page_media;
    public static String backup_page_options;
    public static String backup_section_source;
    public static String backup_database;
    public static String backup_recovery_model;
    public static String backup_type;
    public static String backup_type_full;
    public static String backup_type_differential;
    public static String backup_type_log;
    public static String backup_copy_only;
    public static String backup_simple_recovery_note;
    public static String backup_section_destination;
    public static String backup_destination;
    public static String backup_server_path_note;
    public static String backup_section_overwrite;
    public static String backup_media_append;
    public static String backup_media_overwrite;
    public static String backup_media_new;
    public static String backup_media_name;
    public static String backup_media_description;
    public static String backup_section_reliability;
    public static String backup_verify;
    public static String backup_checksum;
    public static String backup_continue_on_error;
    public static String backup_section_backup_set;
    public static String backup_set_name;
    public static String backup_set_description;
    public static String backup_set_name_full;
    public static String backup_set_name_differential;
    public static String backup_set_name_log;
    public static String backup_section_compression;
    public static String backup_compression;
    public static String backup_compression_default;
    public static String backup_compression_on;
    public static String backup_compression_off;
    public static String backup_compression_compress;
    public static String backup_compression_none;
    public static String backup_compression_unsupported;
    public static String backup_not_supported;
    public static String backup_error_no_destination;
    public static String backup_error_no_name;
    public static String backup_success;

    // Restore (SQL Server)
    public static String restore_page_files;
    public static String restore_page_options;
    public static String restore_section_source;
    public static String restore_source;
    public static String restore_read;
    public static String restore_read_tip;
    public static String restore_section_destination;
    public static String restore_target;
    public static String restore_section_sets;
    public static String restore_column_restore;
    public static String restore_column_name;
    public static String restore_column_type;
    public static String restore_column_database;
    public static String restore_column_position;
    public static String restore_column_start;
    public static String restore_column_finish;
    public static String restore_column_first_lsn;
    public static String restore_column_last_lsn;
    public static String restore_column_size;
    public static String restore_column_user;
    public static String restore_column_server;
    public static String restore_copy_only_suffix;
    public static String restore_section_relocate;
    public static String restore_relocate;
    public static String restore_data_folder;
    public static String restore_log_folder;
    public static String restore_section_files;
    public static String restore_column_logical_name;
    public static String restore_column_file_type;
    public static String restore_column_original;
    public static String restore_column_restore_as;
    public static String restore_file_type_data;
    public static String restore_file_type_log;
    public static String restore_section_options;
    public static String restore_replace;
    public static String restore_section_recovery;
    public static String restore_recovery_state;
    public static String restore_recovery;
    public static String restore_norecovery;
    public static String restore_recovery_note;
    public static String restore_norecovery_note;
    public static String restore_section_server_connections;
    public static String restore_close_connections;
    public static String restore_progress_reading_sets;
    public static String restore_progress_reading_files;
    public static String restore_error_no_source;
    public static String restore_error_not_read;
    public static String restore_error_no_target;
    public static String restore_error_no_sets;
    public static String restore_success;

    // Back Up / Restore with the local client tools (PostgreSQL, MySQL/MariaDB)
    public static String native_page_client;
    public static String native_section_options;
    public static String native_output_folder;
    public static String native_file_name;
    public static String native_file_name_note;
    public static String native_input_file;
    public static String native_browse;
    public static String native_local_file_note;
    public static String native_pg_format;
    public static String native_pg_format_plain;
    public static String native_pg_format_custom;
    public static String native_pg_format_directory;
    public static String native_pg_format_tar;
    public static String native_pg_compression;
    public static String native_pg_encoding;
    public static String native_pg_no_owner;
    public static String native_pg_no_privileges;
    public static String native_pg_inserts;
    public static String native_pg_clean;
    public static String native_pg_create;
    public static String native_pg_restore_clean;
    public static String native_pg_restore_create;
    public static String native_pg_restore_stop_on_error;
    public static String native_pg_plain_note;
    public static String native_mysql_method;
    public static String native_mysql_method_online;
    public static String native_mysql_method_lock;
    public static String native_mysql_method_normal;
    public static String native_mysql_no_create;
    public static String native_mysql_add_drop;
    public static String native_mysql_disable_keys;
    public static String native_mysql_extended_inserts;
    public static String native_mysql_no_data;
    public static String native_mysql_routines;
    public static String native_mysql_events;
    public static String native_mysql_comments;
    public static String native_mysql_remove_definer;
    public static String native_mysql_hex_blob;
    public static String native_mysql_disable_fk;
    public static String native_mysql_verbose;
    public static String native_section_client;
    public static String native_client;
    public static String native_client_none;
    public static String native_client_browse_tip;
    public static String native_client_choose;
    public static String native_client_note;
    public static String native_client_missing;
    public static String native_client_found;
    public static String native_client_tool_missing;
    public static String native_section_command;
    public static String native_copy_command;
    public static String native_error_no_output;
    public static String native_error_no_input;
    public static String native_error_input_missing;
    public static String native_error_pg_restore_errors;

    // Run Script with Native Client
    public static String native_script_menu;
    public static String native_script_menu_tip;
    public static String native_script_editor_menu;
    public static String native_script_editor_menu_tip;
    public static String native_script_no_target;
    public static String native_script_save_first;
    public static String native_script_title;
    public static String native_script_section_target;
    public static String native_script_target;
    public static String native_script_section_script;
    public static String native_script_file;
    public static String native_script_session_note;
    public static String native_script_page_options;
    public static String native_script_section_options;
    public static String native_script_pg_stop_on_error;
    public static String native_script_mysql_disable_fk;
    public static String native_script_oracle_note;
    public static String native_script_error_no_input;
    public static String native_script_confirm_title;
    public static String native_script_confirm;
    public static String native_script_confirm_run;
    public static String native_script_success;
    public static String native_stdin;

    // Saved tasks
    public static String native_task_save;
    public static String native_task_update;
    public static String native_task_save_title;
    public static String native_task_name;
    public static String native_task_saved;
    public static String native_task_loading;
    public static String native_task_no_target;

    static {
        NLS.initializeMessages(BUNDLE_NAME, BackupRestoreMessages.class);
    }

    private BackupRestoreMessages() {
    }
}
