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
 * Object Explorer 생산성 기능(필터 대화상자, 빠른 거르기, Link with Editor, 두 번 클릭 설정, 긴 폴더 "더 읽기", Copy Special, 연결 복제,
 * Object Details, Set as Default, Ctrl+9 / Ctrl+0 고르기)의 화면 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 이 기능만의 {@link NLS} 묶음을 둔다. 같은 파일(Object Explorer)을 고치는 다른
 * 작업과 메시지 파일에서 부딪히지 않게 {@link NavigatorMessages}·{@link CoreMessages}와 나눴다. 영어가 기본
 * ({@code ObjectExplorerMessages.properties}), 한국어는 {@code ObjectExplorerMessages_ko.properties}이고 OS 언어를 따른다. 키 접두사는 {@code oe_}다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-explorer-productivity.md)
 * - 2026-11-12 "더 읽기" 줄의 메뉴 항목 Page Size Settings (B12)
 */
public class ObjectExplorerMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.ObjectExplorerMessages"; //$NON-NLS-1$

    // Filter dialog
    public static String oe_filter_title;
    public static String oe_filter_enabled;
    public static String oe_filter_include;
    public static String oe_filter_exclude;
    public static String oe_filter_hint;
    public static String oe_filter_scope;
    public static String oe_filter_scope_folder;
    public static String oe_filter_scope_global;
    public static String oe_filter_clear;
    public static String oe_filter_clear_tip;
    public static String oe_filtered;

    // Quick filter, paging, Link with Editor
    public static String oe_quick_filter_placeholder;
    public static String oe_quick_filter_tip;
    public static String oe_load_more;
    public static String oe_load_more_tip;
    public static String oe_page_size_settings;
    public static String oe_link_editor;
    public static String oe_link_editor_tip;

    // Double click (Options)
    public static String oe_options_section;
    public static String oe_options_object_dblclick;
    public static String oe_options_connection_dblclick;
    public static String oe_options_page_size;
    public static String oe_options_page_size_hint;
    public static String oe_dblclick_expand;
    public static String oe_dblclick_open_editor;
    public static String oe_dblclick_open_data;
    public static String oe_dblclick_expand_connect;
    public static String oe_dblclick_new_query;
    public static String oe_dblclick_edit_connection;

    // Copy Special, Duplicate Connection
    public static String oe_copy_special;
    public static String oe_copy_special_tip;
    public static String oe_copy_special_title;
    public static String oe_copy_what_one;
    public static String oe_copy_what_many;
    public static String oe_copy_names;
    public static String oe_copy_qualified;
    public static String oe_copy_ddl;
    public static String oe_copy_ddl_unavailable;
    public static String oe_copy_separator;
    public static String oe_copy_separator_new_line;
    public static String oe_copy_separator_comma;
    public static String oe_copy_separator_comma_new_line;
    public static String oe_copy_button;
    public static String oe_copy_progress;
    public static String oe_copy_ddl_error;
    public static String oe_duplicate_connection;
    public static String oe_duplicate_connection_tip;
    public static String oe_duplicate_error;

    // Object Details
    public static String oe_details;
    public static String oe_details_tip;
    public static String oe_details_title;
    public static String oe_details_tooltip;
    public static String oe_details_search;
    public static String oe_details_refresh_tip;
    public static String oe_details_name;
    public static String oe_details_no_items;
    public static String oe_details_count;
    public static String oe_details_reading_values;
    public static String oe_details_error;

    // Set as Default, Ctrl+9 / Ctrl+0
    public static String oe_set_default;
    public static String oe_set_default_tip;
    public static String oe_set_default_error;
    public static String oe_set_default_txn_message;
    public static String oe_set_default_txn_error;
    public static String oe_select_connection;
    public static String oe_select_connection_tip;
    public static String oe_select_schema;
    public static String oe_select_schema_tip;
    public static String oe_choose_filter;
    public static String oe_choose_no_editor;
    public static String oe_choose_schema_unavailable;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ObjectExplorerMessages.class);
    }

    private ObjectExplorerMessages() {
    }
}
