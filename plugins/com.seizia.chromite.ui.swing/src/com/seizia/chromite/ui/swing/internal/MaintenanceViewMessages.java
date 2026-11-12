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
 * Activity Monitor의 Maintenance·Replication 탭과 유지 관리 대화상자(DB 관리 축 4·6)의 화면 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 이 기능만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code MaintenanceViewMessages.properties}), 한국어는 {@code MaintenanceViewMessages_ko.properties}이고 OS 언어를 따른다. 키 접두사는 {@code maintview_}다.
 * 동작·옵션·표 제목·열 머리글·권한 안내는 모델 번들의 {@code MaintenanceMessages}·{@code ReplicationMessages}가 맡는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4·6 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-12 아이콘 단추의 도구 설명에만 쓰던 설명문 키를 지움(도구 설명이 메뉴의 이름이 됨) (앱 변경 #103, docs/chromite/swing-frontend.md "아이콘 단추의 도구 설명")
 */
public class MaintenanceViewMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.MaintenanceViewMessages"; //$NON-NLS-1$

    public static String maintview_maint_command;
    public static String maintview_maint_command_tip;
    public static String maintview_maint_tab;
    public static String maintview_repl_command;
    public static String maintview_repl_command_tip;
    public static String maintview_repl_tab;
    public static String maintview_menu;
    public static String maintview_menu_tip;
    public static String maintview_scope_all;
    public static String maintview_scope_tip;
    public static String maintview_limit_label;
    public static String maintview_limit_tip;
    public static String maintview_run_suggested;
    public static String maintview_reading;
    public static String maintview_status_lists;
    public static String maintview_not_supported;
    public static String maintview_details;
    public static String maintview_repl_reading;
    public static String maintview_repl_not_supported;
    public static String maintview_repl_read_only;
    public static String maintview_dialog_title;
    public static String maintview_target;
    public static String maintview_server;
    public static String maintview_operation;
    public static String maintview_options;
    public static String maintview_no_options;
    public static String maintview_state;
    public static String maintview_preview;
    public static String maintview_messages;
    public static String maintview_run;
    public static String maintview_cancel;
    public static String maintview_close;
    public static String maintview_copy;
    public static String maintview_reading_target;
    public static String maintview_read_error;
    public static String maintview_nothing_to_run;
    public static String maintview_running;
    public static String maintview_canceling;
    public static String maintview_done;
    public static String maintview_failed;
    public static String maintview_canceled;
    public static String maintview_confirm_title;
    public static String maintview_confirm;
    public static String maintview_confirm_connection;
    public static String maintview_confirm_more;
    public static String maintview_close_running;
    public static String maintview_msg_error;

    static {
        NLS.initializeMessages(BUNDLE_NAME, MaintenanceViewMessages.class);
    }

    private MaintenanceViewMessages() {
    }
}
