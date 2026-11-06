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
import com.seizia.chromite.model.ai.AIConstants;
import com.seizia.chromite.model.ai.engine.copilot.CopilotProperties;
import com.seizia.chromite.model.ai.engine.openai.OpenAIEngine;
import com.seizia.chromite.model.ai.engine.openai.OpenAIProperties;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.junit.ChromiteUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

/**
 * Context budget after selecting a model.
 *
 * 설계: 모델을 고를 때 컨텍스트 크기는 제공자 목록의 값이나 보수적 기본값만 쓰는지 검증한다.
 *
 * 변경 이력:
 * - 2026-11-06 dbeaver.com AI 모델 카탈로그 제거: 카탈로그 픽스처와 카탈로그 캐시 값을 쓰던
 *   missingMetadataUsesCachedModelContextWindow 테스트를 삭제했다.
 */
class AIModelSelectionTest extends ChromiteUnitTest {
    @ParameterizedTest
    @MethodSource("largeContextProperties")
    void switchingToSmallerModelReducesContextBudget(@NotNull AIEngineProperties properties) throws Exception {
        properties.setModel("gpt-4.1");

        properties.selectModel(new AIModel("gpt-4o", 128_000, Set.of(AIModelFeature.CHAT)));

        Assertions.assertEquals("gpt-4o", properties.getModel());
        Assertions.assertEquals(128_000, properties.getContextWindowSize());
    }

    @ParameterizedTest
    @MethodSource("smallContextProperties")
    void switchingToLargerModelIncreasesContextBudget(@NotNull AIEngineProperties properties) throws Exception {
        properties.setModel("gpt-4o");

        properties.selectModel(new AIModel("gpt-4.1", 1_048_576, Set.of(AIModelFeature.CHAT)));

        Assertions.assertEquals("gpt-4.1", properties.getModel());
        Assertions.assertEquals(1_048_576, properties.getContextWindowSize());
    }

    @ParameterizedTest
    @MethodSource("largeContextProperties")
    void missingMetadataDoesNotRetainPreviousModelWindow(@NotNull AIEngineProperties properties) throws Exception {
        properties.setModel("gpt-4.1");

        properties.selectModel(new AIModel("custom-model-without-metadata", null, Set.of(AIModelFeature.CHAT)));

        Assertions.assertEquals("custom-model-without-metadata", properties.getModel());
        if (properties instanceof OpenAIProperties) {
            Assertions.assertEquals(AIConstants.DEFAULT_CONTEXT_WINDOW_SIZE, properties.getContextWindowSize());
        } else {
            Assertions.assertNull(properties.getContextWindowSize());
        }
    }

    @Test
    void unknownModelHasUsableContextBudget() throws Exception {
        OpenAIProperties properties = new OpenAIProperties();
        properties.setContextWindowSize(1_048_576);
        properties.selectModel(new AIModel("llama3", null, Set.of()));

        try (OpenAIEngine<OpenAIProperties> engine = new OpenAIEngine<>(properties)) {
            Assertions.assertEquals(AIConstants.DEFAULT_CONTEXT_WINDOW_SIZE, engine.getContextWindowSize(new VoidProgressMonitor()));
        }
    }

    @NotNull
    private static Stream<AIEngineProperties> largeContextProperties() {
        return propertiesWithContextWindow(1_048_576);
    }

    @NotNull
    private static Stream<AIEngineProperties> smallContextProperties() {
        return propertiesWithContextWindow(128_000);
    }

    @NotNull
    private static Stream<AIEngineProperties> propertiesWithContextWindow(int contextWindowSize) {
        OpenAIProperties openAi = new OpenAIProperties();
        openAi.setContextWindowSize(contextWindowSize);
        CopilotProperties copilot = new CopilotProperties();
        copilot.setContextWindowSize(contextWindowSize);
        return Stream.of(openAi, copilot);
    }
}
