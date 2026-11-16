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
 * SQL Server 실행 추적(Trace Execution…, 추적 탭, 인수 대화상자) 화면 문구.
 *
 * 설계: 디버거 문구({@link DebugMessages})와 같은 방식으로 이 기능만의 {@link NLS} 묶음을 둔다(다른 기능과 같은 파일을 고치지 않도록).
 * 영어가 기본({@code TraceMessages.properties}), 한국어는 {@code TraceMessages_ko.properties}다. 키 접두사는 {@code trace_}다.
 * {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/sqlserver-execution-trace.md)
 */
public class TraceMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.TraceMessages"; //$NON-NLS-1$
    public static String trace_menu;
    public static String trace_menu_tip;
    public static String trace_title;
    public static String trace_run;
    public static String trace_run_tip;
    public static String trace_end_txn;
    public static String trace_end_txn_tip;
    public static String trace_module;
    public static String trace_tab_timeline;
    public static String trace_tab_lines;
    public static String trace_tab_messages;
    public static String trace_tab_results;
    public static String trace_col_sequence;
    public static String trace_col_module;
    public static String trace_col_line;
    public static String trace_col_statement;
    public static String trace_col_duration;
    public static String trace_col_cpu;
    public static String trace_col_rows;
    public static String trace_col_logical_reads;
    public static String trace_col_physical_reads;
    public static String trace_col_writes;
    public static String trace_col_timeline;
    public static String trace_col_executions;
    public static String trace_col_total;
    public static String trace_col_max;
    public static String trace_col_name;
    public static String trace_col_type;
    public static String trace_col_direction;
    public static String trace_col_value;
    public static String trace_direction_input;
    public static String trace_direction_output;
    public static String trace_value_not_accepted;
    public static String trace_return_value;
    public static String trace_call;
    public static String trace_dynamic;
    public static String trace_source_unavailable;
    public static String trace_line_tip;
    public static String trace_status_idle;
    public static String trace_status_done;
    public static String trace_status_failed;
    public static String trace_status_canceled;
    public static String trace_status_txn_open;
    public static String trace_msg_result_set;
    public static String trace_msg_error;
    public static String trace_msg_canceled;
    public static String trace_msg_dropped;
    public static String trace_msg_incomplete;
    public static String trace_msg_none;
    public static String trace_start_title;
    public static String trace_start_params;
    public static String trace_start_no_params;
    public static String trace_start_button;
    public static String trace_error_open;
    public static String trace_error_run;
    public static String trace_error_privileges;
    public static String trace_progress_open;
    public static String trace_progress_run;
    public static String trace_progress_end;
    public static String trace_txn_title;
    public static String trace_txn_open;
    public static String trace_txn_failed;
    public static String trace_txn_unknown;
    public static String trace_txn_close;

    static {
        NLS.initializeMessages(BUNDLE_NAME, TraceMessages.class);
    }

    private TraceMessages() {
    }
}
