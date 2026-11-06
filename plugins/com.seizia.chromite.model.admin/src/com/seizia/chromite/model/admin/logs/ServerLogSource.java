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

import java.util.List;

/**
 * 서버 로그 화면에서 고를 수 있는 로그 하나(SQL Server 오류 로그·에이전트 로그, PostgreSQL 서버 로그 형식, MySQL 오류 로그·느린 쿼리 로그,
 * Oracle 경고 로그)와 그 보관본·수준 목록.
 *
 * 설계: 로그인 권한이나 서버 설정 때문에 읽을 수 없는 로그도 목록에 두고 {@link #unavailableReason()}으로 이유(무엇을 받거나 켜야 하는가)를 보인다.
 * 화면은 읽을 수 없는 로그를 고르면 읽지 않고 안내만 보인다.
 *
 * @param id                identifies the log in {@link ServerLogQuery#sourceId()}
 * @param label             name in the log combo (localized)
 * @param unavailableReason why it cannot be read (localized), or null if it can
 * @param archives          files or archive numbers to choose from, newest first (empty: one log only)
 * @param levels            level filter choices (empty: the log has no levels)
 * @param sourceLabel       header of the source column (process, subsystem, user), or null if the log has none
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 서버 로그 (docs/chromite/swing-admin-tools.md)
 */
public record ServerLogSource(
    @NotNull String id,
    @NotNull String label,
    @Nullable String unavailableReason,
    @NotNull List<Option> archives,
    @NotNull List<Option> levels,
    @Nullable String sourceLabel
) {

    /**
     * A choice of a combo: the id goes into the query, the label is shown.
     */
    public record Option(@NotNull String id, @NotNull String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public boolean isAvailable() {
        return unavailableReason == null;
    }

    /**
     * A log that cannot be read.
     */
    @NotNull
    public static ServerLogSource unavailable(@NotNull String id, @NotNull String label, @NotNull String reason) {
        return new ServerLogSource(id, label, reason, List.of(), List.of(), null);
    }

    @Override
    public String toString() {
        return label;
    }
}
