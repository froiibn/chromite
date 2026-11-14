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
import com.google.gson.JsonPrimitive;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shapes raw diagnostic rows into what the model needs: numbered source, blocking trees with findings, parsed deadlock graphs
 * and plans, dependency graphs, permission-to-tool maps.
 *
 * 설계: 해석은 결정적인 규칙만 한다(트리 만들기, XML 읽기, 임계값 비교). "왜"를 설명하는 최종 문장은 모델이 쓴다. 다만 블로킹은 같은 규칙
 * (잠자는 머리 블로커 + 열린 트랜잭션 = 앱이 커밋하지 않음 등)을 매번 모델이 다시 추론하지 않도록 {@code findings}로 후보를 미리 낸다
 * (docs/chromite/ai-agent.md 플레이북 5).
 * Query Store 도구는 Query Store가 꺼져 있으면 뷰가 빈 결과를 돌려준다. 모델이 이를 "비싼 쿼리 없음"으로 읽지 않도록 상태를 결과 맨 앞의 문장으로 밝힌다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-08 Oracle 교착 상태: 경고 로그 항목이 가리키는 추적 파일에서 교착 상태 그래프를 읽음, Query Store 상태 문장
 * - 2026-11-15 도구 정리(docs/chromite/ai-agent.md "별칭"): probePermissions → checkDiagnosticAccess. 후처리는 도구 ID가 아니라 호출이 쓴 SQL
 *   자원 이름으로 고른다(getTopQueries source=queryStore는 getQueryStoreTopQueries)
 *   (docs/chromite/ai-agent.md "1b단계 도구")
 */
final class DiagnosticPostProcessor {

    private static final int MAX_GRAPH_NODES = 60;
    // Lines of one Oracle deadlock section: header, graph and the waiting sessions are about 60 lines
    private static final int ORACLE_DEADLOCK_LINES = 150;
    private static final int ORACLE_DEADLOCK_CHARS = 12000;

    private DiagnosticPostProcessor() {
    }

    /**
     * Post-processing that needs the open session (more statements).
     */
    static void processInSession(
        @NotNull String toolId,
        @NotNull Connection connection,
        @NotNull AgentSql.Runner runner,
        @NotNull DiagnosticSqlTool tool,
        @NotNull AIToolCallContext context,
        @NotNull DiagnosticScript.Block block,
        @NotNull Map<String, Object> args,
        @NotNull AgentLimits limits,
        @NotNull JsonObject result
    ) throws DBException {
        if (toolId.equals("getObjectDependencies")) {
            dependencyGraph(connection, runner, tool, context, block, args, limits, result);
        } else if (toolId.equals("getDeadlocks") && block.family() == DbFamily.ORACLE) {
            oracleDeadlockGraphs(connection, result);
        }
    }

    @NotNull
    static JsonElement process(
        @NotNull String toolId,
        @NotNull AIToolCallContext context,
        @NotNull DbFamily family,
        @NotNull Map<String, Object> args,
        @NotNull JsonObject result
    ) throws DBException {
        switch (toolId) {
            case "getRoutineSource" -> numberSources(result);
            case "getBlockingChain" -> blockingChain(result, family);
            case "listLocks" -> lockSummary(result);
            case "getDeadlocks" -> deadlocks(result, family);
            case "getCachedPlans" -> cachedPlans(result);
            case "checkDiagnosticAccess" -> PermissionProbe.summarize(result, family);
            case "getErrorLog" -> errorLogTail(result, args);
            case "getQueryStoreTopQueries", "getQueryStoreRegressions", "getQueryStoreForcedPlans" -> queryStoreState(result);
            default -> {
                // rows as they are
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ routine source

    // Every "definition" (or Oracle all_source lines) becomes text with line numbers the model can cite
    private static void numberSources(@NotNull JsonObject result) {
        for (Map.Entry<String, JsonElement> e : result.entrySet()) {
            JsonArray rows = rows(e.getValue());
            if (rows == null) {
                continue;
            }
            if (!rows.isEmpty() && rows.get(0).getAsJsonObject().has("line") && rows.get(0).getAsJsonObject().has("text")) {
                // Oracle: one row per source line → one routine per (owner, name, type)
                Map<String, JsonObject> routines = new LinkedHashMap<>();
                Map<String, StringBuilder> texts = new LinkedHashMap<>();
                for (JsonElement r : rows) {
                    JsonObject row = r.getAsJsonObject();
                    String key = str(row, "schema_name") + "." + str(row, "name") + ":" + str(row, "type_desc");
                    routines.computeIfAbsent(key, k -> {
                        JsonObject routine = new JsonObject();
                        routine.add("schema_name", row.get("schema_name"));
                        routine.add("name", row.get("name"));
                        routine.add("type_desc", row.get("type_desc"));
                        return routine;
                    });
                    texts.computeIfAbsent(key, k -> new StringBuilder()).append(str(row, "text"));
                }
                JsonArray merged = new JsonArray();
                for (Map.Entry<String, JsonObject> r : routines.entrySet()) {
                    JsonObject routine = r.getValue();
                    String text = texts.get(r.getKey()).toString();
                    routine.addProperty("lineCount", text.split("\n", -1).length);
                    routine.addProperty("source", numbered(text));
                    merged.add(routine);
                }
                e.getValue().getAsJsonObject().add("rows", merged);
                continue;
            }
            for (JsonElement r : rows) {
                JsonObject row = r.getAsJsonObject();
                JsonElement definition = row.remove("definition");
                if (definition != null && !definition.isJsonNull()) {
                    String text = definition.getAsString();
                    row.addProperty("lineCount", text.split("\\R", -1).length);
                    row.addProperty("source", numbered(text));
                }
            }
        }
    }

    @NotNull
    static String numbered(@NotNull String text) {
        String[] lines = text.split("\\R", -1);
        int width = String.valueOf(lines.length).length();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            out.append(String.format(Locale.ROOT, "%" + width + "d| ", i + 1)).append(lines[i]).append('\n');
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ blocking

    private static void blockingChain(@NotNull JsonObject result, @NotNull DbFamily family) {
        JsonArray rows = rows(result.get("sessions"));
        if (rows == null) {
            return;
        }
        // Merge rows (MySQL returns one row per wait edge) into sessions with a set of blockers
        Map<String, JsonObject> sessions = new LinkedHashMap<>();
        Map<String, Set<String>> blockedBy = new LinkedHashMap<>();
        for (JsonElement r : rows) {
            JsonObject row = r.getAsJsonObject();
            String id = str(row, "session_id");
            if (id == null) {
                continue;
            }
            sessions.putIfAbsent(id, row.deepCopy());
            Set<String> blockers = blockedBy.computeIfAbsent(id, k -> new LinkedHashSet<>());
            JsonElement value = row.get("blocked_by");
            if (value != null && !value.isJsonNull()) {
                for (String part : value.getAsString().replaceAll("[{}\\[\\]\\s]", "").split(",")) {
                    if (!part.isEmpty() && !part.equals("0") && !part.equals(id)) {
                        blockers.add(part);
                    }
                }
            }
        }
        Map<String, List<String>> blocking = new LinkedHashMap<>();
        blockedBy.forEach((waiter, blockers) -> blockers.forEach(b -> blocking.computeIfAbsent(b, k -> new ArrayList<>()).add(waiter)));
        JsonArray heads = new JsonArray();
        JsonArray findings = new JsonArray();
        for (String blocker : blocking.keySet()) {
            Set<String> own = blockedBy.getOrDefault(blocker, Set.of());
            if (own.isEmpty() || own.stream().noneMatch(sessions::containsKey)) {
                JsonObject head = tree(blocker, sessions, blocking, new LinkedHashSet<>());
                heads.add(head);
                explainHead(head, family, findings);
            }
        }
        if (heads.isEmpty() && !blocking.isEmpty()) {
            findings.add("Sessions block each other in a cycle: a deadlock the server should resolve; see getDeadlocks");
        }
        if (blocking.isEmpty()) {
            findings.add("No blocking right now.");
        }
        result.remove("sessions");
        result.add("heads", heads);
        result.addProperty("blockedSessions", blockedBy.values().stream().filter(s -> !s.isEmpty()).count());
        JsonArray suggestions = new JsonArray();
        if (!heads.isEmpty()) {
            suggestions.add("Find why the head blocker keeps its transaction open (idle session = application did not commit; " +
                "running = long statement: tune it, see explainQuery)");
            suggestions.add("Make the blocking statement touch fewer rows: index the columns of its WHERE/JOIN so it locks rows, not ranges or the table");
            suggestions.add("Keep transactions short; do no user interaction or remote calls inside a transaction");
            if (family == DbFamily.SQLSERVER) {
                suggestions.add("Consider READ_COMMITTED_SNAPSHOT so readers do not wait for writers (needs testing)");
            }
            suggestions.add("As a last resort, killSession on the head blocker (DANGEROUS: its transaction is rolled back; asks for confirmation)");
        }
        result.add("findings", findings);
        result.add("suggestions", suggestions);
    }

    @NotNull
    private static JsonObject tree(
        @NotNull String id,
        @NotNull Map<String, JsonObject> sessions,
        @NotNull Map<String, List<String>> blocking,
        @NotNull Set<String> path
    ) {
        JsonObject node = sessions.containsKey(id) ? sessions.get(id).deepCopy() : new JsonObject();
        node.addProperty("session_id", id);
        node.remove("blocked_by");
        path.add(id);
        JsonArray waiters = new JsonArray();
        int total = 0;
        for (String waiter : blocking.getOrDefault(id, List.of())) {
            if (path.contains(waiter)) {
                continue;
            }
            JsonObject child = tree(waiter, sessions, blocking, path);
            total += 1 + child.get("totalBlocked").getAsInt();
            waiters.add(child);
        }
        path.remove(id);
        node.addProperty("totalBlocked", total);
        if (!waiters.isEmpty()) {
            node.add("blocking", waiters);
        }
        return node;
    }

    private static void explainHead(@NotNull JsonObject head, @NotNull DbFamily family, @NotNull JsonArray findings) {
        String id = str(head, "session_id");
        String who = describeClient(head);
        String status = lower(str(head, "status"));
        String state = lower(str(head, "state"));
        Long txAge = longValue(head, "tx_age_sec");
        String sql = str(head, "sql_text");
        boolean idle = status.equals("sleeping") || state.startsWith("idle") || status.equals("inactive")
            || lower(str(head, "command")).equals("sleep");
        StringBuilder text = new StringBuilder("Head blocker session ").append(id);
        if (!who.isEmpty()) {
            text.append(" (").append(who).append(')');
        }
        if (idle) {
            text.append(" is idle");
            if (txAge != null) {
                text.append(" with a transaction open for ").append(txAge).append(" s");
            } else {
                text.append(" with an open transaction");
            }
            text.append(": the application started a transaction and did not commit or roll back");
        } else {
            text.append(" is running");
            String wait = str(head, "wait_type", "wait_event", "event");
            if (wait != null) {
                text.append(" (waiting on ").append(wait).append(')');
            }
            if (txAge != null) {
                text.append(", transaction open for ").append(txAge).append(" s");
            }
        }
        text.append("; it blocks ").append(head.get("totalBlocked").getAsInt()).append(" session(s)");
        if (sql != null) {
            text.append(". Last statement: ").append(sql.length() > 300 ? sql.substring(0, 300) + "..." : sql);
        }
        findings.add(text.toString());
        String isolation = lower(str(head, "isolation", "trx_isolation_level"));
        if (isolation.contains("serializable") || isolation.contains("repeatable")) {
            findings.add("Session " + id + " runs at " + str(head, "isolation", "trx_isolation_level") +
                ": range/shared locks are held until the end of the transaction");
        }
        Long locks = longValue(head, "lock_count", "trx_rows_locked");
        if (locks != null && locks > 5000) {
            findings.add("Session " + id + " holds " + locks + " locks (lock escalation to a table lock is likely)");
        }
        JsonArray waiters = head.getAsJsonArray("blocking");
        if (waiters != null) {
            for (JsonElement w : waiters) {
                JsonObject waiter = w.getAsJsonObject();
                String resource = str(waiter, "wait_resource", "locked_object", "wait_event");
                Long waitMs = longValue(waiter, "wait_time_ms");
                findings.add("Session " + str(waiter, "session_id") + " waits" +
                    (waitMs == null ? "" : " " + waitMs + " ms") + (resource == null ? "" : " on " + resource) +
                    (str(waiter, "sql_text") == null ? "" : " running: " + truncate(str(waiter, "sql_text"), 200)));
            }
        }
    }

    @NotNull
    private static String describeClient(@NotNull JsonObject row) {
        List<String> parts = new ArrayList<>();
        String program = str(row, "program_name", "application_name", "program");
        String host = str(row, "host_name", "client_addr", "host", "machine");
        String login = str(row, "login_name", "usename", "user", "username");
        if (program != null && !program.isBlank()) {
            parts.add(program);
        }
        if (host != null && !host.isBlank()) {
            parts.add("host " + host);
        }
        if (login != null && !login.isBlank()) {
            parts.add("login " + login);
        }
        return String.join(", ", parts);
    }

    private static void lockSummary(@NotNull JsonObject result) {
        JsonArray rows = rows(result.get("locks"));
        if (rows == null) {
            return;
        }
        Map<String, Long> byObject = new LinkedHashMap<>();
        for (JsonElement r : rows) {
            JsonObject row = r.getAsJsonObject();
            String key = "session " + str(row, "session_id") + " " + str(row, "request_mode", "mode", "lock_mode") + " on " +
                (str(row, "object_name") == null ? str(row, "resource_type", "locktype") : str(row, "object_name"));
            Long count = longValue(row, "lock_count");
            byObject.merge(key, count == null ? 1 : count, Long::sum);
        }
        JsonArray summary = new JsonArray();
        byObject.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(50)
            .forEach(e -> summary.add(e.getKey() + ": " + e.getValue()));
        result.add("summary", summary);
    }

    // ------------------------------------------------------------------ deadlocks

    private static void deadlocks(@NotNull JsonObject result, @NotNull DbFamily family) throws DBException {
        if (family == DbFamily.SQLSERVER) {
            JsonArray rows = rows(result.get("rows"));
            if (rows == null) {
                return;
            }
            JsonArray parsed = new JsonArray();
            for (JsonElement r : rows) {
                JsonObject row = r.getAsJsonObject();
                String xml = str(row, "event_xml");
                if (xml == null) {
                    continue;
                }
                JsonObject deadlock = parseSqlServerDeadlock(xml);
                deadlock.add("time", row.get("event_time"));
                parsed.add(deadlock);
            }
            result.remove("rows");
            result.add("deadlocks", parsed);
        } else if (family == DbFamily.MYSQL || family == DbFamily.MARIADB) {
            JsonArray rows = rows(result.get("rows"));
            if (rows == null || rows.isEmpty()) {
                return;
            }
            String status = str(rows.get(0).getAsJsonObject(), "Status");
            result.remove("rows");
            if (status == null) {
                return;
            }
            int start = status.indexOf("LATEST DETECTED DEADLOCK");
            if (start < 0) {
                result.addProperty("latestDeadlock", "No deadlock since the server started (InnoDB status has no LATEST DETECTED DEADLOCK section)");
                return;
            }
            int end = status.indexOf("------------\nTRANSACTIONS", start);
            result.addProperty("latestDeadlock", status.substring(start, end > start ? end : Math.min(status.length(), start + 20000)));
        }
    }

    /**
     * Oracle: adds the deadlock graph of the trace file each ORA-00060 alert entry names. A dedicated server process writes
     * every deadlock it detects to its own trace file, so the n-th oldest entry of a file is the n-th "DEADLOCK DETECTED"
     * section of that file. A trace file that can't be read (privilege, purged file) leaves the entry without a graph.
     */
    private static void oracleDeadlockGraphs(@NotNull Connection connection, @NotNull JsonObject result) {
        JsonArray rows = rows(result.get("rows"));
        if (rows == null) {
            return;
        }
        Map<String, List<JsonObject>> byFile = new LinkedHashMap<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            JsonObject row = rows.get(i).getAsJsonObject();
            String file = str(row, "trace_file");
            if (file != null) {
                byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(row);
            }
        }
        for (Map.Entry<String, List<JsonObject>> e : byFile.entrySet()) {
            try {
                List<Long> starts = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT line_number FROM v$diag_trace_file_contents WHERE trace_filename = ? " + //$NON-NLS-1$
                        "AND payload LIKE '%DEADLOCK DETECTED%' ORDER BY line_number")) { //$NON-NLS-1$
                    statement.setString(1, e.getKey());
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            starts.add(rs.getLong(1));
                        }
                    }
                }
                List<JsonObject> entries = e.getValue();
                for (int n = 0; n < entries.size() && n < starts.size(); n++) {
                    entries.get(n).addProperty("graph", oracleDeadlockSection(connection, e.getKey(), starts.get(n)));
                }
            } catch (SQLException ex) {
                for (JsonObject entry : e.getValue()) {
                    entry.addProperty("graphError", ex.getMessage() +
                        " (the graph needs SELECT_CATALOG_ROLE or SELECT ANY DICTIONARY for V$DIAG_TRACE_FILE_CONTENTS)");
                }
            }
        }
        result.remove("rows");
        result.add("deadlocks", rows);
    }

    @NotNull
    private static String oracleDeadlockSection(@NotNull Connection connection, @NotNull String file, long start) throws SQLException {
        StringBuilder text = new StringBuilder();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT payload FROM v$diag_trace_file_contents WHERE trace_filename = ? AND line_number >= ? AND line_number < ? " + //$NON-NLS-1$
                "ORDER BY line_number")) { //$NON-NLS-1$
            statement.setString(1, file);
            statement.setLong(2, start);
            statement.setLong(3, start + ORACLE_DEADLOCK_LINES);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next() && text.length() < ORACLE_DEADLOCK_CHARS) {
                    String line = rs.getString(1);
                    if (line == null) {
                        continue;
                    }
                    text.append(line.endsWith("\n") ? line : line + "\n");
                    // The waiting sessions end the part the model needs; the error stack and call stack follow
                    if (line.contains("End of information for waiting sessions")) {
                        break;
                    }
                }
            }
        }
        return truncate(text.toString(), ORACLE_DEADLOCK_CHARS);
    }

    @NotNull
    static JsonObject parseSqlServerDeadlock(@NotNull String xml) throws DBException {
        Document document = PlanParser.parseXml(xml);
        JsonObject result = new JsonObject();
        List<Element> deadlocks = PlanParser.descendants(document.getDocumentElement(), "deadlock");
        if (deadlocks.isEmpty()) {
            result.addProperty("raw", xml.length() > 4000 ? xml.substring(0, 4000) + "..." : xml);
            return result;
        }
        Element deadlock = deadlocks.getFirst();
        JsonArray victims = new JsonArray();
        for (Element victim : PlanParser.descendants(deadlock, "victimProcess")) {
            victims.add(victim.getAttribute("id"));
        }
        result.add("victims", victims);
        JsonArray processes = new JsonArray();
        for (Element process : PlanParser.descendants(deadlock, "process")) {
            JsonObject p = new JsonObject();
            for (String name : List.of("id", "spid", "loginname", "hostname", "clientapp", "isolationlevel", "waitresource",
                "waittime", "lockMode", "transactionname", "lasttranstarted", "lastbatchstarted", "status", "trancount", "currentdbname")) {
                if (process.hasAttribute(name)) {
                    p.addProperty(name, process.getAttribute(name));
                }
            }
            JsonArray frames = new JsonArray();
            // Only the frames of the process's own execution stack (system_health events can carry other frame elements)
            Element stack = PlanParser.child(process, "executionStack");
            for (Element frame : stack == null ? List.<Element>of() : PlanParser.children(stack, "frame")) {
                if (!frame.hasAttribute("procname") && frame.getTextContent().isBlank()) {
                    continue;
                }
                JsonObject f = new JsonObject();
                f.addProperty("procname", frame.getAttribute("procname"));
                f.addProperty("line", frame.getAttribute("line"));
                f.addProperty("sql", truncate(frame.getTextContent().strip(), 1000));
                frames.add(f);
            }
            if (!frames.isEmpty()) {
                p.add("executionStack", frames);
            }
            Element input = PlanParser.child(process, "inputbuf");
            if (input != null) {
                p.addProperty("inputbuf", truncate(input.getTextContent().strip(), 2000));
            }
            processes.add(p);
        }
        result.add("processes", processes);
        JsonArray resources = new JsonArray();
        Element resourceList = PlanParser.child(deadlock, "resource-list");
        if (resourceList != null) {
            for (Element resource : PlanParser.children(resourceList, null)) {
                JsonObject res = new JsonObject();
                res.addProperty("type", resource.getLocalName());
                for (String name : List.of("objectname", "indexname", "mode", "dbid", "hobtid", "fileid", "pageid")) {
                    if (resource.hasAttribute(name)) {
                        res.addProperty(name, resource.getAttribute(name));
                    }
                }
                JsonArray owners = new JsonArray();
                for (Element owner : PlanParser.descendants(resource, "owner")) {
                    owners.add(owner.getAttribute("id") + " holds " + owner.getAttribute("mode"));
                }
                JsonArray waiters = new JsonArray();
                for (Element waiter : PlanParser.descendants(resource, "waiter")) {
                    waiters.add(waiter.getAttribute("id") + " requests " + waiter.getAttribute("mode"));
                }
                res.add("owners", owners);
                res.add("waiters", waiters);
                resources.add(res);
            }
        }
        result.add("resources", resources);
        return result;
    }

    // ------------------------------------------------------------------ cached plans

    private static void cachedPlans(@NotNull JsonObject result) {
        JsonArray rows = rows(result.get("rows"));
        if (rows == null) {
            return;
        }
        for (JsonElement r : rows) {
            JsonObject row = r.getAsJsonObject();
            JsonElement xml = row.remove("query_plan");
            if (xml == null || xml.isJsonNull()) {
                continue;
            }
            String text = xml.getAsString();
            if (text.endsWith("...")) {
                row.addProperty("planError", "plan XML was cut at the cell limit; pass a larger maxCellChars");
                continue;
            }
            try {
                row.add("plan", PlanParser.parseSqlServer(text));
            } catch (DBException e) {
                row.addProperty("planError", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ Query Store

    /**
     * The Query Store options row becomes one object, and a Query Store that records nothing is said in a sentence: its views
     * then answer with old or no rows, which must not read as "nothing expensive ran".
     */
    private static void queryStoreState(@NotNull JsonObject result) {
        JsonArray rows = rows(result.get("queryStore"));
        if (rows == null) {
            return;
        }
        if (rows.isEmpty()) {
            // master and tempdb have no Query Store options row (seen on SQL Server 2022)
            result.addProperty("queryStoreOff", true);
            result.addProperty("message", "This database has no Query Store (master and tempdb have none): pass the user " +
                "database as the 'database' argument.");
            return;
        }
        JsonObject options = rows.get(0).getAsJsonObject();
        result.add("queryStore", options);
        String state = str(options, "actual_state_desc");
        String database = str(options, "database_name");
        if ("OFF".equalsIgnoreCase(state)) {
            result.addProperty("queryStoreOff", true);
            result.addProperty("message", "Query Store is OFF in database " + database + ": it has recorded nothing, so these results " +
                "are empty. It is turned on with ALTER DATABASE [" + database + "] SET QUERY_STORE = ON (ALTER permission on the " +
                "database; ask the user), and then needs the workload to run. Until then getTopQueries reads the plan cache.");
        } else if ("READ_ONLY".equalsIgnoreCase(state)) {
            result.addProperty("message", "Query Store is READ_ONLY in database " + database + " (readonly_reason " +
                str(options, "readonly_reason") + "; 65536 = max_storage_size_mb reached): it keeps old data but records nothing " +
                "new, so recent activity is missing.");
        } else if ("ERROR".equalsIgnoreCase(state)) {
            result.addProperty("message", "Query Store is in ERROR state in database " + database + ": its data may be incomplete.");
        }
    }

    // ------------------------------------------------------------------ error log

    private static void errorLogTail(@NotNull JsonObject result, @NotNull Map<String, Object> args) {
        JsonArray rows = rows(result.get("rows"));
        if (rows == null || rows.isEmpty() || !rows.get(0).getAsJsonObject().has("tail")) {
            return;
        }
        JsonObject row = rows.get(0).getAsJsonObject();
        String tail = str(row, "tail");
        JsonArray lines = new JsonArray();
        if (tail != null) {
            String contains = AgentArgs.getString(args, "contains");
            int limit = AgentArgs.getInt(args, AgentLimits.PARAM_LIMIT, AgentLimits.DEFAULT_ROWS);
            String[] all = tail.split("\\R");
            Deque<String> kept = new ArrayDeque<>();
            for (String line : all) {
                if (contains == null || line.toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT))) {
                    kept.addLast(line);
                    if (kept.size() > limit) {
                        kept.removeFirst();
                    }
                }
            }
            kept.forEach(lines::add);
        }
        row.remove("tail");
        row.add("lines", lines);
    }

    // ------------------------------------------------------------------ dependencies

    // Breadth-first over "uses" edges up to the requested depth (1..3); "usedBy" edges are read for the start object only
    private static void dependencyGraph(
        @NotNull Connection connection,
        @NotNull AgentSql.Runner runner,
        @NotNull DiagnosticSqlTool tool,
        @NotNull AIToolCallContext context,
        @NotNull DiagnosticScript.Block block,
        @NotNull Map<String, Object> args,
        @NotNull AgentLimits limits,
        @NotNull JsonObject result
    ) throws DBException {
        int depth = Math.max(1, Math.min(3, AgentArgs.getInt(args, "depth", 1)));
        String start = AgentArgs.getString(args, "object");
        Map<String, JsonObject> nodes = new LinkedHashMap<>();
        Set<String> edges = new LinkedHashSet<>();
        JsonArray edgeList = new JsonArray();
        addEdges(result, nodes, edges, edgeList);
        Set<String> visited = new LinkedHashSet<>();
        if (start != null) {
            visited.add(start.toLowerCase(Locale.ROOT));
        }
        List<String> frontier = usesTargets(edgeList, visited);
        for (int level = 2; level <= depth && !frontier.isEmpty() && nodes.size() < MAX_GRAPH_NODES; level++) {
            List<String> next = new ArrayList<>();
            for (String object : frontier) {
                if (!visited.add(object.toLowerCase(Locale.ROOT)) || nodes.size() >= MAX_GRAPH_NODES) {
                    continue;
                }
                Map<String, Object> nested = new LinkedHashMap<>(args);
                nested.put("object", object);
                JsonObject more = tool.runBlockQuietly(connection, runner, context, block, nested, limits);
                JsonArray before = edgeList.deepCopy();
                addEdges(more, nodes, edges, edgeList);
                JsonArray added = new JsonArray();
                for (int i = before.size(); i < edgeList.size(); i++) {
                    added.add(edgeList.get(i));
                }
                next.addAll(usesTargets(added, visited));
            }
            frontier = next;
        }
        for (String key : List.copyOf(result.keySet())) {
            result.remove(key);
        }
        JsonArray nodeList = new JsonArray();
        nodes.values().forEach(nodeList::add);
        result.add("nodes", nodeList);
        result.add("edges", edgeList);
        if (nodes.size() >= MAX_GRAPH_NODES) {
            result.addProperty("truncated", "graph cut at " + MAX_GRAPH_NODES + " nodes");
        }
    }

    private static void addEdges(
        @NotNull JsonObject result,
        @NotNull Map<String, JsonObject> nodes,
        @NotNull Set<String> edges,
        @NotNull JsonArray edgeList
    ) {
        for (Map.Entry<String, JsonElement> e : result.entrySet()) {
            JsonArray rows = rows(e.getValue());
            if (rows == null) {
                continue;
            }
            for (JsonElement r : rows) {
                JsonObject row = r.getAsJsonObject();
                String from = str(row, "from_object");
                String to = str(row, "to_object");
                if (from == null || to == null) {
                    continue;
                }
                addNode(nodes, from, str(row, "from_type"));
                addNode(nodes, to, str(row, "to_type"));
                String kind = str(row, "kind") == null ? "reference" : str(row, "kind");
                String key = from.toLowerCase(Locale.ROOT) + "->" + to.toLowerCase(Locale.ROOT) + ":" + kind;
                if (edges.add(key)) {
                    JsonObject edge = new JsonObject();
                    edge.addProperty("from", from);
                    edge.addProperty("to", to);
                    edge.addProperty("kind", kind);
                    edgeList.add(edge);
                }
            }
        }
    }

    private static void addNode(@NotNull Map<String, JsonObject> nodes, @NotNull String name, @Nullable String type) {
        JsonObject node = nodes.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> {
            JsonObject n = new JsonObject();
            n.addProperty("name", name);
            return n;
        });
        if (type != null && !node.has("type")) {
            node.addProperty("type", type);
        }
    }

    // Targets of the edges whose source was already visited (the start object or an expanded one)
    @NotNull
    private static List<String> usesTargets(@NotNull JsonArray edgeList, @NotNull Set<String> visited) {
        List<String> result = new ArrayList<>();
        for (JsonElement e : edgeList) {
            JsonObject edge = e.getAsJsonObject();
            String from = edge.get("from").getAsString();
            String to = edge.get("to").getAsString();
            if (visited.stream().anyMatch(v -> from.toLowerCase(Locale.ROOT).endsWith(v)) &&
                !visited.contains(to.toLowerCase(Locale.ROOT))) {
                result.add(to);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    @Nullable
    static JsonArray rows(@Nullable JsonElement table) {
        if (table == null || !table.isJsonObject()) {
            return null;
        }
        JsonElement rows = table.getAsJsonObject().get("rows");
        return rows != null && rows.isJsonArray() ? rows.getAsJsonArray() : null;
    }

    @Nullable
    static String str(@NotNull JsonObject row, @NotNull String... names) {
        for (String name : names) {
            JsonElement value = row.get(name);
            if (value != null && !value.isJsonNull()) {
                return value.isJsonPrimitive() ? value.getAsString() : value.toString();
            }
        }
        return null;
    }

    @Nullable
    private static Long longValue(@NotNull JsonObject row, @NotNull String... names) {
        for (String name : names) {
            JsonElement value = row.get(name);
            if (value instanceof JsonPrimitive p) {
                try {
                    return (long) Double.parseDouble(p.getAsString());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    @NotNull
    private static String lower(@Nullable String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    @NotNull
    private static String truncate(@Nullable String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }
}
