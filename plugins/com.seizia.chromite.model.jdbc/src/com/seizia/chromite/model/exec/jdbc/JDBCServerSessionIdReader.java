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
import com.seizia.chromite.model.sql.SQLDialect;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A SQL dialect that can read the server's number of a session (SQL Server SPID, PostgreSQL backend PID, MySQL connection
 * ID, Oracle SID) on the session's own JDBC connection.
 *
 * 설계(docs/chromite/swing-sql-editor-session-ui.md "탭의 세션 번호"): 쿼리 창 탭의 세션 번호를 위해 JDBC 문맥이 세션을 열 때 한 번 부른다.
 * 세션마다 서버를 다시 부르지 않도록 문맥이 값을 들고 있다. {@link JDBCTransactionProbe}처럼 방언이 구현해 드라이버별 판단이 UI에 오지 않는다.
 * 구현이 지켜야 하는 것:
 * <ul>
 *     <li>트랜잭션을 시작하거나 바꾸면 안 된다. 수동 커밋 PostgreSQL 드라이버는 아무 문장 앞에서도 BEGIN을 보내고, 직렬화 격리 Oracle은
 *     SELECT로도 트랜잭션을 시작한다. 그래서 드라이버가 로그인 때 받은 값(PostgreSQL)이나 표를 읽지 않는 문장·PL/SQL 호출을 쓴다.</li>
 *     <li>쿼리 관리자에 사용자 문장으로 남지 않게 JDBC 연결에 직접 보낸다(트랜잭션 질의와 같은 방법).</li>
 *     <li>시간 제한 {@value #READ_TIMEOUT_SECONDS}초. 실패하면 번호 없이 지나간다({@link #read}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-28 최초 작성 (앱 변경 #193, docs/chromite/swing-sql-editor-session-ui.md "탭의 세션 번호")
 */
public interface JDBCServerSessionIdReader {

    // Not TIMEOUT_SECONDS: the dialects implement JDBCTransactionProbe too, whose constant has that name
    int READ_TIMEOUT_SECONDS = 5;

    /**
     * Reads the number of the connection's session.
     *
     * @return the number, or null when this server has none to read (another server type behind the same dialect)
     */
    @Nullable
    String readServerSessionId(@NotNull DBPDataSource dataSource, @NotNull Connection connection) throws SQLException;

    /**
     * Reads through the data source's dialect, without throwing.
     *
     * @return the number, or null when the database has no reader or the read failed
     */
    @Nullable
    static String read(@NotNull DBPDataSource dataSource, @NotNull Connection connection) {
        SQLDialect dialect = dataSource.getSQLDialect();
        if (!(dialect instanceof JDBCServerSessionIdReader reader)) {
            return null;
        }
        try {
            return reader.readServerSessionId(dataSource, connection);
        } catch (Throwable e) {
            Log.getLog(JDBCServerSessionIdReader.class).debug("Can't read the session number of " + dataSource.getContainer().getName()
                + ": " + e.getMessage());
            return null;
        }
    }
}
