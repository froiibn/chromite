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
import com.seizia.chromite.runtime.DBServiceUnrecordedSessions;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.Map;

/**
 * Regression tests of the idle connection close conditions of {@link DataSourceMonitorJob}.
 *
 * 설계: 유휴 연결 닫기는 연결별 마지막 사용 시각(쿼리 관리자가 본 모든 목적의 문장, 커밋·롤백, Chromite Agent 세션)으로 재고, 열린
 * 트랜잭션·실행 중인 문장·에이전트 세션이 있으면 닫지 않는다(닫으면 묻지 않고 롤백된다). 판단 메서드({@code isIdleConnection})는
 * private이라 리플렉션으로 부르고, 실제 닫기 작업은 띄우지 않는다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "트랜잭션 안전")
 */
public class DataSourceMonitorJobIdleTest {

    private static final long HOUR = 3_600_000;
    private static final long TIMEOUT_SECONDS = 1800;

    @Test
    public void timeoutComesFromTheConnectionThenFromItsType() {
        DBPConnectionConfiguration configuration = TestConnections.configuration(DBPConnectionType.DEV);
        DBPDataSourceContainer container = TestConnections.container(configuration);
        configuration.setCloseIdleConnection(true);
        configuration.setCloseIdleInterval(300);
        Assertions.assertEquals(300, DataSourceMonitorJob.getDisconnectTimeoutSeconds(container));
        configuration.setCloseIdleInterval(0);
        Assertions.assertEquals(DBPConnectionType.DEV.getCloseIdleConnectionPeriod(), DataSourceMonitorJob.getDisconnectTimeoutSeconds(container));
        configuration.setCloseIdleConnection(false);
        Assertions.assertEquals(0, DataSourceMonitorJob.getDisconnectTimeoutSeconds(container));
    }

    @Test
    public void unusedConnectionWithoutOpenTransactionIsIdle() throws Exception {
        try (QMTestSession qm = new QMTestSession(); MockedStatic<DBWorkbench> ignored = TestConnections.services(Map.of())) {
            DBCExecutionContext manual = qm.openContext(true);
            DBCExecutionContext autoCommit = qm.openContext(false);
            qm.age(manual, 2 * HOUR);
            qm.age(autoCommit, 2 * HOUR);
            // Committed work and auto-commit changes leave nothing open
            qm.execute(manual, DBCExecutionPurpose.USER, "UPDATE t SET a = 1", 1, 2 * HOUR);
            qm.commit(manual);
            qm.age(manual, 2 * HOUR);
            qm.execute(autoCommit, DBCExecutionPurpose.USER, "UPDATE t SET a = 1", 1, 2 * HOUR);
            DBPDataSourceContainer container = connectedHoursAgo(2, manual, autoCommit);

            Assertions.assertTrue(isIdle(container, TIMEOUT_SECONDS));
            Assertions.assertFalse(isIdle(container, 0));
            Assertions.assertFalse(isIdle(container, 3 * HOUR / 1000));
        }
    }

    @Test
    public void anyRecentUseOrOpenOrRunningWorkKeepsTheConnection() throws Exception {
        try (QMTestSession qm = new QMTestSession(); MockedStatic<DBWorkbench> ignored = TestConnections.services(Map.of())) {
            // A metadata read is a use
            DBCExecutionContext metadata = qm.openContext(false);
            qm.age(metadata, 2 * HOUR);
            qm.execute(metadata, DBCExecutionPurpose.META, "SELECT * FROM pg_catalog.pg_class", -1, 60_000);
            Assertions.assertFalse(isIdle(connectedHoursAgo(2, metadata), TIMEOUT_SECONDS));

            // An open transaction, however old: closing would roll it back without asking
            DBCExecutionContext open = qm.openContext(true);
            qm.age(open, 2 * HOUR);
            qm.execute(open, DBCExecutionPurpose.USER, "DELETE FROM t WHERE id = 1", 1, 2 * HOUR);
            Assertions.assertFalse(isIdle(connectedHoursAgo(2, open), TIMEOUT_SECONDS));

            // A statement that still runs
            DBCExecutionContext running = qm.openContext(false);
            qm.age(running, 2 * HOUR);
            qm.begin(running, DBCExecutionPurpose.USER, "SELECT pg_sleep(7200)");
            Assertions.assertFalse(isIdle(connectedHoursAgo(2, running), TIMEOUT_SECONDS));
        }
    }

    @Test
    public void chromiteAgentSessionsCountAsUse() throws Exception {
        DBServiceUnrecordedSessions agent = Mockito.mock(DBServiceUnrecordedSessions.class);
        try (QMTestSession qm = new QMTestSession();
             MockedStatic<DBWorkbench> ignored = TestConnections.services(Map.of(DBServiceUnrecordedSessions.class, agent))) {
            DBCExecutionContext context = qm.openContext(false);
            qm.age(context, 2 * HOUR);
            DBPDataSourceContainer container = connectedHoursAgo(2, context);
            Assertions.assertTrue(isIdle(container, TIMEOUT_SECONDS));

            Mockito.when(agent.getLastUseTime(container)).thenReturn(System.currentTimeMillis() - 60_000);
            Assertions.assertFalse(isIdle(container, TIMEOUT_SECONDS));

            Mockito.when(agent.getLastUseTime(container)).thenReturn(0L);
            Mockito.when(agent.isConnectionInUse(container)).thenReturn(true);
            Assertions.assertFalse(isIdle(container, TIMEOUT_SECONDS));
        }
    }

    @NotNull
    private static DBPDataSourceContainer connectedHoursAgo(int hours, @NotNull DBCExecutionContext... contexts) {
        DBPDataSourceContainer container = TestConnections.container(TestConnections.configuration(DBPConnectionType.DEV), contexts);
        Mockito.when(container.getConnectTime()).thenReturn(new Date(System.currentTimeMillis() - hours * HOUR));
        return container;
    }

    private static boolean isIdle(@NotNull DBPDataSourceContainer container, long timeoutSeconds) throws Exception {
        Method method = DataSourceMonitorJob.class.getDeclaredMethod("isIdleConnection", DBPDataSourceContainer.class, long.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, container, timeoutSeconds);
    }
}
