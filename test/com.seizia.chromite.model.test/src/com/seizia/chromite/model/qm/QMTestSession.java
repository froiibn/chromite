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
package com.seizia.chromite.model.qm;

import com.seizia.chromite.junit.StaticFieldOverride;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.qm.meta.QMMConnectionInfo;
import com.seizia.chromite.model.qm.meta.QMMStatementExecuteInfo;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.qm.QMRegistryImpl;
import org.jkiss.code.NotNull;
import org.mockito.Mockito;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Query manager records for tests: real {@link QMMConnectionInfo} objects of mocked execution contexts, served through a
 * mocked registry that replaces the query manager of {@link QMUtils} while the session is open.
 *
 * 설계: 열린 트랜잭션 판단(상태 줄, 유휴 감시, 오류 복구, 유휴 연결 닫기)은 모두 {@link QMUtils}가 쿼리 관리자의 메모리 기록을 읽은
 * 결과를 쓴다. 실제 기록 객체에 실제 문장 실행 순서(열기, 시작, 끝)를 넣어야 판단 코드가 실제 앱과 같은 값을 본다. 실제 쿼리 관리자에
 * 넣으면 메타 이벤트가 프로젝트·세션을 찾으므로, 기록 객체만 실제로 만들고 레지스트리는 mock으로 바꾼다. 시각은 {@code setOpenTime}·
 * {@code setCloseTime}으로 과거로 돌려 유휴 시간을 만든다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "트랜잭션 안전")
 */
public final class QMTestSession implements AutoCloseable {

    private final Map<DBCExecutionContext, QMMConnectionInfo> connections = new IdentityHashMap<>();
    private final StaticFieldOverride registryOverride;
    private final StaticFieldOverride handlerOverride;
    private final QMExecutionHandler handler = Mockito.mock(QMExecutionHandler.class);

    public QMTestSession() throws ReflectiveOperationException {
        QMMCollector collector = Mockito.mock(QMMCollector.class);
        Mockito.when(collector.getConnectionInfo(Mockito.any())).thenAnswer(invocation -> connections.get(invocation.getArgument(0)));
        QMRegistryImpl registry = Mockito.mock(QMRegistryImpl.class);
        Mockito.when(registry.getMetaCollector()).thenReturn(collector);
        registryOverride = new StaticFieldOverride(QMUtils.class, "qmRegistry", registry);
        handlerOverride = new StaticFieldOverride(QMUtils.class, "defaultHandler", handler);
    }

    /**
     * The handler {@link QMUtils#getDefaultHandler()} returns while the session is open (records the rollbacks
     * the recovery writes to the query manager).
     */
    @NotNull
    public QMExecutionHandler getHandler() {
        return handler;
    }

    /**
     * A context known to the query manager, in auto-commit or manual commit mode.
     */
    @NotNull
    public DBCExecutionContext openContext(boolean manualCommit) {
        DBCExecutionContext context = Mockito.mock(DBCExecutionContext.class);
        Mockito.when(context.getContextName()).thenReturn("Test context " + connections.size());
        long now = System.currentTimeMillis();
        QMMConnectionInfo info = new QMMConnectionInfo(now, 0, null, "test-container", "Test", "test-driver",
            new DBPConnectionConfiguration(), "Test instance", "Test context", false);
        if (manualCommit) {
            info.changeTransactional(true);
        }
        connections.put(context, info);
        return context;
    }

    @NotNull
    public QMMConnectionInfo getConnection(@NotNull DBCExecutionContext context) {
        return connections.get(context);
    }

    /**
     * Runs a finished statement: open, execute, end (update count {@code rows}, -1 for a query).
     *
     * @param ageMillis how long ago the statement ended
     */
    @NotNull
    public QMMStatementExecuteInfo execute(
        @NotNull DBCExecutionContext context,
        @NotNull DBCExecutionPurpose purpose,
        @NotNull String sql,
        long rows,
        long ageMillis
    ) {
        DBCStatement statement = statement(context, purpose, sql);
        QMMConnectionInfo info = connections.get(context);
        info.openStatement(statement);
        QMMStatementExecuteInfo exec = info.beginExecution(statement);
        info.endExecution(statement, rows, null);
        long end = System.currentTimeMillis() - ageMillis;
        exec.setOpenTime(end - 10);
        exec.setCloseTime(end);
        return exec;
    }

    /**
     * Starts a statement that has not ended yet.
     */
    @NotNull
    public QMMStatementExecuteInfo begin(@NotNull DBCExecutionContext context, @NotNull DBCExecutionPurpose purpose, @NotNull String sql) {
        DBCStatement statement = statement(context, purpose, sql);
        QMMConnectionInfo info = connections.get(context);
        info.openStatement(statement);
        return info.beginExecution(statement);
    }

    public void commit(@NotNull DBCExecutionContext context) {
        connections.get(context).commit();
    }

    /**
     * Moves the (re)connection and the start of the current transaction record of the context into the past.
     */
    public void age(@NotNull DBCExecutionContext context, long ageMillis) {
        QMMConnectionInfo info = connections.get(context);
        long time = System.currentTimeMillis() - ageMillis;
        info.setOpenTime(time);
        if (info.getTransaction() != null) {
            info.getTransaction().setOpenTime(time);
        }
    }

    @NotNull
    private static DBCStatement statement(@NotNull DBCExecutionContext context, @NotNull DBCExecutionPurpose purpose, @NotNull String sql) {
        DBPDataSource dataSource = Mockito.mock(DBPDataSource.class);
        Mockito.when(dataSource.getSQLDialect()).thenReturn(BasicSQLDialect.INSTANCE);
        DBCSession session = Mockito.mock(DBCSession.class);
        Mockito.when(session.getPurpose()).thenReturn(purpose);
        Mockito.when(session.getDataSource()).thenReturn(dataSource);
        Mockito.when(session.getExecutionContext()).thenReturn(context);
        Mockito.when(session.getProgressMonitor()).thenReturn(Mockito.mock(DBRProgressMonitor.class));
        DBCStatement statement = Mockito.mock(DBCStatement.class);
        Mockito.when(statement.getSession()).thenReturn(session);
        Mockito.when(statement.getQueryString()).thenReturn(sql);
        return statement;
    }

    @Override
    public void close() throws IllegalAccessException {
        handlerOverride.close();
        registryOverride.close();
    }
}
