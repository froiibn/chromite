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
package com.seizia.chromite.model.admin.mysql;

import com.seizia.chromite.model.admin.AbstractBlockingLockManager;
import com.seizia.chromite.model.admin.BlockingSessionLock;
import com.seizia.chromite.model.admin.SessionLockItem;
import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL·MariaDB InnoDB 잠금 대기 관리자.
 *
 * 설계: 서버 버전마다 잠금 정보가 있는 곳이 달라 {@link MySQLDataSource}의 판단을 그대로 쓴다.
 * <ul>
 *     <li>대기 목록: sys 스키마가 있으면({@link MySQLDataSource#supportsSysSchema()}, MySQL 5.7+, MariaDB 10.6+)
 *     {@code sys.innodb_lock_waits}(두 서버 공통 뷰), 없으면 {@code information_schema.INNODB_LOCK_WAITS}와 {@code INNODB_TRX}.
 *     사용자는 {@code information_schema.PROCESSLIST}에서 붙인다.</li>
 *     <li>세션의 잠금: MariaDB는 {@code information_schema.INNODB_LOCKS}(대기에 걸린 잠금만 보임), MySQL 8은
 *     {@code performance_schema.data_locks}(MySQL 8에서 INNODB_LOCKS가 없어짐), 그 밖의 MySQL은 INNODB_LOCKS.</li>
 *     <li>세션 끝내기는 {@code MySQLSessionManager}와 같은 {@code KILL CONNECTION}이다.</li>
 * </ul>
 * InnoDB 행 잠금만 보인다. 메타데이터 잠금(예: ALTER TABLE 대기)은 세션 목록의 State에서 본다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: MySQL/MariaDB 잠금 보기 (docs/chromite/swing-admin-tools.md)
 */
public class MySQLLockManager extends AbstractBlockingLockManager {

    private static final String SYS_LOCK_WAITS_QUERY =
        "SELECT w.waiting_pid AS wait_id, wp.USER AS wait_user, w.blocking_pid AS hold_id, bp.USER AS hold_user,\n" +
        "w.waiting_query AS wait_statement, w.blocking_query AS hold_statement,\n" +
        "w.locked_table, w.locked_index, w.waiting_lock_mode, w.wait_age_secs\n" +
        "FROM sys.innodb_lock_waits w\n" +
        "LEFT OUTER JOIN information_schema.PROCESSLIST wp ON wp.ID = w.waiting_pid\n" +
        "LEFT OUTER JOIN information_schema.PROCESSLIST bp ON bp.ID = w.blocking_pid\n" +
        "ORDER BY w.waiting_pid";

    private static final String INNODB_LOCK_WAITS_QUERY =
        "SELECT rt.trx_mysql_thread_id AS wait_id, wp.USER AS wait_user, bt.trx_mysql_thread_id AS hold_id, bp.USER AS hold_user,\n" +
        "rt.trx_query AS wait_statement, bt.trx_query AS hold_statement,\n" +
        "rl.lock_table AS locked_table, rl.lock_index AS locked_index, rl.lock_mode AS waiting_lock_mode,\n" +
        "TIMESTAMPDIFF(SECOND, rt.trx_wait_started, NOW()) AS wait_age_secs\n" +
        "FROM information_schema.INNODB_LOCK_WAITS w\n" +
        "JOIN information_schema.INNODB_TRX rt ON rt.trx_id = w.requesting_trx_id\n" +
        "JOIN information_schema.INNODB_TRX bt ON bt.trx_id = w.blocking_trx_id\n" +
        "LEFT OUTER JOIN information_schema.INNODB_LOCKS rl ON rl.lock_id = w.requested_lock_id\n" +
        "LEFT OUTER JOIN information_schema.PROCESSLIST wp ON wp.ID = rt.trx_mysql_thread_id\n" +
        "LEFT OUTER JOIN information_schema.PROCESSLIST bp ON bp.ID = bt.trx_mysql_thread_id\n" +
        "ORDER BY rt.trx_mysql_thread_id";

    private static final String INNODB_LOCKS_QUERY =
        "SELECT t.trx_mysql_thread_id AS session_id, l.lock_table, l.lock_index, l.lock_type, l.lock_mode, l.lock_data,\n" +
        "CASE WHEN l.lock_id = t.trx_requested_lock_id THEN 'WAITING' ELSE 'GRANTED' END AS lock_status\n" +
        "FROM information_schema.INNODB_LOCKS l\n" +
        "JOIN information_schema.INNODB_TRX t ON t.trx_id = l.lock_trx_id\n" +
        "WHERE t.trx_mysql_thread_id = ?";

    private static final String DATA_LOCKS_QUERY =
        "SELECT t.PROCESSLIST_ID AS session_id, CONCAT('`', l.OBJECT_SCHEMA, '`.`', l.OBJECT_NAME, '`') AS lock_table,\n" +
        "l.INDEX_NAME AS lock_index, l.LOCK_TYPE AS lock_type, l.LOCK_MODE AS lock_mode, l.LOCK_DATA AS lock_data,\n" +
        "l.LOCK_STATUS AS lock_status\n" +
        "FROM performance_schema.data_locks l\n" +
        "JOIN performance_schema.threads t ON t.THREAD_ID = l.THREAD_ID\n" +
        "WHERE t.PROCESSLIST_ID = ?";

    private final MySQLDataSource dataSource;

    public MySQLLockManager(@NotNull MySQLDataSource dataSource) {
        super(dataSource);
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    protected List<BlockingSessionLock> readLockWaits(@NotNull JDBCSession session) throws SQLException {
        List<BlockingSessionLock> waits = new ArrayList<>();
        String query = dataSource.supportsSysSchema() ? SYS_LOCK_WAITS_QUERY : INNODB_LOCK_WAITS_QUERY;
        try (JDBCPreparedStatement dbStat = session.prepareStatement(query);
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            while (dbResult.next()) {
                String table = JDBCUtils.safeGetString(dbResult, "locked_table");
                String index = JDBCUtils.safeGetString(dbResult, "locked_index");
                waits.add(new BlockingSessionLock(
                    JDBCUtils.safeGetLong(dbResult, "wait_id"),
                    JDBCUtils.safeGetString(dbResult, "wait_user"),
                    JDBCUtils.safeGetLong(dbResult, "hold_id"),
                    JDBCUtils.safeGetString(dbResult, "hold_user"),
                    JDBCUtils.safeGetString(dbResult, "wait_statement"),
                    JDBCUtils.safeGetString(dbResult, "hold_statement"),
                    index == null ? table : table + " (" + index + ")",
                    JDBCUtils.safeGetString(dbResult, "waiting_lock_mode"),
                    JDBCUtils.safeGetLongNullable(dbResult, "wait_age_secs")));
            }
        }
        return waits;
    }

    @NotNull
    @Override
    protected List<SessionLockItem> readLockItems(@NotNull JDBCSession session, long sessionId) throws SQLException {
        boolean dataLocks = !dataSource.isMariaDB() && dataSource.isServerVersionAtLeast(8, 0);
        List<SessionLockItem> items = new ArrayList<>();
        try (JDBCPreparedStatement dbStat = session.prepareStatement(dataLocks ? DATA_LOCKS_QUERY : INNODB_LOCKS_QUERY)) {
            dbStat.setLong(1, sessionId);
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                while (dbResult.next()) {
                    String table = JDBCUtils.safeGetString(dbResult, "lock_table");
                    String index = JDBCUtils.safeGetString(dbResult, "lock_index");
                    items.add(new SessionLockItem(
                        JDBCUtils.safeGetLong(dbResult, "session_id"),
                        null,
                        index == null ? table : table + " (" + index + ")",
                        JDBCUtils.safeGetString(dbResult, "lock_type"),
                        JDBCUtils.safeGetString(dbResult, "lock_mode"),
                        JDBCUtils.safeGetString(dbResult, "lock_status"),
                        JDBCUtils.safeGetString(dbResult, "lock_data")));
                }
            }
        }
        return items;
    }

    @Override
    protected void killSession(@NotNull JDBCSession session, long sessionId) throws SQLException {
        try (JDBCPreparedStatement dbStat = session.prepareStatement("KILL CONNECTION ?")) {
            dbStat.setLong(1, sessionId);
            dbStat.execute();
        }
    }
}
