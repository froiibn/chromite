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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionParameter;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tools whose work is diagnostic SQL ({@link DiagnosticScript}): health, performance, lock, routine and permission views.
 *
 * 설계:
 * <ul>
 *     <li>문장은 모두 읽기 전용 보호 안에서 실행한다({@link AgentSql.Mode#READ_ONLY}).</li>
 *     <li>한 도구의 문장이 여럿이면 하나가 실패해도(권한 없음, 확장 없음, 버전 차이) 나머지는 실행하고, 실패한 문장 자리에 오류와 필요 권한을 넣는다.
 *     "권한이 되는 검사만 하고 안 되는 것은 이유를 말한다"는 시나리오 요구(docs/chromite/ai-agent.md "플레이북")를 도구 수준에서 지키기 위해서다.
 *     PostgreSQL은 트랜잭션 안의 오류가 트랜잭션 전체를 멈추므로 문장마다 저장점을 두고 실패하면 저장점으로 되돌린다.</li>
 *     <li>모든 문장이 실패하면 오류로 끝낸다(감사 기록에 ERROR로 남게).</li>
 *     <li>해석이 필요한 결과(블로킹 트리, 교착 상태 XML, 계획 XML, 줄 번호)는 {@link DiagnosticPostProcessor}가 다듬는다.</li>
 *     <li>SQL 자원은 도구 ID의 파일이다. 합친 도구는 인수로 자원을 고른다: {@code getTopQueries}의 {@code source=queryStore}는
 *     {@code getQueryStoreTopQueries.sql}(26.1.0 전에는 따로 있던 도구). {@code getJobHistory}는 26.1.0부터 SQL Server에서 채팅에 보내지 않는다
 *     ({@code getAgentJobHistory}가 맡음). 그 SQL Server 블록은 직접 부르는 호출(MCP, 저장된 대화)을 위해 26.2.0까지 둔다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-08 쓸 수 있는 DB 종류 = SQL 자원에 블록이 있는 종류({@link #supports}, 요청 크기를 줄이는 도구 고르기)
 * - 2026-11-15 getQueryStoreTopQueries를 getTopQueries(source=queryStore)에 합침, getJobHistory의 SQL Server 부분은 getAgentJobHistory로,
 *   listLocks의 session은 killSession과 같은 글 형식(Oracle sid,serial#도 받음, {@link #normalizeSession})
 *   (도구 정리, docs/chromite/ai-agent.md "별칭")
 */
public class DiagnosticSqlTool extends AgentTool {

    public static final String PARAM_DATABASE = "database"; //$NON-NLS-1$
    private static final String TOP_QUERIES = "getTopQueries"; //$NON-NLS-1$
    private static final String QUERY_STORE_TOP_QUERIES = "getQueryStoreTopQueries"; //$NON-NLS-1$
    private static final String SOURCE_CACHE = "cache"; //$NON-NLS-1$
    private static final String SOURCE_QUERY_STORE = "queryStore"; //$NON-NLS-1$
    private static final String JOB_HISTORY = "getJobHistory"; //$NON-NLS-1$
    private static final String PARAM_SESSION = "session"; //$NON-NLS-1$

    @NotNull
    @Override
    protected JsonElement call(
        @NotNull AIToolCallContext context,
        @NotNull String toolId,
        @NotNull Map<String, Object> args
    ) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        AgentSql.connect(context.getMonitor(), container);
        DbFamily family = DbFamily.of(container);
        String scriptName = scriptName(toolId, args);
        DiagnosticScript script = DiagnosticScript.load(scriptName);
        if (script == null) {
            throw unknownTool(toolId);
        }
        DiagnosticScript.Block block = script.getBlock(family);
        if (block == null) {
            String name = scriptName.equals(toolId) ? toolId : toolId + " with source=" + SOURCE_QUERY_STORE;
            throw new DBException(name + " is not available for " + family.getDisplayName() + " (supported: " +
                script.getFamilies().stream().map(DbFamily::getDisplayName).collect(Collectors.joining(", ")) +
                "). Query the server's system views with runReadOnlySql instead.");
        }
        normalizeSession(family, args);
        AgentLimits limits = AgentLimits.of(args);
        String database = AgentArgs.getString(args, PARAM_DATABASE);
        JsonObject result = AgentSql.run(context.getMonitor(), container, database, AgentSql.Mode.READ_ONLY,
            (connection, runner) -> {
                JsonObject json = runBlock(connection, runner, context.getTool(), block, args, limits);
                DiagnosticPostProcessor.processInSession(scriptName, connection, runner, this, context, block, args, limits, json);
                return json;
            });
        JsonObject output = new JsonObject();
        output.addProperty("connection", container.getName());
        output.addProperty("family", family.getId());
        for (Map.Entry<String, JsonElement> e : result.entrySet()) {
            output.add(e.getKey(), e.getValue());
        }
        if (!block.notes().isEmpty()) {
            JsonArray notes = new JsonArray();
            block.notes().forEach(notes::add);
            output.add("notes", notes);
        }
        return DiagnosticPostProcessor.process(scriptName, context, family, args, output);
    }

    /**
     * The session filter of listLocks: one format with killSession (a string, Oracle {@code sid,serial#}); the lock views key
     * Oracle sessions by sid alone.
     */
    private static void normalizeSession(@NotNull DbFamily family, @NotNull Map<String, Object> args) throws AIToolArgumentException {
        String session = AgentArgs.getString(args, PARAM_SESSION);
        if (session == null) {
            return;
        }
        String id = session.trim();
        if (family == DbFamily.ORACLE && id.contains(",")) {
            id = id.substring(0, id.indexOf(',')).trim();
        }
        if (!id.matches("\\d{1,10}")) { //$NON-NLS-1$
            throw new AIToolArgumentException("Argument 'session' must be a session id from listSessions" +
                (family == DbFamily.ORACLE ? " (Oracle: sid or sid,serial#)" : ""));
        }
        args.put(PARAM_SESSION, id);
    }

    /**
     * The SQL resource of a call: the tool's own, or the one its arguments pick (getTopQueries source=queryStore).
     */
    @NotNull
    private static String scriptName(@NotNull String toolId, @NotNull Map<String, Object> args) throws AIToolArgumentException {
        if (!toolId.equals(TOP_QUERIES)) {
            return toolId;
        }
        String source = AgentArgs.getString(args, "source");
        if (source == null || source.trim().equalsIgnoreCase(SOURCE_CACHE)) {
            return toolId;
        }
        if (source.trim().equalsIgnoreCase(SOURCE_QUERY_STORE)) {
            return QUERY_STORE_TOP_QUERIES;
        }
        throw new AIToolArgumentException("Argument 'source' must be " + SOURCE_CACHE + " or " + SOURCE_QUERY_STORE);
    }

    // The families with a block in the tool's SQL resource: the same rule call() applies. getJobHistory keeps a SQL Server
    // block for direct calls until 26.2.0, but the chat sends getAgentJobHistory there
    @Override
    public boolean supports(@NotNull AIFunctionDescriptor tool, @NotNull DbFamily family) {
        if (tool.getId().equals(JOB_HISTORY) && family == DbFamily.SQLSERVER) {
            return false;
        }
        try {
            DiagnosticScript script = DiagnosticScript.load(tool.getId());
            return script != null && script.getBlock(family) != null;
        } catch (DBException e) {
            return true;
        }
    }

    /**
     * Runs every statement of a block; a failed statement becomes {@code {"error":..., "requires":[...]}}.
     *
     * @throws DBException when every statement failed
     */
    @NotNull
    JsonObject runBlock(
        @NotNull Connection connection,
        @NotNull AgentSql.Runner runner,
        @NotNull AIFunctionDescriptor tool,
        @NotNull DiagnosticScript.Block block,
        @NotNull Map<String, Object> args,
        @NotNull AgentLimits limits
    ) throws DBException {
        JsonObject result = new JsonObject();
        List<String> errors = new ArrayList<>();
        boolean savepoints = block.family() == DbFamily.POSTGRESQL;
        for (DiagnosticScript.Statement statement : block.statements()) {
            if (runner.getMonitor().isCanceled()) {
                throw new DBException("Canceled");
            }
            AgentLimits statementLimits = new AgentLimits(
                statement.limit() != null && args.get(AgentLimits.PARAM_LIMIT) == null ? statement.limit() : limits.maxRows(),
                statement.maxCellChars() != null ? Math.max(statement.maxCellChars(), limits.maxCellChars()) : limits.maxCellChars(),
                limits.maxBytes()
            );
            List<Object> values = bind(tool, statement.parameters(), args, statementLimits);
            Savepoint savepoint = null;
            try {
                if (savepoints && !connection.getAutoCommit()) {
                    savepoint = connection.setSavepoint();
                }
                JsonObject table = runner.query(connection, statement.sql(), values, statementLimits, true);
                if (block.family() == DbFamily.ORACLE) {
                    // Oracle upper-cases unquoted aliases; the post-processors and the other families use lower case
                    AgentJson.lowerCaseColumns(table);
                }
                result.add(statement.name(), table);
                if (savepoint != null) {
                    connection.releaseSavepoint(savepoint);
                }
            } catch (SQLException e) {
                if (savepoint != null) {
                    try {
                        connection.rollback(savepoint);
                    } catch (SQLException ignore) {
                        // the transaction is gone; the next statements report their own errors
                    }
                }
                errors.add(statement.name() + ": " + e.getMessage());
                JsonObject error = AgentJson.error(e.getMessage(), null);
                if (!block.requires().isEmpty()) {
                    JsonArray requires = new JsonArray();
                    block.requires().forEach(requires::add);
                    error.add("requires", requires);
                }
                result.add(statement.name(), error);
            }
        }
        if (!errors.isEmpty() && errors.size() == block.statements().size()) {
            throw new DBException(String.join("; ", errors) +
                (block.requires().isEmpty() ? "" : " (requires: " + String.join(", ", block.requires()) + ")"));
        }
        return result;
    }

    /**
     * {@link #runBlock} for follow-up queries (dependency graph levels): a failure gives an empty result.
     */
    @NotNull
    JsonObject runBlockQuietly(
        @NotNull Connection connection,
        @NotNull AgentSql.Runner runner,
        @NotNull AIToolCallContext context,
        @NotNull DiagnosticScript.Block block,
        @NotNull Map<String, Object> args,
        @NotNull AgentLimits limits
    ) {
        try {
            return runBlock(connection, runner, context.getTool(), block, args, limits);
        } catch (DBException e) {
            return new JsonObject();
        }
    }

    @NotNull
    static List<Object> bind(
        @NotNull AIFunctionDescriptor tool,
        @NotNull List<String> names,
        @NotNull Map<String, Object> args,
        @NotNull AgentLimits limits
    ) {
        List<Object> values = new ArrayList<>(names.size());
        for (String name : names) {
            if (name.equals(AgentLimits.PARAM_LIMIT)) {
                values.add((long) limits.maxRows());
                continue;
            }
            AIFunctionParameter parameter = tool.getParameter(name);
            Object raw = args.get(name);
            if ((raw == null || raw.toString().isBlank()) && parameter != null) {
                raw = parameter.getDefaultValue();
            }
            if (raw == null || raw.toString().isBlank()) {
                boolean numeric = parameter != null && (parameter.getType().equals("integer") || parameter.getType().equals("number")
                    || parameter.getType().equals("boolean"));
                values.add(numeric ? new AgentSql.NullValue(java.sql.Types.BIGINT) : null);
            } else if (parameter != null && (parameter.getType().equals("integer") || parameter.getType().equals("number"))) {
                try {
                    values.add((long) Double.parseDouble(raw.toString().trim()));
                } catch (NumberFormatException e) {
                    values.add(null);
                }
            } else if (parameter != null && parameter.getType().equals("boolean")) {
                values.add(Boolean.parseBoolean(raw.toString().trim()) ? 1L : 0L);
            } else {
                values.add(raw.toString().trim());
            }
        }
        return values;
    }

    @Nullable
    static String firstString(@NotNull JsonObject row, @NotNull String... names) {
        for (String name : names) {
            JsonElement value = row.get(name);
            if (value != null && !value.isJsonNull()) {
                return value.getAsString();
            }
        }
        return null;
    }
}
