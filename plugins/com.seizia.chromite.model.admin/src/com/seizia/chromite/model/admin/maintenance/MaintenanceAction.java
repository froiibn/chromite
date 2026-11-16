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

/**
 * 유지 관리 탭의 후보 행이 권하는 동작: 대상과 동작 ID. 행의 값({@code AdminGrid#getRowTag})으로 붙고, 화면은 이것으로 대화상자를 연다.
 *
 * @param target      what to maintain
 * @param operationId operation to select first ({@link MaintenanceOperation#id()})
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
public record MaintenanceAction(@NotNull MaintenanceTarget target, @NotNull String operationId) {
}
