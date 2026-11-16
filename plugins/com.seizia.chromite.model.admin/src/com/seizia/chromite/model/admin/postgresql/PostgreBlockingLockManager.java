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
package com.seizia.chromite.model.admin.postgresql;

import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.model.admin.AbstractBlockingLockManager;
import com.seizia.chromite.model.admin.ActivityAccessMessages;
import com.seizia.chromite.model.admin.BlockingSessionLock;
import com.seizia.chromite.model.admin.SessionLockItem;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 잠금 대기 관리자({@code pg_blocking_pids}, PostgreSQL 9.6+).
 *
 * 설계: DBeaver {@code PostgreLockManager}는 {@code pg_locks}끼리 잠금 모드 충돌 표로 짝을 지어 막는 세션을 찾는다.
 * 행 잠금 대기는 "기다리는 세션의 transactionid 요청 ↔ 막는 세션의 transactionid 보유"로 나타나 표에서 객체가 보이지 않고,
 * 충돌 표에 없는 잠금 종류(advisory 등)는 놓친다. 서버가 판단한 대기 관계({@code pg_blocking_pids})를 그대로 쓰고,
 * 잠금 대상은 다음 순서로 객체에 연결한다.
 * <ul>
 *     <li>기다리는 잠금(granted = false)에 relation이 있으면 그 테이블(테이블·인덱스 잠금 대기, 다른 세션 뒤에 줄 선 행 잠금 대기).</li>
 *     <li>없으면(transactionid 대기) 기다리는 세션이 잡은 tuple 잠금의 테이블과 행(page, tuple): 행 잠금 대기의 첫 번째 대기자다.</li>
 *     <li>둘 다 없으면 잠금 종류만 보인다. 이름은 이 연결의 데이터베이스에 있는 객체만 {@code regclass}로 풀고, 다른 데이터베이스는 OID다.</li>
 * </ul>
 * 대기 시간은 PostgreSQL 14+의 {@code pg_locks.waitstart}, 그 전은 상태가 바뀐 때부터다. 세션 끝내기는 {@code pg_terminate_backend}이고
 * 결과가 false(세션이 이미 없음)면 오류로 알린다. 남을 막기만 하는 뿌리 행(head blocker)은 상위 클래스가 더한다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: PostgreSQL 세션·잠금 (docs/chromite/swing-admin-tools.md)
 */
public class PostgreBlockingLockManager extends AbstractBlockingLockManager {

    // Name of a locked relation: regclass works only for the database of this connection
    private static final String RELATION_NAME =
        "CASE WHEN l.relation IS NULL THEN NULL\n" +
        "WHEN l.database = (SELECT d.oid FROM pg_catalog.pg_database d WHERE d.datname = pg_catalog.current_database())\n" +
        "THEN CAST(CAST(l.relation AS regclass) AS text) ELSE 'oid ' || l.relation END"; //$NON-NLS-1$

    private static final String LOCK_WAITS_QUERY =
        "SELECT w.pid AS wait_id, w.usename AS wait_user, bp.pid AS hold_id, b.usename AS hold_user,\n" +
        "w.query AS wait_statement, b.query AS hold_statement,\n" +
        "wl.locktype AS wait_locktype, wl.mode AS wait_mode,\n" +
        "CAST(EXTRACT(EPOCH FROM (pg_catalog.now() - %s)) AS bigint) AS wait_seconds,\n" +
        "COALESCE(wl.relation_name, tl.relation_name) AS object_name,\n" +
        "CASE WHEN wl.relation_name IS NOT NULL THEN wl.page ELSE tl.page END AS row_page,\n" +
        "CASE WHEN wl.relation_name IS NOT NULL THEN wl.tuple ELSE tl.tuple END AS row_tuple\n" +
        "FROM pg_catalog.pg_stat_activity w\n" +
        "CROSS JOIN LATERAL unnest(pg_catalog.pg_blocking_pids(w.pid)) WITH ORDINALITY AS bp(pid, n)\n" +
        "LEFT JOIN pg_catalog.pg_stat_activity b ON b.pid = bp.pid\n" +
        "LEFT JOIN LATERAL (SELECT l.locktype, l.mode, l.page, l.tuple, %s, " + RELATION_NAME + " AS relation_name\n" +
        "  FROM pg_catalog.pg_locks l WHERE l.pid = w.pid AND NOT l.granted LIMIT 1) wl ON true\n" +
        "LEFT JOIN LATERAL (SELECT l.page, l.tuple, " + RELATION_NAME + " AS relation_name\n" +
        "  FROM pg_catalog.pg_locks l WHERE l.pid = w.pid AND l.granted AND l.locktype = 'tuple' LIMIT 1) tl ON true\n" +
        "WHERE bp.pid <> 0\n" +
        "ORDER BY w.pid, bp.n"; //$NON-NLS-1$

    private static final String LOCK_ITEMS_QUERY =
        "SELECT l.pid, d.datname, l.locktype, " + RELATION_NAME + " AS object_name, l.mode, l.granted,\n" +
        "concat_ws(', ', 'page ' || l.page, 'row ' || l.tuple, 'xid ' || l.transactionid, 'vxid ' || l.virtualxid,\n" +
        "  CASE WHEN l.locktype = 'advisory' THEN 'key ' || l.classid || '/' || l.objid END) AS detail\n" +
        "FROM pg_catalog.pg_locks l\n" +
        "LEFT JOIN pg_catalog.pg_database d ON d.oid = l.database\n" +
        "WHERE l.pid = ?\n" +
        "ORDER BY l.granted, l.locktype, object_name"; //$NON-NLS-1$

    private final PostgreDataSource dataSource;

    public PostgreBlockingLockManager(@NotNull PostgreDataSource dataSource) {
        super(dataSource);
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    protected List<BlockingSessionLock> readLockWaits(@NotNull JDBCSession session) throws SQLException {
        // pg_locks.waitstart (when the lock wait began) is new in PostgreSQL 14
        boolean waitStart = dataSource.isServerVersionAtLeast(14, 0);
        String query = String.format(LOCK_WAITS_QUERY,
            waitStart ? "COALESCE(wl.waitstart, w.state_change)" : "w.state_change", //$NON-NLS-1$ //$NON-NLS-2$
            waitStart ? "l.waitstart" : "NULL AS waitstart"); //$NON-NLS-1$ //$NON-NLS-2$
        List<BlockingSessionLock> waits = new ArrayList<>();
        try (JDBCPreparedStatement dbStat = session.prepareStatement(query);
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            while (dbResult.next()) {
                String lockType = JDBCUtils.safeGetString(dbResult, "wait_locktype"); //$NON-NLS-1$
                String mode = JDBCUtils.safeGetString(dbResult, "wait_mode"); //$NON-NLS-1$
                waits.add(new BlockingSessionLock(
                    JDBCUtils.safeGetLong(dbResult, "wait_id"), //$NON-NLS-1$
                    JDBCUtils.safeGetString(dbResult, "wait_user"), //$NON-NLS-1$
                    JDBCUtils.safeGetLong(dbResult, "hold_id"), //$NON-NLS-1$
                    JDBCUtils.safeGetString(dbResult, "hold_user"), //$NON-NLS-1$
                    JDBCUtils.safeGetString(dbResult, "wait_statement"), //$NON-NLS-1$
                    JDBCUtils.safeGetString(dbResult, "hold_statement"), //$NON-NLS-1$
                    formatResource(
                        JDBCUtils.safeGetString(dbResult, "object_name"), //$NON-NLS-1$
                        JDBCUtils.safeGetLongNullable(dbResult, "row_page"), //$NON-NLS-1$
                        JDBCUtils.safeGetLongNullable(dbResult, "row_tuple"), //$NON-NLS-1$
                        lockType),
                    mode == null ? null : lockType == null ? mode : mode + " (" + lockType + ")", //$NON-NLS-1$ //$NON-NLS-2$
                    JDBCUtils.safeGetLongNullable(dbResult, "wait_seconds"))); //$NON-NLS-1$
            }
        }
        return waits;
    }

    /**
     * "public.orders (row 0,1)": the locked table and row, or the lock type when no table is known.
     */
    @Nullable
    private static String formatResource(@Nullable String objectName, @Nullable Long page, @Nullable Long tuple, @Nullable String lockType) {
        if (objectName == null) {
            return lockType;
        }
        return page != null && tuple != null ? objectName + " (row " + page + "," + tuple + ")" : objectName; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @NotNull
    @Override
    protected List<SessionLockItem> readLockItems(@NotNull JDBCSession session, long sessionId) throws SQLException {
        List<SessionLockItem> items = new ArrayList<>();
        try (JDBCPreparedStatement dbStat = session.prepareStatement(LOCK_ITEMS_QUERY)) {
            dbStat.setInt(1, (int) sessionId);
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                while (dbResult.next()) {
                    items.add(new SessionLockItem(
                        JDBCUtils.safeGetLong(dbResult, "pid"), //$NON-NLS-1$
                        JDBCUtils.safeGetString(dbResult, "datname"), //$NON-NLS-1$
                        JDBCUtils.safeGetString(dbResult, "object_name"), //$NON-NLS-1$
                        JDBCUtils.safeGetString(dbResult, "locktype"), //$NON-NLS-1$
                        JDBCUtils.safeGetString(dbResult, "mode"), //$NON-NLS-1$
                        JDBCUtils.safeGetBoolean(dbResult, "granted") ? "GRANTED" : "WAITING", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        JDBCUtils.safeGetString(dbResult, "detail"))); //$NON-NLS-1$
                }
            }
        }
        return items;
    }

    @Override
    protected void killSession(@NotNull JDBCSession session, @NotNull BlockingSessionLock lock) throws SQLException {
        try (JDBCPreparedStatement dbStat = session.prepareStatement("SELECT pg_catalog.pg_terminate_backend(?)")) { //$NON-NLS-1$
            dbStat.setInt(1, (int) lock.getWaitId());
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                if (!dbResult.next() || !dbResult.getBoolean(1)) {
                    // Shown in the error dialog of the Locks tab
                    throw new SQLException(NLS.bind(ActivityAccessMessages.session_not_ended, lock.getWaitId()));
                }
            }
        }
    }
}
