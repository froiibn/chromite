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
 * 결과 그리드 열 필터(머리글의 필터·정렬 아이콘, 열 필터 팝업, 필터 요약)의 화면 문구.
 *
 * 설계: 기능마다 묶음 하나를 두는 Swing 관례(다른 작업과 같은 파일을 고치지 않게)를 따른다. 영어가 기본
 * ({@code GridFilterMessages.properties}), 한국어는 {@code GridFilterMessages_ko.properties}이고 OS 언어로 고른다.
 * 키 접두사는 {@code gridfilter_}다. {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 * 연산자 기호(=, ≠, &gt;, ≥, &lt;, ≤)와 SQL 낱말(IS NULL, IS NOT NULL, LIKE)은 옮기지 않는다(코드 상수).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 결과 필터 줄을 열별 필터로 바꿈 (docs/chromite/swing-grid-column-filters.md)
 */
public class GridFilterMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.GridFilterMessages"; //$NON-NLS-1$

    // Column header (ResultSetHeaderRenderer)
    public static String gridfilter_header_column_tip;
    public static String gridfilter_header_filter_tip;
    public static String gridfilter_header_filtered_tip;
    public static String gridfilter_header_sort_ascending_tip;
    public static String gridfilter_header_sort_descending_tip;
    public static String gridfilter_header_sort_clear_tip;

    // Filter popup (ResultSetColumnFilterPopup)
    public static String gridfilter_search_hint;
    public static String gridfilter_select_all;
    public static String gridfilter_select_none;
    public static String gridfilter_values_loading;
    public static String gridfilter_values_one;
    public static String gridfilter_values_many;
    public static String gridfilter_values_none;
    public static String gridfilter_values_more;
    public static String gridfilter_values_load_more;
    public static String gridfilter_values_failed;
    public static String gridfilter_values_unavailable_type;
    public static String gridfilter_values_unavailable_query;
    public static String gridfilter_values_unavailable_name;
    public static String gridfilter_condition;
    public static String gridfilter_op_values;
    public static String gridfilter_op_contains;
    public static String gridfilter_op_starts_with;
    public static String gridfilter_op_ends_with;
    public static String gridfilter_op_between;
    public static String gridfilter_value_hint;
    public static String gridfilter_between_and;
    public static String gridfilter_value_required;
    public static String gridfilter_value_invalid;
    public static String gridfilter_values_too_many;
    public static String gridfilter_apply;
    public static String gridfilter_clear_column;
    public static String gridfilter_clear_all;

    // Active filter summary (ResultSetViewerPanel status bar)
    public static String gridfilter_summary;

    static {
        NLS.initializeMessages(BUNDLE_NAME, GridFilterMessages.class);
    }

    private GridFilterMessages() {
    }
}
