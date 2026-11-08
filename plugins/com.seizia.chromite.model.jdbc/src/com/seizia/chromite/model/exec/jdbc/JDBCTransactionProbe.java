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
package com.seizia.chromite.model.exec.jdbc;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.exec.DBCServerTransaction;
import com.seizia.chromite.model.sql.SQLDialect;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A SQL dialect that can ask its database, on a session's own JDBC connection, what the session's transaction holds.
 *
 * 설계(docs/chromite/idle-transactions.md "트랜잭션 상태"): 원래 Chromite Agent에만 있던 DB별 질의({@code AgentSql.probeOpenTransaction})를
 * 방언으로 옮겨 쿼리 창 상태 줄, 유휴 트랜잭션 감시, 끝낼 때·끊을 때 질문, Agent가 한 규칙을 쓴다. 구현이 지켜야 하는 것:
 * <ul>
 *     <li>질의가 트랜잭션을 새로 시작하면 안 된다. 수동 커밋 SQL Server는 DMV를 읽는 SELECT로도, PostgreSQL 드라이버는 트랜잭션 밖의 아무
 *     문장 앞에서도, 직렬화 격리 Oracle은 SELECT로도 트랜잭션을 시작한다. 그래서 먼저 트랜잭션이 있는지를 시작하지 않는 방법(같은 배치의
 *     {@code @@TRANCOUNT}, 드라이버의 프로토콜 상태, PL/SQL 호출)으로 보고, 있을 때만 더 묻는다.</li>
 *     <li>질의가 실패해도 사용자의 트랜잭션을 바꾸면 안 된다(PostgreSQL은 오류가 트랜잭션을 중단시킨다, SQL Server {@code XACT_ABORT}는
 *     롤백한다). 권한이 없으면 오류를 내지 말고 {@link DBCServerTransaction#UNVERIFIED}로 답한다.</li>
 *     <li>시간 제한 {@value #TIMEOUT_SECONDS}초. 사용자 작업이 끝난 직후 같은 스레드에서 부르므로 오래 막으면 안 된다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/idle-transactions.md "트랜잭션 상태")
 */
public interface JDBCTransactionProbe {

    int TIMEOUT_SECONDS = 5;

    /**
     * Asks the database about the transaction of the connection's session.
     *
     * @return the answer, or null when this server has no probe (another server type behind the same dialect)
     */
    @Nullable
    DBCServerTransaction probeTransaction(@NotNull DBPDataSource dataSource, @NotNull Connection connection) throws SQLException;

    /**
     * Asks through the data source's dialect, without throwing.
     *
     * @return the answer ({@link DBCServerTransaction#FAILED} when the question failed), or null when the database has no probe
     */
    @Nullable
    static DBCServerTransaction probe(@NotNull DBPDataSource dataSource, @NotNull Connection connection) {
        SQLDialect dialect = dataSource.getSQLDialect();
        if (!(dialect instanceof JDBCTransactionProbe probe)) {
            return null;
        }
        try {
            return probe.probeTransaction(dataSource, connection);
        } catch (Throwable e) {
            Log.getLog(JDBCTransactionProbe.class).debug("Can't ask " + dataSource.getContainer().getName()
                + " about the session's transaction: " + e.getMessage());
            return DBCServerTransaction.FAILED;
        }
    }
}
