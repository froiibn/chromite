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
package com.seizia.chromite.model.sql.data;

import org.eclipse.jface.text.Document;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.*;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.exec.*;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.*;
import com.seizia.chromite.model.sql.parser.SQLParserContext;
import com.seizia.chromite.model.sql.parser.SQLRuleManager;
import com.seizia.chromite.model.sql.parser.SQLScriptParser;
import com.seizia.chromite.model.sql.transformers.SQLQueryTransformerCount;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data container for single SQL query.
 * Doesn't support multiple resulsets.
 *
 * 설계(Chromite): 매개변수 값을 SQL 글자로 넣지 않고 JDBC로 바인딩한 쿼리(Swing Query Library)는 {@link #setParameterBinder}로
 * 바인딩 방법을 받는다. 다시 읽을 때(필터, 서버 정렬, 다음 페이지, 내보내기) 필터를 넣은 최종 글자에서 매개변수를 다시 찾으므로,
 * 바인딩 방법은 {@code :name} 글자를 받아 그때 {@code ?}로 바꾸고 값을 이름으로 바인딩한다. 필터·정렬이 쿼리를 감싸거나 ORDER BY를
 * 바꿔도 {@code ?}의 순서가 어긋나지 않는다. 바인딩 방법이 없으면 예전처럼 스크립트 문맥이 값을 글자로 채운다.
 *
 * 행 수({@link #countData})는 컨테이너가 {@code FEATURE_DATA_COUNT}를 내세우면서도 늘 -1을 돌려주던 것을 고쳐, 필터 조건을 넣은 쿼리를
 * 모델의 COUNT 변환기로 바꾸고 {@link #readData}로 읽는다(매개변수 처리가 행 읽기와 같게).
 *
 * 여러 문장을 한 번에 보낸 쿼리(SQL Server {@code GO} 배치, 선택한 여러 문장)의 결과는 {@link #restrictToResultSet}로 그 결과를 만든
 * 문장만 갖는다({@link SQLBatchResultStatement}). 다시 읽기가 배치 전체(UPDATE·DDL·EXEC 포함)를 다시 실행하지 않게 하기 위해서다.
 * 문장을 정하지 못한 결과는 배치를 그대로 갖되 {@link #readData}와 {@link #countData}가 거절한다: 화면이 다시 읽기를 막지 못한
 * 경로(저장 뒤 새로 고침 등)가 있어도 배치가 다시 실행되지 않는다.
 *
 * 변경 이력:
 * - 2026-11-08 {@link ParameterBinder}: 값을 바인딩한 쿼리의 결과도 다시 읽음 (docs/chromite/swing-query-library.md "매개변수 바인딩")
 * - 2026-11-08 {@link #countData}: 쿼리 결과의 전체 행 수(결과 뷰어 "행 수 세기", 데이터 전송의 "행 수 읽기"). 전에는 -1이라 상태 줄에
 *   "전체 -1행"이 보이고 내보내기는 전체 행 수를 몰랐다 (docs/chromite/swing-grid-navigation.md "전체 행 수")
 * - 2026-11-08 {@link #restrictToResultSet}: 배치 결과를 다시 읽으면 그 결과를 만든 문장만 실행하고, 문장을 정하지 못하면 거절
 *   (docs/chromite/sql-batch-reread.md)
 */
public class SQLQueryDataContainer implements DBSDataContainer, SQLQueryContainer, DBPContextProvider, DBPImageProvider {

    /**
     * Binds parameter values natively (JDBC) instead of writing them into the query text.
     */
    public interface ParameterBinder {
        /**
         * Rewrites a query whose parameters were parsed from its final text (after the filters), e.g. {@code :name} to
         * {@code ?}; the returned query is prepared and passed to {@link #bindParameters}.
         *
         * @throws DBException a parameter has no value
         */
        @NotNull
        SQLQuery prepareQuery(@NotNull SQLQuery query) throws DBException;

        void bindParameters(@NotNull DBCStatement statement, @NotNull SQLQuery query) throws DBCException;
    }

    private final DBPContextProvider contextProvider;
    private final SQLScriptContext scriptContext;
    private SQLQuery query;
    private final Log log;
    @Nullable
    private ParameterBinder parameterBinder;
    // The query has several statements and the one that produced the result is not known (restrictToResultSet)
    private boolean wholeBatchOnly;

    public SQLQueryDataContainer(DBPContextProvider contextProvider, SQLQuery query, SQLScriptContext scriptContext, Log log) {
        this.contextProvider = contextProvider;
        this.query = query;
        this.scriptContext = scriptContext;
        this.log = log;
    }

    @Nullable
    @Override
    public DBCExecutionContext getExecutionContext() {
        return contextProvider.getExecutionContext();
    }

    @NotNull
    @Override
    public String[] getSupportedFeatures() {
        return new String[] {FEATURE_DATA_SELECT, FEATURE_DATA_COUNT, FEATURE_DATA_FILTER};
    }

    @Override
    public SQLScriptContext getScriptContext() {
        return scriptContext;
    }

    @NotNull
    @Override
    public DBCStatistics readData(
        @Nullable DBCExecutionSource source,
        @NotNull DBCSession session,
        @NotNull DBDDataReceiver dataReceiver,
        DBDDataFilter dataFilter,
        long firstRow,
        long maxRows,
        long flags,
        int fetchSize
    ) throws DBException
    {
        checkCanReadAgain(session);
        DBCStatistics statistics = new DBCStatistics();
        // Modify query (filters + parameters)
        DBPDataSource dataSource = session.getDataSource();
        SQLQuery sqlQuery = query;
        String queryText = sqlQuery.getText();//.trim();
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
                throw new DBCException("Unable to apply filters to query", e, session.getExecutionContext());
            }
            sqlQuery = new SQLQuery(dataSource, filteredQueryText, sqlQuery);
        } else {
            sqlQuery = new SQLQuery(dataSource, queryText, sqlQuery);
        }

        if (scriptContext != null) {
            SQLSyntaxManager syntaxManager = new SQLSyntaxManager();
            syntaxManager.init(dataSource.getSQLDialect(), dataSource.getContainer().getPreferenceStore());
            SQLRuleManager ruleManager = new SQLRuleManager(syntaxManager);
            ruleManager.loadRules(dataSource, false);
            // parse sql text when the filters are applied, because filters may change the parameters positions
            SQLParserContext parserContext = new SQLParserContext(
                getDataSource(),
                syntaxManager,
                ruleManager,
                new Document(sqlQuery.getText())
            );
            // parse parameters from the final query text
            sqlQuery.setParameters(SQLScriptParser.parseParametersAndVariables(parserContext, 0, sqlQuery.getText().length()));
            if (parameterBinder != null) {
                sqlQuery = parameterBinder.prepareQuery(sqlQuery);
            } else if (!scriptContext.fillQueryParameters(sqlQuery, () -> dataReceiver, CommonUtils.isBitSet(flags, DBSDataContainer.FLAG_REFRESH))) {
                // User canceled
                return statistics;
            }
        }

        final SQLQueryResult curResult = new SQLQueryResult(sqlQuery);
        if (firstRow > 0) {
            curResult.setRowOffset(firstRow);
        }
        statistics.setQueryText(sqlQuery.getText());

        long startTime = System.currentTimeMillis();

        // Natively bound values need a prepared statement (as in SQLQueryExecuteJob.executeStatement)
        boolean bound = parameterBinder != null && scriptContext != null;
        try (final DBCStatement dbcStatement = DBUtils.makeStatement(
            source,
            session,
            bound ? DBCStatementType.QUERY : DBCStatementType.SCRIPT,
            sqlQuery,
            firstRow,
            maxRows))
        {
            if (bound) {
                parameterBinder.bindParameters(dbcStatement, sqlQuery);
            }
            DBExecUtils.setStatementFetchSize(dbcStatement, firstRow, maxRows, fetchSize);

            // Execute statement

            session.getProgressMonitor().subTask("Execute query");

            boolean hasResultSet = dbcStatement.executeStatement();

            statistics.addExecuteTime(System.currentTimeMillis() - startTime);
            statistics.addStatementsCount();

            curResult.setHasResultSet(hasResultSet);

            if (hasResultSet) {
                DBCResultSet resultSet = dbcStatement.openResultSet();
                if (resultSet != null) {
                    SQLQueryResult.ExecuteResult executeResult = curResult.addExecuteResult(true);
                    DBRProgressMonitor monitor = session.getProgressMonitor();
                    monitor.subTask("Fetch result set");
                    DBFetchProgress fetchProgress = new DBFetchProgress(session.getProgressMonitor());

                    DBDDataReceiver.startFetchWorkflow(dataReceiver, session, resultSet, firstRow, maxRows);

                    try (resultSet) {
                        long fetchStartTime = System.currentTimeMillis();

                        // Fetch all rows
                        while (!fetchProgress.isMaxRowsFetched(maxRows) && !fetchProgress.isCanceled() && resultSet.nextRow()) {
                            dataReceiver.fetchRow(session, resultSet);
                            fetchProgress.monitorRowFetch();
                        }
                        statistics.addFetchTime(System.currentTimeMillis() - fetchStartTime);
                    }

                    if (executeResult != null) {
                        executeResult.setRowCount(fetchProgress.getRowCount());
                    }
                    statistics.setRowsFetched(fetchProgress.getRowCount());
                    monitor.subTask(fetchProgress.getRowCount() + " rows fetched");
                }
            } else {
                log.warn("No results returned by query execution");
            }
            try {
                curResult.addWarnings(dbcStatement.getStatementWarnings());
            } catch (Throwable e) {
                log.warn("Can't read execution warnings", e);
            }
        }

        return statistics;
    }

    /**
     * Counts the rows {@link #readData} reads: the query with the filter's conditions, turned into a count by the model's
     * {@link SQLQueryTransformerCount} (SWT counted query results the same way), and read through {@link #readData} so
     * that its parameters are filled or bound as for the rows. Only a single SELECT is counted: wrapping another
     * statement would run it.
     */
    @Override
    public long countData(@NotNull DBCExecutionSource source, @NotNull DBCSession session, @Nullable DBDDataFilter dataFilter, long flags)
        throws DBCException
    {
        checkCanReadAgain(session);
        if (query.getType() != SQLQueryType.SELECT || query.isModifying()) {
            throw new DBCException("Can't count the rows of a statement that is not a single SELECT");
        }
        DBPDataSource dataSource = session.getDataSource();
        try {
            String queryText = query.getText();
            if (dataFilter != null && dataFilter.hasConditions()) {
                // The order does not change the count
                DBDDataFilter conditions = new DBDDataFilter(dataFilter);
                conditions.resetOrderBy();
                queryText = dataSource.getSQLDialect().addFiltersToQuery(session.getProgressMonitor(), dataSource, queryText, conditions);
            }
            SQLSyntaxManager syntaxManager = new SQLSyntaxManager();
            syntaxManager.init(dataSource.getSQLDialect(), dataSource.getContainer().getPreferenceStore());
            SQLQuery countQuery = new SQLQueryTransformerCount().transformQuery(dataSource, syntaxManager, new SQLQuery(dataSource, queryText, query));
            SQLQueryDataContainer counter = new SQLQueryDataContainer(contextProvider, countQuery, scriptContext, log);
            counter.setParameterBinder(parameterBinder);
            CountReceiver receiver = new CountReceiver();
            // FLAG_REFRESH: the parameters keep the values of the rows' read instead of being asked again
            counter.readData(source, session, receiver, null, 0, 1, flags | DBSDataContainer.FLAG_REFRESH, 0);
            if (receiver.count < 0) {
                throw new DBCException("Row count query didn't return any value");
            }
            return receiver.count;
        } catch (DBCException e) {
            throw e;
        } catch (DBException e) {
            throw new DBCException("Can't count the rows of the query", e, session.getExecutionContext());
        }
    }

    /**
     * The first value of a count query's result.
     */
    private static final class CountReceiver implements DBDDataReceiver {
        private long count = -1;

        @Override
        public void fetchStart(@NotNull DBCSession session, @NotNull DBCResultSet resultSet, long offset, long maxRows) {
        }

        @Override
        public void fetchRow(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) throws DBException {
            if (count < 0 && resultSet.getAttributeValue(0) instanceof Number number) {
                count = number.longValue();
            }
        }

        @Override
        public void fetchEnd(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) {
        }

        @Override
        public void close() {
        }
    }

    @Nullable
    @Override
    public String getDescription()
    {
        return "SQL Query";
    }

    @Nullable
    @Override
    public DBSObject getParentObject()
    {
        return getDataSource();
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource()
    {
        DBCExecutionContext executionContext = getExecutionContext();
        return executionContext == null ? null : executionContext.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return false;
    }

    @NotNull
    @Override
    public String getName()
    {
        String name = query.getOriginalText();
        if (name == null) {
            name = "SQL";
        }
        return name;
    }

    @Nullable
    @Override
    public DBPDataSourceContainer getDataSourceContainer() {
        DBPDataSource dataSource = getDataSource();
        return dataSource == null ? null : dataSource.getContainer();
    }

    @Override
    public String toString() {
        return query.getOriginalText();
    }

    @Override
    public SQLScriptElement getQuery() {
        return query;
    }

    public void setQuery(SQLQuery query) {
        this.query = query;
    }

    /**
     * The binder of a query whose parameter values are bound natively, or null when the script context writes them
     * into the text. It is used only with a script context (the parameters are parsed from the final text then).
     */
    @Nullable
    public ParameterBinder getParameterBinder() {
        return parameterBinder;
    }

    public void setParameterBinder(@Nullable ParameterBinder parameterBinder) {
        this.parameterBinder = parameterBinder;
    }

    /**
     * Makes the container of a result read only the statement of its query that produced the result set, for a query
     * of several statements (a {@code GO} batch, selected statements run as one): reading the result again (filters,
     * sorting, next pages, export) must not run the other statements. When that statement can't be found, the
     * container keeps the query and refuses to read it again ({@link #isWholeBatchOnly()}).
     *
     * @param resultSetNumber the number of the result set among the query's result sets, 0 for the first one
     */
    public void restrictToResultSet(int resultSetNumber) {
        DBPDataSource dataSource = query.getDataSource() != null ? query.getDataSource() : getDataSource();
        SQLQuery statement = dataSource == null ? null : SQLBatchResultStatement.find(dataSource, query, resultSetNumber);
        if (statement == null) {
            wholeBatchOnly = true;
        } else {
            query = statement;
        }
    }

    /**
     * Whether the result can only be read again by running all statements of its query again, which this container
     * refuses ({@link #restrictToResultSet}).
     */
    public boolean isWholeBatchOnly() {
        return wholeBatchOnly;
    }

    private void checkCanReadAgain(@NotNull DBCSession session) throws DBCException {
        if (wholeBatchOnly) {
            throw new DBCException("This result can't be read again without running the whole batch again", null,
                session.getExecutionContext());
        }
    }

    @Override
    public Map<String, Object> getQueryParameters() {
        if (query.getParameters() == null) {
            return scriptContext.getAllParameters();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (SQLQueryParameter parameter : query.getParameters()) {
            result.put(parameter.getVarName(), parameter.getValue());
        }
        return result;
    }

    @Nullable
    @Override
    public DBPImage getObjectImage() {
        return DBIcon.TREE_FILE;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof SQLQueryDataContainer &&
            CommonUtils.equalObjects(query, ((SQLQueryDataContainer) obj).query);
    }
}
