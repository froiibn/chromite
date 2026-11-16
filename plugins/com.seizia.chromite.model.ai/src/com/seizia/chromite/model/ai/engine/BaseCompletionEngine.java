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
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

/**
 * Base class for completion engines.
 *
 * 설계: 모델 메타데이터(컨텍스트 크기, 기능)는 각 엔진이 사용자가 연결한 제공자의 모델 목록 API에서 받은 값과
 * 사용자 설정만 쓴다. 사용자가 연결하지 않은 서버(예: DBeaver가 운영하던 모델 카탈로그)로 요청을 보내지 않기
 * 위해서다(docs/chromite/ai.md).
 *
 * 변경 이력:
 * - 2026-11-06 dbeaver.com AI 모델 카탈로그 제거: 카탈로그 조회(getCatalogProviderId, getModelCatalog,
 *   getCachedModelCatalog, getCachedCatalogEntry, findCatalogEntry, mapModels)를 삭제했다.
 *   컨텍스트 크기는 설정값만 쓴다(없으면 호출자 AIEngineRequestFactory가 AIConstants.DEFAULT_CONTEXT_WINDOW_SIZE를 쓴다).
 *   요청 temperature는 카탈로그의 미지원 표시로 빼지 않는다. 제공자가 temperature를 거부하면
 *   클라이언트(OpenAIClientResponses, CopilotClientResponses)가 temperature를 빼고 다시 보낸다.
 */
public abstract class BaseCompletionEngine<PROPS extends AIEngineProperties> implements AIEngine<PROPS> {

    protected final PROPS properties;

    public BaseCompletionEngine(@NotNull PROPS properties) {
        this.properties = properties;
    }

    @NotNull
    @Override
    public PROPS getProperties() {
        return properties;
    }

    @Nullable
    protected String model() throws DBException {
        return properties.getEffectiveModel();
    }

    @Override
    public int getContextWindowSize(@NotNull DBRProgressMonitor monitor) throws DBException {
        Integer contextSize = properties.getEffectiveContextWindowSize();
        if (contextSize != null && contextSize > 0) {
            return contextSize;
        }
        throw new DBException("Context window size is not set for the model: " + properties.getModel());
    }

    @Nullable
    protected Double getRequestTemperature() throws DBException {
        return properties.getRequestTemperature() == null ? null : temperature();
    }

    protected double temperature() throws DBException {
        return properties.getTemperature();
    }
}
