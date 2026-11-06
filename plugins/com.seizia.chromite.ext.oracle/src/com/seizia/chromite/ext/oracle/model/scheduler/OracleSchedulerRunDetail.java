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

import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One row of {@code *_SCHEDULER_JOB_RUN_DETAILS}: a finished run of a scheduler job with its outcome, timing and error.
 *
 * 설계: 작업 실행 기록 탭의 표({@code ObjectListTable})가 {@code @Property} 열로 보여 준다. 오류·출력(ERRORS, OUTPUT은 12.2부터 있는 열)은
 * 표 아래 상세 칸에 보이므로 열로 두지 않는다. 열이 없는 서버 버전에서는 {@link JDBCUtils#safeGetString}이 null을 돌려준다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class OracleSchedulerRunDetail {

    private static final Pattern INTERVAL = Pattern.compile("([+-])?(\\d+) (\\d+):(\\d+):(\\d+)(?:\\.\\d+)?"); //$NON-NLS-1$

    private final long logId;
    @Nullable
    private final Date logDate;
    private final String jobName;
    private final String status;
    private final long errorNumber;
    @Nullable
    private final Date requestedStart;
    @Nullable
    private final Date actualStart;
    private final String runDuration;
    private final String sessionId;
    private final String cpuUsed;
    @Nullable
    private final String additionalInfo;
    @Nullable
    private final String errors;
    @Nullable
    private final String output;

    OracleSchedulerRunDetail(@NotNull ResultSet resultSet) {
        logId = JDBCUtils.safeGetLong(resultSet, "LOG_ID"); //$NON-NLS-1$
        logDate = JDBCUtils.safeGetTimestamp(resultSet, "LOG_DATE"); //$NON-NLS-1$
        jobName = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "JOB_NAME")); //$NON-NLS-1$
        status = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "STATUS")); //$NON-NLS-1$
        errorNumber = JDBCUtils.safeGetLong(resultSet, "ERROR#"); //$NON-NLS-1$
        requestedStart = JDBCUtils.safeGetTimestamp(resultSet, "REQ_START_DATE"); //$NON-NLS-1$
        actualStart = JDBCUtils.safeGetTimestamp(resultSet, "ACTUAL_START_DATE"); //$NON-NLS-1$
        runDuration = formatInterval(JDBCUtils.safeGetString(resultSet, "RUN_DURATION")); //$NON-NLS-1$
        sessionId = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "SESSION_ID")); //$NON-NLS-1$
        cpuUsed = formatInterval(JDBCUtils.safeGetString(resultSet, "CPU_USED")); //$NON-NLS-1$
        additionalInfo = JDBCUtils.safeGetString(resultSet, "ADDITIONAL_INFO"); //$NON-NLS-1$
        errors = JDBCUtils.safeGetString(resultSet, "ERRORS"); //$NON-NLS-1$
        output = JDBCUtils.safeGetString(resultSet, "OUTPUT"); //$NON-NLS-1$
    }

    /**
     * An Oracle INTERVAL DAY TO SECOND text ({@code +00 00:00:01.250000}) as {@code 00:00:01}, with the days in front when
     * there are any; other text as it is.
     */
    @NotNull
    static String formatInterval(@Nullable String interval) {
        if (interval == null) {
            return ""; //$NON-NLS-1$
        }
        Matcher matcher = INTERVAL.matcher(interval.trim());
        if (!matcher.matches()) {
            return interval;
        }
        long days = Long.parseLong(matcher.group(2));
        String time = String.format("%02d:%02d:%02d", //$NON-NLS-1$
            Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)), Integer.parseInt(matcher.group(5)));
        return days > 0 ? days + " " + time : time; //$NON-NLS-1$
    }

    public long getLogId() {
        return logId;
    }

    @Nullable
    @Property(viewable = true, order = 1)
    public Date getLogDate() {
        return logDate;
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public String getJobName() {
        return jobName;
    }

    @NotNull
    @Property(viewable = true, order = 3)
    public String getStatus() {
        return status;
    }

    @Property(viewable = true, order = 4)
    public long getErrorNumber() {
        return errorNumber;
    }

    @Nullable
    @Property(viewable = true, order = 5)
    public Date getRequestedStart() {
        return requestedStart;
    }

    @Nullable
    @Property(viewable = true, order = 6)
    public Date getActualStart() {
        return actualStart;
    }

    @NotNull
    @Property(viewable = true, order = 7)
    public String getRunDuration() {
        return runDuration;
    }

    @NotNull
    @Property(viewable = true, order = 8)
    public String getSessionId() {
        return sessionId;
    }

    @NotNull
    @Property(viewable = true, order = 9)
    public String getCpuUsed() {
        return cpuUsed;
    }

    @Nullable
    public String getAdditionalInfo() {
        return additionalInfo;
    }

    @Nullable
    public String getErrors() {
        return errors;
    }

    @Nullable
    public String getOutput() {
        return output;
    }

    public boolean isFailed() {
        return errorNumber != 0 || "FAILED".equals(status); //$NON-NLS-1$
    }

    public boolean isSucceeded() {
        return "SUCCEEDED".equals(status); //$NON-NLS-1$
    }
}
