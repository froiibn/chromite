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
 * Activity Monitor의 Disk Usage·Server Logs 탭(DB 관리 축 3·5)의 화면 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 이 두 탭만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code AdminViewMessages.properties}), 한국어는 {@code AdminViewMessages_ko.properties}이고 OS 언어를 따른다. 키 접두사는 {@code admin_}이다.
 * 표 제목, 열 머리글, 권한 안내는 모델 번들의 {@code SpaceMessages}·{@code LogMessages}가 맡는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-12 아이콘 단추의 도구 설명에만 쓰던 설명문 키를 지움(도구 설명이 메뉴의 이름이 됨) (앱 변경 #103, docs/chromite/swing-frontend.md "아이콘 단추의 도구 설명")
 */
public class AdminViewMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.AdminViewMessages"; //$NON-NLS-1$

    public static String admin_space_command;
    public static String admin_space_command_tip;
    public static String admin_space_tab;
    public static String admin_space_scope_database;
    public static String admin_space_scope_tip;
    public static String admin_space_scope_all;
    public static String admin_space_tables_label;
    public static String admin_space_tables_tip;
    public static String admin_space_reading;
    public static String admin_space_status_one;
    public static String admin_space_status_many;
    public static String admin_space_not_supported;
    public static String admin_unlimited;
    public static String admin_bytes_tip;
    public static String admin_copy;
    public static String admin_copy_headers;
    public static String admin_copy_exact;
    public static String admin_copy_exact_tip;
    public static String admin_logs_command;
    public static String admin_logs_command_tip;
    public static String admin_logs_tab;
    public static String admin_logs_source;
    public static String admin_logs_source_tip;
    public static String admin_logs_archive;
    public static String admin_logs_archive_tip;
    public static String admin_logs_time;
    public static String admin_logs_time_tip;
    public static String admin_logs_time_hour;
    public static String admin_logs_time_day;
    public static String admin_logs_time_week;
    public static String admin_logs_time_month;
    public static String admin_logs_time_all;
    public static String admin_logs_time_custom;
    public static String admin_logs_from;
    public static String admin_logs_to;
    public static String admin_logs_apply;
    public static String admin_logs_time_format_tip;
    public static String admin_logs_bad_time;
    public static String admin_logs_level;
    public static String admin_logs_level_tip;
    public static String admin_logs_level_all;
    public static String admin_logs_search_placeholder;
    public static String admin_logs_search_tip;
    public static String admin_logs_col_time;
    public static String admin_logs_col_level;
    public static String admin_logs_col_message;
    public static String admin_logs_load_more;
    public static String admin_logs_load_more_tip;
    public static String admin_logs_listing;
    public static String admin_logs_reading;
    public static String admin_logs_none;
    public static String admin_logs_status_all;
    public static String admin_logs_status_more;
    public static String admin_logs_status_capped;
    public static String admin_logs_unavailable_status;
    public static String admin_logs_not_supported;

    static {
        NLS.initializeMessages(BUNDLE_NAME, AdminViewMessages.class);
    }

    private AdminViewMessages() {
    }
}
