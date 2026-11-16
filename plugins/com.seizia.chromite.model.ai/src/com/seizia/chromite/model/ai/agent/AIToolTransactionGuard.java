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
package com.seizia.chromite.model.ai.agent;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;

import java.util.List;
import java.util.Map;

/**
 * Optional interface of a tool implementation whose call would end an uncommitted transaction: it changes a transaction
 * mode, disconnects, switches the database, closes a context, runs DDL that commits implicitly, kills a session,
 * or commits/rolls back on request.
 *
 * 설계: 사용자 규칙 "트랜잭션 안전"(2026-11-07): 열린 트랜잭션을 조용히 커밋·롤백하지 않는다. 실행기는 이 인터페이스로 걸린 트랜잭션을 찾고,
 * 하나라도 있으면 자동 실행 설정과 상관없이 확인 처리기에 Commit / Roll back / Cancel을 묻는다. 답에 따라 {@link #resolvePendingTransactions}로
 * 커밋하거나 롤백한 뒤에만 도구를 실행한다. 처리기가 없으면 거부한다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "트랜잭션 안전")
 */
public interface AIToolTransactionGuard {

    /**
     * Uncommitted transactions this call would end; empty when none.
     */
    @NotNull
    List<AIPendingTransaction> findPendingTransactions(
        @NotNull AIToolCallContext context,
        @NotNull Map<String, Object> arguments
    ) throws DBException;

    /**
     * Commits or rolls back what {@link #findPendingTransactions} found, as the user answered.
     *
     * @param answer {@link AIToolConfirmationAnswer#COMMIT} or {@link AIToolConfirmationAnswer#ROLLBACK}
     */
    void resolvePendingTransactions(
        @NotNull AIToolCallContext context,
        @NotNull Map<String, Object> arguments,
        @NotNull AIToolConfirmationAnswer answer
    ) throws DBException;
}
