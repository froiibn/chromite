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
 * Swing 서버 대시보드(Activity Monitor의 개요 탭)의 화면 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 대시보드만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code DashboardMessages.properties}), 한국어는 {@code DashboardMessages_ko.properties}이고 OS 언어를 따른다. 키 접두사는 {@code dashboard_}다.
 * 차트 제목과 계열 이름은 모델 번들의 {@code DashboardLabels}가 맡는다. 값 단위(B, KB, %, /s)는 옮기지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-dashboard.md)
 */
public class DashboardMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.DashboardMessages"; //$NON-NLS-1$

    public static String dashboard_command;
    public static String dashboard_command_tip;
    public static String dashboard_tab;
    public static String dashboard_pause;
    public static String dashboard_pause_tip;
    public static String dashboard_resume;
    public static String dashboard_resume_tip;
    public static String dashboard_interval_tip;
    public static String dashboard_per_row;
    public static String dashboard_per_row_tip;
    public static String dashboard_charts;
    public static String dashboard_charts_tip;
    public static String dashboard_reset_layout;
    public static String dashboard_clear;
    public static String dashboard_clear_tip;
    public static String dashboard_card_options_tip;
    public static String dashboard_card_wide;
    public static String dashboard_card_move_left;
    public static String dashboard_card_move_right;
    public static String dashboard_card_hide;
    public static String dashboard_card_clear;
    public static String dashboard_waiting;
    public static String dashboard_chart_failed;
    public static String dashboard_no_charts;
    public static String dashboard_no_selection;
    public static String dashboard_status_updated;
    public static String dashboard_status_paused;
    public static String dashboard_status_not_connected;
    public static String dashboard_status_error;
    public static String dashboard_status_failed;

    static {
        NLS.initializeMessages(BUNDLE_NAME, DashboardMessages.class);
    }

    private DashboardMessages() {
    }
}
