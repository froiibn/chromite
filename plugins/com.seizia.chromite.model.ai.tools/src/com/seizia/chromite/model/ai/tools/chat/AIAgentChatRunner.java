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
import com.seizia.chromite.model.ai.agent.AIToolOutcome;
import com.seizia.chromite.model.ai.agent.AIToolPolicy;
import com.seizia.chromite.model.ai.engine.AIEngine;
import com.seizia.chromite.model.ai.engine.AIEngineRequest;
import com.seizia.chromite.model.ai.engine.AIEngineResponseChunk;
import com.seizia.chromite.model.ai.engine.AIEngineResponseConsumer;
import com.seizia.chromite.model.ai.engine.chatcompletions.ChatCompletionsClient;
import com.seizia.chromite.model.ai.registry.AISettingsManager;
import com.seizia.chromite.model.ai.tools.FindTools;
import com.seizia.chromite.model.ai.tools.internal.AIToolsMessages;
import com.seizia.chromite.model.ai.tools.mcp.AIAgentPlaybooks;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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
 *     <li>모델에 보내는 도구는 {@link AIToolCatalog#getTools}({@link AIFunctionPurpose#TOOL}) 가운데 제공자의 도구 묶음(최소·핵심·전체)과 대화가
 *     찾은 도구이고, 최소·핵심은 대화의 연결에서 쓸 수 있는 것만이다({@link AIAgentToolSets}, {@link AIAgentToolSelection}). 요청마다 다시 고른다:
 *     한 요청의 {@code findTools} 결과가 다음 요청에 실린다. 사용자 글이 도구 ID를 부르면(플레이북) 그 도구도 대화에 더한다. 채팅 문맥이 필요한
 *     옛 스키마 함수 3개는 빠진다. 빠진 도구와 줄인 기록은 시스템 프롬프트 끝에 알린다(docs/chromite/ai-agent.md "요청 크기", "도구 묶음").</li>
 *     <li>보내기 전 크기 확인: 모델의 컨텍스트({@link AIAgentProviders#getContextTokens})에서 도구 정의·시스템 프롬프트·답 몫을 뺀 만큼으로 기록을
 *     줄인다. 그래도 넘으면(남겨 두는 최근 차례만으로도 큼) 요청하지 않고 대화에 이유를 적는다. 서버의 문맥 초과 오류보다 할 일을 알 수 있다.</li>
 *     <li>도구 호출을 못 하는 서버(vLLM 400, {@code ToolCallsUnsupportedException})면 그 제공자·모델을 기억하고 같은 요청을 도구 없이 다시 보낸다.
 *     그 뒤로는 도구 없이 보내고, 시스템 프롬프트는 SQL을 코드 블록으로 제안하라고 한다(사용자가 쿼리 창에서 열어 실행).</li>
 *     <li>로그에 대화 내용·인수·키를 쓰지 않는다(비밀 없음 규칙). 오류는 대화 항목으로만 보인다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "2단계")
 * - 2026-11-07 시스템 프롬프트와 요청 한도 오류에서 기능을 공식 이름 "Chromite Agent"로 부름 (docs/chromite/ai-agent.md "명칭")
 * - 2026-11-08 요청 크기: 연결에 맞는 도구만 보내고, 기록을 예산 안으로 줄임 (후속 과제 #76)
 * - 2026-11-12 도구 묶음·findTools·대화가 찾은 도구, 컨텍스트 크기로 보내기 전 확인, 도구 없음 방식 (앱 변경 #64)
 * - 2026-11-08 도구의 진행 글을 항목에 두고 {@link Listener#toolProgress}로 알림(백업·복원 도구)
 * - 2026-11-08 차례의 토큰 사용량을 모아 {@link Listener#usage}로 알림: 엔진이 받던 사용량을 버리고 있었다 (후속 39)
 * - 2026-11-15 사용자 글이 옛 도구 ID를 부르면 지금 도구를 더하고, 카드의 분류는 옛 ID·인수를 지금 도구로 바꾼 뒤 셈 (docs/chromite/ai-agent.md "별칭")
 */
public final class AIAgentChatRunner {

    private static final Log log = Log.getLog(AIAgentChatRunner.class);

    /**
     * Upper bound of model requests in one turn (each tool round is one request): a model that loops stops here.
     */
    public static final int MAX_REQUESTS = 40;
    // Tokens kept free for the model's answer when checking that a request fits (an eighth of small contexts)
    private static final int MAX_ANSWER_RESERVE_TOKENS = 4096;

    private static final String MINIMAL_INSTRUCTIONS =
        "Chromite gives you the database connections the user configured in the Chromite desktop app. You have a small set of tools. " +
        "Find tables with listTables or searchObjects and read their structure with describeTable. For data and for diagnosis write " +
        "one read-only SQL statement (the database's system views and catalogs) and run it with runReadOnlySql; explain a slow query " +
        "with explainQuery. runSql changes data and the user confirms it. If a task needs a tool you do not have, call findTools " +
        "with English keywords. Never commit or roll back a transaction unless the user asked for it.";
    private static final String FIND_TOOLS_INSTRUCTION =
        "Only part of the tools are offered. If none fits the task, call findTools with English keywords (and an area); " +
        "the tools it returns are offered from your next step on.";
    private static final String NO_TOOLS_INSTRUCTIONS =
        "You can't call tools with this provider, and you can't see the user's databases. Do not write tool calls. " +
        "When SQL helps, give it in a ```sql code block: the user opens it in a query window, runs it and shows you the result. " +
        "Ask for the database product and version, table structures or results when you need them.";
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

        /**
         * Tokens of the turn: the usage the provider reported for each request of the turn, summed. Called once at the end of
         * the turn, only when the provider reported usage.
         */
        default void usage(@NotNull AIUsage turnUsage) {
        }

        /**
         * The provider's server rejected tool calls; the turn goes on without tools and later requests of this provider and
         * model send none ({@link AIAgentToolSets#isToolsOff}).
         */
        default void toolsOff() {
        }
    }

    /**
     * Sum of the usage of a turn's requests; null until a request reported usage.
     */
    private static final class TurnUsage {
        @Nullable
        private AIUsage total;

        void add(@Nullable AIUsage usage) {
            if (usage == null) {
                return;
            }
            total = total == null ? usage : new AIUsage(
                total.totalInputTokens() + usage.totalInputTokens(),
                total.cachedTokens() + usage.cachedTokens(),
                total.totalOutputTokens() + usage.totalOutputTokens(),
                total.reasoningTokens() + usage.reasoningTokens());
        }
    }

    private AIAgentChatRunner() {
    }

    /**
     * Runs the turn that the last user entry started and saves the conversation afterwards.
     */
    public static void run(@NotNull DBRProgressMonitor monitor, @NotNull AIAgentChat chat, @NotNull Listener listener) {
        TurnUsage usage = new TurnUsage();
        try {
            runTurn(monitor, chat, listener, usage);
        } catch (Throwable e) {
            if (!monitor.isCanceled()) {
                log.debug("AI assistant turn failed", e);
                listener.entryAdded(chat.addError(AIToolExecutor.describe(e)));
            }
        } finally {
            chat.touch();
            AIAgentChatStore.save(chat);
            if (usage.total != null) {
                listener.usage(usage.total);
            }
        }
    }

    private static void runTurn(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIAgentChat chat,
        @NotNull Listener listener,
        @NotNull TurnUsage usage
    ) throws Exception {
        if (AISettingsManager.getInstance().getSettings().isAiDisabled()) {
            throw new IllegalStateException("AI integration is disabled");
        }
        String engineId = chat.getEngineId();
        if (engineId == null) {
            throw new IllegalStateException("No AI provider is selected");
        }
        // The conversation's connection does not change during a turn (the UI locks it)
        DBPDataSourceContainer connection = chat.getConnectionId() == null ? null : findConnection(chat.getConnectionId());
        List<AIFunctionDescriptor> catalog = AIToolCatalog.getTools(AIFunctionPurpose.TOOL);
        addNamedTools(chat, catalog);
        String model = CommonUtils.isEmptyTrimmed(chat.getModel()) ? AIAgentProviders.getDefaultModel(engineId) : chat.getModel();
        AIAgentToolSets.ToolSet setting = AIAgentToolSets.getToolSet(engineId);
        int contextTokens = AIAgentProviders.getContextTokens(engineId);
        try (AIEngine<?> engine = AIAgentProviders.createEngine(engineId, chat.getModel())) {
            for (int request = 0; request < MAX_REQUESTS; request++) {
                if (monitor.isCanceled()) {
                    return;
                }
                // Planned for every request: findTools in one request adds tools to the next
                boolean toolsOff = AIAgentToolSets.isToolsOff(engineId, model);
                AIAgentToolSets.Plan plan = AIAgentToolSets.plan(setting, connection, catalog, chat.getFoundTools(), toolsOff, contextTokens);
                String systemPrompt = buildSystemPrompt(chat, plan);
                int reserve = Math.min(MAX_ANSWER_RESERVE_TOKENS, contextTokens / 8);
                int fixedTokens = plan.definitionTokens() + AIAgentToolSets.estimateTokens(systemPrompt);
                int available = contextTokens - fixedTokens - reserve;
                AIAgentChat.ModelHistory history = fitHistory(chat, available, !toolsOff);
                if (history.sentTokens() > available) {
                    // Would fail on the server with a context error: say why instead
                    listener.entryAdded(chat.addError(NLS.bind(AIToolsMessages.chat_request_too_large, new Object[]{
                        format(contextTokens), format(plan.definitionTokens()), format(history.sentTokens()),
                        format(AIAgentToolSets.estimateTokens(systemPrompt) + reserve)})));
                    return;
                }
                List<AIMessage> messages = new ArrayList<>();
                messages.add(AIMessage.systemMessage(systemPrompt + describeOmissions(plan, history)));
                messages.addAll(history.messages());
                AIEngineRequest engineRequest = new AIEngineRequest(messages);
                engineRequest.setFunctions(plan.tools());

                StreamCollector collector = new StreamCollector(chat, listener);
                engine.requestCompletionStream(monitor, engineRequest, collector);
                boolean finished = collector.await(monitor);
                usage.add(collector.usage);
                if (!finished) {
                    // Stopped: the engine is closed below, which ends its HTTP stream
                    return;
                }
                if (collector.error != null) {
                    if (!toolsOff && isToolCallsUnsupported(collector.error)) {
                        // The server can't call tools (vLLM without its tool options): remember it and ask again without tools
                        AIAgentToolSets.setToolsOff(engineId, model, true);
                        listener.toolsOff();
                        continue;
                    }
                    listener.entryAdded(chat.addError(AIToolExecutor.describe(collector.error)));
                    return;
                }
                if (collector.calls.isEmpty()) {
                    return;
                }
                Set<String> offered = plan.toolIds();
                for (AIFunctionCall call : collector.calls) {
                    if (monitor.isCanceled()) {
                        return;
                    }
                    AIAgentToolSets.runWithOffered(offered, () -> runTool(monitor, chat, call, offered, listener));
                }
                AIAgentChatStore.save(chat);
            }
            listener.entryAdded(chat.addError("Chromite Agent made " + MAX_REQUESTS + " requests in one turn and was stopped"));
        }
    }

    /**
     * The history that fits the tokens left after the tool definitions, the system prompt and the answer: the character budget
     * of {@link AIAgentChat#toModelMessages} narrowed until the estimated tokens fit (characters per token differ by language).
     * The result may still be larger when the kept turns alone are.
     */
    @NotNull
    private static AIAgentChat.ModelHistory fitHistory(@NotNull AIAgentChat chat, int availableTokens, boolean withTools) {
        int budget = (int) Math.min(AIAgentChat.HISTORY_BUDGET_CHARS, Math.max(0L, (long) availableTokens * 4));
        AIAgentChat.ModelHistory history = chat.toModelMessages(budget, AIAgentChat.KEEP_TURNS, withTools);
        for (int i = 0; i < 3 && history.sentTokens() > availableTokens && budget > 0; i++) {
            budget = (int) ((long) budget * Math.max(0, availableTokens) / history.sentTokens() * 9 / 10);
            history = chat.toModelMessages(budget, AIAgentChat.KEEP_TURNS, withTools);
        }
        return history;
    }

    /**
     * Tools the user's prompt names by ID (a playbook lists the tools of its steps) join the conversation's tools, so that a
     * playbook works with the minimal and core sets too.
     */
    private static void addNamedTools(@NotNull AIAgentChat chat, @NotNull List<AIFunctionDescriptor> catalog) {
        List<AIAgentChat.Entry> entries = chat.getEntries();
        AIAgentChat.Entry prompt = null;
        for (int i = entries.size() - 1; i >= 0 && prompt == null; i--) {
            if (entries.get(i).getKind() == AIAgentChat.Kind.USER) {
                prompt = entries.get(i);
            }
        }
        if (prompt == null) {
            return;
        }
        for (AIFunctionDescriptor tool : catalog) {
            if (tool.getId().equals(AIAgentToolSets.FIND_TOOLS)) {
                continue;
            }
            // A former id (a playbook or a prompt written before the tool was renamed or merged) names the tool too
            List<String> names = new ArrayList<>();
            names.add(tool.getId());
            tool.getAliases().forEach(alias -> names.add(alias.id()));
            for (String name : names) {
                if (Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(prompt.getText()).find()) { //$NON-NLS-1$ //$NON-NLS-2$
                    chat.addFoundTool(tool.getId());
                    break;
                }
            }
        }
    }

    private static boolean isToolCallsUnsupported(@NotNull Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause() == e ? null : e.getCause()) {
            if (e instanceof ChatCompletionsClient.ToolCallsUnsupportedException) {
                return true;
            }
        }
        return false;
    }

    @NotNull
    private static String format(int tokens) {
        return NumberFormat.getIntegerInstance().format(tokens);
    }

    private static void runTool(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIAgentChat chat,
        @NotNull AIFunctionCall call,
        @NotNull Set<String> offered,
        @NotNull Listener listener
    ) {
        AIAgentChat.Entry entry = chat.addTool(call);
        listener.entryAdded(entry);
        Map<String, Object> arguments = new LinkedHashMap<>(call.getArguments());
        AIFunctionDescriptor tool = AIToolCatalog.getTool(call.getFunctionName());
        if (tool != null && !offered.contains(tool.getId())) {
            // The model called a tool it was not offered (named in a playbook or a findTools area list): the executor applies
            // the policy as for any call, and later requests carry its definition so that the model sees its arguments
            chat.addFoundTool(tool.getId());
        }
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
        String access = tool == null ? null : classify(monitor, call.getFunctionName(), arguments);
        entry.finishTool(result.outcome().name(), access, result.text(), System.currentTimeMillis() - start);
        if (tool != null && tool.getId().equals(AIAgentToolSets.FIND_TOOLS) && result.outcome() == AIToolOutcome.SUCCESS) {
            for (String found : FindTools.foundIds(result.text())) {
                chat.addFoundTool(found);
            }
        }
        listener.entryUpdated(entry);
    }

    /**
     * The class the card shows: the static class raised by the tool's classifier, as the executor computes it (a {@code runSql}
     * with {@code DROP} is DANGEROUS). Classifiers only parse the arguments.
     */
    @NotNull
    private static String classify(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String functionName,
        @NotNull Map<String, Object> calledArguments
    ) {
        AIToolAccess access = AIToolAccess.READ;
        try {
            // A former tool id or argument name: the tool and arguments the executor ran
            AIToolCatalog.Call resolved = AIToolCatalog.resolveCall(functionName, calledArguments);
            if (resolved == null) {
                return access.name();
            }
            AIFunctionDescriptor tool = resolved.tool();
            Map<String, Object> arguments = resolved.arguments();
            access = tool.getAccess();
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
    static String buildSystemPrompt(@NotNull AIAgentChat chat, @NotNull AIAgentToolSets.Plan plan) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are Chromite Agent, the database agent inside Chromite, a desktop database workbench. You work like an experienced DBA.\n");
        if (plan.toolsOff()) {
            prompt.append(NO_TOOLS_INSTRUCTIONS).append('\n');
        } else if (plan.set() == AIAgentToolSets.ToolSet.MINIMAL) {
            // The shared instructions name tools the minimal set does not have; a small model would call them blindly
            prompt.append(MINIMAL_INSTRUCTIONS).append('\n');
        } else {
            prompt.append(AIAgentPlaybooks.INSTRUCTIONS).append('\n');
            if (plan.set() != AIAgentToolSets.ToolSet.ALL) {
                prompt.append(FIND_TOOLS_INSTRUCTION).append('\n');
            }
        }
        prompt.append("Answer in the language the user writes in. Use Markdown; put SQL in ```sql code blocks so the user can open it in a query window. ");
        if (!plan.toolsOff()) {
            prompt.append("Cite the evidence (numbers, plan operators, line numbers) from tool results. ");
            prompt.append("Before calling a tool that writes, show the SQL or DDL and say what it changes; the user confirms it in a dialog.\n");
        } else {
            prompt.append('\n');
        }
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
            prompt.append(plan.toolsOff() ? "No connection is selected: ask the user which database the question is about.\n"
                : "No connection is selected: call listConnections and ask the user which one to use if it is not clear.\n");
        }
        return prompt.toString();
    }

    /**
     * What the request leaves out, told to the model so that it can explain a missing tool or call a tool again instead of
     * guessing from a shortened result. Empty when nothing is left out.
     */
    @NotNull
    static String describeOmissions(@NotNull AIAgentToolSets.Plan selection, @NotNull AIAgentChat.ModelHistory history) {
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
        // The last usage of the response (Anthropic repeats the output tokens so far with each message delta)
        @Nullable
        private volatile AIUsage usage;
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
            if (usage != null) {
                this.usage = usage;
            }
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
