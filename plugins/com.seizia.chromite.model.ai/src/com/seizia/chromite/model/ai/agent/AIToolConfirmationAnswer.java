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

/**
 * The user's answer to a confirmation request.
 *
 * 설계: 보통 확인은 {@link #ALLOW}/{@link #DENY}다. 커밋되지 않은 트랜잭션이 걸린 요청
 * ({@link AIToolConfirmationRequest#pendingTransactions()}가 비어 있지 않음)은 사용자 규칙 "트랜잭션 안전"(2026-11-07)에 따라
 * Commit / Roll back / Cancel 셋 중 하나를 받는다. 그때 {@link #ALLOW}는 취소로 본다(조용히 커밋·롤백하지 않기 위해).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "트랜잭션 안전")
 */
public enum AIToolConfirmationAnswer {
    /** Run the tool (ordinary confirmation). */
    ALLOW,
    /** Do not run the tool (also "Cancel" of a transaction question). */
    DENY,
    /** Commit the pending transaction(s), then run the tool. */
    COMMIT,
    /** Roll back the pending transaction(s), then run the tool. */
    ROLLBACK
}
