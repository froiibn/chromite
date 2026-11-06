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
package com.seizia.chromite.ext.oracle.model.scheduler;

import org.eclipse.osgi.util.NLS;

/**
 * Texts of the Oracle Scheduler model objects: leaf node names, scheduler states and the explanations of scheduler errors.
 *
 * 설계: 탐색기 노드 이름과 상태 값은 모델 객체가 돌려주므로 모델 번들에 둔다. DBeaver의 {@code OracleMessages}를 고치지 않고 기능 전용
 * 묶음을 따로 두어 upstream 파일과 섞이지 않게 했다(SQL Server 에이전트의 {@code SQLServerAgentMessages}와 같은 방식).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class OracleSchedulerMessages extends NLS {
    static final String BUNDLE_NAME = "com.seizia.chromite.ext.oracle.model.scheduler.OracleSchedulerMessages"; //$NON-NLS-1$

    public static String node_run_details;
    public static String node_running_jobs;

    public static String state_running;
    public static String state_stopped;
    public static String state_disabled;
    public static String state_unknown;

    public static String error_permission;
    public static String error_not_running;

    static {
        NLS.initializeMessages(BUNDLE_NAME, OracleSchedulerMessages.class);
    }

    private OracleSchedulerMessages() {
    }
}
