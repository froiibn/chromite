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
package com.seizia.chromite.model.ai.engine.anthropic;

import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.ai.engine.*;
import com.seizia.chromite.model.ai.utils.DisposableLazyValue;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Set;

/**
 * Anthropic (Claude) completion engine: Messages API with tool use.
 *
 * 설계: Ollama 엔진({@code OllamaCompletionEngine})과 같은 짜임새다. HTTP 클라이언트는 처음 쓸 때 만들고({@link DisposableLazyValue}),
 * 요청 본문은 {@link AnthropicClient#buildRequest}가 만든다. 모델 목록은 {@code GET /v1/models}에서 받는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "엔진")
 */
public class AnthropicCompletionEngine extends BaseCompletionEngine<AnthropicProperties> {

    private final DisposableLazyValue<AnthropicClient, DBException> client = new DisposableLazyValue<>() {
        @NotNull
        @Override
        protected AnthropicClient initialize() throws DBException {
            String apiKey = properties.getEffectiveApiKey();
            if (CommonUtils.isEmptyTrimmed(apiKey)) {
                throw new DBException("Anthropic API key is not configured");
            }
            AnthropicClient newClient = new AnthropicClient(properties.getEffectiveBaseUrl(), apiKey);
            newClient.setTimeout(properties.getTimeout());
            return newClient;
        }

        @Override
        protected void onDispose(@NotNull AnthropicClient disposedValue) {
            disposedValue.close();
        }
    };

    public AnthropicCompletionEngine(@NotNull AnthropicProperties properties) {
        super(properties);
    }

    @NotNull
    @Override
    public List<AIModel> getModels(@NotNull DBRProgressMonitor monitor) throws DBException {
        return client.getInstance().loadModels(monitor).stream()
            .map(id -> new AIModel(id, null, Set.of(
                AIModelFeature.CHAT, AIModelFeature.STREAMING, AIModelFeature.TOOL_CALL, AIModelFeature.VISION)))
            .toList();
    }

    @NotNull
    @Override
    public AIEngineResponse requestCompletion(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIEngineRequest request
    ) throws DBException {
        return client.getInstance().createMessage(monitor, buildBody(request));
    }

    @Override
    public void requestCompletionStream(
        @NotNull DBRProgressMonitor monitor,
        @NotNull AIEngineRequest request,
        @NotNull AIEngineResponseConsumer listener
    ) throws DBException {
        client.getInstance().createMessageStream(buildBody(request), listener);
    }

    @NotNull
    private JsonObject buildBody(@NotNull AIEngineRequest request) throws DBException {
        String model = model();
        if (CommonUtils.isEmptyTrimmed(model)) {
            throw new DBException("Anthropic model is not configured");
        }
        return AnthropicClient.buildRequest(
            request.getMessages(),
            request.getFunctions(),
            model,
            properties.getMaxTokens(),
            getRequestTemperature()
        );
    }

    @Override
    public void close() throws DBException {
        client.dispose();
    }
}
