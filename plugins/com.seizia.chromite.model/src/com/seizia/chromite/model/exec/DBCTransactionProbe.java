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
package com.seizia.chromite.model.exec;

import org.jkiss.code.Nullable;

/**
 * An execution context that can ask its database what the session's transaction holds.
 *
 * 설계(docs/chromite/idle-transactions.md "트랜잭션 상태"): 쿼리 관리자의 추적기가 사용자 작업이 끝난 뒤(세션을 닫을 때, 커밋·롤백
 * 뒤) 실행 작업 스레드에서 부른다. EDT에서 부르지 않고 주기적으로 묻지도 않는다. 모델 계층은 JDBC를 모르므로 이 인터페이스만 보고,
 * JDBC 문맥이 방언의 질의({@code JDBCTransactionProbe})로 구현한다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/idle-transactions.md "트랜잭션 상태")
 */
public interface DBCTransactionProbe {

    /**
     * Asks the database about the session's transaction. Runs a server round trip; never call in the UI thread.
     * Does not throw: a failed question is {@link DBCServerTransaction#FAILED}.
     *
     * @return the answer, or null when this database has no probe (statement classification decides instead)
     */
    @Nullable
    DBCServerTransaction probeTransaction();
}
