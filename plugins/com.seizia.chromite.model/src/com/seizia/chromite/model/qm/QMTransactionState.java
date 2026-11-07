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

/**
 * Transaction state of an execution context, as the query manager saw it: the statements run since the last commit
 * or rollback.
 *
 * 설계: 열린 트랜잭션 표시(쿼리 창 상태 줄)와 유휴 트랜잭션 감시({@code IdleTransactionMonitor})가 같은 값을 쓴다.
 * 쿼리 관리자의 메모리 안 기록만 읽으므로(드라이버 호출 없음) EDT에서 1초마다 읽어도 된다.
 * <ul>
 *     <li>문장 수와 시작·마지막 시각은 사용자 문장만 센다. 메타데이터 읽기(META)와 내부 작업(UTIL, 커밋·롤백 세션 등)은
 *     사용자가 연 트랜잭션이 아니고, 같은 문맥에서 탐색기가 메타데이터를 읽을 때마다 유휴 시간이 0으로 돌아가면 안 되기 때문이다.</li>
 *     <li>오류가 난 문장도 센다. PostgreSQL은 오류 뒤에도 트랜잭션이 열린 채(aborted) 롤백을 기다린다.</li>
 *     <li>{@link #isExecuting()}은 목적과 관계없이 그 문맥에서 아직 끝나지 않은 문장이 있는지다. 실행 중에는 유휴가 아니다.</li>
 *     <li>{@link #getTransactionStartTime()}은 트랜잭션을 구별하는 값으로도 쓴다. 커밋·롤백 뒤의 새 트랜잭션은 첫 문장 시각이 다르다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 유휴 트랜잭션 처리: 마지막 문장 시각, 실행 중 여부, 열림 여부를 더하고 수정 문장 수({@link #getUpdateCount()})를
 *   쿼리 관리자의 "트랜잭션에 영향을 주는 문장" 기준으로 바꿈. 열린 트랜잭션이 없으면 시작 시각은 0 (docs/chromite/idle-transactions.md)
 */
public class QMTransactionState {
    private final int executeCount;
    private final int updateCount;
    private final boolean transactionMode;
    private final long transactionStartTime;
    private final long lastExecuteTime;
    private final boolean executing;

    QMTransactionState(
        int executeCount,
        int updateCount,
        boolean transactionMode,
        long transactionStartTime,
        long lastExecuteTime,
        boolean executing
    ) {
        this.executeCount = executeCount;
        this.updateCount = updateCount;
        this.transactionMode = transactionMode;
        this.transactionStartTime = transactionStartTime;
        this.lastExecuteTime = lastExecuteTime;
        this.executing = executing;
    }

    /**
     * User statements run in the transaction.
     */
    public int getExecuteCount() {
        return executeCount;
    }

    /**
     * User statements that changed something or may have (the query manager's "transactional" statements: DML, DDL,
     * statements with an update count, failed statements).
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
     * Manual commit mode with user statements since the last commit or rollback.
     */
    public boolean isOpen() {
        return transactionMode && executeCount > 0;
    }

    /**
     * Start time of the first user statement of the transaction, or 0 when none ran.
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
