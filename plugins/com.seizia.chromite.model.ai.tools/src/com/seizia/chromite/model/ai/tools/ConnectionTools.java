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
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceFolder;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionPurpose;
import com.seizia.chromite.model.ai.agent.AIAgentServices;
import com.seizia.chromite.model.ai.agent.AIEditorBridge;
import com.seizia.chromite.model.ai.agent.AIPendingTransaction;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;
import com.seizia.chromite.model.ai.agent.AIToolCatalog;
import com.seizia.chromite.model.ai.agent.AIToolConfirmationAnswer;
import com.seizia.chromite.model.ai.agent.AIToolConnections;
import com.seizia.chromite.model.ai.agent.AIToolDecision;
import com.seizia.chromite.model.ai.agent.AIToolPolicy;
import com.seizia.chromite.model.ai.agent.AIToolTransactionGuard;
import com.seizia.chromite.model.ai.agent.AIToolWrites;
import com.seizia.chromite.model.ai.tools.internal.AIToolsMessages;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.runtime.jobs.IdleTransactionMonitor;

import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Connection tools: list, details without secrets, connect/disconnect, test, current database, transactions, policy, editor.
 *
 * 설계:
 * <ul>
 *     <li>{@code getConnectionInfo}는 연결 설정 중 비밀이 아닌 칸만 고른다(허용 목록). 인증 속성·비밀번호·SSH 키·프록시 비밀번호는 넣지 않는다
 *     (docs/chromite/ai-agent.md "보안"). 네트워크 처리기는 종류와 켜짐 여부만 낸다.</li>
 *     <li>트랜잭션 안전(사용자 규칙): {@code disconnect}는 이 연결의 모든 실행 문맥(쿼리 창은 상태 줄과 같은 규칙
 *     {@link QMUtils#getTransactionState}, 에이전트 문맥은 {@link AgentSql}이 기록한 것)을, {@code setCurrentDatabase}·{@code commitTransaction}·
 *     {@code rollbackTransaction}은 에이전트의 열린 트랜잭션을 실행기에 알려 Commit / Roll back / Cancel을 묻게 한다({@link AIToolTransactionGuard}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-07 확인 대화상자의 열린 트랜잭션 소유자·설명을 AIToolsMessages로 옮김(화면 언어를 따름)
 * - 2026-11-08 쿼리 창 트랜잭션을 상태 줄과 같은 규칙(DB의 답)으로 봄, getTransactionStatus에 확인할 수 없는 트랜잭션
 *   ({@code otherUnverifiedTransactions}), 끝내기는 공용 경로 (docs/chromite/ai-agent.md "트랜잭션 안전")
 */
public class ConnectionTools extends AgentTool implements AIToolTransactionGuard {

    @NotNull
    @Override
    protected JsonElement call(
        @NotNull AIToolCallContext context,
        @NotNull String toolId,
        @NotNull Map<String, Object> args
    ) throws DBException {
        return switch (toolId) {
            case "listConnections" -> listConnections(args);
            case "getConnectionInfo" -> describe(requireConnection(context), true);
            case "connect" -> connect(context);
            case "disconnect" -> disconnect(context);
            case "testConnection" -> testConnection(context);
            case "getCurrentDatabase" -> currentDatabase(context);
            case "setCurrentDatabase" -> setCurrentDatabase(context, args);
            case "getTransactionStatus" -> transactionStatus(context);
            case "commitTransaction", "rollbackTransaction" -> transactionStatus(context);
            case "getToolPolicy" -> toolPolicy(context);
            case "openInEditor" -> openInEditor(context, args);
            default -> throw unknownTool(toolId);
        };
    }

    @NotNull
    private static JsonElement listConnections(@NotNull Map<String, Object> args) {
        String filter = AgentArgs.getString(args, "filter");
        JsonArray list = new JsonArray();
        for (DBPDataSourceContainer container : AIToolConnections.getAll()) {
            if (filter != null) {
                String text = (container.getName() + " " + container.getDriver().getName() + " " +
                    (container.getFolder() == null ? "" : container.getFolder().getFolderPath())).toLowerCase(Locale.ROOT);
                if (!text.contains(filter.toLowerCase(Locale.ROOT))) {
                    continue;
                }
            }
            list.add(describe(container, false));
        }
        JsonObject result = new JsonObject();
        result.add("connections", list);
        result.addProperty("count", list.size());
        return result;
    }

    @NotNull
    static JsonObject describe(@NotNull DBPDataSourceContainer container, boolean details) {
        DBPConnectionConfiguration cfg = container.getConnectionConfiguration();
        JsonObject json = new JsonObject();
        json.addProperty("id", container.getId());
        json.addProperty("name", container.getName());
        DBPDataSourceFolder folder = container.getFolder();
        if (folder != null) {
            json.addProperty("folder", folder.getFolderPath());
        }
        json.addProperty("driver", container.getDriver().getName());
        json.addProperty("family", container.isConnected() ? DbFamily.of(container).getId() : container.getDriver().getProviderId());
        json.addProperty("connectionType", cfg.getConnectionType().getName());
        json.addProperty("production", AIToolPolicy.isProduction(container));
        json.addProperty("readOnly", container.isConnectionReadOnly());
        String color = cfg.getConnectionColor();
        if (color != null && !color.isBlank()) {
            json.addProperty("color", color);
        }
        json.addProperty("connected", container.isConnected());
        if (!details) {
            return json;
        }
        json.addProperty("host", cfg.getHostName());
        json.addProperty("port", cfg.getHostPort());
        json.addProperty("database", cfg.getDatabaseName());
        json.addProperty("server", cfg.getServerName());
        json.addProperty("user", cfg.getUserName());
        json.addProperty("authModel", cfg.getAuthModelId());
        json.addProperty("defaultAutoCommit", container.isDefaultAutoCommit());
        if (container.getDescription() != null) {
            json.addProperty("description", container.getDescription());
        }
        JsonArray handlers = new JsonArray();
        for (DBWHandlerConfiguration handler : cfg.getHandlers()) {
            if (handler.isEnabled()) {
                handlers.add(handler.getId() + " (" + handler.getType().name().toLowerCase(Locale.ROOT) + ")");
            }
        }
        json.add("networkHandlers", handlers);
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource != null) {
            json.addProperty("serverProduct", dataSource.getInfo().getDatabaseProductName());
            json.addProperty("serverVersion", dataSource.getInfo().getDatabaseProductVersion());
            json.addProperty("driverVersion", dataSource.getInfo().getDriverVersion());
        }
        return json;
    }

    @NotNull
    private static JsonElement connect(@NotNull AIToolCallContext context) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        long start = System.currentTimeMillis();
        AgentSql.connect(context.getMonitor(), container);
        JsonObject json = describe(container, true);
        json.addProperty("connectMs", System.currentTimeMillis() - start);
        return json;
    }

    @NotNull
    private static JsonElement disconnect(@NotNull AIToolCallContext context) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        AgentSql.closeContexts(container);
        boolean done = !container.isConnected() || container.disconnect(context.getMonitor());
        JsonObject json = new JsonObject();
        json.addProperty("disconnected", done && !container.isConnected());
        return json;
    }

    @NotNull
    private static JsonElement testConnection(@NotNull AIToolCallContext context) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        long start = System.currentTimeMillis();
        String version = AgentSql.run(context.getMonitor(), container, null, AgentSql.Mode.READ_ONLY, (connection, runner) -> {
            DatabaseMetaData meta = connection.getMetaData();
            return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        });
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.addProperty("roundTripMs", System.currentTimeMillis() - start);
        json.addProperty("server", version);
        return json;
    }

    @NotNull
    private static JsonElement currentDatabase(@NotNull AIToolCallContext context) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        DBPDataSource dataSource = AgentSql.connect(context.getMonitor(), container);
        JsonObject json = new JsonObject();
        String[] agent = AgentSql.run(context.getMonitor(), container, null, AgentSql.Mode.READ_ONLY, (connection, runner) ->
            new String[] {connection.getCatalog(), safeSchema(connection)});
        json.addProperty("agentDatabase", agent[0]);
        json.addProperty("agentSchema", agent[1]);
        DBCExecutionContext main = DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false);
        DBCExecutionContextDefaults<?, ?> defaults = main == null ? null : main.getContextDefaults();
        if (defaults != null) {
            json.addProperty("connectionDatabase", defaults.getDefaultCatalog() == null ? null : defaults.getDefaultCatalog().getName());
            json.addProperty("connectionSchema", defaults.getDefaultSchema() == null ? null : defaults.getDefaultSchema().getName());
        }
        return json;
    }

    @Nullable
    private static String safeSchema(@NotNull java.sql.Connection connection) {
        try {
            return connection.getSchema();
        } catch (Throwable e) {
            return null;
        }
    }

    @NotNull
    private static JsonElement setCurrentDatabase(@NotNull AIToolCallContext context, @NotNull Map<String, Object> args) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        String database = AgentArgs.getString(args, "database");
        String schema = AgentArgs.getString(args, "schema");
        AgentSql.setAgentDefaults(container, database, schema);
        try {
            return currentDatabase(context);
        } catch (DBException e) {
            AgentSql.setAgentDefaults(container, null, null);
            throw e;
        }
    }

    @NotNull
    private static JsonElement transactionStatus(@NotNull AIToolCallContext context) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        JsonObject json = new JsonObject();
        List<String> agent = AgentSql.getPendingTransactions(container);
        json.addProperty("agentTransactionOpen", !agent.isEmpty());
        JsonArray agentList = new JsonArray();
        agent.forEach(agentList::add);
        json.add("agentTransactions", agentList);
        json.addProperty("defaultAutoCommit", container.isDefaultAutoCommit());
        // Same rule as the query window's status line: the database holds something for it or it was begun explicitly;
        // "unverified" when the database could not be asked (treated as open)
        JsonArray user = new JsonArray();
        JsonArray unverified = new JsonArray();
        for (DBCExecutionContext ctx : userContexts(container)) {
            QMTransactionState state = QMUtils.getTransactionState(ctx);
            if (state.isUnknown()) {
                unverified.add(ctx.getContextName());
            } else if (state.isOpen()) {
                user.add(ctx.getContextName());
            }
        }
        json.add("otherOpenTransactions", user);
        json.add("otherUnverifiedTransactions", unverified);
        return json;
    }

    // The application's own execution contexts of a connection (query windows, navigator), not the agent's
    @NotNull
    private static List<DBCExecutionContext> userContexts(@NotNull DBPDataSourceContainer container) {
        List<DBCExecutionContext> result = new ArrayList<>();
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null) {
            return result;
        }
        for (DBSInstance instance : dataSource.getAvailableInstances()) {
            for (DBCExecutionContext ctx : instance.getAllContexts()) {
                if (!AgentSql.isAgentContext(ctx)) {
                    result.add(ctx);
                }
            }
        }
        return result;
    }

    @NotNull
    private static JsonElement toolPolicy(@NotNull AIToolCallContext context) {
        DBPDataSourceContainer container = context.getConnection();
        JsonArray tools = new JsonArray();
        for (AIFunctionDescriptor tool : AIToolCatalog.getTools(AIFunctionPurpose.ALL)) {
            JsonObject t = new JsonObject();
            t.addProperty("tool", tool.getId());
            t.addProperty("access", tool.getAccess().name());
            if (container != null && tool.getAccess().isWrite() && container.isConnectionReadOnly()
                && tool.getWrites() == AIToolWrites.DATABASE) {
                t.addProperty("permission", "READ_ONLY");
                t.addProperty("reason", "connection is read-only");
            } else {
                AIToolDecision decision = AIToolPolicy.getInstance().resolve(container, tool, tool.getAccess());
                t.addProperty("permission", decision.permission().name());
                t.addProperty("reason", decision.reason());
            }
            tools.add(t);
        }
        JsonObject json = new JsonObject();
        if (container != null) {
            json.addProperty("connection", container.getName());
            json.addProperty("production", AIToolPolicy.isProduction(container));
            json.addProperty("readOnly", container.isConnectionReadOnly());
        }
        json.addProperty("confirmationUiAvailable", AIAgentServices.getConfirmationHandler() != null);
        json.add("tools", tools);
        return json;
    }

    @NotNull
    private static JsonElement openInEditor(@NotNull AIToolCallContext context, @NotNull Map<String, Object> args) throws DBException {
        AIEditorBridge bridge = AIAgentServices.getEditorBridge();
        if (bridge == null) {
            throw new DBException("No editor is available (the application runs without its user interface)");
        }
        bridge.openSqlEditor(context.getConnection(), AgentArgs.require(args, "sql"), AgentArgs.getString(args, "title"));
        JsonObject json = new JsonObject();
        json.addProperty("opened", true);
        return json;
    }

    // ------------------------------------------------------------------ transaction safety

    @NotNull
    @Override
    public List<AIPendingTransaction> findPendingTransactions(
        @NotNull AIToolCallContext context,
        @NotNull Map<String, Object> arguments
    ) {
        DBPDataSourceContainer container = context.getConnection();
        String toolId = context.getTool().getId();
        List<AIPendingTransaction> result = new ArrayList<>();
        if (container == null) {
            return result;
        }
        switch (toolId) {
            case "disconnect" -> {
                for (String database : AgentSql.getPendingTransactionDatabases(container)) {
                    result.add(AgentSql.pendingAgentTransaction(database, null));
                }
                for (DBCExecutionContext ctx : userContexts(container)) {
                    if (QMUtils.getTransactionState(ctx).isOpen()) {
                        result.add(new AIPendingTransaction(AIToolsMessages.pending_owner_query_window,
                            NLS.bind(AIToolsMessages.pending_query_window_work, ctx.getContextName()), true));
                    }
                }
            }
            case "setCurrentDatabase", "commitTransaction", "rollbackTransaction" -> {
                for (String database : AgentSql.getPendingTransactionDatabases(container)) {
                    result.add(AgentSql.pendingAgentTransaction(database, null));
                }
            }
            default -> {
            }
        }
        return result;
    }

    @Override
    public void resolvePendingTransactions(
        @NotNull AIToolCallContext context,
        @NotNull Map<String, Object> arguments,
        @NotNull AIToolConfirmationAnswer answer
    ) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        boolean commit = answer == AIToolConfirmationAnswer.COMMIT;
        AgentSql.endTransaction(context.getMonitor(), container, commit);
        if (context.getTool().getId().equals("disconnect")) {
            for (DBCExecutionContext ctx : userContexts(container)) {
                if (!QMUtils.getTransactionState(ctx).isOpen()) {
                    continue;
                }
                try (DBCSession session = ctx.openSession(context.getMonitor(), DBCExecutionPurpose.UTIL, "End transaction")) {
                    IdleTransactionMonitor.commitOrRollback(session, commit);
                }
            }
        }
    }
}
