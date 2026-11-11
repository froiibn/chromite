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
package com.seizia.chromite.model.ai;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * Represents a parameter definition for an AI function/tool call
 *
 * 변경 이력:
 * - 2026-11-12 {@link #getShortDescription()}: 최소 도구 묶음의 짧은 인수 설명 (docs/chromite/ai-agent.md "도구 묶음")
 */
public interface AIFunctionParameter {

    @NotNull
    String getName();

    @NotNull
    String getType();

    @Nullable
    String getDescription();

    /**
     * A shorter description for the minimal tool set of the Chromite Agent chat, or null to send {@link #getDescription()}.
     */
    @Nullable
    default String getShortDescription() {
        return null;
    }

    boolean isRequired();

    @Nullable
    String getDefaultValue();

    @Nullable
    String[] getValidValues();

    @Nullable
    default AIFunctionParameterTransformer getTransformer() {
        return null;
    }

    @Nullable
    default String getTransformerSuffix() {
        return null;
    }
}
