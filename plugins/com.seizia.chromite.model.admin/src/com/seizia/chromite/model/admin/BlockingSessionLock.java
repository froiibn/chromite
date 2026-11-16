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
import com.seizia.chromite.model.admin.locks.DBAServerLock;
import com.seizia.chromite.model.meta.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * 세션 사이의 잠금 대기 한 건: 기다리는 세션(wait)과 그 세션을 막는 세션(hold).
 *
 * 설계: PostgreSQL의 {@code PostgreLock}과 같은 모양이다. 잠금 목록의 한 행이 "기다리는 세션"이고, 그 세션을 막는 세션은
 * {@link #getHoldID()}로 가리킨다. 남을 막기만 하고 자신은 기다리지 않는 세션은 hold가 없는 행(뿌리)으로 따로 넣어
 * {@code LockGraphManager#buildGraphs}가 대기 → 보유 관계를 나무로 묶게 한다.
 * SQL Server와 MySQL/MariaDB 잠금 관리자({@link AbstractBlockingLockManager})가 함께 쓴다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: SQL Server, MySQL/MariaDB 잠금 보기 (docs/chromite/swing-admin-tools.md)
 */
public class BlockingSessionLock implements DBAServerLock {

    private final long waitId;
    @Nullable
    private final String waitUser;
    @Nullable
    private final Long holdId;
    @Nullable
    private final String holdUser;
    @Nullable
    private final String waitStatement;
    @Nullable
    private final String holdStatement;
    @Nullable
    private final String waitResource;
    @Nullable
    private final String waitMode;
    @Nullable
    private final Long waitSeconds;

    private DBAServerLock holdBy;
    private final List<DBAServerLock> waiters = new ArrayList<>();

    public BlockingSessionLock(
        long waitId,
        @Nullable String waitUser,
        @Nullable Long holdId,
        @Nullable String holdUser,
        @Nullable String waitStatement,
        @Nullable String holdStatement,
        @Nullable String waitResource,
        @Nullable String waitMode,
        @Nullable Long waitSeconds
    ) {
        this.waitId = waitId;
        this.waitUser = waitUser;
        this.holdId = holdId;
        this.holdUser = holdUser;
        this.waitStatement = waitStatement;
        this.holdStatement = holdStatement;
        this.waitResource = waitResource;
        this.waitMode = waitMode;
        this.waitSeconds = waitSeconds;
    }

    /**
     * A session that blocks others but waits for nobody: the root of a lock tree.
     */
    @NotNull
    static BlockingSessionLock createRoot(long sessionId, @Nullable String user, @Nullable String statement) {
        return new BlockingSessionLock(sessionId, user, null, null, statement, null, null, null, null);
    }

    @Property(viewable = true, order = 1)
    public long getWaitId() {
        return waitId;
    }

    @Nullable
    @Property(viewable = true, order = 2)
    public String getWaitUser() {
        return waitUser;
    }

    @Nullable
    @Property(viewable = true, order = 3)
    public Long getHoldSessionId() {
        return holdId;
    }

    @Nullable
    @Property(viewable = true, order = 4)
    public String getHoldUser() {
        return holdUser;
    }

    @Nullable
    @Property(viewable = true, order = 5)
    public String getWaitResource() {
        return waitResource;
    }

    @Nullable
    @Property(viewable = true, order = 6)
    public String getWaitMode() {
        return waitMode;
    }

    @Nullable
    @Property(viewable = true, order = 7)
    public Long getWaitSeconds() {
        return waitSeconds;
    }

    @Nullable
    @Property(viewable = true, order = 8)
    public String getWaitStatement() {
        return waitStatement;
    }

    @Nullable
    @Property(viewable = true, order = 9)
    public String getHoldStatement() {
        return holdStatement;
    }

    @NotNull
    @Override
    public String getTitle() {
        return String.valueOf(waitId);
    }

    @NotNull
    @Override
    public Long getId() {
        return waitId;
    }

    @Override
    public DBAServerLock getHoldBy() {
        return holdBy;
    }

    @Override
    public void setHoldBy(DBAServerLock lock) {
        this.holdBy = lock;
    }

    @Nullable
    @Override
    public Long getHoldID() {
        return holdId;
    }

    @NotNull
    @Override
    public List<DBAServerLock> waitThis() {
        return waiters;
    }

    @Override
    public String toString() {
        return holdId == null ? "Session " + waitId : "Session " + waitId + " waits for " + holdId;
    }
}
