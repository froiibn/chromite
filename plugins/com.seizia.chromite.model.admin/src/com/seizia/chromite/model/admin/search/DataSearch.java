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
package com.seizia.chromite.model.admin.search;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPForeignObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeConstraint;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCLogicalOperator;
import com.seizia.chromite.model.exec.DBCResultSet;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.DBCStatementType;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Data search: finds a value in the rows of several tables (SWT "Search data", {@code SearchDataQuery}).
 *
 * 설계: SWT는 테이블마다 {@link DBDDataFilter}(열마다 LIKE·ILIKE·=, 조건을 OR로 묶음)를 만들어 {@code DBSDataContainer#readData}로 읽었다.
 * 조건을 고르는 규칙(문자열은 LIKE {@code %값%}, 대소문자를 무시하면 ILIKE, 숫자는 =, UUID는 =, 불리언·이진은 건너뜀)은 그대로 쓴다.
 * 다른 점과 이유:
 * <ul>
 *     <li>값을 SQL에 넣지 않는다. {@code readData}는 모델의 쿼리 생성기로 값을 SQL 문자열에 넣는다(inline). 여기서는 같은 생성기
 *     ({@link SQLUtils#appendConditionString})를 inline 없이 불러 {@code ?} 자리를 만들고, 값은 열의 값 처리기
 *     ({@link DBDValueHandler#bindValueObject})로 바인딩한다. 식별자는 모델이 따옴표 처리한 이름만 쓴다.</li>
 *     <li>읽기 전용 SELECT만 실행한다. 테이블마다 최대 행 수({@link Options#rowsPerTable})까지만 읽는다({@link DBCStatement#setLimit}과
 *     읽기 반복 둘 다 제한).</li>
 *     <li>사용자의 트랜잭션에 닿지 않는다. 검색은 자기 격리된 실행 컨텍스트({@link DBSInstance#openIsolatedContext})를 열고 늘 자동 커밋으로
 *     둔 뒤 끝나면 닫는다(Activity Monitor의 {@code AdminConnection}과 같은 이유: 수동 커밋이면 첫 읽기가 연 트랜잭션이 남는다).
 *     연결 설정이 "단일 연결"이면 사용자의 기본 컨텍스트밖에 없으므로 그 컨텍스트가 자동 커밋일 때만 쓰고, 수동 커밋이면
 *     {@link SharedTransactionException}으로 거절한다(트랜잭션 안전 규칙: 사용자의 트랜잭션 안에서 읽지 않고 커밋 방식도 바꾸지 않는다).
 *     PostgreSQL처럼 데이터베이스마다 인스턴스가 있는 DB는 범위 객체의 인스턴스({@link DBUtils#getObjectOwnerInstance})에서 연다.</li>
 *     <li>취소: 실행 중인 문장은 JDBC 문장이 작업 모니터에 걸어 두는 블록으로 취소된다(작업 취소가 {@code Statement#cancel}을 부름).
 *     읽기 반복도 모니터를 본다.</li>
 *     <li>어느 열이 맞았는지: DB가 돌려준 행의 값을 같은 규칙(LIKE 패턴을 정규식으로, 대소문자 무시)으로 다시 비교해 열마다 센다.
 *     DB의 정렬 규칙(악센트 무시 등) 때문에 어느 열로도 설명되지 않는 행은 "열 없음" 항목({@link ColumnHit#column()}이 null)으로 센다.</li>
 *     <li>한 테이블의 OR 쿼리가 실패하면(한 열의 형식이 LIKE를 받지 않는 등) 열마다 따로 다시 찾고, 실패한 열만 문제로 남긴다.
 *     SWT는 테이블 전체를 건너뛰었다.</li>
 *     <li>와일드카드: 입력의 {@code %}와 {@code _}는 SWT처럼 LIKE 와일드카드로 남는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-data-search.md)
 */
public final class DataSearch implements AutoCloseable {

    private static final Log log = Log.getLog(DataSearch.class);

    /**
     * Characters of a long text value read to check which column matched and to show a sample.
     */
    private static final int MAX_TEXT_CHECK = 65536;
    private static final int MAX_SAMPLE = 200;

    /**
     * What to find.
     *
     * @param text          the value; {@code %} and {@code _} are LIKE wildcards
     * @param caseSensitive use LIKE instead of ILIKE where the database has ILIKE (elsewhere its collation decides)
     * @param numbers       also compare numeric columns when the text is a number
     * @param longText      also search long text (CLOB, text) columns
     * @param rowsPerTable  rows read at most from each table
     */
    public record Options(@NotNull String text, boolean caseSensitive, boolean numbers, boolean longText, int rowsPerTable) {
    }

    /**
     * Rows of a table matched by one column.
     *
     * @param column the column, or null for rows that no column's value explains (database collation rules)
     * @param rows   matching rows among those read
     * @param more   the row limit was reached: there may be more
     * @param sample the first matching value (shortened), or null
     * @param filter the condition that selects these rows (values, not parameters) to open them in a query window
     */
    public record ColumnHit(@Nullable DBSEntityAttribute column, int rows, boolean more, @Nullable String sample, @NotNull DBDDataFilter filter) {
    }

    /**
     * A column whose search failed.
     */
    public record ColumnProblem(@NotNull String column, @NotNull Throwable error) {
    }

    /**
     * The result of one table: hits by column (empty when no row matched) and the columns that could not be searched.
     */
    public record TableHit(@NotNull DBSEntity table, @NotNull List<ColumnHit> columns, @NotNull List<ColumnProblem> problems) {
    }

    /**
     * The connection allows one connection only and it is in manual-commit mode: a search would read inside the user's
     * transaction.
     */
    public static final class SharedTransactionException extends DBException {
        SharedTransactionException() {
            super("The connection uses a single connection in manual-commit mode");
        }
    }

    /**
     * One searched column: the condition and the client-side check of a fetched value.
     */
    private record Criterion(@NotNull DBSEntityAttribute attribute, @NotNull DBCLogicalOperator operator, @NotNull Object value,
                             @NotNull Object expected) {
    }

    private final Options options;
    private final DBCExecutionContext context;
    private final boolean ownContext;
    private final Pattern textPattern;

    private DataSearch(@NotNull Options options, @NotNull DBCExecutionContext context, boolean ownContext) {
        this.options = options;
        this.context = context;
        this.ownContext = ownContext;
        this.textPattern = likeToPattern("%" + options.text() + "%"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Opens the search's own auto-commit context for the database of the scope object (see the class comment).
     *
     * @throws SharedTransactionException the connection has a single connection in manual-commit mode
     */
    @NotNull
    public static DataSearch open(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject scope, @NotNull Options options) throws DBException {
        DBPDataSource dataSource = scope.getDataSource();
        if (dataSource == null) {
            throw new DBException("Not connected");
        }
        if (dataSource.getContainer().isForceUseSingleConnection()) {
            DBCExecutionContext context = DBUtils.getDefaultContext(scope, false);
            if (context == null) {
                throw new DBException("Not connected");
            }
            DBCTransactionManager transactions = DBUtils.getTransactionManager(context);
            if (transactions != null && !transactions.isAutoCommit()) {
                throw new SharedTransactionException();
            }
            return new DataSearch(options, context, false);
        }
        DBSInstance instance = DBUtils.getObjectOwnerInstance(scope);
        if (instance == null) {
            throw new DBException("Not connected");
        }
        DBCExecutionContext context = instance.openIsolatedContext(monitor, "Chromite data search", null); //$NON-NLS-1$
        try {
            DBCTransactionManager transactions = DBUtils.getTransactionManager(context);
            if (transactions != null && !transactions.isAutoCommit()) {
                transactions.setAutoCommit(monitor, true);
            }
        } catch (DBException e) {
            context.close();
            throw e;
        }
        return new DataSearch(options, context, true);
    }

    /**
     * Searches one table.
     *
     * @return the hits, or null when the table has no column the value can be compared with
     */
    @Nullable
    public TableHit search(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity table) throws DBException {
        if (table instanceof DBPForeignObject foreign && foreign.isForeignObject()) {
            // A foreign table reads another server, as SWT's default
            return null;
        }
        List<Criterion> criteria = createCriteria(monitor, table);
        if (criteria.isEmpty()) {
            return null;
        }
        List<ColumnHit> hits = new ArrayList<>();
        List<ColumnProblem> problems = new ArrayList<>();
        try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Search data in " + table.getName())) { //$NON-NLS-1$
            try {
                hits.addAll(read(session, table, criteria));
            } catch (DBException e) {
                if (monitor.isCanceled() || criteria.size() == 1) {
                    throw e;
                }
                // One column type may refuse the operator: search the columns one by one and keep the failures
                log.debug("Search in " + table.getName() + " failed, searching column by column", e); //$NON-NLS-1$ //$NON-NLS-2$
                for (Criterion criterion : criteria) {
                    if (monitor.isCanceled()) {
                        break;
                    }
                    try {
                        hits.addAll(read(session, table, List.of(criterion)));
                    } catch (DBException columnError) {
                        if (monitor.isCanceled()) {
                            throw columnError;
                        }
                        problems.add(new ColumnProblem(criterion.attribute().getName(), columnError));
                    }
                }
            }
        }
        return new TableHit(table, hits, problems);
    }

    /**
     * Runs one parameterized SELECT of the criteria's columns with the criteria ORed, and counts the rows by column. With
     * several columns, a row that no column's value explains gets a hit without a column.
     */
    @NotNull
    private List<ColumnHit> read(@NotNull DBCSession session, @NotNull DBSEntity table, @NotNull List<Criterion> criteria) throws DBException {
        DBPDataSource dataSource = table.getDataSource();
        DBDDataFilter filter = createFilter(criteria);
        StringBuilder sql = new StringBuilder("SELECT "); //$NON-NLS-1$
        for (int i = 0; i < criteria.size(); i++) {
            if (i > 0) {
                sql.append(", "); //$NON-NLS-1$
            }
            sql.append(DBUtils.getObjectFullName(criteria.get(i).attribute(), DBPEvaluationContext.DML));
        }
        sql.append(" FROM ").append(DBUtils.getObjectFullName(table, DBPEvaluationContext.DML)).append(" WHERE "); //$NON-NLS-1$ //$NON-NLS-2$
        // The model's condition generator without inlined values: one "?" for each criterion, in order
        SQLUtils.appendConditionString(filter, dataSource, null, sql, false);

        int limit = Math.max(1, options.rowsPerTable());
        int[] counts = new int[criteria.size()];
        String[] samples = new String[criteria.size()];
        int unexplained = 0;
        String unexplainedSample = null;
        int rows = 0;
        boolean more = false;
        DBRProgressMonitor monitor = session.getProgressMonitor();
        try (DBCStatement statement = session.prepareStatement(DBCStatementType.QUERY, sql.toString(), false, false, false)) {
            for (int i = 0; i < criteria.size(); i++) {
                Criterion criterion = criteria.get(i);
                DBDValueHandler handler = DBUtils.findValueHandler(session, criterion.attribute());
                handler.bindValueObject(session, statement, criterion.attribute(), i, criterion.value());
            }
            // One more row tells that there are more
            statement.setLimit(0, limit + 1L);
            if (statement.executeStatement()) {
                try (DBCResultSet resultSet = statement.openResultSet()) {
                    while (resultSet != null && !monitor.isCanceled() && resultSet.nextRow()) {
                        if (rows >= limit) {
                            more = true;
                            break;
                        }
                        rows++;
                        boolean explained = false;
                        String firstText = null;
                        for (int i = 0; i < criteria.size(); i++) {
                            Object value = readValue(resultSet.getAttributeValue(i));
                            if (firstText == null && value != null) {
                                firstText = CommonUtils.toString(value);
                            }
                            if (matches(criteria.get(i), value)) {
                                explained = true;
                                counts[i]++;
                                if (samples[i] == null) {
                                    samples[i] = shorten(CommonUtils.toString(value));
                                }
                            }
                        }
                        if (!explained && criteria.size() == 1) {
                            // Its only column matched by the database's rules (collation)
                            counts[0]++;
                            if (samples[0] == null && firstText != null) {
                                samples[0] = shorten(firstText);
                            }
                        } else if (!explained) {
                            unexplained++;
                            if (unexplainedSample == null && firstText != null) {
                                unexplainedSample = shorten(firstText);
                            }
                        }
                    }
                }
            }
        }
        List<ColumnHit> hits = new ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) {
            if (counts[i] > 0) {
                hits.add(new ColumnHit(criteria.get(i).attribute(), counts[i], more, samples[i], createFilter(List.of(criteria.get(i)))));
            }
        }
        if (unexplained > 0) {
            hits.add(new ColumnHit(null, unexplained, more, unexplainedSample, filter));
        }
        return hits;
    }

    /**
     * The searched columns of a table and their conditions (the SWT {@code SearchDataQuery} rules).
     */
    @NotNull
    private List<Criterion> createCriteria(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity table) throws DBException {
        String text = options.text();
        BigDecimal number = options.numbers() ? parseNumber(text) : null;
        UUID uuid = parseUuid(text);
        List<Criterion> criteria = new ArrayList<>();
        for (DBSEntityAttribute attribute : CommonUtils.safeCollection(table.getAttributes(monitor))) {
            if (DBUtils.isPseudoAttribute(attribute) || DBUtils.isHiddenObject(attribute)) {
                continue;
            }
            DBCLogicalOperator[] operators = DBUtils.getAttributeOperators(attribute);
            DBPDataKind kind = attribute.getDataKind();
            switch (kind) {
                case NUMERIC -> {
                    Object value = number == null ? null : numberFor(attribute, number);
                    if (value != null && ArrayUtils.contains(operators, DBCLogicalOperator.EQUALS)) {
                        criteria.add(new Criterion(attribute, DBCLogicalOperator.EQUALS, value, number));
                    }
                }
                case STRING, CONTENT -> {
                    if (kind == DBPDataKind.CONTENT && !options.longText()) {
                        continue;
                    }
                    String pattern = "%" + text + "%"; //$NON-NLS-1$ //$NON-NLS-2$
                    if (!options.caseSensitive() && ArrayUtils.contains(operators, DBCLogicalOperator.ILIKE)) {
                        criteria.add(new Criterion(attribute, DBCLogicalOperator.ILIKE, pattern, textPattern));
                    } else if (ArrayUtils.contains(operators, DBCLogicalOperator.LIKE)) {
                        criteria.add(new Criterion(attribute, DBCLogicalOperator.LIKE, pattern, textPattern));
                    } else if (ArrayUtils.contains(operators, DBCLogicalOperator.EQUALS)) {
                        criteria.add(new Criterion(attribute, DBCLogicalOperator.EQUALS, text, text));
                    }
                }
                default -> {
                    // Other types are compared only when the value is a UUID and the column is a UUID column
                    if (uuid != null && DBConstants.TYPE_NAME_UUID.equalsIgnoreCase(attribute.getTypeName())
                        && ArrayUtils.contains(operators, DBCLogicalOperator.EQUALS)) {
                        criteria.add(new Criterion(attribute, DBCLogicalOperator.EQUALS, uuid.toString(), uuid.toString()));
                    }
                }
            }
        }
        return criteria;
    }

    /**
     * The number to bind for a numeric column, or null when no value of the column can equal it or the column is a
     * flag (bit). The JDBC number handler
     * narrows a value to the column type ({@code setInt(number.intValue())}), so a fraction or a too large number would
     * otherwise find wrong rows (3.5 = 3 in an INTEGER column).
     */
    @Nullable
    private static Object numberFor(@NotNull DBSEntityAttribute attribute, @NotNull BigDecimal number) {
        boolean integral = number.signum() == 0 || number.stripTrailingZeros().scale() <= 0;
        Long longValue = null;
        if (integral) {
            try {
                longValue = number.longValueExact();
            } catch (ArithmeticException e) {
                // Larger than a long
            }
        }
        switch (attribute.getTypeID()) {
            case Types.BIT, Types.BOOLEAN -> {
                // A flag (SQL Server bit is a numeric kind): SWT skips booleans, and 42 would compare as true
                return null;
            }
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> {
                return longValue;
            }
            case Types.NUMERIC, Types.DECIMAL, Types.FLOAT, Types.DOUBLE, Types.REAL -> {
                return longValue != null ? (Object) longValue : number;
            }
            default -> {
                if (longValue != null) {
                    return longValue;
                }
                // The handler binds a fraction as a long when the column has no scale
                return CommonUtils.toInt(attribute.getScale()) > 0 ? number : null;
            }
        }
    }

    @Nullable
    private static BigDecimal parseNumber(@NotNull String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static UUID parseUuid(@NotNull String text) {
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * A fetched value as text or number; a CLOB is read up to {@value #MAX_TEXT_CHECK} characters.
     */
    @Nullable
    private static Object readValue(@Nullable Object value) throws DBCException {
        if (value instanceof Clob clob) {
            try {
                long length = clob.length();
                return clob.getSubString(1, (int) Math.min(length, MAX_TEXT_CHECK));
            } catch (SQLException e) {
                throw new DBCException("Can't read a text value", e);
            }
        }
        return value;
    }

    private static boolean matches(@NotNull Criterion criterion, @Nullable Object value) {
        if (value == null) {
            return false;
        }
        Object expected = criterion.expected();
        if (expected instanceof Pattern pattern) {
            return pattern.matcher(CommonUtils.toString(value)).matches();
        }
        if (expected instanceof BigDecimal number) {
            BigDecimal actual = value instanceof Number ? parseNumber(value.toString()) : null;
            return actual != null && actual.compareTo(number) == 0;
        }
        return CommonUtils.toString(value).equalsIgnoreCase(expected.toString());
    }

    /**
     * A LIKE pattern as a regular expression ({@code %} any text, {@code _} one character), ignoring case: where the
     * database compares case-sensitively it returns only exact-case rows anyway.
     */
    @NotNull
    private static Pattern likeToPattern(@NotNull String like) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : like.toCharArray()) {
            if (c == '%' || c == '_') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '%' ? ".*" : "."); //$NON-NLS-1$ //$NON-NLS-2$
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }

    @NotNull
    private static DBDDataFilter createFilter(@NotNull List<Criterion> criteria) {
        List<DBDAttributeConstraint> constraints = new ArrayList<>();
        for (Criterion criterion : criteria) {
            DBDAttributeConstraint constraint = new DBDAttributeConstraint(criterion.attribute(), constraints.size());
            constraint.setVisible(true);
            constraint.setOperator(criterion.operator());
            constraint.setValue(criterion.value());
            constraints.add(constraint);
        }
        DBDDataFilter filter = new DBDDataFilter(constraints);
        filter.setAnyConstraint(true);
        return filter;
    }

    @NotNull
    private static String shorten(@NotNull String value) {
        String line = value.replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        return line.length() > MAX_SAMPLE ? line.substring(0, MAX_SAMPLE) + "…" : line; //$NON-NLS-1$
    }

    /**
     * Closes the search's own context (not the user's default context of a single-connection connection).
     */
    @Override
    public void close() {
        if (ownContext) {
            try {
                context.close();
            } catch (Throwable e) {
                log.debug("Error closing the data search context", e); //$NON-NLS-1$
            }
        }
    }
}
