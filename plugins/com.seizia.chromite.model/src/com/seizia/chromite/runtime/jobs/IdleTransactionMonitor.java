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
package com.seizia.chromite.runtime.jobs;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPConnectionType;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCServerTransaction;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.exec.DBCTransactionProbe;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.LoggingProgressMonitor;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceIdleTransactions;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches the open transactions of the connected data sources and acts when one stays idle longer than the
 * connection's period: it reminds (default) or, only when the user turned it on for the connection, rolls back and
 * then shows a notice. It never commits.
 *
 * 설계: 사용자가 끝났다고 생각하고 둔 트랜잭션이 잠금을 쥐고 다른 세션을 막은 사고(2026-11-07 사용자 요청)를 막는다.
 * <ul>
 *     <li>유휴 = 그 트랜잭션의 실행 문맥에서 마지막 사용자 문장이 끝난 뒤 지난 시간({@link QMTransactionState#getLastExecuteTime()}).
 *     전역 마우스·키보드 활동은 쓰지 않는다. 사고는 다른 창에서 일하는 동안 한 트랜잭션이 놓여 있던 것이었다.
 *     열린 트랜잭션은 DB가 무언가를 쥐었다고 답한 것과 확인할 수 없는 것이다({@link QMTransactionState#isOpen()}). 메모리 안 기록만
 *     읽으므로 드라이버를 부르지 않는다(DB에는 실행 작업이 끝날 때 미리 묻는다).</li>
 *     <li>{@link DataSourceMonitorJob}이 3초마다 연결된 연결마다 {@link #check}를 부르고, 한 바퀴가 끝나면 {@link #sweep}이
 *     이번에 보지 못한 문맥(연결 끊김, 문맥 닫힘)의 상태와 알림을 지운다.</li>
 *     <li>설정({@link #getReminderPeriodSeconds}, {@link #isAutoRollback}): 알림 켜기와 기간은 연결 설정이 비어 있으면 연결 종류의
 *     값({@link DBPConnectionType#isAutoCloseTransactions()}, {@link DBPConnectionType#getCloseIdleTransactionPeriod()}: 개발 30분,
 *     테스트 15분, 운영 10분)을 따른다. 연결 종류의 옛 "자동 닫기" 값은 알림으로만 읽는다. 자동 롤백은 연결에 사용자가 켠 별도 설정
 *     ({@link DBPConnectionConfiguration#isIdleTransactionRollback()})만 보고 기본은 꺼짐이다(사용자 결정 "설정 시 자동 롤백").</li>
 *     <li>자동 롤백이 꺼져 있으면 UI 서비스에 알림을 한 번 요청한다. 답(Keep open, 닫기)이 오기 전에는 다시 요청하지 않고,
 *     Keep open은 그때부터 한 기간 뒤로 미룬다. 그 문맥에서 문장을 다시 실행하면 유휴 시간과 미루기가 처음부터 다시 시작한다.
 *     답하지 않으면 아무것도 하지 않는다.</li>
 *     <li>자동 롤백이 켜져 있으면 {@link EndIdleTransactionsJob}이 롤백 직전에 같은 트랜잭션인지, 아직 유휴인지 다시 보고 롤백한 뒤
 *     계속 떠 있는 알림을 요청한다. 실패하면 알리고 한 기간 뒤에 다시 한다.</li>
 *     <li>실행 중에는 아무것도 하지 않는다: 그 문맥에 끝나지 않은 문장({@link QMTransactionState#isExecuting()})이나 그 문맥의
 *     {@link DataSourceJob}(SQL 편집기 실행 등)이 있으면 유휴가 아니다.</li>
 *     <li>UI 서비스가 없으면(헤드리스) 알리지도 롤백하지도 않는다. 보이는 알림 없는 롤백은 허용하지 않기 때문이다.</li>
 *     <li>커밋·롤백({@link #endTransaction})은 알림과 쿼리 창의 열린 트랜잭션 표시가 같이 쓰고, 보여 준 트랜잭션과 같은지
 *     (첫 문장 시각) 확인한 뒤에만 한다. 그 사이 다른 트랜잭션이 시작되었으면 그 트랜잭션을 건드리지 않는다.</li>
 *     <li>업스트림의 유휴 트랜잭션 롤백(사용자 활동 시각 기준, 확인 서비스가 없으면 묻지 않고 롤백)은 이 클래스로 바꾸었다.
 *     유휴 연결 닫기({@link DataSourceMonitorJob})는 열린 트랜잭션이 있는 연결을 닫지 않고 그 트랜잭션을 이 클래스에 맡긴다.</li>
 *     <li>조회만 한 트랜잭션(2026-11-09, 앱 변경 #31, 사용자 결정 "한 번에 끝내기 + 자동 설정"): PostgreSQL·MySQL·MariaDB는 일반 SELECT도
 *     테이블 잠금을 쥐어 열린 트랜잭션으로 보이지만({@link QMTransactionState#isReadOnly()}) 끝내도 잃는 것이 없다. 그래서
 *     (A) 사용자가 상태 줄에서 고르면 DB에 다시 묻고 아직 조회만 했을 때만 롤백으로 끝낸다({@link #endReadOnlyTransaction}). 그 사이 변경이
 *     생겼으면 끝내지 않고 알려 보통의 커밋/롤백 질문으로 넘긴다. (B) 연결 종류에서 켠 경우({@link DBPConnectionType#isAutoEndReadOnlyTransactions()},
 *     기본 꺼짐)에만 사용자 작업이 끝난 직후 끝내고 상태 줄에 알린다({@link #endReadOnlyAfterWork}). 롤백인 이유: 둘 다 쥔 것을 풀지만 롤백은
 *     혹시 놓친 변경이 있어도 남기지 않는다. 데이터 변경, 명시적 BEGIN, 잠그는 읽기, DB에 확인할 수 없는 상태에서는 절대 하지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/idle-transactions.md)
 * - 2026-11-07 유휴 연결 닫기와의 관계를 설명에 고침(이제 닫기가 동작함) (docs/chromite/idle-transactions.md "유휴 연결 닫기")
 * - 2026-11-08 열린 트랜잭션을 DB의 답으로 정함(쥔 것 없는 SELECT는 알리지 않음, 자동 커밋의 명시적 트랜잭션은 알림).
 *   커밋·롤백 공용 경로({@link #commitOrRollback}): 자동 커밋 모드는 COMMIT/ROLLBACK 문장, 중첩 BEGIN TRAN은 끝까지 커밋
 *   (docs/chromite/idle-transactions.md "트랜잭션 상태")
 * - 2026-11-09 조회만 한 트랜잭션 끝내기: 한 번에 끝내기({@link #endReadOnlyTransaction})와 연결 종류 설정의 자동으로 끝내기
 *   ({@link #endReadOnlyAfterWork}) (docs/chromite/idle-transactions.md "조회만 한 트랜잭션 끝내기")
 */
public final class IdleTransactionMonitor {

    private static final Log log = Log.getLog(IdleTransactionMonitor.class);

    // Commits of one Commit at most (nested BEGIN TRANs of SQL Server)
    private static final int MAX_NESTED_COMMITS = 32;

    /**
     * What {@link #endReadOnlyTransaction} did.
     */
    public enum ReadOnlyEnd {
        /** Ended with a rollback: it only read. */
        ENDED,
        /** Not ended: the database now reports more than reads (changes, row locks), or cannot tell. Ask the usual question. */
        NOT_READ_ONLY,
        /** Not ended: the transaction already ended or is another one, or a statement runs in it. */
        NOT_ENDED
    }

    /**
     * What the monitor knows about the open transaction of one context.
     */
    private static final class ContextState {
        final DBPDataSourceContainer container;
        // Identity of the watched transaction
        final long transactionStart;
        long lastExecuteTime;
        boolean reminderShown;
        boolean rollbackScheduled;
        // No reminder or automatic rollback before this time (Keep open, failed rollback)
        long snoozedUntil;
        long seenTick;

        ContextState(@NotNull DBPDataSourceContainer container, @NotNull QMTransactionState transaction) {
            this.container = container;
            this.transactionStart = transaction.getTransactionStartTime();
            this.lastExecuteTime = transaction.getLastExecuteTime();
        }
    }

    // Guarded by itself
    private static final Map<DBCExecutionContext, ContextState> states = new IdentityHashMap<>();
    private static long tick;

    private IdleTransactionMonitor() {
    }

    /**
     * Seconds an open transaction of the connection may stay idle before Chromite reminds (or rolls back), or 0 when
     * the reminder is off. The connection's own setting, else its connection type's.
     */
    public static long getReminderPeriodSeconds(@NotNull DBPDataSourceContainer container) {
        DBPConnectionConfiguration configuration = container.getConnectionConfiguration();
        DBPConnectionType type = configuration.getConnectionType();
        Boolean reminder = configuration.getIdleTransactionReminder();
        if (!(reminder != null ? reminder : type.isAutoCloseTransactions())) {
            return 0;
        }
        int period = configuration.getIdleTransactionPeriod() > 0 ? configuration.getIdleTransactionPeriod() : type.getCloseIdleTransactionPeriod();
        return Math.max(0, period);
    }

    /**
     * The user turned on "roll back automatically instead of asking" for the connection (only with the reminder on).
     */
    public static boolean isAutoRollback(@NotNull DBPDataSourceContainer container) {
        return container.getConnectionConfiguration().isIdleTransactionRollback() && getReminderPeriodSeconds(container) > 0;
    }

    /**
     * The connection's type ends read-only transactions automatically after the user's work (off by default).
     */
    public static boolean isAutoEndReadOnly(@NotNull DBPDataSourceContainer container) {
        return container.getConnectionConfiguration().getConnectionType().isAutoEndReadOnlyTransactions();
    }

    /**
     * Starts a pass over all connections (one {@link DataSourceMonitorJob} run).
     */
    static void beginPass() {
        synchronized (states) {
            tick++;
        }
    }

    /**
     * Checks the transactions of a connected data source; called once per pass.
     */
    static void check(@NotNull DBPDataSourceContainer container) {
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null) {
            return;
        }
        long periodMillis = getReminderPeriodSeconds(container) * 1000;
        boolean autoRollback = isAutoRollback(container);
        UIServiceIdleTransactions ui = DBWorkbench.findService(UIServiceIdleTransactions.class);
        List<DBCExecutionContext> toClose = new ArrayList<>();
        List<DBCExecutionContext> toRemind = new ArrayList<>();
        List<DBCExecutionContext> toRollBack = new ArrayList<>();
        Map<DBCExecutionContext, QMTransactionState> transactions = new IdentityHashMap<>();
        for (DBSInstance instance : dataSource.getAvailableInstances()) {
            for (DBCExecutionContext context : instance.getAllContexts()) {
                QMTransactionState transaction = QMUtils.getTransactionState(context);
                transactions.put(context, transaction);
                long now = System.currentTimeMillis();
                synchronized (states) {
                    ContextState state = states.get(context);
                    if (!transaction.isOpen()) {
                        if (state != null) {
                            states.remove(context);
                            if (state.reminderShown) {
                                toClose.add(context);
                            }
                        }
                        continue;
                    }
                    if (state == null || state.transactionStart != transaction.getTransactionStartTime()) {
                        if (state != null && state.reminderShown) {
                            toClose.add(context);
                        }
                        state = new ContextState(container, transaction);
                        states.put(context, state);
                    }
                    state.seenTick = tick;
                    if (state.lastExecuteTime != transaction.getLastExecuteTime()) {
                        // Used again: the idle time and Keep open start over
                        state.lastExecuteTime = transaction.getLastExecuteTime();
                        state.snoozedUntil = 0;
                        if (state.reminderShown) {
                            state.reminderShown = false;
                            toClose.add(context);
                        }
                    }
                    if (periodMillis <= 0 || ui == null || now < state.snoozedUntil || now - transaction.getLastExecuteTime() < periodMillis
                        || transaction.isExecuting() || isJobRunning(context)) {
                        continue;
                    }
                    if (autoRollback) {
                        if (!state.rollbackScheduled) {
                            state.rollbackScheduled = true;
                            toRollBack.add(context);
                        }
                    } else if (!state.reminderShown) {
                        state.reminderShown = true;
                        toRemind.add(context);
                    }
                }
            }
        }
        // The UI and the jobs are called outside of the lock
        if (ui != null) {
            toClose.forEach(ui::closeIdleTransactionReminder);
            for (DBCExecutionContext context : toRemind) {
                log.debug("Idle transaction in " + container.getName() + " (" + context.getContextName() + "): reminder");
                ui.showIdleTransactionReminder(context, transactions.get(context));
            }
            for (DBCExecutionContext context : toRollBack) {
                EndIdleTransactionsJob job = new EndIdleTransactionsJob(context, transactions.get(context), ui);
                job.addJobChangeListener(new JobChangeAdapter() {
                    @Override
                    public void done(IJobChangeEvent event) {
                        synchronized (states) {
                            ContextState state = states.get(context);
                            if (state != null) {
                                state.rollbackScheduled = false;
                            }
                        }
                    }
                });
                job.schedule();
            }
        }
    }

    /**
     * Ends a pass: forgets the contexts not seen in it (disconnected, closed) and closes their reminders.
     */
    static void sweep() {
        List<DBCExecutionContext> gone = new ArrayList<>();
        synchronized (states) {
            states.entrySet().removeIf(entry -> {
                if (entry.getValue().seenTick == tick) {
                    return false;
                }
                if (entry.getValue().reminderShown) {
                    gone.add(entry.getKey());
                }
                return true;
            });
        }
        if (!gone.isEmpty()) {
            UIServiceIdleTransactions ui = DBWorkbench.findService(UIServiceIdleTransactions.class);
            if (ui != null) {
                gone.forEach(ui::closeIdleTransactionReminder);
            }
        }
    }

    /**
     * Keep open (or the reminder closed without an answer): no reminder before another full idle period.
     *
     * @param transactionStart the reminded transaction ({@link QMTransactionState#getTransactionStartTime()})
     */
    public static void keepOpen(@NotNull DBCExecutionContext context, long transactionStart) {
        snooze(context, transactionStart);
    }

    /**
     * The automatic rollback failed: try again after another full idle period.
     */
    static void rollbackFailed(@NotNull DBCExecutionContext context, long transactionStart) {
        snooze(context, transactionStart);
    }

    private static void snooze(@NotNull DBCExecutionContext context, long transactionStart) {
        synchronized (states) {
            ContextState state = states.get(context);
            if (state != null && state.transactionStart == transactionStart) {
                state.reminderShown = false;
                state.snoozedUntil = System.currentTimeMillis() + getReminderPeriodSeconds(state.container) * 1000;
            }
        }
    }

    /**
     * Commits or rolls back the context's transaction, the explicit answer of the user (reminder, query window status
     * line) or the automatic rollback, with the transaction manager as everywhere else. Nothing is done when the
     * transaction is no longer the one shown or a statement is running in the context.
     *
     * @param transactionStart the transaction shown to the user ({@link QMTransactionState#getTransactionStartTime()})
     * @return false when nothing was done (the transaction already ended or changed, or a statement is running)
     */
    public static boolean endTransaction(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        long transactionStart,
        boolean commit
    ) throws DBException {
        QMTransactionState transaction = QMUtils.getTransactionState(context);
        if (!transaction.isOpen() || transaction.getTransactionStartTime() != transactionStart || transaction.isExecuting() || isJobRunning(context)) {
            return false;
        }
        if (DBUtils.getTransactionManager(context) == null) {
            return false;
        }
        try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, commit ? "Commit transaction" : "Roll back transaction")) {
            commitOrRollback(session, commit);
        }
        return true;
    }

    /**
     * Ends the context's read-only transaction ({@link QMTransactionState#isReadOnly()}) with a rollback, as the user
     * chose ("End transaction (release locks)"). Asks the database again first: when something more than reads appeared
     * meanwhile, nothing is done and the caller asks the usual Commit / Roll back question.
     *
     * @param transactionStart the transaction shown to the user ({@link QMTransactionState#getTransactionStartTime()})
     */
    @NotNull
    public static ReadOnlyEnd endReadOnlyTransaction(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        long transactionStart
    ) throws DBException {
        if (!isIdleTransaction(QMUtils.getTransactionState(context), context, transactionStart, false)) {
            return ReadOnlyEnd.NOT_ENDED;
        }
        QMTransactionState transaction = QMUtils.refreshTransactionState(context);
        if (!isIdleTransaction(transaction, context, transactionStart, false)) {
            return ReadOnlyEnd.NOT_ENDED;
        }
        if (!transaction.isReadOnly()) {
            return ReadOnlyEnd.NOT_READ_ONLY;
        }
        try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "End read-only transaction")) {
            commitOrRollback(session, false);
        }
        return ReadOnlyEnd.ENDED;
    }

    /**
     * Ends the context's transaction with a rollback right after the user's work when the connection type says so
     * ({@link #isAutoEndReadOnly}) and the database's fresh answer and the statements say it only read. Called by the
     * query manager's transaction tracker on the thread of the work that just ended (the job of the SQL editor run).
     * Never throws.
     *
     * @return true when the transaction was ended
     */
    public static boolean endReadOnlyAfterWork(@NotNull DBCExecutionContext context) {
        DBPDataSourceContainer container = context.getDataSource().getContainer();
        if (!isAutoEndReadOnly(container) || DBWorkbench.findService(UIServiceIdleTransactions.class) == null) {
            // Without a UI the end would not be visible
            return false;
        }
        QMTransactionState transaction = QMUtils.getTransactionState(context);
        if (!transaction.isReadOnly() || !isIdleTransaction(transaction, context, transaction.getTransactionStartTime(), true)) {
            return false;
        }
        try (DBCSession session = context.openSession(new LoggingProgressMonitor(log), DBCExecutionPurpose.UTIL, "End read-only transaction")) {
            commitOrRollback(session, false);
        } catch (Exception e) {
            log.warn("Cannot end the read-only transaction of " + container.getName() + " (" + context.getContextName() + ")", e);
            return false;
        }
        log.info("Ended the read-only transaction of " + container.getName() + " (" + context.getContextName() + ") after the statement"
            + " (connection type setting), holds were " + transaction.getHolds());
        return true;
    }

    /**
     * The transaction is open, the one given, and nothing runs in its context.
     *
     * @param inOwnJob called from the job whose work just ended in the context: that job does not count as running
     */
    private static boolean isIdleTransaction(
        @NotNull QMTransactionState transaction,
        @NotNull DBCExecutionContext context,
        long transactionStart,
        boolean inOwnJob
    ) {
        if (!transaction.isOpen() || transaction.getTransactionStartTime() != transactionStart || transaction.isExecuting()) {
            return false;
        }
        Job current = inOwnJob ? Job.getJobManager().currentJob() : null;
        for (Job job : Job.getJobManager().find(context)) {
            if (job != current) {
                return false;
            }
        }
        return DBUtils.getTransactionManager(context) != null;
    }

    /**
     * Ends the transaction of the session's context as the user chose: the transaction manager in manual commit mode,
     * the COMMIT or ROLLBACK statement in auto-commit mode (a transaction begun with BEGIN TRAN, BEGIN or START
     * TRANSACTION; the drivers refuse {@code commit()} there). A commit is repeated while the database still has the
     * transaction: in SQL Server a COMMIT only ends the innermost of nested BEGIN TRANs, and closing the session later
     * would roll the rest back although the user chose Commit.
     */
    public static void commitOrRollback(@NotNull DBCSession session, boolean commit) throws DBException {
        DBCExecutionContext context = session.getExecutionContext();
        DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
        if (txnManager == null) {
            return;
        }
        for (int i = 0; i < MAX_NESTED_COMMITS; i++) {
            if (txnManager.isAutoCommit()) {
                try (DBCStatement statement = DBUtils.createStatement(session, commit ? "COMMIT" : "ROLLBACK", false)) { //$NON-NLS-1$ //$NON-NLS-2$
                    statement.executeStatement();
                }
                if (commit) {
                    QMUtils.getDefaultHandler().handleTransactionCommit(context);
                } else {
                    QMUtils.getDefaultHandler().handleTransactionRollback(context, null);
                }
            } else if (commit) {
                txnManager.commit(session);
            } else {
                txnManager.rollback(session, null);
            }
            if (!commit || !(context instanceof DBCTransactionProbe probe)) {
                return;
            }
            DBCServerTransaction answer = probe.probeTransaction();
            if (answer == null || answer.kind() == DBCServerTransaction.Kind.NONE || answer.kind() == DBCServerTransaction.Kind.FAILED) {
                return;
            }
        }
    }

    /**
     * A {@link DataSourceJob} (SQL editor execution, data editing) works in the context: it may run statements of the
     * transaction even between two of them.
     */
    private static boolean isJobRunning(@NotNull DBCExecutionContext context) {
        return Job.getJobManager().find(context).length > 0;
    }
}
