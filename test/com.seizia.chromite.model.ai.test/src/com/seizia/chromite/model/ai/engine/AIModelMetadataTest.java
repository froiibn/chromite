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
package org.jkiss.dbeaver.model.ai.engine;

import com.google.gson.Gson;
import org.jkiss.dbeaver.model.ai.engine.copilot.dto.CopilotModel;
import org.jkiss.dbeaver.model.ai.engine.openai.OpenAIModels;
import org.jkiss.dbeaver.model.ai.engine.openai.dto.OAIModel;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * Model metadata parsed from provider model list responses.
 *
 * 설계: 제공자 응답(OpenAI 호환 models, Copilot models)에서 읽은 값만 검증한다.
 *
 * 변경 이력:
 * - 2026-11-06 dbeaver.com AI 모델 카탈로그 제거: 카탈로그 보강(AIModelCatalogEntry.enrich, CopilotModel.toAIModel(entry),
 *   OpenAIModels.fromApiModel(model, entry))을 검증하던 테스트 7개를 삭제했다.
 */
class AIModelMetadataTest extends DBeaverUnitTest {
    private static final Gson GSON = new Gson();

    @Test
    void featureOverridesPreserveMetadataAndDoNotMutateOriginal() {
        AIModel nativeModel = new AIModel("test-model", 64000, Set.of(AIModelFeature.CHAT, AIModelFeature.VISION),
            0.7, 48000, 16000, 2.0);
        AIModel updated = nativeModel.withFeature(AIModelFeature.VISION, false).withFeature(AIModelFeature.TOOL_CALL, true);

        Assertions.assertTrue(nativeModel.features().contains(AIModelFeature.VISION));
        Assertions.assertFalse(nativeModel.features().contains(AIModelFeature.TOOL_CALL));
        Assertions.assertEquals(Set.of(AIModelFeature.CHAT, AIModelFeature.TOOL_CALL), updated.features());
        Assertions.assertEquals(64000, updated.contextWindowSize());
        Assertions.assertEquals(48000, updated.inputTokenLimit());
        Assertions.assertEquals(16000, updated.outputTokenLimit());
        Assertions.assertEquals(0.7, updated.defaultTemperature());
        Assertions.assertEquals(2.0, updated.maxTemperature());
        Assertions.assertFalse(updated.imageInputSupported());
        Assertions.assertTrue(updated.withFeature(AIModelFeature.VISION, true).imageInputSupported());
        Assertions.assertSame(updated, updated.withFeature(AIModelFeature.TOOL_CALL, null));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> updated.features().add(AIModelFeature.VISION));
    }

    @Test
    void providerMetadataDoesNotUsePerModelTemperaturePresets() {
        OAIModel response = GSON.fromJson("""
            {"id":"gpt-5","context_length":128000}
            """, OAIModel.class);

        AIModel model = OpenAIModels.fromApiModel(response);

        Assertions.assertEquals(128_000, model.contextWindowSize());
        Assertions.assertEquals(0.0, model.defaultTemperature());
        Assertions.assertFalse(model.features().contains(AIModelFeature.ALWAYS_DEFAULT_TEMPERATURE));
    }

    @Test
    void readsContextForUnknownCompatibleModel() {
        AIModel model = OpenAIModels.fromApiModel(GSON.fromJson("""
            {"id":"custom-model","context_length":32000}
            """, OAIModel.class));

        Assertions.assertEquals(32_000, model.contextWindowSize());
    }

    @Test
    void missingOrInvalidContextRemainsUnknownEvenForPreviouslyHardcodedModels() {
        for (String context : new String[]{"null", "0", "-1"}) {
            AIModel model = OpenAIModels.fromApiModel(GSON.fromJson(
                "{\"id\":\"gpt-5\",\"context_length\":" + context + "}", OAIModel.class
            ));
            Assertions.assertNull(model.contextWindowSize());
        }
        Assertions.assertNull(OpenAIModels.fromApiModel(GSON.fromJson("{\"id\":\"custom\"}", OAIModel.class)).contextWindowSize());
    }

    @Test
    void copilotPreservesSeparateLimitsAndExplicitCapabilities() {
        CopilotModel response = GSON.fromJson("""
            {"id":"custom-chat","capabilities":{
              "type":"chat",
              "limits":{"max_context_window_tokens":128000,"max_prompt_tokens":96000,"max_output_tokens":32000},
              "supports":{"streaming":true,"tool_calls":true,"vision":false,"reasoning_effort":["low","high"]}
            }}
            """, CopilotModel.class);

        AIModel model = response.toAIModel();

        Assertions.assertEquals(128_000, model.contextWindowSize());
        Assertions.assertEquals(96_000, model.inputTokenLimit());
        Assertions.assertEquals(32_000, model.outputTokenLimit());
        Assertions.assertFalse(model.imageInputSupported());
        Assertions.assertEquals(Set.of(
            AIModelFeature.CHAT, AIModelFeature.STREAMING, AIModelFeature.TOOL_CALL, AIModelFeature.REASONING
        ), model.features());
    }

    @Test
    void missingCopilotCapabilitiesDoNotInventLimitsOrStreamingSupport() {
        AIModel model = GSON.fromJson("{\"id\":\"legacy-chat\"}", CopilotModel.class).toAIModel();

        Assertions.assertEquals(Set.of(AIModelFeature.CHAT), model.features());
        Assertions.assertNull(model.contextWindowSize());
        Assertions.assertNull(model.inputTokenLimit());
        Assertions.assertNull(model.outputTokenLimit());
        Assertions.assertNull(model.maxTemperature());
    }
}
