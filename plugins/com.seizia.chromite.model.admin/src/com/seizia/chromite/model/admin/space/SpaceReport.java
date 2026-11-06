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
package com.seizia.chromite.model.admin.space;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.List;

/**
 * 공간 사용량 한 번 읽은 결과: 표들, 큰 테이블 표의 범위(데이터베이스·스키마·소유자) 목록, 권한 안내.
 *
 * 설계: 권한이 모자라도 읽을 수 있는 것은 보인다. 무엇이 빠졌고 무엇을 받아야 하는지는 {@link #notices()}(현지화된 문장)로 알리고
 * 화면은 안내 줄에 보인다(Activity Monitor의 {@code ServerAdminAccess#notice}와 같은 방식).
 *
 * @param grids   tables to show, in tab order
 * @param scopes  names the table list can be limited to (databases, schemas or owners), in display order
 * @param scope   the scope the table list was read for; null means all
 * @param notices what the login could not read and what to grant (localized), empty if nothing is missing
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 공간 사용량 (docs/chromite/swing-admin-tools.md)
 */
public record SpaceReport(
    @NotNull List<SpaceGrid> grids,
    @NotNull List<String> scopes,
    @Nullable String scope,
    @NotNull List<String> notices
) {
}
