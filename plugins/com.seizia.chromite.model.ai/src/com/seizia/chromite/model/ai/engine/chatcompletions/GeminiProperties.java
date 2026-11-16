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
 * Settings of Google Gemini (Gemini API with an API key) through its OpenAI-compatible endpoint.
 *
 * 설계: Gemini API의 OpenAI 호환 엔드포인트({@value #DEFAULT_BASE_URL}, {@code Authorization: Bearer <API 키>})가 Chat Completions의 스트리밍과
 * {@code tools}/{@code tool_calls}를 받으므로 자체 API({@code generateContent}) 대신 vLLM과 같은 엔진을 쓴다. 키는 꼭 필요하다.
 * 모델 목록의 ID가 {@code models/} 접두어를 달고 올 수 있어 요청 이름에서는 뗀다({@link #toModelName}). Vertex AI(Google Cloud 프로젝트·서비스 계정)는
 * 이번 범위가 아니다(docs/chromite/ai-agent.md "Chat Completions 엔진").
 *
 * 변경 이력:
 * - 2026-11-11 최초 작성 (app round 2 #35)
 */
public class GeminiProperties extends ChatCompletionsProperties {

    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"; //$NON-NLS-1$

    private static final String API_KEY_SECRET = "gemini.apiKey"; //$NON-NLS-1$
    private static final String MODEL_ID_PREFIX = "models/"; //$NON-NLS-1$

    @NotNull
    @Override
    public String getProviderName() {
        return "Google Gemini"; //$NON-NLS-1$
    }

    @Nullable
    @Override
    public String getDefaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    @Override
    public boolean isApiKeyRequired() {
        return true;
    }

    @NotNull
    @Override
    protected String getApiKeySecretName() {
        return API_KEY_SECRET;
    }

    @NotNull
    @Override
    public String toModelName(@NotNull String modelId) {
        return modelId.startsWith(MODEL_ID_PREFIX) ? modelId.substring(MODEL_ID_PREFIX.length()) : modelId;
    }
}
