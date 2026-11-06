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
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.DBPStatefulObject;
import com.seizia.chromite.model.DBPUniqueObject;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectState;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.util.Date;
import java.util.Map;

/**
 * A SQL Server Agent job, as one row of {@code msdb.dbo.sp_help_job}: what the job list, the Job Activity Monitor and
 * the Properties window show.
 *
 * 설계: 목록 한 행만 담고, 단계·일정·알림 같은 전체 정의는 속성 창이나 스크립트를 만들 때 {@link SQLServerAgentJobDefinition}으로 따로 읽는다.
 * {@link DBPScriptObject}이므로 Object Explorer의 "스크립팅 &gt; CREATE 대상"과 "DDL 보기"가 그 정의로 만든 생성 스크립트를 보여 준다
 * (SSMS "Script Job as &gt; CREATE To").
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public class SQLServerAgentJob implements DBSObject, DBPUniqueObject, DBPStatefulObject, DBPScriptObject {

    /** sp_help_job current_execution_status */
    public static final int STATUS_EXECUTING = 1;
    public static final int STATUS_IDLE = 4;

    private final SQLServerAgent agent;
    private final String jobId;
    private final String name;
    private final boolean enabled;
    @Nullable
    private final String description;
    @Nullable
    private final String category;
    @Nullable
    private final String owner;
    @Nullable
    private final Date dateCreated;
    @Nullable
    private final Date dateModified;
    @Nullable
    private final Date lastRunDate;
    private final int lastRunOutcome;
    @Nullable
    private final Date nextRunDate;
    private final int executionStatus;
    @Nullable
    private final String currentStep;
    private final boolean hasSchedule;
    private final boolean hasStep;

    SQLServerAgentJob(@NotNull SQLServerAgent agent, @NotNull ResultSet row) {
        this.agent = agent;
        this.jobId = CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "job_id")); //$NON-NLS-1$
        this.name = CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "name")); //$NON-NLS-1$
        this.enabled = JDBCUtils.safeGetInt(row, "enabled") != 0; //$NON-NLS-1$
        this.description = JDBCUtils.safeGetString(row, "description"); //$NON-NLS-1$
        this.category = JDBCUtils.safeGetString(row, "category"); //$NON-NLS-1$
        this.owner = JDBCUtils.safeGetString(row, "owner"); //$NON-NLS-1$
        this.dateCreated = JDBCUtils.safeGetTimestamp(row, "date_created"); //$NON-NLS-1$
        this.dateModified = JDBCUtils.safeGetTimestamp(row, "date_modified"); //$NON-NLS-1$
        this.lastRunDate = SQLServerAgentSql.toTimestamp(
            JDBCUtils.safeGetInt(row, "last_run_date"), JDBCUtils.safeGetInt(row, "last_run_time")); //$NON-NLS-1$ //$NON-NLS-2$
        this.lastRunOutcome = this.lastRunDate == null ? -1 : JDBCUtils.safeGetInt(row, "last_run_outcome"); //$NON-NLS-1$
        this.nextRunDate = SQLServerAgentSql.toTimestamp(
            JDBCUtils.safeGetInt(row, "next_run_date"), JDBCUtils.safeGetInt(row, "next_run_time")); //$NON-NLS-1$ //$NON-NLS-2$
        this.executionStatus = JDBCUtils.safeGetInt(row, "current_execution_status"); //$NON-NLS-1$
        // "1 (step name)" while running, "0 (unknown)" when idle
        String step = JDBCUtils.safeGetString(row, "current_execution_step"); //$NON-NLS-1$
        this.currentStep = step == null || step.startsWith("0 ") ? null : step; //$NON-NLS-1$
        this.hasSchedule = JDBCUtils.safeGetInt(row, "has_schedule") != 0; //$NON-NLS-1$
        this.hasStep = JDBCUtils.safeGetInt(row, "has_step") != 0; //$NON-NLS-1$
    }

    @NotNull
    public SQLServerAgent getAgent() {
        return agent;
    }

    /**
     * The job's {@code uniqueidentifier}, which stays when it is renamed.
     */
    @NotNull
    public String getJobId() {
        return jobId;
    }

    @NotNull
    @Override
    public String getUniqueName() {
        return jobId;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Property(viewable = true, order = 2)
    public boolean isEnabled() {
        return enabled;
    }

    @NotNull
    @Property(viewable = true, order = 3)
    public String getStatus() {
        return switch (executionStatus) {
            case STATUS_EXECUTING -> SQLServerAgentMessages.job_status_executing;
            case 2 -> SQLServerAgentMessages.job_status_waiting_thread;
            case 3 -> SQLServerAgentMessages.job_status_between_retries;
            case 5 -> SQLServerAgentMessages.job_status_suspended;
            case 7 -> SQLServerAgentMessages.job_status_completing;
            default -> SQLServerAgentMessages.job_status_idle;
        };
    }

    /**
     * True while the job runs (anything but idle or suspended): Stop Job applies.
     */
    public boolean isRunning() {
        return executionStatus != STATUS_IDLE && executionStatus != 5 && executionStatus != 0;
    }

    @Nullable
    @Property(viewable = true, order = 4)
    public String getLastRunOutcome() {
        return lastRunOutcome < 0 ? null : getOutcomeLabel(lastRunOutcome);
    }

    /**
     * Code of the last outcome (0 failed, 1 succeeded, 3 canceled, 5 unknown), -1 if the job never ran.
     */
    public int getLastRunOutcomeCode() {
        return lastRunOutcome;
    }

    @Nullable
    @Property(viewable = true, order = 5)
    public Date getLastRunDate() {
        return lastRunDate;
    }

    @Nullable
    @Property(viewable = true, order = 6)
    public Date getNextRunDate() {
        return nextRunDate;
    }

    @Nullable
    @Property(viewable = true, order = 7)
    public String getCurrentStep() {
        return currentStep;
    }

    @Nullable
    @Property(viewable = true, order = 8)
    public String getCategory() {
        return category;
    }

    @Nullable
    @Property(viewable = true, order = 9)
    public String getOwner() {
        return owner;
    }

    @Property(viewable = true, order = 10)
    public boolean isScheduled() {
        return hasSchedule;
    }

    /**
     * False for a job without steps (it can't be started).
     */
    public boolean hasSteps() {
        return hasStep;
    }

    @Nullable
    @Override
    @Property(order = 11)
    public String getDescription() {
        return description;
    }

    @Nullable
    @Property(order = 12)
    public Date getDateCreated() {
        return dateCreated;
    }

    @Nullable
    @Property(order = 13)
    public Date getDateModified() {
        return dateModified;
    }

    /**
     * Label of a job or step outcome code ({@code run_status} / {@code last_run_outcome}).
     */
    @NotNull
    public static String getOutcomeLabel(int outcome) {
        return switch (outcome) {
            case 0 -> SQLServerAgentMessages.job_outcome_failed;
            case 1 -> SQLServerAgentMessages.job_outcome_succeeded;
            case 2 -> SQLServerAgentMessages.job_outcome_retry;
            case 3 -> SQLServerAgentMessages.job_outcome_canceled;
            case 4 -> SQLServerAgentMessages.job_outcome_in_progress;
            default -> SQLServerAgentMessages.job_outcome_unknown;
        };
    }

    @NotNull
    @Override
    public SQLServerAgent getParentObject() {
        return agent;
    }

    @NotNull
    @Override
    public SQLServerDataSource getDataSource() {
        return agent.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        return enabled ? DBSObjectState.NORMAL : DBSObjectState.INVALID;
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) {
    }

    @NotNull
    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        SQLServerAgentJobDefinition definition = SQLServerAgentJobDefinition.load(monitor, agent, jobId);
        return SQLServerAgentManager.formatScript(definition.generateCreateStatements());
    }

    @Override
    public String toString() {
        return name;
    }
}
