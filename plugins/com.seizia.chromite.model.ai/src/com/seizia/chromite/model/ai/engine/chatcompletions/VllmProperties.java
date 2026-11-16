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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * Settings of a vLLM server (OpenAI-compatible server of a self-hosted model).
 *
 * 설계: 서버 주소는 설치마다 달라 기본값을 두지 않는다(설정 화면은 {@value #EXAMPLE_BASE_URL}를 자리 표시로만 보인다). API 키는 서버를
 * {@code --api-key}로 띄웠을 때만 필요하다. 도구 호출은 서버를 {@code --enable-auto-tool-choice --tool-call-parser <모델별 파서>}로 띄워야 되므로,
 * 서버가 거부하면 그 방법을 알린다({@link #getToolCallHint}).
 *
 * 변경 이력:
 * - 2026-11-11 최초 작성 (app round 2 #34, docs/chromite/ai-agent.md "Chat Completions 엔진")
 */
public class VllmProperties extends ChatCompletionsProperties {

    public static final String EXAMPLE_BASE_URL = "http://localhost:8000/v1"; //$NON-NLS-1$

    private static final String API_KEY_SECRET = "vllm.apiKey"; //$NON-NLS-1$

    @NotNull
    @Override
    public String getProviderName() {
        return "vLLM"; //$NON-NLS-1$
    }

    @Nullable
    @Override
    public String getDefaultBaseUrl() {
        return null;
    }

    @Override
    public boolean isApiKeyRequired() {
        return false;
    }

    @NotNull
    @Override
    protected String getApiKeySecretName() {
        return API_KEY_SECRET;
    }

    @NotNull
    @Override
    public String getToolCallHint() {
        return "Start the vLLM server with --enable-auto-tool-choice and the --tool-call-parser of the model " +
            "(for example hermes, llama3_json or mistral)";
    }
}
