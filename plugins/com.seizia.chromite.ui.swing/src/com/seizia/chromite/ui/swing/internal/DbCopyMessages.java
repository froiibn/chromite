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
 * 테이블 복사(다른 연결·데이터베이스로 복사: Copy Tables…, 내보내기의 "Database" 대상)의 화면 문구.
 *
 * 설계: 다른 기능 작업과 병합 충돌이 나지 않도록 {@link ToolsMessages}와 나눈 이 기능만의 {@link NLS} 묶음이다. 영어가 기본
 * ({@code DbCopyMessages.properties}), 한국어는 {@code DbCopyMessages_ko.properties}이고 플랫폼이 OS 언어로 고른다.
 * 키는 {@code 영역_항목} 형식이고 영역은 explorer, copy, format, destination, mapping, columns, ddl, confirm, load, summary,
 * progress, error다. {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 * 데이터베이스 노드에서 여는 가져오기·내보내기·테이블 복사 문구는 {@code dbnode_} 영역이다(같은 마법사의 문구라 따로 묶음을 만들지 않음).
 *
 * 변경 이력:
 * - 2026-11-06 테이블 복사: 최초 작성 (docs/chromite/swing-data-transfer.md)
 * - 2026-11-06 데이터베이스 노드: {@code dbnode_} 문구 (docs/chromite/swing-data-transfer.md "데이터베이스 노드")
 */
public class DbCopyMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.DbCopyMessages"; //$NON-NLS-1$

    public static String explorer_copy_tables;
    public static String copy_title;
    public static String copy_error_nothing;
    public static String copy_source_description;
    public static String format_database;
    public static String format_database_description;
    public static String destination_title;
    public static String destination_description;
    public static String destination_connection;
    public static String destination_database;
    public static String destination_schema;
    public static String destination_note;
    public static String destination_note_none;
    public static String destination_error_none;
    public static String destination_error_not_supported;
    public static String progress_connect;
    public static String progress_read;
    public static String progress_map;
    public static String progress_ddl;
    public static String error_configure;
    public static String error_canceled;
    public static String error_map;
    public static String error_connect;
    public static String error_read;
    public static String error_no_containers;
    public static String mapping_title;
    public static String mapping_description;
    public static String mapping_description_none;
    public static String mapping_note;
    public static String mapping_truncate;
    public static String mapping_tab_columns;
    public static String mapping_tab_ddl;
    public static String mapping_column_source;
    public static String mapping_column_destination;
    public static String mapping_column_mapping;
    public static String mapping_type_existing;
    public static String mapping_type_create;
    public static String mapping_type_recreate;
    public static String mapping_type_skip;
    public static String mapping_type_unspecified;
    public static String mapping_error_name_used;
    public static String mapping_error_incomplete;
    public static String mapping_error_nothing;
    public static String mapping_error_same_table;
    public static String columns_source;
    public static String columns_source_type;
    public static String columns_destination;
    public static String columns_destination_type;
    public static String columns_mapping;
    public static String ddl_skipped;
    public static String ddl_no_changes;
    public static String ddl_added_columns;
    public static String ddl_error;
    public static String confirm_recreate;
    public static String load_title;
    public static String load_description;
    public static String load_new_connection;
    public static String load_transactions;
    public static String load_commit;
    public static String load_commit_note;
    public static String load_multi_row;
    public static String load_rows_per_insert;
    public static String load_disable_batches;
    public static String load_auto_generated;
    public static String load_disable_integrity;
    public static String load_disable_integrity_tip;
    public static String load_disable_integrity_unsupported;
    public static String load_bulk_load;
    public static String load_open_tables;
    public static String load_note;
    public static String summary_description;
    public static String summary_destination;
    public static String summary_commit;
    public static String summary_no_transactions;
    public static String summary_multi_row;
    public static String summary_one_by_one;
    public static String summary_no_auto_generated;
    public static String summary_integrity;
    public static String summary_bulk_load;
    public static String summary_open_tables;
    public static String dbnode_tip_import_table;
    public static String dbnode_tip_import_database;
    public static String dbnode_tip_import_disabled;
    public static String dbnode_tip_export;
    public static String dbnode_tip_export_database;
    public static String dbnode_tip_copy;
    public static String dbnode_tip_copy_database;
    public static String dbnode_tip_transfer_disabled;
    public static String dbnode_source_note;
    public static String dbnode_source_show_system;
    public static String dbnode_source_checked;
    public static String dbnode_error_read_system;
    public static String dbnode_output_error_same_name;
    public static String dbnode_mapping_error_same_target;
    public static String dbnode_import_target_title;
    public static String dbnode_import_target_description;
    public static String dbnode_import_target_database;
    public static String dbnode_import_target_schema;
    public static String dbnode_import_target_existing;
    public static String dbnode_import_target_new;
    public static String dbnode_import_target_new_placeholder;
    public static String dbnode_import_target_note_existing;
    public static String dbnode_import_target_note_new;
    public static String dbnode_import_target_error_schema;
    public static String dbnode_import_target_error_table;
    public static String dbnode_import_error_no_schemas;
    public static String dbnode_import_new_table_in;
    public static String dbnode_import_mapping_description_new;
    public static String dbnode_import_mapping_note_new;
    public static String dbnode_import_mapping_error_same_column;
    public static String dbnode_import_mapping_error_none;
    public static String dbnode_import_summary_new_table;

    static {
        NLS.initializeMessages(BUNDLE_NAME, DbCopyMessages.class);
    }

    private DbCopyMessages() {
    }
}
