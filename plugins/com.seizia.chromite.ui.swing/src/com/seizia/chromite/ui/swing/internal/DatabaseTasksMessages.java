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
 * Database Tasks 도구 창(저장한 작업 목록)의 화면 문구.
 *
 * 설계: 다른 기능과 병합 충돌을 피하려고 이 기능만의 묶음으로 둔다(docs/chromite/swing-l10n.md의 영역 나눔과 같은 방식).
 * 영어가 기본, 한국어는 {@code DatabaseTasksMessages_ko.properties}다. {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로
 * 작은따옴표를 두 번 쓴다('').
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-database-tasks.md)
 */
public class DatabaseTasksMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.DatabaseTasksMessages"; //$NON-NLS-1$

    public static String tasks_title;
    public static String tasks_empty;
    public static String tasks_column_name;
    public static String tasks_column_type;
    public static String tasks_column_connection;
    public static String tasks_column_last_run;
    public static String tasks_column_result;
    public static String tasks_column_duration;
    public static String tasks_result_never;
    public static String tasks_result_running;
    public static String tasks_result_success;
    public static String tasks_result_failed;
    public static String tasks_result_canceled;
    public static String tasks_run;
    public static String tasks_run_tip;
    public static String tasks_run_confirm_title;
    public static String tasks_run_confirm;
    public static String tasks_no_client;
    public static String tasks_edit;
    public static String tasks_edit_tip;
    public static String tasks_edit_unsupported;
    public static String tasks_show_log;
    public static String tasks_show_log_tip;
    public static String tasks_log_title;
    public static String tasks_log_none;
    public static String tasks_delete;
    public static String tasks_delete_tip;
    public static String tasks_delete_title;
    public static String tasks_delete_confirm;
    public static String tasks_delete_button;
    public static String tasks_refresh;
    public static String tasks_refresh_tip;
    public static String tasks_finished;
    public static String tasks_failed;

    static {
        NLS.initializeMessages(BUNDLE_NAME, DatabaseTasksMessages.class);
    }

    private DatabaseTasksMessages() {
    }
}
