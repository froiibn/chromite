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
package com.seizia.chromite.model.admin.replication;

import com.seizia.chromite.model.admin.AdminGrid;
import org.jkiss.code.NotNull;

import java.util.List;

/**
 * 복제 상태를 한 번 읽은 결과: 요약(역할, 구성 여부, 주요 설정)과 DB별 표(대기 서버, 슬롯, 복제본 연결, 가용성 그룹 등), 권한 안내.
 *
 * 설계: 표마다 {@code AdminGrid#getEmptyText()}에 "구성되지 않음" 문구를 둔다. 복제가 없는 서버는 흔하고 오류가 아니므로 빈 표 대신 그 문구를 보인다.
 *
 * @param grids   the summary first, then the lists
 * @param notices what the login could not read and what to grant (localized)
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 6 복제 상태 (docs/chromite/swing-admin-tools.md)
 */
public record ReplicationReport(@NotNull List<AdminGrid> grids, @NotNull List<String> notices) {
}
