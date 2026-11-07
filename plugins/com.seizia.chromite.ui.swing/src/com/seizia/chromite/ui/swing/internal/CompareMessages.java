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
 * Swing 스키마 비교(Schema Compare 문서 탭)의 화면 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 스키마 비교만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code CompareMessages.properties}), 한국어는 {@code CompareMessages_ko.properties}이고 OS 언어를 따른다. 키 접두사는 {@code compare_}다.
 * {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다(''). 스크립트 안의 주석은 모델 묶음
 * ({@code com.seizia.chromite.tools.compare.schema.SchemaCompareMessages})에 있다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/schema-compare.md)
 */
public class CompareMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.CompareMessages"; //$NON-NLS-1$

    public static String compare_title;
    public static String compare_tooltip;
    public static String compare_command;
    public static String compare_command_tip;
    public static String compare_label_source;
    public static String compare_label_target;
    public static String compare_label_database;
    public static String compare_label_schema;
    public static String compare_choose_connection;
    public static String compare_none;
    public static String compare_swap;
    public static String compare_swap_tip;
    public static String compare_button;
    public static String compare_generate;
    public static String compare_generate_tip;
    public static String compare_progress_read;
    public static String compare_progress_compare;
    public static String compare_progress_script;
    public static String compare_choose_both;
    public static String compare_same;
    public static String compare_hint;
    public static String compare_group_only_source;
    public static String compare_group_only_target;
    public static String compare_group_different;
    public static String compare_summary;
    public static String compare_summary_not_compared;
    public static String compare_no_differences;
    public static String compare_nothing_checked;
    public static String compare_kind_table;
    public static String compare_kind_view;
    public static String compare_kind_routine;
    public static String compare_kind_sequence;
    public static String compare_kind_column;
    public static String compare_kind_primary_key;
    public static String compare_kind_foreign_key;
    public static String compare_kind_index;
    public static String compare_kind_trigger;
    public static String compare_only_source;
    public static String compare_only_target;
    public static String compare_property_type;
    public static String compare_property_nullable;
    public static String compare_property_default;
    public static String compare_property_columns;
    public static String compare_property_unique;
    public static String compare_property_reference;
    public static String compare_property_delete_rule;
    public static String compare_property_update_rule;
    public static String compare_property_definition;
    public static String compare_property_increment;
    public static String compare_property_min_value;
    public static String compare_property_max_value;
    public static String compare_difference;
    public static String compare_empty_value;
    public static String compare_drop_tip;
    public static String compare_ddl_source;
    public static String compare_ddl_source_mapped;
    public static String compare_ddl_target;
    public static String compare_ddl_absent;
    public static String compare_ddl_loading;
    public static String compare_failed;
    public static String compare_script_failed;
    public static String compare_read_failed;

    static {
        NLS.initializeMessages(BUNDLE_NAME, CompareMessages.class);
    }

    private CompareMessages() {
    }
}
