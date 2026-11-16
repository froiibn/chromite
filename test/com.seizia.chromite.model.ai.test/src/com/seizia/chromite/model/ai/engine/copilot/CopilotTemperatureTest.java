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
package com.seizia.chromite.model.ai.engine.copilot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.ai.AIMessage;
import com.seizia.chromite.model.ai.engine.AIEngineRequest;
import com.seizia.chromite.model.ai.engine.AIEngineResponseConsumer;
import com.seizia.chromite.model.ai.engine.copilot.dto.CopilotChatRequest;
import com.seizia.chromite.model.ai.engine.copilot.dto.CopilotSessionToken;
import com.seizia.chromite.model.ai.engine.openai.dto.OAIResponsesResponse;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.junit.ChromiteUnitTest;
import org.jkiss.utils.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.stream.Stream;

/**
 * Saved temperature in Copilot requests.
 *
 * 설계: 저장한 temperature가 두 요청 형식(Responses, 레거시 chat)에 그대로 들어가고, 비워 두면 빠지는지 검증한다.
 *
 * 변경 이력:
 * - 2026-11-06 dbeaver.com AI 모델 카탈로그 제거: 카탈로그의 temperature 지원 여부 차원(supported)과 카탈로그 픽스처를 삭제했다.
 */
class CopilotTemperatureTest extends ChromiteUnitTest {
    @NotNull
    static Stream<Arguments> requestSettings() {
        return Stream.of("", "0.0", "0.7").flatMap(value -> Stream.of(false, true)
            .map(streaming -> Arguments.of(value, streaming)));
    }

    @ParameterizedTest
    @MethodSource("requestSettings")
    void preservesSavedTemperatureInBothRequestFormats(@NotNull String value, boolean streaming) throws Exception {
        CopilotProperties properties = new CopilotProperties();
        properties.setModel("test-model");
        if (value.isEmpty()) {
            properties.clearTemperature();
        } else {
            properties.setTemperature(Double.parseDouble(value));
        }
        Gson gson = new Gson();
        properties = gson.fromJson(gson.toJson(properties), CopilotProperties.class);
        CopilotClientResponses copilotClient = Mockito.mock(CopilotClientResponses.class);
        Mockito.when(copilotClient.chat(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(CopilotUtils.GSON.fromJson(
            "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}",
            OAIResponsesResponse.class
        ));
        try (
            CopilotCompletionEngine<CopilotProperties> engine = new CopilotCompletionEngine<>(properties) {
                @NotNull
                @Override
                protected CopilotClientResponses createClient(@NotNull String baseAuthUrl) {
                    return copilotClient;
                }

                @NotNull
                @Override
                protected CopilotSessionToken requestSessionToken(@NotNull DBRProgressMonitor monitor) {
                    return new CopilotSessionToken("test-token", null);
                }
            }
        ) {
            AIEngineRequest request = new AIEngineRequest(AIMessage.userMessage("test"));
            ArgumentCaptor<Pair> captured = ArgumentCaptor.forClass(Pair.class);
            if (streaming) {
                AIEngineResponseConsumer consumer = Mockito.mock(AIEngineResponseConsumer.class);
                engine.requestCompletionStream(monitor, request, consumer);
                Mockito.verify(copilotClient).createChatCompletionStream(
                    Mockito.same(monitor), Mockito.any(), captured.capture(), Mockito.same(consumer)
                );
            } else {
                engine.requestCompletion(monitor, request);
                Mockito.verify(copilotClient).chat(Mockito.same(monitor), Mockito.any(), captured.capture());
            }
            for (Object payload : new Object[]{captured.getValue().getFirst(), captured.getValue().getSecond()}) {
                JsonObject json = payload instanceof CopilotChatRequest chat
                    ? JsonParser.parseString(CopilotUtils.serializeChatRequest(chat)).getAsJsonObject()
                    : CopilotUtils.GSON.toJsonTree(payload).getAsJsonObject();
                Assertions.assertFalse(json.has("useServerDefaultTemperature"));
                if (value.isEmpty()) {
                    Assertions.assertFalse(json.has("temperature"));
                } else {
                    Assertions.assertEquals(Double.parseDouble(value), json.get("temperature").getAsDouble());
                }
            }
        }
    }
}
