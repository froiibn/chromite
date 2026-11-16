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
 * Texts of the execution plan views: the view switch, the graphical plan and the raw plan text.
 *
 * 설계: 계획 탭의 트리 표 문구는 {@link EditorMessages}에 있다. 세 보기(그래픽·트리·원본)를 더하면서 생긴 문구는 다른 작업과
 * 같은 파일을 고치지 않도록 이 클래스에 따로 둔다(영어 {@code PlanMessages.properties}, 한국어 {@code PlanMessages_ko.properties}).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sql-editor-plus.md "실행 계획 세 보기")
 */
public class PlanMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.PlanMessages"; //$NON-NLS-1$

    // View switch
    public static String plan_view_graphical;
    public static String plan_view_graphical_tip;
    public static String plan_view_tree;
    public static String plan_view_tree_tip;
    public static String plan_view_raw;
    public static String plan_view_raw_tip;

    // Graphical plan
    public static String plan_zoom_in;
    public static String plan_zoom_out;
    public static String plan_zoom_fit;
    public static String plan_zoom_actual;
    public static String plan_query;
    public static String plan_query_cost;
    public static String plan_no_plan;
    public static String plan_missing_index;
    public static String plan_cost;
    public static String plan_rows;
    public static String plan_rows_actual;
    public static String plan_arrow_rows_estimated;
    public static String plan_arrow_rows_actual;
    public static String plan_tip_operation;
    public static String plan_tip_logical_operation;
    public static String plan_tip_object;
    public static String plan_tip_cost;
    public static String plan_tip_subtree_cost;
    public static String plan_tip_estimated_rows;
    public static String plan_tip_actual_rows;
    public static String plan_tip_executions;
    public static String plan_tip_time;
    public static String plan_tip_condition;
    public static String plan_tip_warnings;
    public static String plan_misestimate;
    public static String plan_warning_implicit_conversion;
    public static String plan_warning_spill;
    public static String plan_warning_no_join_predicate;
    public static String plan_warning_no_statistics;
    public static String plan_warning_memory_grant;
    public static String plan_warning_missing_index;
    public static String plan_warning_other;

    // Raw plan
    public static String plan_raw_copy;
    public static String plan_raw_copy_tip;
    public static String plan_raw_save;
    public static String plan_raw_save_tip;
    public static String plan_raw_save_failed;
    public static String plan_raw_none;
    public static String plan_raw_query;
    public static String plan_raw_queries;
    public static String plan_filter_sqlplan;
    public static String plan_filter_xml;
    public static String plan_filter_json;
    public static String plan_filter_text;

    static {
        NLS.initializeMessages(BUNDLE_NAME, PlanMessages.class);
    }

    private PlanMessages() {
    }
}
