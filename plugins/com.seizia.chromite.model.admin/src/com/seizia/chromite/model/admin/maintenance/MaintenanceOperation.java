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
import org.jkiss.code.Nullable;

import java.util.List;

/**
 * 대상 하나에 할 수 있는 유지 관리 동작(예: VACUUM, REINDEX, Rebuild, Gather Statistics)과 그 옵션.
 *
 * 설계: 동작 ID는 방언 안에서만 뜻이 있고({@link MaintenanceDialect#buildCommand}가 받는다), 화면은 이름·설명·옵션만 보인다.
 * 유지 관리 탭의 후보 행은 권장 동작의 ID를 가지고 있어({@link MaintenanceAction}) 대화상자가 그 동작을 골라 연다.
 *
 * @param id          operation id (stable, not shown)
 * @param label       name (localized)
 * @param description what it does, one sentence (localized), or null
 * @param options     options in display order
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
public record MaintenanceOperation(
    @NotNull String id,
    @NotNull String label,
    @Nullable String description,
    @NotNull List<MaintenanceOption> options
) {

    @Override
    public String toString() {
        return label;
    }
}
