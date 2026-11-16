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
package com.seizia.chromite.ext.mssql.model.agent;

import org.eclipse.osgi.util.NLS;

/**
 * Texts of the SQL Server Agent model objects: node names, state and outcome values shown in the tree, the Properties
 * window and the job grids, and the explanations of agent errors.
 *
 * 설계: 탐색기 노드 이름(SQL Server 에이전트, 작업 활동 모니터)과 {@code @Property} 값은 모델 객체가 돌려주므로 모델 번들에 둔다.
 * DBeaver의 {@code SQLServerMessages}를 고치지 않고 기능 전용 묶음을 따로 두어 upstream 파일과 섞이지 않게 했다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public class SQLServerAgentMessages extends NLS {
    static final String BUNDLE_NAME = "com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentMessages"; //$NON-NLS-1$

    public static String agent_node_name;
    public static String agent_activity_monitor_node_name;

    public static String agent_state_running;
    public static String agent_state_stopped;
    public static String agent_state_not_supported;
    public static String agent_state_unknown;

    public static String job_outcome_failed;
    public static String job_outcome_succeeded;
    public static String job_outcome_retry;
    public static String job_outcome_canceled;
    public static String job_outcome_in_progress;
    public static String job_outcome_unknown;

    public static String job_status_executing;
    public static String job_status_waiting_thread;
    public static String job_status_between_retries;
    public static String job_status_idle;
    public static String job_status_suspended;
    public static String job_status_completing;

    public static String error_log_current;
    public static String error_log_current_no_date;
    public static String error_log_archive;

    public static String error_permission;
    public static String error_not_running;
    public static String error_not_supported;

    static {
        NLS.initializeMessages(BUNDLE_NAME, SQLServerAgentMessages.class);
    }

    private SQLServerAgentMessages() {
    }
}
