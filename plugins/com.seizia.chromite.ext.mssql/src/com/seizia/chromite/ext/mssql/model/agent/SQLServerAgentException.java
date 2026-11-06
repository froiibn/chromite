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
package com.seizia.chromite.ext.mssql.model.agent;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.model.DBPDataSource;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.Set;

/**
 * A failed SQL Server Agent call. Missing msdb permissions and a stopped agent get a localized explanation as the
 * message, with the server error as the cause (the error dialog shows both lines, never a stack trace first).
 *
 * 설계: 권한이 모자라면 msdb 프로시저가 229(EXECUTE 거부), 916(msdb 접근 불가) 같은 일반 권한 오류를 낸다. 사용자가 무엇이 필요한지
 * 알 수 있게 오류 번호로 분류해 에이전트 역할을 알려 주는 문구를 앞에 둔다. 스크립트 실행 중 실패하면 실패한 문을 함께 들고 있다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public class SQLServerAgentException extends DBDatabaseException {

    public enum Kind {
        /** The login lacks msdb permissions or agent roles */
        PERMISSION,
        /** The agent service is not running (sp_start_job, Agent XPs disabled) */
        NOT_RUNNING,
        OTHER
    }

    /**
     * Permission errors: 229 object permission denied, 230 column permission denied, 262 permission denied in the
     * database, 297 no permission for the action, 300 server permission denied, 916 cannot access msdb, 15247 no
     * permission for the operation, 14525 parameter allowed only to sysadmin, 15003 only sysadmin may do it (alerts,
     * operators).
     */
    private static final Set<Integer> PERMISSION_ERRORS = Set.of(229, 230, 262, 297, 300, 916, 14525, 15003, 15247);
    /**
     * Agent not running: 22022 the agent cannot be notified, 15281 the Agent XPs component is turned off.
     */
    private static final Set<Integer> NOT_RUNNING_ERRORS = Set.of(22022, 15281);

    @NotNull
    private final Kind kind;
    @Nullable
    private final String statement;

    private SQLServerAgentException(
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
    public static SQLServerAgentException wrap(@NotNull SQLException error, @Nullable DBPDataSource dataSource, @Nullable String statement) {
        Kind kind = classify(error);
        String message = switch (kind) {
            case PERMISSION -> SQLServerAgentMessages.error_permission;
            case NOT_RUNNING -> SQLServerAgentMessages.error_not_running;
            case OTHER -> CommonUtils.notEmpty(error.getMessage());
        };
        return new SQLServerAgentException(message, error, dataSource, kind, statement);
    }

    @NotNull
    private static Kind classify(@NotNull SQLException error) {
        for (SQLException e = error; e != null; e = e.getNextException()) {
            if (PERMISSION_ERRORS.contains(e.getErrorCode())) {
                return Kind.PERMISSION;
            }
            if (NOT_RUNNING_ERRORS.contains(e.getErrorCode())) {
                return Kind.NOT_RUNNING;
            }
        }
        return Kind.OTHER;
    }

    @NotNull
    public Kind getKind() {
        return kind;
    }

    /**
     * The script statement that failed, or null.
     */
    @Nullable
    public String getStatement() {
        return statement;
    }
}
