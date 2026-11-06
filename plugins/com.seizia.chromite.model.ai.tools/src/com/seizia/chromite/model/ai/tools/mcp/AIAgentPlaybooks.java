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
package com.seizia.chromite.model.ai.tools.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The DBA playbooks of docs/chromite/ai-agent.md "시나리오 플레이북" as prompts (MCP {@code prompts/get}; the chat in phase 2).
 *
 * 설계: 플레이북 글은 모델에게 주는 지시라 영어로, 자원 파일({@code playbooks/<이름>.md})에 둔다. 설계 문서의 한국어 플레이북과 단계·도구·멈춤 지점이
 * 같아야 한다. 인수({@code {connection}} 등)는 글에 그대로 끼워 넣는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public final class AIAgentPlaybooks {

    /**
     * Server instructions of the MCP initialize result.
     */
    public static final String INSTRUCTIONS =
        "Chromite gives you the database connections the user configured in the Chromite desktop app. " +
        "Start with listConnections. On a connection, call getServerInfo (version picks the system views) and probePermissions " +
        "(which checks this login can run) before diagnosing. Prefer specific tools (getBlockingChain, explainQuery, getTopQueries, ...) " +
        "and use runReadOnlySql for anything else that only reads. Tools marked WRITE or DANGEROUS ask the user in the Chromite app " +
        "and may be blocked by the user's policy (getToolPolicy shows it); show the SQL/DDL to the user before calling them. " +
        "Never commit or roll back a transaction unless the user asked for it. Prompts describe step-by-step playbooks for common DBA tasks.";

    private record Playbook(@NotNull String name, @NotNull String title, @NotNull String description, @NotNull List<String> arguments) {
    }

    private static final List<Playbook> PLAYBOOKS = List.of(
        new Playbook("tune-slow-query", "Tune a slow query",
            "Analyze a slow query (plan, data volumes, indexes), propose a rewrite or index, compare plans, apply only with the user's consent",
            List.of("connection", "sql")),
        new Playbook("database-health", "Database health check",
            "Check sessions, blocking, waits, long queries, space, backups, job failures, error log and configuration",
            List.of("connection")),
        new Playbook("slow-query-root-cause", "Root cause of a slow query",
            "Find why a query is slow without changing anything; ranked causes with evidence",
            List.of("connection", "sql")),
        new Playbook("library-monitoring", "System status from the Query Library",
            "Run the monitoring queries of a Query Library folder and judge them against their thresholds",
            List.of("connection", "folder")),
        new Playbook("blocking-root-cause", "Blocking and lock root cause",
            "Explain who blocks whom and why, with the head blocker's transaction and SQL, and suggest fixes",
            List.of("connection")),
        new Playbook("routine-review", "Procedure, function or trigger review",
            "Review a routine's logic and performance with line-numbered evidence",
            List.of("connection", "object"))
    );

    private AIAgentPlaybooks() {
    }

    @NotNull
    static JsonObject list() {
        JsonArray prompts = new JsonArray();
        for (Playbook playbook : PLAYBOOKS) {
            JsonObject prompt = new JsonObject();
            prompt.addProperty("name", playbook.name());
            prompt.addProperty("title", playbook.title());
            prompt.addProperty("description", playbook.description());
            JsonArray arguments = new JsonArray();
            for (String argument : playbook.arguments()) {
                JsonObject a = new JsonObject();
                a.addProperty("name", argument);
                a.addProperty("required", !argument.equals("folder"));
                arguments.add(a);
            }
            prompt.add("arguments", arguments);
            prompts.add(prompt);
        }
        JsonObject result = new JsonObject();
        result.add("prompts", prompts);
        return result;
    }

    /**
     * {@code prompts/get}: the playbook text with the arguments filled in, or null for an unknown name.
     */
    @Nullable
    static JsonObject get(@NotNull JsonObject params) throws IOException {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        Playbook playbook = PLAYBOOKS.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
        if (playbook == null) {
            return null;
        }
        String text = getText(playbook.name());
        if (params.has("arguments") && params.get("arguments").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : params.getAsJsonObject("arguments").entrySet()) {
                text = text.replace("{" + e.getKey() + "}", e.getValue().isJsonNull() ? "" : e.getValue().getAsString());
            }
        }
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);
        JsonArray messages = new JsonArray();
        messages.add(message);
        JsonObject result = new JsonObject();
        result.addProperty("description", playbook.description());
        result.add("messages", messages);
        return result;
    }

    /**
     * The raw playbook text (placeholders not filled), for the chat in phase 2.
     */
    @NotNull
    public static String getText(@NotNull String name) throws IOException {
        try (InputStream in = AIAgentPlaybooks.class.getResourceAsStream("playbooks/" + name + ".md")) {
            if (in == null) {
                throw new IOException("Playbook " + name + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @NotNull
    public static List<String> getNames() {
        return PLAYBOOKS.stream().map(Playbook::name).toList();
    }
}
