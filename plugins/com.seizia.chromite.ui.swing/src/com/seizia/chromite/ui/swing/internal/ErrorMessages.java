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
 * 오류 문구(오류 설명, 오류 대화상자의 자세히·복사, 오래 걸린 작업 알림)의 화면 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 오류 표시만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code ErrorMessages.properties}), 한국어는 {@code ErrorMessages_ko.properties}이고 OS 언어를 따른다.
 * 키 접두사는 오류 설명 {@code error_}, 오류 종류 {@code error_kind_}, 대화상자 {@code dialog_}, 알림 {@code agent_}다.
 * {@code CoreMessages}에 두지 않은 것은 같은 날 다른 작업(PR #55)이 그 파일을 고쳐 병합 충돌을 피하려는 것이다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-frontend.md "오류 문구")
 */
public class ErrorMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.ErrorMessages"; //$NON-NLS-1$

    public static String error_unexpected;
    public static String error_unexpected_kind;
    public static String error_kind_detail;
    public static String error_kind_internal;
    public static String error_kind_out_of_memory;
    public static String error_kind_stack_overflow;
    public static String error_kind_canceled;
    public static String error_kind_timeout;
    public static String error_kind_io;
    public static String error_kind_unknown_host;
    public static String error_kind_file_not_found;
    public static String error_kind_file_exists;
    public static String error_kind_access_denied;
    public static String error_kind_directory_not_empty;
    public static String error_kind_not_directory;
    public static String error_kind_class_not_found;
    public static String error_sql_code;
    public static String error_sql_state;
    public static String error_sql_code_state;
    public static String error_disconnect;

    public static String dialog_details;
    public static String dialog_details_button;
    public static String dialog_copy;

    public static String agent_long_operation_finished;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ErrorMessages.class);
    }

    private ErrorMessages() {
    }
}
