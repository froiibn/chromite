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
package com.seizia.chromite.model.admin;

import org.jkiss.code.Nullable;

/**
 * 로그인 권한에 따라 Activity Monitor가 할 수 있는 일(세션 읽기, 잠금 읽기, 세션 끝내기)과 화면에 보일 안내.
 *
 * 설계: 권한이 없을 때 서버 오류(ORA-00942, "insufficient privilege")를 그대로 보이는 대신 무엇이 없고 무엇을 받아야 하는지
 * 먼저 알린다(docs/chromite/swing-admin-tools.md "권한 확인"). 확인은 연결마다 한 번, 첫 작업에서 한다({@link ServerAdminProfile#checkAccess}).
 * 확인할 방법이 없는 DB는 {@link #FULL}이다(지금처럼 서버가 거부하면 그 오류를 보인다).
 *
 * @param notice         안내 문구(현지화됨), 없으면 null
 * @param canReadSessions 세션 목록을 읽을 수 있는가
 * @param canReadLocks   잠금 대기를 읽을 수 있는가
 * @param canEndSessions 세션 끝내기·쿼리 취소 명령을 쓸 수 있는가
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: PostgreSQL·Oracle 권한 확인 (docs/chromite/swing-admin-tools.md)
 */
public record ServerAdminAccess(@Nullable String notice, boolean canReadSessions, boolean canReadLocks, boolean canEndSessions) {

    /**
     * Everything allowed, nothing to say (also when the database has no check).
     */
    public static final ServerAdminAccess FULL = new ServerAdminAccess(null, true, true, true);
}
