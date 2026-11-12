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
 * 결과 그리드 편집(저장하고 커밋, 형식별 편집기, 행·셀 명령, 붙여넣기, LOB 파일, JSON·XML 편집기, 고급 복사)의 화면 문구.
 *
 * 설계: 기능마다 묶음 하나를 두는 Swing 관례({@link GridFilterMessages}와 같음, 다른 작업과 같은 파일을 고치지 않게)를 따른다. 영어가 기본
 * ({@code GridEditMessages.properties}), 한국어는 {@code GridEditMessages_ko.properties}이고 OS 언어로 고른다. 키 접두사는 {@code gridedit_}다.
 * {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 결과 그리드 편집 (docs/chromite/swing-grid-editing.md)
 * - 2026-11-12 아이콘 단추의 도구 설명에만 쓰던 설명문 키를 지움(도구 설명이 메뉴의 이름이 됨) (앱 변경 #103, docs/chromite/swing-frontend.md "아이콘 단추의 도구 설명")
 */
public class GridEditMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.GridEditMessages"; //$NON-NLS-1$

    // Save and Commit, preview (ResultSetGrid)
    public static String gridedit_save_commit;
    public static String gridedit_save_commit_tip;
    public static String gridedit_commit_earlier;
    public static String gridedit_committed;
    public static String gridedit_commit_failed;
    public static String gridedit_preview_changes;
    public static String gridedit_preview_changes_tip;

    // Cell and row commands (ResultSetGrid)
    public static String gridedit_set_default;
    public static String gridedit_no_default;
    public static String gridedit_reset_cell;
    public static String gridedit_duplicate_row;
    public static String gridedit_duplicate_lob_skipped;
    public static String gridedit_copy_from_above;
    public static String gridedit_copy_from_below;
    public static String gridedit_cant_copy_values;
    public static String gridedit_undo;
    public static String gridedit_redo;

    // Paste (ResultSetGrid, ResultSetPaste)
    public static String gridedit_paste;
    public static String gridedit_paste_special;
    public static String gridedit_paste_special_title;
    public static String gridedit_paste_special_hint;
    public static String gridedit_paste_new_rows;
    public static String gridedit_paste_ignore_quotes;
    public static String gridedit_paste_null_marker;
    public static String gridedit_paste_skipped;
    public static String gridedit_paste_skipped_readonly;

    // Date picker (ResultSetGrid, ResultSetDateTimePicker)
    public static String gridedit_pick_date;
    public static String gridedit_pick_title;
    public static String gridedit_pick_time;
    public static String gridedit_pick_now;
    public static String gridedit_pick_today;
    public static String gridedit_pick_previous_month;
    public static String gridedit_pick_next_month;

    // JSON and XML editors (ResultSetValuePanel)
    public static String gridedit_value_format;
    public static String gridedit_value_format_tip;
    public static String gridedit_value_valid_json;
    public static String gridedit_value_valid_xml;

    // LOB files (ResultSetViewerPanel, ResultSetLobFiles)
    public static String gridedit_lob_load;
    public static String gridedit_lob_load_title;
    public static String gridedit_lob_load_tip;
    public static String gridedit_lob_save;
    public static String gridedit_lob_save_title;
    public static String gridedit_lob_save_tip;
    public static String gridedit_lob_cant_load;
    public static String gridedit_lob_cant_save;

    // Copy As and Advanced Copy (ResultSetCopyFormats)
    public static String gridedit_copy_html;
    public static String gridedit_copy_sql_insert;
    public static String gridedit_copy_advanced;
    public static String gridedit_copy_advanced_menu;
    public static String gridedit_copy_header;
    public static String gridedit_copy_row_numbers;
    public static String gridedit_copy_delimiter;
    public static String gridedit_copy_quote;
    public static String gridedit_copy_quote_needed;
    public static String gridedit_copy_quote_always;
    public static String gridedit_copy_quote_never;
    public static String gridedit_copy_null;

    static {
        NLS.initializeMessages(BUNDLE_NAME, GridEditMessages.class);
    }

    private GridEditMessages() {
    }
}
