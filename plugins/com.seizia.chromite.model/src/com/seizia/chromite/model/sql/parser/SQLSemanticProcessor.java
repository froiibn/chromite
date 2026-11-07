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
package com.seizia.chromite.model.sql.parser;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDAttributeConstraint;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.exec.DBCAttributeMetaData;
import com.seizia.chromite.model.exec.DBCEntityMetaData;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryGenerator;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.Pair;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Semantic SQL processor
 *
 * 설계: 결과 필터는 가능하면 원래 SELECT의 WHERE에 넣고(정렬·별칭 그대로), 열의 테이블을 확인하지 못하면 쿼리를 감싼다
 * ({@code SQLQueryGenerator.getWrappedFilterQuery}). 열의 테이블은 드라이버 메타데이터의 테이블 이름에서 찾는다.
 *
 * 변경 이력:
 * - 2026-11-07 SQL Server처럼 드라이버가 결과 열의 테이블 이름을 주지 않을 때, 쿼리에서 찾아 묶은 열({@code DBDAttributeBinding.getEntityAttribute})의
 *   테이블로 필터를 넣음. 그전에는 늘 감싸서 {@code ORDER BY}가 있는 쿼리의 열 필터가 "ORDER BY clause is invalid in … derived tables"로 실패했다
 *   (docs/chromite/swing-grid-column-filters.md)
 * - 2026-11-08 결과 정렬이 쿼리의 ORDER BY를 바꿈(뒤에 덧붙여 보이는 순서가 그대로였다). 행을 고르는 ORDER BY(TOP·LIMIT·FETCH·
 *   DISTINCT ON)는 그대로 둠. 쿼리를 감쌀 때 ORDER BY를 결과 열 참조로 바꿔 바깥으로 옮기는 {@link #moveOrderByOutside}
 *   (docs/chromite/swing-grid-column-filters.md "정렬과 쿼리의 ORDER BY")
 */
public class SQLSemanticProcessor {

    private static final Log log = Log.getLog(SQLSemanticProcessor.class);

    private static final boolean ALLOW_COMPLEX_PARSING = false;
    private static final int PARSE_FUTURE_TIMEOUT_MS = 1000; // if we can't parse fast, we don't want to

    private static final class ParserExecutor {
        private static final ExecutorService executor = Executors.newCachedThreadPool(
            Thread.ofPlatform().daemon().name("SQL parser-", 0).factory());

        static {
            // Only install the hook if a timed parse actually needs the executor.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!executor.shutdownNow().isEmpty()) {
                    log.warn("Unexpected awaiting tasks found while terminating JSqlParser executor.");
                }
            }, "SQL parser shutdown"));
        }
    }

    @NotNull
    private static CCJSqlParser buildParser(@Nullable SQLDialect dialect, @NotNull String sql) throws DBCException {
        final String sqlWithoutComments = dialect == null
            ? sql
            : SQLUtils.stripComments(dialect, SQLUtils.maskDialectLineComments(dialect, sql));
        try {
            CCJSqlParser parser = new CCJSqlParser(sqlWithoutComments)
                .withAllowComplexParsing(ALLOW_COMPLEX_PARSING);

            if (dialect != null) {
                for (String[] qs : ArrayUtils.safeArray(dialect.getIdentifierQuoteStrings())) {
                    if (qs.length == 2 && "[".equals(qs[0]) && "]".equals(qs[1])) {
                        parser.withSquareBracketQuotation(true);
                        break;
                    }
                }
                // Match MySQL-style backslash escaping so the parser agrees with SQLUtils.maskDialectLineComments
                // on where string literals end (e.g. 'O\'Brien'); otherwise the parser sees a stray closing quote.
                if (dialect.getStringEscapeCharacter() == '\\') {
                    parser.withBackslashEscapeCharacter(true);
                }
            }
            return parser;
        } catch (ParseException e) {
            throw new DBCException("Error initializing SQL parser: " + e.getMessage(), e);
        }
    }

    @NotNull
    private static <T> T callWithTimeout(@NotNull CCJSqlParser parser, @NotNull Callable<T> task) throws DBCException {
        Future<T> future = ParserExecutor.executor.submit(task);
        try {
            return future.get(PARSE_FUTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException e) {
            parser.interrupted = true;
            future.cancel(true);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DBCException("Failed to parse SQL query within reasonable time", e);
        } catch (ExecutionException e) {
            throw new DBCException("Error parsing SQL query: " + e.getMessage(), e);
        }
    }

    @NotNull
    public static List<Statement> parseQueries(@Nullable SQLDialect dialect, @NotNull String sql) throws DBCException {
        CCJSqlParser parser = buildParser(dialect, sql);
        return callWithTimeout(parser, parser::Statements);
    }

    @NotNull
    public static Statement parseQuery(@Nullable SQLDialect dialect, @NotNull String sql) throws DBCException {
        CCJSqlParser parser = buildParser(dialect, sql);
        return callWithTimeout(parser, parser::Statement);
    }

    @NotNull
    public static Statement parseQuery(@NotNull String sql) throws DBCException {
        return parseQuery(null, sql);
    }

    @Nullable
    public static Expression parseExpression(@NotNull String expression) throws DBCException {
        return parseExpression(expression, true);
    }

    @Nullable
    public static Expression parseExpression(@NotNull String expression, boolean allowPartialParse) throws DBCException {
        try {
            return CCJSqlParserUtil.parseExpression(expression, allowPartialParse);
        } catch (JSQLParserException e) {
            throw new DBCException("Error parsing conditional SQL expression", e);
        }
    }

    @Nullable
    public static Expression parseCondExpression(@NotNull String expression) throws DBCException {
        return parseCondExpression(expression, true);
    }

    @Nullable
    public static Expression parseCondExpression(@NotNull String expression, boolean allowPartialParse) throws DBCException {
        try {
            return CCJSqlParserUtil.parseCondExpression(expression, allowPartialParse);
        } catch (JSQLParserException e) {
            throw new DBCException("Error parsing SQL expression", e);
        }
    }

    public static boolean isSelectQuery(@Nullable SQLDialect dialect, @NotNull String query) {
        try {
            Statement statement = parseQuery(dialect, query);
            return statement instanceof PlainSelect plainSelect && CommonUtils.isEmpty(plainSelect.getIntoTables());
        } catch (Throwable e) {
            //log.debug(e);
            return false;
        }
    }

    /**
     *  Applying filters changes query formatting (thus it changes column names in expressions)
     *  Solution - always wrap query in subselect + add patched WHERE and ORDER
     *  It is configurable
     *
     * @deprecated Use {@link SQLQueryGenerator#getQueryWithAppliedFilters(DBRProgressMonitor, DBPDataSource, String, DBDDataFilter)}
     * instead
     */
    @NotNull
    @Deprecated
    public static String addFiltersToQuery(
        @Nullable DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull String sqlQuery,
        @NotNull DBDDataFilter dataFilter
    ) throws DBException {
        return dataSource.getSQLDialect().getQueryGenerator().getQueryWithAppliedFilters(
            monitor,
            dataSource,
            sqlQuery,
            dataFilter
        );
    }

    public static boolean isForceFilterSubQuery(@NotNull DBPDataSource dataSource) {
        return dataSource.getSQLDialect().supportsSubqueries() && dataSource.getContainer().getPreferenceStore()
            .getBoolean(ModelPreferences.SQL_FILTER_FORCE_SUBSELECT);
    }

    @NotNull
    public static String injectFiltersToQuery(
        @Nullable DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull String sqlQuery,
        @NotNull DBDDataFilter dataFilter
    ) throws DBException {
        try {
            Statement statement = parseQuery(dataSource.getSQLDialect(), sqlQuery);
            if (statement instanceof PlainSelect plainSelect) {
                if (patchSelectQuery(monitor, dataSource, plainSelect, dataFilter)) {
                    return statement.toString();
                } else if (plainSelect.getWithItemsList() != null && !plainSelect.getWithItemsList().isEmpty()) {
                    addWhereCondition(dataSource, plainSelect, dataFilter);
                    if (dataFilter.hasOrdering()) {
                        addOrderByClause(monitor, dataSource, plainSelect, dataFilter);
                    }
                    return statement.toString();
                }
            }
        } catch (Throwable e) {
            throw new DBException("Error parsing SQL query", e);
        }
        throw new DBException("Can't inject filters to a query that is not a plain SELECT statement");
    }

    private static boolean patchSelectQuery(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull PlainSelect select,
        @NotNull DBDDataFilter filter
    ) throws DBException {
        // WHERE
        if (filter.hasConditions()) {
            for (DBDAttributeConstraint co : filter.getConstraints()) {
                if (co.hasCondition() && !isDynamicAttribute(co.getAttribute())) {
                    Table table = getConstraintTable(dataSource, select, co);
                    if (!isValidTableColumn(monitor, dataSource, table, co)) {
                        return false;
                    }
                    if (table != null) {
                        if (table.getAlias() != null) {
                            co.setEntityAlias(table.getAlias().getName());
                        } else {
                            co.setEntityAlias(table.getName());
                        }
                    } else {
                        return false;
                    }
                }
            }
            addWhereCondition(dataSource, select, filter);
        }
        // ORDER
        if (filter.hasOrdering()) {
            addOrderByClause(monitor, dataSource, select, filter);
        }
        return true;
    }

    private static void addWhereCondition(
        @NotNull DBPDataSource dataSource,
        @NotNull PlainSelect select,
        @NotNull DBDDataFilter filter
    ) throws DBException {
        StringBuilder whereString = new StringBuilder();
        SQLUtils.appendConditionString(filter, dataSource, null, whereString, true);
        String condString = whereString.toString();
        addWhereToSelect(select, condString);
    }

    private static void addOrderByClause(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull PlainSelect select,
        @NotNull DBDDataFilter filter
    ) throws DBException {
        List<OrderByElement> orderByElements = select.getOrderByElements();
        if (orderByElements == null || !isOrderDecidingRows(select)) {
            // A sort of the result replaces the query's ORDER BY: added after it, the sort changed nothing visible.
            // An ORDER BY that decides the rows stays (the generator sorts such queries outside when it can).
            orderByElements = new ArrayList<>();
            select.setOrderByElements(orderByElements);
        }
        List<DBDAttributeConstraint> orderConstraints = filter.getOrderConstraints();
        if (!CommonUtils.isEmpty(orderConstraints)) {
            for (DBDAttributeConstraint co : orderConstraints) {
                Expression orderExpr = getOrderConstraintExpression(monitor, dataSource, select, filter, co);
                OrderByElement element = new OrderByElement();
                element.setExpression(orderExpr);
                if (co.isOrderDescending()) {
                    element.setAsc(false);
                    element.setAscDescPresent(true);
                }
                orderByElements.add(element);
            }
        }
        String filterOrder = filter.getOrder();
        if (!CommonUtils.isEmpty(filterOrder)) {
            // expression = CCJSqlParserUtil.parseExpression(filterOrder);
            // It's good place to use parseExpression, but it parse fine just one column name, not "column1,column2" or "column1 DESC"
            Expression expression = new CustomExpression(filterOrder);
            OrderByElement element = new OrderByElement();
            element.setExpression(expression);
            orderByElements.add(element);
        }
    }

    private static boolean isDynamicAttribute(@Nullable DBSAttributeBase attribute) {
        if (!(attribute instanceof DBDAttributeBinding attributeBinding)) {
            return DBUtils.isDynamicAttribute(attribute);
        }
        return DBUtils.isDynamicAttribute(attributeBinding.getAttribute());
    }

    private static boolean isValidTableColumn(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @Nullable Table table,
        @NotNull DBDAttributeConstraint co
    ) throws DBException {
        DBSAttributeBase attribute = co.getAttribute();

        if (isDynamicAttribute(attribute)) {
            return true;
        }

        if (attribute instanceof DBDAttributeBinding attributeBinding) {
            attribute = attributeBinding.getMetaAttribute();
            DBSEntityAttribute boundAttribute = attributeBinding.getEntityAttribute();
            if (table != null && boundAttribute != null
                && (!(attribute instanceof DBCAttributeMetaData metaData) || metaData.getEntityMetaData() == null)) {
                // The driver gave no table for the column (SQL Server) and the binding found it from the query: the
                // result column is that table's column only if the names match (not an alias, #6927)
                return boundAttribute.getName().equalsIgnoreCase(co.getAttributeName());
            }
        }

        if (table != null && attribute instanceof DBCAttributeMetaData attributeMetaData) {
            DBSEntityAttribute entityAttribute = null;
            DBCEntityMetaData entityMetaData = attributeMetaData.getEntityMetaData();
            var defaultContext = DBUtils.getDefaultContext(dataSource, true);
            if (entityMetaData != null && defaultContext != null) {
                DBSEntity entity = DBUtils.getEntityFromMetaData(monitor, defaultContext, entityMetaData);
                if (entity != null) {
                    entityAttribute = entity.getAttribute(monitor, co.getAttributeName());
                }
            }
            // No such attribute in entity. Do not use table prefix (#6927)
            return entityAttribute != null;
        }
        return true;
    }

    @Nullable
    private static Expression getOrderConstraintExpression(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull PlainSelect select,
        @NotNull DBDDataFilter filter,
        @NotNull DBDAttributeConstraint co
    ) throws DBException {
        String columnName = co.getAttributeName();
        boolean supportsIndexOrdering = dataSource.getSQLDialect().supportsColumnIndexOrdering();
        boolean forceNumeric = filter.hasNameDuplicates(columnName) || !SQLUtils.PATTERN_SIMPLE_NAME.matcher(columnName).matches();
        Expression orderExpr;
        String attrName = DBUtils.getQuotedIdentifier(dataSource, co.getAttributeName());
        if (supportsIndexOrdering && (forceNumeric || attrName.isEmpty())) {
            int orderColumnIndex = SQLUtils.getConstraintOrderIndex(filter, co);
            if (orderColumnIndex == -1) {
                throw new DBException("Can't generate column order: no position found");
            }
            orderExpr = new LongValue(orderColumnIndex);
        } else if (CommonUtils.isJavaIdentifier(attrName)) {
            // Use column table only if there are multiple source tables (joins)
            Table orderTable = CommonUtils.isEmpty(select.getJoins()) ? null : getConstraintTable(dataSource, select, co);

            if (!isValidTableColumn(monitor, dataSource, orderTable, co)) {
                orderTable = null;
            }

            orderExpr = new Column(orderTable, attrName);
        } else {
            // TODO: set tableAlias for all column references in expression
            orderExpr = SQLSemanticProcessor.parseExpression(attrName);
            //orderExpr = new CustomExpression(attrName);
            //orderExpr = new LongValue(co.getAttribute().getOrdinalPosition() + 1);
        }
        return orderExpr;
    }

    /**
     * Extract alias (or source table name) for specified constraint from SQL select.
     * Searches in FROM and JOIN
     */
    @Nullable
    public static Table getConstraintTable(
        @NotNull DBPDataSource dataSource,
        @NotNull PlainSelect select,
        @NotNull DBDAttributeConstraint constraint
    ) {
        String constrTable;
        DBSAttributeBase ca = constraint.getAttribute();
        if (ca instanceof DBDAttributeBinding binding) {
            DBCAttributeMetaData metaAttribute = binding.getMetaAttribute();
            constrTable = metaAttribute == null ? null : metaAttribute.getEntityName();
            if (CommonUtils.isEmpty(constrTable) && binding.getEntityAttribute() != null) {
                // Drivers like SQL Server's don't name the table of a result column; the binding found it from the query
                constrTable = binding.getEntityAttribute().getParentObject().getName();
            }
        } else if (ca instanceof DBSEntityAttribute entityAttribute) {
            constrTable = entityAttribute.getParentObject().getName();
        } else {
            return null;
        }
        if (constrTable == null) {
            return null;
        }
        FromItem fromItem = select.getFromItem();
        Table table = findTableInFrom(dataSource, fromItem, constrTable);
        if (table == null) {
            // Maybe it is a join
            if (!CommonUtils.isEmpty(select.getJoins())) {
                for (Join join : select.getJoins()) {
                    table = findTableInFrom(dataSource, join.getRightItem(), constrTable);
                    if (table != null) {
                        break;
                    }
                }
            }
        }
        return table;
    }

    @Nullable
    public static Table getTableFromSelect(@Nullable Select select) {
        if (select instanceof PlainSelect plainSelect) {
            FromItem fromItem = plainSelect.getFromItem();
            if (fromItem instanceof Table table) {
                return table;
            }
        }
        return null;
    }

    /**
     * Whether the ORDER BY of a SELECT decides which rows it returns, not only their order: with a row limit (TOP, LIMIT,
     * OFFSET, FETCH) or PostgreSQL {@code DISTINCT ON}. Such an ORDER BY can't be removed or replaced without changing
     * the rows.
     */
    public static boolean isOrderDecidingRows(@NotNull Select select) {
        if (select.getLimit() != null || select.getOffset() != null || select.getFetch() != null) {
            return true;
        }
        return select instanceof PlainSelect plainSelect
            && (plainSelect.getTop() != null
                || plainSelect.getDistinct() != null && plainSelect.getDistinct().getOnSelectItems() != null);
    }

    /**
     * Whether a sort of the result must be put around a query ({@code SELECT * FROM (query) ORDER BY ...}) instead of
     * into it: the query's ORDER BY decides its rows ({@link #isOrderDecidingRows}). Not for queries with a WITH clause,
     * which SQL Server does not accept in a derived table; their sort is added after the query's ORDER BY.
     */
    public static boolean isSortedOutside(@Nullable SQLDialect dialect, @NotNull String sqlQuery) {
        try {
            return parseQuery(dialect, sqlQuery) instanceof Select select
                && isOrderDecidingRows(select)
                && CommonUtils.isEmpty(select.getWithItemsList());
        } catch (DBCException e) {
            log.debug("Can't parse the query to check its ordering", e);
            return false;
        }
    }

    /**
     * The ORDER BY of a SELECT query that a sort of the result replaces, as the query writes it
     * ({@code ORDER BY id DESC}), or null if the query has none or its ORDER BY decides the rows
     * ({@link #isOrderDecidingRows}, the sort is then applied to the rows the query returns).
     */
    @Nullable
    public static String getReplaceableOrderBy(@NotNull SQLQuery query) {
        if (query.getStatement() instanceof Select select && !CommonUtils.isEmpty(select.getOrderByElements())
            && !isOrderDecidingRows(select)) {
            return Select.orderByToString(select.getOrderByElements()).trim();
        }
        return null;
    }

    /**
     * Prepares a query to be wrapped as a derived table ({@code SELECT * FROM (query) alias}). SQL Server rejects an
     * ORDER BY in a derived table unless it has TOP, OFFSET or FOR XML, so the query's ORDER BY is moved outside: its
     * items become references to the result columns of the derived table (output alias, selected column, a column of
     * {@code *}, an aliased expression, or a column number). An ORDER BY that decides the rows
     * ({@link #isOrderDecidingRows}) stays in the query, as does one that orders by something that is not a result
     * column (only the query itself can sort by it).
     *
     * @param alias        alias of the derived table
     * @param sortReplaces the wrapped query is sorted by a sort of the result, which replaces the query's ORDER BY
     * @return the query to wrap, and the outer ORDER BY items that keep the query's order or null
     */
    @NotNull
    public static Pair<String, String> moveOrderByOutside(
        @Nullable SQLDialect dialect,
        @NotNull String sqlQuery,
        @NotNull String alias,
        boolean sortReplaces
    ) {
        Statement statement;
        try {
            statement = parseQuery(dialect, sqlQuery);
        } catch (DBCException e) {
            log.debug("Can't parse the query to move its ORDER BY, wrapping it as it is", e);
            return new Pair<>(sqlQuery, null);
        }
        if (!(statement instanceof Select select) || CommonUtils.isEmpty(select.getOrderByElements())
            || isOrderDecidingRows(select)) {
            return new Pair<>(sqlQuery, null);
        }
        String outerOrder = null;
        if (!sortReplaces) {
            List<String> items = new ArrayList<>();
            for (OrderByElement element : select.getOrderByElements()) {
                Expression reference = getResultColumnReference(select, element.getExpression(), alias);
                if (reference == null) {
                    return new Pair<>(sqlQuery, null);
                }
                items.add(new OrderByElement()
                    .withExpression(reference)
                    .withAsc(element.isAsc())
                    .withAscDescPresent(element.isAscDescPresent())
                    .withNullOrdering(element.getNullOrdering())
                    .toString());
            }
            outerOrder = String.join(", ", items);
        }
        select.setOrderByElements(null);
        return new Pair<>(statement.toString(), outerOrder);
    }

    /**
     * The result column of a query that an ORDER BY item of the query names, as a column of the derived table, or null.
     * Names are resolved as SQL does: output aliases first, then selected columns, then the columns of {@code *}.
     */
    @Nullable
    private static Expression getResultColumnReference(@NotNull Select select, @NotNull Expression expression, @NotNull String alias) {
        if (expression instanceof LongValue) {
            // A column number stays valid: SELECT * keeps the columns in their order
            return expression;
        }
        List<SelectItem<?>> items = select instanceof PlainSelect plainSelect ? plainSelect.getSelectItems() : null;
        Table derived = new Table(alias);
        if (expression instanceof Column column) {
            String name = column.getUnquotedColumnName();
            Table table = column.getTable() == null || CommonUtils.isEmpty(column.getTable().getName()) ? null : column.getTable();
            if (items == null) {
                // UNION and the like: their ORDER BY already names result columns
                return table == null ? new Column(derived, column.getColumnName()) : null;
            }
            if (table == null) {
                for (SelectItem<?> item : items) {
                    if (item.getAlias() != null && item.getAlias().getUnquotedName().equalsIgnoreCase(name)) {
                        return new Column(derived, item.getAlias().getName());
                    }
                }
            }
            for (SelectItem<?> item : items) {
                if (item.getExpression() instanceof Column selected && selected.getUnquotedColumnName().equalsIgnoreCase(name)
                    && (table == null || selected.getTable() == null || CommonUtils.isEmpty(selected.getTable().getName())
                        || isSameTable(selected.getTable(), table))) {
                    return new Column(derived, item.getAlias() != null ? item.getAlias().getName() : selected.getColumnName());
                }
            }
            for (SelectItem<?> item : items) {
                if (item.getExpression() instanceof AllTableColumns tableColumns) {
                    if (table == null || isSameTable(tableColumns.getTable(), table)) {
                        return new Column(derived, column.getColumnName());
                    }
                } else if (item.getExpression() instanceof AllColumns) {
                    return new Column(derived, column.getColumnName());
                }
            }
            return null;
        }
        if (items != null) {
            String text = expression.toString();
            for (SelectItem<?> item : items) {
                if (item.getAlias() != null && item.getExpression() != null && item.getExpression().toString().equalsIgnoreCase(text)) {
                    return new Column(derived, item.getAlias().getName());
                }
            }
        }
        return null;
    }

    private static boolean isSameTable(@Nullable Table table1, @NotNull Table table2) {
        return table1 != null && table1.getUnquotedName() != null && table1.getUnquotedName().equalsIgnoreCase(table2.getUnquotedName());
    }

    @Nullable
    private static Table findTableInFrom(@NotNull DBPDataSource dataSource, @Nullable FromItem fromItem, @NotNull String tableName) {
        if (fromItem instanceof Table table && DBUtils.getUnQuotedIdentifier(dataSource, tableName)
            .equals(DBUtils.getUnQuotedIdentifier(dataSource, table.getName()))) {
            return table;
        }
        return null;
    }

    @Nullable
    public static Table findTableByNameOrAlias(@Nullable Select select, @Nullable String tableName) {
        if (select instanceof PlainSelect plainSelect) {
            FromItem fromItem = plainSelect.getFromItem();
            if (fromItem instanceof Table table && equalTables(table, tableName)) {
                return table;
            }
            for (Join join : CommonUtils.safeCollection(plainSelect.getJoins())) {
                if (join.getRightItem() instanceof Table table && equalTables(table, tableName)) {
                    return table;
                }
            }
        }
        return null;
    }

    public static boolean equalTables(@Nullable Table t1, @Nullable String name) {
        if (t1 == null || name == null) {
            return true;
        }
        if (t1.getAlias() != null) {
            return CommonUtils.equalObjects(t1.getAlias().getName(), name);
        } else {
            return CommonUtils.equalObjects(t1.getName(), name);
        }
    }

    public static void addWhereToSelect(@NotNull PlainSelect select, @Nullable String condString) throws DBCException {
        Expression filterWhere = SQLSemanticProcessor.parseCondExpression(condString);
        addWhereToSelect(select, filterWhere);
    }

    public static void addWhereToSelect(@NotNull PlainSelect select, @Nullable Expression conditionExpr) {
        Expression sourceWhere = select.getWhere();
        if (sourceWhere == null) {
            select.setWhere(conditionExpr);
        } else {
            select.setWhere(new AndExpression(
                new ParenthesedExpressionList<>(sourceWhere),
                new ParenthesedExpressionList<>(conditionExpr)
            ));
        }
    }


    /**
     * Returns a simple table name in the form of schema.table with proper quoting rules.
     *
     * Examples of transformations:
     * <ul>
     *   <li>{@code a.b    -> a.b}</li>
     *   <li>{@code "a.b" -> "a.b"}</li>
     *   <li>{@code "a".b -> "a".b}</li>
     *   <li>{@code a."b" -> a."b"}</li>
     *   <li>{@code "a"."b" -> "a.b"}</li>
     * </ul>
     *
     * @param select  SQL SELECT statement
     * @param dialect SQL dialect used to check and quote identifiers
     * @return string representation of the table name
     * @throws DBException if the table cannot be determined from the SELECT
     */
    @NotNull
    public static String getSimpleTableName(@NotNull PlainSelect select, @NotNull SQLDialect dialect) throws DBException {
        if (!(select.getFromItem() instanceof Table table)) {
            throw new DBException("Cannot determine table name: FROM is " +
                select.getFromItem().getClass().getSimpleName());
        }
        final String name   = table.getName();
        final String schema = table.getSchemaName();

        if (schema == null || schema.isEmpty()) {
            return name;
        }

        final boolean schemaQuoted = dialect.isQuotedIdentifier(schema);
        final boolean nameQuoted   = dialect.isQuotedIdentifier(name);

        if (schemaQuoted && nameQuoted) {
            final String merged = DBUtils.getUnQuotedIdentifier(schema, "\"")
                + '.'
                + DBUtils.getUnQuotedIdentifier(name, "\"");
            return dialect.getQuotedIdentifier(merged, true, true);
        }

        return schema + '.' + name;
    }

    @NotNull
    public static Token[] parseSqlTextForTokens(@NotNull SQLDialect dialect, @NotNull String sqlText) throws DBCException {
        Statement statement = SQLSemanticProcessor.parseQuery(dialect, sqlText);
        LinkedList<Token> tokensList = new LinkedList<>();
        for (Token t = ((ASTNodeAccess) statement).getASTNode().jjtGetFirstToken(); t != null; t = t.next) {
            if (!t.image.isEmpty()) {
                tokensList.add(t);
            }
        }
        return tokensList.toArray(Token[]::new);
    }
}
