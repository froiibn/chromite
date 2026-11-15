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
package com.seizia.chromite.model.ai.agent;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Audit log of every agent tool call (allowed or not): {@code <workspace>/.metadata/chromite-ai/audit.jsonl}.
 *
 * 설계:
 * <ul>
 *     <li>한 호출 = JSON 한 줄 덧붙이기. 앱이 비정상 종료되어도 그때까지의 기록이 남고, 파일 전체를 다시 쓰지 않는다
 *     (Swing 쿼리 기록 {@code QueryHistory}와 같은 방식).</li>
 *     <li>10 MB를 넘으면 {@code audit.1.jsonl}로 바꾸고 새로 시작한다. 하나만 보관해 디스크를 무한히 쓰지 않는다.</li>
 *     <li>인수의 비밀은 {@link #maskSecrets}로 지운다. 확인 요청에도 같은 함수를 쓴다.</li>
 *     <li>트랜잭션 질문에 Commit / Roll back으로 답했으면 그 답을 {@code transactionsBeforeCall}로 남긴다. 그런 호출의 정책 판정은 대개
 *     AUTO라, 이것이 없으면 사용자가 물음을 받고 무엇을 골랐는지 기록에 없다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "감사 기록")
 * - 2026-11-08 트랜잭션 질문의 답(transactionsBeforeCall)을 기록함 (docs/chromite/ai-agent.md "실제 API 확인")
 * - 2026-11-15 {@code tool}은 지금 도구 ID이고, 옛 ID로 부른 호출은 {@code calledAs}, 합친 도구의 호출은 {@code operation}을 함께 남김.
 *   예전 기록(옛 ID)은 그대로 읽힌다: 형식은 필드를 더했을 뿐이다 (docs/chromite/ai-agent.md "감사 기록")
 */
public final class AIToolAuditLog {

    private static final Log log = Log.getLog(AIToolAuditLog.class);

    public static final String FILE_NAME = "audit.jsonl"; //$NON-NLS-1$
    private static final String OLD_FILE_NAME = "audit.1.jsonl"; //$NON-NLS-1$
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int MAX_ARGUMENT_CHARS = 2000;
    private static final String MASK = "***"; //$NON-NLS-1$
    private static final String[] SECRET_NAME_PARTS = {"password", "secret", "token", "key", "credential", "pwd"}; //$NON-NLS-1$

    private static final Gson GSON = new Gson();
    private static final AIToolAuditLog instance = new AIToolAuditLog();

    /**
     * One audit record.
     */
    public record Entry(
        @NotNull String time,
        @NotNull AIToolClient client,
        @NotNull String tool,
        @Nullable String calledAs,
        @Nullable String operation,
        @NotNull AIToolAccess access,
        @Nullable String connectionId,
        @Nullable String connectionName,
        @NotNull Map<String, Object> arguments,
        @Nullable AIToolPermission permission,
        @Nullable String reason,
        @NotNull AIToolOutcome outcome,
        long durationMs,
        @Nullable String error,
        @Nullable String transactionAction,
        int transactionCount
    ) {
        @NotNull
        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("time", time);
            json.addProperty("client", client.name());
            json.addProperty("tool", tool);
            if (operation != null) {
                json.addProperty("operation", operation);
            }
            if (calledAs != null) {
                json.addProperty("calledAs", calledAs);
            }
            json.addProperty("access", access.name());
            if (connectionId != null) {
                json.addProperty("connectionId", connectionId);
                json.addProperty("connectionName", connectionName);
            }
            json.add("arguments", GSON.toJsonTree(arguments));
            if (permission != null) {
                json.addProperty("permission", permission.name());
            }
            if (reason != null) {
                json.addProperty("reason", reason);
            }
            json.addProperty("outcome", outcome.name());
            json.addProperty("durationMs", durationMs);
            if (error != null) {
                json.addProperty("error", error);
            }
            if (transactionAction != null) {
                JsonObject transactions = new JsonObject();
                transactions.addProperty("action", transactionAction);
                transactions.addProperty("count", transactionCount);
                json.add(AIToolExecutor.RESULT_TRANSACTIONS_BEFORE_CALL, transactions);
            }
            return json;
        }
    }

    private AIToolAuditLog() {
    }

    @NotNull
    public static AIToolAuditLog getInstance() {
        return instance;
    }

    @NotNull
    public Path getFile() {
        return AIAgentServices.getAgentFolder().resolve(FILE_NAME);
    }

    /**
     * Builds an entry with the current time and masked arguments.
     *
     * @param tool              the current tool id
     * @param calledAs          the former tool id the call used (an alias), or null
     * @param operation         the operation of a merged tool's call, or null
     * @param transactionAction the user's answer to a transaction question, carried out before the call
     *                          ({@code COMMITTED} or {@code ROLLED_BACK}), or null when there was no such question
     * @param transactionCount  number of uncommitted transactions that answer ended
     */
    @NotNull
    public static Entry newEntry(
        @NotNull AIToolClient client,
        @NotNull String tool,
        @Nullable String calledAs,
        @Nullable String operation,
        @NotNull AIToolAccess access,
        @Nullable DBPDataSourceContainer connection,
        @NotNull Map<String, Object> arguments,
        @Nullable AIToolDecision decision,
        @NotNull AIToolOutcome outcome,
        long durationMs,
        @Nullable String error,
        @Nullable String transactionAction,
        int transactionCount
    ) {
        return new Entry(
            OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            client,
            tool,
            calledAs,
            operation,
            access,
            connection == null ? null : connection.getId(),
            connection == null ? null : connection.getName(),
            maskSecrets(arguments),
            decision == null ? null : decision.permission(),
            decision == null ? null : decision.reason(),
            outcome,
            durationMs,
            error,
            transactionAction,
            transactionCount
        );
    }

    public synchronized void append(@NotNull Entry entry) {
        Path file = getFile();
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > MAX_FILE_SIZE) {
                Files.move(file, file.resolveSibling(OLD_FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, GSON.toJson(entry.toJson()) + "\n", StandardCharsets.UTF_8, //$NON-NLS-1$
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Can't write the AI tool audit log " + file, e);
        }
    }

    /**
     * The newest records first, as JSON objects (for an audit page).
     */
    @NotNull
    public synchronized List<JsonObject> readRecent(int limit) {
        Path file = getFile();
        List<JsonObject> result = new ArrayList<>();
        if (!Files.exists(file)) {
            return result;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && result.size() < limit; i--) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    JsonElement element = JsonParser.parseString(line);
                    if (element.isJsonObject()) {
                        result.add(element.getAsJsonObject());
                    }
                } catch (RuntimeException e) {
                    // A torn last line after a crash: skip it
                    log.debug("Skipping a broken audit line");
                }
            }
        } catch (IOException e) {
            log.warn("Can't read the AI tool audit log " + file, e);
        }
        return result;
    }

    /**
     * A copy of the arguments with secret-looking names masked and long strings cut.
     */
    @NotNull
    public static Map<String, Object> maskSecrets(@NotNull Map<String, Object> arguments) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : arguments.entrySet()) {
            Object value = e.getValue();
            if (isSecretName(e.getKey())) {
                value = MASK;
            } else if (value instanceof String text && text.length() > MAX_ARGUMENT_CHARS) {
                value = text.substring(0, MAX_ARGUMENT_CHARS) + "... (" + text.length() + " chars)"; //$NON-NLS-1$ //$NON-NLS-2$
            } else if (value instanceof Map<?, ?> map) {
                Map<String, Object> nested = new LinkedHashMap<>();
                map.forEach((k, v) -> nested.put(String.valueOf(k), v));
                value = maskSecrets(nested);
            }
            result.put(e.getKey(), value);
        }
        return result;
    }

    private static boolean isSecretName(@NotNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String part : SECRET_NAME_PARTS) {
            // "key" alone would hit legitimate arguments such as keyColumns or the row key of updateRows
            if (part.equals("key")) { //$NON-NLS-1$
                if (lower.equals("apikey") || lower.endsWith("_key") && !lower.equals("row_key") || lower.equals("privatekey")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    return true;
                }
            } else if (lower.contains(part)) {
                return true;
            }
        }
        return false;
    }
}
