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
package com.seizia.chromite.model.admin.maintenance;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSInstance;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;

/**
 * 유지 관리 대화상자의 서버 작업(권한·상태 읽기, 문장 실행)을 그 작업만의 연결에서 한다.
 *
 * 설계: 유지 관리 문장은 사용자의 트랜잭션에 넣지 않는다.
 * <ul>
 *     <li>작업마다 격리된 실행 컨텍스트를 새로 열고 끝나면 닫는다({@link DBSInstance#openIsolatedContext}). SQL 편집기의 연결도, Activity Monitor가
 *     열어 둔 연결({@code AdminConnection})도 쓰지 않는다. 연결 설정이 "단일 연결"이어도 새로 연다: 그 연결의 트랜잭션에 VACUUM을 넣을 수 없고,
 *     Oracle DDL(ALTER INDEX REBUILD, ALTER … COMPILE)은 열린 트랜잭션을 커밋하기 때문이다.</li>
 *     <li>새 컨텍스트가 수동 커밋 모드면(연결 설정의 기본값) 자동 커밋으로 바꾼다. 방금 연 연결이라 끝나지 않은 트랜잭션이 없어
 *     트랜잭션 안전 규칙(커밋/롤백을 묻기)의 대상이 아니다. VACUUM, REINDEX CONCURRENTLY, REINDEX DATABASE는 트랜잭션 블록 안에서 실행할 수 없다.</li>
 *     <li>PostgreSQL은 대상의 데이터베이스 인스턴스({@code PostgreDatabase})에서 연다. REINDEX DATABASE와 테이블 문장은 그 데이터베이스에 연결해야 한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
public final class MaintenanceRunner {

    private static final Log log = Log.getLog(MaintenanceRunner.class);

    /**
     * Work done with the session of a maintenance connection.
     */
    @FunctionalInterface
    public interface SessionTask<T> {
        T run(@NotNull JDBCSession session) throws DBException;
    }

    private MaintenanceRunner() {
    }

    /**
     * Reads the plan of a target on its own connection.
     */
    @NotNull
    public static MaintenancePlan prepare(@NotNull DBRProgressMonitor monitor, @NotNull DBSInstance instance, @NotNull MaintenanceDialect dialect,
                                          @NotNull MaintenanceTarget target) throws DBException {
        return withSession(monitor, instance, "Maintenance (read)", session -> dialect.prepare(monitor, session, target)); //$NON-NLS-1$
    }

    /**
     * Runs a command on its own auto-commit connection.
     */
    @NotNull
    public static MaintenanceResult run(@NotNull DBRProgressMonitor monitor, @NotNull DBSInstance instance, @NotNull MaintenanceDialect dialect,
                                        @NotNull MaintenanceCommand command) throws DBException {
        return withSession(monitor, instance, "Maintenance", session -> dialect.execute(monitor, session, command)); //$NON-NLS-1$
    }

    @NotNull
    private static <T> T withSession(@NotNull DBRProgressMonitor monitor, @NotNull DBSInstance instance, @NotNull String purpose,
                                     @NotNull SessionTask<T> task) throws DBException {
        DBCExecutionContext context = instance.openIsolatedContext(monitor, purpose, null);
        try {
            DBCTransactionManager transactions = DBUtils.getTransactionManager(context);
            if (transactions != null && !transactions.isAutoCommit()) {
                // A new connection: no transaction is open yet
                transactions.setAutoCommit(monitor, true);
            }
            try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, purpose)) {
                if (!(session instanceof JDBCSession jdbcSession)) {
                    throw new DBException("Maintenance needs a JDBC connection"); //$NON-NLS-1$
                }
                return task.run(jdbcSession);
            }
        } finally {
            try {
                context.close();
            } catch (Throwable e) {
                log.debug("Error closing the maintenance connection", e); //$NON-NLS-1$
            }
        }
    }
}
