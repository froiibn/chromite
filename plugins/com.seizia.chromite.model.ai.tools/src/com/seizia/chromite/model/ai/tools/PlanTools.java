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
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.ai.agent.AIToolAccess;
import com.seizia.chromite.model.ai.agent.AIToolAccessClassifier;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;
import com.seizia.chromite.model.ai.agent.AIToolPreviewProvider;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.plan.DBCPlan;
import com.seizia.chromite.model.exec.plan.DBCPlanCostNode;
import com.seizia.chromite.model.exec.plan.DBCPlanNode;
import com.seizia.chromite.model.exec.plan.DBCQueryPlanner;
import com.seizia.chromite.model.exec.plan.DBCQueryPlannerConfiguration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Execution plan tools: estimated plan, actual plan with run-time statistics, plan comparison — as structured JSON.
 *
 * 설계(docs/chromite/ai-agent.md "설계 결정과 이유" — 계획은 DB 원래 형식에서 직접):
 * <ul>
 *     <li>SQL Server: 예상은 {@code SET SHOWPLAN_XML ON}(실행하지 않음), 실제는 {@code SET STATISTICS XML, IO, TIME ON}으로 실행해
 *     계획 XML과 I/O·시간 메시지를 받는다. PostgreSQL: {@code EXPLAIN (FORMAT JSON, VERBOSE)}, 실제는 {@code ANALYZE, BUFFERS}를 더한다.
 *     MySQL: {@code EXPLAIN FORMAT=JSON}, 실제는 MariaDB {@code ANALYZE FORMAT=JSON}, MySQL {@code EXPLAIN ANALYZE}(글자 트리).
 *     Oracle: {@code EXPLAIN PLAN} + {@code DBMS_XPLAN.DISPLAY}와 PLAN_TABLE 행(롤백). 그 밖의 DB는 드라이버 계획기({@link DBCQueryPlanner}) 노드.</li>
 *     <li>실제 계획은 문장을 실행한다. 읽기라고 증명된 문장은 읽기 전용 보호 안에서 실행해 READ다. 아니면 WRITE로 올리고(확인 요청에 "실행 후 롤백"을 보임)
 *     항상 롤백되는 트랜잭션에서 잰다({@link AgentSql.Mode#ROLLBACK}: 에이전트 트랜잭션이 열려 있으면 거부, 그 일을 버리지 않기 위해).</li>
 *     <li>SQL Server SHOWPLAN·STATISTICS 설정은 세션 설정이라 끝나면 늘 끈다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public class PlanTools extends AgentTool implements AIToolAccessClassifier, AIToolPreviewProvider {

    private static final String SHOWPLAN_COLUMN = "Showplan"; //$NON-NLS-1$

    @NotNull
    @Override
    protected JsonElement call(
        @NotNull AIToolCallContext context,
        @NotNull String toolId,
        @NotNull Map<String, Object> args
    ) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        AgentSql.connect(context.getMonitor(), container);
        String database = AgentArgs.getString(args, "database");
        return switch (toolId) {
            case "explainQuery" -> plan(context, container, database, AgentArgs.require(args, "sql"), false);
            case "getActualPlan" -> plan(context, container, database, AgentArgs.require(args, "sql"), true);
            case "comparePlans" -> {
                boolean actual = AgentArgs.getBoolean(args, "actual", false);
                String sqlA = AgentArgs.require(args, "sqlA");
                String sqlB = AgentArgs.require(args, "sqlB");
                if (actual) {
                    QueryTools.requireReadOnly(container.getDataSource(), sqlA, "comparePlans with actual=true");
                    QueryTools.requireReadOnly(container.getDataSource(), sqlB, "comparePlans with actual=true");
                }
                JsonObject a = plan(context, container, database, sqlA, actual);
                JsonObject b = plan(context, container, database, sqlB, actual);
                JsonObject json = new JsonObject();
                JsonObject planA = a.getAsJsonObject("plan");
                JsonObject planB = b.getAsJsonObject("plan");
                json.add("diff", planA != null && planB != null ? PlanParser.compare(planA, planB) : new JsonObject());
                json.add("a", a);
                json.add("b", b);
                yield json;
            }
            default -> throw unknownTool(toolId);
        };
    }

    @NotNull
    private JsonObject plan(
        @NotNull AIToolCallContext context,
        @NotNull DBPDataSourceContainer container,
        @Nullable String database,
        @NotNull String rawSql,
        boolean actual
    ) throws DBException {
        String sql = AgentSqlClassifier.stripTrailingDelimiter(rawSql);
        DBPDataSource dataSource = container.getDataSource();
        // The statement is appended to EXPLAIN: a second statement after it must never run
        if (dataSource != null && QueryTools.splitScript(dataSource, sql).size() > 1) {
            throw new com.seizia.chromite.model.ai.agent.AIToolArgumentException("Pass exactly one statement");
        }
        boolean readOnly = AgentSqlClassifier.classify(dataSource, sql).readOnly();
        DbFamily family = DbFamily.of(container);
        AgentSql.Mode mode = family == DbFamily.ORACLE || actual && !readOnly ? AgentSql.Mode.ROLLBACK : AgentSql.Mode.READ_ONLY;
        JsonObject result = new JsonObject();
        result.addProperty("family", family.getId());
        result.addProperty("kind", actual ? "actual" : "estimated");
        if (actual && !readOnly) {
            result.addProperty("note", "The statement was executed in a transaction that was rolled back.");
        }
        switch (family) {
            case SQLSERVER -> AgentSql.run(context.getMonitor(), container, database, mode, (connection, runner) -> {
                sqlServerPlan(connection, runner, sql, actual, result);
                return null;
            });
            case POSTGRESQL -> AgentSql.run(context.getMonitor(), container, database, mode, (connection, runner) -> {
                String options = actual ? "ANALYZE, BUFFERS, VERBOSE, FORMAT JSON" : "VERBOSE, FORMAT JSON";
                String json = singleValue(connection, runner, "EXPLAIN (" + options + ") " + sql); //$NON-NLS-1$ //$NON-NLS-2$
                result.add("plan", PlanParser.parsePostgres(json));
                return null;
            });
            case MYSQL, MARIADB -> AgentSql.run(context.getMonitor(), container, database, mode, (connection, runner) -> {
                if (!actual) {
                    result.add("plan", rawPlan(JsonParser.parseString(singleValue(connection, runner, "EXPLAIN FORMAT=JSON " + sql)))); //$NON-NLS-1$
                } else if (family == DbFamily.MARIADB) {
                    result.add("plan", rawPlan(JsonParser.parseString(singleValue(connection, runner, "ANALYZE FORMAT=JSON " + sql)))); //$NON-NLS-1$
                } else {
                    JsonObject text = new JsonObject();
                    text.addProperty("tree", singleValue(connection, runner, "EXPLAIN ANALYZE " + sql)); //$NON-NLS-1$
                    result.add("plan", text);
                }
                return null;
            });
            case ORACLE -> {
                if (actual) {
                    throw new DBException("Actual plans for Oracle are planned for phase 1b; use explainQuery (estimated plan)");
                }
                AgentSql.run(context.getMonitor(), container, database, AgentSql.Mode.ROLLBACK, (connection, runner) -> {
                    oraclePlan(connection, runner, sql, result);
                    return null;
                });
            }
            default -> genericPlan(context, container, sql, actual, result);
        }
        return result;
    }

    // ------------------------------------------------------------------ SQL Server

    private static void sqlServerPlan(
        @NotNull Connection connection,
        @NotNull AgentSql.Runner runner,
        @NotNull String sql,
        boolean actual,
        @NotNull JsonObject result
    ) throws SQLException, DBException {
        List<String> plans = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        JsonArray resultSets = new JsonArray();
        try (Statement statement = connection.createStatement()) {
            String[] on = actual
                ? new String[] {"SET STATISTICS XML ON", "SET STATISTICS IO ON", "SET STATISTICS TIME ON"} //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : new String[] {"SET SHOWPLAN_XML ON"}; //$NON-NLS-1$
            String[] off = actual
                ? new String[] {"SET STATISTICS XML OFF", "SET STATISTICS IO OFF", "SET STATISTICS TIME OFF"} //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : new String[] {"SET SHOWPLAN_XML OFF"}; //$NON-NLS-1$
            for (String s : on) {
                statement.execute(s);
            }
            runner.track(statement, true);
            try {
                boolean isResultSet = statement.execute(sql);
                while (true) {
                    AgentSql.collectWarnings(statement.getWarnings(), messages);
                    statement.clearWarnings();
                    if (isResultSet) {
                        try (ResultSet rs = statement.getResultSet()) {
                            ResultSetMetaData meta = rs.getMetaData();
                            if (meta.getColumnCount() == 1 && meta.getColumnLabel(1).contains(SHOWPLAN_COLUMN)) {
                                while (rs.next()) {
                                    plans.add(rs.getString(1));
                                }
                            } else {
                                // Rows of the measured query: count them, the model wants the plan, not the data
                                int rows = 0;
                                while (rs.next()) {
                                    rows++;
                                }
                                resultSets.add(rows);
                            }
                        }
                    } else if (statement.getUpdateCount() == -1) {
                        break;
                    }
                    isResultSet = statement.getMoreResults();
                }
            } finally {
                runner.track(statement, false);
                for (String s : off) {
                    try {
                        statement.execute(s);
                    } catch (SQLException e) {
                        messages.add("Can't reset " + s + ": " + e.getMessage());
                    }
                }
            }
        }
        if (plans.isEmpty()) {
            throw new DBException("The server returned no plan (SHOWPLAN permission is needed)");
        }
        JsonArray parsed = new JsonArray();
        for (String xml : plans) {
            parsed.add(PlanParser.parseSqlServer(xml));
        }
        result.add("plan", plans.size() == 1 ? parsed.get(0) : merge(parsed));
        if (actual) {
            JsonObject runtime = new JsonObject();
            runtime.add("resultRows", resultSets);
            JsonArray msg = new JsonArray();
            messages.forEach(msg::add);
            runtime.add("statisticsMessages", msg);
            result.add("runtime", runtime);
        }
    }

    // Several batches/plans (e.g. a statement list): one object with all statements, hotspots and findings
    @NotNull
    private static JsonObject merge(@NotNull JsonArray plans) {
        JsonObject merged = new JsonObject();
        JsonArray statements = new JsonArray();
        JsonArray hotspots = new JsonArray();
        JsonArray findings = new JsonArray();
        for (JsonElement p : plans) {
            JsonObject plan = p.getAsJsonObject();
            statements.addAll(plan.getAsJsonArray("statements"));
            hotspots.addAll(plan.getAsJsonArray("hotspots"));
            findings.addAll(plan.getAsJsonArray("findings"));
        }
        merged.add("statements", statements);
        merged.add("hotspots", hotspots);
        merged.add("findings", findings);
        return merged;
    }

    // ------------------------------------------------------------------ Oracle

    private static void oraclePlan(
        @NotNull Connection connection,
        @NotNull AgentSql.Runner runner,
        @NotNull String sql,
        @NotNull JsonObject result
    ) throws SQLException {
        String id = "chr_ai_" + UUID.randomUUID().toString().substring(0, 8); //$NON-NLS-1$
        try (Statement statement = connection.createStatement()) {
            runner.track(statement, true);
            try {
                statement.execute("EXPLAIN PLAN SET STATEMENT_ID = '" + id + "' FOR " + sql); //$NON-NLS-1$ //$NON-NLS-2$
            } finally {
                runner.track(statement, false);
            }
        }
        JsonObject rows = runner.query(connection,
            "SELECT id, parent_id, operation, options, object_owner, object_name, cost, cardinality, bytes, " + //$NON-NLS-1$
                "access_predicates, filter_predicates FROM plan_table WHERE statement_id = ? ORDER BY id", //$NON-NLS-1$
            List.of(id), new AgentLimits(1000, 4000, AgentLimits.DEFAULT_BYTES), true);
        JsonObject text = runner.query(connection, "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, ?, 'TYPICAL'))", //$NON-NLS-1$
            List.of(id), new AgentLimits(1000, 4000, AgentLimits.DEFAULT_BYTES), true);
        // Tree from id/parent_id in the common node shape
        Map<String, JsonObject> nodes = new LinkedHashMap<>();
        JsonObject root = null;
        for (JsonElement r : rows.getAsJsonArray("rows")) {
            JsonObject row = r.getAsJsonObject();
            JsonObject node = new JsonObject();
            String op = DiagnosticPostProcessor.str(row, "OPERATION");
            String options = DiagnosticPostProcessor.str(row, "OPTIONS");
            node.addProperty("op", options == null ? op : op + " " + options);
            String object = DiagnosticPostProcessor.str(row, "OBJECT_NAME");
            if (object != null) {
                node.addProperty("object", DiagnosticPostProcessor.str(row, "OBJECT_OWNER") + "." + object);
            }
            copy(row, "COST", node, "cost");
            copy(row, "CARDINALITY", node, "estRows");
            copy(row, "ACCESS_PREDICATES", node, "seekPredicate");
            copy(row, "FILTER_PREDICATES", node, "predicate");
            String nodeId = DiagnosticPostProcessor.str(row, "ID");
            nodes.put(nodeId, node);
            String parent = DiagnosticPostProcessor.str(row, "PARENT_ID");
            if (parent == null) {
                root = node;
            } else if (nodes.containsKey(parent)) {
                JsonObject p = nodes.get(parent);
                if (!p.has("children")) {
                    p.add("children", new JsonArray());
                }
                p.getAsJsonArray("children").add(node);
            }
        }
        JsonObject plan = new JsonObject();
        JsonArray statements = new JsonArray();
        JsonObject statement = new JsonObject();
        if (root != null) {
            statement.add("root", root);
            statement.add("cost", root.get("cost"));
        }
        statements.add(statement);
        plan.add("statements", statements);
        StringBuilder lines = new StringBuilder();
        for (JsonElement r : text.getAsJsonArray("rows")) {
            lines.append(DiagnosticPostProcessor.str(r.getAsJsonObject(), "PLAN_TABLE_OUTPUT")).append('\n');
        }
        plan.addProperty("text", lines.toString());
        plan.add("findings", new JsonArray());
        plan.add("hotspots", new JsonArray());
        result.add("plan", plan);
    }

    private static void copy(@NotNull JsonObject from, @NotNull String fromName, @NotNull JsonObject to, @NotNull String toName) {
        JsonElement value = from.get(fromName);
        if (value != null && !value.isJsonNull()) {
            to.add(toName, value);
        }
    }

    // ------------------------------------------------------------------ others

    // MySQL/MariaDB JSON plans differ too much to normalize safely; keep them, and list the full table scans
    @NotNull
    private static JsonObject rawPlan(@NotNull JsonElement json) {
        JsonObject plan = new JsonObject();
        plan.add("native", json);
        JsonArray findings = new JsonArray();
        collectMySqlFindings(json, findings);
        plan.add("findings", findings);
        return plan;
    }

    private static void collectMySqlFindings(@NotNull JsonElement json, @NotNull JsonArray findings) {
        if (json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject();
            if (object.has("table_name") && object.has("access_type")) {
                String access = object.get("access_type").getAsString();
                if (access.equals("ALL") || access.equals("index")) {
                    findings.add((access.equals("ALL") ? "Full table scan" : "Full index scan") + " on " +
                        object.get("table_name").getAsString() +
                        (object.has("rows_examined_per_scan") ? " (" + object.get("rows_examined_per_scan").getAsString() + " rows)" :
                            object.has("rows") ? " (" + object.get("rows").getAsString() + " rows)" : ""));
                }
            }
            if (object.has("using_filesort") && object.get("using_filesort").getAsBoolean()) {
                findings.add("Sort without an index (filesort)");
            }
            if (object.has("using_temporary_table") && object.get("using_temporary_table").getAsBoolean()) {
                findings.add("Temporary table");
            }
            object.entrySet().forEach(e -> collectMySqlFindings(e.getValue(), findings));
        } else if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(e -> collectMySqlFindings(e, findings));
        }
    }

    private static void genericPlan(
        @NotNull AIToolCallContext context,
        @NotNull DBPDataSourceContainer container,
        @NotNull String sql,
        boolean actual,
        @NotNull JsonObject result
    ) throws DBException {
        DBPDataSource dataSource = AgentSql.connect(context.getMonitor(), container);
        DBCQueryPlanner planner = DBUtils.getAdapter(DBCQueryPlanner.class, dataSource);
        if (planner == null) {
            throw new DBException("This database has no query planner in Chromite; use runReadOnlySql with the server's EXPLAIN syntax");
        }
        if (actual) {
            throw new DBException("Actual plans are available for SQL Server, PostgreSQL and MySQL/MariaDB");
        }
        DBCExecutionContext executionContext = DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false);
        DBCPlan plan;
        try (DBCSession session = executionContext.openSession(context.getMonitor(), DBCExecutionPurpose.UTIL, "Explain query")) {
            plan = planner.planQueryExecution(session, sql, new DBCQueryPlannerConfiguration());
        }
        JsonArray nodes = new JsonArray();
        for (DBCPlanNode node : plan.getPlanNodes(Map.of())) {
            nodes.add(genericNode(node));
        }
        JsonObject json = new JsonObject();
        JsonArray statements = new JsonArray();
        JsonObject statement = new JsonObject();
        statement.add("nodes", nodes);
        statements.add(statement);
        json.add("statements", statements);
        result.add("plan", json);
    }

    @NotNull
    private static JsonObject genericNode(@NotNull DBCPlanNode node) {
        JsonObject json = new JsonObject();
        json.addProperty("op", node.getNodeType());
        json.addProperty("object", node.getNodeName());
        json.addProperty("description", node.getNodeDescription());
        if (node instanceof DBCPlanCostNode cost) {
            if (cost.getNodeCost() != null) {
                json.addProperty("cost", cost.getNodeCost());
            }
            if (cost.getNodeRowCount() != null) {
                json.addProperty("estRows", cost.getNodeRowCount());
            }
        }
        if (node.getNested() != null && !node.getNested().isEmpty()) {
            JsonArray children = new JsonArray();
            for (DBCPlanNode child : node.getNested()) {
                children.add(genericNode(child));
            }
            json.add("children", children);
        }
        return json;
    }

    @NotNull
    private static String singleValue(@NotNull Connection connection, @NotNull AgentSql.Runner runner, @NotNull String sql)
        throws SQLException, DBException {
        try (Statement statement = connection.createStatement()) {
            runner.track(statement, true);
            try (ResultSet rs = statement.executeQuery(sql)) {
                StringBuilder text = new StringBuilder();
                while (rs.next()) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(rs.getString(1));
                }
                if (text.isEmpty()) {
                    throw new DBException("The server returned no plan");
                }
                return text.toString();
            } finally {
                runner.track(statement, false);
            }
        }
    }

    // ------------------------------------------------------------------ policy hooks

    @NotNull
    @Override
    public AIToolAccess classify(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments) {
        if (!context.getTool().getId().equals("getActualPlan")) {
            return AIToolAccess.READ;
        }
        String sql = AgentArgs.getString(arguments, "sql");
        DBPDataSourceContainer container = context.getConnection();
        DBPDataSource dataSource = container == null ? null : container.getDataSource();
        return sql == null || AgentSqlClassifier.classify(dataSource, AgentSqlClassifier.stripTrailingDelimiter(sql)).readOnly()
            ? AIToolAccess.READ
            : AgentSqlClassifier.access(dataSource, sql).max(AIToolAccess.WRITE);
    }

    @Nullable
    @Override
    public String getPreview(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments) {
        String sql = AgentArgs.getString(arguments, "sql");
        return sql == null ? null : "-- Runs the statement to measure its actual plan, then rolls it back\n" + sql;
    }
}
