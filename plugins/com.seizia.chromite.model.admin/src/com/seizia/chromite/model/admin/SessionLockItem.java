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

import org.jkiss.code.Nullable;
import com.seizia.chromite.model.admin.locks.DBAServerLockItem;
import com.seizia.chromite.model.meta.Property;

/**
 * 세션 하나가 가진(또는 기다리는) 잠금 한 건.
 *
 * 설계: {@code PostgreLockItem}처럼 잠금 상세 표의 한 행이다. SQL Server({@code sys.dm_tran_locks})와
 * MySQL/MariaDB(InnoDB 잠금)의 열 이름이 달라 공통 열(데이터베이스, 객체, 종류, 모드, 상태, 상세)로 옮겨 담는다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: SQL Server, MySQL/MariaDB 잠금 보기 (docs/chromite/swing-admin-tools.md)
 */
public class SessionLockItem implements DBAServerLockItem {

    private final long sessionId;
    @Nullable
    private final String database;
    @Nullable
    private final String objectName;
    @Nullable
    private final String lockType;
    @Nullable
    private final String mode;
    @Nullable
    private final String status;
    @Nullable
    private final String detail;

    public SessionLockItem(
        long sessionId,
        @Nullable String database,
        @Nullable String objectName,
        @Nullable String lockType,
        @Nullable String mode,
        @Nullable String status,
        @Nullable String detail
    ) {
        this.sessionId = sessionId;
        this.database = database;
        this.objectName = objectName;
        this.lockType = lockType;
        this.mode = mode;
        this.status = status;
        this.detail = detail;
    }

    // Not a column: the header of each lock table names its session
    @Property(order = 1)
    public long getSessionId() {
        return sessionId;
    }

    // Optional: MySQL/MariaDB locks name the schema in the object, so the column is left out there
    @Nullable
    @Property(viewable = true, optional = true, order = 2)
    public String getDatabase() {
        return database;
    }

    @Nullable
    @Property(viewable = true, order = 3)
    public String getObjectName() {
        return objectName;
    }

    @Nullable
    @Property(viewable = true, order = 4)
    public String getLockType() {
        return lockType;
    }

    @Nullable
    @Property(viewable = true, order = 5)
    public String getMode() {
        return mode;
    }

    @Nullable
    @Property(viewable = true, order = 6)
    public String getStatus() {
        return status;
    }

    @Nullable
    @Property(viewable = true, order = 7)
    public String getDetail() {
        return detail;
    }
}
