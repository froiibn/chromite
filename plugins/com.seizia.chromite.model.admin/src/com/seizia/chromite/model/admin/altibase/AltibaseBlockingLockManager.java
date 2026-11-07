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
package com.seizia.chromite.model.admin.altibase;

import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.model.admin.AbstractBlockingLockManager;
import com.seizia.chromite.model.admin.BlockingSessionLock;
import com.seizia.chromite.model.admin.SessionLockItem;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Altibase lock waits and the locks of a session.
 *
 * 설계: DBeaver의 {@code AltibaseLockManager}(ext.altibase)는 SWT 잠금 편집기({@code AltibaseLockEditor})만 쓰고, 행 모양이 다른 네 DB와 달라
 * ({@code w_sid}·{@code h_sid} 열, 상세 옵션 키 {@code wsid}/{@code hsid}) Swing 잠금 탭의 공통 모양({@link BlockingSessionLock},
 * {@link SessionLockItem})과 맞지 않는다. 또 대기 객체를 기다리는 트랜잭션의 모든 {@code V$LOCK} 행과 곱해(대기 잠금만 고르지 않음) 한 대기가
 * 여러 줄이 된다. 그래서 다른 DB처럼 {@link AbstractBlockingLockManager}를 따라 새로 만들었다(DBeaver 파일은 고치지 않는다).
 * <ul>
 *     <li>대기 관계는 서버의 {@code V$LOCK_WAIT}(트랜잭션 → 기다리는 트랜잭션)이고, 세션은 {@code V$SESSION.TRANS_ID}로 찾는다.</li>
 *     <li>대기 대상·모드는 기다리는 트랜잭션의 승인되지 않은 {@code V$LOCK} 행({@code IS_GRANT = 0})의 테이블과 {@code LOCK_DESC}다.
 *     행 대기처럼 그런 행이 없으면 기다리는 트랜잭션 번호를 보인다.</li>
 *     <li>문장은 세션의 현재 문장({@code V$SESSION.CURRENT_STMT_ID} → {@code V$STATEMENT})이다. 막는 쪽이 유휴면 마지막 문장이 보인다.</li>
 *     <li>대기 시간은 기다리는 문장의 시작 시각({@code QUERY_START_TIME}, Unix 초)과 서버의 UTC 현재 시각({@code UNIX_DATE})의 차이다.</li>
 *     <li>세션 끝내기는 DBeaver 세션·잠금 관리자와 같은 {@code ALTER DATABASE <db> SESSION CLOSE <id>}다(SYS 권한).</li>
 * </ul>
 * 2026-11-08 Docker Altibase 7.3.0.0.7에서 두 세션의 {@code LOCK TABLE}로 열과 값을 확인했다(docs/chromite/swing-admin-tools.md).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: Altibase 잠금 탭 (docs/chromite/swing-admin-tools.md "Altibase 잠금")
 */
public class AltibaseBlockingLockManager extends AbstractBlockingLockManager {

    private static final String LOCK_WAITS_QUERY =
        "SELECT w.ID AS wait_id, w.DB_USERNAME AS wait_user, h.ID AS hold_id, h.DB_USERNAME AS hold_user,\n" +
        "lw.WAIT_FOR_TRANS_ID AS hold_tx,\n" +
        "(SELECT MAX(l.LOCK_DESC) FROM V$LOCK l WHERE l.TRANS_ID = lw.TRANS_ID AND l.IS_GRANT = 0) AS wait_mode,\n" +
        "(SELECT MAX(u.USER_NAME || '.' || t.TABLE_NAME) FROM V$LOCK l, SYSTEM_.SYS_TABLES_ t, SYSTEM_.SYS_USERS_ u\n" +
        "  WHERE l.TRANS_ID = lw.TRANS_ID AND l.IS_GRANT = 0 AND t.TABLE_OID = l.TABLE_OID AND u.USER_ID = t.USER_ID) AS object_name,\n" +
        "(SELECT MAX(s.QUERY) FROM V$STATEMENT s WHERE s.SESSION_ID = w.ID AND s.ID = w.CURRENT_STMT_ID) AS wait_statement,\n" +
        "(SELECT MAX(s.QUERY) FROM V$STATEMENT s WHERE s.SESSION_ID = h.ID AND s.ID = h.CURRENT_STMT_ID) AS hold_statement,\n" +
        "DATEDIFF(TO_DATE('1970-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS'), UNIX_DATE, 'SECOND') -\n" +
        "  (SELECT MAX(s.QUERY_START_TIME) FROM V$STATEMENT s WHERE s.SESSION_ID = w.ID AND s.ID = w.CURRENT_STMT_ID) AS wait_seconds\n" +
        "FROM V$LOCK_WAIT lw\n" +
        "JOIN V$SESSION w ON w.TRANS_ID = lw.TRANS_ID\n" +
        "LEFT OUTER JOIN V$SESSION h ON h.TRANS_ID = lw.WAIT_FOR_TRANS_ID\n" +
        "ORDER BY w.ID"; //$NON-NLS-1$

    private static final String LOCK_ITEMS_QUERY =
        "SELECT s.ID AS session_id, l.LOCK_ITEM_TYPE, l.LOCK_DESC, l.LOCK_CNT, l.IS_GRANT, l.TRANS_ID,\n" +
        "(SELECT MAX(u.USER_NAME || '.' || t.TABLE_NAME) FROM SYSTEM_.SYS_TABLES_ t, SYSTEM_.SYS_USERS_ u\n" +
        "  WHERE t.TABLE_OID = l.TABLE_OID AND u.USER_ID = t.USER_ID) AS object_name,\n" +
        "(SELECT MAX(p.PARTITION_NAME) FROM SYSTEM_.SYS_TABLE_PARTITIONS_ p WHERE p.PARTITION_OID = l.TABLE_OID) AS partition_name,\n" +
        "(SELECT MAX(tb.NAME) FROM V$TABLESPACES tb WHERE tb.ID = l.TBS_ID) AS tablespace_name\n" +
        "FROM V$LOCK l\n" +
        "JOIN V$SESSION s ON s.TRANS_ID = l.TRANS_ID\n" +
        "WHERE s.ID = ?\n" +
        "ORDER BY l.IS_GRANT, l.LOCK_ITEM_TYPE"; //$NON-NLS-1$

    @NotNull
    private final AltibaseDataSource dataSource;

    public AltibaseBlockingLockManager(@NotNull AltibaseDataSource dataSource) {
        super(dataSource);
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    protected List<BlockingSessionLock> readLockWaits(@NotNull JDBCSession session) throws SQLException {
        List<BlockingSessionLock> waits = new ArrayList<>();
        try (JDBCPreparedStatement dbStat = session.prepareStatement(LOCK_WAITS_QUERY);
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            while (dbResult.next()) {
                String objectName = JDBCUtils.safeGetString(dbResult, "object_name"); //$NON-NLS-1$
                Long waitSeconds = JDBCUtils.safeGetLongNullable(dbResult, "wait_seconds"); //$NON-NLS-1$
                waits.add(new BlockingSessionLock(
                    JDBCUtils.safeGetLong(dbResult, "wait_id"), //$NON-NLS-1$
                    JDBCUtils.safeGetString(dbResult, "wait_user"), //$NON-NLS-1$
                    JDBCUtils.safeGetLongNullable(dbResult, "hold_id"), //$NON-NLS-1$
                    JDBCUtils.safeGetString(dbResult, "hold_user"), //$NON-NLS-1$
                    JDBCUtils.safeGetString(dbResult, "wait_statement"), //$NON-NLS-1$
                    JDBCUtils.safeGetString(dbResult, "hold_statement"), //$NON-NLS-1$
                    // A row wait has no ungranted table lock: the transaction it waits for is the resource
                    objectName != null ? objectName : "TX " + JDBCUtils.safeGetString(dbResult, "hold_tx"), //$NON-NLS-1$ //$NON-NLS-2$
                    JDBCUtils.safeGetString(dbResult, "wait_mode"), //$NON-NLS-1$
                    waitSeconds == null || waitSeconds < 0 ? null : waitSeconds));
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
                    String objectName = JDBCUtils.safeGetString(dbResult, "object_name"); //$NON-NLS-1$
                    String partition = JDBCUtils.safeGetString(dbResult, "partition_name"); //$NON-NLS-1$
                    String tablespace = JDBCUtils.safeGetString(dbResult, "tablespace_name"); //$NON-NLS-1$
                    // Altibase has one database per server: the tablespace goes to the details
                    items.add(new SessionLockItem(
                        JDBCUtils.safeGetLong(dbResult, "session_id"), //$NON-NLS-1$
                        null,
                        objectName != null ? objectName : partition,
                        JDBCUtils.safeGetString(dbResult, "LOCK_ITEM_TYPE"), //$NON-NLS-1$
                        JDBCUtils.safeGetString(dbResult, "LOCK_DESC"), //$NON-NLS-1$
                        JDBCUtils.safeGetLong(dbResult, "IS_GRANT") == 0 ? "WAITING" : "GRANTED", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "TX " + JDBCUtils.safeGetString(dbResult, "TRANS_ID") //$NON-NLS-1$ //$NON-NLS-2$
                            + ", count " + JDBCUtils.safeGetInt(dbResult, "LOCK_CNT") //$NON-NLS-1$ //$NON-NLS-2$
                            + (tablespace == null ? "" : ", " + tablespace))); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        return items;
    }

    @Override
    protected void killSession(@NotNull JDBCSession session, @NotNull BlockingSessionLock lock) throws SQLException {
        String database;
        try {
            database = dataSource.getDbName(session);
        } catch (DBException e) {
            throw new SQLException(e.getMessage(), e);
        }
        // ALTER DATABASE takes no bind variables; the database name and the id are read from the server
        try (JDBCPreparedStatement dbStat = session.prepareStatement(
            "ALTER DATABASE " + database + " SESSION CLOSE " + lock.getWaitId())) { //$NON-NLS-1$ //$NON-NLS-2$
            dbStat.execute();
        }
    }
}
