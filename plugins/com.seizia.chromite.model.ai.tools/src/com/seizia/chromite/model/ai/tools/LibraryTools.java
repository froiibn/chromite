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
import com.seizia.chromite.model.ai.agent.AIToolAccess;
import com.seizia.chromite.model.ai.agent.AIToolAccessClassifier;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;
import com.seizia.chromite.model.ai.agent.AIToolConnections;
import com.seizia.chromite.model.ai.agent.AIToolPreviewProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Query Library tools: list, read, run with parameters, and run a folder of monitoring queries against their thresholds.
 *
 * 설계:
 * <ul>
 *     <li>매개변수 {@code :name}은 문자열·주석·{@code ::} 형 변환을 건너뛰고 {@code ?}로 바꿔 JDBC로 묶는다(값을 SQL에 붙이지 않음, Swing
 *     {@code QueryParameterBinder}와 같은 원칙). 형식은 머리말 {@code @param}의 {@code type}(number, date, datetime, 그 밖은 문자열).</li>
 *     <li>실행 대상: {@code connection} 인수, 없으면 머리말의 {@code @target-id}, 그다음 {@code @target-name}. DB는 머리말 {@code @database}.</li>
 *     <li>읽기라고 증명된 쿼리는 READ로 읽기 전용 보호 안에서, 아니면 WRITE로 올려 연결의 커밋 방식 그대로 실행한다.
 *     {@code runMonitoringChecks}는 읽기 쿼리만 실행하고 나머지는 건너뛴 이유를 적는다.</li>
 *     <li>기준값 판정은 {@link ThresholdEvaluator}(docs/chromite/ai-agent.md "Query Library 기준값").</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-12 서버로 보내는 쿼리의 끝 {@code ;}를 쿼리 창의 파서 판단대로 남김(앱 변경 #87): Oracle PL/SQL 블록인 라이브러리 쿼리가
 *   {@code runSql}처럼 PLS-00103으로 실패했다 (docs/chromite/ai-agent.md "문장 끝의 구분자")
 */
public class LibraryTools extends AgentTool implements AIToolAccessClassifier, AIToolPreviewProvider {

    @NotNull
    @Override
    protected JsonElement call(
        @NotNull AIToolCallContext context,
        @NotNull String toolId,
        @NotNull Map<String, Object> args
    ) throws DBException {
        return switch (toolId) {
            case "listLibraryQueries" -> list(args);
            case "getLibraryQuery" -> describe(LibraryReader.find(AgentArgs.require(args, "id")), true);
            case "runLibraryQuery" -> run(context, LibraryReader.find(AgentArgs.require(args, "id")), args, false);
            case "runMonitoringChecks" -> monitoring(context, args);
            default -> throw unknownTool(toolId);
        };
    }

    @NotNull
    private static JsonElement list(@NotNull Map<String, Object> args) throws DBException {
        String folder = AgentArgs.getString(args, "folder");
        String contains = AgentArgs.getString(args, "contains");
        JsonArray list = new JsonArray();
        for (LibraryReader.Item item : LibraryReader.list()) {
            if (!matchesFolder(item, folder)) {
                continue;
            }
            if (contains != null && !(item.name() + " " + item.description() + " " + item.sql()).toLowerCase(Locale.ROOT)
                .contains(contains.toLowerCase(Locale.ROOT))) {
                continue;
            }
            list.add(describe(item, false));
        }
        JsonObject json = new JsonObject();
        JsonArray roots = new JsonArray();
        for (LibraryReader.Root root : LibraryReader.roots()) {
            roots.add(root.name() + ": " + root.path());
        }
        json.add("roots", roots);
        json.add("queries", list);
        json.addProperty("count", list.size());
        return json;
    }

    private static boolean matchesFolder(@NotNull LibraryReader.Item item, @Nullable String folder) {
        if (folder == null) {
            return true;
        }
        String f = folder.toLowerCase(Locale.ROOT).replace('\\', '/');
        String itemFolder = item.folder().toLowerCase(Locale.ROOT);
        return itemFolder.equals(f) || itemFolder.startsWith(f + "/") || ("/" + itemFolder + "/").contains("/" + f + "/");
    }

    @NotNull
    private static JsonObject describe(@NotNull LibraryReader.Item item, boolean withSql) {
        JsonObject json = new JsonObject();
        json.addProperty("id", item.id());
        json.addProperty("name", item.name());
        if (item.description() != null) {
            json.addProperty("description", item.description());
        }
        json.addProperty("root", item.root());
        json.addProperty("folder", item.folder());
        if (item.targetName() != null || item.targetId() != null) {
            json.addProperty("target", item.targetName() != null ? item.targetName() : item.targetId());
        }
        if (item.database() != null) {
            json.addProperty("database", item.database());
        }
        JsonArray parameters = new JsonArray();
        for (LibraryReader.Parameter p : item.parameters()) {
            JsonObject param = new JsonObject();
            param.addProperty("name", p.name());
            if (p.label() != null) {
                param.addProperty("label", p.label());
            }
            param.addProperty("type", p.type() == null ? "text" : p.type());
            if (p.defaultValue() != null) {
                param.addProperty("default", p.defaultValue());
            }
            param.addProperty("required", p.required());
            if (!p.choices().isEmpty()) {
                JsonArray choices = new JsonArray();
                p.choices().forEach(choices::add);
                param.add("choices", choices);
            }
            parameters.add(param);
        }
        json.add("parameters", parameters);
        JsonArray thresholds = new JsonArray();
        for (LibraryReader.Threshold t : item.thresholds()) {
            JsonObject threshold = new JsonObject();
            threshold.addProperty("column", t.column());
            if (t.label() != null) {
                threshold.addProperty("label", t.label());
            }
            if (t.warn() != null) {
                threshold.addProperty("warn", t.warn());
            }
            if (t.critical() != null) {
                threshold.addProperty("critical", t.critical());
            }
            threshold.addProperty("scope", t.scope());
            thresholds.add(threshold);
        }
        json.add("thresholds", thresholds);
        if (withSql) {
            json.addProperty("sql", item.sql());
        }
        return json;
    }

    /**
     * Runs one library query; {@code monitoringOnly} skips queries that are not provably read-only.
     */
    @NotNull
    private static JsonObject run(
        @NotNull AIToolCallContext context,
        @NotNull LibraryReader.Item item,
        @NotNull Map<String, Object> args,
        boolean monitoringOnly
    ) throws DBException {
        DBPDataSourceContainer container = target(context, item);
        DBPDataSource dataSource = AgentSql.connect(context.getMonitor(), container);
        Bound bound = bind(item, AgentArgs.getJson(args, "parameters"));
        boolean readOnly = AgentSqlClassifier.classify(dataSource, AgentSqlClassifier.stripTrailingDelimiter(bound.sql())).readOnly();
        JsonObject json = new JsonObject();
        json.addProperty("query", item.name());
        json.addProperty("connection", container.getName());
        if (monitoringOnly && !readOnly) {
            json.addProperty("status", "SKIPPED");
            json.addProperty("reason", "the query is not provably read-only; run it with runLibraryQuery (asks the user)");
            return json;
        }
        if (!readOnly && context.getConnection() == null) {
            // The policy judged this call without a connection: a write needs the connection named so its rules apply
            throw new AIToolArgumentException("This library query changes data; pass 'connection' explicitly");
        }
        AgentLimits limits = AgentLimits.of(args, 200);
        String sql = AgentSqlClassifier.trimTrailingDelimiter(dataSource, bound.sql());
        JsonObject result = AgentSql.run(context.getMonitor(), container, item.database(), readOnly ? AgentSql.Mode.READ_ONLY : AgentSql.Mode.NATIVE,
            (connection, runner) -> runner.query(connection, sql, bound.values(), limits, true));
        json.add("result", result);
        json.add("evaluation", ThresholdEvaluator.evaluate(item.thresholds(), result.getAsJsonArray("rows")));
        json.addProperty("status", json.getAsJsonObject("evaluation").get("status").getAsString());
        if (!readOnly) {
            QueryTools.addTransactionState(container, json);
        }
        return json;
    }

    @NotNull
    private static JsonElement monitoring(@NotNull AIToolCallContext context, @NotNull Map<String, Object> args) throws DBException {
        String folder = AgentArgs.require(args, "folder");
        JsonArray checks = new JsonArray();
        int ok = 0, warn = 0, critical = 0, noThresholds = 0, skipped = 0, failed = 0;
        for (LibraryReader.Item item : LibraryReader.list()) {
            if (!matchesFolder(item, folder)) {
                continue;
            }
            if (context.getMonitor().isCanceled()) {
                break;
            }
            JsonObject check;
            if (item.parameters().stream().anyMatch(p -> p.required() && p.defaultValue() == null)) {
                check = new JsonObject();
                check.addProperty("query", item.name());
                check.addProperty("status", "SKIPPED");
                check.addProperty("reason", "needs parameters without defaults; run it with runLibraryQuery");
            } else {
                try {
                    check = run(context, item, args, true);
                    // The table is in the result for the model; keep the summary lean
                    JsonObject result = check.getAsJsonObject("result");
                    if (result != null && result.getAsJsonArray("rows").size() > 20) {
                        JsonArray first = new JsonArray();
                        for (int i = 0; i < 20; i++) {
                            first.add(result.getAsJsonArray("rows").get(i));
                        }
                        result.add("rows", first);
                        result.addProperty("rowsShown", 20);
                    }
                } catch (DBException e) {
                    check = new JsonObject();
                    check.addProperty("query", item.name());
                    check.addProperty("status", "ERROR");
                    check.addProperty("error", e.getMessage());
                }
            }
            switch (check.get("status").getAsString()) {
                case "OK" -> ok++;
                case "WARN" -> warn++;
                case "CRITICAL" -> critical++;
                case "NO_THRESHOLDS" -> noThresholds++;
                case "SKIPPED" -> skipped++;
                default -> failed++;
            }
            checks.add(check);
        }
        JsonObject summary = new JsonObject();
        summary.addProperty("ok", ok);
        summary.addProperty("warn", warn);
        summary.addProperty("critical", critical);
        summary.addProperty("noThresholds", noThresholds);
        summary.addProperty("skipped", skipped);
        summary.addProperty("error", failed);
        summary.addProperty("overall", critical > 0 ? "CRITICAL" : warn > 0 ? "WARN" : failed > 0 ? "ERROR" : checks.isEmpty() ? "NO_QUERIES" : "OK");
        JsonObject json = new JsonObject();
        json.addProperty("folder", folder);
        json.add("summary", summary);
        json.add("checks", checks);
        if (noThresholds > 0) {
            json.addProperty("note", noThresholds + " queries have no thresholds: report their values and flag only obvious problems");
        }
        return json;
    }

    @NotNull
    private static DBPDataSourceContainer target(@NotNull AIToolCallContext context, @NotNull LibraryReader.Item item) throws DBException {
        if (context.getConnection() != null) {
            return context.getConnection();
        }
        if (item.targetId() != null) {
            try {
                return AIToolConnections.find(item.targetId());
            } catch (AIToolArgumentException e) {
                // a colleague's file: the ID differs, try the name
            }
        }
        if (item.targetName() != null) {
            return AIToolConnections.find(item.targetName());
        }
        throw new AIToolArgumentException("Library query '" + item.name() + "' has no fixed target; pass 'connection'");
    }

    record Bound(@NotNull String sql, @NotNull List<Object> values) {
    }

    /**
     * Replaces {@code :name} outside strings, quoted identifiers, comments and {@code ::} casts with {@code ?}.
     */
    @NotNull
    static Bound bind(@NotNull LibraryReader.Item item, @Nullable JsonElement given) throws AIToolArgumentException {
        JsonObject values = given != null && given.isJsonObject() ? given.getAsJsonObject() : new JsonObject();
        String sql = item.sql();
        StringBuilder out = new StringBuilder();
        List<Object> bound = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                int end = sql.indexOf(close, i + 1);
                end = end < 0 ? sql.length() - 1 : end;
                out.append(sql, i, end + 1);
                i = end + 1;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? sql.length() : end;
                out.append(sql, i, end);
                i = end;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? sql.length() : end + 2;
                out.append(sql, i, end);
                i = end;
            } else if (c == ':' && i + 1 < sql.length() && sql.charAt(i + 1) == ':') {
                out.append("::");
                i += 2;
            } else if (c == ':' && i + 1 < sql.length() && Character.isJavaIdentifierStart(sql.charAt(i + 1))) {
                int end = i + 1;
                while (end < sql.length() && Character.isJavaIdentifierPart(sql.charAt(end))) {
                    end++;
                }
                String name = sql.substring(i + 1, end);
                bound.add(value(item, name, values));
                out.append('?');
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return new Bound(out.toString(), bound);
    }

    @Nullable
    private static Object value(@NotNull LibraryReader.Item item, @NotNull String name, @NotNull JsonObject values)
        throws AIToolArgumentException {
        LibraryReader.Parameter parameter = item.parameters().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        String text = null;
        for (Map.Entry<String, JsonElement> e : values.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isJsonNull()) {
                text = e.getValue().getAsString();
            }
        }
        if (text == null && parameter != null) {
            text = parameter.defaultValue();
        }
        if (text == null) {
            if (parameter != null && parameter.required()) {
                throw new AIToolArgumentException("Parameter '" + name + "' is required");
            }
            return null;
        }
        String type = parameter == null || parameter.type() == null ? "text" : parameter.type().toLowerCase(Locale.ROOT);
        try {
            return switch (type) {
                case "number" -> new BigDecimal(text.trim());
                case "date" -> LocalDate.parse(text.trim());
                case "datetime" -> LocalDateTime.parse(text.trim().replace(' ', 'T'));
                default -> text;
            };
        } catch (RuntimeException e) {
            throw new AIToolArgumentException("Parameter '" + name + "' must be a " + type + ": " + text);
        }
    }

    // ------------------------------------------------------------------ policy hooks

    @NotNull
    @Override
    public AIToolAccess classify(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments) throws DBException {
        if (!context.getTool().getId().equals("runLibraryQuery")) {
            return AIToolAccess.READ;
        }
        LibraryReader.Item item = LibraryReader.find(AgentArgs.require(arguments, "id"));
        DBPDataSourceContainer container = context.getConnection();
        DBPDataSource dataSource = container == null ? null : container.getDataSource();
        String sql = AgentSqlClassifier.stripTrailingDelimiter(bind(item, AgentArgs.getJson(arguments, "parameters")).sql());
        return AgentSqlClassifier.classify(dataSource, sql).readOnly() ? AIToolAccess.READ : AgentSqlClassifier.access(dataSource, sql);
    }

    @Nullable
    @Override
    public String getPreview(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments) throws DBException {
        if (!context.getTool().getId().equals("runLibraryQuery")) {
            return null;
        }
        LibraryReader.Item item = LibraryReader.find(AgentArgs.require(arguments, "id"));
        return "-- " + item.name() + "\n" + item.sql() + "\n-- parameters: " + arguments.get("parameters");
    }
}
