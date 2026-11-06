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
package com.seizia.chromite.model.admin.logs;

import org.eclipse.osgi.util.NLS;

/**
 * 서버 로그 화면의 모델 문구: 로그 이름, 보관본, 수준, 열 머리글, 읽을 수 없는 이유.
 *
 * 설계: 로그 목록과 이유는 DB별 방언이 만들므로 모델 번들에 둔다({@code SpaceMessages}와 같은 방식). 키 접두사는 {@code log_}다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 서버 로그 (docs/chromite/swing-admin-tools.md)
 */
public class LogMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.model.admin.logs.LogMessages"; //$NON-NLS-1$

    public static String log_archive_current;
    public static String log_archive_current_at;
    public static String log_archive_number;
    public static String log_archive_current_file;
    public static String log_col_process;
    public static String log_col_subsystem;
    public static String log_col_user_host;
    public static String log_col_component;
    public static String log_level_error;
    public static String log_level_warning;
    public static String log_level_information;
    public static String log_level_critical;
    public static String log_level_severe;
    public static String log_level_important;
    public static String log_level_normal;
    public static String log_mssql_error_log;
    public static String log_mssql_agent_log;
    public static String log_mssql_edition;
    public static String log_mssql_no_permission;
    public static String log_postgres_server_log;
    public static String log_postgres_server_log_format;
    public static String log_postgres_format_text;
    public static String log_postgres_format_csv;
    public static String log_postgres_format_json;
    public static String log_postgres_file;
    public static String log_postgres_level_errors;
    public static String log_postgres_version;
    public static String log_postgres_no_collector;
    public static String log_postgres_no_permission;
    public static String log_postgres_no_file_destination;
    public static String log_postgres_no_files;
    public static String log_mysql_error_log;
    public static String log_mysql_slow_log;
    public static String log_mysql_stderr;
    public static String log_mysql_mariadb_error_log;
    public static String log_mysql_old_error_log;
    public static String log_mysql_no_permission;
    public static String log_mysql_slow_off;
    public static String log_mysql_slow_file;
    public static String log_mysql_slow_detail;
    public static String log_oracle_alert_log;
    public static String log_oracle_version;
    public static String log_oracle_no_permission;
    public static String log_oracle_level_ora;

    static {
        NLS.initializeMessages(BUNDLE_NAME, LogMessages.class);
    }

    private LogMessages() {
    }
}
