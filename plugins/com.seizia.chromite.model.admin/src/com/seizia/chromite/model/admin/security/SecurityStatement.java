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
package com.seizia.chromite.model.admin.security;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * One statement of a security change script.
 *
 * 설계: 암호가 들어 있는 문은 실행할 글({@link #sql})과 보여 줄 글({@link #displaySql}, 암호 자리를 가림)을 따로 둔다.
 * 미리 보기·복사·쿼리 창으로 열기에는 가린 글만 나가고, 실행할 때는 쿼리 관리자 기록을 끈다
 * (DBeaver의 {@code setDisableSessionLogging(true)}와 같은 목적: {@code PostgreRoleManager.addRoleOptions},
 * {@code SQLServerLoginManager.addObjectCreateActions}).
 * SQL Server의 데이터베이스 사용자·권한 문은 그 데이터베이스에서 실행해야 하므로 {@link #database}를 둔다(스크립트에는 {@code USE}로 보인다).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 *
 * @param sql         statement to execute
 * @param displaySql  statement shown to the user (secrets masked)
 * @param database    database to run the statement in (SQL Server), or null for the connection's own
 * @param secret      the statement contains a password: do not log it
 */
public record SecurityStatement(
    @NotNull String sql,
    @NotNull String displaySql,
    @Nullable String database,
    boolean secret
) {

    @NotNull
    public static SecurityStatement of(@NotNull String sql) {
        return new SecurityStatement(sql, sql, null, false);
    }

    @NotNull
    public static SecurityStatement inDatabase(@Nullable String database, @NotNull String sql) {
        return new SecurityStatement(sql, sql, database, false);
    }
}
