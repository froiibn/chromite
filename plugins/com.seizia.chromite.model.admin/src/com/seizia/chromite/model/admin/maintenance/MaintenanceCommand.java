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
package com.seizia.chromite.model.admin.maintenance;

import org.jkiss.code.NotNull;

import java.util.List;

/**
 * 실행할 유지 관리 문장과 그 미리 보기, 실행 전에 알릴 경고(예: VACUUM FULL은 테이블 전체에 배타 잠금을 건다).
 *
 * 설계: 미리 보기에 보인 문장을 그대로 실행한다(보인 것과 다른 문장이 실행되지 않게). 문장은 하나씩 실행해 사이마다 취소를 볼 수 있다.
 *
 * @param statements statements to run, in order (each one is sent alone)
 * @param preview    the statements as a script, for the preview (with the dialect's delimiter)
 * @param warnings   what to know before running (localized), shown above the preview and in the confirmation
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
public record MaintenanceCommand(@NotNull List<String> statements, @NotNull String preview, @NotNull List<String> warnings) {

    /**
     * A command whose preview ends each statement with {@code delimiter} (";" or a line with "/" after PL/SQL blocks).
     */
    @NotNull
    public static MaintenanceCommand of(@NotNull List<String> statements, @NotNull String delimiter, @NotNull List<String> warnings) {
        StringBuilder preview = new StringBuilder();
        for (String statement : statements) {
            if (!preview.isEmpty()) {
                preview.append('\n');
            }
            preview.append(statement).append(delimiter).append('\n');
        }
        return new MaintenanceCommand(List.copyOf(statements), preview.toString(), List.copyOf(warnings));
    }
}
