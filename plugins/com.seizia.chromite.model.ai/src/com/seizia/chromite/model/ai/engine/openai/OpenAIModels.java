/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package org.jkiss.dbeaver.model.ai.engine.openai;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.ai.engine.AIModel;
import org.jkiss.dbeaver.model.ai.engine.AIModelFeature;
import org.jkiss.dbeaver.model.ai.engine.openai.dto.OAIModel;
import org.jkiss.utils.CommonUtils;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/**
 * OpenAI model helpers.
 *
 * 설계: 모델 정보는 OpenAI 호환 엔드포인트의 GET models 응답(id, context_length)과 모델 ID로 추정한 기능만 쓴다.
 * 공식 OpenAI API의 GET /v1/models는 context_length를 주지 않으므로, 그때 컨텍스트 크기는 사용자 설정이나
 * AIConstants.DEFAULT_CONTEXT_WINDOW_SIZE를 쓴다(docs/chromite/ai.md).
 *
 * 변경 이력:
 * - 2026-11-06 dbeaver.com AI 모델 카탈로그 제거: CATALOG_PROVIDER_ID, getModelByName(), findCatalogEntry()와
 *   스냅샷 이름 패턴, 카탈로그 항목을 받던 fromApiModel() 오버로드, isTemperatureEditable()을 삭제했다.
 */
public final class OpenAIModels {
    private static final Pattern EMBEDDING_MODEL_PATTERN = Pattern.compile("text-embedding-.*");

    private OpenAIModels() {
    }

    /**
     * Returns the effective model name for the given model name.
     * If the model name is null or empty, returns null.
     * Model IDs are preserved as supplied by the provider.
     *
     * @param modelName the model name to check
     * @return the effective model name
     */
    @Nullable
    public static String getEffectiveModelName(@Nullable String modelName) {
        if (CommonUtils.isEmpty(modelName)) {
            return null;
        }
        return modelName;
    }

    public static boolean isOpenAIEndpoint(@Nullable String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return true;
        }
        try {
            return "api.openai.com".equalsIgnoreCase(URI.create(baseUrl).getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @NotNull
    public static AIModel fromApiModel(@NotNull OAIModel model) {
        return fromApiModel(model, detectModelFeatures(model.id()));
    }

    @NotNull
    public static AIModel fromApiModel(@NotNull OAIModel model, @NotNull Set<AIModelFeature> features) {
        Integer contextSize = model.contextLength();
        return new AIModel(
            model.id(), contextSize != null && contextSize > 0 ? contextSize : null, features
        );
    }

    @NotNull
    public static Set<AIModelFeature> detectModelFeatures(@NotNull String modelName) {
        Set<AIModelFeature> features = new HashSet<>();

        if (isChatModel(modelName)) {
            features.add(AIModelFeature.CHAT);
            features.add(AIModelFeature.STREAMING);
        }

        if (EMBEDDING_MODEL_PATTERN.matcher(modelName).matches()) {
            features.add(AIModelFeature.EMBEDDING);
        }

        if (modelName.startsWith("whisper-") || modelName.contains("-transcribe")) {
            features.add(AIModelFeature.SPEECH_TO_TEXT);
        }

        return features;
    }

    private static final List<String> CHAT_EXCLUDED_KEYWORDS = List.of(
        "search",
        "research",
        "moderation",
        "realtime",
        "audio",
        "transcribe",
        "image"
    );

    private static boolean isChatModel(@NotNull String modelName) {
        if (!(modelName.startsWith("gpt-") || modelName.startsWith("o"))) {
            return false;
        }
        for (String keyword : CHAT_EXCLUDED_KEYWORDS) {
            if (modelName.contains(keyword)) {
                return false;
            }
        }
        return true;
    }
}
