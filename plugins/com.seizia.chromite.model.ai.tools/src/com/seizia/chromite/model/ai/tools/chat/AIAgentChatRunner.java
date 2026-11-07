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

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.ai.AIFunctionCall;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionPurpose;
import com.seizia.chromite.model.ai.AIMessage;
import com.seizia.chromite.model.ai.AIUsage;
import com.seizia.chromite.model.ai.agent.AIToolAccess;
import com.seizia.chromite.model.ai.agent.AIToolAccessClassifier;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;
import com.seizia.chromite.model.ai.agent.AIToolCallResult;
import com.seizia.chromite.model.ai.agent.AIToolCatalog;
import com.seizia.chromite.model.ai.agent.AIToolClient;
import com.seizia.chromite.model.ai.agent.AIToolConnections;
import com.seizia.chromite.model.ai.agent.AIToolExecutor;
import com.seizia.chromite.model.ai.agent.AIToolPolicy;
import com.seizia.chromite.model.ai.engine.AIEngine;
import com.seizia.chromite.model.ai.engine.AIEngineRequest;
import com.seizia.chromite.model.ai.engine.AIEngineResponseChunk;
import com.seizia.chromite.model.ai.engine.AIEngineResponseConsumer;
import com.seizia.chromite.model.ai.registry.AISettingsManager;
import com.seizia.chromite.model.ai.tools.mcp.AIAgentPlaybooks;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs one turn of an assistant conversation: asks the provider (streamed), runs the tool calls it makes through the policy,
 * sends the results back, until the model answers without tools, the user stops, or the turn limit is reached.
 *
 * 설계:
 * <ul>
 *     <li>모든 도구 호출은 {@link AIToolExecutor#execute}(클라이언트 {@link AIToolClient#CHAT})를 거친다. 정책·확인 대화상자·트랜잭션 안전 질문·감사
 *     기록이 MCP와 같다(docs/chromite/ai-agent.md "구조"). 도구에 {@code connection} 인수가 없으면 대화의 연결을 넣는다. 실행기는 채팅 문맥이 없으면
 *     연결 기본값을 모르기 때문이다(기존 채팅의 {@code AIDatabaseContext}를 만들지 않으려고 인수로 넘긴다).</li>
 *     <li>이 클래스는 호출한 작업 스레드에서 돈다(UI 스레드가 아님). 응답 글 조각과 도구 상태는 {@link Listener}로 알리고, 화면이 EDT로 넘긴다.</li>
 *     <li>중지: 모니터가 취소되면 스트림 대기를 끝내고 엔진을 닫는다(HTTP 연결도 닫힘). 실행 중인 도구는 같은 모니터로 취소된다. 확인 대화상자도
 *     모니터 취소를 보고 닫힌다({@code AIToolConfirmationHandler} 계약).</li>
 *     <li>모델에 보내는 도구는 {@link AIToolCatalog#getTools}({@link AIFunctionPurpose#TOOL}) 가운데 대화의 연결에서 쓸 수 있는 것이다
 *     ({@link AIAgentToolSelection}). 채팅 문맥이 필요한 옛 스키마 함수 3개는 빠진다. 대화 기록은 크기 예산 안으로 줄여 보낸다
 *     ({@link AIAgentChat#toModelMessages}). 빠진 도구와 줄인 기록은 시스템 프롬프트 끝에 알린다(docs/chromite/ai-agent.md "요청 크기").</li>
 *     <li>로그에 대화 내용·인수·키를 쓰지 않는다(비밀 없음 규칙). 오류는 대화 항목으로만 보인다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "2단계")
 * - 2026-11-07 시스템 프롬프트와 요청 한도 오류에서 기능을 공식 이름 "Chromite Agent"로 부름 (docs/chromite/ai-agent.md "명칭")
 * - 2026-11-08 요청 크기: 연결에 맞는 도구만 보내고, 기록을 예산 안으로 줄임 (후속 과제 #76)
 * - 2026-11-08 도구의 진행 글을 항목에 두고 {@link Listener#toolProgress}로 알림(백업·복원 도구)
 */
public final class AIAgentChatRunner {

    private static final Log log = Log.getLog(AIAgentChatRunner.class);

    /**
     * Upper bound of model requests in one turn (each tool round is one request): a model that loops stops here.
     */
    public static final int MAX_REQUESTS = 40;
    private static final long WAIT_STEP_MS = 100;

    /**
     * Progress of a turn; called on the worker thread.
     */
    public interface Listener {
        /** A new entry (the model's text, a tool call that starts, an error). */
        void entryAdded(@NotNull AIAgentChat.Entry entry);

        /** More text of the model's entry. */
        void textAppended(@NotNull AIAgentChat.Entry entry, @NotNull String delta);

        /** A tool call finished. */
        void entryUpdated(@NotNull AIAgentChat.Entry entry);

        /** A running tool reported progress ({@link AIAgentChat.Entry#getProgress}). */
        default void toolProgress(@NotNull AIAgentChat.Entry entry) {
        }
    }

    private AIAgentChatRunner() {
    }

    /**
     * Runs the turn that the last user entry started and saves the conversation afterwards.
     */
    public static void run(@NotNull DBRProgressMonitor monitor, @NotNull AIAgentChat chat, @NotNull Listener listener) {
        try {
            runTurn(monitor, chat, listener);
        } catch (Throwable e) {
            if (!monitor.isCanceled()) {
                log.debug("AI assistant turn failed", e);
                listener.entryAdded(chat.addError(AIToolExecutor.describe(e)));
            }
        } finally {
            chat.touch();
            AIAgentChatStore.save(chat);
        }
    }

    private static void runTurn(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIAgentChat chat,
        @NotNull Listener listener
    ) throws Exception {
        if (AISettingsManager.getInstance().getSettings().isAiDisabled()) {
            throw new IllegalStateException("AI integration is disabled");
        }
        String engineId = chat.getEngineId();
        if (engineId == null) {
            throw new IllegalStateException("No AI provider is selected");
        }
        // The conversation's connection does not change during a turn (the UI locks it), so the tools are picked once
        AIAgentToolSelection.Selection selection = AIAgentToolSelection.select(
            chat.getConnectionId() == null ? null : findConnection(chat.getConnectionId()),
            AIToolCatalog.getTools(AIFunctionPurpose.TOOL));
        try (AIEngine<?> engine = AIAgentProviders.createEngine(engineId, chat.getModel())) {
            for (int request = 0; request < MAX_REQUESTS; request++) {
                if (monitor.isCanceled()) {
                    return;
                }
                AIAgentChat.ModelHistory history = chat.toModelMessages(AIAgentChat.HISTORY_BUDGET_CHARS, AIAgentChat.KEEP_TURNS);
                List<AIMessage> messages = new ArrayList<>();
                messages.add(AIMessage.systemMessage(buildSystemPrompt(chat) + describeOmissions(selection, history)));
                messages.addAll(history.messages());
                AIEngineRequest engineRequest = new AIEngineRequest(messages);
                engineRequest.setFunctions(selection.tools());

                StreamCollector collector = new StreamCollector(chat, listener);
                engine.requestCompletionStream(monitor, engineRequest, collector);
                if (!collector.await(monitor)) {
                    // Stopped: the engine is closed below, which ends its HTTP stream
                    return;
                }
                if (collector.error != null) {
                    listener.entryAdded(chat.addError(AIToolExecutor.describe(collector.error)));
                    return;
                }
                if (collector.calls.isEmpty()) {
                    return;
                }
                for (AIFunctionCall call : collector.calls) {
                    if (monitor.isCanceled()) {
                        return;
                    }
                    runTool(monitor, chat, call, listener);
                }
                AIAgentChatStore.save(chat);
            }
            listener.entryAdded(chat.addError("Chromite Agent made " + MAX_REQUESTS + " requests in one turn and was stopped"));
        }
    }

    private static void runTool(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIAgentChat chat,
        @NotNull AIFunctionCall call,
        @NotNull Listener listener
    ) {
        AIAgentChat.Entry entry = chat.addTool(call);
        listener.entryAdded(entry);
        Map<String, Object> arguments = new LinkedHashMap<>(call.getArguments());
        AIFunctionDescriptor tool = AIToolCatalog.getTool(call.getFunctionName());
        if (tool != null && tool.getParameter(AIToolConnections.PARAM_CONNECTION) != null
            && CommonUtils.isEmptyTrimmed(CommonUtils.toString(arguments.get(AIToolConnections.PARAM_CONNECTION), null))
            && chat.getConnectionId() != null
        ) {
            arguments.put(AIToolConnections.PARAM_CONNECTION, chat.getConnectionId());
        }
        long start = System.currentTimeMillis();
        AIToolCallResult result = AIToolExecutor.execute(monitor, AIToolClient.CHAT, call.getFunctionName(), arguments, null,
            progress -> {
                entry.setProgress(progress);
                listener.toolProgress(entry);
            });
        String access = tool == null ? null : classify(monitor, tool, arguments);
        entry.finishTool(result.outcome().name(), access, result.text(), System.currentTimeMillis() - start);
        listener.entryUpdated(entry);
    }

    /**
     * The class the card shows: the static class raised by the tool's classifier, as the executor computes it (a {@code runSql}
     * with {@code DROP} is DANGEROUS). Classifiers only parse the arguments.
     */
    @NotNull
    private static String classify(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIFunctionDescriptor tool,
        @NotNull Map<String, Object> arguments
    ) {
        AIToolAccess access = tool.getAccess();
        try {
            if (tool.getInstance() instanceof AIToolAccessClassifier classifier) {
                DBPDataSourceContainer connection = findConnection(
                    CommonUtils.toString(arguments.get(AIToolConnections.PARAM_CONNECTION), null));
                access = access.max(classifier.classify(
                    new AIToolCallContext(monitor, null, tool, AIToolClient.CHAT, connection), arguments));
            }
        } catch (Exception e) {
            // The executor already reported the problem as the call's result
        }
        return access.name();
    }

    @Nullable
    private static DBPDataSourceContainer findConnection(@Nullable String idOrName) {
        if (CommonUtils.isEmptyTrimmed(idOrName)) {
            return null;
        }
        try {
            return AIToolConnections.find(idOrName);
        } catch (AIToolArgumentException e) {
            return null;
        }
    }

    /**
     * System prompt of Chromite Agent: the agent instructions shared with the MCP server and the conversation's context.
     * The first line gives the model the official name, so it introduces itself as Chromite Agent.
     */
    @NotNull
    static String buildSystemPrompt(@NotNull AIAgentChat chat) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are Chromite Agent, the database agent inside Chromite, a desktop database workbench. You work like an experienced DBA.\n");
        prompt.append(AIAgentPlaybooks.INSTRUCTIONS).append('\n');
        prompt.append("Answer in the language the user writes in. Use Markdown; put SQL in ```sql code blocks so the user can open it in a query window. ");
        prompt.append("Cite the evidence (numbers, plan operators, line numbers) from tool results. ");
        prompt.append("Before calling a tool that writes, show the SQL or DDL and say what it changes; the user confirms it in a dialog.\n");
        DBPDataSourceContainer connection = chat.getConnectionId() == null ? null : findConnection(chat.getConnectionId());
        if (connection != null) {
            prompt.append("Current connection: \"").append(connection.getName()).append("\" (id ").append(connection.getId())
                .append(", driver ").append(connection.getDriver().getName()).append(")");
            if (AIToolPolicy.isProduction(connection)) {
                prompt.append(", marked as PRODUCTION: write tools are blocked unless the user allowed them for this connection");
            }
            if (connection.isConnectionReadOnly()) {
                prompt.append(", read-only");
            }
            prompt.append(". Tools use it when you omit the connection argument.\n");
            if (!CommonUtils.isEmpty(chat.getDatabase())) {
                prompt.append("Current database/schema: \"").append(chat.getDatabase())
                    .append("\". Pass it as the database (or schema) argument where a tool takes one, unless the user names another.\n");
            }
        } else {
            prompt.append("No connection is selected: call listConnections and ask the user which one to use if it is not clear.\n");
        }
        return prompt.toString();
    }

    /**
     * What the request leaves out, told to the model so that it can explain a missing tool or call a tool again instead of
     * guessing from a shortened result. Empty when nothing is left out.
     */
    @NotNull
    static String describeOmissions(@NotNull AIAgentToolSelection.Selection selection, @NotNull AIAgentChat.ModelHistory history) {
        StringBuilder text = new StringBuilder();
        if (!selection.blocked().isEmpty()) {
            text.append("Tools the user's policy blocks on this connection are not offered: ")
                .append(String.join(", ", selection.blocked()))
                .append(". If the task needs one, say so; the user can allow it in Options > Chromite Agent > Tool Policy.\n");
        }
        if (!selection.otherDatabases().isEmpty()) {
            text.append("Tools for other database types are not offered: ").append(String.join(", ", selection.otherDatabases()))
                .append(". For another connection's database, ask the user to switch the conversation's connection.\n");
        }
        if (history.shortenedResults() > 0 || history.droppedTurns() > 0) {
            text.append("To keep the request small, ");
            if (history.droppedTurns() > 0) {
                text.append(history.droppedTurns()).append(" earlier turn(s) of this conversation were left out");
                text.append(history.shortenedResults() > 0 ? " and " : ". ");
            }
            if (history.shortenedResults() > 0) {
                text.append(history.shortenedResults()).append(" earlier tool result(s) were shortened. ");
            }
            text.append("Call a tool again when you need data you no longer see.\n");
        }
        return text.toString();
    }

    /**
     * Collects one streamed response: text goes into the conversation as it arrives, tool calls are kept for the turn.
     */
    private static final class StreamCollector implements AIEngineResponseConsumer {
        private final AIAgentChat chat;
        private final Listener listener;
        private final CountDownLatch done = new CountDownLatch(1);
        private final List<AIFunctionCall> calls = new ArrayList<>();
        @Nullable
        private AIAgentChat.Entry text;
        @Nullable
        private volatile Throwable error;
        private volatile boolean stopped;

        StreamCollector(@NotNull AIAgentChat chat, @NotNull Listener listener) {
            this.chat = chat;
            this.listener = listener;
        }

        @Override
        public synchronized void nextChunk(@NotNull AIEngineResponseChunk chunk) {
            if (stopped) {
                return;
            }
            AIFunctionCall call = chunk.getFunctionCall();
            if (call != null) {
                calls.add(call);
                return;
            }
            for (String delta : chunk.getChoices()) {
                if (CommonUtils.isEmpty(delta)) {
                    continue;
                }
                if (text == null) {
                    text = chat.addAssistant();
                    listener.entryAdded(text);
                }
                text.appendText(delta);
                listener.textAppended(text, delta);
            }
        }

        @Override
        public void error(@NotNull Throwable throwable) {
            error = throwable;
            done.countDown();
        }

        @Override
        public void completeBlock() {
            done.countDown();
        }

        @Override
        public void usage(@Nullable AIUsage usage) {
            // Not shown
        }

        @Override
        public void systemPromptLength(int length) {
            // Not shown
        }

        @Override
        public void warning(@NotNull String message) {
            listener.entryAdded(chat.addError(message));
        }

        /**
         * Waits for the end of the response; false when the user stopped the turn.
         */
        boolean await(@NotNull DBRProgressMonitor monitor) throws InterruptedException {
            while (!done.await(WAIT_STEP_MS, TimeUnit.MILLISECONDS)) {
                if (monitor.isCanceled()) {
                    synchronized (this) {
                        stopped = true;
                    }
                    return false;
                }
            }
            synchronized (this) {
                return true;
            }
        }
    }
}
