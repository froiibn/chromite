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

import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.runtime.jobs.IdleTransactionMonitor;
import com.seizia.chromite.runtime.jobs.TransactionRecoveryGuard;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Shows the reminder and the notice of {@link IdleTransactionMonitor}, and the notices of
 * {@link TransactionRecoveryGuard} (the error recovery stopped instead of ending an open transaction). All methods are
 * called from background threads and must not block.
 *
 * 설계: 감지와 시간 재기는 모델({@link IdleTransactionMonitor})이 하고, 사용자에게 보이는 것은 UI 번들이 이 서비스로 한다
 * (AGENTS-Architecture.md 모델/UI 분리). 서비스가 없으면(헤드리스) 모니터는 알리지도 롤백하지도 않는다: 자동 롤백 뒤에는
 * 반드시 보이는 알림이 따라야 하기 때문이다(사용자 결정 2026-11-07). 오류 복구의 알림도 같은 "열린 트랜잭션에 대한 알림"이라
 * 새 서비스를 만들지 않고 이 서비스에 두었다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/idle-transactions.md)
 * - 2026-11-07 오류 복구가 멈췄다는 알림 두 가지({@link #showTransactionAborted}, {@link #showReconnectHeld})
 *   (docs/chromite/transactions.md "오류 복구와 열린 트랜잭션")
 * - 2026-11-08 끊긴 격리 세션만 다시 열었다는 알림({@link #showSessionReopened}) (docs/chromite/transactions.md "끊긴 세션의 쿼리 창")
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

    /**
     * Shows a notice that stays until answered or dismissed: the server aborted the context's transaction after an
     * error, and the error recovery did not roll it back because it has the user's statements. Offers Roll back of
     * this transaction ({@link IdleTransactionMonitor#endTransaction}). One notice per context at a time.
     */
    void showTransactionAborted(@NotNull DBCExecutionContext context, @NotNull QMTransactionState transaction);

    /**
     * Shows a notice that stays until answered or dismissed: the connection failed, and the error recovery did not
     * reconnect because reconnecting ends open transactions. Offers Reconnect ({@link TransactionRecoveryGuard#reconnect}).
     * One notice per connection at a time.
     *
     * @param transactions  the sessions with open transactions and what they hold
     * @param agentSessions connections where Chromite Agent sessions run a statement or left a transaction open
     * @param error         the connection error
     */
    void showReconnectHeld(
        @NotNull DBPDataSource dataSource,
        @NotNull Map<DBCExecutionContext, QMTransactionState> transactions,
        @NotNull List<DBPDataSourceContainer> agentSessions,
        @NotNull Throwable error);

    /**
     * Shows a notice: the context's own session was lost and the error recovery reopened that context alone
     * ({@link TransactionRecoveryGuard#reopenLostSession}); the other sessions were not touched. With a lost transaction
     * the notice stays until dismissed, as its statements are gone.
     *
     * @param lostTransaction the uncommitted transaction the lost session had, or null when it had none
     * @param error           the connection error
     */
    void showSessionReopened(
        @NotNull DBCExecutionContext context,
        @Nullable QMTransactionState lostTransaction,
        @NotNull Throwable error);
}
