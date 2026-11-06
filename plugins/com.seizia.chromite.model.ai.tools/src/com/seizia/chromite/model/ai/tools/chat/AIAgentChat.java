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

import com.google.gson.reflect.TypeToken;
import com.seizia.chromite.model.ai.AIFunctionCall;
import com.seizia.chromite.model.ai.AIFunctionResult;
import com.seizia.chromite.model.ai.AIFunctionType;
import com.seizia.chromite.model.ai.AIMessage;
import com.seizia.chromite.model.ai.tools.AgentJson;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.lang.reflect.Type;
import java.util.ArrayList;
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
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "2단계")
 */
public final class AIAgentChat {

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

        void finishTool(@NotNull String outcome, @Nullable String access, @NotNull String result, long durationMs) {
            this.outcome = outcome;
            this.access = access;
            this.result = result;
            this.durationMs = durationMs;
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
     * The conversation as model messages: prompts, the model's texts and tool call/result pairs. Errors are not sent. A tool
     * call without a result (the app stopped while it ran) is sent as canceled, because every tool call needs its result.
     */
    @NotNull
    List<AIMessage> toModelMessages() {
        List<AIMessage> messages = new ArrayList<>();
        for (Entry entry : getEntries()) {
            switch (entry.kind) {
                case USER -> messages.add(AIMessage.userMessage(entry.getText()));
                case ASSISTANT -> {
                    if (!entry.getText().isBlank()) {
                        messages.add(AIMessage.assistantMessage(entry.getText(), null));
                    }
                }
                case TOOL -> {
                    AIFunctionCall call = new AIFunctionCall(
                        entry.function == null ? "" : entry.function,
                        new LinkedHashMap<>(entry.getArguments()),
                        entry.metadata == null ? null : new LinkedHashMap<>(entry.metadata));
                    String result = entry.result != null ? entry.result : "{\"error\":\"Canceled\",\"outcome\":\"CANCELED\"}"; //$NON-NLS-1$
                    boolean error = entry.outcome == null || !entry.outcome.equals("SUCCESS"); //$NON-NLS-1$
                    messages.add(AIMessage.functionCall(call, error
                        ? new AIFunctionResult(AIFunctionType.INFORMATION, result, null, new DBException(result))
                        : new AIFunctionResult(AIFunctionType.INFORMATION, result)));
                }
                case ERROR -> {
                    // Local only
                }
            }
        }
        return messages;
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
