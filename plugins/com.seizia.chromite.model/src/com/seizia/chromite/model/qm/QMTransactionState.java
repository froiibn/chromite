/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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

package com.seizia.chromite.model.qm;

import com.seizia.chromite.model.exec.DBCServerTransaction;
import org.jkiss.code.NotNull;

import java.util.Set;

/**
 * Transaction state of an execution context: what the database last said the session's transaction holds, completed
 * with the user statements the query manager saw run in it.
 *
 * 설계: 열린 트랜잭션 표시(쿼리 창 상태 줄), 유휴 트랜잭션 감시({@code IdleTransactionMonitor}), 끝낼 때·끊을 때 질문, 오류 복구
 * 멈춤, 유휴 연결 닫기가 같은 값을 쓴다. 메모리 안 기록만 읽으므로(드라이버 호출 없음) EDT에서 1초마다 읽어도 된다. DB에 묻는 일은
 * 실행 작업 스레드에서 미리 해 둔다({@code QMTransactionTracker}).
 * <ul>
 *     <li>열림(사용자 원칙 2026-11-08 "실제로 쥐고 있을 때만"): DB가 트랜잭션이 무언가를 쥐었다고 답했을 때(변경, 끝까지 쥐는 잠금,
 *     스냅숏, 오류로 중단됨) 또는 사용자가 명시적으로 시작했을 때. 자동 커밋 모드도 같다. 쥔 것 없는 SELECT는 열림이 아니다.
 *     DB에 물을 수 없으면(질의가 없는 DB, 권한 없음, 질의 실패) 문장 분류(데이터 변경, 잠그는 읽기, 명시적 BEGIN)로 열림을 정하고,
 *     분류로도 모르면 "확인할 수 없음"({@link Status#UNKNOWN})이다. 열림으로 꾸미지 않는다.</li>
 *     <li>{@link #isOpen()}은 열림 또는 확인할 수 없음이다. 묻고 막는 안전 동작은 확인할 수 없음을 열림처럼 다룬다. 표시는
 *     {@link #isUnknown()}으로 둘을 구별한다.</li>
 *     <li>문장 수와 시작·마지막 시각은 사용자 문장만 센다. 메타데이터 읽기(META)와 내부 작업(UTIL, 커밋·롤백 세션 등)은
 *     사용자가 연 트랜잭션이 아니고, 같은 문맥에서 탐색기가 메타데이터를 읽을 때마다 유휴 시간이 0으로 돌아가면 안 되기 때문이다.
 *     세는 범위는 트랜잭션이 없다고 확인된 뒤의 문장이다(자동 커밋 모드의 명시적 트랜잭션 포함).</li>
 *     <li>{@link #isExecuting()}은 목적과 관계없이 그 문맥에서 아직 끝나지 않은 문장이 있는지다. 실행 중에는 유휴가 아니다.</li>
 *     <li>{@link #getTransactionStartTime()}은 트랜잭션을 구별하는 값으로도 쓴다. 커밋·롤백 뒤의 새 트랜잭션은 첫 문장 시각이 다르다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 유휴 트랜잭션 처리: 마지막 문장 시각, 실행 중 여부, 열림 여부를 더하고 수정 문장 수({@link #getUpdateCount()})를
 *   쿼리 관리자의 "트랜잭션에 영향을 주는 문장" 기준으로 바꿈. 열린 트랜잭션이 없으면 시작 시각은 0 (docs/chromite/idle-transactions.md)
 * - 2026-11-08 열림을 DB의 답으로 정함(상태 {@link #getStatus()}, 쥔 것 {@link #getHolds()}): 수동 커밋에서 SELECT만 해도 열림이던
 *   것을 고치고 자동 커밋의 명시적 트랜잭션도 열림으로 (docs/chromite/idle-transactions.md "트랜잭션 상태")
 */
public class QMTransactionState {

    public enum Status {
        /** No transaction, or one that holds nothing. */
        NONE,
        /** The transaction holds something or was begun explicitly. */
        OPEN,
        /** The database could not be asked and the statements do not tell. */
        UNKNOWN
    }

    private final int executeCount;
    private final int updateCount;
    private final boolean transactionMode;
    private final long transactionStartTime;
    private final long lastExecuteTime;
    private final boolean executing;
    private final Status status;
    private final Set<DBCServerTransaction.Hold> holds;
    private final boolean serverChecked;

    QMTransactionState(
        int executeCount,
        int updateCount,
        boolean transactionMode,
        long transactionStartTime,
        long lastExecuteTime,
        boolean executing,
        @NotNull Status status,
        @NotNull Set<DBCServerTransaction.Hold> holds,
        boolean serverChecked
    ) {
        this.executeCount = executeCount;
        this.updateCount = updateCount;
        this.transactionMode = transactionMode;
        this.transactionStartTime = transactionStartTime;
        this.lastExecuteTime = lastExecuteTime;
        this.executing = executing;
        this.status = status;
        this.holds = holds;
        this.serverChecked = serverChecked;
    }

    /**
     * User statements run in the transaction.
     */
    public int getExecuteCount() {
        return executeCount;
    }

    /**
     * User statements that changed something or may have (the query manager's "transactional" statements: DML, DDL,
     * statements with an update count, failed statements), except those that only change the session (SET, USE,
     * ALTER SESSION, savepoints, BEGIN, COMMIT, ROLLBACK).
     */
    public int getUpdateCount() {
        return updateCount;
    }

    /**
     * Manual commit mode.
     */
    public boolean isTransactionMode() {
        return transactionMode;
    }

    /**
     * The transaction holds something or was begun explicitly ({@link Status#OPEN}), or that cannot be told
     * ({@link Status#UNKNOWN}): what the safety checks treat as an open transaction.
     */
    public boolean isOpen() {
        return status != Status.NONE;
    }

    /**
     * Whether the database holds something for the transaction could not be told: shown apart from an open transaction.
     */
    public boolean isUnknown() {
        return status == Status.UNKNOWN;
    }

    @NotNull
    public Status getStatus() {
        return status;
    }

    /**
     * What the transaction holds, as the database said (and as the statements show: changes, an explicit begin).
     */
    @NotNull
    public Set<DBCServerTransaction.Hold> getHolds() {
        return holds;
    }

    /**
     * The status is the database's answer, not a classification of the statements.
     */
    public boolean isServerChecked() {
        return serverChecked;
    }

    /**
     * Start time of the transaction: of its first user statement, or when the database first said it is open (after a
     * commit that left a nested transaction). 0 when there is none.
     */
    public long getTransactionStartTime() {
        return transactionStartTime;
    }

    /**
     * End time of the last user statement (its start time while it runs), or 0 when none ran.
     */
    public long getLastExecuteTime() {
        return lastExecuteTime;
    }

    /**
     * A statement (of any purpose) is still running in the context.
     */
    public boolean isExecuting() {
        return executing;
    }
}
