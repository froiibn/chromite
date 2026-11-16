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
import com.seizia.chromite.model.meta.Property;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * Oracle 잠금 대기 한 건: {@link BlockingSessionLock}에 두 세션의 SERIAL#과 (RAC면) 인스턴스 번호를 더했다.
 *
 * 설계: Oracle은 SID를 다시 쓰므로 {@code ALTER SYSTEM KILL SESSION}은 'SID,SERIAL#'로 세션을 가리킨다. 목록을 읽은 뒤 확인 창에서
 * "예"를 누르는 사이에 그 세션이 끝나고 같은 SID로 다른 세션이 생길 수 있어, 끝낼 때 SERIAL#을 다시 읽지 않고 목록을 읽을 때의 값을 쓴다.
 * 뿌리 행(head blocker)의 SERIAL#은 그 세션을 기다리는 행의 {@link #getHoldSerial()}에서 온다
 * ({@link OracleBlockingLockManager#createRootLock}).
 * <p>
 * RAC(인스턴스가 둘 이상)에서는 SID가 인스턴스마다 따로 매겨져 SID만으로는 세션을 가리킬 수 없다. 그래서 인스턴스 번호를 두고,
 * 잠금 그래프와 상세 조회의 열쇠({@link #getId()}, {@link #getHoldID()})를 인스턴스와 SID를 묶은 값({@link #sessionKey})으로 바꾼다.
 * 화면의 세션 번호 열({@link #getWaitId()}, {@link #getHoldSessionId()})은 SID 그대로이고 인스턴스는 따로 열로 보인다
 * (RAC일 때만, {@link OracleBlockingLockManager#getLocksType()}). 단일 인스턴스에서는 인스턴스가 null이라 열쇠도 SID 그대로다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: Oracle 세션·잠금 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-08 RAC: 두 세션의 인스턴스 번호와 인스턴스를 묶은 열쇠 (docs/chromite/swing-admin-tools.md "Oracle RAC와 쿼리 취소")
 */
public class OracleBlockingLock extends BlockingSessionLock {

    private final long serial;
    @Nullable
    private final Long holdSerial;
    @Nullable
    private final Integer waitInstance;
    @Nullable
    private final Integer holdInstance;

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
        @Nullable Long waitSeconds,
        @Nullable Integer waitInstance,
        @Nullable Integer holdInstance
    ) {
        super(waitId, waitUser, holdId, holdUser, waitStatement, holdStatement, waitResource, waitMode, waitSeconds);
        this.serial = serial;
        this.holdSerial = holdSerial;
        this.waitInstance = waitInstance;
        this.holdInstance = holdInstance;
    }

    /**
     * The lock graph key of a session on a RAC instance: the instance number in the high 32 bits, the SID in the low ones.
     */
    public static long sessionKey(int instance, long sid) {
        return ((long) instance << 32) | sid;
    }

    public long getSerial() {
        return serial;
    }

    @Nullable
    public Long getHoldSerial() {
        return holdSerial;
    }

    /**
     * The instance of the waiting session (RAC only, else null).
     */
    @Nullable
    @Property(viewable = true, order = 11)
    public Integer getWaitInstance() {
        return waitInstance;
    }

    /**
     * The instance of the blocking session (RAC only, else null).
     */
    @Nullable
    @Property(viewable = true, order = 12)
    public Integer getHoldInstance() {
        return holdInstance;
    }

    @NotNull
    @Override
    public Long getId() {
        // Not a conditional expression: Long and long operands would unbox (see getHoldID)
        if (waitInstance == null) {
            return super.getId();
        }
        return sessionKey(waitInstance, getWaitId());
    }

    @Nullable
    @Override
    public Long getHoldID() {
        Long holdId = super.getHoldID();
        // Not a conditional expression: with a long operand it unboxes a null holdId (root rows)
        if (holdId == null || holdInstance == null) {
            return holdId;
        }
        return sessionKey(holdInstance, holdId);
    }

    @NotNull
    @Override
    public String getTitle() {
        return waitInstance == null ? super.getTitle() : getWaitId() + "@" + waitInstance; //$NON-NLS-1$
    }
}
