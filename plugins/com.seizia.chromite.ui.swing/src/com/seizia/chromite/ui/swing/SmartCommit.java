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
package com.seizia.chromite.ui.swing;

import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCSavepoint;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.qm.DefaultExecutionHandler;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Smart commit of the query windows: on an auto-commit connection, a statement that changes data switches its session to
 * manual commit, so that the change can still be rolled back; after the commit or rollback the session goes back to
 * auto-commit (when the connection type, or the connection's own setting, says so).
 *
 * 설계: 실행 엔진은 처리기의 {@code isSmartAutoCommit()}이 참일 때만 문장마다 {@code DBExecUtils.checkSmartAutoCommit}으로 수동 커밋으로 바꾼다.
 * SWT 편집기는 이것을 켰고 커밋·롤백 뒤 {@code TransactionMonitorToolbar}의 쿼리 관리자 처리기가 {@code DBExecUtils.recoverSmartCommit}으로
 * 자동 커밋으로 되돌렸다. Swing 쿼리 창은 켜지 않아 설정이 저장만 되고 효과가 없었다(docs/chromite/swt-parity/B-editors.md S16).
 * <ul>
 *     <li>켜기: 연결의 설정 저장소에 {@code transaction.smart.commit}이 있으면 그 값, 없으면 연결 유형의 값(SWT {@code SQLEditor.isSmartAutoCommit}과 같음).</li>
 *     <li>되돌릴 세션: 스마트 커밋이 바꾼 세션만 기억한다(쿼리 창 문장 전에 자동 커밋이었고 끝난 뒤 수동 커밋). 업스트림
 *     {@code recoverSmartCommit}은 켜져 있으면 모든 커밋·롤백 뒤 자동 커밋으로 바꿔서, 처음부터 수동 커밋인 연결도 커밋 한 번에 자동 커밋이
 *     되었다. 결과 표 저장이 잠시 자동 커밋을 끄는 것도 스마트 커밋으로 오인하지 않는다.</li>
 *     <li>되돌리기(트랜잭션 안전 규칙): 커밋·롤백 기록을 받으면 Job에서, 그 세션에 열린 트랜잭션이 없고 실행 중이 아닐 때만 자동 커밋으로 바꾼다.
 *     그 사이 새 문장이 트랜잭션을 열었으면 바꾸지 않고 다음 커밋·롤백을 기다린다(자동 커밋으로 바꾸면 JDBC가 그 트랜잭션을 묻지 않고 커밋한다).</li>
 *     <li>수동 커밋으로 바꾸는 것은 잃을 것이 없다(자동 커밋 세션에는 열린 트랜잭션이 없다). 바뀐 뒤에는 상태 줄·유휴 알림·끝내기 질문이
 *     다른 수동 커밋 세션과 똑같이 그 트랜잭션을 본다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/connection-lifecycle.md "스마트 커밋")
 */
final class SmartCommit {

    private static final Log log = Log.getLog(SmartCommit.class);

    // Sessions the smart commit switched to manual commit; weak, so that closed sessions drop out
    private static final Set<DBCExecutionContext> switched = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final AtomicBoolean handlerRegistered = new AtomicBoolean();

    private SmartCommit() {
    }

    /**
     * Whether the query windows of the connection use smart commit: the connection's own setting, else its type's.
     */
    static boolean isEnabled(@Nullable DBPDataSourceContainer container) {
        if (container == null) {
            return false;
        }
        DBPPreferenceStore store = container.getPreferenceStore();
        return store.contains(ModelPreferences.TRANSACTIONS_SMART_COMMIT)
            ? store.getBoolean(ModelPreferences.TRANSACTIONS_SMART_COMMIT)
            : container.getConnectionConfiguration().getConnectionType().isSmartCommit();
    }

    private static boolean isRecoverEnabled(@NotNull DBPDataSourceContainer container) {
        DBPPreferenceStore store = container.getPreferenceStore();
        return store.contains(ModelPreferences.TRANSACTIONS_SMART_COMMIT_RECOVER)
            ? store.getBoolean(ModelPreferences.TRANSACTIONS_SMART_COMMIT_RECOVER)
            : container.getConnectionConfiguration().getConnectionType().isSmartCommitRecover();
    }

    /**
     * Whether the session is in auto-commit mode as the query manager saw it (no driver call).
     */
    static boolean isAutoCommit(@NotNull DBCExecutionContext context) {
        return !QMUtils.getTransactionState(context).isTransactionMode();
    }

    /**
     * After a query window statement with smart commit on: remembers the session when the statement switched it from
     * auto-commit to manual commit.
     *
     * @param wasAutoCommit the session's mode before the statement ({@link #isAutoCommit})
     */
    static void afterStatement(@NotNull DBCExecutionContext context, boolean wasAutoCommit) {
        if (!wasAutoCommit || isAutoCommit(context)) {
            return;
        }
        if (handlerRegistered.compareAndSet(false, true)) {
            QMUtils.registerHandler(new RecoverHandler());
        }
        if (switched.add(context)) {
            log.debug("Smart commit switched " + context.getDataSource().getContainer().getName() + "::" + context.getContextName()
                + " to manual commit"); //$NON-NLS-1$
        }
    }

    private static void transactionEnded(@NotNull DBCExecutionContext context) {
        if (!switched.contains(context)) {
            return;
        }
        DBPDataSourceContainer container = context.getDataSource().getContainer();
        if (!isRecoverEnabled(container)) {
            // Stays in manual commit until it is reconnected
            switched.remove(context);
            return;
        }
        // Not in the query manager's callback: switching the mode is a driver call, and the callback must stay fast
        new AbstractJob("Return to auto-commit after smart commit") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                if (!context.isConnected()) {
                    switched.remove(context);
                    return Status.OK_STATUS;
                }
                QMTransactionState state = QMUtils.getTransactionState(context);
                if (state.isOpen() || state.isExecuting() || QMUtils.isTransactionActive(context)
                    || Job.getJobManager().find(context).length > 0) {
                    // A new transaction started meanwhile: auto-commit would commit it without asking; wait for its end
                    return Status.OK_STATUS;
                }
                DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
                try {
                    if (txnManager != null && !txnManager.isAutoCommit()) {
                        txnManager.setAutoCommit(monitor, true);
                    }
                    switched.remove(context);
                } catch (DBCException e) {
                    log.debug("Cannot return to auto-commit after smart commit: " + e.getMessage()); //$NON-NLS-1$
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * Commits and rollbacks of the remembered sessions, from any path (Query menu, status line, COMMIT typed in the
     * editor, the idle reminder, the exit question).
     */
    private static final class RecoverHandler extends DefaultExecutionHandler {
        @NotNull
        @Override
        public String getHandlerName() {
            return "Chromite smart commit"; //$NON-NLS-1$
        }

        @Override
        public void handleTransactionCommit(@NotNull DBCExecutionContext context) {
            transactionEnded(context);
        }

        @Override
        public void handleTransactionRollback(@NotNull DBCExecutionContext context, @Nullable DBCSavepoint savepoint) {
            // A rollback to a savepoint keeps the transaction open
            if (savepoint == null) {
                transactionEnded(context);
            }
        }

        @Override
        public void handleTransactionAutocommit(@NotNull DBCExecutionContext context, boolean autoCommit) {
            if (autoCommit) {
                // Back in auto-commit by any path (this class, the connection's Auto-commit setting)
                switched.remove(context);
            }
        }

        @Override
        public void handleContextClose(@NotNull DBCExecutionContext context) {
            switched.remove(context);
        }
    }
}
