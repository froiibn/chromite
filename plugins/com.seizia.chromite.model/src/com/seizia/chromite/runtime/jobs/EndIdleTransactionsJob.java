/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.runtime.jobs;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.ui.UIServiceIdleTransactions;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;

/**
 * Rolls back one idle transaction automatically, for a connection where the user turned that on, then has the UI
 * show a notice that stays until dismissed.
 *
 * 설계: {@link IdleTransactionMonitor}가 자동 롤백을 정하면 이 Job이 한다. 예약과 실행 사이에 사용자가 돌아와 문장을 실행했을 수
 * 있으므로 롤백 직전에 같은 트랜잭션인지, 아직 기간 넘게 유휴인지, 자동 롤백이 아직 켜져 있는지 다시 보고, 롤백 자체는
 * {@link IdleTransactionMonitor#endTransaction}(같은 트랜잭션·실행 중 아님 확인 뒤 트랜잭션 관리자의 rollback)으로 한다.
 * 연결마다 하나만 돈다({@link DataSourceUpdaterJob}).
 *
 * 변경 이력:
 * - 2026-11-07 업스트림의 "모든 유휴 트랜잭션을 확인 서비스로 묻고(서비스가 없으면 묻지 않고) 롤백"을 바꿈: 사용자가 연결에 켠
 *   자동 롤백만, 문맥 하나씩, 롤백 직전 다시 확인, 끝나면 UI 알림. 묻기는 {@link UIServiceIdleTransactions}의 알림이 한다
 *   (docs/chromite/idle-transactions.md)
 */
class EndIdleTransactionsJob extends DataSourceUpdaterJob {
    private static final Log log = Log.getLog(EndIdleTransactionsJob.class);

    private final DBCExecutionContext context;
    private final QMTransactionState transaction;
    private final UIServiceIdleTransactions ui;

    EndIdleTransactionsJob(
        @NotNull DBCExecutionContext context,
        @NotNull QMTransactionState transaction,
        @NotNull UIServiceIdleTransactions ui
    ) {
        super("Roll back idle transaction (" + context.getDataSource().getContainer().getName() + ")");
        setUser(false);
        setSystem(true);
        this.context = context;
        this.transaction = transaction;
        this.ui = ui;
    }

    @Override
    public DBPDataSource getDataSource() {
        return context.getDataSource();
    }

    @Override
    protected IStatus updateDataSource(DBRProgressMonitor monitor) {
        DBPDataSourceContainer container = context.getDataSource().getContainer();
        QMTransactionState current = QMUtils.getTransactionState(context);
        long now = System.currentTimeMillis();
        long periodMillis = IdleTransactionMonitor.getReminderPeriodSeconds(container) * 1000;
        if (!IdleTransactionMonitor.isAutoRollback(container) || !current.isOpen()
            || current.getTransactionStartTime() != transaction.getTransactionStartTime()
            || now - current.getLastExecuteTime() < periodMillis) {
            // The user came back, ended the transaction or changed the setting in the meantime
            return Status.CANCEL_STATUS;
        }
        try {
            if (!IdleTransactionMonitor.endTransaction(monitor, context, current.getTransactionStartTime(), false)) {
                return Status.CANCEL_STATUS;
            }
            log.info("Idle transaction of " + container.getName() + " (" + context.getContextName() + ") rolled back after "
                + (now - current.getLastExecuteTime()) / 1000 + " s idle, " + current.getExecuteCount() + " statement(s)");
            ui.showIdleTransactionRolledBack(context, current, now, null);
        } catch (Exception e) {
            log.error("Error rolling back the idle transaction of " + container.getName(), e);
            IdleTransactionMonitor.rollbackFailed(context, current.getTransactionStartTime());
            ui.showIdleTransactionRolledBack(context, current, now, e);
        }
        return Status.OK_STATUS;
    }

}
