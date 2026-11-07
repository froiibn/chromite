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

import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPConnectionType;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.qm.QMTestSession;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.runtime.DBServiceUnrecordedSessions;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceIdleTransactions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Map;

/**
 * Regression tests of the error recovery guard ({@link TransactionRecoveryGuard}): the automatic rollback and reconnect
 * stop for open transactions, and a lost isolated session is reopened alone.
 *
 * 설계: 사용자 규칙 "트랜잭션 안전"의 판단만 본다: 열린 트랜잭션(사용자 문장이 있는 수동 커밋)이 있으면 멈추고 알리는지, 실패한 시도의 문장만
 * 연 트랜잭션은 잃을 것이 없다고 보는지(시도 전 스냅숏), 핑의 알림이 연결마다 한 번인지, 주·메타데이터 문맥은 혼자 다시 열지 않는지.
 * 실제 다시 연결({@code InvalidateJob})과 서버 확인은 하지 않는다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "트랜잭션 안전")
 */
public class TransactionRecoveryGuardTest {

    @Test
    public void abortedTransactionRollbackStopsOnlyForTheUsersStatements() throws Exception {
        UIServiceIdleTransactions ui = Mockito.mock(UIServiceIdleTransactions.class);
        try (QMTestSession qm = new QMTestSession();
             MockedStatic<DBWorkbench> ignored = TestConnections.services(Map.of(UIServiceIdleTransactions.class, ui))) {
            DBCExecutionContext open = qm.openContext(true);
            DBCExecutionContext autoCommit = qm.openContext(false);
            DBCExecutionContext metadataOnly = qm.openContext(true);
            qm.execute(open, DBCExecutionPurpose.USER, "INSERT INTO t VALUES (1)", 1, 1000);
            qm.execute(autoCommit, DBCExecutionPurpose.USER, "INSERT INTO t VALUES (1)", 1, 1000);
            qm.execute(metadataOnly, DBCExecutionPurpose.META, "SELECT * FROM pg_catalog.pg_class", -1, 1000);
            DBPDataSource dataSource = TestConnections.container(TestConnections.configuration(DBPConnectionType.DEV),
                open, autoCommit, metadataOnly).getDataSource();

            Assertions.assertFalse(TransactionRecoveryGuard.holdAbortedTransactionRollback(dataSource, autoCommit, null));
            Assertions.assertFalse(TransactionRecoveryGuard.holdAbortedTransactionRollback(dataSource, metadataOnly, null));
            Mockito.verifyNoInteractions(ui);

            Assertions.assertTrue(TransactionRecoveryGuard.holdAbortedTransactionRollback(dataSource, open, null));
            // Without a named context all contexts of the data source count, and only the open one is reported
            Assertions.assertTrue(TransactionRecoveryGuard.holdAbortedTransactionRollback(dataSource, null, null));
            Mockito.verify(ui, Mockito.times(2)).showTransactionAborted(Mockito.eq(open), Mockito.any(QMTransactionState.class));
            Mockito.verify(ui, Mockito.never()).showTransactionAborted(Mockito.eq(autoCommit), Mockito.any());
            Mockito.verify(ui, Mockito.never()).showTransactionAborted(Mockito.eq(metadataOnly), Mockito.any());
        }
    }

    @Test
    public void transactionOpenedOnlyByTheFailedAttemptLosesNothing() throws Exception {
        UIServiceIdleTransactions ui = Mockito.mock(UIServiceIdleTransactions.class);
        try (QMTestSession qm = new QMTestSession();
             MockedStatic<DBWorkbench> ignored = TestConnections.services(Map.of(UIServiceIdleTransactions.class, ui))) {
            DBCExecutionContext context = qm.openContext(true);
            DBPDataSource dataSource = TestConnections.container(TestConnections.configuration(DBPConnectionType.DEV), context).getDataSource();

            // Snapshot before the first statement of the transaction; the failed statement is then recorded in it
            TransactionRecoveryGuard.Attempt first = TransactionRecoveryGuard.Attempt.start(context);
            qm.execute(context, DBCExecutionPurpose.USER, "INSERT INTO t VALUES (1)", 1, 0);
            Assertions.assertFalse(TransactionRecoveryGuard.holdAbortedTransactionRollback(dataSource, context, first));
            Assertions.assertFalse(TransactionRecoveryGuard.holdReconnect(dataSource, new Exception("connection reset"), first, false));

            // A later attempt in the same transaction: the earlier statement would be lost
            TransactionRecoveryGuard.Attempt second = TransactionRecoveryGuard.Attempt.start(context);
            qm.execute(context, DBCExecutionPurpose.USER, "INSERT INTO t VALUES (2)", 1, 0);
            Assertions.assertTrue(TransactionRecoveryGuard.holdAbortedTransactionRollback(dataSource, context, second));
            Mockito.verify(ui).showTransactionAborted(Mockito.eq(context), Mockito.any(QMTransactionState.class));
        }
    }

    @Test
    public void reconnectIsHeldForOpenTransactionsAndAgentSessionsAndThePingReportsOnce() throws Exception {
        UIServiceIdleTransactions ui = Mockito.mock(UIServiceIdleTransactions.class);
        DBServiceUnrecordedSessions agent = Mockito.mock(DBServiceUnrecordedSessions.class);
        try (QMTestSession qm = new QMTestSession();
             MockedStatic<DBWorkbench> ignored = TestConnections.services(Map.of(
                 UIServiceIdleTransactions.class, ui, DBServiceUnrecordedSessions.class, agent))) {
            DBCExecutionContext idle = qm.openContext(true);
            DBPDataSourceContainer container = TestConnections.container(TestConnections.configuration(DBPConnectionType.DEV), idle);
            DBPDataSource dataSource = container.getDataSource();
            Exception error = new Exception("connection reset");

            // Nothing to lose: the recovery may reconnect
            Assertions.assertFalse(TransactionRecoveryGuard.holdReconnect(dataSource, error, null, false));

            // A Chromite Agent session works on the connection
            Mockito.when(agent.isConnectionInUse(container)).thenReturn(true);
            Assertions.assertTrue(TransactionRecoveryGuard.holdReconnect(dataSource, error, null, false));
            Mockito.when(agent.isConnectionInUse(container)).thenReturn(false);

            qm.execute(idle, DBCExecutionPurpose.USER, "UPDATE t SET a = 1", 1, 1000);
            Assertions.assertTrue(TransactionRecoveryGuard.holdReconnect(dataSource, error, null, true));
            Assertions.assertTrue(TransactionRecoveryGuard.holdReconnect(dataSource, error, null, true));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<DBCExecutionContext, QMTransactionState>> held = ArgumentCaptor.forClass(Map.class);
            // Once for the agent session, once for the first ping: the second ping of the same connection is quiet
            Mockito.verify(ui, Mockito.times(2)).showReconnectHeld(Mockito.eq(dataSource), held.capture(), Mockito.anyList(), Mockito.eq(error));
            Assertions.assertTrue(held.getAllValues().get(0).isEmpty());
            Assertions.assertEquals(1, held.getAllValues().get(1).get(idle).getExecuteCount());
        }
    }

    @Test
    public void lostIsolatedSessionIsReopenedAloneAndItsTransactionRecordedAsRolledBack() throws Exception {
        UIServiceIdleTransactions ui = Mockito.mock(UIServiceIdleTransactions.class);
        try (QMTestSession qm = new QMTestSession();
             MockedStatic<DBWorkbench> ignored = TestConnections.services(Map.of(UIServiceIdleTransactions.class, ui))) {
            DBCExecutionContext main = qm.openContext(true);
            DBCExecutionContext isolated = qm.openContext(true);
            TestConnections.container(TestConnections.configuration(DBPConnectionType.DEV), main, isolated);
            DBRProgressMonitor monitor = Mockito.mock(DBRProgressMonitor.class);
            DBSInstance instance = main.getOwnerInstance();
            Mockito.when(instance.getDefaultContext(monitor, false)).thenReturn(main);
            Exception error = new Exception("connection reset");

            // The shared main context is never reopened alone
            Assertions.assertFalse(TransactionRecoveryGuard.reopenLostSession(monitor, main, error, null));

            qm.execute(isolated, DBCExecutionPurpose.USER, "UPDATE t SET a = 1", 1, 1000);
            TransactionRecoveryGuard.Attempt attempt = TransactionRecoveryGuard.Attempt.start(isolated);
            Mockito.when(isolated.isConnected()).thenReturn(false);

            Assertions.assertTrue(TransactionRecoveryGuard.reopenLostSession(monitor, isolated, error, attempt));
            Mockito.verify(isolated).invalidateContext(monitor);
            Mockito.verify(main, Mockito.never()).invalidateContext(Mockito.any());
            Mockito.verify(qm.getHandler()).handleTransactionRollback(isolated, null);
            ArgumentCaptor<QMTransactionState> lost = ArgumentCaptor.forClass(QMTransactionState.class);
            Mockito.verify(ui).showSessionReopened(Mockito.eq(isolated), lost.capture(), Mockito.eq(error));
            Assertions.assertEquals(1, lost.getValue().getExecuteCount());
        }
    }

    @Test
    public void runningOrConnectedSessionIsNeverTakenForLost() throws Exception {
        try (QMTestSession qm = new QMTestSession()) {
            DBCExecutionContext context = qm.openContext(true);
            Mockito.when(context.isConnected()).thenReturn(true);
            qm.begin(context, DBCExecutionPurpose.USER, "SELECT pg_sleep(60)");
            Assertions.assertFalse(TransactionRecoveryGuard.isSessionLost(Mockito.mock(DBRProgressMonitor.class), context));
            Mockito.verify(context, Mockito.never()).checkContextAlive(Mockito.any());

            // No user statement: nothing is recorded as rolled back
            DBCExecutionContext idle = qm.openContext(true);
            TransactionRecoveryGuard.discardLostTransaction(idle);
            Mockito.verify(qm.getHandler(), Mockito.never()).handleTransactionRollback(Mockito.eq(idle), Mockito.any());
        }
    }
}
