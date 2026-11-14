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

import java.util.Map;

/**
 * Optional interface of a tool implementation: what one call changes ({@link AIToolWrites}), when it differs by call.
 *
 * 설계: 26.1.0에서 백업 도구 둘을 합쳤다(SQL Server {@code BACKUP}은 DB 서버에 쓰고, PostgreSQL·MySQL 덤프는 이 컴퓨터의 새 파일만 쓴다).
 * 읽기 전용 연결은 DB를 바꾸는 호출({@link AIToolWrites#DATABASE})만 막으므로, 합친 도구의 정적 값을 {@code file}로 두면 서버 백업이 읽기 전용
 * 연결에서 풀리고, {@code database}로 두면 전에 되던 덤프가 막힌다. 그래서 정적 값은 엄격한 쪽({@code database})으로 두고 호출마다 정한다.
 * {@link AIToolAccessClassifier}와 달리 정적 값보다 덜 엄격한 값을 돌려줄 수 있으므로, 합치기 전 도구가 실제로 그 값이었던 호출에만 쓴다.
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성 (도구 정리, docs/chromite/ai-agent.md "별칭")
 */
public interface AIToolWritesClassifier {

    @NotNull
    AIToolWrites classifyWrites(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments);
}
