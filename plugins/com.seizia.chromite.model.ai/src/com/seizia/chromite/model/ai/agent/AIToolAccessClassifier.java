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

import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;

import java.util.Map;

/**
 * Optional interface of a tool implementation: the permission class of one call, from its arguments.
 * The executor takes the higher of this and the static class, so a classifier can only raise it.
 *
 * 설계: {@code runSql}의 {@code DROP}, 읽기 전용이 아닌 쿼리의 실제 계획처럼 같은 도구라도 인수에 따라 위험도가 다르다.
 * 정적 분류를 가장 높은 쪽으로 두면 읽기 호출도 매번 확인을 받아 쓸모가 없어지므로, 호출마다 올리는 방식을 택했다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "분류")
 * - 2026-11-15 {@link #getHighestAccess}: 호출 전에 알아야 하는 곳(MCP {@code tools/list}의 readOnlyHint·destructiveHint)이 호출별 분류 도구에
 *   보수적인 값을 쓰게 (도구 정리, docs/chromite/ai-agent.md "로컬 MCP 서버")
 */
public interface AIToolAccessClassifier {

    @NotNull
    AIToolAccess classify(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments) throws DBException;

    /**
     * The highest class a call of the tool can get, for places that describe the tool before any call. The default is the
     * conservative DANGEROUS; an implementation lowers it for tools that never raise their class this far.
     */
    @NotNull
    default AIToolAccess getHighestAccess(@NotNull AIFunctionDescriptor tool) {
        return AIToolAccess.DANGEROUS;
    }
}
