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
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.ai.agent.AIToolAccess;
import com.seizia.chromite.model.ai.agent.AIToolWrites;

import java.util.List;

/**
 * AI function metadata.
 *
 * 변경 이력:
 * - 2026-11-07 {@link #getAccess()}, {@link #getWrites()} 추가: 에이전트 도구 권한 정책의 분류 (docs/chromite/ai-agent.md)
 * - 2026-11-12 {@link #getToolSet()}, {@link #getShortAiDescription()} 추가: 채팅 요청의 도구 묶음과 최소 묶음의 짧은 설명
 *   (docs/chromite/ai-agent.md "도구 묶음")
 * - 2026-11-12 {@link #isAlwaysConfirmed()} 추가: 정책과 상관없이 늘 사용자가 확인하는 도구를 권한 정책 표가 사실대로 보이게
 *   (앱 변경 #71 g, docs/chromite/ai-agent.md "권한 정책")
 * - 2026-11-15 {@link #getAliases()}, {@link #getOperationParameter()}, {@link #getAddedIn()}: 합치거나 이름을 바꾼 도구의 옛 ID를 한 판 동안 받고, 합친 도구의
 *   작업을 감사 기록에 남김 (docs/chromite/ai-agent.md "별칭")
 */
public interface AIFunctionDescriptor {

    @NotNull
    AIToolbox getToolbox();

    @NotNull
    String getId();

    @Nullable
    default String getLegacyId() {
        return null;
    }

    /**
     * Former ids of an agent tool that was renamed or merged into this one, accepted for one edition
     * (docs/chromite/ai-agent.md "별칭").
     */
    @NotNull
    default List<AIFunctionAlias> getAliases() {
        return List.of();
    }

    /**
     * Edition that added the tool (a tool that merged or renamed former ones), or null for an older tool: the tool policy
     * migration knows that it had no rules of its own before.
     */
    @Nullable
    default String getAddedIn() {
        return null;
    }

    /**
     * Name of the parameter whose value is the operation of a call (a tool that merges former tools), or null.
     */
    @Nullable
    default String getOperationParameter() {
        return null;
    }

    @NotNull
    default String getFullId() {
        return getFullFunctionId(getToolbox().getToolboxId(), getId());
    }

    @NotNull
    String getName();
    
    @Nullable
    DBPImage getIcon();

    @NotNull
    AIFunctionType getType();

    @NotNull
    AIFunctionPurpose getPurpose();

    /**
     * Permission class used by the agent tool policy (docs/chromite/ai-agent.md "권한 정책").
     */
    @NotNull
    default AIToolAccess getAccess() {
        return getType() == AIFunctionType.ACTION ? AIToolAccess.WRITE : AIToolAccess.READ;
    }

    /**
     * What a WRITE/DANGEROUS tool changes; read-only connections refuse only {@link AIToolWrites#DATABASE}.
     */
    @NotNull
    default AIToolWrites getWrites() {
        return AIToolWrites.DATABASE;
    }

    /**
     * Whether the user confirms every call that has something to do, whatever the tool policy allows: the transaction
     * guard asks Commit / Roll back / Cancel before commitTransaction and rollbackTransaction end the agent's transaction.
     * The permission class stays what it is (rollbackTransaction is READ so that production connections do not block it);
     * this only lets the Tool Policy table say that the tool always asks.
     */
    default boolean isAlwaysConfirmed() {
        return false;
    }

    @Nullable
    String getCategoryId();

    @Nullable
    String getAiDescription();

    /**
     * Smallest tool set of the Chromite Agent chat that contains the tool: {@code minimal} (also in core), {@code core},
     * or null when only the full set has it (docs/chromite/ai-agent.md "도구 묶음").
     */
    @Nullable
    default String getToolSet() {
        return null;
    }

    /**
     * One or two sentences sent instead of {@link #getAiDescription()} in the minimal tool set, or null.
     */
    @Nullable
    default String getShortAiDescription() {
        return null;
    }

    /**
     * Function which returns information about surrounding UI (e.g. open windows, active editor, etc)
     */
    boolean isUI();

    /**
     * Indicates whether the function is a system function.
     * System functions are not shown in the UI and can be executed without confirmation.
     */
    boolean isSystem();

    boolean isEnabledByDefault();

    default boolean isOmitConfirmation() {
        return false;
    }

    @NotNull
    AIFunctionAllowMode getDefaultAllowMode();

    @NotNull
    AIFunctionParameter[] getParameters();

    @Nullable
    AIFunctionParameter getParameter(@NotNull String name);

    @NotNull
    String[] getDependsOn();

    @NotNull
    default AIFunctionVerifier.FunctionState getFunctionState(@NotNull AIFunctionContext functionContext) {
        return getInstance() instanceof AIFunctionVerifier verifier ?
            verifier.getFunctionState(functionContext, this) :
            AIFunctionVerifier.FunctionState.APPLICABLE;
    }

    @NotNull
    AIFunction getInstance();

    @NotNull
    static String getFullFunctionId(@NotNull String toolboxId, @NotNull String toolId) {
        return toolboxId + "_" + toolId;
    }
}
