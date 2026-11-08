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
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.ai.AIFunction;
import com.seizia.chromite.model.ai.AIFunctionContext;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionParameter;
import com.seizia.chromite.model.ai.AIFunctionResult;
import com.seizia.chromite.model.ai.engine.AIDatabaseContext;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The single entry point that runs an agent tool for the chat or the MCP server.
 *
 * 설계: 정책·확인·감사를 한 곳에 둬야 채팅과 MCP가 같은 규칙을 지킨다(docs/chromite/ai-agent.md "구조"). 순서:
 * <ol>
 *     <li>도구와 연결 찾기(없거나 모호하면 INVALID). {@code connection}이 비어 있으면 채팅의 현재 연결.</li>
 *     <li>필수 인수 검사.</li>
 *     <li>실제 분류 = 정적 분류와 {@link AIToolAccessClassifier} 결과 중 높은 것.</li>
 *     <li>읽기 전용 연결 + DB를 바꾸는 도구 → READ_ONLY. 정책으로 바꿀 수 없게 정책보다 먼저 본다.</li>
 *     <li>{@link AIToolPolicy#resolve}: BLOCKED면 끝, CONFIRM이면 미리 보기를 만들어 확인 처리기에 묻는다. 처리기가 없으면 거부.</li>
 *     <li>트랜잭션 안전(사용자 규칙 2026-11-07): {@link AIToolTransactionGuard}가 이 호출로 끝날 커밋 안 된 트랜잭션을 찾으면, AUTO여도
 *     Commit / Roll back / Cancel을 묻고 답대로 처리한 뒤에만 실행한다. 처리한 내용은 결과 JSON의
 *     {@value #RESULT_TRANSACTIONS_BEFORE_CALL}로 덧붙인다. 도구는 답과 상관없이 실행되므로(1단계 계약) 이것이 없으면 모델과 채팅 카드가
 *     커밋인지 롤백인지 알 수 없다.</li>
 *     <li>실행, 결과 크기 한도, 감사 기록. 거부·막힘도 기록한다.</li>
 * </ol>
 * 실패는 예외가 아니라 {@link AIToolCallResult}의 오류 JSON으로 돌려준다. 모델이 오류를 읽고 다른 방법을 고를 수 있어야 하기 때문이다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-07 트랜잭션 질문의 Commit/Roll back 답을 결과에 남김({@value #RESULT_TRANSACTIONS_BEFORE_CALL}): 롤백으로 답한
 *   {@code commitTransaction}이 커밋과 같은 "완료"로만 보이던 것(docs/chromite/ai-agent.md "트랜잭션 안전")
 * - 2026-11-08 도구의 진행 상황을 받을 수신자를 넘기는 {@code execute}(채팅 카드에 백업·복원 진행 표시)
 * - 2026-11-08 트랜잭션 질문의 Commit/Roll back 답을 감사 기록에도 남김(docs/chromite/ai-agent.md "실제 API 확인")
 * - 2026-11-08 확인 요청에 미리 보기 종류를 넣음(쿼리 창 글 고치기, docs/chromite/ai-agent.md "편집기 도구")
 */
public final class AIToolExecutor {

    private static final Log log = Log.getLog(AIToolExecutor.class);
    private static final Gson GSON = new Gson();

    /**
     * Upper bound of a result text; tools cap their own output earlier and report what they cut.
     */
    public static final int MAX_RESULT_CHARS = 1024 * 1024;

    /**
     * Result member describing a Commit / Roll back answer carried out before the tool ran:
     * {@code {"action": "COMMITTED"|"ROLLED_BACK", "count": n, "note": "..."}}.
     */
    public static final String RESULT_TRANSACTIONS_BEFORE_CALL = "transactionsBeforeCall"; //$NON-NLS-1$

    private AIToolExecutor() {
    }

    /**
     * Runs a tool through the policy.
     *
     * @param toolId      function ID or full ID ({@code db_<id>})
     * @param arguments   tool arguments as decoded from JSON
     * @param chatContext the chat's function context (current connection, prompt), or null for MCP
     */
    @NotNull
    public static AIToolCallResult execute(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIToolClient client,
        @NotNull String toolId,
        @NotNull Map<String, Object> arguments,
        @Nullable AIFunctionContext chatContext
    ) {
        return execute(monitor, client, toolId, arguments, chatContext, null);
    }

    /**
     * Runs a tool through the policy and passes the tool's progress texts to a listener.
     *
     * @param progressListener receives {@link AIToolCallContext#reportProgress} texts on the tool's thread, or null
     */
    @NotNull
    public static AIToolCallResult execute(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIToolClient client,
        @NotNull String toolId,
        @NotNull Map<String, Object> arguments,
        @Nullable AIFunctionContext chatContext,
        @Nullable Consumer<String> progressListener
    ) {
        long start = System.currentTimeMillis();
        Map<String, Object> args = new LinkedHashMap<>(arguments);
        AIFunctionDescriptor tool = AIToolCatalog.getTool(toolId);
        if (tool == null) {
            return finish(client, toolId, AIToolAccess.READ, null, args, null, AIToolOutcome.INVALID, start,
                "Unknown tool '" + toolId + "'", null);
        }
        DBPDataSourceContainer connection = null;
        AIToolAccess access = tool.getAccess();
        AIToolDecision decision = null;
        // What the user's answer to a transaction question did before the call (null when there was no such question)
        Resolution resolution = null;
        try {
            connection = resolveConnection(tool, args, chatContext);
            checkRequired(tool, args);
            AIToolCallContext context = new AIToolCallContext(monitor, chatContext, tool, client, connection, progressListener);
            AIFunction instance = tool.getInstance();
            if (instance instanceof AIToolAccessClassifier classifier) {
                access = access.max(classifier.classify(context, args));
            }
            if (access.isWrite() && tool.getWrites() == AIToolWrites.DATABASE &&
                connection != null && connection.isConnectionReadOnly()
            ) {
                return finish(client, tool.getId(), access, connection, args, null, AIToolOutcome.READ_ONLY, start,
                    "Connection '" + connection.getName() + "' is read-only; this tool changes the database", null);
            }
            decision = AIToolPolicy.getInstance().resolve(connection, tool, access);
            if (decision.permission() == AIToolPermission.BLOCKED) {
                return finish(client, tool.getId(), access, connection, args, decision, AIToolOutcome.BLOCKED, start,
                    "Blocked by the tool policy: " + decision.reason(), null);
            }
            // Transaction safety (user rule): a call that would end an uncommitted transaction always asks
            List<AIPendingTransaction> pending = instance instanceof AIToolTransactionGuard guard
                ? guard.findPendingTransactions(context, args)
                : List.of();
            if (!pending.isEmpty() || decision.permission() == AIToolPermission.CONFIRM) {
                Confirmation confirmation = confirm(monitor, context, tool, access, connection, args, decision, instance, pending);
                if (confirmation.denial() != null) {
                    return finish(client, tool.getId(), access, connection, args, decision, AIToolOutcome.DENIED, start,
                        confirmation.denial(), null);
                }
                if (confirmation.resolved() != null) {
                    resolution = new Resolution(confirmation.resolved(), pending.size());
                }
            }
            if (monitor.isCanceled()) {
                return finish(client, tool.getId(), access, connection, args, decision, AIToolOutcome.CANCELED,
                    start, "Canceled", null, resolution);
            }
            AIFunctionResult result = instance.callFunction(context, args);
            String text = String.valueOf(result.getValue());
            if (result.getException() != null) {
                return finish(client, tool.getId(), access, connection, args, decision, AIToolOutcome.ERROR,
                    start, text, null, resolution);
            }
            return finish(client, tool.getId(), access, connection, args, decision, AIToolOutcome.SUCCESS,
                start, null, text, resolution);
        } catch (AIToolArgumentException e) {
            return finish(client, tool.getId(), access, connection, args, decision, AIToolOutcome.INVALID,
                start, e.getMessage(), null, resolution);
        } catch (Throwable e) {
            if (monitor.isCanceled()) {
                return finish(client, tool.getId(), access, connection, args, decision, AIToolOutcome.CANCELED,
                    start, "Canceled", null, resolution);
            }
            log.debug("AI tool " + tool.getId() + " failed", e);
            return finish(client, tool.getId(), access, connection, args, decision, AIToolOutcome.ERROR,
                start, describe(e), null, resolution);
        }
    }

    /**
     * The user's answer to a transaction question, already carried out before the tool ran.
     *
     * @param answer {@link AIToolConfirmationAnswer#COMMIT} or {@link AIToolConfirmationAnswer#ROLLBACK}
     * @param count  number of uncommitted transactions the answer ended
     */
    private record Resolution(@NotNull AIToolConfirmationAnswer answer, int count) {
        /** {@code COMMITTED} or {@code ROLLED_BACK}, as the result and the audit log name it. */
        @NotNull
        String action() {
            return answer == AIToolConfirmationAnswer.COMMIT ? "COMMITTED" : "ROLLED_BACK"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Adds what happened to the uncommitted transactions before the call to the result
     * ({@value #RESULT_TRANSACTIONS_BEFORE_CALL}). The tool runs after a Commit or Roll back answer (phase 1 contract), so
     * without this a "Roll back" answer to {@code commitTransaction} returned the same status as a commit: the model could
     * report a commit and the chat card only said "done".
     */
    @NotNull
    private static AIToolCallResult withResolution(@NotNull AIToolCallResult result, @Nullable Resolution resolution) {
        if (resolution == null) {
            return result;
        }
        JsonObject note = new JsonObject();
        boolean commit = resolution.answer() == AIToolConfirmationAnswer.COMMIT;
        note.addProperty("action", resolution.action());
        note.addProperty("count", resolution.count());
        note.addProperty("note", "Before this call the user chose " + (commit ? "Commit" : "Roll back") + ": " +
            resolution.count() + " uncommitted transaction(s) were " + (commit ? "committed" : "rolled back") +
            ". The rest of this result is what the tool did afterwards.");
        JsonObject json;
        try {
            JsonElement parsed = JsonParser.parseString(result.text());
            if (parsed.isJsonObject()) {
                json = parsed.getAsJsonObject();
            } else {
                json = new JsonObject();
                json.add("result", parsed);
            }
        } catch (RuntimeException e) {
            json = new JsonObject();
            json.addProperty("result", result.text());
        }
        json.add(RESULT_TRANSACTIONS_BEFORE_CALL, note);
        return new AIToolCallResult(result.outcome(), GSON.toJson(json));
    }

    /**
     * Error text of an exception chain: the messages without class names (the model reads them).
     */
    @NotNull
    public static String describe(@NotNull Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable e = error; e != null && text.length() < 4000; e = e.getCause() == e ? null : e.getCause()) {
            String message = e.getMessage();
            if (CommonUtils.isEmpty(message) || text.indexOf(message) >= 0) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(" | ");
            }
            text.append(message);
        }
        return text.isEmpty() ? error.getClass().getSimpleName() : text.toString();
    }

    @Nullable
    private static DBPDataSourceContainer resolveConnection(
        @NotNull AIFunctionDescriptor tool,
        @NotNull Map<String, Object> args,
        @Nullable AIFunctionContext chatContext
    ) throws AIToolArgumentException {
        if (tool.getParameter(AIToolConnections.PARAM_CONNECTION) == null) {
            return null;
        }
        String name = CommonUtils.toString(args.get(AIToolConnections.PARAM_CONNECTION), null);
        if (CommonUtils.isEmptyTrimmed(name)) {
            AIDatabaseContext databaseContext = chatContext == null ? null : chatContext.getContext();
            if (databaseContext != null) {
                DBPDataSourceContainer container = databaseContext.getExecutionContext().getDataSource().getContainer();
                args.put(AIToolConnections.PARAM_CONNECTION, container.getId());
                return container;
            }
            AIFunctionParameter parameter = tool.getParameter(AIToolConnections.PARAM_CONNECTION);
            if (parameter != null && parameter.isRequired()) {
                throw new AIToolArgumentException("Argument 'connection' is required (connection ID or name, see listConnections)");
            }
            return null;
        }
        return AIToolConnections.find(name);
    }

    private static void checkRequired(@NotNull AIFunctionDescriptor tool, @NotNull Map<String, Object> args)
        throws AIToolArgumentException {
        for (AIFunctionParameter parameter : tool.getParameters()) {
            if (parameter.isRequired() && CommonUtils.isEmptyTrimmed(CommonUtils.toString(args.get(parameter.getName()), null))) {
                throw new AIToolArgumentException("Argument '" + parameter.getName() + "' is required");
            }
        }
    }

    /**
     * Answer of {@link #confirm}.
     *
     * @param denial   why the call does not run, or null when it runs
     * @param resolved COMMIT or ROLLBACK when the pending transactions were ended as the user answered, otherwise null
     */
    private record Confirmation(@Nullable String denial, @Nullable AIToolConfirmationAnswer resolved) {
    }

    @NotNull
    private static Confirmation confirm(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIToolCallContext context,
        @NotNull AIFunctionDescriptor tool,
        @NotNull AIToolAccess access,
        @Nullable DBPDataSourceContainer connection,
        @NotNull Map<String, Object> args,
        @NotNull AIToolDecision decision,
        @NotNull AIFunction instance,
        @NotNull List<AIPendingTransaction> pending
    ) throws DBException {
        String reason = pending.isEmpty()
            ? decision.reason()
            : "the call would end " + pending.size() + " uncommitted transaction(s): commit, roll back or cancel";
        AIToolConfirmationHandler handler = AIAgentServices.getConfirmationHandler();
        if (handler == null) {
            return new Confirmation("Denied: this call needs the user's confirmation (" + reason +
                ") and no confirmation UI is available", null);
        }
        String preview = null;
        AIToolPreviewProvider.Kind previewKind = AIToolPreviewProvider.Kind.RUN;
        if (instance instanceof AIToolPreviewProvider provider) {
            preview = provider.getPreview(context, args);
            previewKind = provider.getPreviewKind(context);
        }
        AIToolConfirmationRequest request = new AIToolConfirmationRequest(
            context.getClient(),
            tool.getId(),
            tool.getName(),
            access,
            connection,
            AIToolAuditLog.maskSecrets(args),
            preview,
            previewKind,
            reason,
            List.copyOf(pending)
        );
        AIToolConfirmationAnswer answer = handler.confirm(monitor, request);
        if (pending.isEmpty()) {
            return new Confirmation(answer == AIToolConfirmationAnswer.ALLOW ? null : "Denied by the user", null);
        }
        boolean canCommit = pending.stream().allMatch(AIPendingTransaction::canCommit);
        if (answer == AIToolConfirmationAnswer.ROLLBACK || answer == AIToolConfirmationAnswer.COMMIT && canCommit) {
            ((AIToolTransactionGuard) instance).resolvePendingTransactions(context, args, answer);
            return new Confirmation(null, answer);
        }
        // ALLOW is not an answer to a transaction question: never commit or roll back silently
        return new Confirmation("Canceled by the user (uncommitted transaction left as it is)", null);
    }

    @NotNull
    private static AIToolCallResult finish(
        @NotNull AIToolClient client,
        @NotNull String toolId,
        @NotNull AIToolAccess access,
        @Nullable DBPDataSourceContainer connection,
        @NotNull Map<String, Object> args,
        @Nullable AIToolDecision decision,
        @NotNull AIToolOutcome outcome,
        long start,
        @Nullable String error,
        @Nullable String text
    ) {
        return finish(client, toolId, access, connection, args, decision, outcome, start, error, text, null);
    }

    /**
     * Audits the call and builds its result. A Commit / Roll back answer carried out before the call goes into both
     * ({@link #withResolution}): the audit log records the user's decision, not only the policy's.
     */
    @NotNull
    private static AIToolCallResult finish(
        @NotNull AIToolClient client,
        @NotNull String toolId,
        @NotNull AIToolAccess access,
        @Nullable DBPDataSourceContainer connection,
        @NotNull Map<String, Object> args,
        @Nullable AIToolDecision decision,
        @NotNull AIToolOutcome outcome,
        long start,
        @Nullable String error,
        @Nullable String text,
        @Nullable Resolution resolution
    ) {
        long duration = System.currentTimeMillis() - start;
        AIToolAuditLog.getInstance().append(AIToolAuditLog.newEntry(
            client, toolId, access, connection, args, decision, outcome, duration, error,
            resolution == null ? null : resolution.action(), resolution == null ? 0 : resolution.count()));
        if (outcome == AIToolOutcome.SUCCESS && text != null) {
            if (text.length() > MAX_RESULT_CHARS) {
                JsonObject cut = new JsonObject();
                cut.addProperty("truncatedText", text.substring(0, MAX_RESULT_CHARS));
                cut.addProperty("note", "Result was cut at " + MAX_RESULT_CHARS + " characters; ask for fewer rows or columns");
                return withResolution(new AIToolCallResult(outcome, GSON.toJson(cut)), resolution);
            }
            return withResolution(new AIToolCallResult(outcome, text), resolution);
        }
        JsonObject json = new JsonObject();
        json.addProperty("error", error == null ? outcome.name() : error);
        json.addProperty("outcome", outcome.name());
        json.addProperty("tool", toolId);
        if (decision != null) {
            json.addProperty("policy", decision.permission().name());
            json.addProperty("policyReason", decision.reason());
        }
        return withResolution(new AIToolCallResult(outcome, GSON.toJson(json)), resolution);
    }
}
