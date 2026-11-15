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
package com.seizia.chromite.model.ai.tools.chat;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.seizia.chromite.model.ai.AIFunctionCall;
import com.seizia.chromite.model.ai.AIFunctionResult;
import com.seizia.chromite.model.ai.AIFunctionType;
import com.seizia.chromite.model.ai.AIMessage;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.ai.agent.AIToolCatalog;
import com.seizia.chromite.model.ai.tools.AgentJson;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One conversation of the in-app AI assistant: what the user and the model said, the tool calls with their results, and the
 * provider, model, connection and database the conversation works with.
 *
 * 설계:
 * <ul>
 *     <li>대화는 화면에 보이는 항목({@link Entry}) 목록 하나로 둔다. 모델에 보내는 메시지({@link #toModelMessages})는 이 목록에서 다시 만든다.
 *     기존 채팅 모델({@code AIChatConversation}, {@code AIChatSession})은 QM 저장소·할당량·DB 문맥 스냅숏을 함께 쓰는데, 에이전트 채팅은 연결을
 *     도구 인수로 넘기고 정책·확인·감사는 {@code AIToolExecutor}가 맡으므로 그 기계 장치가 필요 없다.</li>
 *     <li>도구 항목은 모델이 보낸 함수 이름·인수·엔진 메타데이터(Anthropic {@code tool_use_id}, OpenAI {@code call_id})와 결과 글을 함께 저장한다.
 *     다음 요청에서 같은 호출과 결과 짝을 다시 보내야 하기 때문이다.</li>
 *     <li>저장은 {@link AIAgentChatStore}(작업 공간의 JSON 파일 하나)다. 항목 목록은 작업 스레드가 덧붙이고 화면이 읽으므로
 *     {@link CopyOnWriteArrayList}다.</li>
 *     <li>요청 크기: 요청마다 대화 전체를 다시 보내므로 긴 대화는 문맥 한도에 걸린다. 모델 메시지를 만들 때 크기 예산
 *     ({@link #HISTORY_BUDGET_CHARS})을 넘으면 최근 차례를 남기고 오래된 도구 결과를 앞부분 미리 보기로 줄이고, 그래도 넘으면 오래된 차례를 통째로 뺀다
 *     ({@link #toModelMessages}). 저장한 대화와 화면은 그대로이고 보내는 사본만 줄인다. 크기는 글자 수로 잰다(토큰 수는 제공자·모델마다 달라
 *     요청 전에 알 수 없다. 영어·JSON은 대략 4글자 = 1토큰).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "2단계")
 * - 2026-11-08 기록 크기 예산: 오래된 도구 결과 줄이기, 오래된 차례 빼기, 호출·결과 짝은 나누지 않음 (docs/chromite/ai-agent.md "요청 크기")
 * - 2026-11-08 실행 중인 도구의 진행 글({@link Entry#getProgress}, 저장하지 않음): 백업·복원 도구
 * - 2026-11-12 대화가 찾은 도구({@link #getFoundTools}, 저장됨. 예전 대화는 없음으로 읽힘), 보낼 기록의 토큰 어림, 도구 없이 보내는 기록
 *   (앱 변경 #64, docs/chromite/ai-agent.md "도구 묶음")
 * - 2026-11-15 옛 도구 ID로 저장된 호출은 지금 도구 이름·인수로 보내고, 찾은 도구의 옛 ID는 지금 ID로 읽음. 저장된 대화와 화면은 그대로다
 *   (도구 정리, docs/chromite/ai-agent.md "별칭")
 */
public final class AIAgentChat {

    /**
     * History characters one request carries at most (about 15,000 tokens of JSON and English): with the system prompt and
     * the tool definitions a request stays far below the 128k-token context of current hosted models, and within reach of
     * the 32k contexts of local models.
     */
    public static final int HISTORY_BUDGET_CHARS = 60_000;
    /**
     * Turns (a prompt and everything up to the next one) kept whole: the current turn and the one before it.
     */
    public static final int KEEP_TURNS = 2;
    // Newest tool results never shortened, even when one long turn alone is over the budget
    private static final int KEEP_RESULTS = 4;
    private static final int SHORT_RESULT_CHARS = 600;
    private static final int PREVIEW_CHARS = 300;
    private static final String RESULT_HEADER = " was completed.\n"; //$NON-NLS-1$

    /**
     * Kind of a conversation entry.
     */
    public enum Kind {
        /** The user's prompt. */
        USER,
        /** The model's text. */
        ASSISTANT,
        /** A tool call and its result. */
        TOOL,
        /** An error of the provider or of the conversation itself (not sent to the model). */
        ERROR
    }

    /**
     * One entry; fields that do not apply to the kind stay null.
     */
    public static final class Entry {
        private final String id = UUID.randomUUID().toString();
        private Kind kind;
        private long time;
        // USER: the text sent to the model; ASSISTANT: the (streamed) text; ERROR: the message
        private String text;
        // USER: a short label shown instead of a long playbook prompt
        @Nullable
        private String label;
        // TOOL
        @Nullable
        private String function;
        @Nullable
        private Map<String, Object> arguments;
        @Nullable
        private Map<String, String> metadata;
        @Nullable
        private String access;
        @Nullable
        private String result;
        @Nullable
        private String outcome;
        private long durationMs;
        // TOOL while it runs: the tool's latest progress text; not saved (a saved conversation has no running tools)
        @Nullable
        private transient volatile String progress;

        Entry() {
        }

        Entry(@NotNull Kind kind, @NotNull String text) {
            this.kind = kind;
            this.text = text;
            this.time = System.currentTimeMillis();
        }

        @NotNull
        public String getId() {
            return id;
        }

        @NotNull
        public Kind getKind() {
            return kind;
        }

        public long getTime() {
            return time;
        }

        @NotNull
        public String getText() {
            return text == null ? "" : text;
        }

        void appendText(@NotNull String delta) {
            text = getText() + delta;
        }

        @Nullable
        public String getLabel() {
            return label;
        }

        /**
         * Function name as the model called it ({@code db_<tool id>}).
         */
        @Nullable
        public String getFunction() {
            return function;
        }

        @NotNull
        public Map<String, Object> getArguments() {
            return arguments == null ? Map.of() : arguments;
        }

        /**
         * Effective class of the call (READ, WRITE, DANGEROUS), known once the policy ran.
         */
        @Nullable
        public String getAccess() {
            return access;
        }

        /**
         * JSON text the tool returned (or the error JSON); null while the tool runs.
         */
        @Nullable
        public String getResult() {
            return result;
        }

        /**
         * {@code AIToolOutcome} name; null while the tool runs.
         */
        @Nullable
        public String getOutcome() {
            return outcome;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public boolean isRunning() {
            return kind == Kind.TOOL && outcome == null;
        }

        /**
         * Latest progress text of the running tool ({@code AIToolCallContext.reportProgress}), or null.
         */
        @Nullable
        public String getProgress() {
            return progress;
        }

        void setProgress(@Nullable String progress) {
            this.progress = progress;
        }

        void finishTool(@NotNull String outcome, @Nullable String access, @NotNull String result, long durationMs) {
            this.outcome = outcome;
            this.access = access;
            this.result = result;
            this.durationMs = durationMs;
            this.progress = null;
        }
    }

    private String id;
    private String title;
    private long created;
    private long updated;
    @Nullable
    private String engineId;
    @Nullable
    private String model;
    @Nullable
    private String connectionId;
    @Nullable
    private String database;
    // Tools outside the provider's tool set that findTools returned or the model called; null in conversations saved before
    @Nullable
    private List<String> foundTools;
    private List<Entry> entries = new CopyOnWriteArrayList<>();

    AIAgentChat() {
    }

    /**
     * A new empty conversation.
     */
    @NotNull
    public static AIAgentChat create(@NotNull String title) {
        AIAgentChat chat = new AIAgentChat();
        chat.id = UUID.randomUUID().toString();
        chat.title = title;
        chat.created = System.currentTimeMillis();
        chat.updated = chat.created;
        return chat;
    }

    @NotNull
    public String getId() {
        return id;
    }

    @NotNull
    public String getTitle() {
        return title == null ? "" : title;
    }

    public void setTitle(@NotNull String title) {
        this.title = title;
    }

    public long getCreated() {
        return created;
    }

    public long getUpdated() {
        return updated;
    }

    void touch() {
        updated = System.currentTimeMillis();
    }

    /**
     * AI engine (provider) id: {@code anthropic}, {@code openai} or {@code ollama}.
     */
    @Nullable
    public String getEngineId() {
        return engineId;
    }

    public void setEngineId(@Nullable String engineId) {
        this.engineId = engineId;
    }

    @Nullable
    public String getModel() {
        return model;
    }

    public void setModel(@Nullable String model) {
        this.model = model;
    }

    /**
     * Data source ID of the conversation's connection: the default {@code connection} argument of the tools.
     */
    @Nullable
    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(@Nullable String connectionId) {
        this.connectionId = connectionId;
    }

    /**
     * Database (catalog or schema) the user works in, told to the model; null for the connection default.
     */
    @Nullable
    public String getDatabase() {
        return database;
    }

    public void setDatabase(@Nullable String database) {
        this.database = database;
    }

    /**
     * IDs of the tools this conversation reached outside its tool set (findTools results, tools the model or the prompt named):
     * later requests send them too (docs/chromite/ai-agent.md "도구 묶음").
     */
    @NotNull
    public List<String> getFoundTools() {
        if (foundTools == null) {
            return List.of();
        }
        // Conversations saved before a tool was renamed or merged keep its former id
        List<String> ids = new ArrayList<>();
        for (String id : foundTools) {
            String current = canonicalId(id);
            if (!ids.contains(current)) {
                ids.add(current);
            }
        }
        return ids;
    }

    @NotNull
    private static String canonicalId(@NotNull String id) {
        try {
            return AIToolCatalog.canonicalId(id);
        } catch (RuntimeException e) {
            // No tool registry (outside the application): the id as saved
            return id;
        }
    }

    /**
     * Adds a tool to {@link #getFoundTools}; false if it was there.
     */
    boolean addFoundTool(@NotNull String toolId) {
        if (foundTools == null) {
            foundTools = new ArrayList<>();
        }
        if (getFoundTools().contains(canonicalId(toolId))) {
            return false;
        }
        foundTools.add(toolId);
        return true;
    }

    @NotNull
    public List<Entry> getEntries() {
        if (entries == null) {
            entries = new CopyOnWriteArrayList<>();
        }
        return entries;
    }

    public boolean isEmpty() {
        return getEntries().isEmpty();
    }

    /**
     * Adds the user's prompt.
     *
     * @param label short text shown instead of the prompt (a playbook), or null to show the prompt
     */
    @NotNull
    public Entry addUser(@NotNull String text, @Nullable String label) {
        Entry entry = new Entry(Kind.USER, text);
        entry.label = label;
        getEntries().add(entry);
        touch();
        return entry;
    }

    @NotNull
    Entry addAssistant() {
        Entry entry = new Entry(Kind.ASSISTANT, "");
        getEntries().add(entry);
        return entry;
    }

    @NotNull
    Entry addError(@NotNull String message) {
        Entry entry = new Entry(Kind.ERROR, message);
        getEntries().add(entry);
        return entry;
    }

    @NotNull
    Entry addTool(@NotNull AIFunctionCall call) {
        Entry entry = new Entry(Kind.TOOL, "");
        entry.function = call.getFunctionName();
        entry.arguments = new LinkedHashMap<>(call.getArguments());
        entry.metadata = call.getMessageMetadata() == null ? null : new LinkedHashMap<>(call.getMessageMetadata());
        getEntries().add(entry);
        return entry;
    }

    /**
     * The history of one request and what was left out of it to fit the budget.
     *
     * @param fullChars        size of the whole conversation as messages (characters of texts, arguments and results)
     * @param sentChars        size of the messages sent
     * @param shortenedResults tool results replaced by a short preview
     * @param droppedTurns     oldest turns (a prompt and everything up to the next prompt) left out
     * @param sentTokens       estimated tokens of the messages sent ({@link AIAgentToolSets#estimateTokens})
     */
    public record ModelHistory(
        @NotNull List<AIMessage> messages,
        int fullChars,
        int sentChars,
        int shortenedResults,
        int droppedTurns,
        int sentTokens
    ) {
    }

    @NotNull
    ModelHistory toModelMessages(int budgetChars, int keepTurns) {
        return toModelMessages(budgetChars, keepTurns, true);
    }

    /**
     * The conversation as model messages, within a size budget: prompts, the model's texts and tool call/result pairs.
     * Errors are not sent. A tool call without a result (the app stopped while it ran) is sent as canceled, because every
     * tool call needs its result.
     * <p>
     * Over the budget, in this order until it fits: the results of tool calls before the last {@code keepTurns} turns are
     * replaced by a short preview (oldest first); then the oldest turns before them are left out whole; then the results in
     * the kept turns are shortened too, except the newest {@link #KEEP_RESULTS}. A call and its result are one message,
     * and a turn is left out whole, so a call is never sent without its result (or a result without its call) and the
     * history still starts with the user's prompt.
     *
     * @param withTools false when the request carries no tools (the provider can't call them): tool calls and results are left
     *                  out, because a server without tool calls may reject them
     */
    @NotNull
    ModelHistory toModelMessages(int budgetChars, int keepTurns, boolean withTools) {
        List<List<Entry>> turns = new ArrayList<>();
        for (Entry entry : getEntries()) {
            boolean sent = switch (entry.kind) {
                case USER -> true;
                case TOOL -> withTools;
                case ASSISTANT -> !entry.getText().isBlank();
                case ERROR -> false;
            };
            if (!sent) {
                continue;
            }
            if (entry.kind == Kind.USER || turns.isEmpty()) {
                turns.add(new ArrayList<>());
            }
            turns.get(turns.size() - 1).add(entry);
        }
        Map<Entry, String> shortened = new IdentityHashMap<>();
        int full = 0;
        for (List<Entry> turn : turns) {
            for (Entry entry : turn) {
                full += size(entry, null);
            }
        }
        int total = full;
        int kept = Math.max(0, turns.size() - Math.max(1, keepTurns));
        // 1. Old tool results become previews
        for (int t = 0; t < kept && total > budgetChars; t++) {
            for (Entry entry : turns.get(t)) {
                total -= shorten(entry, shortened);
            }
        }
        // 2. Old turns are left out whole
        int first = 0;
        while (total > budgetChars && first < kept) {
            for (Entry entry : turns.get(first)) {
                total -= size(entry, shortened.get(entry));
            }
            first++;
        }
        // 3. A long turn of its own: shorten its older results, keep the newest ones the model works with
        if (total > budgetChars) {
            List<Entry> keptTools = new ArrayList<>();
            for (int t = first; t < turns.size(); t++) {
                turns.get(t).stream().filter(e -> e.kind == Kind.TOOL).forEach(keptTools::add);
            }
            for (int i = 0; i < keptTools.size() - KEEP_RESULTS && total > budgetChars; i++) {
                total -= shorten(keptTools.get(i), shortened);
            }
        }
        List<AIMessage> messages = new ArrayList<>();
        int sentResults = 0;
        int sentTokens = 0;
        for (int t = first; t < turns.size(); t++) {
            for (Entry entry : turns.get(t)) {
                messages.add(toModelMessage(entry, shortened.get(entry)));
                sentTokens += tokens(entry, shortened.get(entry));
                if (shortened.containsKey(entry)) {
                    sentResults++;
                }
            }
        }
        return new ModelHistory(messages, full, total, sentResults, first, sentTokens);
    }

    @NotNull
    private static AIMessage toModelMessage(@NotNull Entry entry, @Nullable String shortResult) {
        return switch (entry.kind) {
            case USER -> AIMessage.userMessage(entry.getText());
            case ASSISTANT -> AIMessage.assistantMessage(entry.getText(), null);
            default -> {
                SentCall sent = sentCall(entry);
                AIFunctionCall call = new AIFunctionCall(
                    sent.name(),
                    new LinkedHashMap<>(sent.arguments()),
                    entry.metadata == null ? null : new LinkedHashMap<>(entry.metadata));
                String result = shortResult != null ? shortResult : resultText(entry);
                boolean error = entry.outcome == null || !entry.outcome.equals("SUCCESS"); //$NON-NLS-1$
                yield AIMessage.functionCall(call, error
                    ? new AIFunctionResult(AIFunctionType.INFORMATION, result, null, new DBException(result))
                    : new AIFunctionResult(AIFunctionType.INFORMATION, result));
            }
        };
    }

    /**
     * A tool call as a request carries it.
     */
    private record SentCall(@NotNull String name, @NotNull Map<String, Object> arguments) {
    }

    /**
     * The call of a tool entry under the current tool name and argument names: a call saved with a former tool id
     * ({@code db_listIndexes}) is sent as the tool it became ({@code db_describeTable}, with the operation a merged tool needs),
     * so that the history names only tools the request offers. The pairing metadata (tool_use id, call id) stays.
     */
    @NotNull
    private static SentCall sentCall(@NotNull Entry entry) {
        String name = entry.function == null ? "" : entry.function;
        try {
            AIToolCatalog.Call call = name.isEmpty() ? null : AIToolCatalog.resolveCall(name, entry.getArguments());
            if (call != null) {
                String current = name.equals(call.tool().getId()) || name.endsWith("_" + call.tool().getId()) ? name
                    : name.startsWith(call.tool().getToolbox().getToolboxId() + "_") ? call.tool().getFullId() : call.tool().getId();
                return new SentCall(current, call.arguments());
            }
        } catch (AIToolArgumentException | RuntimeException e) {
            // Conflicting former and current argument names, or no tool registry: send the call as saved
        }
        return new SentCall(name, entry.getArguments());
    }

    @NotNull
    private static String resultText(@NotNull Entry entry) {
        return entry.result != null ? entry.result : "{\"error\":\"Canceled\",\"outcome\":\"CANCELED\"}"; //$NON-NLS-1$
    }

    /**
     * Replaces a long tool result by a preview; returns the characters saved.
     */
    private static int shorten(@NotNull Entry entry, @NotNull Map<Entry, String> shortened) {
        if (entry.kind != Kind.TOOL || shortened.containsKey(entry)) {
            return 0;
        }
        String result = resultText(entry);
        if (result.length() <= SHORT_RESULT_CHARS) {
            return 0;
        }
        JsonObject preview = new JsonObject();
        preview.addProperty("shortened", "This earlier tool result was shortened to keep the request small; call the tool again if you need it in full.");
        preview.addProperty("originalChars", result.length());
        preview.addProperty("start", result.substring(0, PREVIEW_CHARS) + "...");
        String text = AgentJson.toText(preview);
        if (text.length() >= result.length()) {
            return 0;
        }
        shortened.put(entry, text);
        return result.length() - text.length();
    }

    // What the request carries for an entry: texts, the function name and arguments of a call, its result. AIMessage sends a
    // successful result as "<function> was completed.\n<result>", so the name counts twice
    private static int size(@NotNull Entry entry, @Nullable String shortResult) {
        int size = entry.getText().length();
        if (entry.kind == Kind.TOOL) {
            SentCall sent = sentCall(entry);
            int name = sent.name().length();
            size += name + AgentJson.GSON.toJson(sent.arguments()).length() +
                name + RESULT_HEADER.length() + (shortResult != null ? shortResult : resultText(entry)).length();
        }
        return size;
    }

    // Estimated tokens of the same parts as size()
    private static int tokens(@NotNull Entry entry, @Nullable String shortResult) {
        int tokens = AIAgentToolSets.estimateTokens(entry.getText());
        if (entry.kind == Kind.TOOL) {
            SentCall sent = sentCall(entry);
            int name = AIAgentToolSets.estimateTokens(sent.name());
            tokens += name + AIAgentToolSets.estimateTokens(AgentJson.GSON.toJson(sent.arguments())) +
                name + AIAgentToolSets.estimateTokens(RESULT_HEADER) +
                AIAgentToolSets.estimateTokens(shortResult != null ? shortResult : resultText(entry));
        }
        return tokens;
    }

    @NotNull
    String toJson() {
        return AgentJson.GSON.toJson(this);
    }

    @Nullable
    static AIAgentChat fromJson(@NotNull String json) {
        Type type = new TypeToken<AIAgentChat>() { }.getType();
        AIAgentChat chat = AgentJson.GSON.fromJson(json, type);
        if (chat == null || chat.id == null) {
            return null;
        }
        // Gson fills a plain list; the runner appends while the UI reads
        chat.entries = new CopyOnWriteArrayList<>(chat.getEntries());
        return chat;
    }
}
