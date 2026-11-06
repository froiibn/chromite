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
 * Swing 주 도구 막대와 쿼리 창 도구 막대의 화면 문구(도구 설명, Save All, 넘침 메뉴).
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 도구 막대만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code ToolbarMessages.properties}), 한국어는 {@code ToolbarMessages_ko.properties}이고 OS 언어를 따른다. 키 접두사는 {@code toolbar_}다.
 * 도구 설명 끝의 단축키는 메뉴의 단축키와 같게 적는다(메뉴가 바뀌면 함께 고친다).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-frontend.md "도구 막대")
 */
public class ToolbarMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.ToolbarMessages"; //$NON-NLS-1$

    public static String toolbar_save_all;
    public static String toolbar_save_all_tip;
    public static String toolbar_undo_tip;
    public static String toolbar_redo_tip;
    public static String toolbar_activity_monitor_tip;
    public static String toolbar_activity_monitor_disabled_tip;
    public static String toolbar_registered_servers_tip;
    public static String toolbar_object_explorer_tip;
    public static String toolbar_query_library_tip;
    public static String toolbar_properties_tip;
    public static String toolbar_find_tip;

    public static String toolbar_comment;
    public static String toolbar_comment_tip;
    public static String toolbar_uncomment;
    public static String toolbar_uncomment_tip;
    public static String toolbar_outdent;
    public static String toolbar_outdent_tip;
    public static String toolbar_indent;
    public static String toolbar_indent_tip;
    public static String toolbar_format;
    public static String toolbar_format_tip;
    public static String toolbar_query_history;
    public static String toolbar_query_history_tip;

    public static String toolbar_overflow_tip;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ToolbarMessages.class);
    }

    private ToolbarMessages() {
    }
}
