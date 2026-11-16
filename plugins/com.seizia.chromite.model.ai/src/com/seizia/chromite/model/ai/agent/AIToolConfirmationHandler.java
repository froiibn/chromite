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
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

/**
 * Asks the user whether a CONFIRM tool call may run. Implemented by the UI (chat confirmation dialog, phase 2) and registered
 * with {@link AIAgentServices#setConfirmationHandler}.
 *
 * 설계: 모델 계층은 화면에 의존할 수 없으므로 인터페이스만 둔다. 채팅과 MCP 호출이 같은 처리기를 쓴다(MCP 기본값 "실행 중인 앱의 대화상자").
 * 처리기가 등록되지 않았으면 실행기가 거부한다. 열린 트랜잭션이 걸린 요청은 Commit / Roll back / Cancel을 묻는다("트랜잭션 안전").
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "확인")
 */
public interface AIToolConfirmationHandler {

    /**
     * Called on a worker thread; blocks until the user answers. Must return {@link AIToolConfirmationAnswer#DENY} when the
     * monitor is canceled.
     * <p>
     * When {@link AIToolConfirmationRequest#pendingTransactions()} is not empty, offer Commit / Roll back / Cancel
     * (Commit only if every pending transaction {@link AIPendingTransaction#canCommit()}); otherwise Allow / Deny.
     */
    @NotNull
    AIToolConfirmationAnswer confirm(@NotNull DBRProgressMonitor monitor, @NotNull AIToolConfirmationRequest request);
}
