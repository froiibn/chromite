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
package com.seizia.chromite.ext.postgresql.model.cron;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.model.DBPDataSource;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.Set;

/**
 * A failed pg_cron call. A missing privilege and a missing extension get a localized explanation as the message, with
 * the server error as the cause.
 *
 * 설계: SQL Server 에이전트의 {@code SQLServerAgentException}과 같은 분류를 SQLSTATE로 한다. 42501(insufficient_privilege)은 cron 스키마의
 * USAGE나 함수 실행 권한이 없을 때, 3F000(invalid_schema_name)·42883(undefined_function)은 확장이 없을 때 나온다.
 * 남의 작업을 바꾸려 하면 pg_cron이 일반 오류(P0001 "could not find valid entry")를 내므로 그 글은 그대로 보인다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class PostgreCronException extends DBDatabaseException {

    public enum Kind {
        PERMISSION,
        NOT_INSTALLED,
        OTHER
    }

    private static final Set<String> PERMISSION_STATES = Set.of("42501"); //$NON-NLS-1$
    private static final Set<String> NOT_INSTALLED_STATES = Set.of("3F000", "42883", "42P01"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    @NotNull
    private final Kind kind;
    @Nullable
    private final String statement;

    private PostgreCronException(
        @NotNull String message,
        @NotNull SQLException cause,
        @Nullable DBPDataSource dataSource,
        @NotNull Kind kind,
        @Nullable String statement
    ) {
        super(message, cause, dataSource);
        this.kind = kind;
        this.statement = statement;
    }

    /**
     * @param statement the script statement that failed, or null for reads
     */
    @NotNull
    public static PostgreCronException wrap(@NotNull SQLException error, @Nullable DBPDataSource dataSource, @Nullable String statement) {
        Kind kind = Kind.OTHER;
        for (SQLException e = error; e != null; e = e.getNextException()) {
            if (PERMISSION_STATES.contains(e.getSQLState())) {
                kind = Kind.PERMISSION;
                break;
            }
            if (NOT_INSTALLED_STATES.contains(e.getSQLState())) {
                kind = Kind.NOT_INSTALLED;
                break;
            }
        }
        String message = switch (kind) {
            case PERMISSION -> PostgreCronMessages.error_permission;
            case NOT_INSTALLED -> PostgreCronMessages.error_not_installed;
            case OTHER -> CommonUtils.notEmpty(error.getMessage());
        };
        return new PostgreCronException(message, error, dataSource, kind, statement);
    }

    @NotNull
    public Kind getKind() {
        return kind;
    }

    @Nullable
    public String getStatement() {
        return statement;
    }
}
