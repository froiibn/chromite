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
 * 결과의 저장한 필터·최근 필터와 가상(계산) 열의 화면 문구.
 *
 * 설계: 기능마다 묶음 하나를 두는 Swing 관례({@link GridNavMessages}와 같음, 다른 작업과 같은 파일을 고치지 않게)를 따른다. 영어가 기본
 * ({@code GridVirtualMessages.properties}), 한국어는 {@code GridVirtualMessages_ko.properties}이고 OS 언어로 고른다. 키 접두사는 {@code gridvirt_}다.
 * {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 저장한 필터, 최근 필터, 가상 열 (docs/chromite/swing-saved-filters-virtual-columns.md)
 */
public class GridVirtualMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.GridVirtualMessages"; //$NON-NLS-1$

    // Saved filters and recent filters (ResultSetViewerPanel, ResultSetSavedFilters)
    public static String gridvirt_filters_menu;
    public static String gridvirt_filters_save;
    public static String gridvirt_filters_nothing;
    public static String gridvirt_filters_save_title;
    public static String gridvirt_filters_save_intro;
    public static String gridvirt_filters_name;
    public static String gridvirt_filters_default_name;
    public static String gridvirt_filters_name_required;
    public static String gridvirt_filters_replace_confirm;
    public static String gridvirt_filters_replace;
    public static String gridvirt_filters_save_button;
    public static String gridvirt_filters_saved;
    public static String gridvirt_filters_history;
    public static String gridvirt_filters_history_empty;
    public static String gridvirt_filters_history_clear;
    public static String gridvirt_filters_history_clear_confirm;
    public static String gridvirt_filters_clear_button;
    public static String gridvirt_filters_delete;
    public static String gridvirt_filters_delete_title;
    public static String gridvirt_filters_delete_confirm;
    public static String gridvirt_filters_delete_button;
    public static String gridvirt_filters_skipped;
    public static String gridvirt_filters_order;

    // Virtual columns (ResultSetViewerPanel, ResultSetVirtualColumns)
    public static String gridvirt_columns_menu;
    public static String gridvirt_column_add;
    public static String gridvirt_column_edit;
    public static String gridvirt_column_remove;
    public static String gridvirt_column_title_add;
    public static String gridvirt_column_title_edit;
    public static String gridvirt_column_intro;
    public static String gridvirt_column_name;
    public static String gridvirt_column_kind;
    public static String gridvirt_column_kind_string;
    public static String gridvirt_column_kind_numeric;
    public static String gridvirt_column_kind_boolean;
    public static String gridvirt_column_kind_datetime;
    public static String gridvirt_column_expression;
    public static String gridvirt_column_expression_help;
    public static String gridvirt_column_insert;
    public static String gridvirt_column_preview;
    public static String gridvirt_column_preview_none;
    public static String gridvirt_column_name_required;
    public static String gridvirt_column_name_exists;
    public static String gridvirt_column_expression_required;
    public static String gridvirt_column_expression_invalid;
    public static String gridvirt_column_remove_title;
    public static String gridvirt_column_remove_confirm;
    public static String gridvirt_column_remove_button;
    public static String gridvirt_column_run_again;
    public static String gridvirt_column_tip;
    public static String gridvirt_column_no_filter;

    static {
        NLS.initializeMessages(BUNDLE_NAME, GridVirtualMessages.class);
    }

    private GridVirtualMessages() {
    }
}
