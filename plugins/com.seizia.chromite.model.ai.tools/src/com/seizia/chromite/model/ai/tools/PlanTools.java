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
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.agent.AIToolAccess;
import com.seizia.chromite.model.ai.agent.AIToolAccessClassifier;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Execution plan tools: estimated plan, actual plan with run-time statistics, plan comparison — as structured JSON.
 *
 * 설계(docs/chromite/ai-agent.md "설계 결정과 이유" — 계획은 DB 원래 형식에서 직접):
 * <ul>
 *     <li>SQL Server: 예상은 {@code SET SHOWPLAN_XML ON}(실행하지 않음), 실제는 {@code SET STATISTICS XML, IO, TIME ON}으로 실행해
 *     계획 XML과 I/O·시간 메시지를 받는다. PostgreSQL: {@code EXPLAIN (FORMAT JSON, VERBOSE)}, 실제는 {@code ANALYZE, BUFFERS}를 더한다.
 *     MySQL: {@code EXPLAIN FORMAT=JSON}, 실제는 MariaDB {@code ANALYZE FORMAT=JSON}, MySQL {@code EXPLAIN ANALYZE}(글자 트리).
 *     Oracle: {@code EXPLAIN PLAN} + {@code DBMS_XPLAN.DISPLAY}와 PLAN_TABLE 행(롤백), 실제는 {@code GATHER_PLAN_STATISTICS} 힌트로 실행한 커서의
 *     {@code V$SQL_PLAN_STATISTICS_ALL} 행과 {@code DBMS_XPLAN.DISPLAY_CURSOR('ALLSTATS LAST')}(Diagnostics/Tuning Pack 필요 없음).
 *     그 밖의 DB는 드라이버 계획기({@link DBCQueryPlanner}) 노드.</li>
 *     <li>실제 계획은 문장을 실행한다. 읽기라고 증명된 문장은 읽기 전용 보호 안에서 실행해 READ다. 아니면 WRITE로 올리고(확인 요청에 "실행 후 롤백"을 보임)
 *     항상 롤백되는 트랜잭션에서 잰다({@link AgentSql.Mode#ROLLBACK}: 에이전트 트랜잭션이 열려 있으면 거부, 그 일을 버리지 않기 위해).</li>
 *     <li>SQL Server SHOWPLAN·STATISTICS 설정은 세션 설정이라 끝나면 늘 끈다. Oracle은 세션 설정(statistics_level)을 바꾸지 않고 힌트를 쓴다.</li>
 *     <li>가상 인덱스({@code whatIfIndex}): PostgreSQL hypopg가 그 DB에 있을 때만 쓴다. 확장을 만들지 않고, 없으면 이유를 돌려준다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-08 Oracle 실제 계획, whatIfIndex(hypopg), Oracle 계획 노드를 {@link PlanParser#parseOracle}로 옮겨 예상·실제 계획이 함께 씀
 *   (docs/chromite/ai-agent.md "1b단계 도구")
 */
public class PlanTools extends AgentTool implements AIToolAccessClassifier, AIToolPreviewProvider {

    private static final String SHOWPLAN_COLUMN = "Showplan"; //$NON-NLS-1$
    private static final AgentLimits ORACLE_PLAN_LIMITS = new AgentLimits(1000, 4000, AgentLimits.DEFAULT_BYTES);
    // Leading comments, then the first DML keyword; a WITH query's first SELECT (the first query block)
    private static final Pattern ORACLE_FIRST_KEYWORD = Pattern.compile(
        "^(?:\\s|--[^\\n]*\\n|/\\*.*?\\*/)*(?:WITH\\b.*?)?\\b(SELECT|INSERT|UPDATE|DELETE|MERGE)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ORACLE_HINT = Pattern.compile("\\s*/\\*\\+(.*?)\\*/", Pattern.DOTALL); //$NON-NLS-1$
    private static final Pattern CREATE_INDEX = Pattern.compile("\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\b", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

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
            case "whatIfIndex" -> whatIfIndex(context, container, database, args);
            default -> throw unknownTool(toolId);
        };
    }

    @Override
    public boolean supports(@NotNull AIFunctionDescriptor tool, @NotNull DbFamily family) {
        // The plan tools fall back to the driver's planner; hypothetical indexes exist only in PostgreSQL's hypopg
        return !tool.getId().equals("whatIfIndex") || family == DbFamily.POSTGRESQL;
    }

    // ------------------------------------------------------------------ hypothetical index (PostgreSQL hypopg)

    /**
     * Estimated plan before and after a hypothetical index. hypopg keeps the index in the backend's memory only (no catalog
     * row, nothing on disk, invisible to other sessions), so this stays a READ tool and runs in the read-only transaction;
     * the index is dropped again before the call ends. The extension is never created here: without it the tool says why.
     */
    @NotNull
    private JsonObject whatIfIndex(
        @NotNull AIToolCallContext context,
        @NotNull DBPDataSourceContainer container,
        @Nullable String database,
        @NotNull Map<String, Object> args
    ) throws DBException {
        DbFamily family = DbFamily.of(container);
        String sql = AgentSqlClassifier.stripTrailingDelimiter(AgentArgs.require(args, "sql"));
        String index = AgentSqlClassifier.stripTrailingDelimiter(AgentArgs.require(args, "index"));
        JsonObject result = new JsonObject();
        result.addProperty("family", family.getId());
        if (family != DbFamily.POSTGRESQL) {
            result.addProperty("available", false);
            result.addProperty("reason", "Hypothetical indexes need PostgreSQL with the hypopg extension; " + family.getDisplayName() +
                " has none in Chromite. Compare explainQuery before and after a real index (createIndex, confirmed by the user) instead.");
            return result;
        }
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource != null && QueryTools.splitScript(dataSource, sql).size() > 1) {
            throw new AIToolArgumentException("Pass exactly one statement in 'sql'");
        }
        if (!CREATE_INDEX.matcher(index).lookingAt() || dataSource != null && QueryTools.splitScript(dataSource, index).size() > 1) {
            throw new AIToolArgumentException("Argument 'index' must be one CREATE INDEX statement");
        }
        return AgentSql.run(context.getMonitor(), container, database, AgentSql.Mode.READ_ONLY, (connection, runner) -> {
            String version = null;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT extversion FROM pg_extension WHERE extname = 'hypopg'")) { //$NON-NLS-1$
                if (rs.next()) {
                    version = rs.getString(1);
                }
            }
            if (version == null) {
                boolean onServer;
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT 1 FROM pg_available_extensions WHERE name = 'hypopg'")) { //$NON-NLS-1$
                    onServer = rs.next();
                }
                result.addProperty("available", false);
                result.addProperty("reason", onServer
                    ? "The hypopg extension is installed on the server but not created in this database. A user with CREATE privilege " +
                        "on the database can run CREATE EXTENSION hypopg; Chromite Agent does not create extensions. Until then compare " +
                        "explainQuery before and after a real index."
                    : "The hypopg extension is not installed on this PostgreSQL server (pg_available_extensions has no hypopg), so " +
                        "hypothetical indexes are not available. Compare explainQuery before and after a real index instead.");
                return result;
            }
            result.addProperty("available", true);
            result.addProperty("hypopgVersion", version);
            JsonObject before = PlanParser.parsePostgres(singleValue(connection, runner, "EXPLAIN (VERBOSE, FORMAT JSON) " + sql)); //$NON-NLS-1$
            long indexOid;
            String indexName;
            try (PreparedStatement statement = connection.prepareStatement("SELECT indexrelid, indexname FROM hypopg_create_index(?)")) { //$NON-NLS-1$
                statement.setString(1, index);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new DBException("hypopg did not create the hypothetical index");
                    }
                    indexOid = rs.getLong(1);
                    indexName = rs.getString(2);
                }
            }
            try {
                JsonObject after = PlanParser.parsePostgres(singleValue(connection, runner, "EXPLAIN (VERBOSE, FORMAT JSON) " + sql)); //$NON-NLS-1$
                JsonObject hypothetical = new JsonObject();
                hypothetical.addProperty("name", indexName);
                hypothetical.addProperty("definition", index);
                try (PreparedStatement statement = connection.prepareStatement("SELECT hypopg_relation_size(?)")) { //$NON-NLS-1$
                    statement.setLong(1, indexOid);
                    try (ResultSet rs = statement.executeQuery()) {
                        if (rs.next()) {
                            hypothetical.addProperty("estimatedSizeBytes", rs.getLong(1));
                        }
                    }
                }
                result.add("hypotheticalIndex", hypothetical);
                result.addProperty("indexUsed", after.toString().contains(indexName));
                result.add("diff", PlanParser.compare(before, after));
                result.add("before", before);
                result.add("after", after);
            } finally {
                try (PreparedStatement statement = connection.prepareStatement("SELECT hypopg_drop_index(?)")) { //$NON-NLS-1$
                    statement.setLong(1, indexOid);
                    statement.execute();
                }
            }
            return result;
        });
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
                    // The cursor statistics are read from V$ views, which a read-only transaction may read
                    AgentSql.run(context.getMonitor(), container, database, readOnly ? AgentSql.Mode.READ_ONLY : AgentSql.Mode.ROLLBACK,
                        (connection, runner) -> {
                            oracleActualPlan(connection, runner, sql, result);
                            return null;
                        });
                } else {
                    AgentSql.run(context.getMonitor(), container, database, AgentSql.Mode.ROLLBACK, (connection, runner) -> {
                        oraclePlan(connection, runner, sql, result);
                        return null;
                    });
                }
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
            List.of(id), ORACLE_PLAN_LIMITS, true);
        JsonObject text = runner.query(connection, "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, ?, 'TYPICAL'))", //$NON-NLS-1$
            List.of(id), ORACLE_PLAN_LIMITS, true);
        AgentJson.lowerCaseColumns(rows);
        result.add("plan", PlanParser.parseOracle(rows.getAsJsonArray("rows"), planText(text)));
    }

    /**
     * Oracle actual plan: runs the statement with the {@code GATHER_PLAN_STATISTICS} hint (row source statistics for this
     * cursor only, no session or system setting changes) and reads the last execution's statistics of its cursor.
     * The cursor is found by a marker comment in V$SQL, not by the session's previous statement: the driver may run its own
     * statements in between. Row source statistics, V$SQL and DBMS_XPLAN.DISPLAY_CURSOR need no Diagnostics or Tuning Pack.
     */
    private static void oracleActualPlan(
        @NotNull Connection connection,
        @NotNull AgentSql.Runner runner,
        @NotNull String sql,
        @NotNull JsonObject result
    ) throws SQLException, DBException {
        String marker = "chr_ai_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String hinted = oracleHint(sql, marker);
        JsonObject runtime = new JsonObject();
        long started = System.nanoTime();
        try (Statement statement = connection.createStatement()) {
            runner.track(statement, true);
            try {
                if (statement.execute(hinted)) {
                    // Fetch every row: the row source statistics are complete only when the cursor is exhausted
                    long rows = 0;
                    try (ResultSet rs = statement.getResultSet()) {
                        while (rs.next()) {
                            rows++;
                        }
                    }
                    runtime.addProperty("resultRows", rows);
                } else {
                    runtime.addProperty("updateCount", statement.getUpdateCount());
                }
            } finally {
                runner.track(statement, false);
            }
        }
        runtime.addProperty("elapsedMs", (System.nanoTime() - started) / 1_000_000);
        String sqlId;
        long child;
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT sql_id, child_number FROM v$sql WHERE sql_fulltext LIKE ? ORDER BY last_active_time DESC FETCH FIRST 1 ROWS ONLY")) { //$NON-NLS-1$
            // The marker is bound, so this query's own text does not contain it
            statement.setString(1, "%" + marker + "%"); //$NON-NLS-1$ //$NON-NLS-2$
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new DBException("The statement ran, but its cursor was not found in V$SQL (aged out of the shared pool)");
                }
                sqlId = rs.getString(1);
                child = rs.getLong(2);
            }
        } catch (SQLException e) {
            throw new DBException("The statement ran, but its run-time statistics can't be read: " + e.getMessage() +
                ". Reading them needs SELECT on V$SQL, V$SQL_PLAN_STATISTICS_ALL and V$SESSION: GRANT SELECT_CATALOG_ROLE (or SELECT ANY DICTIONARY) TO <user>", e);
        }
        JsonObject rows = runner.query(connection,
            "SELECT id, parent_id, operation, options, object_owner, object_name, cost, cardinality, " + //$NON-NLS-1$
                "last_starts, last_output_rows, last_elapsed_time, last_cr_buffer_gets, last_disk_reads, last_memory_used, " + //$NON-NLS-1$
                "last_tempseg_size, access_predicates, filter_predicates " + //$NON-NLS-1$
                "FROM v$sql_plan_statistics_all WHERE sql_id = ? AND child_number = ? ORDER BY id", //$NON-NLS-1$
            List.of(sqlId, child), ORACLE_PLAN_LIMITS, true);
        JsonObject text = runner.query(connection,
            "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(?, ?, 'ALLSTATS LAST'))", //$NON-NLS-1$
            List.of(sqlId, child), ORACLE_PLAN_LIMITS, true);
        AgentJson.lowerCaseColumns(rows);
        String planText = planText(text);
        // DISPLAY_CURSOR reports a missing privilege as plan text instead of an error
        if (planText.contains("User has no SELECT privilege")) { //$NON-NLS-1$
            throw new DBException(planText.strip() + " Reading actual plans needs GRANT SELECT_CATALOG_ROLE (or SELECT ANY DICTIONARY) TO <user>");
        }
        JsonObject plan = PlanParser.parseOracle(rows.getAsJsonArray("rows"), planText);
        plan.addProperty("sqlId", sqlId);
        plan.addProperty("childNumber", child);
        result.add("plan", plan);
        result.add("runtime", runtime);
    }

    /**
     * Puts the hint and the marker after the statement's first keyword. A hint is read only right after the keyword of a query
     * block; GATHER_PLAN_STATISTICS applies to the whole statement from any block, so the first SELECT of a WITH query does.
     */
    @NotNull
    static String oracleHint(@NotNull String sql, @NotNull String marker) throws AIToolArgumentException {
        Matcher keyword = ORACLE_FIRST_KEYWORD.matcher(sql);
        if (!keyword.find()) {
            throw new AIToolArgumentException("Oracle actual plans take a SELECT, INSERT, UPDATE, DELETE or MERGE statement (or a WITH query)");
        }
        int at = keyword.end();
        Matcher hint = ORACLE_HINT.matcher(sql).region(at, sql.length());
        if (hint.lookingAt()) {
            // Oracle reads only the first comment after the keyword as the hint: join the user's hint instead of hiding it
            return sql.substring(0, hint.start(1)) + " GATHER_PLAN_STATISTICS" + sql.substring(hint.start(1), hint.end()) + //$NON-NLS-1$
                " /* " + marker + " */" + sql.substring(hint.end()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sql.substring(0, at) + " /*+ GATHER_PLAN_STATISTICS */ /* " + marker + " */" + sql.substring(at); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    private static String planText(@NotNull JsonObject table) {
        StringBuilder lines = new StringBuilder();
        for (JsonElement r : table.getAsJsonArray("rows")) {
            String line = DiagnosticPostProcessor.str(r.getAsJsonObject(), "PLAN_TABLE_OUTPUT", "plan_table_output");
            lines.append(line == null ? "" : line).append('\n');
        }
        return lines.toString();
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
