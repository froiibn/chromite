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
 * 프로시저 디버거(Debug…, 디버거 창, 시작 대화상자) 화면 문구.
 *
 * 설계: 다른 기능과 같은 파일을 고치지 않도록(병합 충돌) 이 기능만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code DebugMessages.properties}), 한국어는 {@code DebugMessages_ko.properties}이고, 플랫폼이 OS 언어로 고른다.
 * 키 접두사는 {@code debug_}다. {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 * 단계 실행 명령 이름은 SSMS 한국어판의 이름을 따랐다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/procedure-debugger.md)
 * - 2026-11-07 Oracle 디버거 문구: 디버그 컴파일 질문, 권한 안내, attach·프로시저 나가기 미지원 도움말
 * - 2026-11-08 디버그 컴파일 확인의 컴파일 단추 문구 추가 (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 */
public class DebugMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.DebugMessages"; //$NON-NLS-1$
    public static String debug_menu;
    public static String debug_menu_tip;
    public static String debug_title;
    public static String debug_continue;
    public static String debug_continue_tip;
    public static String debug_stop;
    public static String debug_stop_tip;
    public static String debug_step_over;
    public static String debug_step_over_tip;
    public static String debug_step_into;
    public static String debug_step_into_tip;
    public static String debug_step_out;
    public static String debug_step_out_tip;
    public static String debug_step_out_unsupported_tip;
    public static String debug_tab_variables;
    public static String debug_tab_stack;
    public static String debug_tab_messages;
    public static String debug_tab_results;
    public static String debug_col_name;
    public static String debug_col_type;
    public static String debug_col_value;
    public static String debug_col_function;
    public static String debug_col_line;
    public static String debug_col_mode;
    public static String debug_argument;
    public static String debug_status_idle;
    public static String debug_status_starting;
    public static String debug_status_running;
    public static String debug_status_waiting;
    public static String debug_status_suspended;
    public static String debug_status_ended;
    public static String debug_status_txn_open;
    public static String debug_start_title;
    public static String debug_start_mode_local;
    public static String debug_start_mode_attach;
    public static String debug_start_pid;
    public static String debug_start_params;
    public static String debug_start_no_params;
    public static String debug_start_value_missing;
    public static String debug_start_pid_invalid;
    public static String debug_start_attach_unsupported;
    public static String debug_start_button;
    public static String debug_error_start;
    public static String debug_error_command;
    public static String debug_error_open;
    public static String debug_error_privileges;
    public static String debug_compile_title;
    public static String debug_compile_question;
    public static String debug_compile_button;
    public static String debug_progress_open;
    public static String debug_progress_compile;
    public static String debug_progress_start;
    public static String debug_progress_stop;
    public static String debug_txn_title;
    public static String debug_txn_open;
    public static String debug_txn_failed;
    public static String debug_result_error;
    public static String debug_result_canceled;
    public static String debug_result_rows;
    public static String debug_result_none;
    public static String debug_breakpoint_tip;
    public static String debug_current_line_tip;
    public static String debug_value_error;

    static {
        NLS.initializeMessages(BUNDLE_NAME, DebugMessages.class);
    }

    private DebugMessages() {
    }
}
