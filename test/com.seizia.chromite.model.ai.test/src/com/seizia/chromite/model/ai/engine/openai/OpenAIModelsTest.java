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
package com.seizia.chromite.model.ai.engine.openai;

import com.seizia.chromite.junit.ChromiteUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * OpenAI model name helpers.
 *
 * 변경 이력:
 * - 2026-11-06 dbeaver.com AI 모델 카탈로그 제거: 카탈로그에서 모델 메타데이터를 찾던 테스트
 *   (modelMetadataComesFromEachProvidersCache, copilotDoesNotFallBackToFirstPartyOpenAIMetadata)를 삭제했다.
 */
public class OpenAIModelsTest extends ChromiteUnitTest {

    @Test
    public void effectiveModelNameNullShouldReturnNull() {
        //when
        var result = OpenAIModels.getEffectiveModelName(null);
        //then
        Assertions.assertNull(result);
    }

    @Test
    public void effectiveModelNameUnknownUppercaseShouldReturnKnownModelUppercase() {
        //given
        var inputModelName = "some-UNKNOWN-MODEL";
        //when
        var result = OpenAIModels.getEffectiveModelName(inputModelName);
        //then
        Assertions.assertEquals(inputModelName, result);
    }

}
