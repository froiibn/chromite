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
 * 데이터 내보내기·테이블 복사의 고급 옵션 화면 문구: 원본 읽기 설정, 출력 파일 나누기·시각 형식, 데이터 형식 프로필, 내보낼 열 고르기,
 * 중복 키 처리, 매핑 규칙, 열 값 바꾸기, 지우는 작업의 미리 보기와 확인.
 *
 * 설계: 같은 마법사를 고치는 다른 작업(작업 저장 등)과 병합 충돌이 나지 않도록 {@link ToolsMessages}·{@link DbCopyMessages}와 나눈 묶음이다.
 * 영어가 기본({@code TransferOptionsMessages.properties}), 한국어는 {@code TransferOptionsMessages_ko.properties}이고 플랫폼이 OS 언어로 고른다.
 * 키는 {@code 영역_항목} 형식이고 영역은 extract, output, format, columns, load, rules, transform, ddl, confirm, summary다.
 * {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-data-transfer.md "고급 옵션")
 */
public class TransferOptionsMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.TransferOptionsMessages"; //$NON-NLS-1$

    public static String extract_title;
    public static String extract_description;
    public static String extract_new_connection;
    public static String extract_row_count;
    public static String extract_fetch_size;
    public static String extract_fetch_size_note;
    public static String extract_segments;
    public static String extract_segment_size;
    public static String extract_note;
    public static String output_timestamp;
    public static String output_timestamp_note;
    public static String output_split;
    public static String output_max_size;
    public static String output_max_size_note;
    public static String output_error_timestamp;
    public static String format_profile;
    public static String format_profile_note;
    public static String format_profile_connection;
    public static String format_profile_global;
    public static String format_columns;
    public static String format_columns_all;
    public static String format_columns_some;
    public static String format_columns_button;
    public static String columns_title;
    public static String columns_table;
    public static String columns_column_export;
    public static String columns_column_name;
    public static String columns_column_type;
    public static String columns_select_all;
    public static String columns_select_none;
    public static String columns_error_none;
    public static String columns_progress;
    public static String load_on_duplicate;
    public static String load_on_duplicate_none;
    public static String load_on_duplicate_note;
    public static String load_on_duplicate_unsupported;
    public static String load_ignore_duplicates;
    public static String load_skip_bind;
    public static String load_qm_logging;
    public static String rules_button;
    public static String rules_title;
    public static String rules_note;
    public static String rules_scope;
    public static String rules_global;
    public static String transform_button;
    public static String transform_title;
    public static String transform_none;
    public static String transform_null;
    public static String transform_constant;
    public static String transform_expression;
    public static String transform_expression_note;
    public static String transform_error_empty;
    public static String columns_value;
    public static String ddl_truncate;
    public static String confirm_title;
    public static String confirm_button;
    public static String confirm_truncate;
    public static String confirm_recreate;
    public static String confirm_question;
    public static String summary_extract_main_session;
    public static String summary_extract_no_count;
    public static String summary_extract_fetch_size;
    public static String summary_extract_segments;
    public static String summary_timestamp;
    public static String summary_split;
    public static String summary_profile;
    public static String summary_columns;
    public static String summary_truncate;
    public static String summary_recreate;
    public static String summary_on_duplicate;
    public static String summary_ignore_duplicates;
    public static String summary_skip_bind;
    public static String summary_qm_logging;
    public static String summary_transform;

    static {
        NLS.initializeMessages(BUNDLE_NAME, TransferOptionsMessages.class);
    }

    private TransferOptionsMessages() {
    }
}
