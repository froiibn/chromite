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
package com.seizia.chromite.ext.mysql.model.scheduler;

import org.eclipse.osgi.util.NLS;

/**
 * Texts of the MySQL event scheduler model: the event_scheduler states and the explanation of a missing EVENT privilege.
 *
 * 설계: DBeaver의 {@code MySQLMessages}를 고치지 않고 기능 전용 묶음을 따로 두었다(SQL Server 에이전트와 같은 방식).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class MySQLEventSchedulerMessages extends NLS {
    static final String BUNDLE_NAME = "com.seizia.chromite.ext.mysql.model.scheduler.MySQLEventSchedulerMessages"; //$NON-NLS-1$

    public static String state_on;
    public static String state_off;
    public static String state_disabled;
    public static String state_unknown;

    public static String error_permission;

    static {
        NLS.initializeMessages(BUNDLE_NAME, MySQLEventSchedulerMessages.class);
    }

    private MySQLEventSchedulerMessages() {
    }
}
