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

import com.seizia.chromite.model.admin.AdminGrid;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.List;

/**
 * 유지 관리 탭에서 한 번 읽은 결과: 후보·상태 표들(조각난 인덱스, 오래된 통계, 무효 객체, VACUUM이 필요한 테이블),
 * 범위(데이터베이스·스키마·소유자) 목록, 권한 안내.
 *
 * 설계: 공간 사용량의 {@code SpaceReport}와 같은 모양이다. 행마다 권장 동작({@link MaintenanceAction})이 붙어 화면이 바로 대화상자를 연다.
 *
 * @param grids   candidate and status lists, in tab order
 * @param scopes  names the lists can be limited to, in display order
 * @param scope   the scope the lists were read for; null means all
 * @param notices what the login could not read and what to grant (localized)
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
public record MaintenanceReport(
    @NotNull List<AdminGrid> grids,
    @NotNull List<String> scopes,
    @Nullable String scope,
    @NotNull List<String> notices
) {
}
