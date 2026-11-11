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
package com.seizia.chromite.model.ai.engine.chatcompletions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.seizia.chromite.model.ai.AIMessage;
import com.seizia.chromite.model.ai.engine.AIEngineRequest;
import com.seizia.chromite.model.ai.engine.AIEngineResponse;
import com.seizia.chromite.model.ai.engine.AIEngineResponseConsumer;
import com.seizia.chromite.model.ai.engine.AIModel;
import com.seizia.chromite.model.ai.engine.AIModelFeature;
import com.seizia.chromite.model.ai.engine.BaseCompletionEngine;
import com.seizia.chromite.model.ai.utils.DisposableLazyValue;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Completion engine over the OpenAI-compatible Chat Completions API, shared by the vLLM and Google Gemini providers
 * ({@link ChatCompletionsProperties} subclasses).
 *
 * 설계: Anthropic 엔진과 같은 짜임새다. HTTP 클라이언트는 처음 쓸 때 만들고({@link DisposableLazyValue}), 요청 본문은
 * {@link ChatCompletionsClient#buildRequest}가 만든다. 모델 목록은 {@code GET models}에서 받는다. 설정 화면의 Test는 {@link #checkToolCalls}로
 * 서버가 도구 호출을 받는지도 확인한다(도구 호출 안내가 있는 제공자, 곧 vLLM만).
 *
 * 변경 이력:
 * - 2026-11-11 최초 작성 (app round 2 #34, #35, docs/chromite/ai-agent.md "Chat Completions 엔진")
 */
public class ChatCompletionsEngine extends BaseCompletionEngine<ChatCompletionsProperties> {

    private static final String PROBE_TOOL = "chromite_probe"; //$NON-NLS-1$

    private final DisposableLazyValue<ChatCompletionsClient, DBException> client = new DisposableLazyValue<>() {
        @NotNull
        @Override
        protected ChatCompletionsClient initialize() throws DBException {
            String baseUrl = properties.getEffectiveBaseUrl();
            if (CommonUtils.isEmptyTrimmed(baseUrl)) {
                throw new DBException(properties.getProviderName() + " base URL is not configured");
            }
            if (properties.isApiKeyRequired() && CommonUtils.isEmptyTrimmed(properties.getApiKey())) {
                throw new DBException(properties.getProviderName() + " API key is not configured");
            }
            ChatCompletionsClient newClient = new ChatCompletionsClient(
                baseUrl, properties.getApiKey(), properties.getProviderName(), properties.getToolCallHint());
            newClient.setTimeout(properties.getTimeout());
            return newClient;
        }

        @Override
        protected void onDispose(@NotNull ChatCompletionsClient disposedValue) {
            disposedValue.close();
        }
    };

    public ChatCompletionsEngine(@NotNull ChatCompletionsProperties properties) {
        super(properties);
    }

    @NotNull
    @Override
    public List<AIModel> getModels(@NotNull DBRProgressMonitor monitor) throws DBException {
        List<AIModel> models = new ArrayList<>();
        for (String id : client.getInstance().loadModels(monitor)) {
            models.add(new AIModel(properties.toModelName(id), null, Set.of(
                AIModelFeature.CHAT, AIModelFeature.STREAMING, AIModelFeature.TOOL_CALL)));
        }
        return models;
    }

    @NotNull
    @Override
    public AIEngineResponse requestCompletion(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIEngineRequest request
    ) throws DBException {
        return client.getInstance().createCompletion(monitor, buildBody(request));
    }

    @Override
    public void requestCompletionStream(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIEngineRequest request,
        @NotNull AIEngineResponseConsumer listener
    ) throws DBException {
        client.getInstance().createCompletionStream(buildBody(request), listener);
    }

    /**
     * Asks the configured model for one token with one tool offered. Returns why the server does not take tool calls, or null
     * when it took the request (or the provider has no tool call hint, so no check is needed). Other failures are thrown.
     */
    @Nullable
    public String checkToolCalls(@NotNull DBRProgressMonitor monitor) throws DBException {
        String model = model();
        if (properties.getToolCallHint() == null || CommonUtils.isEmptyTrimmed(model)) {
            return null;
        }
        JsonObject body = ChatCompletionsClient.buildRequest(
            List.of(AIMessage.userMessage("Reply with OK.")), null, model, null); //$NON-NLS-1$
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object"); //$NON-NLS-1$ //$NON-NLS-2$
        parameters.add("properties", new JsonObject()); //$NON-NLS-1$
        JsonObject function = new JsonObject();
        function.addProperty("name", PROBE_TOOL); //$NON-NLS-1$
        function.addProperty("description", "Checks the connection; never needed"); //$NON-NLS-1$ //$NON-NLS-2$
        function.add("parameters", parameters); //$NON-NLS-1$
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function"); //$NON-NLS-1$ //$NON-NLS-2$
        tool.add("function", function); //$NON-NLS-1$
        JsonArray tools = new JsonArray();
        tools.add(tool);
        body.add("tools", tools); //$NON-NLS-1$
        body.addProperty("tool_choice", "auto"); //$NON-NLS-1$ //$NON-NLS-2$
        body.addProperty("max_tokens", 1); //$NON-NLS-1$
        try {
            client.getInstance().createCompletion(monitor, body);
            return null;
        } catch (ChatCompletionsClient.ToolCallsUnsupportedException e) {
            return e.getMessage();
        }
    }

    @NotNull
    private JsonObject buildBody(@NotNull AIEngineRequest request) throws DBException {
        String model = model();
        if (CommonUtils.isEmptyTrimmed(model)) {
            throw new DBException(properties.getProviderName() + " model is not configured");
        }
        return ChatCompletionsClient.buildRequest(request.getMessages(), request.getFunctions(), model, getRequestTemperature());
    }

    @Override
    public void close() throws DBException {
        client.dispose();
    }
}
