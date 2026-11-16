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
package com.seizia.chromite.model.ai;

import com.seizia.chromite.model.ai.agent.AIToolAccess;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.Map;

/**
 * A former id of an agent tool that was renamed or merged into another tool ({@code <alias>} of the
 * {@code com.seizia.chromite.ai.function} extension point).
 *
 * 설계: 26.1.0에서 도구를 합치고 이름을 바꾸면서(앱 변경 #64) 옛 ID를 한 판 동안 받는다. MCP 클라이언트 설정, 저장된 대화의 도구 호출, 대화가 찾은
 * 도구, 플레이북과 사용자 글이 옛 ID에 묶여 있기 때문이다. 별칭은 {@code plugin.xml} 한 곳에 적는다(코드의 대응표로 흩어 두지 않게). 합친 도구의
 * 옛 ID는 그 도구의 한 작업이므로, 부를 때 그 작업을 고르는 인수({@link #arguments()}, 예: {@code operation=dropTable})를 함께 적는다.
 * {@link #access()}는 옛 도구의 분류다. 정책 옮기기({@code AIToolPolicy})가 옛 규칙이 어떤 권한으로 판정되었는지 다시 계산하는 데 쓴다.
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성 (도구 정리, docs/chromite/ai-agent.md "별칭")
 *
 * @param id        the former tool id
 * @param access    permission class the former tool had, or null when it was the class of the tool
 * @param arguments arguments every call through the alias gets, replacing what the caller passed
 * @param defaults  arguments a call through the alias gets when the caller did not pass them
 * @param removeIn  edition in which the alias goes away, or null
 */
public record AIFunctionAlias(
    @NotNull String id,
    @Nullable AIToolAccess access,
    @NotNull Map<String, String> arguments,
    @NotNull Map<String, String> defaults,
    @Nullable String removeIn
) {
}
