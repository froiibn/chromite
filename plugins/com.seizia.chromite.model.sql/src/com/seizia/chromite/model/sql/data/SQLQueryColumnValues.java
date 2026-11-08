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
package com.seizia.chromite.model.sql.data;

import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDAttributeConstraint;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCLogicalOperator;
import com.seizia.chromite.model.exec.DBCResultSet;
import com.seizia.chromite.model.exec.DBCSavepoint;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.impl.AbstractExecutionSource;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.sql.SQLGroupingAttribute;
import com.seizia.chromite.model.sql.SQLGroupingQueryGenerator;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryContainer;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.SQLSyntaxManager;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.sql.parser.SQLSemanticProcessor;
import com.seizia.chromite.model.struct.DBSDataContainer;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 쿼리 결과의 열 하나에 있는 서로 다른 값과 값마다의 행 수를 서버에서 읽는다(Swing 결과 그리드의 열 필터 팝업).
 *
 * 설계:
 * <ul>
 *     <li>SWT 결과 뷰어의 값 목록({@code GenericFilterValueEdit.loadAttributeEnum})은 열의 테이블 열
 *     ({@code DBSAttributeEnumerable.getValueEnumeration})에서 값을 읽는다. 그 값은 테이블 전체의 값이라 쿼리의 WHERE·조인과 다른
 *     열의 필터를 따르지 않아, 고르면 0행이 되는 값이 섞인다. 그래서 지금 결과의 쿼리 자체를 감싸 묶는다(SWT 그룹 패널
 *     {@code GroupingResultsContainer}처럼 {@link SQLGroupingQueryGenerator}로 {@code SELECT 열, COUNT(*) FROM (쿼리) GROUP BY 열}).
 *     테이블 열이 없는 계산 열·별칭 열도 같은 방법으로 읽는다.</li>
 *     <li>다른 열의 필터는 결과를 다시 읽을 때와 같은 방언 경로({@link SQLDialect#addFiltersToQuery})로 쿼리에 먼저 넣는다(엑셀 자동 필터처럼
 *     다른 열의 필터를 따른 값만 보임). 검색어도 이 열의 LIKE 조건으로 같은 경로에 넣는다: 값은 방언이 따옴표로 감싸고 이스케이프하며
 *     사용자가 쓴 글자가 SQL로 들어가지 않는다. 대소문자를 구분하지 않는 ILIKE를 열이 지원하면 그것을 쓴다.</li>
 *     <li>SQL Server는 파생 테이블 안의 ORDER BY를 거부한다. 그래서 필터를 넣기 전에 TOP·LIMIT·OFFSET·FETCH가 없는 쿼리의 바깥 ORDER BY를
 *     JSQLParser로 지운다({@code SQLQueryTransformerCount}와 같은 처리, 묶어 세는 데 순서는 필요 없음). 필터를 넣은 뒤에 지우면 방언이 쿼리를
 *     감싼 경우({@code getWrappedFilterQuery}) 안쪽 ORDER BY가 남는다. 읽지 못하는 쿼리는 그대로 감싸 DB가 판단하게 한다.</li>
 *     <li>새 {@link SQLQueryDataContainer}로 읽는다. 결과 쿼리와 같은 스크립트 문맥을 넘겨 {@link DBSDataContainer#FLAG_REFRESH}로
 *     쿼리 매개변수를 다시 묻지 않고 지난 값을 쓴다. 값을 바인딩한 쿼리(Query Library)는 결과 쿼리의 바인딩 방법도 넘긴다.
 *     행 수 제한도 결과를 읽을 때와 같은 방언 경로({@code DBUtils.makeStatement})다.</li>
 *     <li>행이 많이 나온 값부터 {@code limit}개를 읽고(ORDER BY 개수 DESC), 한 행을 더 읽어 더 있는지 알린다. 화면 순서는 호출하는 쪽이 정한다.</li>
 *     <li>결과의 실행 문맥에서 읽는다(임시 테이블, 세션 설정, 현재 DB가 같아야 한다). 수동 커밋 문맥에서는 저장점을 두고 실패하면 저장점까지만
 *     되돌린다: PostgreSQL은 문장 하나가 실패하면 열린 트랜잭션 전체를 못 쓰게 만들기 때문이다. 사용자의 변경은 커밋하거나 되돌리지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 결과 그리드 열 필터의 값 목록 (docs/chromite/swing-grid-column-filters.md)
 * - 2026-11-08 값을 바인딩한 쿼리의 값 목록: 결과 쿼리의 바인딩 방법({@link SQLQueryDataContainer#getParameterBinder})을 넘김
 *   (docs/chromite/swing-query-library.md "매개변수 바인딩")
 * - 2026-11-08 결과를 만든 문장을 정하지 못한 배치 결과({@link SQLQueryDataContainer#isWholeBatchOnly()})는 읽지 않음: 쿼리 글자를
 *   감싸 새 컨테이너로 읽으므로 결과 컨테이너의 거절을 거치지 않는다 (docs/chromite/sql-batch-reread.md)
 */
public final class SQLQueryColumnValues {

    private static final Log log = Log.getLog(SQLQueryColumnValues.class);

    /**
     * One distinct value and the number of rows that have it.
     *
     * @param value the value as the column's value handler fetches it, null for NULL
     */
    public record ValueCount(@Nullable Object value, long count) {
    }

    /**
     * @param values    at most the requested number of values, the most frequent first
     * @param truncated there are more distinct values than were read
     */
    public record Result(@NotNull List<ValueCount> values, boolean truncated) {
    }

    private SQLQueryColumnValues() {
    }

    /**
     * Reads the distinct values of a result column with their row counts.
     *
     * @param queryContainer the query of the result (a single SELECT)
     * @param column         the result column
     * @param otherFilter    the conditions of the other columns, or null
     * @param search         only values that contain this text (string columns), or null
     * @param limit          maximum number of values
     */
    @NotNull
    public static Result read(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull SQLQueryContainer queryContainer,
        @NotNull DBDAttributeBinding column,
        @Nullable DBDDataFilter otherFilter,
        @Nullable String search,
        int limit
    ) throws DBException {
        SQLScriptElement element = queryContainer.getQuery();
        if (!(element instanceof SQLQuery query) || !(queryContainer instanceof DBSDataContainer dataContainer)) {
            throw new DBException("The result has no query to read the values from");
        }
        if (queryContainer instanceof SQLQueryDataContainer resultContainer && resultContainer.isWholeBatchOnly()) {
            throw new DBException("The values can't be read without running the whole batch of the result again");
        }
        DBPDataSource dataSource = context.getDataSource();
        SQLDialect dialect = dataSource.getSQLDialect();

        DBDDataFilter filter = otherFilter == null ? new DBDDataFilter() : new DBDDataFilter(otherFilter);
        filter.resetOrderBy();
        if (!CommonUtils.isEmpty(search)) {
            DBDAttributeConstraint constraint = filter.getConstraint(column);
            if (constraint == null) {
                constraint = new DBDAttributeConstraint(column);
                filter.addConstraints(List.of(constraint));
            }
            constraint.setOperator(ArrayUtils.contains(column.getValueHandler().getSupportedOperators(column), DBCLogicalOperator.ILIKE)
                ? DBCLogicalOperator.ILIKE : DBCLogicalOperator.LIKE);
            constraint.setValue("%" + search + "%"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String queryText = removeOrdering(dialect, query.getText());
        if (filter.hasConditions()) {
            queryText = dialect.addFiltersToQuery(monitor, dataSource, queryText, filter);
        }

        SQLSyntaxManager syntaxManager = new SQLSyntaxManager();
        syntaxManager.init(dialect, dataSource.getContainer().getPreferenceStore());
        SQLGroupingQueryGenerator generator = new SQLGroupingQueryGenerator(dataSource, dataContainer, dialect, syntaxManager,
            List.of(SQLGroupingAttribute.makeBound(column)), List.of(SQLGroupingQueryGenerator.DEFAULT_FUNCTION), false);
        String groupingQuery = generator.generateGroupingQuery(SQLUtils.trimQueryStatement(syntaxManager, queryText, true));
        String[] aliases = generator.getFuncAliases();
        // Most frequent values first: they are the ones worth listing when there are more than the limit
        String sql = groupingQuery + "\nORDER BY " + (aliases.length > 0 ? aliases[0] : SQLGroupingQueryGenerator.DEFAULT_FUNCTION) + " DESC"; //$NON-NLS-1$ //$NON-NLS-2$

        SQLQuery valuesQuery = new SQLQuery(dataSource, sql);
        SQLQueryDataContainer valuesContainer = new SQLQueryDataContainer(() -> context, valuesQuery, queryContainer.getScriptContext(), log);
        if (queryContainer instanceof SQLQueryDataContainer resultContainer) {
            valuesContainer.setParameterBinder(resultContainer.getParameterBinder());
        }
        ValueReceiver receiver = new ValueReceiver(column);
        try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Read column values")) { //$NON-NLS-1$
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
            DBCSavepoint savepoint = null;
            if (txnManager != null && txnManager.isSupportsTransactions() && !txnManager.isAutoCommit() && txnManager.supportsSavepoints()) {
                savepoint = txnManager.setSavepoint(monitor, null);
            }
            try {
                valuesContainer.readData(new AbstractExecutionSource(valuesContainer, context, SQLQueryColumnValues.class, valuesQuery),
                    session, receiver, null, 0, limit + 1L, DBSDataContainer.FLAG_REFRESH, 0);
            } catch (DBException e) {
                if (savepoint != null) {
                    try {
                        txnManager.rollback(session, savepoint);
                    } catch (DBCException rollbackError) {
                        log.debug("Can't roll back to the savepoint of the value read", rollbackError);
                    }
                }
                throw e;
            }
            if (savepoint != null) {
                txnManager.releaseSavepoint(monitor, savepoint);
            }
        }
        List<ValueCount> values = receiver.values;
        boolean truncated = values.size() > limit;
        return new Result(truncated ? List.copyOf(values.subList(0, limit)) : List.copyOf(values), truncated);
    }

    /**
     * The query without its outer ORDER BY, unless the ordering limits the rows (TOP, LIMIT, OFFSET, FETCH).
     */
    @NotNull
    private static String removeOrdering(@NotNull SQLDialect dialect, @NotNull String queryText) {
        try {
            Statement statement = SQLSemanticProcessor.parseQuery(dialect, queryText);
            if (statement instanceof Select select && !CommonUtils.isEmpty(select.getOrderByElements())
                && select.getLimit() == null && select.getOffset() == null && select.getFetch() == null
                && !(select instanceof PlainSelect plainSelect && plainSelect.getTop() != null)) {
                select.setOrderByElements(null);
                return statement.toString();
            }
        } catch (DBCException e) {
            log.debug("Can't parse the query to remove its ordering, wrapping it as it is", e);
        }
        return queryText;
    }

    /**
     * Reads (value, count) rows: the value with the column's value handler, so it can be put back into a filter.
     */
    private static final class ValueReceiver implements DBDDataReceiver {
        private final DBDAttributeBinding column;
        private final List<ValueCount> values = new ArrayList<>();

        ValueReceiver(@NotNull DBDAttributeBinding column) {
            this.column = column;
        }

        @Override
        public void fetchStart(@NotNull DBCSession session, @NotNull DBCResultSet resultSet, long offset, long maxRows) {
        }

        @Override
        public void fetchRow(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) throws DBCException {
            Object value = column.getValueHandler().fetchValueObject(session, resultSet, column, 0);
            Object count = resultSet.getAttributeValue(1);
            values.add(new ValueCount(DBUtils.isNullValue(value) ? null : value, count instanceof Number number ? number.longValue() : 0));
        }

        @Override
        public void fetchEnd(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) {
        }

        @Override
        public void close() {
        }
    }
}
