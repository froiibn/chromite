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
package com.seizia.chromite.model.sql.exec;

import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.update.Update;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.*;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.exec.*;
import com.seizia.chromite.model.impl.AbstractExecutionSource;
import com.seizia.chromite.model.impl.AbstractStatement;
import com.seizia.chromite.model.impl.local.LocalStatement;
import com.seizia.chromite.model.impl.local.StatResultSet;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableParametrized;
import com.seizia.chromite.model.sql.*;
import com.seizia.chromite.model.sql.data.SQLQueryDataContainer;
import com.seizia.chromite.model.sql.parser.SQLSemanticProcessor;
import com.seizia.chromite.model.sql.registry.SQLCommandHandlerDescriptor;
import com.seizia.chromite.model.sql.registry.SQLCommandsRegistry;
import com.seizia.chromite.model.sql.registry.SQLPragmaHandlerDescriptor;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.runtime.jobs.DataSourceJob;
import com.seizia.chromite.runtime.ui.DBPPlatformUI;
import com.seizia.chromite.utils.GeneralUtils;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes SQL script elements in one session: control commands, pragmas, parameter binding, statement execution,
 * multiple results fetching, transaction handling, error handling and statistics.
 * Results are passed to {@link DBDDataReceiver}s and query events to {@link SQLQueryListener}.
 *
 * 설계: SQL 편집기의 실행 로직은 SWT 플러그인의 SQLQueryJob(ui.editors.sql)에 있었다.
 * Swing 편집기가 같은 로직을 쓰도록 UI와 무관한 부분을 그대로 이 클래스로 옮기고,
 * UI가 개입하던 지점(결과 수신기, 대화상자, 편집기 설정값)은 {@link SQLQueryExecuteHandler}로 넘겼다.
 * SQLQueryJob은 이 클래스를 상속해 SWT 구현을 넘기므로 공개 API와 동작이 그대로다.
 * 이미 있는 SQLScriptProcessor(model.sql)는 결과 수신기 하나, 쿼리 이벤트 없음, 행 수 제한 없음이라
 * 편집기 결과 창(결과 집합별 수신기, 쿼리별 결과·경고)에 쓸 수 없어 이 클래스를 따로 두었다.
 * Job이므로 schedule()로 백그라운드 실행하거나, 이미 작업 스레드에 있으면 runDirectly(monitor)로 동기 실행한다.
 * 로거는 실제 클래스 이름을 쓴다(SQLQueryJob 로그 이름 유지).
 *
 * 변경 이력:
 * - 2026-11-06 SQLQueryJob(ui.editors.sql)에서 실행 로직을 옮겨 만듦(docs/chromite/swing-frontend.md "쿼리 실행 계층").
 * - 2026-11-06 Query Library: 핸들러가 값을 바인딩하는 쿼리({@link SQLQueryExecuteHandler#hasBoundParameters})는
 *   스크립트 문장 대신 prepared statement로 만들고 값을 바인딩한다 (docs/chromite/swing-query-library.md "매개변수 바인딩").
 * - 2026-11-06 매개변수 대화상자·실행 확인에서 사용자가 멈춘 실행을 {@link #isCanceledByUser}로 알린다. 그런 실행은 오류 없이
 *   OK 상태로 끝나서 Swing 편집기가 "성공"으로 보였다. Job 상태는 SWT 동작 그대로 둔다 (docs/chromite/swing-sql-editor-plus.md "매개변수").
 * - 2026-11-08 {@code @include}: 명령이 돌려준 {@link SQLIncludedScript}의 문장을 남은 문장 앞에 끼워 같은 루프로 실행한다(오류 처리·취소·
 *   진행·통계가 스크립트 자신의 문장과 같다). 끼운 문장은 그 파일의 스크립트 문맥으로 실행한다(매개변수, 명령, 중첩 포함의 기준 폴더).
 *   다시 실행하면 처음 받은 문장부터 다시 읽는다 (docs/chromite/swing-query-commands.md "@include").
 * - 2026-11-08 문장이 오류 없이 끝나고 결과를 다 읽은 뒤, 닫기 전에 {@link SQLQueryExecuteHandler#afterStatementExecuted}를 부른다
 *   (Altibase 실제 계획, docs/chromite/swing-sql-editor-plus.md "실제 계획 토글을 Altibase·Oracle로").
 */
public class SQLQueryExecuteJob extends DataSourceJob {
    private static final String PLUGIN_ID = "com.seizia.chromite.model.sql";

    private final Log log = Log.getLog(getClass());

    public static final Object STATS_RESULTS = new Object();
    private static final int MAX_UPDATE_COUNT_READS = 1000;

    private final DBSDataContainer dataContainer;
    // The elements the job was created with; queries also has the statements of included files after their @include
    private final List<SQLScriptElement> scriptQueries;
    private final List<SQLScriptElement> queries;
    private final SQLScriptContext scriptContext;
    // The script context of each statement of an included file (the others run in scriptContext)
    private final Map<SQLScriptElement, SQLScriptContext> includedContexts = new IdentityHashMap<>();
    // The script context of the element that runs now (job thread)
    private SQLScriptContext currentScriptContext;
    private final SQLQueryListener listener;
    private final SQLQueryExecuteHandler handler;

    private DBDDataFilter dataFilter;
    private boolean connectionInvalidated = false;

    private SQLScriptCommitType commitType = SQLScriptCommitType.NO_COMMIT;
    private SQLScriptErrorHandling errorHandling = SQLScriptErrorHandling.STOP_ROLLBACK;
    private boolean fetchResultSets = true;
    private long rsOffset;
    private long rsMaxRows;

    private DBCStatement curStatement;
    private final List<DBCResultSet> curResultSets = new ArrayList<>();
    private Throwable lastError = null;

    private DBCStatistics statistics;
    private int fetchResultSetNumber;
    private int resultSetNumber;
    private SQLScriptElement lastGoodQuery;
    private int queryNum = 0;

    private boolean skipConfirmation;
    /**
     * The user stopped the script in a dialog of the run (parameter values, execution confirmation). The job still
     * ends with an OK status, as it always did for the SWT editor, so front ends that report the outcome read this.
     */
    private volatile boolean canceledByUser;
    private int fetchSize;
    private long fetchFlags;
    private SQLQueryResult curResult;

    private transient int rowsFetched;

    /**
     * Commit type and error handling default to the SQL editor preference defaults
     * ({@link SQLScriptCommitType#NO_COMMIT}, {@link SQLScriptErrorHandling#STOP_ROLLBACK}),
     * all result sets are fetched, and the row limit is {@link ModelPreferences#RESULT_SET_MAX_ROWS}
     * of the connection.
     *
     * @param dataContainer data container of the execution source, may be null
     * @param queries       script elements to execute in order
     * @param listener      query and script events, may be null
     * @param handler       result receivers, user interaction and editor settings
     */
    public SQLQueryExecuteJob(
        @NotNull String name,
        @NotNull DBCExecutionContext executionContext,
        @Nullable DBSDataContainer dataContainer,
        @NotNull List<SQLScriptElement> queries,
        @NotNull SQLScriptContext scriptContext,
        @Nullable SQLQueryListener listener,
        @NotNull SQLQueryExecuteHandler handler
    ) {
        super(name, executionContext);
        this.dataContainer = dataContainer;
        this.scriptQueries = List.copyOf(queries);
        this.queries = new ArrayList<>(queries);
        this.scriptContext = scriptContext;
        this.currentScriptContext = scriptContext;
        this.listener = listener;
        this.handler = handler;
        this.rsMaxRows = getDataSourceContainer().getPreferenceStore().getInt(ModelPreferences.RESULT_SET_MAX_ROWS);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " (" + queries + ")";
    }

    public void setCommitType(@NotNull SQLScriptCommitType commitType) {
        this.commitType = commitType;
    }

    public void setErrorHandling(@NotNull SQLScriptErrorHandling errorHandling) {
        this.errorHandling = errorHandling;
    }

    public void setFetchResultSets(boolean fetchResultSets)
    {
        this.fetchResultSets = fetchResultSets;
    }

    public SQLScriptElement getLastQuery()
    {
        return queries.isEmpty() ? null : queries.getFirst();
    }

    public SQLScriptElement getLastGoodQuery() {
        return lastGoodQuery;
    }

    public DBCStatement getCurrentStatement() {
        return curStatement;
    }

    public SQLQueryResult getCurrentQueryResult() {
        return curResult;
    }

    /**
     * The script context of the element that runs now: the job's context, or the context of an included file
     * ({@link SQLIncludedScript#getScriptContext()}) while its statements run. For query events, in the job thread.
     */
    @NotNull
    public SQLScriptContext getCurrentScriptContext() {
        return currentScriptContext;
    }

    /**
     * Whether the last run was stopped by the user in a dialog (parameter values or execution confirmation canceled).
     * Such a run ends without an error and with an OK job status; a canceled progress monitor is reported by the
     * job status instead.
     */
    public boolean isCanceledByUser() {
        return canceledByUser;
    }

    private boolean hasLimits()
    {
        return rsOffset >= 0 && rsMaxRows > 0;
    }

    public void setResultSetLimit(long offset, long maxRows) {
        this.rsOffset = offset;
        this.rsMaxRows = maxRows;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }


    public void setFetchFlags(long fetchFlags) {
        this.fetchFlags = fetchFlags;
    }

    /**
     * Plugin ID of the status returned by {@link #run(DBRProgressMonitor)}.
     */
    @NotNull
    protected String getStatusPluginId() {
        return PLUGIN_ID;
    }

    @NotNull
    @Override
    protected IStatus run(@NotNull DBRProgressMonitor monitor)
    {
        RuntimeUtils.setThreadName("SQL script execution");
        statistics = new DBCStatistics();
        skipConfirmation = false;
        canceledByUser = false;
        if (queryNum == queries.size()) {
            queryNum = 0;
            // A new run of the whole script: its @include commands read their files again
            queries.clear();
            queries.addAll(scriptQueries);
            includedContexts.clear();
        }
        monitor.beginTask("Execute SQL script", queries.size());
        try {
            DBCExecutionContext context = getExecutionContext();
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
            DBCExecutionPurpose purpose = queries.size() > 1 ? DBCExecutionPurpose.USER_SCRIPT : DBCExecutionPurpose.USER;
            try (DBCSession session = context.openSession(monitor, purpose, "SQL Query")) {
                // Set transaction settings (only if autocommit is off)
                if (session.isLoggingEnabled()) {
                    QMUtils.getDefaultHandler().handleScriptBegin(session);
                }

                boolean oldAutoCommit = txnManager == null || txnManager.isAutoCommit();
                boolean newAutoCommit = (commitType == SQLScriptCommitType.AUTOCOMMIT);
                if (txnManager != null && !oldAutoCommit && newAutoCommit) {
                    txnManager.setAutoCommit(monitor, true);
                }

                monitor.beginTask(this.getName(), queries.size());

                // Notify job start
                if (listener != null) {
                    try {
                        listener.onStartScript();
                    } catch (Exception e) {
                        log.error(e);
                    }
                }

                resultSetNumber = 0;
                while (queryNum < queries.size()) {
                    // Execute query
                    SQLScriptElement query = queries.get(queryNum);

                    fetchResultSetNumber = resultSetNumber;
                    boolean runNext = executeSingleQuery(session, query, true);

                    if (txnManager != null && txnManager.isSupportsTransactions()
                        && !oldAutoCommit && commitType != SQLScriptCommitType.AUTOCOMMIT
                        && query instanceof SQLQuery sqlQuery
                    ) {
                        handleTransactionStatements(txnManager, session, sqlQuery);
                    }
                    if (!runNext) {
                        if (lastError == null) {
                            // Execution cancel
                            break;
                        }
                        // Ask to continue
                        log.error(lastError);
                        boolean isQueue = isQueue();
                        DBPPlatformUI.UserResponse response = handler.handleExecutionError(
                            (isQueue ? "SQL script execution" : "SQL query execution") +
                                "\n" +  GeneralUtils.getFirstMessage(lastError),
                            lastError,
                            isQueue);

                        boolean stopScript = false;
                        boolean tryAgain = false;
                        switch (response) {
                            case STOP:
                                // just stop execution
                                stopScript = true;
                                break;
                            case RETRY:
                                // just make it again
                                tryAgain = true;
                                break;
                            case IGNORE:
                                // ignore error
                                lastError = null;
                                break;
                            case IGNORE_ALL:
                                lastError = null;
                                errorHandling = SQLScriptErrorHandling.IGNORE;
                                break;
                        }

                        if (stopScript) {
                            break;
                        } else if (tryAgain) {
                            RuntimeUtils.pause(100);
                            continue;
                        }
                    }

                    // Check monitor
                    if (monitor.isCanceled()) {
                        break;
                    }
                    monitor.worked(1);
                    queryNum++;
                }
                if (statistics.getStatementsCount() > 0) {
                    showExecutionResult(session);
                }
                monitor.done();

                // Commit data
                if (txnManager != null && txnManager.isSupportsTransactions() && !oldAutoCommit && commitType != SQLScriptCommitType.AUTOCOMMIT) {
                    if (lastError == null || errorHandling == SQLScriptErrorHandling.STOP_COMMIT) {
                        if (commitType != SQLScriptCommitType.NO_COMMIT) {
                            monitor.beginTask("Commit data", 1);
                            txnManager.commit(session);
                            monitor.done();
                        }
                    } else if (errorHandling == SQLScriptErrorHandling.STOP_ROLLBACK) {
                        monitor.beginTask("Rollback data", 1);
                        txnManager.rollback(session, null);
                        monitor.done();
                    } else {
                        // Just ignore error
                        log.info("Script executed with errors. Changes were not committed.");
                    }
                }

                // Restore transactions settings
                if (txnManager != null && !oldAutoCommit && newAutoCommit) {
                    txnManager.setAutoCommit(monitor, false);
                }
                if (session.isLoggingEnabled()) {
                    QMUtils.getDefaultHandler().handleScriptEnd(session);
                }

                if (listener != null) {
                    listener.onEndSqlJob(session, getSqlJobResult());
                }

                // Return success
                return new Status(
                    Status.OK,
                    getStatusPluginId(),
                    "SQL job completed");
            }
        }
        catch (Throwable ex) {
            return new Status(
                Status.ERROR,
                getStatusPluginId(),
                "Error during SQL job execution: " + ex.getMessage());
        }
        finally {
            monitor.done();

            // Notify job end
            if (listener != null) {
                try {
                    listener.onEndScript(statistics, lastError != null);
                } catch (Exception e) {
                    log.error(e);
                }
            }
        }
    }

    private boolean isQueue() {
        return handler.isIncludedScript() || queryNum < queries.size() - 1;
    }

    @NotNull
    private SqlJobResult getSqlJobResult() {
        if (queries.get(queries.size() - 1) == lastGoodQuery && lastError == null) {
            return SqlJobResult.SUCCESS;
        } else if (lastGoodQuery != null) {
            return SqlJobResult.PARTIAL_SUCCESS;
        } else {
            return SqlJobResult.FAILURE;
        }
    }

    protected void handleTransactionStatements(
        @NotNull DBCTransactionManager txnManager,
        @NotNull DBCSession session,
        @NotNull SQLQuery query
    ) throws DBCException {
        if (query.getType().equals(SQLQueryType.COMMIT)) {
            txnManager.commit(session);
        } else if (query.getType().equals(SQLQueryType.ROLLBACK)) {
            txnManager.rollback(session, null);
        }
    }

    private boolean executeSingleQuery(
        @NotNull DBCSession session,
        @NotNull SQLScriptElement element,
        final boolean fireEvents
    ) {
        final SQLScriptContext elementContext = includedContexts.getOrDefault(element, scriptContext);
        currentScriptContext = elementContext;
        if (!elementContext.getPragmas().isEmpty() && element instanceof SQLQuery query) {
            final SQLQueryDataContainer container = new SQLQueryDataContainer(this::getExecutionContext, query, elementContext, log);

            for (var it = elementContext.getPragmas().entrySet().iterator(); it.hasNext(); ) {
                final Map.Entry<String, Map<String, Object>> entry = it.next();
                final String id = entry.getKey();
                final SQLPragmaHandlerDescriptor descriptor = SQLCommandsRegistry.getInstance().getPragmaHandler(id);

                if (descriptor != null) {
                    final int result;

                    try {
                        result = descriptor.createHandler().processPragma(session.getProgressMonitor(), container, entry.getValue());
                    } catch (DBException e) {
                        lastError = e;
                        return false;
                    }

                    if (CommonUtils.isBitSet(result, SQLPragmaHandler.RESULT_CONSUME_PRAGMA)) {
                        it.remove();
                    }

                    if (CommonUtils.isBitSet(result, SQLPragmaHandler.RESULT_CONSUME_QUERY)) {
                        return false;
                    }
                }
            }
        }
        if (element instanceof SQLControlCommand controlCommand) {
            try {
                SQLCommandHandlerDescriptor descriptor = SQLCommandsRegistry.getInstance().getCommandHandler(controlCommand.getCommandId());
                if (descriptor != null && descriptor.isInteractive()) {
                    controlCommand.setData(listener);
                }
                SQLControlResult controlResult = elementContext.executeControlCommand(session.getProgressMonitor(), controlCommand);
                if (controlResult.getTransformed() instanceof SQLIncludedScript includedScript) {
                    boolean included = includeScript(includedScript);
                    if (included) {
                        lastGoodQuery = element;
                    }
                    return included;
                } else if (controlResult.getTransformed() != null) {
                    element = controlResult.getTransformed();
                } else {
                    if (controlResult.isSuccess()) {
                        lastGoodQuery = element;
                    }
                    return controlResult.isSuccess();
                }
            } catch (Throwable e) {
                if (!(e instanceof DBException)) {
                    log.error("Unexpected error while processing SQL command", e);
                }
                lastGoodQuery = element;
                lastError = e;
                return false;
            } finally {
                if (element instanceof SQLControlCommand finalCommand) {
                    statistics.addStatementsCount();
                    statistics.addMessage("Command " + finalCommand.getCommand() + " processed");
                }
            }
        }
        if (!(element instanceof SQLQuery sqlQuery)) {
            log.error("Unsupported SQL element type: " + element);
            return false;
        }

        // Query may be a nested script
        List<SQLScriptElement> nestedElements = sqlQuery.getScriptElements();

        for (SQLScriptElement nestedElement : nestedElements) {
            if (!(nestedElement instanceof SQLQuery nestedQuery)) {
                log.error("Unsupported SQL element type: " + element);
                return false;
            }

            if (!executeSingleElement(session, fireEvents, nestedQuery, elementContext)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Puts the statements of an included file before the rest of the script, to run in the included file's context.
     *
     * @return false if the front end stopped the script
     */
    private boolean includeScript(@NotNull SQLIncludedScript script) {
        if (!handler.confirmIncludedScript(script)) {
            canceledByUser = true;
            return false;
        }
        List<SQLScriptElement> elements = script.getScriptElements();
        for (SQLScriptElement element : elements) {
            includedContexts.put(element, script.getScriptContext());
        }
        queries.addAll(queryNum + 1, elements);
        return true;
    }

    private boolean executeSingleElement(
        @NotNull DBCSession session,
        boolean fireEvents,
        SQLQuery sqlQuery,
        @NotNull SQLScriptContext elementContext
    ) {
        lastError = null;

        if (!skipConfirmation && getDataSourceContainer().getConnectionConfiguration().getConnectionType().isConfirmExecute()) {
            // Validate all transactional queries
            if (!SQLSemanticProcessor.isSelectQuery(session.getDataSource().getSQLDialect(), sqlQuery.getText())) {

                SQLQueryExecuteHandler.QueryConfirmation confirmResult = handler.confirmQueryExecution(sqlQuery, queries.size() > 1);
                switch (confirmResult) {
                    case NO:
                        return true;
                    case YES:
                        break;
                    case YES_TO_ALL:
                        skipConfirmation = true;
                        break;
                    default:
                        canceledByUser = true;
                        return false;
                }
            }
        }

        final DBCExecutionContext executionContext = getExecutionContext();
        final DBPDataSource dataSource = executionContext.getDataSource();

        final SQLQuery originalQuery = sqlQuery;

        DBRProgressMonitor monitor = session.getProgressMonitor();
        monitor.beginTask("Get data receiver", 1);
        monitor.subTask("Create results view");
        if (!elementContext.fillQueryParameters(
            originalQuery,
            () -> handler.getDataReceiver(originalQuery, resultSetNumber),
            CommonUtils.isBitSet(fetchFlags, DBSDataContainer.FLAG_REFRESH)
        )) {
            // User canceled
            canceledByUser = true;
            return false;
        }
        monitor.done();

        long startTime = System.currentTimeMillis();
        boolean startQueryAlerted = false;
        boolean executionCanceled = false;

        // Modify query (filters + parameters)
        String queryText = originalQuery.getText();//.trim();
        if (dataFilter != null && dataFilter.hasFilters()) {
            String filteredQueryText;
            try {
                filteredQueryText = dataSource.getSQLDialect().addFiltersToQuery(
                    session.getProgressMonitor(),
                    dataSource,
                    queryText,
                    dataFilter
                );
            } catch (DBException e) {
                log.error("Unable to add filters to query", e);
                lastError = e;
                return false;
            }
            sqlQuery = new SQLQuery(executionContext.getDataSource(), filteredQueryText, sqlQuery);
        } else {
            sqlQuery = new SQLQuery(executionContext.getDataSource(), queryText, sqlQuery);
        }

        curResult = new SQLQueryResult(sqlQuery);
        if (rsOffset > 0) {
            curResult.setRowOffset(rsOffset);
        }

        monitor.beginTask("Process query", 1);
        monitor.subTask("Initialize context");
        try {
            // Prepare statement
            closeStatement();

            // Check and invalidate connection
            if (!connectionInvalidated && handler.isInvalidateBeforeExecute()) {
                executionContext.invalidateContext(monitor);
                connectionInvalidated = true;
            }

            statistics.setQueryText(sqlQuery.getText());

            // Notify query start
            if (fireEvents && listener != null) {
                // Notify query start
                try {
                    listener.onStartQuery(session, sqlQuery);
                } catch (Exception e) {
                    log.error(e);
                }
                startQueryAlerted = true;
            }

            monitor.subTask("Execute query");
            startTime = System.currentTimeMillis();

            SQLQuery execStatement = sqlQuery;
            DBRRunnableParametrized<DBCSession> executor = param -> {
                boolean changedToManualCommit = false;
                try {
                    // We can't reset statistics here (we can be in script mode)
                    //statistics.setStatementsCount(0);
                    //statistics.setExecuteTime(0);
                    //statistics.setFetchTime(0);
                    //statistics.setRowsUpdated(0);

                    // Toggle smart commit mode
                    if (handler.isSmartAutoCommit()) {
                        changedToManualCommit = DBExecUtils.checkSmartAutoCommit(session, execStatement.getText());
                    }
                    long execStartTime = System.currentTimeMillis();
                    executeStatement(session, execStatement, execStartTime, curResult, elementContext);
                } catch (Throwable e) {
                    // We just switched to manual mode, there is no other statements in transaction
                    // let's return back to auto commit
                    if (changedToManualCommit) {
                        try {
                            DBCTransactionManager transactionManager = DBUtils.getTransactionManager(session.getExecutionContext());
                            if (transactionManager != null) {
                                transactionManager.setAutoCommit(monitor, true);
                            }
                        } catch (DBCException ex) {
                            log.warn("Error returning to auto commit");
                        }
                    }
                    throw new InvocationTargetException(e);
                }
            };

            if (shouldRecoverQuery(execStatement)) {
                DBExecUtils.tryExecuteRecover(session, session.getDataSource(), executor);
            } else {
                try {
                    executor.run(session);
                } catch (InvocationTargetException e) {
                    throw e.getTargetException();
                }
            }
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(session.getExecutionContext());
            if (txnManager != null && txnManager.isSupportsTransactions()
                && !txnManager.isAutoCommit() && commitType != SQLScriptCommitType.AUTOCOMMIT
            ) {
                handleTransactionStatements(txnManager, session, sqlQuery);
            }
        }
        catch (Throwable ex) {
            if (DBExecUtils.isExecutionCanceled(dataSource, ex) || monitor.isCanceled()) {
                executionCanceled = true;
            } else if (!(ex instanceof DBException)) {
                log.error("Unexpected error while processing SQL", ex);
                curResult.setError(ex);
                lastError = ex;
            } else {
                curResult.setError(ex);
                lastError = ex;
            }
        }
        finally {
            curResult.setQueryTime(System.currentTimeMillis() - startTime);

            if (fireEvents && listener != null && startQueryAlerted) {
                notifyQueryExecutionEnd(session, curResult);
            }

            monitor.done();
        }

        if (executionCanceled) {
            return false;
        }

        lastGoodQuery = originalQuery;

        if (curResult.getError() != null && errorHandling != SQLScriptErrorHandling.IGNORE) {
            return false;
        }
        // Success
        return true;
    }

    private boolean shouldRecoverQuery(SQLQuery query) {
        Statement statement = query.getStatement();
        if (statement instanceof Insert ||
            statement instanceof Delete ||
            statement instanceof Update ||
            (statement instanceof PlainSelect select && !CommonUtils.isEmpty(select.getIntoTables()))) {
            return false;
        }
        return true;
    }

    public void notifyQueryExecutionEnd(DBCSession session, SQLQueryResult curResult) {
        // Notify query end
        try {
            listener.onEndQuery(session, curResult, statistics);
        } catch (Exception e) {
            log.error(e);
        }
    }

    private void executeStatement(
        @NotNull DBCSession session,
        SQLQuery sqlQuery,
        long startTime,
        SQLQueryResult curResult,
        @NotNull SQLScriptContext elementContext
    ) throws DBException {
        AbstractExecutionSource source = new AbstractExecutionSource(dataContainer, session.getExecutionContext(), handler.getExecutionController(), sqlQuery);
        source.setScriptContext(elementContext);
        // Natively bound parameters need a prepared statement; everything else is sent as a script (SQLQueryJob)
        final boolean boundParameters = handler.hasBoundParameters(sqlQuery);
        final DBCStatement dbcStatement = DBUtils.makeStatement(
            source,
            session,
            boundParameters ? DBCStatementType.QUERY : DBCStatementType.SCRIPT,
            sqlQuery,
            rsOffset,
            rsMaxRows);
        if (boundParameters) {
            try {
                handler.bindParameters(dbcStatement, sqlQuery);
            } catch (DBCException e) {
                dbcStatement.close();
                throw e;
            }
        }
        DBExecUtils.setStatementFetchSize(dbcStatement, rsOffset, rsMaxRows, fetchSize);
        curStatement = dbcStatement;

        int statementTimeout = handler.getStatementTimeout();
        if (statementTimeout > 0) {
            try {
                dbcStatement.setStatementTimeout(statementTimeout);
            } catch (Throwable e) {
                log.debug("Can't set statement timeout:" + e.getMessage());
            }
        }

        // Execute statement
        try {
            DBRProgressMonitor monitor = session.getProgressMonitor();
            monitor.subTask("Execute query");

            boolean hasResultSet = dbcStatement.executeStatement();

            statistics.addExecuteTime(System.currentTimeMillis() - startTime);
            statistics.addStatementsCount();

            curResult.setHasResultSet(hasResultSet);

            long updateCount = -1;
            // Some databases (especially NoSQL) may produce a lot of
            // result sets, we should warn user because it may lead to UI freeze
            int resultSetCounter = 0;
            int updateCountReads = 0;
            boolean confirmed = false;
            while (true) {
                // Fetch data only if we have to fetch all results or if it is rs requested
                if (fetchResultSetNumber < 0 || fetchResultSetNumber == resultSetNumber) {
                    if (hasResultSet && !confirmed && resultSetCounter >= handler.getMaxResultSetsPerQuery()) {
                        hasResultSet = handler.confirmFetchManyResultSets(handler.getMaxResultSetsPerQuery());
                        confirmed = hasResultSet;
                    }
                    if (hasResultSet && fetchResultSets) {
                        DBCResultSet resultSet;
                        try {
                            resultSet = dbcStatement.openResultSet();
                            resultSetCounter++;
                        } catch (DBCException e) {
                            DBPErrorAssistant.ErrorType errorType = DBExecUtils.discoverErrorType(session.getDataSource(), e);
                            if (errorType == DBPErrorAssistant.ErrorType.RESULT_SET_MISSING) {
                                // We need to ignore this error and try to get next results
                                if (dbcStatement.nextResults()) {
                                    continue;
                                }
                            }
                            throw e;
                        }
                        if (resultSet == null) {
                            // Kind of bug in the driver. It says it has resultset but returns null
                            break;
                        } else {
                            DBDDataReceiver dataReceiver = handler.getDataReceiver(sqlQuery, resultSetNumber);
                            if (dataReceiver != null) {
                                try {
                                    hasResultSet = fetchQueryData(session, resultSet, curResult, curResult.addExecuteResult(true), dataReceiver, true);
                                } catch (DBCException e) {
                                    if (DBExecUtils.isExecutionCanceled(session.getDataSource(), e) || monitor.isCanceled()) {
                                        throw e;
                                    }
                                    if (rowsFetched == 0) {
                                        throw e;
                                    } else {
                                        // Some rows were fetched, so we don't want to fail entire query
                                        // Ad error as a warning
                                        log.warn("Fetch failed", e);
                                        statistics.setRowsFetched(rowsFetched);
                                        statistics.setError(e);
                                    }
                                }
                            } else {
                                // The result is skipped without reading any rows, so the max rows check must not use a count left from the previous fetch
                                rowsFetched = 0;
                            }
                        }
                    }
                }
                if (!hasResultSet) {
                    try {
                        updateCount = dbcStatement.getUpdateRowCount();
                        SQLQueryResult.ExecuteResult executeResult = curResult.addExecuteResult(false);
                        if (updateCount >= 0) {
                            executeResult.setUpdateCount(updateCount);
                            statistics.addRowsUpdated(updateCount);
                            updateCountReads++;
                        }
                    } catch (DBCException e) {
                        // In some cases we can't read update count
                        // This is bad, but we can live with it
                        // Just print a warning
                        log.warn("Can't obtain update count", e);
                    }
                }
                if (hasResultSet && fetchResultSets) {
                    resultSetNumber++;
                    fetchResultSetNumber = resultSetNumber;
                }
                if (!hasResultSet) {
                    if (updateCount <= 0 && updateCountReads >= MAX_UPDATE_COUNT_READS) {
                        // Exhausted all read attempts with no success
                        break;
                    }
                    if (updateCount < 0) {
                        // Nothing else to fetch
                        break;
                    }
                }

                DBPDataSourceInfo dataSourceInfo = session.getDataSource().getInfo();
                if (dataSourceInfo.supportsMultipleResults()) {
                    if (hasLimits() && rowsFetched >= rsMaxRows && dataSourceInfo.isMultipleResultsFailsOnMaxRows()) {
                        log.trace("Max rows exceeded. Additional resultsets extraction is disabled");
                        hasResultSet = false;
                    } else {
                        try {
                            hasResultSet = dbcStatement.nextResults();
                        } catch (DBCException e) {
                            if (dataSourceInfo.isMultipleResultsFetchBroken()) {
                                statistics.addWarning(e);
                                statistics.setError(e);
                                // #2792: Check this twice. Some drivers (e.g. Sybase jConnect)
                                // throw error on n'th result fetch - but it still can keep fetching next results
                                hasResultSet = dbcStatement.nextResults();
                            } else {
                                throw e;
                            }
                        }
                    }
                    updateCount = hasResultSet ? -1 : 0;
                } else {
                    break;
                }
            };
            // Still open: some drivers give the statement's actual plan only now (Altibase EXPLAIN PLAN = ON)
            handler.afterStatementExecuted(session, dbcStatement, sqlQuery);
        }
        finally {
            try {
                curResult.addWarnings(dbcStatement.getStatementWarnings());
            } catch (Throwable e) {
                log.warn("Can't read execution warnings", e);
            }
            if (!CommonUtils.isEmpty(statistics.getWarnings())) {
                curResult.addWarnings(statistics.getWarnings().toArray(new Throwable[0]));
            }
            //monitor.subTask("Close query");
            if (!keepStatementOpen()) {
                closeStatement();
            } else {
                if (dbcStatement instanceof AbstractStatement<?> as) {
                    as.runCloseDependants();
                }
            }
        }
    }

    private void showExecutionResult(DBCSession session) {
        if (handler.isShowExecutionResult(resultSetNumber, statistics)) { // Single statement with some stats
            SQLQuery query = new SQLQuery(session.getDataSource(), "", -1, -1);
            if (queries.size() == 1) {
                SQLScriptElement source = queries.getFirst();
                query.setText(source.getText(), source instanceof SQLQuery sqlQuery && sqlQuery.representsOneStatement());
            }
            query.setData(STATS_RESULTS); // It will set tab name to "Stats"
            DBDDataReceiver dataReceiver = handler.getStatisticsDataReceiver(query, resultSetNumber);
            if (dataReceiver != null) {
                try {
                    fetchExecutionResult(session, dataReceiver, query);
                } catch (DBException e) {
                    log.error("Error generating execution result stats", e);
                }
            }
        }
    }

    private void fetchExecutionResult(@NotNull DBCSession session, @NotNull DBDDataReceiver dataReceiver, @NotNull SQLQuery query) throws DBException {
        // Fetch fake result set
        try (LocalStatement statStatement = new LocalStatement(session, "statistics")) {
            try (StatResultSet fakeResultSet = new StatResultSet(session, statStatement)) {
                SQLQueryResult resultInfo = new SQLQueryResult(query);
                SQLQueryResult.ExecuteResult executeResult = resultInfo.addExecuteResult(true);

                if (statistics.getStatementsCount() > 1) {
                    // Multiple statements - show script statistics
                    fakeResultSet.addColumn("Queries", DBPDataKind.NUMERIC);
                    fakeResultSet.addColumn("Updated Rows", DBPDataKind.NUMERIC);
                    fakeResultSet.addColumn("Execute time", DBPDataKind.NUMERIC);
                    fakeResultSet.addColumn("Fetch time", DBPDataKind.NUMERIC);
                    fakeResultSet.addColumn("Total time", DBPDataKind.NUMERIC);
                    fakeResultSet.addColumn("Start time", DBPDataKind.DATETIME);
                    fakeResultSet.addColumn("Finish time", DBPDataKind.DATETIME);
                    fakeResultSet.addRow(
                        statistics.getStatementsCount(),
                        statistics.getRowsUpdated() < 0 ? 0 : statistics.getRowsUpdated(),
                        RuntimeUtils.formatExecutionTime(statistics.getExecuteTime()),
                        RuntimeUtils.formatExecutionTime(statistics.getFetchTime()),
                        RuntimeUtils.formatExecutionTime(statistics.getTotalTime()),
                        new SimpleDateFormat(DBConstants.DEFAULT_TIMESTAMP_FORMAT).format(new Date(statistics.getStartTime())),
                        new SimpleDateFormat(DBConstants.DEFAULT_TIMESTAMP_FORMAT).format(new Date())
                    );
                    executeResult.setResultSetName(handler.getStatisticsResultSetName());
                } else {
                    // Single statement - reorder fields to prioritize the important ones
                    // Important fields like "Updated Rows" and "Execute time" are now displayed before the query text for easier access.
                    long updateCount = statistics.getRowsUpdated();
                    fakeResultSet.addColumn("Updated Rows", DBPDataKind.NUMERIC);
                    fakeResultSet.addColumn("Execute time", DBPDataKind.NUMERIC);
                    fakeResultSet.addColumn("Start time", DBPDataKind.DATETIME);
                    fakeResultSet.addColumn("Finish time", DBPDataKind.DATETIME);
                    fakeResultSet.addColumn("Query", DBPDataKind.STRING);
                    fakeResultSet.addRow(
                        updateCount,
                        RuntimeUtils.formatExecutionTime(statistics.getExecuteTime()),
                        new Date(statistics.getStartTime()),
                        new Date(),
                        query.getText()
                    );
                    executeResult.setResultSetName(handler.getDefaultResultSetName());
                }
                fetchQueryData(session, fakeResultSet, resultInfo, executeResult, dataReceiver, false);
            }
        }
    }

    private boolean fetchQueryData(
        @NotNull DBCSession session,
        @Nullable DBCResultSet resultSet,
        @Nullable SQLQueryResult result,
        @NotNull SQLQueryResult.ExecuteResult executeResult,
        @NotNull DBDDataReceiver dataReceiver,
        boolean updateStatistics
    ) throws DBException {
        if (resultSet == null) {
            return false;
        }
        boolean keepCursor = keepStatementOpen();

        if (keepCursor) {
            curResultSets.add(resultSet);
        }
        DBRProgressMonitor monitor = session.getProgressMonitor();
        monitor.subTask("Fetch result set");
        DBFetchProgress fetchProgress = new DBFetchProgress(session.getProgressMonitor());

        DBDDataReceiver.startFetchWorkflow(dataReceiver, session, resultSet, rsOffset, rsMaxRows);

        try {
            String sourceName = null;//resultSet.getResultSetName();
            if (result != null) {
                final String queryTitle = result.getStatement().getQueryTitle();
                if (!CommonUtils.isEmpty(queryTitle)) {
                    sourceName = queryTitle;
                } else {
                    // Retrieve source entity
                    DBCResultSetMetaData rsMeta = resultSet.getMeta();
                    for (DBCAttributeMetaData attr : rsMeta.getAttributes()) {
                        String entityName = attr.getEntityName();
                        if (!CommonUtils.isEmpty(entityName)) {
                            if (sourceName == null) {
                                sourceName = entityName;
                            } else if (!sourceName.equals(entityName)) {
                                // Multiple source entities
                                sourceName += "(+)";
                                break;
                            }
                        }
                    }
                    // Fallback: extract table name from parsed SQL query AST.
                    // Some drivers (e.g. Oracle JDBC thin) do not return table names
                    // from ResultSetMetaData.getTableName(), so we use JSQLParser results instead.
                    if (CommonUtils.isEmpty(sourceName)) {
                        SQLQuery sqlQuery = result.getStatement();
                        DBCEntityMetaData entityMeta = sqlQuery.getEntityMetadata(false);
                        if (entityMeta != null) {
                            sourceName = entityMeta.getEntityName();
                        } else if (!sqlQuery.getAllSelectEntitiesNames().isEmpty()) {
                            // Query has JOINs - use first table name with (+) marker
                            sourceName = sqlQuery.getAllSelectEntitiesNames().getFirst();
                            if (sqlQuery.getAllSelectEntitiesNames().size() > 1) {
                                sourceName += "(+)";
                            }
                        }
                    }
                    if (CommonUtils.isEmpty(sourceName)) {
                        try {
                            sourceName = resultSet.getResultSetName();
                        } catch (Exception e) {
                            // This will happen quite often, do not log it
                        }
                    }
                }
                if (CommonUtils.isEmpty(sourceName)) {
                    sourceName = handler.getDefaultResultSetName();
                }
                executeResult.setResultSetName(sourceName);
            }
            long fetchStartTime = System.currentTimeMillis();

            // Fetch all rows
            rowsFetched = 0;
            while ((!hasLimits() || !fetchProgress.isMaxRowsFetched(rsMaxRows)) && !fetchProgress.isCanceled() && resultSet.nextRow()) {
                dataReceiver.fetchRow(session, resultSet);
                rowsFetched++;
                fetchProgress.monitorRowFetch();
            }
            if (updateStatistics) {
                statistics.addFetchTime(System.currentTimeMillis() - fetchStartTime);
            }
        }
        finally {
            if (!keepCursor) {
                try {
                    resultSet.close();
                } catch (Throwable e) {
                    log.error("Error while closing resultset", e);
                }
            }
        }

        if (result != null) {
            executeResult.setRowCount(fetchProgress.getRowCount());
        }
        if (updateStatistics) {
            statistics.setRowsFetched(fetchProgress.getRowCount());
        }
        monitor.subTask(fetchProgress.getRowCount() + " rows fetched");

        return true;
    }

    private boolean keepStatementOpen() {
        if (!getExecutionContext().getDataSource().getContainer().getDriver().isThreadSafeDriver()) {
            return false;
        }
        // Only in single query mode and if pref option set to true
        return queries.size() == 1 &&
            handler.isKeepStatementOpen();
    }

    private void closeStatement() {
        if (curStatement != null) {
            try {
                for (DBCResultSet resultSet : curResultSets) {
                    DBUtils.closeSafely(resultSet);
                }
            } finally {
                curResultSets.clear();

                DBUtils.closeSafely(curStatement);
                curStatement = null;
            }
        }
    }

    public void extractData(
        @NotNull DBCSession session,
        @NotNull SQLScriptElement query,
        int resultNumber,
        boolean fireEvents,
        boolean allowStatistics
    ) throws DBCException {
        // Reset query to original. Otherwise multiple filters will corrupt it
        query.reset();

        statistics = new DBCStatistics();
        resultSetNumber = resultNumber;
        //session.getProgressMonitor().beginTask(CommonUtils.truncateString(query.getText(), 512), 1);
        session.getProgressMonitor().subTask(CommonUtils.truncateString(query.getText(), 512));

        boolean result = executeSingleQuery(session, query, fireEvents);

        if (listener != null) {
            listener.onEndSqlJob(session, getSqlJobResult());
        }

        if (!result && lastError != null) {
            if (lastError instanceof DBCException dbce) {
                throw dbce;
            } else {
                throw new DBCException(lastError, getExecutionContext());
            }
        } else if (allowStatistics && result && statistics.getStatementsCount() > 0) {
            showExecutionResult(session);
        }
    }

    public void setDataFilter(DBDDataFilter dataFilter)
    {
        this.dataFilter = dataFilter;
    }

    public DBCStatistics getStatistics()
    {
        return statistics;
    }

    public void setFetchResultSetNumber(int fetchResultSetNumber)
    {
        this.fetchResultSetNumber = fetchResultSetNumber;
    }

    public boolean isJobOpen() {
        return curStatement != null;
    }

    public void closeJob() {
        closeStatement();
    }

}
