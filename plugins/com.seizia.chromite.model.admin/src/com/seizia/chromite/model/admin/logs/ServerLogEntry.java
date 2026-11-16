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

import java.time.LocalDateTime;

/**
 * 서버 로그 항목 하나.
 *
 * 설계: 시각은 로그에 적힌 그대로(서버 로그의 시간대)다. 클라이언트 시간대로 바꾸면 서버의 다른 기록(파일, 다른 도구)과 맞춰 보기 어렵다.
 * 메시지는 표에 한 줄로 보이고 아래 상세 칸에 전체와 {@link #detail()}(SQLSTATE, 문장, 모듈 같은 부가 정보)을 보인다.
 *
 * @param time    time written in the log, or null if it has none
 * @param level   level as the database names it (ERROR, Warning, Critical), or null
 * @param source  process, subsystem or user of the entry, or null
 * @param message the message
 * @param detail  additional lines (one field per line), or null
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 서버 로그 (docs/chromite/swing-admin-tools.md)
 */
public record ServerLogEntry(
    @Nullable LocalDateTime time,
    @Nullable String level,
    @Nullable String source,
    @NotNull String message,
    @Nullable String detail
) {
}
