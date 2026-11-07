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
 * 연결 내보내기·가져오기(암호로 암호화한 파일)의 화면 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 이 기능만의 {@link NLS} 묶음을 둔다. 다른 에이전트가 함께 고치는
 * {@code CoreMessages}를 건드리지 않기 위해서이기도 하다. 영어가 기본, 한국어는 {@code ConnectionTransferMessages_ko.properties}이고
 * OS 언어를 따른다. 키 접두사는 {@code transfer_}다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/connection-transfer.md)
 * - 2026-11-07 가져오기 저장 실패 문구, 내보내기 암호 안내에 비밀번호가 작업 공간 복사로 따라가지 않는다는 문장 (docs/chromite/credential-storage.md)
 * - 2026-11-08 다른 도구에서 가져오기 문구(File > Import Connections 하위 메뉴, 원본 고르기, 알 수 없는 DB 종류) (docs/chromite/connection-transfer.md)
 */
public class ConnectionTransferMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.ConnectionTransferMessages"; //$NON-NLS-1$

    public static String transfer_menu_export;
    public static String transfer_menu_import;
    public static String transfer_file_filter;

    public static String transfer_export_title;
    public static String transfer_export_choose;
    public static String transfer_export_select_all;
    public static String transfer_export_select_none;
    public static String transfer_export_column_name;
    public static String transfer_export_column_host;
    public static String transfer_export_include_passwords;
    public static String transfer_export_passphrase;
    public static String transfer_export_passphrase_confirm;
    public static String transfer_export_passphrase_hint;
    public static String transfer_export_plain_hint;
    public static String transfer_export_button;
    public static String transfer_export_nothing_selected;
    public static String transfer_export_passphrase_short;
    public static String transfer_export_passphrase_mismatch;
    public static String transfer_export_save_title;
    public static String transfer_export_overwrite;
    public static String transfer_export_progress;
    public static String transfer_export_done;
    public static String transfer_export_failed;

    public static String transfer_import_title;
    public static String transfer_import_open_title;
    public static String transfer_import_passphrase_prompt;
    public static String transfer_import_progress;
    public static String transfer_import_wrong_passphrase;
    public static String transfer_import_not_a_file;
    public static String transfer_import_unsupported;
    public static String transfer_import_invalid;
    public static String transfer_import_failed;
    public static String transfer_import_save_failed;
    public static String transfer_import_empty;
    public static String transfer_import_file;
    public static String transfer_import_with_passwords;
    public static String transfer_import_without_passwords;
    public static String transfer_import_column_name;
    public static String transfer_import_column_host;
    public static String transfer_import_column_driver;
    public static String transfer_import_column_group;
    public static String transfer_import_column_passwords;
    public static String transfer_import_column_conflict;
    public static String transfer_import_column_action;
    public static String transfer_import_yes;
    public static String transfer_import_no;
    public static String transfer_import_driver_missing;
    public static String transfer_import_drivers_missing_note;
    public static String transfer_import_conflict_none;
    public static String transfer_import_conflict_same_id;
    public static String transfer_import_conflict_same_name;
    public static String transfer_import_conflict_connected;
    public static String transfer_import_action_new;
    public static String transfer_import_action_skip;
    public static String transfer_import_action_replace;
    public static String transfer_import_action_copy;
    public static String transfer_import_all_conflicts;
    public static String transfer_import_apply;
    public static String transfer_import_button;
    public static String transfer_import_done;
    public static String transfer_import_done_skipped_connected;
    public static String transfer_import_done_missing_drivers;
    public static String transfer_import_done_kept_profiles;
    public static String transfer_import_driver_unrecognized;
    public static String transfer_import_drivers_unrecognized_note;
    public static String transfer_import_done_unrecognized;

    public static String transfer_menu_import_group;
    public static String transfer_menu_import_file;
    public static String transfer_menu_import_other;
    public static String transfer_other_title;
    public static String transfer_other_choose;
    public static String transfer_other_sqldeveloper;
    public static String transfer_other_toad;
    public static String transfer_other_csv;
    public static String transfer_other_note;
    public static String transfer_other_open_title;
    public static String transfer_other_filter_sqldeveloper;
    public static String transfer_other_filter_toad;
    public static String transfer_other_filter_csv;
    public static String transfer_other_progress;
    public static String transfer_other_failed;
    public static String transfer_other_empty;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ConnectionTransferMessages.class);
    }

    private ConnectionTransferMessages() {
    }
}
