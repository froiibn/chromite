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
package com.seizia.chromite.model.admin.oracle;

import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.model.admin.AbstractBlockingLockManager;
import com.seizia.chromite.model.admin.BlockingSessionLock;
import com.seizia.chromite.model.admin.SessionLockItem;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracle 잠금 대기 관리자({@code V$SESSION.BLOCKING_SESSION}, {@code V$LOCK}, Oracle 10g+).
 *
 * 설계: DBeaver {@code OracleLockManager}는 뿌리 행(남을 막는 세션)의 "막는 세션"을 0으로 채우고, 잠금 상세를 {@code DBA_LOCK}
 * (catproc 뷰, DBA 권한)에서 읽으며, 테이블 잠금(TM) 대기의 객체를 보이지 않는다. SQL Server·MySQL과 같은 행 모양
 * ({@link AbstractBlockingLockManager})으로 다시 만들었다.
 * <ul>
 *     <li>대기 관계: 서버가 판단한 {@code V$SESSION.BLOCKING_SESSION}(BLOCKING_SESSION_STATUS = VALID)을 그대로 쓴다.
 *     대기 종류는 대기 이벤트(예: "enq: TX - row lock contention")다.</li>
 *     <li>잠금 대상 → 객체: 요청 중인 잠금({@code V$LOCK.REQUEST > 0})이 TM이면 ID1이 객체 번호, 그 밖(TX 행 잠금 등)은
 *     {@code V$SESSION.ROW_WAIT_OBJ#}과 파일·블록·행 번호다. 이름은 {@code DBA_OBJECTS}에서 찾고, 그 뷰를 읽을 수 없는 로그인은
 *     {@code ALL_OBJECTS}(권한 있는 객체만)를 쓴다({@link #setObjectsView}, 권한 확인이 정함).</li>
 *     <li>막는 세션의 문장은 실행 중이면 SQL_ID, 아니면(유휴 트랜잭션) PREV_SQL_ID의 {@code V$SQL} 문장이다.</li>
 *     <li>세션 끝내기는 SWT 잠금 편집기와 같은 {@code ALTER SYSTEM KILL SESSION 'SID,SERIAL#' IMMEDIATE}다
 *     (기다리던 세션이 바로 이어 가도록 IMMEDIATE). SERIAL#은 목록을 읽을 때의 값({@link OracleBlockingLock})이다.</li>
 *     <li>라이선스: Diagnostics/Tuning Pack이 필요한 ASH·AWR 뷰({@code V$ACTIVE_SESSION_HISTORY}, {@code DBA_HIST_*})는 쓰지 않는다.</li>
 *     <li>현재 인스턴스({@code V$})만 본다. RAC의 다른 인스턴스 세션이 막으면 막는 세션 행은 비어 있다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: Oracle 세션·잠금 (docs/chromite/swing-admin-tools.md)
 */
public class OracleBlockingLockManager extends AbstractBlockingLockManager {

    public static final String DBA_OBJECTS = "DBA_OBJECTS"; //$NON-NLS-1$
    public static final String ALL_OBJECTS = "ALL_OBJECTS"; //$NON-NLS-1$

    private static final String LOCK_WAITS_QUERY =
        "SELECT w.SID AS wait_id, w.SERIAL# AS wait_serial, w.USERNAME AS wait_user,\n" +
        "w.BLOCKING_SESSION AS hold_id, h.SERIAL# AS hold_serial, h.USERNAME AS hold_user,\n" +
        "w.EVENT AS wait_event, w.SECONDS_IN_WAIT AS wait_seconds, rl.TYPE AS lock_type, rl.REQUEST AS lock_request,\n" +
        "w.ROW_WAIT_FILE# AS row_file, w.ROW_WAIT_BLOCK# AS row_block, w.ROW_WAIT_ROW# AS row_row,\n" +
        "(SELECT o.OWNER || '.' || o.OBJECT_NAME FROM %1$s o\n" +
        "  WHERE o.OBJECT_ID = CASE WHEN rl.TYPE = 'TM' THEN rl.ID1 ELSE NULLIF(w.ROW_WAIT_OBJ#, -1) END) AS object_name,\n" +
        "(SELECT q.SQL_TEXT FROM V$SQL q WHERE q.SQL_ID = w.SQL_ID AND ROWNUM = 1) AS wait_statement,\n" +
        "(SELECT q.SQL_TEXT FROM V$SQL q WHERE q.SQL_ID = NVL(h.SQL_ID, h.PREV_SQL_ID) AND ROWNUM = 1) AS hold_statement\n" +
        "FROM V$SESSION w\n" +
        "LEFT JOIN V$SESSION h ON h.SID = w.BLOCKING_SESSION\n" +
        "LEFT JOIN V$LOCK rl ON rl.SID = w.SID AND rl.REQUEST > 0\n" +
        "WHERE w.BLOCKING_SESSION IS NOT NULL AND w.BLOCKING_SESSION_STATUS = 'VALID'\n" +
        "ORDER BY w.SID"; //$NON-NLS-1$

    private static final String LOCK_ITEMS_QUERY =
        "SELECT l.SID, l.TYPE, l.LMODE, l.REQUEST, l.ID1, l.ID2, l.CTIME, l.BLOCK,\n" +
        "(SELECT o.OWNER || '.' || o.OBJECT_NAME FROM %1$s o WHERE o.OBJECT_ID =\n" +
        "  CASE WHEN l.TYPE = 'TM' THEN l.ID1\n" +
        "  WHEN l.REQUEST > 0 THEN (SELECT NULLIF(s.ROW_WAIT_OBJ#, -1) FROM V$SESSION s WHERE s.SID = l.SID) END) AS object_name\n" +
        "FROM V$LOCK l\n" +
        "WHERE l.SID = ?\n" +
        "ORDER BY CASE WHEN l.REQUEST > 0 THEN 0 ELSE 1 END, l.TYPE"; //$NON-NLS-1$

    private volatile String objectsView = DBA_OBJECTS;

    public OracleBlockingLockManager(@NotNull OracleDataSource dataSource) {
        super(dataSource);
    }

    /**
     * The view the object names come from: {@link #DBA_OBJECTS}, or {@link #ALL_OBJECTS} when the login cannot read it.
     */
    public void setObjectsView(@NotNull String objectsView) {
        this.objectsView = objectsView;
    }

    @NotNull
    @Override
    protected List<BlockingSessionLock> readLockWaits(@NotNull JDBCSession session) throws SQLException {
        List<BlockingSessionLock> waits = new ArrayList<>();
        try (JDBCPreparedStatement dbStat = session.prepareStatement(String.format(LOCK_WAITS_QUERY, objectsView));
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            while (dbResult.next()) {
                String lockType = JDBCUtils.safeGetString(dbResult, "lock_type"); //$NON-NLS-1$
                String objectName = JDBCUtils.safeGetString(dbResult, "object_name"); //$NON-NLS-1$
                String resource;
                if (objectName != null && !"TM".equals(lockType)) { //$NON-NLS-1$
                    // A row: file, block and row number as in V$SESSION.ROW_WAIT_*
                    resource = objectName + " (file " + JDBCUtils.safeGetLong(dbResult, "row_file") //$NON-NLS-1$ //$NON-NLS-2$
                        + ", block " + JDBCUtils.safeGetLong(dbResult, "row_block") //$NON-NLS-1$ //$NON-NLS-2$
                        + ", row " + JDBCUtils.safeGetLong(dbResult, "row_row") + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                } else {
                    resource = objectName != null ? objectName : lockType;
                }
                String event = JDBCUtils.safeGetString(dbResult, "wait_event"); //$NON-NLS-1$
                int request = JDBCUtils.safeGetInt(dbResult, "lock_request"); //$NON-NLS-1$
                waits.add(new OracleBlockingLock(
                    JDBCUtils.safeGetLong(dbResult, "wait_id"), //$NON-NLS-1$
                    JDBCUtils.safeGetLong(dbResult, "wait_serial"), //$NON-NLS-1$
                    JDBCUtils.safeGetString(dbResult, "wait_user"), //$NON-NLS-1$
                    JDBCUtils.safeGetLongNullable(dbResult, "hold_id"), //$NON-NLS-1$
                    JDBCUtils.safeGetLongNullable(dbResult, "hold_serial"), //$NON-NLS-1$
                    JDBCUtils.safeGetString(dbResult, "hold_user"), //$NON-NLS-1$
                    JDBCUtils.safeGetString(dbResult, "wait_statement"), //$NON-NLS-1$
                    JDBCUtils.safeGetString(dbResult, "hold_statement"), //$NON-NLS-1$
                    resource,
                    lockType == null || request == 0 ? event : event + " (" + lockType + " " + getModeName(request) + ")", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    JDBCUtils.safeGetLongNullable(dbResult, "wait_seconds"))); //$NON-NLS-1$
            }
        }
        return waits;
    }

    @NotNull
    @Override
    protected BlockingSessionLock createRootLock(@NotNull BlockingSessionLock wait) {
        Long holdSerial = wait instanceof OracleBlockingLock oracleLock ? oracleLock.getHoldSerial() : null;
        return new OracleBlockingLock(wait.getHoldID(), holdSerial == null ? 0 : holdSerial, wait.getHoldUser(), null, null, null,
            wait.getHoldStatement(), null, null, null, null);
    }

    @NotNull
    @Override
    protected List<SessionLockItem> readLockItems(@NotNull JDBCSession session, long sessionId) throws SQLException {
        List<SessionLockItem> items = new ArrayList<>();
        try (JDBCPreparedStatement dbStat = session.prepareStatement(String.format(LOCK_ITEMS_QUERY, objectsView))) {
            dbStat.setLong(1, sessionId);
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                while (dbResult.next()) {
                    int held = JDBCUtils.safeGetInt(dbResult, "LMODE"); //$NON-NLS-1$
                    int request = JDBCUtils.safeGetInt(dbResult, "REQUEST"); //$NON-NLS-1$
                    String status = request > 0 ? "WAITING" : JDBCUtils.safeGetInt(dbResult, "BLOCK") > 0 ? "BLOCKING" : "GRANTED"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    items.add(new SessionLockItem(
                        JDBCUtils.safeGetLong(dbResult, "SID"), //$NON-NLS-1$
                        null,
                        JDBCUtils.safeGetString(dbResult, "object_name"), //$NON-NLS-1$
                        JDBCUtils.safeGetString(dbResult, "TYPE"), //$NON-NLS-1$
                        getModeName(request > 0 ? request : held),
                        status,
                        "ID1=" + JDBCUtils.safeGetString(dbResult, "ID1") + ", ID2=" + JDBCUtils.safeGetString(dbResult, "ID2") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                            + ", " + JDBCUtils.safeGetLong(dbResult, "CTIME") + " s")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }
        }
        return items;
    }

    @Override
    protected void killSession(@NotNull JDBCSession session, @NotNull BlockingSessionLock lock) throws SQLException {
        long serial = lock instanceof OracleBlockingLock oracleLock ? oracleLock.getSerial() : 0;
        // ALTER SYSTEM takes no bind variables; both values are numbers read from the server
        try (JDBCPreparedStatement dbStat = session.prepareStatement(
            "ALTER SYSTEM KILL SESSION '" + lock.getWaitId() + "," + serial + "' IMMEDIATE")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            dbStat.execute();
        }
    }

    /**
     * Lock mode names of the Oracle documentation (V$LOCK.LMODE / REQUEST).
     */
    @Nullable
    private static String getModeName(int mode) {
        return switch (mode) {
            case 0 -> null;
            case 1 -> "Null (N)"; //$NON-NLS-1$
            case 2 -> "Row-S (SS)"; //$NON-NLS-1$
            case 3 -> "Row-X (SX)"; //$NON-NLS-1$
            case 4 -> "Share (S)"; //$NON-NLS-1$
            case 5 -> "S/Row-X (SSX)"; //$NON-NLS-1$
            case 6 -> "Exclusive (X)"; //$NON-NLS-1$
            default -> String.valueOf(mode);
        };
    }
}
