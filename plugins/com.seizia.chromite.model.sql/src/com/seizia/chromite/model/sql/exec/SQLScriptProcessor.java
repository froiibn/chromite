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
package com.seizia.chromite.model.sql.exec;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBFetchProgress;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.exec.*;
import com.seizia.chromite.model.impl.AbstractExecutionSource;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.*;
import com.seizia.chromite.model.sql.data.SQLQueryDataContainer;
import com.seizia.chromite.utils.DurationFormat;
import com.seizia.chromite.utils.DurationFormatter;
import com.seizia.chromite.utils.RuntimeUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLScriptProcessor
 *
 * 변경 이력:
 * - 2026-11-08 {@code @include}가 돌려준 {@link SQLIncludedScript}의 문장을 그 파일의 스크립트 문맥으로 실행한다. 바깥 문맥으로 실행하면
 *   안에서 다시 포함하는 상대 경로의 기준과 포함 고리 검사가 틀린다 (docs/chromite/swing-query-commands.md "@include")
 * - 2026-11-08 배치 오류: 여러 문장 배치의 뒤 문장 오류({@code getMoreResults} 예외)를 로그에만 쓰고 넘어가서 작업이 성공으로 끝나던 것을
 *   고쳤다. 오류는 그 쿼리의 실패가 되고(오류 처리 설정대로 멈춤), 오류 뒤 결과와 첫 문장 오류 뒤에 실행된 문장의 결과도 읽는다
 *   ({@link SQLBatchErrors}, docs/chromite/sql-batch-errors.md)
 * - 2026-11-12 연결이 끊긴 뒤 모든 문장을 다시 실행하던 복구를 SQL 편집기와 같은 허용 목록으로({@link SQLQueryRecovery}, 앱 변경 #86):
 *   읽기만 하는 SELECT 하나만 다시 실행한다 (docs/chromite/transactions.md "연결이 끊긴 쿼리")
 */
public class SQLScriptProcessor {
    private static final String STAT_LOG_PREFIX = "-----------------> ";

    private final DBCExecutionContext executionContext;
    private final List<SQLScriptElement> queries;
    // The context of the running statement: an included file's context while its statements run
    private SQLScriptContext scriptContext;
    private final DBDDataReceiver dataReceiver;
    private final Log log;

    private Throwable lastError = null;
    // Statement errors of the running query's batch that the driver reported while the results were read (SQLBatchErrors)
    private final List<Throwable> statementErrors = new ArrayList<>();

    private DBCStatistics statistics;
    private final DBCStatistics totalStatistics = new DBCStatistics();

    private int fetchSize;
    private long offset;
    private long maxRows;
    private long fetchFlags;
    private SQLScriptCommitType commitType = SQLScriptCommitType.AUTOCOMMIT;
    private SQLScriptErrorHandling errorHandling = SQLScriptErrorHandling.STOP_ROLLBACK;

    public SQLScriptProcessor(
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<SQLScriptElement> queries,
        @NotNull SQLScriptContext scriptContext,
        @NotNull DBDDataReceiver dataReceiver,
        @NotNull Log log) {
        this.executionContext = executionContext;
        this.queries = queries;
        this.scriptContext = scriptContext;
        this.dataReceiver = dataReceiver;
        this.log = log;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public void setMaxRows(long maxRows) {
        this.maxRows = maxRows;
    }

    public void setFetchFlags(long fetchFlags) {
        this.fetchFlags = fetchFlags;
    }

    public SQLScriptCommitType getCommitType() {
        return commitType;
    }

    public void setCommitType(SQLScriptCommitType commitType) {
        this.commitType = commitType;
    }

    public SQLScriptErrorHandling getErrorHandling() {
        return errorHandling;
    }

    public void setErrorHandling(SQLScriptErrorHandling errorHandling) {
        this.errorHandling = errorHandling;
    }

    public void runScript(DBRProgressMonitor monitor) throws DBCException {
        RuntimeUtils.setThreadName("SQL script execution");
        statistics = new DBCStatistics();
        try {
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(executionContext);
            try (DBCSession session = executionContext.openSession(monitor, DBCExecutionPurpose.USER_SCRIPT, "SQL Query")) {
                // Set transaction settings (only if autocommit is off)
                if (session.isLoggingEnabled()) {
                    QMUtils.getDefaultHandler().handleScriptBegin(session);
                }

                boolean oldAutoCommit = txnManager == null || txnManager.isAutoCommit();
                boolean newAutoCommit = (commitType == SQLScriptCommitType.AUTOCOMMIT);
                if (txnManager != null && txnManager.isSupportsTransactions() && oldAutoCommit != newAutoCommit) {
                    txnManager.setAutoCommit(monitor, newAutoCommit);
                }

                monitor.beginTask("Execute queries (" + queries.size() + ")", queries.size());
                executeScript(session, queries, true);
                monitor.done();

                // Commit data
                if (txnManager != null && txnManager.isSupportsTransactions() && !oldAutoCommit && commitType != SQLScriptCommitType.AUTOCOMMIT) {
                    monitor.beginTask("Finish transaction", 1);
                    if (lastError == null || errorHandling == SQLScriptErrorHandling.STOP_COMMIT) {
                        if (commitType != SQLScriptCommitType.NO_COMMIT) {
                            monitor.subTask("Commit");
                            txnManager.commit(session);
                        }
                    } else if (errorHandling == SQLScriptErrorHandling.STOP_ROLLBACK) {
                        monitor.subTask("Rollback");
                        txnManager.rollback(session, null);
                    } else {
                        // Just ignore error
                        monitor.subTask("Script executed with errors. Changes were not committed.");
                    }
                    monitor.done();
                }

                // Restore transactions settings
                if (txnManager != null && txnManager.isSupportsTransactions() && oldAutoCommit != newAutoCommit) {
                    txnManager.setAutoCommit(monitor, oldAutoCommit);
                }
                if (session.isLoggingEnabled()) {
                    QMUtils.getDefaultHandler().handleScriptEnd(session);
                }
            }
        } catch (Throwable ex) {
            throw new DBCException("Error during SQL script execution", ex);
        }

        if (lastError != null && errorHandling != SQLScriptErrorHandling.IGNORE) {
            throw new DBCException("Script execute failed", lastError);
        }
    }

    private void executeScript(
        @NotNull DBCSession session,
        @NotNull List<SQLScriptElement> script,
        boolean trackMonitor
    ) {
        for (SQLScriptElement query : script) {
            if (session.getProgressMonitor().isCanceled()) {
                break;
            }
            // Execute query
            boolean runNext = executeSingleQuery(session, query);
            if (!runNext) {
                if (lastError == null) {
                    // Execution cancel
                    break;
                }
                if (errorHandling != SQLScriptErrorHandling.IGNORE) {
                    if (!statementErrors.contains(lastError)) {
                        // A collected statement error is already in the log
                        log.error(lastError);
                    }
                    break;
                } else {
                    log.warn("Query failed: " + lastError.getMessage());
                }
            }
            if (trackMonitor) {
                session.getProgressMonitor().worked(1);
            }
        }
    }

    private boolean executeSingleQuery(@NotNull DBCSession session, @NotNull SQLScriptElement element) {
        if (element instanceof SQLControlCommand controlCommand) {
            log.debug(STAT_LOG_PREFIX + "Execute command\n" + element.getText());
            try {
                SQLControlResult controlResult = scriptContext.executeControlCommand(session.getProgressMonitor(), controlCommand);
                if (controlResult.getTransformed() != null) {
                    element = controlResult.getTransformed();
                } else {
                    return true;
                }
            } catch (Throwable e) {
                if (!(e instanceof DBException)) {
                    log.error("Unexpected error while processing SQL command", e);
                }
                lastError = e;
                return false;
            }
        }
        if (element instanceof SQLIncludedScript includedScript) {
            SQLScriptContext outerContext = scriptContext;
            scriptContext = includedScript.getScriptContext();
            try {
                this.executeScript(session, includedScript.getScriptElements(), false);
            } finally {
                scriptContext = outerContext;
            }
        } else if (element instanceof SQLScript script) {
            this.executeScript(session, script.getScriptElements(), false);
        } else if (!(element instanceof SQLQuery sqlQuery)) {
            log.error("Unsupported SQL element type: " + element);
            return false;
        } else {
            scriptContext.fillQueryParameters(sqlQuery, () -> dataReceiver, true);
            lastError = null;
            statementErrors.clear();

            try {
                statistics.reset();
                statistics.setQueryText(sqlQuery.getText());

                // After a lost connection only a read is run again (the same rule as the SQL editor)
                SQLQueryRecovery.execute(session, sqlQuery, param -> {
                    long execStartTime = System.currentTimeMillis();
                    executeStatement(session, sqlQuery, execStartTime);
                }, () -> log.debug(STAT_LOG_PREFIX + "Connection lost: reconnected and run again (it only reads)\n" + sqlQuery.getText()));
                if (!statementErrors.isEmpty()) {
                    // The batch failed although the statement did not throw
                    lastError = statementErrors.getFirst();
                }
            } catch (Throwable ex) {
                if (!(ex instanceof DBException)) {
                    log.error("Unexpected error while processing SQL", ex);
                }
                lastError = ex;
            } finally {
                // Only now that the results are read: logging walks the driver's error chain (SQLBatchErrors.canReadPast)
                for (Throwable error : statementErrors) {
                    log.error(error);
                }
                scriptContext.clearStatementContext();
            }
        }

        return lastError == null || errorHandling == SQLScriptErrorHandling.IGNORE;
    }

    private void executeStatement(@NotNull DBCSession session, SQLQuery sqlQuery, long startTime) throws DBException {
        SQLQueryDataContainer dataContainer = new SQLQueryDataContainer(() -> executionContext, sqlQuery, scriptContext, log);
        DBCExecutionSource source = new AbstractExecutionSource(dataContainer, session.getExecutionContext(), this, sqlQuery);
        final DBCStatement statement = DBUtils.makeStatement(
            source,
            session,
            DBCStatementType.SCRIPT,
            sqlQuery,
            offset,
            maxRows
        );
        DBExecUtils.setStatementFetchSize(statement, 0, maxRows, fetchSize);

        // Execute statement
        try {
            log.debug(STAT_LOG_PREFIX + "Execute query\n" + sqlQuery.getText());
            boolean hasResultSet;
            try {
                hasResultSet = statement.executeStatement();
            } catch (DBCException e) {
                // The first statement of a batch failed and the later ones may still have run (SQL Server)
                hasResultSet = SQLBatchErrors.resumeAfterExecuteError(session, statement, e, statementErrors::add);
            }

            statistics.addExecuteTime(System.currentTimeMillis() - startTime);
            statistics.addStatementsCount();

            long updateCount = -1;
            while (true) {
                // Fetch data only if we have to fetch all results or if it is rs requested
                {
                    if (hasResultSet) {
                        DBCResultSet resultSet = statement.openResultSet();
                        if (resultSet == null) {
                            // Kind of bug in the driver. It says it has resultset but returns null
                            break;
                        } else {
                            try {
                                hasResultSet = fetchQueryData(session, resultSet, dataReceiver);
                            } catch (DBCException e) {
                                // A statement of the batch failed while its rows were read: the later results are still read
                                if (!SQLBatchErrors.canReadPast(session, e)) {
                                    throw e;
                                }
                                statementErrors.add(e);
                            }
                        }
                    }
                }
                if (!hasResultSet) {
                    try {
                        updateCount = statement.getUpdateRowCount();
                        if (updateCount >= 0) {
                            statistics.addRowsUpdated(updateCount);
                        }
                    } catch (DBCException e) {
                        // In some cases we can't read update count
                        // This is bad but we can live with it
                        // Just print a warning
                        log.warn("Can't obtain update count", e);
                    }
                }
                if (!hasResultSet && updateCount < 0) {
                    // Nothing else to fetch
                    break;
                }

                if (session.getDataSource().getInfo().supportsMultipleResults()) {
                    // An error of a later statement of the batch fails the query; the results after it are still read
                    hasResultSet = SQLBatchErrors.nextResults(session, statement, statementErrors::add);
                    updateCount = hasResultSet ? -1 : 0;
                } else {
                    break;
                }
            }
        } finally {
            try {
                Throwable[] warnings = statement.getStatementWarnings();
                if (warnings != null) {
                    for (Throwable warning : warnings) {
                        scriptContext.getOutputWriter().println(null, warning.getMessage());
                    }
                }
            } catch (Throwable e) {
                log.warn("Can't read execution warnings", e);
            }
            try {
                statement.close();
            } catch (Throwable e) {
                log.error("Error closing statement", e);
            }
            String duration = DurationFormatter.format(Duration.ofMillis(statistics.getExecuteTime()), DurationFormat.MEDIUM);
            log.debug(STAT_LOG_PREFIX + "Time: " + duration +
                (statistics.getRowsFetched() >= 0 ? ", fetched " + statistics.getRowsFetched() + " row(s)" : "") +
                (statistics.getRowsUpdated() >= 0 ? ", updated " + statistics.getRowsUpdated() + " row(s)" : ""));

            totalStatistics.accumulate(statistics);
        }
    }

    private boolean fetchQueryData(DBCSession session, DBCResultSet resultSet, DBDDataReceiver dataReceiver) throws DBException {
        if (dataReceiver == null) {
            // No data pump - skip fetching stage
            return false;
        }
        if (resultSet == null) {
            return false;
        }
        DBRProgressMonitor monitor = session.getProgressMonitor();
        monitor.subTask("Fetch result set");
        DBFetchProgress fetchProgress = new DBFetchProgress(session.getProgressMonitor());

        DBDDataReceiver.startFetchWorkflow(dataReceiver, session, resultSet, 0, 0);

        try (resultSet) {
            long fetchStartTime = System.currentTimeMillis();

            // Fetch all rows
            while (!fetchProgress.isCanceled() && resultSet.nextRow()) {
                dataReceiver.fetchRow(session, resultSet);
                fetchProgress.monitorRowFetch();
            }
            statistics.addFetchTime(System.currentTimeMillis() - fetchStartTime);
        }

        statistics.setRowsFetched(fetchProgress.getRowCount());
        monitor.subTask(fetchProgress.getRowCount() + " rows fetched");

        return true;
    }

    public DBCStatistics getTotalStatistics() {
        return totalStatistics;
    }

}
