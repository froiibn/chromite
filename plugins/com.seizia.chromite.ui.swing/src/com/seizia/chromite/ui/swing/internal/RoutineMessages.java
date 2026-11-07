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
 * 루틴 명령의 화면 문구: Execute Procedure 대화상자, Go to Source Code, 컴파일 오류 목록, 쿼리 창의 Oracle 컴파일 오류 줄.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 이 기능만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code RoutineMessages.properties}), 한국어는 {@code RoutineMessages_ko.properties}이고 OS 언어를 따른다. 키 접두사는 {@code routine_}다.
 * 인수 표의 열 머리글은 디버거 시작 대화상자와 같은 표({@code RoutineArgumentTable})라 {@code DebugMessages}를 쓴다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-routines.md)
 */
public class RoutineMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.RoutineMessages"; //$NON-NLS-1$

    public static String routine_execute;
    public static String routine_execute_tip;
    public static String routine_execute_title;
    public static String routine_execute_progress;
    public static String routine_execute_no_params;
    public static String routine_execute_hint;
    public static String routine_execute_outputs;
    public static String routine_execute_no_outputs;
    public static String routine_execute_button;
    public static String routine_execute_open_button;
    public static String routine_execute_open_tip;
    public static String routine_go_to_source;
    public static String routine_go_to_source_tip;
    public static String routine_close;
    public static String routine_compile_go_to_error;
    public static String routine_compile_go_to_error_tip;
    public static String routine_compile_col_object;
    public static String routine_compile_col_type;
    public static String routine_compile_col_line;
    public static String routine_compile_col_column;
    public static String routine_compile_col_message;
    public static String routine_compile_warning_prefix;
    public static String routine_compile_errors_header;
    public static String routine_compile_warnings_header;
    public static String routine_compile_error_position;
    public static String routine_compile_error_line;

    static {
        NLS.initializeMessages(BUNDLE_NAME, RoutineMessages.class);
    }

    private RoutineMessages() {
    }
}
