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
package com.seizia.chromite.model.admin.mssql;

import com.seizia.chromite.model.admin.AbstractBlockingLockManager;
import com.seizia.chromite.model.admin.BlockingSessionLock;
import com.seizia.chromite.model.admin.SessionLockItem;
import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL Server 잠금 대기 관리자.
 *
 * 설계: 기다리는 요청은 {@code sys.dm_exec_requests.blocking_session_id}(SSMS Activity Monitor의 "Blocked By"와 같은 값)로,
 * 세션의 잠금은 {@code sys.dm_tran_locks}로 읽는다. 문장은 기다리는 쪽은 실행 중인 요청의 sql_handle, 막는 쪽은 연결의
 * {@code most_recent_sql_handle}(유휴 트랜잭션도 마지막 문장이 보임)이다. 세션 끝내기는 {@code SQLServerSessionManager}와 같은 {@code KILL}이다.
 * 객체 이름은 OBJECT 잠금만 {@code OBJECT_NAME(id, database_id)}로 풀고, KEY/PAGE 등은 자원 설명을 그대로 보인다
 * (hobt_id를 풀려면 그 DB의 {@code sys.partitions}를 읽어야 해서 DB마다 질의가 필요하다).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: SQL Server 잠금 보기 (docs/chromite/swing-admin-tools.md)
 */
public class SQLServerLockManager extends AbstractBlockingLockManager {

    private static final String LOCK_WAITS_QUERY =
        "SELECT r.session_id AS wait_id, ws.login_name AS wait_user, r.blocking_session_id AS hold_id, hs.login_name AS hold_user,\n" +
        "r.wait_type, r.wait_time, r.wait_resource,\n" +
        "(SELECT t.text FROM sys.dm_exec_sql_text(r.sql_handle) t) AS wait_statement,\n" +
        "(SELECT t.text FROM sys.dm_exec_sql_text(hc.most_recent_sql_handle) t) AS hold_statement\n" +
        "FROM sys.dm_exec_requests r\n" +
        "JOIN sys.dm_exec_sessions ws ON ws.session_id = r.session_id\n" +
        "LEFT OUTER JOIN sys.dm_exec_sessions hs ON hs.session_id = r.blocking_session_id\n" +
        "LEFT OUTER JOIN sys.dm_exec_connections hc ON hc.session_id = r.blocking_session_id AND hc.parent_connection_id IS NULL\n" +
        "WHERE r.blocking_session_id <> 0 AND r.blocking_session_id <> r.session_id\n" +
        "ORDER BY r.session_id";

    private static final String LOCK_ITEMS_QUERY =
        "SELECT l.request_session_id, DB_NAME(l.resource_database_id) AS database_name, l.resource_type,\n" +
        "CASE WHEN l.resource_type = 'OBJECT' THEN OBJECT_SCHEMA_NAME(l.resource_associated_entity_id, l.resource_database_id) + '.' +\n" +
        "OBJECT_NAME(l.resource_associated_entity_id, l.resource_database_id) END AS object_name,\n" +
        "l.request_mode, l.request_status, RTRIM(l.resource_description) AS resource_description\n" +
        "FROM sys.dm_tran_locks l\n" +
        "WHERE l.request_session_id = ?\n" +
        "ORDER BY CASE l.request_status WHEN 'WAIT' THEN 0 ELSE 1 END, l.resource_type";

    public SQLServerLockManager(@NotNull SQLServerDataSource dataSource) {
        super(dataSource);
    }

    @NotNull
    @Override
    protected List<BlockingSessionLock> readLockWaits(@NotNull JDBCSession session) throws SQLException {
        List<BlockingSessionLock> waits = new ArrayList<>();
        try (JDBCPreparedStatement dbStat = session.prepareStatement(LOCK_WAITS_QUERY);
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            while (dbResult.next()) {
                // wait_time is in milliseconds
                long waitMillis = JDBCUtils.safeGetLong(dbResult, "wait_time");
                waits.add(new BlockingSessionLock(
                    JDBCUtils.safeGetLong(dbResult, "wait_id"),
                    JDBCUtils.safeGetString(dbResult, "wait_user"),
                    JDBCUtils.safeGetLong(dbResult, "hold_id"),
                    JDBCUtils.safeGetString(dbResult, "hold_user"),
                    JDBCUtils.safeGetString(dbResult, "wait_statement"),
                    JDBCUtils.safeGetString(dbResult, "hold_statement"),
                    JDBCUtils.safeGetString(dbResult, "wait_resource"),
                    JDBCUtils.safeGetString(dbResult, "wait_type"),
                    waitMillis / 1000));
            }
        }
        return waits;
    }

    @NotNull
    @Override
    protected List<SessionLockItem> readLockItems(@NotNull JDBCSession session, long sessionId) throws SQLException {
        List<SessionLockItem> items = new ArrayList<>();
        try (JDBCPreparedStatement dbStat = session.prepareStatement(LOCK_ITEMS_QUERY)) {
            dbStat.setLong(1, sessionId);
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                while (dbResult.next()) {
                    items.add(new SessionLockItem(
                        JDBCUtils.safeGetLong(dbResult, "request_session_id"),
                        JDBCUtils.safeGetString(dbResult, "database_name"),
                        JDBCUtils.safeGetString(dbResult, "object_name"),
                        JDBCUtils.safeGetString(dbResult, "resource_type"),
                        JDBCUtils.safeGetString(dbResult, "request_mode"),
                        JDBCUtils.safeGetString(dbResult, "request_status"),
                        JDBCUtils.safeGetString(dbResult, "resource_description")));
                }
            }
        }
        return items;
    }

    @Override
    protected void killSession(@NotNull JDBCSession session, long sessionId) throws SQLException {
        // KILL takes no parameters; the id is a number read from the server
        try (JDBCPreparedStatement dbStat = session.prepareStatement("KILL " + sessionId)) {
            dbStat.execute();
        }
    }
}
