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
package com.seizia.chromite.model.ai.tools;

import org.jkiss.code.NotNull;

import java.util.Map;

/**
 * Result size limits of one tool call (docs/chromite/ai-agent.md "공통 규칙").
 *
 * 설계: 모델의 문맥 창과 앱 메모리를 보호하려면 행 수, 칸 길이, 결과 전체 크기를 모두 잘라야 한다. 행 수만 자르면 큰 텍스트 열 몇 개로도
 * 수 MB가 될 수 있다. 자른 사실은 결과에 {@code truncated}로 알려 모델이 페이지를 넘기거나 열을 줄이게 한다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 *
 * @param maxRows      rows per result set
 * @param maxCellChars characters per value
 * @param maxBytes     approximate characters of the whole result
 */
public record AgentLimits(int maxRows, int maxCellChars, int maxBytes) {

    public static final int DEFAULT_ROWS = 100;
    public static final int MAX_ROWS = 10_000;
    public static final int DEFAULT_CELL_CHARS = 4000;
    public static final int MAX_CELL_CHARS = 100_000;
    public static final int DEFAULT_BYTES = 1024 * 1024;

    public static final String PARAM_LIMIT = "limit"; //$NON-NLS-1$
    public static final String PARAM_MAX_CELL_CHARS = "maxCellChars"; //$NON-NLS-1$

    @NotNull
    public static AgentLimits of(@NotNull Map<String, Object> arguments) {
        return of(arguments, DEFAULT_ROWS);
    }

    @NotNull
    public static AgentLimits of(@NotNull Map<String, Object> arguments, int defaultRows) {
        int rows = AgentArgs.getInt(arguments, PARAM_LIMIT, defaultRows);
        int cells = AgentArgs.getInt(arguments, PARAM_MAX_CELL_CHARS, DEFAULT_CELL_CHARS);
        return new AgentLimits(
            Math.max(1, Math.min(rows, MAX_ROWS)),
            Math.max(16, Math.min(cells, MAX_CELL_CHARS)),
            DEFAULT_BYTES
        );
    }

    @NotNull
    public AgentLimits withRows(int rows) {
        return new AgentLimits(rows, maxCellChars, maxBytes);
    }
}
