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
package com.seizia.chromite.ext.oracle.model.scheduler;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.model.DBPDataSource;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.Set;

/**
 * A failed Oracle Scheduler call. Missing privileges and a scheduler that runs no jobs get a localized explanation as the
 * message, with the server error as the cause (the error dialog shows both lines, never a stack trace first).
 *
 * 설계: SQL Server 에이전트의 {@code SQLServerAgentException}과 같은 분류다. 권한이 모자라면 DBMS_SCHEDULER가 ORA-01031·ORA-27486 같은
 * 일반 권한 오류를 내므로, 오류 번호로 나눠 필요한 권한(CREATE JOB, CREATE ANY JOB, MANAGE SCHEDULER)을 알려 주는 문구를 앞에 둔다.
 * 스크립트 실행 중 실패하면 실패한 문을 함께 들고 있다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class OracleSchedulerException extends DBDatabaseException {

    public enum Kind {
        /** The login lacks a scheduler privilege */
        PERMISSION,
        /** The scheduler runs no jobs (job_queue_processes = 0, SCHEDULER_DISABLED) */
        NOT_RUNNING,
        OTHER
    }

    /**
     * Permission errors: ORA-01031 insufficient privileges, ORA-27486 insufficient privileges (scheduler),
     * ORA-27399 job owner lacks the privilege to run the job type, ORA-00942 a DBA_ view the login may not read.
     */
    private static final Set<Integer> PERMISSION_ERRORS = Set.of(1031, 27486, 27399, 942);
    /**
     * Scheduler unavailable: ORA-27492 unable to run job, scheduler unavailable.
     */
    private static final Set<Integer> NOT_RUNNING_ERRORS = Set.of(27492);

    @NotNull
    private final Kind kind;
    @Nullable
    private final String statement;

    private OracleSchedulerException(
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
    public static OracleSchedulerException wrap(@NotNull SQLException error, @Nullable DBPDataSource dataSource, @Nullable String statement) {
        Kind kind = classify(error);
        String message = switch (kind) {
            case PERMISSION -> OracleSchedulerMessages.error_permission;
            case NOT_RUNNING -> OracleSchedulerMessages.error_not_running;
            case OTHER -> CommonUtils.notEmpty(error.getMessage());
        };
        return new OracleSchedulerException(message, error, dataSource, kind, statement);
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
