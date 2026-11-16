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
package com.seizia.chromite.model.admin.logs;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 서버 로그 한 쪽(page)을 읽는 조건: 로그, 보관본, 시간 범위, 찾을 글자, 수준, 이어 읽을 위치, 쪽 크기.
 *
 * 설계: 시간 범위는 두 가지다. "최근 N"({@link #last()})은 서버의 지금 시각을 기준으로 해서 클라이언트와 서버의 시간대·시계 차이에 영향받지 않는다.
 * 직접 지정({@link #from()}, {@link #to()})은 로그에 적힌 시각(서버의 로그 시간대) 그대로 비교한다. 결과는 늘 최신 항목부터이고,
 * 다음 쪽은 앞 쪽이 돌려준 {@link ServerLogPage#nextCursor()}에서 이어 읽는다(큰 로그를 한 번에 다 읽지 않는다).
 *
 * @param sourceId  {@link ServerLogSource#id()}
 * @param archiveId {@link ServerLogSource#archives()} id, or null for the current log
 * @param last      entries of the last period (server time), or null
 * @param from      entries at or after this log time, or null
 * @param to        entries at or before this log time, or null
 * @param text      text the message must contain (any case), or null
 * @param levelId   {@link ServerLogSource#levels()} id, or null for all levels
 * @param cursor    where the previous page stopped, or null for the newest entries
 * @param limit     entries per page
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 서버 로그 (docs/chromite/swing-admin-tools.md)
 */
public record ServerLogQuery(
    @NotNull String sourceId,
    @Nullable String archiveId,
    @Nullable Duration last,
    @Nullable LocalDateTime from,
    @Nullable LocalDateTime to,
    @Nullable String text,
    @Nullable String levelId,
    @Nullable String cursor,
    int limit
) {

    /**
     * Log time as SQL literal text ("2026-11-07 05:30:00").
     */
    public static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); //$NON-NLS-1$

    /**
     * The same query from another position.
     */
    @NotNull
    public ServerLogQuery withCursor(@Nullable String newCursor) {
        return new ServerLogQuery(sourceId, archiveId, last, from, to, text, levelId, newCursor, limit);
    }

    /**
     * The search text, or null when empty.
     */
    @Nullable
    public String searchText() {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
