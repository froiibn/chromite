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
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;
import com.seizia.chromite.model.ai.agent.AIToolConnections;
import com.seizia.chromite.model.ai.agent.AIToolPreviewProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Data tools: sample, count, column profile, result comparison (READ); insert, update, delete rows by key (WRITE).
 *
 * 설계:
 * <ul>
 *     <li>읽기 도구가 만드는 SELECT에 모델의 {@code where}·{@code orderBy}가 들어가므로, 완성된 문장 전체를 분류기로 다시 검사한다
 *     ({@link QueryTools#requireReadOnly}). 식별자는 방언으로 따옴표를 붙인다.</li>
 *     <li>{@code updateRows}·{@code deleteRows}는 키가 기본 키나 고유 인덱스의 열을 모두 덮어야 한다. 키가 고유하지 않으면 한 번에 여러 행이 바뀌는 사고가
 *     나므로 실행 전에 거부한다. 쓰기는 {@link AgentSql.Mode#ATOMIC}(자동 커밋 연결: 한 호출 = 한 트랜잭션, 수동 커밋 연결: 열어 둠).</li>
 *     <li>{@code profileColumns}는 열마다 집계 쿼리를 따로 돌린다. 형식 때문에 MIN/MAX가 안 되는 열(긴 텍스트, XML)은 그 값만 빠지고
 *     PostgreSQL은 저장점으로 나머지 열을 계속한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-15 도구 정리(docs/chromite/ai-agent.md "별칭"): sampleRows의 format → rowFormat, compareQueryResults의 maxRows → limit
 */
public class DataTools extends AgentTool implements AIToolPreviewProvider {

    private static final int MAX_PROFILE_COLUMNS = 30;
    private static final int COMPARE_SAMPLES = 20;

    @NotNull
    @Override
    protected JsonElement call(
        @NotNull AIToolCallContext context,
        @NotNull String toolId,
        @NotNull Map<String, Object> args
    ) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        DBPDataSource dataSource = AgentSql.connect(context.getMonitor(), container);
        DbFamily family = DbFamily.of(container);
        String database = AgentArgs.getString(args, "database");
        return switch (toolId) {
            case "sampleRows" -> sample(context, container, dataSource, family, database, args);
            case "countRows" -> count(context, container, dataSource, family, database, args);
            case "profileColumns" -> profile(context, container, dataSource, family, database, args);
            case "compareQueryResults" -> compare(context, container, dataSource, args);
            case "insertRows" -> insert(context, container, dataSource, family, database, args);
            case "updateRows", "deleteRows" -> updateOrDelete(context, container, dataSource, family, database, args, toolId.equals("deleteRows"));
            default -> throw unknownTool(toolId);
        };
    }

    // ------------------------------------------------------------------ read

    @NotNull
    private static JsonElement sample(
        @NotNull AIToolCallContext context,
        @NotNull DBPDataSourceContainer container,
        @NotNull DBPDataSource dataSource,
        @NotNull DbFamily family,
        @Nullable String database,
        @NotNull Map<String, Object> args
    ) throws DBException {
        String table = AgentArgs.require(args, "table");
        AgentLimits limits = AgentLimits.of(args, 20);
        boolean objects = !"arrays".equalsIgnoreCase(AgentArgs.getString(args, "rowFormat"));
        return AgentSql.run(context.getMonitor(), container, database, AgentSql.Mode.READ_ONLY, (connection, runner) -> {
            AgentNames.QName name = AgentNames.resolve(connection, family, table);
            String sql = "SELECT * FROM " + name.quoted(dataSource) + where(args) + orderBy(args);
            QueryTools.requireReadOnly(dataSource, sql, "sampleRows");
            JsonObject result = runner.queryPage(connection, sql, Math.max(0, AgentArgs.getInt(args, "offset", 0)), limits, objects);
            result.addProperty("table", name.display());
            return result;
        });
    }

    @NotNull
    private static JsonElement count(
        @NotNull AIToolCallContext context,
        @NotNull DBPDataSourceContainer container,
        @NotNull DBPDataSource dataSource,
        @NotNull DbFamily family,
        @Nullable String database,
        @NotNull Map<String, Object> args
    ) throws DBException {
        String table = AgentArgs.require(args, "table");
        return AgentSql.run(context.getMonitor(), container, database, AgentSql.Mode.READ_ONLY, (connection, runner) -> {
            AgentNames.QName name = AgentNames.resolve(connection, family, table);
            String sql = "SELECT COUNT(*) FROM " + name.quoted(dataSource) + where(args);
            QueryTools.requireReadOnly(dataSource, sql, "countRows");
            JsonObject rows = runner.query(connection, sql, null, new AgentLimits(1, 100, AgentLimits.DEFAULT_BYTES), false);
            JsonObject json = new JsonObject();
            json.addProperty("table", name.display());
            json.add("count", rows.getAsJsonArray("rows").get(0).getAsJsonArray().get(0));
            return json;
        });
    }

    @NotNull
    private static JsonElement profile(
        @NotNull AIToolCallContext context,
        @NotNull DBPDataSourceContainer container,
        @NotNull DBPDataSource dataSource,
        @NotNull DbFamily family,
        @Nullable String database,
        @NotNull Map<String, Object> args
    ) throws DBException {
        String table = AgentArgs.require(args, "table");
        int topN = Math.max(0, Math.min(50, AgentArgs.getInt(args, "topN", 5)));
        List<String> wanted = AgentArgs.getList(args, "columns");
        return AgentSql.run(context.getMonitor(), container, database, AgentSql.Mode.READ_ONLY, (connection, runner) -> {
            AgentNames.QName name = AgentNames.resolve(connection, family, table);
            String from = name.quoted(dataSource);
            AgentValues.TableColumns columns = AgentValues.columns(connection, from);
            List<String> selected = new ArrayList<>();
            if (wanted.isEmpty()) {
                selected.addAll(columns.types().keySet());
            } else {
                for (String w : wanted) {
                    selected.add(columns.column(w));
                }
            }
            if (selected.size() > MAX_PROFILE_COLUMNS) {
                selected = selected.subList(0, MAX_PROFILE_COLUMNS);
            }
            JsonArray result = new JsonArray();
            AgentLimits one = new AgentLimits(1, 200, AgentLimits.DEFAULT_BYTES);
            for (String column : selected) {
                if (runner.getMonitor().isCanceled()) {
                    break;
                }
                String q = DBUtils.getQuotedIdentifier(dataSource, column);
                JsonObject profile = new JsonObject();
                profile.addProperty("column", column);
                profile.add("counts", tryQuery(connection, runner, family,
                    "SELECT COUNT(*) AS total_rows, COUNT(" + q + ") AS non_null, COUNT(DISTINCT " + q + ") AS distinct_values FROM " + from, one));
                profile.add("range", tryQuery(connection, runner, family,
                    "SELECT MIN(" + q + ") AS min_value, MAX(" + q + ") AS max_value FROM " + from, one));
                if (topN > 0) {
                    profile.add("topValues", tryQuery(connection, runner, family,
                        "SELECT " + q + " AS value_, COUNT(*) AS rows_ FROM " + from + " GROUP BY " + q + " ORDER BY COUNT(*) DESC",
                        new AgentLimits(topN, 200, AgentLimits.DEFAULT_BYTES)));
                }
                result.add(profile);
            }
            JsonObject json = new JsonObject();
            json.addProperty("table", name.display());
            json.add("columns", result);
            return json;
        });
    }

    @NotNull
    private static JsonElement tryQuery(
        @NotNull Connection connection,
        @NotNull AgentSql.Runner runner,
        @NotNull DbFamily family,
        @NotNull String sql,
        @NotNull AgentLimits limits
    ) throws SQLException {
        Savepoint savepoint = family == DbFamily.POSTGRESQL && !connection.getAutoCommit() ? connection.setSavepoint() : null;
        try {
            JsonObject table = runner.queryPage(connection, sql, 0, limits, true);
            JsonArray rows = table.getAsJsonArray("rows");
            return limits.maxRows() == 1 && rows.size() == 1 ? rows.get(0) : rows;
        } catch (SQLException e) {
            if (savepoint != null) {
                connection.rollback(savepoint);
            }
            return AgentJson.error(e.getMessage(), null);
        }
    }

    @NotNull
    private static JsonElement compare(
        @NotNull AIToolCallContext context,
        @NotNull DBPDataSourceContainer container,
        @NotNull DBPDataSource dataSource,
        @NotNull Map<String, Object> args
    ) throws DBException {
        String sqlA = AgentSqlClassifier.stripTrailingDelimiter(AgentArgs.require(args, "sqlA"));
        String sqlB = AgentSqlClassifier.stripTrailingDelimiter(AgentArgs.require(args, "sqlB"));
        String otherName = AgentArgs.getString(args, "connectionB");
        DBPDataSourceContainer other = otherName == null ? container : AIToolConnections.find(otherName);
        DBPDataSource otherSource = AgentSql.connect(context.getMonitor(), other);
        QueryTools.requireReadOnly(dataSource, sqlA, "compareQueryResults");
        QueryTools.requireReadOnly(otherSource, sqlB, "compareQueryResults");
        int maxRows = Math.max(1, Math.min(100_000, AgentArgs.getInt(args, AgentLimits.PARAM_LIMIT, 10_000)));
        AgentLimits limits = new AgentLimits(maxRows, 2000, Integer.MAX_VALUE);
        JsonObject a = AgentSql.run(context.getMonitor(), container, AgentArgs.getString(args, "database"), AgentSql.Mode.READ_ONLY,
            (connection, runner) -> runner.queryPage(connection, sqlA, 0, limits, true));
        JsonObject b = AgentSql.run(context.getMonitor(), other, AgentArgs.getString(args, "databaseB"), AgentSql.Mode.READ_ONLY,
            (connection, runner) -> runner.queryPage(connection, sqlB, 0, limits, true));
        List<String> keyColumns = AgentArgs.getList(args, "keyColumns");
        JsonObject json = new JsonObject();
        json.addProperty("rowsA", a.get("rowCount").getAsInt());
        json.addProperty("rowsB", b.get("rowCount").getAsInt());
        json.addProperty("truncated", a.get("hasMore").getAsBoolean() || b.get("hasMore").getAsBoolean());
        JsonArray onlyA = new JsonArray();
        JsonArray onlyB = new JsonArray();
        JsonArray different = new JsonArray();
        int countOnlyA = 0;
        int countOnlyB = 0;
        int countDifferent = 0;
        if (keyColumns.isEmpty()) {
            // Multiset comparison of whole rows
            Map<String, Integer> remaining = new HashMap<>();
            for (JsonElement row : a.getAsJsonArray("rows")) {
                remaining.merge(normalize(row), 1, Integer::sum);
            }
            for (JsonElement row : b.getAsJsonArray("rows")) {
                String key = normalize(row);
                Integer n = remaining.get(key);
                if (n == null || n == 0) {
                    countOnlyB++;
                    if (onlyB.size() < COMPARE_SAMPLES) {
                        onlyB.add(row);
                    }
                } else {
                    remaining.put(key, n - 1);
                }
            }
            for (JsonElement row : a.getAsJsonArray("rows")) {
                String key = normalize(row);
                Integer n = remaining.get(key);
                if (n != null && n > 0) {
                    remaining.put(key, n - 1);
                    countOnlyA++;
                    if (onlyA.size() < COMPARE_SAMPLES) {
                        onlyA.add(row);
                    }
                }
            }
        } else {
            Map<String, JsonObject> mapA = byKey(a.getAsJsonArray("rows"), keyColumns);
            Map<String, JsonObject> mapB = byKey(b.getAsJsonArray("rows"), keyColumns);
            for (Map.Entry<String, JsonObject> e : mapA.entrySet()) {
                JsonObject rowB = mapB.get(e.getKey());
                if (rowB == null) {
                    countOnlyA++;
                    if (onlyA.size() < COMPARE_SAMPLES) {
                        onlyA.add(e.getValue());
                    }
                } else if (!normalize(e.getValue()).equals(normalize(rowB))) {
                    countDifferent++;
                    if (different.size() < COMPARE_SAMPLES) {
                        JsonObject diff = new JsonObject();
                        diff.add("a", e.getValue());
                        diff.add("b", rowB);
                        different.add(diff);
                    }
                }
            }
            for (Map.Entry<String, JsonObject> e : mapB.entrySet()) {
                if (!mapA.containsKey(e.getKey())) {
                    countOnlyB++;
                    if (onlyB.size() < COMPARE_SAMPLES) {
                        onlyB.add(e.getValue());
                    }
                }
            }
        }
        json.addProperty("equal", countOnlyA == 0 && countOnlyB == 0 && countDifferent == 0);
        json.addProperty("onlyInACount", countOnlyA);
        json.addProperty("onlyInBCount", countOnlyB);
        json.addProperty("differentCount", countDifferent);
        json.add("onlyInA", onlyA);
        json.add("onlyInB", onlyB);
        if (!keyColumns.isEmpty()) {
            json.add("different", different);
        }
        return json;
    }

    // Column names compared case-insensitively, values as JSON text
    @NotNull
    private static String normalize(@NotNull JsonElement row) {
        if (!row.isJsonObject()) {
            return row.toString();
        }
        Map<String, String> sorted = new java.util.TreeMap<>();
        for (Map.Entry<String, JsonElement> e : row.getAsJsonObject().entrySet()) {
            JsonElement v = e.getValue();
            sorted.put(e.getKey().toLowerCase(Locale.ROOT), v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()
                ? new java.math.BigDecimal(v.getAsString()).stripTrailingZeros().toPlainString() : v.toString());
        }
        return sorted.toString();
    }

    @NotNull
    private static Map<String, JsonObject> byKey(@NotNull JsonArray rows, @NotNull List<String> keyColumns) throws AIToolArgumentException {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (JsonElement r : rows) {
            JsonObject row = r.getAsJsonObject();
            StringBuilder key = new StringBuilder();
            for (String column : keyColumns) {
                JsonElement value = null;
                for (Map.Entry<String, JsonElement> e : row.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(column)) {
                        value = e.getValue();
                    }
                }
                if (value == null) {
                    throw new AIToolArgumentException("Key column '" + column + "' is not in the result");
                }
                key.append(value).append('\u0001');
            }
            result.put(key.toString(), row);
        }
        return result;
    }

    @NotNull
    private static String where(@NotNull Map<String, Object> args) {
        String where = AgentArgs.getString(args, "where");
        return where == null ? "" : " WHERE " + where;
    }

    @NotNull
    private static String orderBy(@NotNull Map<String, Object> args) {
        String orderBy = AgentArgs.getString(args, "orderBy");
        return orderBy == null ? "" : " ORDER BY " + orderBy;
    }

    // ------------------------------------------------------------------ write

    @NotNull
    private static JsonElement insert(
        @NotNull AIToolCallContext context,
        @NotNull DBPDataSourceContainer container,
        @NotNull DBPDataSource dataSource,
        @NotNull DbFamily family,
        @Nullable String database,
        @NotNull Map<String, Object> args
    ) throws DBException {
        String table = AgentArgs.require(args, "table");
        JsonArray rows = rowsArgument(args);
        int inserted = AgentSql.run(context.getMonitor(), container, database, AgentSql.Mode.ATOMIC, (connection, runner) -> {
            AgentNames.QName name = AgentNames.resolve(connection, family, table);
            String quoted = name.quoted(dataSource);
            AgentValues.TableColumns columns = AgentValues.columns(connection, quoted);
            int count = 0;
            for (JsonElement r : rows) {
                if (!r.isJsonObject()) {
                    throw new AIToolArgumentException("Each row must be a JSON object of column: value");
                }
                count += insertRow(connection, runner, dataSource, quoted, columns, r.getAsJsonObject());
            }
            return count;
        });
        JsonObject json = new JsonObject();
        json.addProperty("inserted", inserted);
        QueryTools.addTransactionState(container, json);
        return json;
    }

    static int insertRow(
        @NotNull Connection connection,
        @NotNull AgentSql.Runner runner,
        @NotNull DBPDataSource dataSource,
        @NotNull String quotedTable,
        @NotNull AgentValues.TableColumns columns,
        @NotNull JsonObject row
    ) throws SQLException, AIToolArgumentException {
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : row.entrySet()) {
            names.add(columns.column(e.getKey()));
            values.add(e.getValue());
        }
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(quotedTable).append(" (");
        for (int i = 0; i < names.size(); i++) {
            sql.append(i > 0 ? ", " : "").append(DBUtils.getQuotedIdentifier(dataSource, names.get(i)));
        }
        sql.append(") VALUES (").append("?, ".repeat(Math.max(0, names.size() - 1))).append(names.isEmpty() ? "" : "?").append(')');
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < names.size(); i++) {
                AgentValues.bind(statement, i + 1, values.get(i), columns.type(names.get(i)));
            }
            runner.track(statement, true);
            try {
                return statement.executeUpdate();
            } finally {
                runner.track(statement, false);
            }
        }
    }

    @NotNull
    private static JsonElement updateOrDelete(
        @NotNull AIToolCallContext context,
        @NotNull DBPDataSourceContainer container,
        @NotNull DBPDataSource dataSource,
        @NotNull DbFamily family,
        @Nullable String database,
        @NotNull Map<String, Object> args,
        boolean delete
    ) throws DBException {
        String table = AgentArgs.require(args, "table");
        JsonArray keys = keysArgument(args);
        JsonElement valuesArg = delete ? null : AgentArgs.getJson(args, "values");
        if (!delete && (valuesArg == null || !valuesArg.isJsonObject() || valuesArg.getAsJsonObject().isEmpty())) {
            throw new AIToolArgumentException("Argument 'values' must be a JSON object of column: new value");
        }
        int changed = AgentSql.run(context.getMonitor(), container, database, AgentSql.Mode.ATOMIC, (connection, runner) -> {
            AgentNames.QName name = AgentNames.resolve(connection, family, table);
            String quoted = name.quoted(dataSource);
            AgentValues.TableColumns columns = AgentValues.columns(connection, quoted);
            List<Set<String>> uniqueKeys = AgentValues.uniqueKeys(connection, name);
            int count = 0;
            for (JsonElement k : keys) {
                Map<String, JsonElement> key = AgentValues.keyColumns(k.getAsJsonObject(), columns);
                Set<String> keyNames = new java.util.HashSet<>();
                key.keySet().forEach(c -> keyNames.add(c.toLowerCase(Locale.ROOT)));
                if (uniqueKeys.stream().noneMatch(keyNames::containsAll)) {
                    throw new AIToolArgumentException("The key " + key.keySet() + " does not cover a primary key or unique index of " +
                        name.display() + " (unique keys: " + uniqueKeys + "); refusing to change possibly many rows");
                }
                StringBuilder sql = new StringBuilder(delete ? "DELETE FROM " : "UPDATE ").append(quoted);
                List<Object> values = new ArrayList<>();
                List<Integer> types = new ArrayList<>();
                if (!delete) {
                    sql.append(" SET ");
                    boolean first = true;
                    for (Map.Entry<String, JsonElement> e : valuesArg.getAsJsonObject().entrySet()) {
                        String column = columns.column(e.getKey());
                        sql.append(first ? "" : ", ").append(DBUtils.getQuotedIdentifier(dataSource, column)).append(" = ?");
                        values.add(e.getValue());
                        types.add(columns.type(column));
                        first = false;
                    }
                }
                sql.append(" WHERE ");
                boolean first = true;
                for (Map.Entry<String, JsonElement> e : key.entrySet()) {
                    sql.append(first ? "" : " AND ").append(DBUtils.getQuotedIdentifier(dataSource, e.getKey()));
                    if (e.getValue().isJsonNull()) {
                        sql.append(" IS NULL");
                    } else {
                        sql.append(" = ?");
                        values.add(e.getValue());
                        types.add(columns.type(e.getKey()));
                    }
                    first = false;
                }
                try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                    for (int i = 0; i < values.size(); i++) {
                        AgentValues.bind(statement, i + 1, values.get(i), types.get(i));
                    }
                    runner.track(statement, true);
                    try {
                        count += statement.executeUpdate();
                    } finally {
                        runner.track(statement, false);
                    }
                }
            }
            return count;
        });
        JsonObject json = new JsonObject();
        json.addProperty(delete ? "deleted" : "updated", changed);
        QueryTools.addTransactionState(container, json);
        return json;
    }

    @NotNull
    private static JsonArray rowsArgument(@NotNull Map<String, Object> args) throws AIToolArgumentException {
        JsonElement rows = AgentArgs.getJson(args, "rows");
        if (rows == null) {
            throw new AIToolArgumentException("Argument 'rows' is required: a JSON array of objects");
        }
        if (rows.isJsonObject()) {
            JsonArray one = new JsonArray();
            one.add(rows);
            return one;
        }
        if (!rows.isJsonArray() || rows.getAsJsonArray().isEmpty()) {
            throw new AIToolArgumentException("Argument 'rows' must be a non-empty JSON array of objects");
        }
        if (rows.getAsJsonArray().size() > 10_000) {
            throw new AIToolArgumentException("At most 10000 rows per call; use importCsv for more");
        }
        return rows.getAsJsonArray();
    }

    @NotNull
    private static JsonArray keysArgument(@NotNull Map<String, Object> args) throws AIToolArgumentException {
        JsonElement key = AgentArgs.getJson(args, "key");
        if (key == null) {
            throw new AIToolArgumentException("Argument 'key' is required: a JSON object (or array of objects) of key column: value");
        }
        JsonArray keys = new JsonArray();
        if (key.isJsonObject()) {
            keys.add(key);
        } else if (key.isJsonArray()) {
            for (JsonElement k : key.getAsJsonArray()) {
                if (!k.isJsonObject()) {
                    throw new AIToolArgumentException("Each key must be a JSON object");
                }
                keys.add(k);
            }
        } else {
            throw new AIToolArgumentException("Argument 'key' must be a JSON object or an array of objects");
        }
        return keys;
    }

    @Nullable
    @Override
    public String getPreview(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments) {
        String toolId = context.getTool().getId();
        String table = AgentArgs.getString(arguments, "table");
        return switch (toolId) {
            case "insertRows" -> "INSERT INTO " + table + " rows: " + arguments.get("rows");
            case "updateRows" -> "UPDATE " + table + " SET " + arguments.get("values") + " WHERE key = " + arguments.get("key");
            case "deleteRows" -> "DELETE FROM " + table + " WHERE key = " + arguments.get("key");
            default -> null;
        };
    }
}
