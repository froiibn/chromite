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
package com.seizia.chromite.model.admin;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.admin.locks.DBAServerLockManager;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.admin.locks.LockGraphManager;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 잠금 대기 목록을 읽는 잠금 관리자의 공통 부분 (SQL Server, MySQL/MariaDB).
 *
 * 설계: DBeaver에는 PostgreSQL·Oracle 등의 잠금 관리자({@code PostgreLockManager})만 있고 SQL Server와 MySQL/MariaDB에는 없다.
 * 같은 구조({@link LockGraphManager} 상속, {@link DBAServerLockManager} 구현)로 만들어 화면이 DB 종류와 상관없이 같은 API를 쓰게 한다.
 * <ul>
 *     <li>하위 클래스는 "누가 누구를 기다리는가"({@link #readLockWaits})만 읽는다. 남을 막기만 하는 세션은 여기서 뿌리 행으로 더한다.
 *     PostgreSQL의 잠금 질의가 SQL 안에서 같은 일을 한다(root_quest).</li>
 *     <li>한 세션이 여러 세션을 기다리면 처음 읽은 행 하나만 둔다(PostgreSQL 질의의 {@code rid = 1}과 같음).</li>
 *     <li>잠금 상세({@link #getLockItems})의 대상 세션은 {@link LockGraphManager#keyType}(wait/hold)와
 *     {@link #OPTION_WAIT_SESSION}/{@link #OPTION_HOLD_SESSION}으로 받는다. PostgreSQL의 {@code pidWait}/{@code pidHold}와 같은 방식이다.</li>
 *     <li>"세션 끝내기"({@link #alterSession})는 고른 행의 세션(기다리는 세션, 뿌리면 막는 세션)을 끝낸다. SWT 잠금 보기의
 *     "Kill waiting session"과 같다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: SQL Server, MySQL/MariaDB 잠금 보기 (docs/chromite/swing-admin-tools.md)
 */
public abstract class AbstractBlockingLockManager extends LockGraphManager
    implements DBAServerLockManager<BlockingSessionLock, SessionLockItem> {

    public static final String OPTION_WAIT_SESSION = "waitSession"; //$NON-NLS-1$
    public static final String OPTION_HOLD_SESSION = "holdSession"; //$NON-NLS-1$

    private final DBPDataSource dataSource;

    protected AbstractBlockingLockManager(@NotNull DBPDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return dataSource;
    }

    @NotNull
    @Override
    public Class<BlockingSessionLock> getLocksType() {
        return BlockingSessionLock.class;
    }

    @NotNull
    @Override
    public Map<Object, BlockingSessionLock> getLocks(@NotNull DBCSession session, @Nullable Map<String, Object> options) throws DBException {
        List<BlockingSessionLock> waits;
        try {
            waits = readLockWaits((JDBCSession) session);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, session.getDataSource());
        }
        Map<Object, BlockingSessionLock> locks = new LinkedHashMap<>();
        for (BlockingSessionLock wait : waits) {
            locks.putIfAbsent(wait.getId(), wait);
        }
        // Blockers that wait for nobody are the roots of the lock trees
        for (BlockingSessionLock wait : waits) {
            Long holdId = wait.getHoldID();
            if (holdId != null && !locks.containsKey(holdId)) {
                locks.put(holdId, BlockingSessionLock.createRoot(holdId, wait.getHoldUser(), wait.getHoldStatement()));
            }
        }
        buildGraphs(locks);
        return locks;
    }

    @NotNull
    @Override
    public Collection<SessionLockItem> getLockItems(@NotNull DBCSession session, @NotNull Map<String, Object> options) throws DBException {
        Object type = options.get(LockGraphManager.keyType);
        Object sessionId = LockGraphManager.typeHold.equals(type) ? options.get(OPTION_HOLD_SESSION)
            : LockGraphManager.typeWait.equals(type) ? options.get(OPTION_WAIT_SESSION) : null;
        if (!(sessionId instanceof Number number)) {
            return Collections.emptyList();
        }
        try {
            return readLockItems((JDBCSession) session, number.longValue());
        } catch (SQLException e) {
            throw new DBDatabaseException(e, session.getDataSource());
        }
    }

    @Override
    public void alterSession(@NotNull DBCSession session, @NotNull BlockingSessionLock lock, @Nullable Map<String, Object> options) throws DBException {
        try {
            killSession((JDBCSession) session, lock.getWaitId());
        } catch (SQLException e) {
            throw new DBDatabaseException(e, session.getDataSource());
        }
    }

    /**
     * Options of {@link #getLockItems} for the locks of the session that waits ({@code holding} false) or of the
     * session that blocks it.
     */
    @NotNull
    public static Map<String, Object> getLockItemOptions(@NotNull BlockingSessionLock lock, boolean holding) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put(LockGraphManager.keyType, holding ? LockGraphManager.typeHold : LockGraphManager.typeWait);
        options.put(OPTION_WAIT_SESSION, lock.getWaitId());
        if (lock.getHoldID() != null) {
            options.put(OPTION_HOLD_SESSION, lock.getHoldID());
        }
        return options;
    }

    /**
     * Reads the sessions that wait for a lock, each with the session holding it.
     */
    @NotNull
    protected abstract List<BlockingSessionLock> readLockWaits(@NotNull JDBCSession session) throws SQLException;

    /**
     * Reads the locks a session holds or waits for.
     */
    @NotNull
    protected abstract List<SessionLockItem> readLockItems(@NotNull JDBCSession session, long sessionId) throws SQLException;

    /**
     * Ends a session (its connection).
     */
    protected abstract void killSession(@NotNull JDBCSession session, long sessionId) throws SQLException;
}
