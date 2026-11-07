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

import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.qm.meta.QMMStatementExecuteInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

/**
 * Regression tests of the open transaction reading of the query manager ({@link QMUtils#getTransactionState},
 * {@link QMUtils#getTransactionStatements}, {@link QMUtils#getLastUseTime}).
 *
 * 설계: 상태 줄, 트랜잭션 로그, 유휴 감시, 유휴 연결 닫기가 같은 판단을 쓰므로, 사용자 문장만 세는지(META·UTIL 제외), 커밋 뒤 새로
 * 세는지, 실행 중인 문장과 마지막 사용 시각이 맞는지를 실제 기록 객체로 본다({@link QMTestSession}).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "트랜잭션 안전")
 */
public class QMUtilsTransactionStateTest {

    @Test
    public void onlyUserStatementsOpenATransaction() throws Exception {
        try (QMTestSession qm = new QMTestSession()) {
            DBCExecutionContext context = qm.openContext(true);
            qm.execute(context, DBCExecutionPurpose.META, "SELECT * FROM information_schema.tables", -1, 5000);
            qm.execute(context, DBCExecutionPurpose.UTIL, "SET search_path TO public", 0, 4000);
            Assertions.assertFalse(QMUtils.getTransactionState(context).isOpen());
            Assertions.assertTrue(QMUtils.getTransactionStatements(context).isEmpty());

            QMMStatementExecuteInfo update = qm.execute(context, DBCExecutionPurpose.USER, "UPDATE t SET a = 1", 3, 3000);
            QMMStatementExecuteInfo select = qm.execute(context, DBCExecutionPurpose.USER_SCRIPT, "SELECT * FROM t", -1, 2000);
            qm.execute(context, DBCExecutionPurpose.META, "SELECT * FROM pg_catalog.pg_class", -1, 1000);

            QMTransactionState state = QMUtils.getTransactionState(context);
            Assertions.assertTrue(state.isOpen());
            Assertions.assertEquals(2, state.getExecuteCount());
            Assertions.assertEquals(1, state.getUpdateCount());
            // The first user statement starts the transaction, the last one is the last use: the metadata read does not count
            Assertions.assertEquals(update.getOpenTime(), state.getTransactionStartTime());
            Assertions.assertEquals(select.getCloseTime(), state.getLastExecuteTime());
            Assertions.assertFalse(state.isExecuting());
            Assertions.assertEquals(List.of(update, select), QMUtils.getTransactionStatements(context));
        }
    }

    @Test
    public void commitStartsANewTransactionAndAutoCommitHasNone() throws Exception {
        try (QMTestSession qm = new QMTestSession()) {
            DBCExecutionContext context = qm.openContext(true);
            QMMStatementExecuteInfo first = qm.execute(context, DBCExecutionPurpose.USER, "DELETE FROM t WHERE id = 1", 1, 3000);
            long firstStart = QMUtils.getTransactionState(context).getTransactionStartTime();
            Assertions.assertEquals(first.getOpenTime(), firstStart);

            qm.commit(context);
            Assertions.assertFalse(QMUtils.getTransactionState(context).isOpen());
            Assertions.assertEquals(0, QMUtils.getTransactionState(context).getTransactionStartTime());
            Assertions.assertTrue(QMUtils.getTransactionStatements(context).isEmpty());

            qm.execute(context, DBCExecutionPurpose.USER, "DELETE FROM t WHERE id = 2", 1, 1000);
            // A different start time: the reminder and the commit buttons see another transaction
            Assertions.assertNotEquals(firstStart, QMUtils.getTransactionState(context).getTransactionStartTime());

            DBCExecutionContext autoCommit = qm.openContext(false);
            qm.execute(autoCommit, DBCExecutionPurpose.USER, "UPDATE t SET a = 2", 5, 1000);
            QMTransactionState state = QMUtils.getTransactionState(autoCommit);
            Assertions.assertFalse(state.isTransactionMode());
            Assertions.assertFalse(state.isOpen());
            Assertions.assertTrue(QMUtils.getTransactionStatements(autoCommit).isEmpty());
        }
    }

    @Test
    public void runningStatementOfAnyPurposeIsExecutionAndCurrentUse() throws Exception {
        try (QMTestSession qm = new QMTestSession()) {
            DBCExecutionContext context = qm.openContext(true);
            qm.age(context, 60_000);
            qm.execute(context, DBCExecutionPurpose.USER, "UPDATE t SET a = 1", 1, 50_000);
            Assertions.assertFalse(QMUtils.getTransactionState(context).isExecuting());

            qm.begin(context, DBCExecutionPurpose.META, "SELECT * FROM pg_catalog.pg_locks");
            long before = System.currentTimeMillis();
            Assertions.assertTrue(QMUtils.getTransactionState(context).isExecuting());
            Assertions.assertTrue(QMUtils.getLastUseTime(context) >= before);
        }
    }

    @Test
    public void lastUseTimeIsTheLatestOfConnectStatementAndCommit() throws Exception {
        try (QMTestSession qm = new QMTestSession()) {
            DBCExecutionContext context = qm.openContext(true);
            qm.age(context, 120_000);
            long connected = qm.getConnection(context).getOpenTime();
            Assertions.assertEquals(connected, QMUtils.getLastUseTime(context));

            // A metadata read is a use of the connection too (not of the transaction)
            QMMStatementExecuteInfo meta = qm.execute(context, DBCExecutionPurpose.META, "SELECT 1", -1, 90_000);
            Assertions.assertEquals(meta.getCloseTime(), QMUtils.getLastUseTime(context));

            // A commit starts a new transaction record: that time is the last use
            long beforeCommit = System.currentTimeMillis();
            qm.commit(context);
            Assertions.assertTrue(QMUtils.getLastUseTime(context) >= beforeCommit);

            // Unknown to the query manager
            Assertions.assertEquals(0, QMUtils.getLastUseTime(Mockito.mock(DBCExecutionContext.class)));
            Assertions.assertEquals(0, QMUtils.getLastUseTime(null));
        }
    }
}
