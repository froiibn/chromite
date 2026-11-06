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
package com.seizia.chromite.ext.postgresql.model.cron;

import org.eclipse.osgi.util.NLS;

/**
 * Texts of the pg_cron model objects: node names, extension and launcher states, and the explanations of pg_cron errors.
 *
 * 설계: 노드 이름과 상태 값은 모델 객체가 돌려주므로 모델 번들에 둔다. DBeaver의 {@code PostgreSQLMessages}를 고치지 않고 기능 전용
 * 묶음을 따로 두었다(SQL Server 에이전트와 같은 방식).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class PostgreCronMessages extends NLS {
    static final String BUNDLE_NAME = "com.seizia.chromite.ext.postgresql.model.cron.PostgreCronMessages"; //$NON-NLS-1$

    public static String node_name;
    public static String node_run_details;

    public static String state_installed;
    public static String state_not_installed;
    public static String state_no_access;
    public static String state_unknown;

    public static String launcher_running;
    public static String launcher_stopped;
    public static String launcher_unknown;

    public static String error_permission;
    public static String error_not_installed;

    static {
        NLS.initializeMessages(BUNDLE_NAME, PostgreCronMessages.class);
    }

    private PostgreCronMessages() {
    }
}
