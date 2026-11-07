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
 * 데이터 검색(Search Data 문서 탭)의 화면 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 데이터 검색만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code DataSearchMessages.properties}), 한국어는 {@code DataSearchMessages_ko.properties}이고 OS 언어를 따른다. 키 접두사는
 * {@code datasearch_}다. {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-data-search.md)
 */
public class DataSearchMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.DataSearchMessages"; //$NON-NLS-1$

    public static String datasearch_command;
    public static String datasearch_command_tip;
    public static String datasearch_tab_title;
    public static String datasearch_tooltip;

    public static String datasearch_label_find;
    public static String datasearch_label_options;
    public static String datasearch_label_scope;
    public static String datasearch_placeholder;
    public static String datasearch_case_sensitive;
    public static String datasearch_case_sensitive_tip;
    public static String datasearch_numbers;
    public static String datasearch_numbers_tip;
    public static String datasearch_long_text;
    public static String datasearch_long_text_tip;
    public static String datasearch_rows_per_table;
    public static String datasearch_rows_per_table_tip;
    public static String datasearch_button;
    public static String datasearch_button_stop;
    public static String datasearch_select_all;
    public static String datasearch_select_none;

    public static String datasearch_tables_column_name;
    public static String datasearch_tables_column_type;
    public static String datasearch_type_table;
    public static String datasearch_type_view;
    public static String datasearch_scope_text;

    public static String datasearch_column_table;
    public static String datasearch_column_column;
    public static String datasearch_column_rows;
    public static String datasearch_column_value;
    public static String datasearch_rows_more;
    public static String datasearch_other_columns;
    public static String datasearch_other_columns_tip;
    public static String datasearch_result_tip;

    public static String datasearch_loading_tables;
    public static String datasearch_tables_loaded;
    public static String datasearch_no_tables;
    public static String datasearch_select_tables;
    public static String datasearch_enter_text;
    public static String datasearch_searching;
    public static String datasearch_found;
    public static String datasearch_none_found;
    public static String datasearch_stopped;
    public static String datasearch_not_connected;
    public static String datasearch_shared_transaction;
    public static String datasearch_problems;
    public static String datasearch_open_rows;
    public static String datasearch_open_failed;

    static {
        NLS.initializeMessages(BUNDLE_NAME, DataSearchMessages.class);
    }

    private DataSearchMessages() {
    }
}
