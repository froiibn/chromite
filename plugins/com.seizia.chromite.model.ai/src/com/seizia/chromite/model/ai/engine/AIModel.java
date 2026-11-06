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
package com.seizia.chromite.model.ai.engine;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * An AI model as reported by the provider the user connected.
 *
 * 설계: 한도와 기능은 제공자 모델 목록 응답에서만 채운다. 값이 없으면 null(알 수 없음)로 두고 호출자가 사용자 설정이나
 * 기본값을 쓴다.
 *
 * 변경 이력:
 * - 2026-11-06 dbeaver.com AI 모델 카탈로그 제거: isTemperatureEditable()에서 삭제된 TEMPERATURE_UNSUPPORTED 검사를 뺐다.
 */
public record AIModel(
    @NotNull String name,
    @Nullable Integer contextWindowSize,
    @NotNull Set<AIModelFeature> features,
    double defaultTemperature,
    @Nullable Integer inputTokenLimit,
    @Nullable Integer outputTokenLimit,
    @Nullable Double maxTemperature,
    boolean imageInputSupported
) {

    public AIModel(
        @NotNull String name,
        @Nullable Integer contextWindowSize,
        @NotNull Set<AIModelFeature> features,
        double defaultTemperature
    ) {
        this(name, contextWindowSize, features, defaultTemperature, null, null, null, true);
    }

    public AIModel(
        @NotNull String name,
        @Nullable Integer contextWindowSize,
        @NotNull Set<AIModelFeature> features,
        double defaultTemperature,
        boolean imageInputSupported
    ) {
        this(name, contextWindowSize, features, defaultTemperature, null, null, null, imageInputSupported);
    }

    public AIModel(
        @NotNull String name,
        @Nullable Integer contextWindowSize,
        @NotNull Set<AIModelFeature> features,
        double defaultTemperature,
        @Nullable Integer inputTokenLimit,
        @Nullable Integer outputTokenLimit,
        @Nullable Double maxTemperature
    ) {
        this(name, contextWindowSize, features, defaultTemperature, inputTokenLimit, outputTokenLimit, maxTemperature, true);
    }

    public AIModel(@NotNull String name, @Nullable Integer contextWindowSize, @NotNull Set<AIModelFeature> features) {
        this(name, contextWindowSize, features, 0.0);
    }

    @NotNull
    public AIModel withFeature(@NotNull AIModelFeature feature, @Nullable Boolean supported) {
        if (supported == null) {
            return this;
        }
        boolean updatedImageInputSupport = feature == AIModelFeature.VISION ? supported : imageInputSupported;
        if (features.contains(feature) == supported && updatedImageInputSupport == imageInputSupported) {
            return this;
        }
        Set<AIModelFeature> updated = new HashSet<>(features);
        if (supported) {
            updated.add(feature);
        } else {
            updated.remove(feature);
        }
        return new AIModel(name, contextWindowSize, Set.copyOf(updated), defaultTemperature,
            inputTokenLimit, outputTokenLimit, maxTemperature, updatedImageInputSupport);
    }

    public boolean isTemperatureEditable() {
        return !features.contains(AIModelFeature.ALWAYS_DEFAULT_TEMPERATURE);
    }
}
