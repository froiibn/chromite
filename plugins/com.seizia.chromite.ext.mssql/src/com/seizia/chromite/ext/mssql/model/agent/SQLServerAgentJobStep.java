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
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.util.Objects;

/**
 * One step of a job definition (editable copy of a {@code msdb.dbo.sp_help_jobstep} row).
 *
 * 설계: 편집 창은 T-SQL 단계의 칸(데이터베이스, 명령, 성공·실패 동작, 재시도)만 고치지만, 단계가 바뀌면 단계를 모두 지우고 다시 만들므로
 * ({@link SQLServerAgentJobDefinition#generateAlterStatements}) 화면에 없는 값(프록시, 출력 파일, 플래그 등)도 읽은 그대로 들고 다닌다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public final class SQLServerAgentJobStep {

    public static final String SUBSYSTEM_TSQL = "TSQL"; //$NON-NLS-1$

    /** on_success_action / on_fail_action */
    public static final int ACTION_QUIT_SUCCESS = 1;
    public static final int ACTION_QUIT_FAILURE = 2;
    public static final int ACTION_NEXT_STEP = 3;
    public static final int ACTION_GO_TO_STEP = 4;

    private String name = ""; //$NON-NLS-1$
    private String subsystem = SUBSYSTEM_TSQL;
    private String command = ""; //$NON-NLS-1$
    @Nullable
    private String databaseName;
    private int onSuccessAction = ACTION_NEXT_STEP;
    private int onSuccessStepId;
    private int onFailAction = ACTION_QUIT_FAILURE;
    private int onFailStepId;
    private int retryAttempts;
    private int retryInterval;
    // Kept as read (not edited here)
    @Nullable
    private String databaseUserName;
    @Nullable
    private String server;
    private int flags;
    private int cmdexecSuccessCode;
    private int osRunPriority;
    @Nullable
    private String outputFileName;
    @Nullable
    private Integer proxyId;

    public SQLServerAgentJobStep() {
    }

    @NotNull
    static SQLServerAgentJobStep read(@NotNull ResultSet row) {
        SQLServerAgentJobStep step = new SQLServerAgentJobStep();
        step.name = CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "step_name")); //$NON-NLS-1$
        step.subsystem = CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "subsystem")); //$NON-NLS-1$
        step.command = CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "command")); //$NON-NLS-1$
        step.databaseName = JDBCUtils.safeGetString(row, "database_name"); //$NON-NLS-1$
        step.onSuccessAction = JDBCUtils.safeGetInt(row, "on_success_action"); //$NON-NLS-1$
        step.onSuccessStepId = JDBCUtils.safeGetInt(row, "on_success_step_id"); //$NON-NLS-1$
        step.onFailAction = JDBCUtils.safeGetInt(row, "on_fail_action"); //$NON-NLS-1$
        step.onFailStepId = JDBCUtils.safeGetInt(row, "on_fail_step_id"); //$NON-NLS-1$
        step.retryAttempts = JDBCUtils.safeGetInt(row, "retry_attempts"); //$NON-NLS-1$
        step.retryInterval = JDBCUtils.safeGetInt(row, "retry_interval"); //$NON-NLS-1$
        step.databaseUserName = JDBCUtils.safeGetString(row, "database_user_name"); //$NON-NLS-1$
        step.server = JDBCUtils.safeGetString(row, "server"); //$NON-NLS-1$
        step.flags = JDBCUtils.safeGetInt(row, "flags"); //$NON-NLS-1$
        step.cmdexecSuccessCode = JDBCUtils.safeGetInt(row, "cmdexec_success_code"); //$NON-NLS-1$
        step.osRunPriority = JDBCUtils.safeGetInt(row, "os_run_priority"); //$NON-NLS-1$
        step.outputFileName = JDBCUtils.safeGetString(row, "output_file_name"); //$NON-NLS-1$
        step.proxyId = JDBCUtils.safeGetInteger(row, "proxy_id"); //$NON-NLS-1$
        return step;
    }

    @NotNull
    public SQLServerAgentJobStep copy() {
        SQLServerAgentJobStep copy = new SQLServerAgentJobStep();
        copy.name = name;
        copy.subsystem = subsystem;
        copy.command = command;
        copy.databaseName = databaseName;
        copy.onSuccessAction = onSuccessAction;
        copy.onSuccessStepId = onSuccessStepId;
        copy.onFailAction = onFailAction;
        copy.onFailStepId = onFailStepId;
        copy.retryAttempts = retryAttempts;
        copy.retryInterval = retryInterval;
        copy.databaseUserName = databaseUserName;
        copy.server = server;
        copy.flags = flags;
        copy.cmdexecSuccessCode = cmdexecSuccessCode;
        copy.osRunPriority = osRunPriority;
        copy.outputFileName = outputFileName;
        copy.proxyId = proxyId;
        return copy;
    }

    /**
     * This step as the last step of a job: "go to the next step" becomes quitting with success (on success) or with
     * failure (on failure).
     */
    @NotNull
    SQLServerAgentJobStep asLastStep() {
        SQLServerAgentJobStep last = copy();
        if (last.onSuccessAction == ACTION_NEXT_STEP) {
            last.onSuccessAction = ACTION_QUIT_SUCCESS;
        }
        if (last.onFailAction == ACTION_NEXT_STEP) {
            last.onFailAction = ACTION_QUIT_FAILURE;
        }
        return last;
    }

    /**
     * {@code sp_add_jobstep} of this step at a position.
     *
     * @param jobParameter how the job is named: {@code job_name} (a new job) or {@code job_id}
     */
    @NotNull
    String toAddStatement(@NotNull String jobParameter, @NotNull String jobValue, int stepId) {
        SQLServerAgentSql.Call call = new SQLServerAgentSql.Call("sp_add_jobstep") //$NON-NLS-1$
            .arg(jobParameter, jobValue)
            .arg("step_name", name) //$NON-NLS-1$
            .arg("step_id", stepId) //$NON-NLS-1$
            .arg("subsystem", subsystem); //$NON-NLS-1$
        if (isTransactSql()) {
            call.arg("database_name", databaseName); //$NON-NLS-1$
            if (databaseUserName != null) {
                call.arg("database_user_name", databaseUserName); //$NON-NLS-1$
            }
        }
        if (server != null) {
            call.arg("server", server); //$NON-NLS-1$
        }
        call.arg("on_success_action", onSuccessAction) //$NON-NLS-1$
            .arg("on_success_step_id", onSuccessAction == ACTION_GO_TO_STEP ? onSuccessStepId : 0) //$NON-NLS-1$
            .arg("on_fail_action", onFailAction) //$NON-NLS-1$
            .arg("on_fail_step_id", onFailAction == ACTION_GO_TO_STEP ? onFailStepId : 0) //$NON-NLS-1$
            .arg("retry_attempts", retryAttempts) //$NON-NLS-1$
            .arg("retry_interval", retryInterval) //$NON-NLS-1$
            .arg("os_run_priority", osRunPriority) //$NON-NLS-1$
            .arg("cmdexec_success_code", cmdexecSuccessCode) //$NON-NLS-1$
            .arg("flags", flags); //$NON-NLS-1$
        if (outputFileName != null) {
            call.arg("output_file_name", outputFileName); //$NON-NLS-1$
        }
        if (proxyId != null) {
            call.arg("proxy_id", proxyId); //$NON-NLS-1$
        }
        return call.arg("command", command).toSql(); //$NON-NLS-1$
    }

    public boolean isTransactSql() {
        return SUBSYSTEM_TSQL.equalsIgnoreCase(subsystem);
    }

    @NotNull
    public String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    @NotNull
    public String getSubsystem() {
        return subsystem;
    }

    public void setSubsystem(@NotNull String subsystem) {
        this.subsystem = subsystem;
    }

    @NotNull
    public String getCommand() {
        return command;
    }

    public void setCommand(@NotNull String command) {
        this.command = command;
    }

    @Nullable
    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(@Nullable String databaseName) {
        this.databaseName = databaseName;
    }

    public int getOnSuccessAction() {
        return onSuccessAction;
    }

    public void setOnSuccessAction(int onSuccessAction) {
        this.onSuccessAction = onSuccessAction;
    }

    public int getOnSuccessStepId() {
        return onSuccessStepId;
    }

    public void setOnSuccessStepId(int onSuccessStepId) {
        this.onSuccessStepId = onSuccessStepId;
    }

    public int getOnFailAction() {
        return onFailAction;
    }

    public void setOnFailAction(int onFailAction) {
        this.onFailAction = onFailAction;
    }

    public int getOnFailStepId() {
        return onFailStepId;
    }

    public void setOnFailStepId(int onFailStepId) {
        this.onFailStepId = onFailStepId;
    }

    public int getRetryAttempts() {
        return retryAttempts;
    }

    public void setRetryAttempts(int retryAttempts) {
        this.retryAttempts = retryAttempts;
    }

    public int getRetryInterval() {
        return retryInterval;
    }

    public void setRetryInterval(int retryInterval) {
        this.retryInterval = retryInterval;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof SQLServerAgentJobStep other)) {
            return false;
        }
        return name.equals(other.name) && subsystem.equals(other.subsystem) && command.equals(other.command)
            && Objects.equals(databaseName, other.databaseName)
            && onSuccessAction == other.onSuccessAction && onSuccessStepId == other.onSuccessStepId
            && onFailAction == other.onFailAction && onFailStepId == other.onFailStepId
            && retryAttempts == other.retryAttempts && retryInterval == other.retryInterval
            && Objects.equals(databaseUserName, other.databaseUserName) && Objects.equals(server, other.server)
            && flags == other.flags && cmdexecSuccessCode == other.cmdexecSuccessCode && osRunPriority == other.osRunPriority
            && Objects.equals(outputFileName, other.outputFileName) && Objects.equals(proxyId, other.proxyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, command);
    }
}
