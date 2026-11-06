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
package com.seizia.chromite.model.ai.agent;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.ai.AIConstants;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionPurpose;
import com.seizia.chromite.model.ai.registry.AIToolboxInternalDescriptor;

import java.util.List;

/**
 * The agent tool catalog: the functions of the internal toolbox ({@code com.seizia.chromite.ai.function} extension point)
 * that the chat and the MCP server share.
 *
 * 설계: 채팅의 도구 상자 관리자({@code AIToolboxRegistry})는 사용자의 도구 상자 설정 파일(ai-tools.json)과 외부 MCP 도구 상자까지 읽는다.
 * MCP 서버가 다른 MCP 서버의 도구를 다시 내보내면 안 되므로, 내부 도구 상자만 따로 한 번 만들어 쓴다. 등록 정보는 같은 확장점에서 오므로
 * 채팅과 MCP의 도구 목록이 같다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md)
 */
public final class AIToolCatalog {

    private static AIToolboxInternalDescriptor toolbox;

    private AIToolCatalog() {
    }

    @NotNull
    private static synchronized AIToolboxInternalDescriptor getToolbox() {
        if (toolbox == null) {
            toolbox = new AIToolboxInternalDescriptor();
        }
        return toolbox;
    }

    /**
     * Tools for a purpose (MCP: {@link AIFunctionPurpose#MCP}), without UI-only functions (they need an editor) and without the
     * original schema functions, which read the chat's database context (the agent tools take a connection argument instead).
     */
    @NotNull
    public static List<AIFunctionDescriptor> getTools(@NotNull AIFunctionPurpose purpose) {
        return getToolbox().getSupportedFunctions(purpose).stream()
            .filter(f -> !f.isUI())
            .filter(AIToolCatalog::isUsableWithoutChat)
            .toList();
    }

    /**
     * A tool by its ID or full ID ({@code db_<id>}).
     */
    @Nullable
    public static AIFunctionDescriptor getTool(@NotNull String id) {
        String prefix = AIConstants.INTERNAL_TOOLBOX_ID + "_"; //$NON-NLS-1$
        String plainId = id.startsWith(prefix) ? id.substring(prefix.length()) : id;
        AIFunctionDescriptor tool = getToolbox().getFunctionById(plainId);
        return tool == null || tool.isUI() ? null : tool;
    }

    // The three original schema functions need the chat's database context; outside the chat they can't run
    private static boolean isUsableWithoutChat(@NotNull AIFunctionDescriptor function) {
        return function.getCategoryId() == null || !function.getCategoryId().equals("schema.discovery"); //$NON-NLS-1$
    }
}
