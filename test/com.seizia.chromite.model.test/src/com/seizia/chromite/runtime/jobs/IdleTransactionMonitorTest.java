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

import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPConnectionType;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.qm.QMTestSession;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceIdleTransactions;
import org.jkiss.code.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Regression tests of the idle transaction reminder ({@link IdleTransactionMonitor}).
 *
 * 설계: 유휴 시간은 마지막 사용자 문장이 끝난 때부터 재고(전역 마우스·키보드 활동이 아님), 기간과 켜기는 연결 설정이 비어 있으면 연결
 * 종류를 따른다. 감시는 알림을 한 번만 요청하고, 다시 쓰면 알림을 닫고, 실행 중이면 알리지 않고, 사라진 문맥의 알림은 닫는다.
 * 실제 감시 한 바퀴({@code beginPass}, {@code check}, {@code sweep})를 쿼리 관리자 기록({@link QMTestSession})과 UI 서비스 mock으로 돈다.
 * 자동 롤백은 작업(Job)을 띄우므로 여기서는 설정 판단만 본다. 시험 앱의 감시 작업(3초마다)이 시험의 바퀴 사이에 끼면 그 sweep이 시험
 * 문맥을 지우므로, 시험은 감시가 단계마다 잡는 같은 잠금({@code states})을 쥔 채 돈다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "트랜잭션 안전")
 */
public class IdleTransactionMonitorTest {

    private static final long MINUTE = 60_000;

    @BeforeEach
    @AfterEach
    public void forgetWatchedContexts() throws Exception {
        synchronized (states()) {
            states().clear();
        }
    }

    @Test
    public void periodAndSwitchComeFromTheConnectionThenFromItsType() {
        DBPConnectionConfiguration dev = TestConnections.configuration(DBPConnectionType.DEV);
        DBPDataSourceContainer container = TestConnections.container(dev);
        Assertions.assertEquals(1800, IdleTransactionMonitor.getReminderPeriodSeconds(container));
        Assertions.assertEquals(900, IdleTransactionMonitor.getReminderPeriodSeconds(
            TestConnections.container(TestConnections.configuration(DBPConnectionType.TEST))));
        Assertions.assertEquals(600, IdleTransactionMonitor.getReminderPeriodSeconds(
            TestConnections.container(TestConnections.configuration(DBPConnectionType.PROD))));

        dev.setIdleTransactionPeriod(120);
        Assertions.assertEquals(120, IdleTransactionMonitor.getReminderPeriodSeconds(container));
        dev.setIdleTransactionReminder(false);
        Assertions.assertEquals(0, IdleTransactionMonitor.getReminderPeriodSeconds(container));

        // A type whose reminder is off, turned on for the connection
        DBPConnectionConfiguration quiet = TestConnections.configuration(DBPConnectionType.DEV);
        quiet.getConnectionType().setAutoCloseTransactions(false);
        DBPDataSourceContainer quietContainer = TestConnections.container(quiet);
        Assertions.assertEquals(0, IdleTransactionMonitor.getReminderPeriodSeconds(quietContainer));
        quiet.setIdleTransactionReminder(true);
        Assertions.assertEquals(1800, IdleTransactionMonitor.getReminderPeriodSeconds(quietContainer));
    }

    @Test
    public void automaticRollbackOnlyWhenTheUserTurnedItOnWithTheReminder() {
        DBPConnectionConfiguration configuration = TestConnections.configuration(DBPConnectionType.PROD);
        DBPDataSourceContainer container = TestConnections.container(configuration);
        // Never by default, also not on a production connection
        Assertions.assertFalse(IdleTransactionMonitor.isAutoRollback(container));
        configuration.setIdleTransactionRollback(true);
        Assertions.assertTrue(IdleTransactionMonitor.isAutoRollback(container));
        configuration.setIdleTransactionReminder(false);
        Assertions.assertFalse(IdleTransactionMonitor.isAutoRollback(container));
    }

    @Test
    public void remindsOnceAfterTheIdlePeriodAndClosesTheReminderWhenUsedAgain() throws Exception {
        UIServiceIdleTransactions ui = Mockito.mock(UIServiceIdleTransactions.class);
        synchronized (states()) {
            try (QMTestSession qm = new QMTestSession();
                 MockedStatic<DBWorkbench> ignored = TestConnections.services(Map.of(UIServiceIdleTransactions.class, ui))) {
                DBCExecutionContext context = qm.openContext(true);
                DBPDataSourceContainer container = TestConnections.container(minutes(1), context);

                // Idle for less than the period: nothing
                qm.execute(context, DBCExecutionPurpose.USER, "UPDATE t SET a = 1", 1, 30_000);
                pass(container);
                Mockito.verifyNoInteractions(ui);

                // Over the period, counted from the last user statement: a later metadata read does not count
                qm.execute(context, DBCExecutionPurpose.USER, "UPDATE t SET a = 2", 1, 2 * MINUTE);
                qm.execute(context, DBCExecutionPurpose.META, "SELECT * FROM pg_catalog.pg_class", -1, 0);
                pass(container);
                pass(container);
                Mockito.verify(ui, Mockito.times(1)).showIdleTransactionReminder(Mockito.eq(context), Mockito.any(QMTransactionState.class));

                // Used again: the reminder closes and the idle time starts over
                qm.execute(context, DBCExecutionPurpose.USER, "UPDATE t SET a = 3", 1, 0);
                pass(container);
                Mockito.verify(ui).closeIdleTransactionReminder(context);
                Mockito.verify(ui, Mockito.times(1)).showIdleTransactionReminder(Mockito.eq(context), Mockito.any(QMTransactionState.class));
                Assertions.assertTrue(QMUtils.getTransactionState(context).isOpen());
            }
        }
    }

    @Test
    public void keepOpenWaitsAnotherPeriodAndAGoneContextClosesItsReminder() throws Exception {
        UIServiceIdleTransactions ui = Mockito.mock(UIServiceIdleTransactions.class);
        synchronized (states()) {
            try (QMTestSession qm = new QMTestSession();
                 MockedStatic<DBWorkbench> ignored = TestConnections.services(Map.of(UIServiceIdleTransactions.class, ui))) {
                DBCExecutionContext context = qm.openContext(true);
                DBPDataSourceContainer container = TestConnections.container(minutes(1), context);
                qm.execute(context, DBCExecutionPurpose.USER, "DELETE FROM t WHERE id = 1", 1, 5 * MINUTE);
                pass(container);
                Mockito.verify(ui, Mockito.times(1)).showIdleTransactionReminder(Mockito.eq(context), Mockito.any(QMTransactionState.class));

                // Keep open of another transaction does nothing; of this one, no reminder for another period
                long start = QMUtils.getTransactionState(context).getTransactionStartTime();
                IdleTransactionMonitor.keepOpen(context, start + 1);
                pass(container);
                IdleTransactionMonitor.keepOpen(context, start);
                pass(container);
                Mockito.verify(ui, Mockito.times(1)).showIdleTransactionReminder(Mockito.eq(context), Mockito.any(QMTransactionState.class));

                // A context that is gone (disconnected) has its shown reminder closed
                DBCExecutionContext other = qm.openContext(true);
                DBPDataSourceContainer otherContainer = TestConnections.container(minutes(1), other);
                qm.execute(other, DBCExecutionPurpose.USER, "DELETE FROM t WHERE id = 2", 1, 5 * MINUTE);
                pass(otherContainer);
                Mockito.verify(ui).showIdleTransactionReminder(Mockito.eq(other), Mockito.any(QMTransactionState.class));
                IdleTransactionMonitor.beginPass();
                IdleTransactionMonitor.sweep();
                Mockito.verify(ui).closeIdleTransactionReminder(other);
            }
        }
    }

    @Test
    public void noReminderWhileAStatementRunsOrInAutoCommitOrWithoutUi() throws Exception {
        UIServiceIdleTransactions ui = Mockito.mock(UIServiceIdleTransactions.class);
        synchronized (states()) {
            try (QMTestSession qm = new QMTestSession()) {
                DBCExecutionContext running = qm.openContext(true);
                DBCExecutionContext autoCommit = qm.openContext(false);
                DBCExecutionContext headless = qm.openContext(true);
                qm.execute(running, DBCExecutionPurpose.USER, "UPDATE t SET a = 1", 1, 5 * MINUTE);
                // A metadata read still runs: the last user statement is old, but the context is busy
                qm.begin(running, DBCExecutionPurpose.META, "SELECT * FROM pg_catalog.pg_locks");
                qm.execute(autoCommit, DBCExecutionPurpose.USER, "UPDATE t SET a = 1", 1, 5 * MINUTE);
                qm.execute(headless, DBCExecutionPurpose.USER, "UPDATE t SET a = 1", 1, 5 * MINUTE);

                try (MockedStatic<DBWorkbench> ignored = TestConnections.services(Map.of(UIServiceIdleTransactions.class, ui))) {
                    pass(TestConnections.container(minutes(1), running, autoCommit));
                }
                // No UI service (headless application): neither a reminder nor an automatic rollback
                try (MockedStatic<DBWorkbench> ignored = TestConnections.services(Map.of())) {
                    pass(TestConnections.container(minutes(1), headless));
                }
                Mockito.verifyNoInteractions(ui);
            }
        }
    }

    @Test
    public void endTransactionLeavesAnotherTransactionAlone() throws Exception {
        try (QMTestSession qm = new QMTestSession()) {
            DBCExecutionContext context = qm.openContext(true);
            qm.execute(context, DBCExecutionPurpose.USER, "UPDATE t SET a = 1", 1, MINUTE);
            long start = QMUtils.getTransactionState(context).getTransactionStartTime();
            DBRProgressMonitor monitor = Mockito.mock(DBRProgressMonitor.class);

            // The transaction shown to the user is not the current one: nothing is committed or rolled back
            Assertions.assertFalse(IdleTransactionMonitor.endTransaction(monitor, context, start - 1, true));
            Assertions.assertFalse(IdleTransactionMonitor.endTransaction(monitor, context, start - 1, false));
            qm.commit(context);
            Assertions.assertFalse(IdleTransactionMonitor.endTransaction(monitor, context, start, false));
            Mockito.verify(context, Mockito.never()).openSession(Mockito.any(), Mockito.any(), Mockito.anyString());
        }
    }

    @NotNull
    private static Map<?, ?> states() throws Exception {
        Field field = IdleTransactionMonitor.class.getDeclaredField("states");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }

    @NotNull
    private static DBPConnectionConfiguration minutes(int minutes) {
        DBPConnectionConfiguration configuration = TestConnections.configuration(DBPConnectionType.DEV);
        configuration.setIdleTransactionReminder(true);
        configuration.setIdleTransactionPeriod(minutes * 60);
        return configuration;
    }

    private static void pass(@NotNull DBPDataSourceContainer container) {
        IdleTransactionMonitor.beginPass();
        IdleTransactionMonitor.check(container);
        IdleTransactionMonitor.sweep();
    }
}
