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

/**
 * Permission class of an agent tool.
 *
 * 설계: 정책의 기본값과 운영 연결 막기가 이 분류로 정해진다(docs/chromite/ai-agent.md "권한 정책"). 순서가 위험도 순이라
 * 호출별 분류({@link AIToolAccessClassifier})는 {@link #max}로 정적 분류보다 낮아지지 않게 합친다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public enum AIToolAccess {
    /** Reads only: metadata, data, system views. */
    READ,
    /** Changes data, schema, files or server state. */
    WRITE,
    /** Destructive or disruptive: drop, restore, kill session. */
    DANGEROUS;

    @NotNull
    public AIToolAccess max(@NotNull AIToolAccess other) {
        return other.ordinal() > ordinal() ? other : this;
    }

    public boolean isWrite() {
        return this != READ;
    }
}
