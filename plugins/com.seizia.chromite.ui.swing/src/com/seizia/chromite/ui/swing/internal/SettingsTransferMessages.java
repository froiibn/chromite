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
 * 설정 내보내기·가져오기·가져오기 되돌리기(File 메뉴)의 화면 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 이 기능만의 {@link NLS} 묶음을 둔다. 영어가 기본, 한국어는
 * {@code SettingsTransferMessages_ko.properties}이고 OS 언어를 따른다. 키 접두사는 {@code settings_}다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/settings-transfer.md)
 */
public class SettingsTransferMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.SettingsTransferMessages"; //$NON-NLS-1$

    public static String settings_menu_export;
    public static String settings_menu_import;
    public static String settings_menu_undo;
    public static String settings_file_filter;

    public static String settings_section_preferences;
    public static String settings_section_drivers;
    public static String settings_section_query_library;
    public static String settings_section_scripts;
    public static String settings_section_connections;
    public static String settings_short_preferences;
    public static String settings_short_drivers;
    public static String settings_short_query_library;
    public static String settings_short_scripts;

    public static String settings_export_title;
    public static String settings_export_choose;
    public static String settings_export_note;
    public static String settings_export_button;
    public static String settings_export_nothing;
    public static String settings_export_save_title;
    public static String settings_export_overwrite;
    public static String settings_export_progress;
    public static String settings_export_done;
    public static String settings_export_count_preferences;
    public static String settings_export_count_drivers;
    public static String settings_export_count_query_library;
    public static String settings_export_count_scripts;
    public static String settings_export_count_connections_encrypted;
    public static String settings_export_count_connections_plain;
    public static String settings_export_secrets_skipped;
    public static String settings_export_failed;

    public static String settings_import_title;
    public static String settings_import_open_title;
    public static String settings_import_progress;
    public static String settings_import_preview_progress;
    public static String settings_import_not_a_file;
    public static String settings_import_unsupported;
    public static String settings_import_invalid;
    public static String settings_import_failed;
    public static String settings_import_nothing;
    public static String settings_import_file;
    public static String settings_import_hint;
    public static String settings_import_column_section;
    public static String settings_import_column_item;
    public static String settings_import_column_status;
    public static String settings_import_column_here;
    public static String settings_import_column_file;
    public static String settings_import_column_action;
    public static String settings_status_new;
    public static String settings_status_default;
    public static String settings_status_conflict;
    public static String settings_status_open;
    public static String settings_status_read_only;
    public static String settings_status_unavailable;
    public static String settings_action_import;
    public static String settings_action_skip;
    public static String settings_action_replace;
    public static String settings_action_keep_both;
    public static String settings_import_all_conflicts;
    public static String settings_import_apply;
    public static String settings_import_connections;
    public static String settings_import_no_scripts_folder;
    public static String settings_import_backup_note;
    public static String settings_import_button;
    public static String settings_import_backup_progress;
    public static String settings_import_backup_failed;
    public static String settings_import_done;
    public static String settings_import_done_restart;
    public static String settings_import_done_undo;
    public static String settings_import_errors;

    public static String settings_undo_title;
    public static String settings_undo_none;
    public static String settings_undo_confirm;
    public static String settings_undo_button;
    public static String settings_undo_progress;
    public static String settings_undo_done;
    public static String settings_undo_kept_changed;
    public static String settings_undo_kept_connected;
    public static String settings_undo_kept_drivers;
    public static String settings_undo_failed;

    static {
        NLS.initializeMessages(BUNDLE_NAME, SettingsTransferMessages.class);
    }

    private SettingsTransferMessages() {
    }
}
