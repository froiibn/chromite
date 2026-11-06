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

import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;

/**
 * One row of {@code *_SCHEDULER_RUNNING_JOBS}: a scheduler job running now, with its session and elapsed time.
 *
 * 설계: 실행 중인 작업 탭의 표가 {@code @Property} 열로 보여 주고, 고른 행의 작업을 중지할 때 스키마와 이름을 쓴다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class OracleSchedulerRunningJob {

    private final OracleSchema schema;
    private final String jobName;
    @Nullable
    private final String jobSubName;
    private final String sessionId;
    private final String slaveProcessId;
    private final String runningInstance;
    private final String elapsedTime;
    private final String cpuUsed;

    OracleSchedulerRunningJob(@NotNull OracleSchema schema, @NotNull ResultSet resultSet) {
        this.schema = schema;
        jobName = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "JOB_NAME")); //$NON-NLS-1$
        jobSubName = CommonUtils.nullIfEmpty(JDBCUtils.safeGetString(resultSet, "JOB_SUBNAME")); //$NON-NLS-1$
        sessionId = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "SESSION_ID")); //$NON-NLS-1$
        slaveProcessId = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "SLAVE_PROCESS_ID")); //$NON-NLS-1$
        runningInstance = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "RUNNING_INSTANCE")); //$NON-NLS-1$
        elapsedTime = OracleSchedulerRunDetail.formatInterval(JDBCUtils.safeGetString(resultSet, "ELAPSED_TIME")); //$NON-NLS-1$
        cpuUsed = OracleSchedulerRunDetail.formatInterval(JDBCUtils.safeGetString(resultSet, "CPU_USED")); //$NON-NLS-1$
    }

    @NotNull
    public OracleSchema getSchema() {
        return schema;
    }

    @NotNull
    @Property(viewable = true, order = 1)
    public String getJobName() {
        return jobName;
    }

    @Nullable
    @Property(viewable = true, optional = true, order = 2)
    public String getJobSubName() {
        return jobSubName;
    }

    @NotNull
    @Property(viewable = true, order = 3)
    public String getSessionId() {
        return sessionId;
    }

    @NotNull
    @Property(viewable = true, order = 4)
    public String getSlaveProcessId() {
        return slaveProcessId;
    }

    @NotNull
    @Property(viewable = true, order = 5)
    public String getRunningInstance() {
        return runningInstance;
    }

    @NotNull
    @Property(viewable = true, order = 6)
    public String getElapsedTime() {
        return elapsedTime;
    }

    @NotNull
    @Property(viewable = true, order = 7)
    public String getCpuUsed() {
        return cpuUsed;
    }
}
