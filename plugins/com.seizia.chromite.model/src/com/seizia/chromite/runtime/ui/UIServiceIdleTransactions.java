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
package com.seizia.chromite.runtime.ui;

import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.runtime.jobs.IdleTransactionMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * Shows the reminder and the notice of {@link IdleTransactionMonitor}. All methods are called from the monitor's
 * background thread and must not block.
 *
 * 설계: 감지와 시간 재기는 모델({@link IdleTransactionMonitor})이 하고, 사용자에게 보이는 것은 UI 번들이 이 서비스로 한다
 * (AGENTS-Architecture.md 모델/UI 분리). 서비스가 없으면(헤드리스) 모니터는 알리지도 롤백하지도 않는다: 자동 롤백 뒤에는
 * 반드시 보이는 알림이 따라야 하기 때문이다(사용자 결정 2026-11-07).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/idle-transactions.md)
 */
public interface UIServiceIdleTransactions {

    /**
     * Shows a non-blocking reminder that the context's transaction is idle, offering Commit / Roll back / Keep open.
     * Nothing may be chosen automatically. The answers go to {@link IdleTransactionMonitor#endTransaction} and
     * {@link IdleTransactionMonitor#keepOpen}; closing the reminder without an answer counts as Keep open.
     */
    void showIdleTransactionReminder(@NotNull DBCExecutionContext context, @NotNull QMTransactionState transaction);

    /**
     * Closes the context's reminder, if shown: its transaction ended or the context was used again.
     */
    void closeIdleTransactionReminder(@NotNull DBCExecutionContext context);

    /**
     * Shows a notice that stays until dismissed: the idle transaction was rolled back automatically, or the automatic
     * rollback failed.
     *
     * @param rollbackTime when the rollback was done (the idle time is counted up to it)
     * @param error        why the rollback failed, or null when it was rolled back
     */
    void showIdleTransactionRolledBack(
        @NotNull DBCExecutionContext context,
        @NotNull QMTransactionState transaction,
        long rollbackTime,
        @Nullable Throwable error);
}
