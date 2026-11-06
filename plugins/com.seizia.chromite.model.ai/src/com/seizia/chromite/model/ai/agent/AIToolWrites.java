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

import java.util.Locale;

/**
 * What a WRITE or DANGEROUS tool changes ({@code writes} attribute of the function extension).
 *
 * 설계: 연결의 "읽기 전용" 설정은 DB를 바꾸는 도구만 막아야 한다. 파일로 내보내기({@link #FILE})나 연결 끊기({@link #NONE})는
 * 읽기 전용 연결에서도 쓸 수 있어야 하므로 분류와 따로 둔다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public enum AIToolWrites {
    DATABASE("database"),
    FILE("file"),
    NONE("none");

    private final String id;

    AIToolWrites(@NotNull String id) {
        this.id = id;
    }

    @NotNull
    public String getId() {
        return id;
    }

    @NotNull
    public static AIToolWrites fromId(@Nullable String id) {
        if (id != null) {
            String key = id.trim().toLowerCase(Locale.ROOT);
            for (AIToolWrites value : values()) {
                if (value.id.equals(key)) {
                    return value;
                }
            }
        }
        return DATABASE;
    }
}
