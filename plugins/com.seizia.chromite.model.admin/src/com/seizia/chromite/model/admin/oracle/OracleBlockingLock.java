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

import com.seizia.chromite.model.admin.BlockingSessionLock;
import org.jkiss.code.Nullable;

/**
 * Oracle 잠금 대기 한 건: {@link BlockingSessionLock}에 두 세션의 SERIAL#을 더했다.
 *
 * 설계: Oracle은 SID를 다시 쓰므로 {@code ALTER SYSTEM KILL SESSION}은 'SID,SERIAL#'로 세션을 가리킨다. 목록을 읽은 뒤 확인 창에서
 * "예"를 누르는 사이에 그 세션이 끝나고 같은 SID로 다른 세션이 생길 수 있어, 끝낼 때 SERIAL#을 다시 읽지 않고 목록을 읽을 때의 값을 쓴다.
 * 뿌리 행(head blocker)의 SERIAL#은 그 세션을 기다리는 행의 {@link #getHoldSerial()}에서 온다
 * ({@link OracleBlockingLockManager#createRootLock}).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: Oracle 세션·잠금 (docs/chromite/swing-admin-tools.md)
 */
public class OracleBlockingLock extends BlockingSessionLock {

    private final long serial;
    @Nullable
    private final Long holdSerial;

    public OracleBlockingLock(
        long waitId,
        long serial,
        @Nullable String waitUser,
        @Nullable Long holdId,
        @Nullable Long holdSerial,
        @Nullable String holdUser,
        @Nullable String waitStatement,
        @Nullable String holdStatement,
        @Nullable String waitResource,
        @Nullable String waitMode,
        @Nullable Long waitSeconds
    ) {
        super(waitId, waitUser, holdId, holdUser, waitStatement, holdStatement, waitResource, waitMode, waitSeconds);
        this.serial = serial;
        this.holdSerial = holdSerial;
    }

    public long getSerial() {
        return serial;
    }

    @Nullable
    public Long getHoldSerial() {
        return holdSerial;
    }
}
