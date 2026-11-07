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
package com.seizia.chromite.model.ai.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgent;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentJob;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentJobDefinition;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentJobStep;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentManager;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentSql;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;
import com.seizia.chromite.model.ai.agent.AIToolPreviewProvider;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQL Server Agent job tools: list, definition and run history (READ); start, stop, enable/disable (WRITE).
 *
 * 설계(docs/chromite/ai-agent.md "1b단계 도구"):
 * <ul>
 *     <li>Swing 에이전트 노드와 같은 모델({@code ext.mssql}의 {@code model.agent}: {@link SQLServerAgentManager},
 *     {@link SQLServerAgentJobDefinition})을 쓴다. 읽기는 msdb 도움말 프로시저라서 msdb 테이블 SELECT 권한이 없는 에이전트 역할
 *     (SQLAgentUserRole 등) 로그인도 된다(docs/chromite/swing-sqlserver-agent.md "설계 결정과 이유"). 모델의 읽기는 DBeaver 세션을 받으므로
 *     탐색기의 에이전트 노드처럼 메타데이터 세션에서 읽는다. 연결의 기본(Main) 문맥에서 읽으면 수동 커밋 연결(운영 종류)에서 프로시저 호출이
 *     사용자 문맥에 트랜잭션을 열어 앱을 닫을 때 "커밋하지 않은 트랜잭션"으로 잡혔다(2026-11-08 시험). 에이전트 문맥(JDBC 직접)은 모델의 읽기
 *     메서드가 받는 DBeaver 세션을 줄 수 없다. 탐색기 노드의 목록 캐시를 쓰지 않도록 호출마다
 *     {@link SQLServerAgent}를 새로 만든다.</li>
 *     <li>바꾸는 문은 모델의 문 생성기({@link SQLServerAgentManager#startJobStatement} 등)로 만들어 확인 요청에 그대로 보이고,
 *     {@link SQLServerAgentManager#execute}로 Swing 메뉴와 같이 격리된 연결에서 한 트랜잭션으로 실행한다. 작업 시작·중지는 에이전트 서비스에 보내는
 *     요청이라 열어 둔 트랜잭션으로 되돌릴 수 없으므로 수동 커밋 연결에서도 에이전트 문맥의 트랜잭션에 넣지 않는다(SQL Server 백업 도구와 같음).</li>
 *     <li>에이전트가 멈춰 있다고 알면 시작·중지를 서버에 보내지 않고 이유를 돌려준다. 이미 실행 중인 작업의 시작, 실행 중이 아닌 작업의 중지도 먼저
 *     거른다. 서버는 이 둘에도 "에이전트에 알릴 수 없음"과 같은 오류 번호(22022)를 내어 모델의 오류 분류가 "에이전트가 멈춤"으로 잘못 알리기 때문이다.
 *     사용 설정은 msdb만 바꾸므로 에이전트가 멈춰 있어도 된다.</li>
 *     <li>작업은 이름(대소문자 무시) 또는 job_id로 찾는다. 상태·결과 이름은 모델의 표시 이름(화면 언어)과 코드를 함께 낸다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (1b단계: SQL Server 에이전트 작업 도구)
 */
public class AgentJobTools extends AgentTool implements AIToolPreviewProvider {

    private static final String PARAM_JOB = "job"; //$NON-NLS-1$
    // run_status / last_run_outcome codes of msdb: 0 failed, 3 canceled
    private static final int OUTCOME_FAILED = 0;
    private static final int OUTCOME_CANCELED = 3;

    @NotNull
    @Override
    protected JsonElement call(
        @NotNull AIToolCallContext context,
        @NotNull String toolId,
        @NotNull Map<String, Object> args
    ) throws DBException {
        SQLServerAgent agent = openAgent(context);
        return switch (toolId) {
            case "listAgentJobs" -> listJobs(context, agent, AgentArgs.getString(args, "filter"));
            case "getAgentJob" -> jobDetail(context, agent, findJob(context, agent, AgentArgs.require(args, PARAM_JOB)));
            case "getAgentJobHistory" -> history(context, agent, args);
            case "startAgentJob", "stopAgentJob", "setAgentJobEnabled" -> change(context, agent, toolId, args);
            default -> throw unknownTool(toolId);
        };
    }

    @Override
    public boolean supports(@NotNull AIFunctionDescriptor tool, @NotNull DbFamily family) {
        return family == DbFamily.SQLSERVER;
    }

    @NotNull
    private static SQLServerAgent openAgent(@NotNull AIToolCallContext context) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        DBPDataSource dataSource = AgentSql.connect(context.getMonitor(), container);
        if (!(dataSource instanceof SQLServerDataSource sqlServer)) {
            throw new DBException("SQL Server Agent tools need a SQL Server connection; '" + container.getName() + "' is " +
                DbFamily.of(container).getDisplayName() + ". Use getJobHistory for the job history of other databases.");
        }
        SQLServerAgent agent = new SQLServerAgent(sqlServer);
        agent.readState(context.getMonitor());
        if (!agent.isAvailable()) {
            throw new DBException("This server has no SQL Server Agent (Azure SQL Database, Synapse or Babelfish)");
        }
        return agent;
    }

    @NotNull
    private static List<SQLServerAgentJob> readJobs(@NotNull AIToolCallContext context, @NotNull SQLServerAgent agent) throws DBException {
        try (JDBCSession session = DBUtils.openMetaSession(context.getMonitor(), agent.getDataSource(), "Read SQL Server Agent jobs")) { //$NON-NLS-1$
            return SQLServerAgentManager.readJobs(session, agent);
        }
    }

    @NotNull
    private static SQLServerAgentJob findJob(
        @NotNull AIToolCallContext context,
        @NotNull SQLServerAgent agent,
        @NotNull String nameOrId
    ) throws DBException {
        String key = nameOrId.trim();
        List<SQLServerAgentJob> jobs = readJobs(context, agent);
        for (SQLServerAgentJob job : jobs) {
            if (job.getJobId().equalsIgnoreCase(key)) {
                return job;
            }
        }
        for (SQLServerAgentJob job : jobs) {
            if (job.getName().equalsIgnoreCase(key)) {
                return job;
            }
        }
        throw new AIToolArgumentException("No SQL Server Agent job '" + key + "' visible to this login (listAgentJobs lists them)");
    }

    // ------------------------------------------------------------------ read

    @NotNull
    private static JsonObject listJobs(
        @NotNull AIToolCallContext context,
        @NotNull SQLServerAgent agent,
        @Nullable String filter
    ) throws DBException {
        String text = filter == null ? null : filter.trim().toLowerCase(Locale.ROOT);
        JsonArray jobs = new JsonArray();
        for (SQLServerAgentJob job : readJobs(context, agent)) {
            if (text != null && !job.getName().toLowerCase(Locale.ROOT).contains(text)
                && !CommonUtils.notEmpty(job.getCategory()).toLowerCase(Locale.ROOT).contains(text)) {
                continue;
            }
            jobs.add(jobJson(job));
        }
        JsonObject result = new JsonObject();
        result.add("agent", agentJson(agent));
        result.addProperty("jobCount", jobs.size());
        result.add("jobs", jobs);
        return result;
    }

    @NotNull
    private static JsonObject jobDetail(
        @NotNull AIToolCallContext context,
        @NotNull SQLServerAgent agent,
        @NotNull SQLServerAgentJob job
    ) throws DBException {
        SQLServerAgentJobDefinition definition = SQLServerAgentJobDefinition.load(context.getMonitor(), agent, job.getJobId());
        JsonObject result = jobJson(job);
        result.add("agent", agentJson(agent));
        result.addProperty("startStepId", definition.getStartStepId());
        JsonArray steps = new JsonArray();
        List<SQLServerAgentJobStep> definitionSteps = definition.getSteps();
        for (int i = 0; i < definitionSteps.size(); i++) {
            SQLServerAgentJobStep step = definitionSteps.get(i);
            JsonObject s = new JsonObject();
            // msdb keeps step ids 1..n in order (sp_add_jobstep / sp_delete_jobstep renumber them)
            s.addProperty("stepId", i + 1);
            s.addProperty("name", step.getName());
            s.addProperty("subsystem", step.getSubsystem());
            s.addProperty("database", step.getDatabaseName());
            s.addProperty("command", truncate(step.getCommand(), AgentLimits.DEFAULT_CELL_CHARS));
            s.addProperty("onSuccess", describeAction(step.getOnSuccessAction(), step.getOnSuccessStepId()));
            s.addProperty("onFailure", describeAction(step.getOnFailAction(), step.getOnFailStepId()));
            s.addProperty("retryAttempts", step.getRetryAttempts());
            s.addProperty("retryIntervalMinutes", step.getRetryInterval());
            steps.add(s);
        }
        result.add("steps", steps);
        JsonArray schedules = new JsonArray();
        try (JDBCSession session = DBUtils.openMetaSession(context.getMonitor(), agent.getDataSource(), "Read SQL Server Agent job schedules")) { //$NON-NLS-1$
            // The model's schedule rows keep the frequency fields; the procedure also describes them in words
            SQLServerAgentSql.readRows(session, "EXEC msdb.dbo.sp_help_jobschedule @job_id = ?, @include_description = 1", //$NON-NLS-1$
                new Object[] {job.getJobId()}, row -> {
                    JsonObject s = new JsonObject();
                    s.addProperty("name", JDBCUtils.safeGetString(row, "schedule_name")); //$NON-NLS-1$
                    s.addProperty("enabled", JDBCUtils.safeGetInt(row, "enabled") != 0); //$NON-NLS-1$
                    s.addProperty("description", JDBCUtils.safeGetString(row, "schedule_description")); //$NON-NLS-1$
                    // The description writes times as hhmmss numbers ("Every day at 30000" = 03:00:00)
                    s.addProperty("activeStartTime", SQLServerAgentSql.toLocalTime(JDBCUtils.safeGetInt(row, "active_start_time")).toString()); //$NON-NLS-1$
                    s.addProperty("activeEndTime", SQLServerAgentSql.toLocalTime(JDBCUtils.safeGetInt(row, "active_end_time")).toString()); //$NON-NLS-1$
                    addDate(s, "nextRun", SQLServerAgentSql.toTimestamp( //$NON-NLS-1$
                        JDBCUtils.safeGetInt(row, "next_run_date"), JDBCUtils.safeGetInt(row, "next_run_time"))); //$NON-NLS-1$ //$NON-NLS-2$
                    schedules.add(s);
                });
        }
        result.add("schedules", schedules);
        return result;
    }

    @NotNull
    private static JsonObject history(
        @NotNull AIToolCallContext context,
        @NotNull SQLServerAgent agent,
        @NotNull Map<String, Object> args
    ) throws DBException {
        String jobArg = AgentArgs.getString(args, PARAM_JOB);
        SQLServerAgentJob job = jobArg == null ? null : findJob(context, agent, jobArg);
        boolean failedOnly = AgentArgs.getBoolean(args, "failedOnly", false);
        AgentLimits limits = AgentLimits.of(args);
        List<SQLServerAgentManager.HistoryEntry> entries;
        try (JDBCSession session = DBUtils.openMetaSession(context.getMonitor(), agent.getDataSource(), "Read SQL Server Agent job history")) { //$NON-NLS-1$
            entries = SQLServerAgentManager.readHistory(session, job == null ? null : job.getJobId());
        }
        JsonArray rows = new JsonArray();
        int matching = 0;
        for (SQLServerAgentManager.HistoryEntry entry : entries) {
            if (failedOnly && entry.runStatus() != OUTCOME_FAILED && entry.runStatus() != OUTCOME_CANCELED) {
                continue;
            }
            matching++;
            if (rows.size() >= limits.maxRows()) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("job", entry.jobName());
            row.addProperty("stepId", entry.stepId());
            row.addProperty("stepName", entry.isJobOutcome() ? "(job outcome)" : entry.stepName());
            row.addProperty("outcome", SQLServerAgentJob.getOutcomeLabel(entry.runStatus()));
            row.addProperty("outcomeCode", entry.runStatus());
            addDate(row, "runTime", entry.runDate());
            row.addProperty("durationSeconds", entry.durationSeconds());
            row.addProperty("retriesAttempted", entry.retriesAttempted());
            if (entry.sqlMessageId() != 0) {
                row.addProperty("sqlMessageId", entry.sqlMessageId());
                row.addProperty("sqlSeverity", entry.sqlSeverity());
            }
            row.addProperty("message", truncate(entry.message(), 2000));
            rows.add(row);
        }
        JsonObject result = new JsonObject();
        if (job != null) {
            result.addProperty("job", job.getName());
        }
        result.addProperty("rowCount", rows.size());
        result.addProperty("hasMore", matching > rows.size());
        result.add("history", rows);
        result.addProperty("note", "stepId 0 rows are the outcome of a whole run; the step rows that follow belong to the same run. " +
            "msdb keeps a limited history per job (SQL Server Agent properties).");
        return result;
    }

    // ------------------------------------------------------------------ change

    @NotNull
    private static String statement(@NotNull String toolId, @NotNull SQLServerAgentJob job, @NotNull Map<String, Object> args)
        throws AIToolArgumentException {
        return switch (toolId) {
            case "startAgentJob" -> SQLServerAgentManager.startJobStatement(job, AgentArgs.getString(args, "step"));
            case "stopAgentJob" -> SQLServerAgentManager.stopJobStatement(job);
            case "setAgentJobEnabled" -> {
                if (AgentArgs.getString(args, "enabled") == null) {
                    throw new AIToolArgumentException("Argument 'enabled' is required (true or false)");
                }
                yield SQLServerAgentManager.enableJobStatement(job, AgentArgs.getBoolean(args, "enabled", true));
            }
            default -> throw new AIToolArgumentException("Unknown SQL Server Agent change: " + toolId);
        };
    }

    @NotNull
    private static JsonObject change(
        @NotNull AIToolCallContext context,
        @NotNull SQLServerAgent agent,
        @NotNull String toolId,
        @NotNull Map<String, Object> args
    ) throws DBException {
        SQLServerAgentJob job = findJob(context, agent, AgentArgs.require(args, PARAM_JOB));
        String statement = statement(toolId, job, args);
        if (!toolId.equals("setAgentJobEnabled")) {
            if (!agent.canRunJobs()) {
                throw new DBException("SQL Server Agent is not running on this server (" + agent.getServiceState().name() +
                    "): jobs can't be started or stopped until the SQL Server Agent service runs");
            }
            if (toolId.equals("startAgentJob") && job.isRunning()) {
                throw new DBException("Job '" + job.getName() + "' is already running (" + job.getStatus() + ", step " +
                    CommonUtils.notEmpty(job.getCurrentStep()) + ")");
            }
            if (toolId.equals("startAgentJob") && !job.hasSteps()) {
                throw new DBException("Job '" + job.getName() + "' has no steps and can't be started");
            }
            if (toolId.equals("stopAgentJob") && !job.isRunning()) {
                throw new DBException("Job '" + job.getName() + "' is not running (" + job.getStatus() + "); there is nothing to stop");
            }
        }
        List<String> messages = new ArrayList<>();
        SQLServerAgentManager.execute(context.getMonitor(), agent.getDataSource(), List.of(statement), messages::add);
        JsonObject result = new JsonObject();
        result.addProperty("job", job.getName());
        result.addProperty("jobId", job.getJobId());
        result.addProperty("statement", statement);
        result.addProperty("completed", true);
        if (toolId.equals("setAgentJobEnabled")) {
            result.addProperty("previouslyEnabled", job.isEnabled());
            result.addProperty("enabled", AgentArgs.getBoolean(args, "enabled", true));
        } else {
            result.addProperty("note", toolId.equals("startAgentJob")
                ? "The agent runs the job asynchronously: follow it with listAgentJobs (status) and getAgentJobHistory (outcome)."
                : "The agent stops the job asynchronously: listAgentJobs shows when it is idle.");
        }
        JsonArray serverMessages = new JsonArray();
        messages.forEach(serverMessages::add);
        result.add("messages", serverMessages);
        return result;
    }

    @Nullable
    @Override
    public String getPreview(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments) throws DBException {
        String toolId = context.getTool().getId();
        if (!toolId.equals("startAgentJob") && !toolId.equals("stopAgentJob") && !toolId.equals("setAgentJobEnabled")) {
            return null;
        }
        SQLServerAgent agent = openAgent(context);
        return statement(toolId, findJob(context, agent, AgentArgs.require(arguments, PARAM_JOB)), arguments);
    }

    // ------------------------------------------------------------------ JSON

    @NotNull
    private static JsonObject agentJson(@NotNull SQLServerAgent agent) {
        JsonObject json = new JsonObject();
        json.addProperty("serviceState", agent.getServiceState().name());
        json.addProperty("canRunJobs", agent.canRunJobs());
        return json;
    }

    @NotNull
    private static JsonObject jobJson(@NotNull SQLServerAgentJob job) {
        JsonObject json = new JsonObject();
        json.addProperty("name", job.getName());
        json.addProperty("jobId", job.getJobId());
        json.addProperty("enabled", job.isEnabled());
        json.addProperty("running", job.isRunning());
        json.addProperty("status", job.getStatus());
        if (job.getCurrentStep() != null) {
            json.addProperty("currentStep", job.getCurrentStep());
        }
        json.addProperty("lastRunOutcome", job.getLastRunOutcome());
        json.addProperty("lastRunOutcomeCode", job.getLastRunOutcomeCode());
        addDate(json, "lastRun", job.getLastRunDate());
        addDate(json, "nextRun", job.getNextRunDate());
        json.addProperty("scheduled", job.isScheduled());
        json.addProperty("hasSteps", job.hasSteps());
        json.addProperty("category", job.getCategory());
        json.addProperty("owner", job.getOwner());
        if (!CommonUtils.isEmpty(job.getDescription())) {
            json.addProperty("description", truncate(job.getDescription(), 1000));
        }
        return json;
    }

    // Wall clock of the server (msdb run_date/run_time), as the agent wrote it
    private static void addDate(@NotNull JsonObject json, @NotNull String name, @Nullable Date date) {
        json.addProperty(name, date == null ? null : date.toString());
    }

    @NotNull
    private static String describeAction(int action, int stepId) {
        return switch (action) {
            case SQLServerAgentJobStep.ACTION_QUIT_SUCCESS -> "quit reporting success";
            case SQLServerAgentJobStep.ACTION_QUIT_FAILURE -> "quit reporting failure";
            case SQLServerAgentJobStep.ACTION_NEXT_STEP -> "go to the next step";
            case SQLServerAgentJobStep.ACTION_GO_TO_STEP -> "go to step " + stepId;
            default -> "action " + action;
        };
    }

    @Nullable
    private static String truncate(@Nullable String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
