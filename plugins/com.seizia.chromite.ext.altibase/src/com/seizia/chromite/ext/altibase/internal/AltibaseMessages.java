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
package com.seizia.chromite.ext.altibase.internal;

import org.eclipse.osgi.util.NLS;

/**
 * Screen texts of the Altibase model that are values, not property or tree labels.
 *
 * 설계: 트리 이름과 속성 열 이름은 {@code OSGI-INF/l10n/bundle*.properties}가 옮기지만, System Info &gt; 서버 정보의 항목 이름은
 * 객체의 값({@code AltibaseServerInfoItem#getName})이라 그 표가 옮길 수 없다. 그래서 이 번들의 첫 NLS 문구 클래스를 둔다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: 서버 정보 항목 이름 (앱 변경 #139 작업 8, docs/chromite/altibase.md "개요 차트와 서버 정보")
 * - 2026-11-17 작업의 주기와 종류 값(앱 변경 #139 수정 5, docs/chromite/altibase.md "작업")
 * - 2026-11-22 작업 정의에서 빠진 값을 알리는 주석 줄(docs/chromite/altibase.md "작업 정의 보기")
 */
public class AltibaseMessages extends NLS {

    private static final String BUNDLE_NAME = "com.seizia.chromite.ext.altibase.internal.AltibaseMessages"; //$NON-NLS-1$

    public static String server_info_version;
    public static String server_info_build;
    public static String server_info_build_time;
    public static String server_info_sm_version;
    public static String server_info_meta_version;
    public static String server_info_protocol_version;
    public static String server_info_startup_phase;
    public static String server_info_started;
    public static String server_info_uptime;
    public static String server_info_uptime_value;
    public static String server_info_status;
    public static String server_info_archive_mode;
    public static String server_info_archiver;
    public static String server_info_running;
    public static String server_info_stopped;
    public static String server_info_current_log;
    public static String server_info_next_archive_log;
    public static String server_info_backups;
    public static String server_info_last_backup;

    // A job's interval ({0}: the number) by SYS_JOBS_.INTERVAL_TYPE, and its type
    public static String job_interval_year;
    public static String job_interval_month;
    public static String job_interval_day;
    public static String job_interval_hour;
    public static String job_interval_minute;
    public static String job_type_procedure;
    // A comment line before a job's definition naming a catalog value the statement leaves out ({0}: the clause)
    public static String job_ddl_left_out;

    static {
        NLS.initializeMessages(BUNDLE_NAME, AltibaseMessages.class);
    }

    private AltibaseMessages() {
    }
}
