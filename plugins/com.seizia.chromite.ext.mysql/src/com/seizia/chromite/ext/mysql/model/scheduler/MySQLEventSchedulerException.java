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
package com.seizia.chromite.ext.mysql.model.scheduler;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.model.DBPDataSource;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.Set;

/**
 * A failed event statement. A missing EVENT privilege gets a localized explanation as the message, with the server
 * error as the cause.
 *
 * 설계: SQL Server 에이전트의 {@code SQLServerAgentException}과 같은 분류다. MySQL·MariaDB는 권한이 없으면 1044(데이터베이스 접근 거부),
 * 1142(명령 거부), 1227(특정 권한 필요)을 낸다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class MySQLEventSchedulerException extends DBDatabaseException {

    private static final Set<Integer> PERMISSION_ERRORS = Set.of(1044, 1142, 1227);

    private final boolean permission;
    @Nullable
    private final String statement;

    private MySQLEventSchedulerException(
        @NotNull String message,
        @NotNull SQLException cause,
        @Nullable DBPDataSource dataSource,
        boolean permission,
        @Nullable String statement
    ) {
        super(message, cause, dataSource);
        this.permission = permission;
        this.statement = statement;
    }

    /**
     * @param statement the script statement that failed, or null for reads
     */
    @NotNull
    public static MySQLEventSchedulerException wrap(@NotNull SQLException error, @Nullable DBPDataSource dataSource, @Nullable String statement) {
        boolean permission = false;
        for (SQLException e = error; e != null; e = e.getNextException()) {
            if (PERMISSION_ERRORS.contains(e.getErrorCode())) {
                permission = true;
                break;
            }
        }
        String message = permission ? MySQLEventSchedulerMessages.error_permission : CommonUtils.notEmpty(error.getMessage());
        return new MySQLEventSchedulerException(message, error, dataSource, permission, statement);
    }

    public boolean isPermissionError() {
        return permission;
    }

    @Nullable
    public String getStatement() {
        return statement;
    }
}
