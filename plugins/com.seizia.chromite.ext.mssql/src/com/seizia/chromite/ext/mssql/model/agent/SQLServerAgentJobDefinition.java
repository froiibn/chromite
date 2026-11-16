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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The whole definition of a job (general settings, steps, schedules, notifications) as the Job Properties dialog edits
 * it, and the msdb procedure calls that create, change or drop it.
 *
 * 설계: 보안 편집기({@code SecurityDialect})처럼 "읽은 정의 → 고친 정의"의 차이를 문 목록으로 만든다. SSMS 작업 속성 창과 같은 프로시저를 쓴다:
 * <ul>
 *     <li>일반·알림: 바뀐 인자만 {@code sp_update_job}.</li>
 *     <li>단계: 하나라도 바뀌면 {@code sp_delete_jobstep @step_id = 0}(모든 단계)으로 지우고 순서대로 다시 만든 뒤 시작 단계를 정한다.
 *     단계 번호가 위치이므로 끼워 넣기·순서 바꾸기·지우기를 하나씩 맞추면 번호가 엇갈리기 쉽다. 작업 기록은 단계를 지워도 남는다.</li>
 *     <li>일정: 없어진 일정은 {@code sp_detach_schedule}(다른 작업이 쓰지 않으면 일정도 지움), 새 일정은 {@code sp_add_jobschedule},
 *     바뀐 일정은 {@code sp_update_schedule}.</li>
 *     <li>새 작업은 이 서버를 대상으로 {@code sp_add_jobserver}까지 한다(대상이 없으면 에이전트가 실행하지 않는다).</li>
 * </ul>
 * 새 작업은 이름으로, 기존 작업은 이름이 바뀌어도 그대로인 {@code @job_id}로 가리킨다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public final class SQLServerAgentJobDefinition {

    /** notify_level_* and delete_level */
    public static final int LEVEL_NEVER = 0;
    public static final int LEVEL_ON_SUCCESS = 1;
    public static final int LEVEL_ON_FAILURE = 2;
    public static final int LEVEL_ON_COMPLETION = 3;

    public static final String DEFAULT_CATEGORY = "[Uncategorized (Local)]"; //$NON-NLS-1$
    private static final String NO_OPERATOR = "(unknown)"; //$NON-NLS-1$

    /** null for a new job */
    @Nullable
    private String jobId;
    private String name = ""; //$NON-NLS-1$
    private boolean enabled = true;
    private String description = ""; //$NON-NLS-1$
    private String category = DEFAULT_CATEGORY;
    private String owner = ""; //$NON-NLS-1$
    private int startStepId = 1;
    private int notifyLevelEventlog = LEVEL_ON_FAILURE;
    private int notifyLevelEmail = LEVEL_NEVER;
    @Nullable
    private String notifyEmailOperator;
    private int deleteLevel = LEVEL_NEVER;
    private final List<SQLServerAgentJobStep> steps = new ArrayList<>();
    private final List<SQLServerAgentSchedule> schedules = new ArrayList<>();

    private SQLServerAgentJobDefinition() {
    }

    /**
     * A new job with the defaults of a new job (enabled, uncategorized, owned by the current login, event log on failure).
     */
    @NotNull
    public static SQLServerAgentJobDefinition newJob(@NotNull String owner) {
        SQLServerAgentJobDefinition definition = new SQLServerAgentJobDefinition();
        definition.owner = owner;
        return definition;
    }

    /**
     * Reads a job with its steps and schedules.
     */
    @NotNull
    public static SQLServerAgentJobDefinition load(@NotNull DBRProgressMonitor monitor, @NotNull SQLServerAgent agent, @NotNull String jobId) throws DBException {
        SQLServerAgentJobDefinition definition = new SQLServerAgentJobDefinition();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, agent.getDataSource(), "Read SQL Server Agent job")) { //$NON-NLS-1$
            boolean[] found = new boolean[1];
            SQLServerAgentSql.readRows(session, "EXEC msdb.dbo.sp_help_job @job_id = ?, @job_aspect = N'JOB'", new Object[] {jobId}, row -> { //$NON-NLS-1$
                found[0] = true;
                definition.jobId = JDBCUtils.safeGetString(row, "job_id"); //$NON-NLS-1$
                definition.name = CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "name")); //$NON-NLS-1$
                definition.enabled = JDBCUtils.safeGetInt(row, "enabled") != 0; //$NON-NLS-1$
                definition.description = CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "description")); //$NON-NLS-1$
                definition.category = CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "category")); //$NON-NLS-1$
                definition.owner = CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "owner")); //$NON-NLS-1$
                definition.startStepId = JDBCUtils.safeGetInt(row, "start_step_id"); //$NON-NLS-1$
                definition.notifyLevelEventlog = JDBCUtils.safeGetInt(row, "notify_level_eventlog"); //$NON-NLS-1$
                definition.notifyLevelEmail = JDBCUtils.safeGetInt(row, "notify_level_email"); //$NON-NLS-1$
                String operator = JDBCUtils.safeGetString(row, "notify_email_operator"); //$NON-NLS-1$
                definition.notifyEmailOperator = CommonUtils.isEmpty(operator) || NO_OPERATOR.equals(operator) ? null : operator;
                definition.deleteLevel = JDBCUtils.safeGetInt(row, "delete_level"); //$NON-NLS-1$
            });
            if (!found[0]) {
                throw new DBException("Job " + jobId + " not found"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            SQLServerAgentSql.readRows(session, "EXEC msdb.dbo.sp_help_jobstep @job_id = ?", new Object[] {jobId}, //$NON-NLS-1$
                row -> definition.steps.add(SQLServerAgentJobStep.read(row)));
            SQLServerAgentSql.readRows(session, "EXEC msdb.dbo.sp_help_jobschedule @job_id = ?", new Object[] {jobId}, //$NON-NLS-1$
                row -> definition.schedules.add(SQLServerAgentSchedule.read(row)));
        }
        return definition;
    }

    /**
     * A copy to edit; the original stays as read.
     */
    @NotNull
    public SQLServerAgentJobDefinition copy() {
        SQLServerAgentJobDefinition copy = new SQLServerAgentJobDefinition();
        copy.jobId = jobId;
        copy.name = name;
        copy.enabled = enabled;
        copy.description = description;
        copy.category = category;
        copy.owner = owner;
        copy.startStepId = startStepId;
        copy.notifyLevelEventlog = notifyLevelEventlog;
        copy.notifyLevelEmail = notifyLevelEmail;
        copy.notifyEmailOperator = notifyEmailOperator;
        copy.deleteLevel = deleteLevel;
        for (SQLServerAgentJobStep step : steps) {
            copy.steps.add(step.copy());
        }
        for (SQLServerAgentSchedule schedule : schedules) {
            copy.schedules.add(schedule.copy());
        }
        return copy;
    }

    //////////////////////////////////////////////////////////
    // Statements

    /**
     * Statements that create the job as it is now (New Job, Script Job as CREATE).
     */
    @NotNull
    public List<String> generateCreateStatements() {
        List<String> statements = new ArrayList<>();
        SQLServerAgentSql.Call addJob = new SQLServerAgentSql.Call("sp_add_job") //$NON-NLS-1$
            .arg("job_name", name) //$NON-NLS-1$
            .arg("enabled", enabled) //$NON-NLS-1$
            .arg("description", description) //$NON-NLS-1$
            .arg("category_name", category) //$NON-NLS-1$
            .arg("owner_login_name", owner) //$NON-NLS-1$
            .arg("notify_level_eventlog", notifyLevelEventlog) //$NON-NLS-1$
            .arg("notify_level_email", getEffectiveEmailLevel()) //$NON-NLS-1$
            .arg("delete_level", deleteLevel); //$NON-NLS-1$
        if (getEffectiveEmailLevel() != LEVEL_NEVER) {
            addJob.arg("notify_email_operator_name", notifyEmailOperator); //$NON-NLS-1$
        }
        statements.add(addJob.toSql());
        addSteps(statements, "job_name", name); //$NON-NLS-1$
        for (SQLServerAgentSchedule schedule : schedules) {
            statements.add(addScheduleStatement("job_name", name, schedule)); //$NON-NLS-1$
        }
        statements.add(new SQLServerAgentSql.Call("sp_add_jobserver") //$NON-NLS-1$
            .arg("job_name", name) //$NON-NLS-1$
            .arg("server_name", "(local)") //$NON-NLS-1$ //$NON-NLS-2$
            .toSql());
        return statements;
    }

    /**
     * Statements that change the job from the original definition to this one (empty if nothing changed).
     */
    @NotNull
    public List<String> generateAlterStatements(@NotNull SQLServerAgentJobDefinition original) {
        if (jobId == null) {
            return generateCreateStatements();
        }
        List<String> statements = new ArrayList<>();
        boolean stepsChanged = !steps.equals(original.steps);

        SQLServerAgentSql.Call update = new SQLServerAgentSql.Call("sp_update_job").arg("job_id", jobId); //$NON-NLS-1$ //$NON-NLS-2$
        if (!name.equals(original.name)) {
            update.arg("new_name", name); //$NON-NLS-1$
        }
        if (enabled != original.enabled) {
            update.arg("enabled", enabled); //$NON-NLS-1$
        }
        if (!description.equals(original.description)) {
            update.arg("description", description); //$NON-NLS-1$
        }
        if (!category.equals(original.category)) {
            update.arg("category_name", category); //$NON-NLS-1$
        }
        if (!owner.equals(original.owner)) {
            update.arg("owner_login_name", owner); //$NON-NLS-1$
        }
        if (notifyLevelEventlog != original.notifyLevelEventlog) {
            update.arg("notify_level_eventlog", notifyLevelEventlog); //$NON-NLS-1$
        }
        int emailLevel = getEffectiveEmailLevel();
        if (emailLevel != original.getEffectiveEmailLevel() || !Objects.equals(notifyEmailOperator, original.notifyEmailOperator)) {
            update.arg("notify_level_email", emailLevel); //$NON-NLS-1$
            // An empty name clears the operator
            update.arg("notify_email_operator_name", emailLevel == LEVEL_NEVER ? "" : notifyEmailOperator); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (deleteLevel != original.deleteLevel) {
            update.arg("delete_level", deleteLevel); //$NON-NLS-1$
        }
        if (!stepsChanged && startStepId != original.startStepId) {
            update.arg("start_step_id", startStepId); //$NON-NLS-1$
        }
        if (update.hasArguments(1)) {
            statements.add(update.toSql());
        }

        if (stepsChanged) {
            if (!original.steps.isEmpty()) {
                statements.add(new SQLServerAgentSql.Call("sp_delete_jobstep") //$NON-NLS-1$
                    .arg("job_id", jobId) //$NON-NLS-1$
                    .arg("step_id", 0) //$NON-NLS-1$
                    .toSql());
            }
            addSteps(statements, "job_id", jobId); //$NON-NLS-1$
        }

        for (SQLServerAgentSchedule old : original.schedules) {
            if (findSchedule(old.getScheduleId()) == null) {
                statements.add(new SQLServerAgentSql.Call("sp_detach_schedule") //$NON-NLS-1$
                    .arg("job_id", jobId) //$NON-NLS-1$
                    .arg("schedule_id", CommonUtils.toInt(old.getScheduleId())) //$NON-NLS-1$
                    .arg("delete_unused_schedule", true) //$NON-NLS-1$
                    .toSql());
            }
        }
        for (SQLServerAgentSchedule schedule : schedules) {
            if (schedule.getScheduleId() == null) {
                statements.add(addScheduleStatement("job_id", jobId, schedule)); //$NON-NLS-1$
                continue;
            }
            SQLServerAgentSchedule old = original.findSchedule(schedule.getScheduleId());
            if (old == null || old.equals(schedule)) {
                continue;
            }
            SQLServerAgentSql.Call call = new SQLServerAgentSql.Call("sp_update_schedule") //$NON-NLS-1$
                .arg("schedule_id", schedule.getScheduleId()); //$NON-NLS-1$
            if (!schedule.getName().equals(old.getName())) {
                call.arg("new_name", schedule.getName()); //$NON-NLS-1$
            }
            if (!schedule.sameFrequency(old)) {
                schedule.addFrequencyArguments(call);
            }
            statements.add(call.toSql());
        }
        return statements;
    }

    /**
     * Statement that drops the job (and its schedules no other job uses).
     */
    @NotNull
    public static String generateDropStatement(@NotNull String jobId) {
        return new SQLServerAgentSql.Call("sp_delete_job") //$NON-NLS-1$
            .arg("job_id", jobId) //$NON-NLS-1$
            .arg("delete_unused_schedule", true) //$NON-NLS-1$
            .toSql();
    }

    private void addSteps(@NotNull List<String> statements, @NotNull String jobParameter, @NotNull String jobValue) {
        for (int i = 0; i < steps.size(); i++) {
            SQLServerAgentJobStep step = steps.get(i);
            // The agent fails a job whose last step goes to the next step ("tried to run a non-existent step", seen
            // 2026-11-07): there the next step means quitting, as the job dialogs this one follows save it
            statements.add((i == steps.size() - 1 ? step.asLastStep() : step).toAddStatement(jobParameter, jobValue, i + 1));
        }
        if (!steps.isEmpty()) {
            statements.add(new SQLServerAgentSql.Call("sp_update_job") //$NON-NLS-1$
                .arg(jobParameter, jobValue)
                .arg("start_step_id", Math.max(1, Math.min(startStepId, steps.size()))) //$NON-NLS-1$
                .toSql());
        }
    }

    @NotNull
    private static String addScheduleStatement(@NotNull String jobParameter, @NotNull String jobValue, @NotNull SQLServerAgentSchedule schedule) {
        SQLServerAgentSql.Call call = new SQLServerAgentSql.Call("sp_add_jobschedule") //$NON-NLS-1$
            .arg(jobParameter, jobValue)
            .arg("name", schedule.getName()); //$NON-NLS-1$
        schedule.addFrequencyArguments(call);
        return call.toSql();
    }

    @Nullable
    private SQLServerAgentSchedule findSchedule(@Nullable Integer scheduleId) {
        if (scheduleId == null) {
            return null;
        }
        for (SQLServerAgentSchedule schedule : schedules) {
            if (scheduleId.equals(schedule.getScheduleId())) {
                return schedule;
            }
        }
        return null;
    }

    /**
     * The e-mail level that is actually set: never without an operator.
     */
    private int getEffectiveEmailLevel() {
        return CommonUtils.isEmpty(notifyEmailOperator) ? LEVEL_NEVER : notifyLevelEmail;
    }

    //////////////////////////////////////////////////////////
    // Properties

    @Nullable
    public String getJobId() {
        return jobId;
    }

    public boolean isNew() {
        return jobId == null;
    }

    @NotNull
    public String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @NotNull
    public String getDescription() {
        return description;
    }

    public void setDescription(@NotNull String description) {
        this.description = description;
    }

    @NotNull
    public String getCategory() {
        return category;
    }

    public void setCategory(@NotNull String category) {
        this.category = category;
    }

    @NotNull
    public String getOwner() {
        return owner;
    }

    public void setOwner(@NotNull String owner) {
        this.owner = owner;
    }

    /**
     * 1-based position of the first step.
     */
    public int getStartStepId() {
        return startStepId;
    }

    public void setStartStepId(int startStepId) {
        this.startStepId = startStepId;
    }

    public int getNotifyLevelEventlog() {
        return notifyLevelEventlog;
    }

    public void setNotifyLevelEventlog(int notifyLevelEventlog) {
        this.notifyLevelEventlog = notifyLevelEventlog;
    }

    public int getNotifyLevelEmail() {
        return notifyLevelEmail;
    }

    public void setNotifyLevelEmail(int notifyLevelEmail) {
        this.notifyLevelEmail = notifyLevelEmail;
    }

    @Nullable
    public String getNotifyEmailOperator() {
        return notifyEmailOperator;
    }

    public void setNotifyEmailOperator(@Nullable String notifyEmailOperator) {
        this.notifyEmailOperator = notifyEmailOperator;
    }

    public int getDeleteLevel() {
        return deleteLevel;
    }

    public void setDeleteLevel(int deleteLevel) {
        this.deleteLevel = deleteLevel;
    }

    /**
     * The steps in order (step id = position + 1); the list is edited in place.
     */
    @NotNull
    public List<SQLServerAgentJobStep> getSteps() {
        return steps;
    }

    /**
     * The schedules; the list is edited in place.
     */
    @NotNull
    public List<SQLServerAgentSchedule> getSchedules() {
        return schedules;
    }
}
