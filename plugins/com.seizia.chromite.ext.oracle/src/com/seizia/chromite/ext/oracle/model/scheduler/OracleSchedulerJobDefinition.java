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

import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleSchedulerJob;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The editable state of an Oracle Scheduler job (the New Job / Job Properties dialog) and the {@code DBMS_SCHEDULER}
 * blocks that create or change it.
 *
 * 설계: 새 작업은 {@code CREATE_JOB} 한 번, 기존 작업은 바뀐 속성만 {@code SET_ATTRIBUTE}(값을 비우면 {@code SET_ATTRIBUTE_NULL})로
 * 고치고 사용 여부는 마지막에 {@code ENABLE}/{@code DISABLE}로 맞춘다. 바꾸는 호출은 한 PL/SQL 블록에 모아 미리 보기·쿼리 창·실행에
 * 같은 글을 쓴다. 작업 유형은 PL/SQL 블록과 저장 프로시저만 만들고 고친다. 프로그램이나 이름 있는 일정을 쓰는 작업, 다른 유형
 * (EXECUTABLE, CHAIN …)은 실행 동작·일정을 그대로 두고({@link #isActionEditable}, {@link #isScheduleEditable}) 나머지만 고친다.
 * 날짜는 {@link OracleSchedulerManager#DATE_FORMAT} 글자로 다루어 지역 시간대 이름(일광 절약 시간)을 잃지 않는다.
 * 작업 이름은 DBMS_SCHEDULER에 이름 바꾸기가 없어 기존 작업에서는 바꿀 수 없다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class OracleSchedulerJobDefinition {

    public static final String TYPE_PLSQL_BLOCK = "PLSQL_BLOCK"; //$NON-NLS-1$
    public static final String TYPE_STORED_PROCEDURE = "STORED_PROCEDURE"; //$NON-NLS-1$

    private final OracleSchema schema;
    @Nullable
    private final OracleSchedulerJobDefinition original;
    private String name = ""; //$NON-NLS-1$
    private String jobType = TYPE_PLSQL_BLOCK;
    private String jobAction = ""; //$NON-NLS-1$
    @Nullable
    private String startDate;
    @Nullable
    private String repeatInterval;
    @Nullable
    private String endDate;
    private boolean enabled = true;
    private boolean autoDrop;
    @Nullable
    private String comments;
    @Nullable
    private String programName;
    @Nullable
    private String scheduleName;

    /**
     * A new job in a schema.
     */
    public OracleSchedulerJobDefinition(@NotNull OracleSchema schema) {
        this.schema = schema;
        this.original = null;
    }

    private OracleSchedulerJobDefinition(@NotNull OracleSchema schema, @NotNull OracleSchedulerJobDefinition original) {
        this.schema = schema;
        this.original = original;
        copyFrom(original);
    }

    private void copyFrom(@NotNull OracleSchedulerJobDefinition other) {
        name = other.name;
        jobType = other.jobType;
        jobAction = other.jobAction;
        startDate = other.startDate;
        repeatInterval = other.repeatInterval;
        endDate = other.endDate;
        enabled = other.enabled;
        autoDrop = other.autoDrop;
        comments = other.comments;
        programName = other.programName;
        scheduleName = other.scheduleName;
    }

    /**
     * Reads a job as it is now (the dates as text in {@link OracleSchedulerManager#DATE_FORMAT}).
     */
    @NotNull
    public static OracleSchedulerJobDefinition load(@NotNull DBRProgressMonitor monitor, @NotNull OracleSchedulerJob job) throws DBException {
        OracleSchema schema = job.getSchema();
        OracleSchedulerJobDefinition loaded = new OracleSchedulerJobDefinition(schema);
        String format = OracleSchedulerManager.DATE_FORMAT;
        try (JDBCSession session = DBUtils.openMetaSession(monitor, schema, "Read Oracle Scheduler job")) { //$NON-NLS-1$
            try (JDBCPreparedStatement statement = session.prepareStatement(
                "SELECT JOB_TYPE, JOB_ACTION, TO_CHAR(START_DATE, '" + format + "') AS START_DATE, REPEAT_INTERVAL, " //$NON-NLS-1$ //$NON-NLS-2$
                    + "TO_CHAR(END_DATE, '" + format + "') AS END_DATE, ENABLED, AUTO_DROP, COMMENTS, PROGRAM_NAME, SCHEDULE_NAME FROM " //$NON-NLS-1$ //$NON-NLS-2$
                    + OracleSchedulerManager.getView(monitor, schema.getDataSource(), "SCHEDULER_JOBS") //$NON-NLS-1$
                    + " WHERE OWNER = ? AND JOB_NAME = ?")) { //$NON-NLS-1$
                statement.setString(1, schema.getName());
                statement.setString(2, job.getName());
                try (JDBCResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new DBException("Job " + job.getName() + " not found"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    loaded.name = job.getName();
                    loaded.jobType = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "JOB_TYPE")); //$NON-NLS-1$
                    loaded.jobAction = CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "JOB_ACTION")); //$NON-NLS-1$
                    loaded.startDate = JDBCUtils.safeGetString(resultSet, "START_DATE"); //$NON-NLS-1$
                    loaded.repeatInterval = JDBCUtils.safeGetString(resultSet, "REPEAT_INTERVAL"); //$NON-NLS-1$
                    loaded.endDate = JDBCUtils.safeGetString(resultSet, "END_DATE"); //$NON-NLS-1$
                    loaded.enabled = "TRUE".equals(JDBCUtils.safeGetString(resultSet, "ENABLED")); //$NON-NLS-1$ //$NON-NLS-2$
                    loaded.autoDrop = "TRUE".equals(JDBCUtils.safeGetString(resultSet, "AUTO_DROP")); //$NON-NLS-1$ //$NON-NLS-2$
                    loaded.comments = JDBCUtils.safeGetString(resultSet, "COMMENTS"); //$NON-NLS-1$
                    loaded.programName = JDBCUtils.safeGetString(resultSet, "PROGRAM_NAME"); //$NON-NLS-1$
                    loaded.scheduleName = JDBCUtils.safeGetString(resultSet, "SCHEDULE_NAME"); //$NON-NLS-1$
                }
            }
        } catch (SQLException e) {
            throw OracleSchedulerException.wrap(e, schema.getDataSource(), null);
        }
        return new OracleSchedulerJobDefinition(schema, loaded);
    }

    public boolean isNew() {
        return original == null;
    }

    @NotNull
    public OracleSchema getSchema() {
        return schema;
    }

    /**
     * False for jobs whose action comes from a program or whose type the dialog does not edit.
     */
    public boolean isActionEditable() {
        return programName == null && (TYPE_PLSQL_BLOCK.equals(jobType) || TYPE_STORED_PROCEDURE.equals(jobType));
    }

    /**
     * False for jobs that use a named schedule.
     */
    public boolean isScheduleEditable() {
        return scheduleName == null;
    }

    @Nullable
    public String getProgramName() {
        return programName;
    }

    @Nullable
    public String getScheduleName() {
        return scheduleName;
    }

    @NotNull
    public String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    @NotNull
    public String getJobType() {
        return jobType;
    }

    public void setJobType(@NotNull String jobType) {
        this.jobType = jobType;
    }

    @NotNull
    public String getJobAction() {
        return jobAction;
    }

    public void setJobAction(@NotNull String jobAction) {
        this.jobAction = jobAction;
    }

    @Nullable
    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(@Nullable String startDate) {
        this.startDate = CommonUtils.nullIfEmpty(startDate == null ? null : startDate.trim());
    }

    @Nullable
    public String getRepeatInterval() {
        return repeatInterval;
    }

    public void setRepeatInterval(@Nullable String repeatInterval) {
        this.repeatInterval = CommonUtils.nullIfEmpty(repeatInterval == null ? null : repeatInterval.trim());
    }

    @Nullable
    public String getEndDate() {
        return endDate;
    }

    public void setEndDate(@Nullable String endDate) {
        this.endDate = CommonUtils.nullIfEmpty(endDate == null ? null : endDate.trim());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutoDrop() {
        return autoDrop;
    }

    public void setAutoDrop(boolean autoDrop) {
        this.autoDrop = autoDrop;
    }

    @Nullable
    public String getComments() {
        return comments;
    }

    public void setComments(@Nullable String comments) {
        this.comments = CommonUtils.nullIfEmpty(comments);
    }

    //////////////////////////////////////////////////////////
    // Statements

    /**
     * The block that creates the job, or the block of the changed attributes; empty when nothing changed.
     */
    @NotNull
    public List<String> generateStatements() {
        OracleDataSource dataSource = schema.getDataSource();
        String jobName = OracleSchedulerManager.literal(OracleSchedulerManager.qualifiedName(dataSource, schema.getName(), name));
        if (original == null) {
            List<String> args = new ArrayList<>();
            args.add("job_name => " + jobName); //$NON-NLS-1$
            args.add("job_type => " + OracleSchedulerManager.literal(jobType)); //$NON-NLS-1$
            args.add("job_action => " + OracleSchedulerManager.literal(jobAction)); //$NON-NLS-1$
            if (startDate != null) {
                args.add("start_date => " + timestamp(startDate)); //$NON-NLS-1$
            }
            if (repeatInterval != null) {
                args.add("repeat_interval => " + OracleSchedulerManager.literal(repeatInterval)); //$NON-NLS-1$
            }
            if (endDate != null) {
                args.add("end_date => " + timestamp(endDate)); //$NON-NLS-1$
            }
            args.add("enabled => " + bool(enabled)); //$NON-NLS-1$
            args.add("auto_drop => " + bool(autoDrop)); //$NON-NLS-1$
            if (comments != null) {
                args.add("comments => " + OracleSchedulerManager.literal(comments)); //$NON-NLS-1$
            }
            return List.of("BEGIN\n  DBMS_SCHEDULER.CREATE_JOB(\n    " + String.join(",\n    ", args) + ");\nEND;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        List<String> calls = new ArrayList<>();
        if (isActionEditable() && (!jobType.equals(original.jobType) || !jobAction.equals(original.jobAction))) {
            if (!jobType.equals(original.jobType)) {
                calls.add(setAttribute(jobName, "job_type", OracleSchedulerManager.literal(jobType))); //$NON-NLS-1$
            }
            calls.add(setAttribute(jobName, "job_action", OracleSchedulerManager.literal(jobAction))); //$NON-NLS-1$
        }
        if (isScheduleEditable()) {
            addDateChange(calls, jobName, "start_date", original.startDate, startDate); //$NON-NLS-1$
            if (!Objects.equals(repeatInterval, original.repeatInterval)) {
                calls.add(repeatInterval == null
                    ? setAttributeNull(jobName, "repeat_interval") //$NON-NLS-1$
                    : setAttribute(jobName, "repeat_interval", OracleSchedulerManager.literal(repeatInterval))); //$NON-NLS-1$
            }
            addDateChange(calls, jobName, "end_date", original.endDate, endDate); //$NON-NLS-1$
        }
        if (autoDrop != original.autoDrop) {
            calls.add(setAttribute(jobName, "auto_drop", bool(autoDrop))); //$NON-NLS-1$
        }
        if (!Objects.equals(comments, original.comments)) {
            calls.add(comments == null
                ? setAttributeNull(jobName, "comments") //$NON-NLS-1$
                : setAttribute(jobName, "comments", OracleSchedulerManager.literal(comments))); //$NON-NLS-1$
        }
        if (enabled != original.enabled) {
            calls.add(enabled
                ? "DBMS_SCHEDULER.ENABLE(name => " + jobName + ")" //$NON-NLS-1$ //$NON-NLS-2$
                : "DBMS_SCHEDULER.DISABLE(name => " + jobName + ", force => FALSE)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (calls.isEmpty()) {
            return List.of();
        }
        return List.of("BEGIN\n  " + String.join(";\n  ", calls) + ";\nEND;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static void addDateChange(@NotNull List<String> calls, @NotNull String jobName, @NotNull String attribute, @Nullable String oldValue, @Nullable String newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            calls.add(newValue == null ? setAttributeNull(jobName, attribute) : setAttribute(jobName, attribute, timestamp(newValue)));
        }
    }

    @NotNull
    private static String setAttribute(@NotNull String jobName, @NotNull String attribute, @NotNull String value) {
        return "DBMS_SCHEDULER.SET_ATTRIBUTE(name => " + jobName + ", attribute => '" + attribute + "', value => " + value + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @NotNull
    private static String setAttributeNull(@NotNull String jobName, @NotNull String attribute) {
        return "DBMS_SCHEDULER.SET_ATTRIBUTE_NULL(name => " + jobName + ", attribute => '" + attribute + "')"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @NotNull
    private static String timestamp(@NotNull String value) {
        return "TO_TIMESTAMP_TZ(" + OracleSchedulerManager.literal(value) + ", '" + OracleSchedulerManager.DATE_FORMAT + "')"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @NotNull
    private static String bool(boolean value) {
        return value ? "TRUE" : "FALSE"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
