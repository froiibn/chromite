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
 * Swing ER 다이어그램(View Diagram 문서 탭)의 화면 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 ER 다이어그램만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code ErdMessages.properties}), 한국어는 {@code ErdMessages_ko.properties}이고 OS 언어를 따른다. 키 접두사는 {@code erd_}다.
 * {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 * 열 형식 뒤의 {@code NOT NULL}, 확대 비율({@code 100%}), 로그와 작업(Job) 이름은 옮기지 않는다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-erd.md)
 */
public class ErdMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.ErdMessages"; //$NON-NLS-1$

    public static String erd_title;
    public static String erd_view_diagram;
    public static String erd_view_diagram_tip;
    public static String erd_tab_title;
    public static String erd_tab_tooltip;
    public static String erd_loading;
    public static String erd_load_failed;
    public static String erd_empty;
    public static String erd_status_counts;
    public static String erd_status_unsaved;
    public static String erd_status_saved;

    public static String erd_zoom_in;
    public static String erd_zoom_out;
    public static String erd_zoom_fit;
    public static String erd_zoom_actual;
    public static String erd_layout;
    public static String erd_layout_tip;
    public static String erd_columns;
    public static String erd_columns_all;
    public static String erd_columns_keys;
    public static String erd_columns_none;
    public static String erd_show_types;
    public static String erd_refresh;
    public static String erd_save_layout;
    public static String erd_save_layout_tip;
    public static String erd_save_layout_failed;

    public static String erd_export;
    public static String erd_export_png;
    public static String erd_export_svg;
    public static String erd_export_erd;
    public static String erd_filter_png;
    public static String erd_filter_svg;
    public static String erd_filter_erd;
    public static String erd_export_failed;

    public static String erd_show_in_explorer;
    public static String erd_link_tooltip;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ErdMessages.class);
    }

    private ErdMessages() {
    }
}
