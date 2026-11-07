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
package com.seizia.chromite.model.impl.data;

import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceInfo;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Regression tests of the NULL handling of {@link ExecuteBatchImpl}: a row whose NULL columns differ from the row before
 * needs a new statement, with and without batches (the data corruption fixed on 2026-11-07).
 *
 * 설계: 실제 드라이버 대신 문장 준비와 실행을 세는 하위 클래스로 확인한다. 엔진은 NULL인 열을 문장에 직접 쓰고 매개변수에서 빼므로
 * ({@link ExecuteBatchImpl#getNextUsedParamIndex}), NULL 열이 바뀐 행에 앞 문장을 다시 쓰면 앞 행의 값이 남고 뒤 매개변수가 밀린다.
 * 그래서 행마다 "준비할 때의 NULL 열"과 "그 문장으로 실행한 행의 NULL 열"이 늘 같아야 한다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "데이터")
 */
public class ExecuteBatchImplNullColumnsTest {

    @Test
    public void rowByRowExecutionPreparesAgainWhenTheNullColumnsChange() throws Exception {
        RecordingBatch batch = new RecordingBatch();
        batch.add(new Object[] {1, "a"});
        batch.add(new Object[] {null, "b"});
        batch.add(new Object[] {3, null});
        batch.add(new Object[] {4, null});
        batch.add(new Object[] {5, "e"});

        batch.execute(session(false), Map.of());

        Assertions.assertEquals(List.of("[false, false]", "[true, false]", "[false, true]", "[false, false]"), batch.prepared);
        assertEveryRowRanWithItsOwnStatement(batch);
        Assertions.assertEquals(5, batch.executed);
    }

    @Test
    public void batchesAreFlushedAndPreparedAgainWhenTheNullColumnsChange() throws Exception {
        RecordingBatch batch = new RecordingBatch();
        batch.add(new Object[] {1, "a"});
        batch.add(new Object[] {2, "b"});
        batch.add(new Object[] {null, "c"});
        batch.add(new Object[] {4, "d"});

        batch.execute(session(true), Map.of());

        Assertions.assertEquals(List.of("[false, false]", "[true, false]", "[false, false]"), batch.prepared);
        assertEveryRowRanWithItsOwnStatement(batch);
        Assertions.assertEquals(3, batch.flushed);
    }

    @Test
    public void sameNullColumnsReuseOneStatement() throws Exception {
        RecordingBatch batch = new RecordingBatch();
        batch.add(new Object[] {1, null});
        batch.add(new Object[] {2, null});
        batch.add(new Object[] {3, null});

        batch.execute(session(false), Map.of());

        Assertions.assertEquals(List.of("[false, true]"), batch.prepared);
        assertEveryRowRanWithItsOwnStatement(batch);
    }

    private static void assertEveryRowRanWithItsOwnStatement(@NotNull RecordingBatch batch) {
        for (int i = 0; i < batch.bound.size(); i++) {
            Assertions.assertEquals(batch.bound.get(i)[0], batch.bound.get(i)[1], "row " + i + ": statement prepared for other NULL columns");
        }
    }

    @NotNull
    private static DBCSession session(boolean batchUpdates) {
        DBPDataSourceInfo info = Mockito.mock(DBPDataSourceInfo.class);
        Mockito.when(info.supportsBatchUpdates()).thenReturn(batchUpdates);
        DBPDataSource dataSource = Mockito.mock(DBPDataSource.class);
        Mockito.when(dataSource.getInfo()).thenReturn(info);
        DBCSession session = Mockito.mock(DBCSession.class);
        Mockito.when(session.getDataSource()).thenReturn(dataSource);
        Mockito.when(session.getProgressMonitor()).thenReturn(Mockito.mock(DBRProgressMonitor.class));
        return session;
    }

    @NotNull
    private static DBSAttributeBase[] attributes() {
        DBSAttributeBase[] attributes = new DBSAttributeBase[2];
        for (int i = 0; i < attributes.length; i++) {
            DBDAttributeBinding binding = Mockito.mock(DBDAttributeBinding.class);
            Mockito.when(binding.getValueHandler()).thenReturn(Mockito.mock(DBDValueHandler.class));
            attributes[i] = binding;
        }
        return attributes;
    }

    private static String nulls(@NotNull Object[] values) {
        boolean[] nulls = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            nulls[i] = values[i] == null;
        }
        return Arrays.toString(nulls);
    }

    /**
     * Records the NULL columns each statement was prepared for and each row was bound with.
     */
    private static final class RecordingBatch extends ExecuteBatchImpl {
        final List<String> prepared = new ArrayList<>();
        // NULL columns of the statement, NULL columns of the row
        final List<String[]> bound = new ArrayList<>();
        final Map<DBCStatement, String> statementNulls = new java.util.IdentityHashMap<>();
        int executed;
        int flushed;

        RecordingBatch() {
            super(attributes(), null, true);
        }

        @NotNull
        @Override
        protected DBCStatement prepareStatement(
            @NotNull DBCSession session,
            DBDValueHandler[] handlers,
            Object[] attributeValues,
            Map<String, Object> options
        ) {
            DBCStatement statement = Mockito.mock(DBCStatement.class);
            Mockito.when(statement.getQueryString()).thenReturn("INSERT INTO t (a, b) VALUES (?, ?)");
            try {
                Mockito.when(statement.getUpdateRowCount()).thenReturn(1L);
                Mockito.when(statement.executeStatement()).thenAnswer(invocation -> {
                    executed++;
                    return false;
                });
                Mockito.when(statement.executeStatementBatch()).thenAnswer(invocation -> {
                    flushed++;
                    return new long[] {1};
                });
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            String nulls = nulls(attributeValues);
            prepared.add(nulls);
            statementNulls.put(statement, nulls);
            return statement;
        }

        @Override
        protected void bindStatement(@NotNull DBDValueHandler[] handlers, @NotNull DBCStatement statement, Object[] attributeValues) {
            bound.add(new String[] {statementNulls.get(statement), nulls(attributeValues)});
        }
    }
}
