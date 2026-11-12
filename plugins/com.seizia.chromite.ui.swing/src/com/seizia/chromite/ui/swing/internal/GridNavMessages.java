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
 * 결과 탐색(외래 키 따라가기와 기록, 참조·집계 패널, 가상 키, 열 숨기기·이동, 찾기, 텍스트 보기, 전체 행 수, 오류 위치로 가기)의 화면 문구.
 *
 * 설계: 기능마다 묶음 하나를 두는 Swing 관례({@link GridEditMessages}와 같음, 다른 작업과 같은 파일을 고치지 않게)를 따른다. 영어가 기본
 * ({@code GridNavMessages.properties}), 한국어는 {@code GridNavMessages_ko.properties}이고 OS 언어로 고른다. 키 접두사는 {@code gridnav_}다.
 * {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 결과 탐색 (docs/chromite/swing-grid-navigation.md)
 * - 2026-11-12 아이콘 단추의 도구 설명에만 쓰던 설명문 키를 지움(도구 설명이 메뉴의 이름이 됨) (앱 변경 #103, docs/chromite/swing-frontend.md "아이콘 단추의 도구 설명")
 */
public class GridNavMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.GridNavMessages"; //$NON-NLS-1$

    // Foreign key navigation and history (ResultSetViewerPanel)
    public static String gridnav_menu_navigate;
    public static String gridnav_go_referenced;
    public static String gridnav_no_cell_link;
    public static String gridnav_referenced_tables;
    public static String gridnav_referencing_tables;
    public static String gridnav_no_referenced;
    public static String gridnav_no_referencing;
    public static String gridnav_reading_links;
    public static String gridnav_select_rows;
    public static String gridnav_action_navigate;
    public static String gridnav_action_read_again;
    public static String gridnav_title_referenced;
    public static String gridnav_title_referencing;
    public static String gridnav_navigated_tip;
    public static String gridnav_back;
    public static String gridnav_forward;

    // Row count (ResultSetViewerPanel)
    public static String gridnav_count;
    public static String gridnav_count_tip;
    public static String gridnav_counting;
    public static String gridnav_count_failed;
    public static String gridnav_count_failed_detail;
    public static String gridnav_rows_of_total;

    // References panel (ResultSetReferencesPanel)
    public static String gridnav_refs;
    public static String gridnav_refs_tip;
    public static String gridnav_refs_key;
    public static String gridnav_refs_none;
    public static String gridnav_refs_referenced;
    public static String gridnav_refs_referencing;
    public static String gridnav_refs_open;
    public static String gridnav_refs_open_tip;
    public static String gridnav_refs_reading_keys;
    public static String gridnav_refs_no_keys;
    public static String gridnav_refs_choose_key;
    public static String gridnav_refs_select_rows;
    public static String gridnav_refs_reading;
    public static String gridnav_refs_failed;
    public static String gridnav_refs_read_only;

    // Aggregates panel (ResultSetAggregatePanel)
    public static String gridnav_agg;
    public static String gridnav_agg_tip;
    public static String gridnav_agg_functions;
    public static String gridnav_agg_by_column;
    public static String gridnav_agg_by_column_tip;
    public static String gridnav_agg_column;
    public static String gridnav_agg_selection;
    public static String gridnav_agg_select_cells;
    public static String gridnav_agg_capped;
    public static String gridnav_agg_count;
    public static String gridnav_agg_count_distinct;
    public static String gridnav_agg_nulls;
    public static String gridnav_agg_sum;
    public static String gridnav_agg_avg;
    public static String gridnav_agg_min;
    public static String gridnav_agg_max;
    public static String gridnav_agg_median;
    public static String gridnav_agg_mode;

    // Columns, go to, zoom (ResultSetColumnLayout, ResultSetViewerPanel)
    public static String gridnav_menu_columns;
    public static String gridnav_hide_column;
    public static String gridnav_show_all_columns;
    public static String gridnav_show_all_columns_count;
    public static String gridnav_hide_empty_columns;
    public static String gridnav_no_empty_columns;
    public static String gridnav_columns_hidden;
    public static String gridnav_goto_row;
    public static String gridnav_goto_row_title;
    public static String gridnav_goto_row_prompt;
    public static String gridnav_goto_column;
    public static String gridnav_goto_column_title;
    public static String gridnav_goto_column_hidden;
    public static String gridnav_zoom_in;
    public static String gridnav_zoom_out;
    public static String gridnav_zoom_reset;

    // Find (ResultSetFindBar)
    public static String gridnav_find;
    public static String gridnav_find_label;
    public static String gridnav_find_placeholder;
    public static String gridnav_find_previous;
    public static String gridnav_find_previous_tip;
    public static String gridnav_find_next;
    public static String gridnav_find_next_tip;
    public static String gridnav_find_match_case;
    public static String gridnav_find_close;
    public static String gridnav_find_none;
    public static String gridnav_find_found;
    public static String gridnav_find_position;

    // Text view (ResultSetTextView)
    public static String gridnav_text;
    public static String gridnav_text_tip;
    public static String gridnav_text_capped;

    // Virtual keys (ResultSetVirtualKeys, ResultSetGrid)
    public static String gridnav_menu_virtual;
    public static String gridnav_vkey_define;
    public static String gridnav_vkey_define_tip;
    public static String gridnav_vkey_has_key;
    public static String gridnav_vkey_no_table;
    public static String gridnav_vkey_title;
    public static String gridnav_vkey_intro;
    public static String gridnav_vkey_all_columns;
    public static String gridnav_vkey_some_columns;
    public static String gridnav_vkey_choose_columns;
    public static String gridnav_vkey_no_columns;
    public static String gridnav_vkey_remove;
    public static String gridnav_vkey_run_again;
    public static String gridnav_vfk_add;
    public static String gridnav_vfk_title;
    public static String gridnav_vfk_reading_tables;
    public static String gridnav_vfk_save_failed;
    public static String gridnav_vfk_remove;
    public static String gridnav_vfk_remove_title;
    public static String gridnav_vfk_remove_confirm;
    public static String gridnav_vfk_remove_button;

    // Go to error (QueryResultsView)
    public static String gridnav_goto_error;
    public static String gridnav_goto_error_tip;

    static {
        NLS.initializeMessages(BUNDLE_NAME, GridNavMessages.class);
    }

    private GridNavMessages() {
    }
}
