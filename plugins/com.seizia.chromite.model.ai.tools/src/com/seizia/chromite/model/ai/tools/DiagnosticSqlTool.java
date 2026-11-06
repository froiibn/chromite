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
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public class DiagnosticSqlTool extends AgentTool {

    public static final String PARAM_DATABASE = "database"; //$NON-NLS-1$

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
        DiagnosticScript script = DiagnosticScript.load(toolId);
        if (script == null) {
            throw unknownTool(toolId);
        }
        DiagnosticScript.Block block = script.getBlock(family);
        if (block == null) {
            throw new DBException(toolId + " is not available for " + family.getDisplayName() + " (supported: " +
                script.getFamilies().stream().map(DbFamily::getDisplayName).collect(Collectors.joining(", ")) +
                "). Query the server's system views with runReadOnlySql instead.");
        }
        AgentLimits limits = AgentLimits.of(args);
        String database = AgentArgs.getString(args, PARAM_DATABASE);
        JsonObject result = AgentSql.run(context.getMonitor(), container, database, AgentSql.Mode.READ_ONLY,
            (connection, runner) -> {
                JsonObject json = runBlock(connection, runner, context.getTool(), block, args, limits);
                DiagnosticPostProcessor.processInSession(toolId, connection, runner, this, context, block, args, limits, json);
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
        return DiagnosticPostProcessor.process(toolId, context, family, args, output);
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
                result.add(statement.name(), runner.query(connection, statement.sql(), values, statementLimits, true));
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
                values.add(null);
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
