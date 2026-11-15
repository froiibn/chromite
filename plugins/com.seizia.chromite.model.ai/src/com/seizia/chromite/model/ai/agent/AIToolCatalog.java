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
import com.seizia.chromite.model.ai.AIFunctionAlias;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionParameter;
import com.seizia.chromite.model.ai.AIFunctionPurpose;
import com.seizia.chromite.model.ai.registry.AIToolboxInternalDescriptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent tool catalog: the functions of the internal toolbox ({@code com.seizia.chromite.ai.function} extension point)
 * that the chat and the MCP server share.
 *
 * 설계: 채팅의 도구 상자 관리자({@code AIToolboxRegistry})는 사용자의 도구 상자 설정 파일(ai-tools.json)과 외부 MCP 도구 상자까지 읽는다.
 * MCP 서버가 다른 MCP 서버의 도구를 다시 내보내면 안 되므로, 내부 도구 상자만 따로 한 번 만들어 쓴다. 등록 정보는 같은 확장점에서 오므로
 * 채팅과 MCP의 도구 목록이 같다.
 *
 * 별칭(2026-11-15): 합치거나 이름을 바꾼 도구의 옛 ID와 인수의 옛 이름은 {@code plugin.xml}의 {@code <alias>}와 인수의 {@code aliases}에 적고,
 * {@link #resolveCall}이 지금 도구·이름으로 바꾼다. 실행기, 채팅의 기록 다시 보내기, 대화가 찾은 도구가 같은 함수를 써서 옛 이름이 어느 길로 와도
 * 같은 도구·인수·정책이 된다. {@code tools/list}에는 지금 도구만 보인다(별칭은 함수가 아니다).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md)
 * - 2026-11-15 옛 ID·인수 이름을 지금 도구로 바꾸는 {@link #resolveCall}, {@link #canonicalId} (docs/chromite/ai-agent.md "별칭")
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
     * A tool by its ID or full ID ({@code db_<id>}), or by a former ID of a renamed or merged tool ({@code <alias>}).
     */
    @Nullable
    public static AIFunctionDescriptor getTool(@NotNull String id) {
        AIFunctionDescriptor tool = getToolbox().getFunctionById(plainId(id));
        return tool == null || tool.isUI() ? null : tool;
    }

    /**
     * The current ID of a tool ID that may be a former one (an alias), without the {@code db_} prefix; the ID itself when no
     * tool has it (a removed tool).
     */
    @NotNull
    public static String canonicalId(@NotNull String id) {
        AIFunctionDescriptor tool = getTool(id);
        return tool == null ? plainId(id) : tool.getId();
    }

    @NotNull
    private static String plainId(@NotNull String id) {
        String prefix = AIConstants.INTERNAL_TOOLBOX_ID + "_"; //$NON-NLS-1$
        return id.startsWith(prefix) ? id.substring(prefix.length()) : id;
    }

    /**
     * A call as the tool it reaches sees it.
     *
     * @param tool      the tool
     * @param arguments the arguments under the tool's current names, with the operation a former tool id selects
     * @param calledAs  the former tool id the call used, or null when it used the current one
     */
    public record Call(@NotNull AIFunctionDescriptor tool, @NotNull Map<String, Object> arguments, @Nullable String calledAs) {
    }

    /**
     * Maps a call made with a current or a former tool id and argument names to the current tool and names
     * (docs/chromite/ai-agent.md "별칭"): a former argument name becomes the current one, and a former tool id adds the
     * arguments its {@code <alias>} sets (a merged tool's operation) or defaults. Returns null for an unknown tool.
     *
     * @throws AIToolArgumentException when the call gives a parameter under its current and a former name with different values
     */
    @Nullable
    public static Call resolveCall(@NotNull String id, @NotNull Map<String, Object> arguments) throws AIToolArgumentException {
        AIFunctionDescriptor tool = getTool(id);
        if (tool == null) {
            return null;
        }
        String plain = plainId(id);
        AIFunctionAlias alias = null;
        if (!plain.equals(tool.getId())) {
            for (AIFunctionAlias candidate : tool.getAliases()) {
                if (candidate.id().equals(plain)) {
                    alias = candidate;
                }
            }
        }
        Map<String, Object> mapped = new LinkedHashMap<>(arguments);
        for (AIFunctionParameter parameter : tool.getParameters()) {
            for (String former : parameter.getAliases()) {
                if (!mapped.containsKey(former)) {
                    continue;
                }
                Object value = mapped.remove(former);
                Object current = mapped.get(parameter.getName());
                if (current != null && value != null && !String.valueOf(current).equals(String.valueOf(value))) {
                    throw new AIToolArgumentException("Arguments '" + parameter.getName() + "' and '" + former + "' (its former name) " +
                        "have different values; pass only '" + parameter.getName() + "'");
                }
                if (current == null) {
                    mapped.put(parameter.getName(), value);
                }
            }
        }
        if (alias != null) {
            alias.defaults().forEach(mapped::putIfAbsent);
            mapped.putAll(alias.arguments());
        }
        return new Call(tool, mapped, alias == null ? null : alias.id());
    }

    // The three original schema functions need the chat's database context; outside the chat they can't run
    private static boolean isUsableWithoutChat(@NotNull AIFunctionDescriptor function) {
        return function.getCategoryId() == null || !function.getCategoryId().equals("schema.discovery"); //$NON-NLS-1$
    }
}
