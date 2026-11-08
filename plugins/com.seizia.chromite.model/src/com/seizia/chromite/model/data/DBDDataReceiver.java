/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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

package com.seizia.chromite.model.data;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.DBRuntimeException;
import com.seizia.chromite.model.DBFetchProgress;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCResultSet;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatistics;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

/**
 * Data receiver.
 * Used to receive some result set data.
 * Result set can be a result of some query execution, cursor returned from stored procedure, generated keys result set, etc.
 *
 * 변경 이력:
 * - 2026-11-08 Chromite: {@link #startFetchWorkflow}에서 {@code fetchStart}가 실패해도 {@link #close()}를 부름(이 인터페이스가 약속한 대로).
 *   전에는 닫기가 문장 닫기에 걸리기 전에 실패하면 불리지 않아, 데이터 전송 넣기 소비자가 비우기(TRUNCATE)를 한 넣기 연결을
 *   커밋하지 않은 트랜잭션째 열어 둔 채 남겼다 (docs/chromite/swing-data-transfer.md "취소·실패 때 넣기 트랜잭션")
 */
public interface DBDDataReceiver extends AutoCloseable {

    void fetchStart(@NotNull DBCSession session, @NotNull DBCResultSet resultSet, long offset, long maxRows)
        throws DBException;

    void fetchRow(@NotNull DBCSession session, @NotNull DBCResultSet resultSet)
        throws DBException;

    /**
     * Called after entire result set if fetched.
     * WARN: It SHOULD be called after owner statement close. Because in fetchEnd additional queries/server reads may be performed.
     * This may cause statement lock issues.
     * @throws DBCException on error
     * @param session execution context
     * @param resultSet    result set
     */
    void fetchEnd(@NotNull DBCSession session, @NotNull DBCResultSet resultSet)
        throws DBException;

    /**
     * Called after entire result set is fetched and closed.
     * This method is called even if fetchStart wasn't called in this data receiver (may occur if statement throws an error)
     */
    void close() throws DBException;

    // FIXME: we should keep in variable or do not keep it at all (use separate interface)
    @NotNull
    default DBCStatistics getStatistics() {
        return new DBCStatistics();
    }

    static void startFetchWorkflow(
        @NotNull DBDDataReceiver dataReceiver,
        @NotNull DBCSession session,
        @NotNull DBCResultSet resultSet,
        long offset,
        long maxRows
    ) throws DBException {
        try {
            dataReceiver.fetchStart(session, resultSet, offset, maxRows);
        } catch (Throwable e) {
            // The close below is bound to the statement only after a successful start. A receiver may have opened
            // resources in fetchStart before it failed (the data transfer's load connection and its transaction)
            try {
                dataReceiver.close();
            } catch (Throwable closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        resultSet.getSourceStatement().autoCloseDependant(() -> {
            try (dataReceiver) {
                dataReceiver.fetchEnd(session, resultSet);
            } catch (DBCException e) {
                throw new DBRuntimeException("Error while finishing result set fetching into '" + dataReceiver + "'", e);
            }
        });
    }

    static void fetchRowsWithStatistics(
        @NotNull DBDDataReceiver dataReceiver,
        @NotNull DBCSession session,
        @NotNull DBCResultSet resultSet,
        @NotNull DBCStatistics statistics
    ) throws DBException {
        DBRProgressMonitor progressMonitor = session.getProgressMonitor();
        DBFetchProgress fetchProgress = new DBFetchProgress(progressMonitor);

        while (!progressMonitor.isCanceled() && resultSet.nextRow()) {
            dataReceiver.fetchRow(session, resultSet);
            fetchProgress.monitorRowFetch();
        }
        fetchProgress.dumpStatistics(statistics);
    }


}
