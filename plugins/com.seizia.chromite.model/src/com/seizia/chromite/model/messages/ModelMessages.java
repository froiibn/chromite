/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.messages;

import org.eclipse.osgi.util.NLS;

/**
 * 변경 이력:
 * - 2026-11-07 SWT 프런트엔드 삭제로 쓰는 곳이 없어진 문구 삭제(docs/chromite/swt-removal.md)
 * - 2026-11-07 테이블 저장 방식 이름(model_struct_storage_*) 추가(docs/chromite/altibase.md)
 * - 2026-11-07 셸 명령 승인 대화상자의 암호 명령 문구(model_shell_command_*) 추가(docs/chromite/shell-command-approval.md)
 * - 2026-11-07 연결 작업 이름과 연결 무효화 알림 문구(runtime_connect_job_name, runtime_invalidate_*) 추가
 *   (docs/chromite/swing-l10n.md "남은 한국어 문구 정리")
 * - 2026-11-07 유휴 연결 닫기 알림 문구(model_connection_idle_closed) 추가(docs/chromite/idle-transactions.md "유휴 연결 닫기")
 * - 2026-11-08 저장된 작업의 셸 명령 승인 문맥(model_shell_command_context_task, model_shell_command_event_saved_task) 추가
 *   (docs/chromite/shell-command-approval.md "저장된 네이티브 도구 작업")
 * - 2026-11-08 {@link #BUNDLE_NAME}을 public으로: 기본 연결 유형의 이름을 모든 번역과 비교하는 {@code DBPConnectionType}이 번역 파일을 찾음
 *   (docs/chromite/model-fixes.md "작은 모델·안전·라이선스 수정")
 * - 2026-11-12 저장 중 연결이 끊겨 다시 보내지 않았다는 문구(error_save_connection_lost) 추가 (앱 변경 #86,
 *   docs/chromite/transactions.md "연결이 끊긴 쿼리")
 */
public class ModelMessages extends NLS {
    public static final String BUNDLE_NAME = "com.seizia.chromite.model.messages.ModelResources"; //$NON-NLS-1$

    public static String error_not_connected_to_database;
    public static String error_save_connection_lost;
    public static String dialog_web_download_text_known;
    public static String dialog_web_download_text_unknown;

    public static String common_error_sql;

    public static String model_navigator__connections;
    public static String model_navigator_Connection;

    public static String model_connection_events_event_after_connect;
    public static String model_connection_events_event_after_disconnect;
    public static String model_connection_events_event_before_connect;
    public static String model_connection_events_event_before_disconnect;
    public static String model_connection_idle_closed;

    public static String model_shell_command_context_project;
    public static String model_shell_command_context_connection;
    public static String model_shell_command_context_event;
    public static String model_shell_command_event_password;
    public static String model_shell_command_context_task;
    public static String model_shell_command_event_saved_task;

    public static String runtime_connect_job_name;
    public static String runtime_invalidate_success;
    public static String runtime_invalidate_failure;

    public static String model_ddl_transaction_behavior_auto;
    public static String model_ddl_transaction_behavior_immediate;
    public static String model_ddl_transaction_behavior_transactional;
    public static String model_ddl_transaction_behavior_ignored;

    public static String model_edit_execute_;
    public static String model_jdbc_read_database_meta_data;

    public static String model_jdbc__rows_fetched;
    public static String model_jdbc_create_new_constraint;
    public static String model_jdbc_create_new_foreign_key;
    public static String model_jdbc_create_new_index;
    public static String model_jdbc_create_new_object;
    public static String model_jdbc_create_new_table;
    public static String model_jdbc_create_new_table_column;
    public static String model_jdbc_Database;
    public static String model_jdbc_delete_object;
    public static String model_jdbc_driver_properties;
    public static String model_jdbc_drop_constraint;
    public static String model_jdbc_drop_foreign_key;
    public static String model_jdbc_drop_index;
    public static String model_jdbc_drop_table;
    public static String model_jdbc_drop_table_column;
    public static String model_jdbc_exception_bad_savepoint_object;
    public static String model_jdbc_exception_could_not_bind_statement_parameter;
    public static String model_jdbc_exception_internal_jdbc_driver_error;
    public static String model_jdbc_exception_invalid_transaction_isolation_parameter;
    public static String model_jdbc_fetch_table_data;
    public static String model_jdbc_fetch_table_row_count;
    public static String model_jdbc_find_objects_by_name;
    public static String model_jdbc_load_from_file_;
    public static String model_jdbc_None;
    public static String model_jdbc_Procedure;
    public static String model_jdbc_read_committed;
    public static String model_jdbc_read_uncommitted;
    public static String model_jdbc_rename_object;
    public static String model_jdbc_reorder_object;
    public static String model_jdbc_repeatable_read;
    public static String model_jdbc_Schema;
    public static String model_jdbc_Serializable;
    public static String model_jdbc_unsupported_column_type_;
    public static String model_jdbc_unsupported_content_value_type_;
    public static String model_jdbc_unsupported_value_type_;

    public static String model_navigator_load_;
    public static String model_navigator_Model_root;
    public static String model_navigator_Name;
    public static String model_navigator_Project;

    public static String model_navigator_Root;

    public static String model_struct_Association;
    public static String model_struct_Cascade;
    public static String model_struct_Check;
    public static String model_struct_Clustered;
    public static String model_struct_Foreign_Key;
    public static String model_struct_Hashed;
    public static String model_struct_Index;
    public static String model_struct_Inheritance;
    public static String model_struct_No_Action;
    public static String model_struct_Not_NULL;
    public static String model_struct_Other;
    public static String model_struct_Primary_Key;
    public static String model_struct_Restrict;
    public static String model_struct_Set_Default;
    public static String model_struct_Set_NULL;
    public static String model_struct_Statistic;
    public static String model_struct_Unique_Key;
    public static String model_struct_Virtual_Key;
    public static String model_struct_Virtual_Foreign_Key;
    public static String model_struct_Pseudo_Key;
    public static String model_struct_Unknown;
    public static String model_struct_storage_Memory;
    public static String model_struct_storage_Disk;
    public static String model_struct_storage_Volatile;
    public static String model_struct_storage_Hybrid;


    public static String dbp_connection_type_table_development;

    public static String dbp_connection_type_table_production;

    public static String dbp_connection_type_table_production_database;

    public static String dbp_connection_type_table_regular_development_database;

    public static String dbp_connection_type_table_test;

    public static String dbp_connection_type_table_test_database;
    public static String dialog_connection_wizard_start_connection_monitor_start;
    public static String dialog_connection_wizard_start_connection_monitor_subtask_test;
    public static String dialog_connection_wizard_start_connection_monitor_success;
    public static String dialog_connection_wizard_start_connection_monitor_thread;

    public static String dbp_permission_edit_data_name;
    public static String dbp_permission_edit_data_description;
    public static String dbp_permission_edit_metadata_name;
    public static String dbp_permission_edit_metadata_description;
    public static String dbp_permission_execute_scripts_name;
    public static String dbp_permission_execute_scripts_description;
    public static String dbp_permission_import_data_name;
    public static String dbp_permission_import_data_description;

    public static String task_rows_fetched_message_part;
    public static String task_rows_modified_message_part;
    public static String task_statements_executed_message_part;

    public static String tasks_restore_confirmation_title;
    public static String tasks_restore_confirmation_cancelled_message;
    public static String tasks_restore_confirmation_message;
    public static String tasks_restore_readonly_message;

    public static String project_shared_display_name;
    public static String project_private_display_name;

    public static String jdbc_content_view_error_message_title;
    public static String jdbc_content_view_error_message_hint;

    public static String no_corresponding_table_column_text;

    public static String dialog_user_password_change_label;
    public static String dialog_user_password_change_question_label;
    public static String dialog_user_password_change_question_message;
    public static String dialog_user_password_change_error_message;

    public static String dbutils_type_name_unknown;

    public static String fs_root;
    public static String fs_file_system;
    public static String fs_folder;
    public static String fs_file;

    public static String struct_utils_object_ddl_source;
    public static String struct_utils_object_ddl_definition;
    public static String struct_utils_object_ddl_foreign_keys;

    public static String duration_formatter_hours;
    public static String duration_formatter_minutes;
    public static String duration_formatter_seconds;
    public static String duration_formatter_milliseconds;

    static {
        // initialize resource bundle
        NLS.initializeMessages(BUNDLE_NAME, ModelMessages.class);
    }

    private ModelMessages() {
    }
}
