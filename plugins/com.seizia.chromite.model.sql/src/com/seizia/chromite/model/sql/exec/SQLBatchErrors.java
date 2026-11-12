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
package com.seizia.chromite.model.sql.exec;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPDataSourceInfo;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.runtime.DBInterruptedException;

import java.nio.channels.ClosedByInterruptException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Errors of the statements of a multi-statement batch that the driver reports while the results are read, for the
 * script executors ({@link SQLQueryExecuteJob}, {@link SQLScriptProcessor}).
 *
 * 설계: SQL Server는 배치의 문장 하나가 실패해도(문장 단위 오류: 0으로 나누기, RAISERROR, 키 위반 등) 나머지 문장을 계속 실행하고,
 * 드라이버는 그 오류를 결과를 읽다가 그 자리에서 예외로 알린다({@code execute}, {@code ResultSet.next}, {@code getMoreResults}).
 * 예외 뒤에도 다음 결과를 읽을 수 있다. 이런 드라이버는 {@link DBPDataSourceInfo#isMultipleResultsFetchBroken()}가 true다
 * (SQL Server와 같은 플러그인의 Sybase, jTDS). 예전 실행기는 이 플래그일 때 {@code getMoreResults} 예외를 경고로만 남기거나(편집기)
 * 로그에만 쓰고(작업) 다음 결과로 넘어가서, 뒤 문장의 오류가 사라지고 실행이 성공으로 끝났다. 여기서는 그 예외를 쿼리 오류로 모으고
 * 계속 읽어서 오류 뒤에 실행된 문장의 결과도 보이게 한다. 취소와 연결 끊김은 더 읽을 수 없으므로 예전처럼 던진다
 * (이 경우만 실행기의 복구가 동작하고, 그 복구는 읽기만 하는 SELECT 하나만 다시 실행한다: {@link SQLQueryRecovery}). 이 판단은 오류 자신과 원인만 본다: SQL Server 드라이버의
 * {@code getNextException()}은 뒤 결과를 미리 읽어 오류 사이의 행 수와 결과 집합을 버리므로, 결과를 다 읽기 전에는 오류 도우미
 * ({@code discoverErrorType})를 부르거나 오류를 로그에 쓰지 않는다(둘 다 그 사슬을 따라간다).
 * 두 실행기의 결과 읽기 루프가 달라 루프는 그대로 두고 판단과 다음 결과 이동만 모았다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/sql-batch-errors.md)
 * - 2026-11-12 주석만: 연결 끊김 뒤의 복구는 읽기만 다시 실행함(앱 변경 #86)
 */
final class SQLBatchErrors {

    /**
     * Errors in a row while moving to the next result, after which the move fails: a driver that keeps throwing would
     * otherwise loop forever.
     */
    private static final int MAX_ERRORS_IN_A_ROW = 1000;

    private static final String SQL_STATE_CONNECTION_CLASS = "08"; //$NON-NLS-1$
    private static final String SQL_STATE_CANCELED = "HY008"; //$NON-NLS-1$
    private static final String SQL_STATE_JCONNECT_DRIVER = "JZ0"; //$NON-NLS-1$

    private SQLBatchErrors() {
    }

    /**
     * Whether the results after this error can still be read: the driver reports statement errors in place
     * ({@link DBPDataSourceInfo#isMultipleResultsFetchBroken()}), the run was not canceled, and the error is not a lost
     * connection or a canceled statement.
     * <p>
     * Only the error and its causes are looked at, never {@link SQLException#getNextException()}: the SQL Server driver
     * builds that chain by reading the following results ahead and drops the update counts and result sets between the
     * errors. So the error assistants ({@link DBExecUtils#discoverErrorType}, which walk the chain) and logging the error
     * (the log writes the chain too) must wait until the results are read.
     */
    static boolean canReadPast(@NotNull DBCSession session, @NotNull Throwable error) {
        if (!session.getDataSource().getInfo().isMultipleResultsFetchBroken() || session.getProgressMonitor().isCanceled()) {
            return false;
        }
        for (Throwable cause = error; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof DBInterruptedException || cause instanceof ClosedByInterruptException) {
                return false;
            }
            if (cause instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                // 08xxx: connection exception (jTDS and SQL Server 08S01), HY008: statement canceled or timed out,
                // JZ0xx: jConnect's own driver errors (I/O, closed connection)
                return state == null || !(state.startsWith(SQL_STATE_CONNECTION_CLASS) || state.equals(SQL_STATE_CANCELED)
                    || state.startsWith(SQL_STATE_JCONNECT_DRIVER));
            }
        }
        return true;
    }

    /**
     * Moves to the next result ({@link DBCStatement#nextResults()}). An error that the driver reports there for a later
     * statement goes to {@code errors} and the move is tried again, as long as {@link #canReadPast} allows it and the
     * statement is still open.
     */
    static boolean nextResults(
        @NotNull DBCSession session,
        @NotNull DBCStatement statement,
        @NotNull Consumer<Throwable> errors
    ) throws DBCException {
        for (int count = 0; ; count++) {
            try {
                return statement.nextResults();
            } catch (DBCException e) {
                if (count >= MAX_ERRORS_IN_A_ROW || !canReadPast(session, e) || statement.isStatementClosed()) {
                    throw e;
                }
                errors.accept(e);
            }
        }
    }

    /**
     * After {@link DBCStatement#executeStatement()} failed: the statement that failed may be the first one of a batch
     * whose later statements still ran. Then the error goes to {@code errors} with the errors of the later statements,
     * and the result of the next statement is returned (true: a result set) to be read as usual. When nothing follows
     * the error (one statement, or an error that ended the batch), it is thrown as before.
     */
    static boolean resumeAfterExecuteError(
        @NotNull DBCSession session,
        @NotNull DBCStatement statement,
        @NotNull DBCException error,
        @NotNull Consumer<Throwable> errors
    ) throws DBCException {
        if (!canReadPast(session, error)) {
            throw error;
        }
        List<Throwable> laterErrors = new ArrayList<>();
        boolean hasResultSet;
        try {
            hasResultSet = nextResults(session, statement, laterErrors::add);
            if (!hasResultSet && laterErrors.isEmpty() && statement.getUpdateRowCount() < 0) {
                throw error;
            }
        } catch (DBCException e) {
            // The results can't be read further (connection lost, canceled): fail with the statement's own error
            if (e != error) {
                laterErrors.forEach(error::addSuppressed);
                error.addSuppressed(e);
            }
            throw error;
        }
        errors.accept(error);
        laterErrors.forEach(errors);
        return hasResultSet;
    }
}
