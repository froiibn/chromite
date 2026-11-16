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

import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.util.Date;

/**
 * One run of a pg_cron job (a row of {@code cron.job_run_details}).
 *
 * 설계: 작업 실행 기록 탭의 표가 {@code @Property} 열로 보여 주고, 돌려받은 메시지(오류 글)는 표 아래 상세 칸에 보인다.
 * 기간은 끝난 실행만 계산한다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class PostgreCronRunDetail {

    private final long runId;
    private final long jobId;
    @Nullable
    private final String jobName;
    private final String status;
    @Nullable
    private final Date startTime;
    @Nullable
    private final Date endTime;
    private final String database;
    private final String userName;
    private final long jobPid;
    private final String command;
    private final String returnMessage;

    PostgreCronRunDetail(@NotNull ResultSet resultSet) {
        runId = JDBCUtils.safeGetLong(resultSet, "runid"); //$NON-NLS-1$
        jobId = JDBCUtils.safeGetLong(resultSet, "jobid"); //$NON-NLS-1$
        jobName = CommonUtils.nullIfEmpty(JDBCUtils.safeGetString(resultSet, "jobname")); //$NON-NLS-1$
        status = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "status")); //$NON-NLS-1$
        startTime = JDBCUtils.safeGetTimestamp(resultSet, "start_time"); //$NON-NLS-1$
        endTime = JDBCUtils.safeGetTimestamp(resultSet, "end_time"); //$NON-NLS-1$
        database = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "database")); //$NON-NLS-1$
        userName = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "username")); //$NON-NLS-1$
        jobPid = JDBCUtils.safeGetLong(resultSet, "job_pid"); //$NON-NLS-1$
        command = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "command")); //$NON-NLS-1$
        returnMessage = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "return_message")); //$NON-NLS-1$
    }

    public long getRunId() {
        return runId;
    }

    @Nullable
    @Property(viewable = true, order = 1)
    public Date getStartTime() {
        return startTime;
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public String getJob() {
        return jobName != null ? jobName : "#" + jobId; //$NON-NLS-1$
    }

    @NotNull
    @Property(viewable = true, order = 3)
    public String getStatus() {
        return status;
    }

    @NotNull
    @Property(viewable = true, order = 4)
    public String getDuration() {
        if (startTime == null || endTime == null) {
            return ""; //$NON-NLS-1$
        }
        long millis = Math.max(0, endTime.getTime() - startTime.getTime());
        long seconds = millis / 1000;
        return String.format("%02d:%02d:%02d.%03d", seconds / 3600, seconds / 60 % 60, seconds % 60, millis % 1000); //$NON-NLS-1$
    }

    @NotNull
    @Property(viewable = true, order = 5)
    public String getReturnMessage() {
        return returnMessage;
    }

    @NotNull
    @Property(viewable = true, order = 6)
    public String getDatabase() {
        return database;
    }

    @NotNull
    @Property(viewable = true, order = 7)
    public String getUserName() {
        return userName;
    }

    @Property(viewable = true, order = 8)
    public long getJobPid() {
        return jobPid;
    }

    @Nullable
    @Property(viewable = true, order = 9)
    public Date getEndTime() {
        return endTime;
    }

    @NotNull
    public String getCommand() {
        return command;
    }

    public long getJobId() {
        return jobId;
    }

    public boolean isFailed() {
        return "failed".equals(status); //$NON-NLS-1$
    }

    public boolean isSucceeded() {
        return "succeeded".equals(status); //$NON-NLS-1$
    }
}
