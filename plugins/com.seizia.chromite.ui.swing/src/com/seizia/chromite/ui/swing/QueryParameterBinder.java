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
package com.seizia.chromite.ui.swing;

import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBExecUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLQueryParameter;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Query Library 쿼리의 매개변수 바인딩: {@code :name}은 prepared statement의 {@code ?}로 바꿔 값을 바인딩하고,
 * {@code ${name}} 변수는 값을 SQL 글자로 끼운다.
 *
 * 설계:
 * <ul>
 *     <li>매개변수 찾기는 SWT 편집기와 같은 모델 파서다: {@code SQLScriptBatches.extractQueries}가 문장마다
 *     {@code SQLScriptParser.parseParametersAndVariables}(→ {@code DefaultSQLQueryParameterParser})로 찾은 {@link SQLQueryParameter}
 *     목록을 붙인다. 문자열·주석 안의 {@code :x}, PostgreSQL의 {@code ::type}, DDL 문장은 파서가 이미 건너뛴다.</li>
 *     <li>SWT 편집기는 값을 SQL 글자로 치환한다({@code SQLScriptContext.fillQueryParameters} → {@code SQLUtils.fillQueryParameters},
 *     사용자가 {@code 'text'}처럼 따옴표까지 입력). Query Library는 다른 사람이 만든 쿼리에 값만 넣는 용도라 이 방식은 SQL 주입과 따옴표 실수를
 *     부른다. 그래서 {@code :name} 자리는 {@code ?}로 바꾸고 형식에 맞는 Java 값({@link QueryLibraryParameter#toJdbcValue})을 JDBC로 바인딩한다
 *     (엔진의 {@code SQLQueryExecuteHandler.bindParameters}). 같은 이름이 여러 번 나오면 자리마다 같은 값을 바인딩한다.</li>
 *     <li>{@code ${name}}·{@code {{name}}}은 SWT에서도 텍스트 치환 변수다(식별자·SQL 조각 자리). 바인딩할 수 없으므로 값을 그대로 끼우고,
 *     대화상자에 "SQL text"로 표시한다(문서 "매개변수 바인딩"). 익명 {@code ?}는 이름이 없어 정의를 붙일 수 없으므로 받지 않는다.</li>
 *     <li>쿼리의 기본 DB/스키마가 있으면 실행 전에 연결의 기본 문맥에 설정한다(쿼리 창의 DB/스키마 콤보와 같은 API).</li>
 *     <li>실행 전(EDT)의 매개변수 목록은 연결 없이 만들어야 하므로 {@link #detect}가 같은 규칙을 간단히 따르는 문자 단위 검사로 찾는다.
 *     실행할 때 파서가 찾은 이름에 값이 없으면 실행하지 않고 오류를 낸다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md "매개변수 바인딩")
 * - 2026-11-06 Query History용 글자({@link #getHistoryText}): 값을 SQL 리터럴로 넣은 원래 문장. 엔진이 보낸 {@code ?} 문장이 기록되어
 *   기록에서 다시 열거나 실행할 수 없던 문제 (docs/chromite/swing-query-library.md "매개변수 바인딩")
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 */
final class QueryParameterBinder implements SqlParameterBinding {

    /**
     * Values bound to the {@code ?} markers of one rewritten query, in order; kept as the query's data.
     *
     * @param historyText the query with the values written in as SQL literals, for the query history
     */
    private record BoundValues(@NotNull List<Object> values, @NotNull List<QueryLibraryParameter.Type> types, @NotNull String historyText) {
    }

    // Lower-case name -> value as typed
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, QueryLibraryParameter> definitions = new LinkedHashMap<>();
    @Nullable
    private final String database;
    @Nullable
    private final String schema;

    /**
     * @param item   query with the parameter definitions and the default database/schema (not changed)
     * @param values values by parameter name
     */
    QueryParameterBinder(@NotNull QueryLibraryItem item, @NotNull Map<String, String> values) {
        values.forEach((name, value) -> this.values.put(name.toLowerCase(Locale.ROOT), value));
        for (QueryLibraryParameter parameter : item.getParameters()) {
            definitions.put(parameter.getName().toLowerCase(Locale.ROOT), parameter.copy());
        }
        this.database = item.getDatabase();
        this.schema = item.getSchema();
    }

    /**
     * Whether binding is needed at all (parameters or a default database/schema).
     */
    boolean isNeeded() {
        return !values.isEmpty() || database != null || schema != null;
    }

    @NotNull
    @Override
    public List<SQLScriptElement> prepare(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull List<SQLScriptElement> elements
    ) throws DBException {
        applyDefaults(monitor, context);
        List<SQLScriptElement> result = new ArrayList<>(elements.size());
        for (SQLScriptElement element : elements) {
            result.add(element instanceof SQLQuery query && !CommonUtils.isEmpty(query.getParameters()) ? rewrite(query) : element);
        }
        return result;
    }

    @Override
    public boolean isBound(@NotNull SQLQuery query) {
        return query.getData() instanceof BoundValues;
    }

    @Override
    public void bind(@NotNull DBCStatement statement, @NotNull SQLQuery query) throws DBCException {
        if (!(query.getData() instanceof BoundValues bound)) {
            return;
        }
        if (!(statement instanceof PreparedStatement prepared)) {
            throw new DBCException("Query parameters can only be bound on JDBC connections");
        }
        try {
            for (int i = 0; i < bound.values().size(); i++) {
                Object value = bound.values().get(i);
                if (value == null) {
                    prepared.setNull(i + 1, toSqlType(bound.types().get(i)));
                } else {
                    prepared.setObject(i + 1, value);
                }
            }
        } catch (SQLException e) {
            throw new DBCException("Can't bind query parameters", e);
        }
    }

    @NotNull
    @Override
    public String getHistoryText(@NotNull SQLQuery query) {
        return query.getData() instanceof BoundValues bound ? bound.historyText() : query.getText();
    }

    /**
     * Replaces the parameter markers of a parsed query: {@code :name} by {@code ?} with a bound value, {@code ${name}}
     * by its value as SQL text.
     */
    @NotNull
    private SQLQuery rewrite(@NotNull SQLQuery query) throws DBException {
        List<SQLQueryParameter> parameters = new ArrayList<>(query.getParameters());
        parameters.sort(Comparator.comparingInt(SQLQueryParameter::getTokenOffset));
        String text = query.getText();
        StringBuilder newText = new StringBuilder(text.length());
        // The same text with the values instead of the markers: what the query history keeps and can run again
        StringBuilder historyText = new StringBuilder(text.length());
        List<Object> boundValues = new ArrayList<>();
        List<QueryLibraryParameter.Type> boundTypes = new ArrayList<>();
        int position = 0;
        for (SQLQueryParameter parameter : parameters) {
            int start = parameter.getTokenOffset();
            int end = start + parameter.getTokenLength();
            if (start < position || end > text.length()) {
                continue;
            }
            String originalName = CommonUtils.notEmpty(parameter.getOriginalName());
            if (!parameter.isNamed() && !isTextSubstitution(originalName)) {
                throw new DBException(NLS.bind(ToolsMessages.param_error_unnamed, originalName));
            }
            String key = parameter.getName().toLowerCase(Locale.ROOT);
            if (!values.containsKey(key)) {
                throw new DBException(NLS.bind(ToolsMessages.param_error_no_value, originalName));
            }
            String value = values.get(key);
            newText.append(text, position, start);
            historyText.append(text, position, start);
            if (isTextSubstitution(originalName)) {
                newText.append(CommonUtils.notEmpty(value));
                historyText.append(CommonUtils.notEmpty(value));
            } else {
                QueryLibraryParameter definition = definitions.get(key);
                QueryLibraryParameter.Type type = definition != null ? definition.getType() : QueryLibraryParameter.Type.TEXT;
                Object boundValue;
                try {
                    boundValue = definition != null ? definition.toJdbcValue(value) : CommonUtils.nullIfEmpty(value);
                } catch (IllegalArgumentException e) {
                    throw new DBException(NLS.bind(ToolsMessages.param_error_value, originalName, ErrorText.describeLine(e)));
                }
                boundValues.add(boundValue);
                boundTypes.add(type);
                newText.append('?');
                historyText.append(toSqlLiteral(query.getDataSource(), boundValue, value));
            }
            position = end;
        }
        newText.append(text, position, text.length());
        historyText.append(text, position, text.length());
        SQLQuery rewritten = new SQLQuery(query.getDataSource(), newText.toString(), query.getOffset(), query.getLength());
        if (!boundValues.isEmpty()) {
            rewritten.setData(new BoundValues(boundValues, boundTypes, historyText.toString()));
        }
        return rewritten;
    }

    /**
     * A bound value as SQL text for the query history: numbers as they are, strings, dates and times as quoted strings
     * (the dialect's quoting; the servers convert them as for the bound value), booleans as {@code TRUE}/{@code FALSE}
     * (SQL Server has no boolean literal: such a history entry fails there with an error rather than comparing wrongly).
     *
     * @param typed the value as typed (dates and times keep the user's format)
     */
    @NotNull
    private static String toSqlLiteral(@Nullable DBPDataSource dataSource, @Nullable Object value, @Nullable String typed) {
        if (value == null) {
            return "NULL"; //$NON-NLS-1$
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean flag) {
            return flag ? "TRUE" : "FALSE"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String text = value instanceof String string ? string : CommonUtils.notEmpty(typed).trim();
        return dataSource != null ? SQLUtils.quoteString(dataSource, text) : "'" + text.replace("'", "''") + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Makes the query's default database/schema the default of the execution context, as the database combo of a
     * query window does ({@code DBExecUtils.setExecutionContextDefaults}).
     */
    private void applyDefaults(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext context) throws DBException {
        if (database == null && schema == null) {
            return;
        }
        DBCExecutionContextDefaults<?, ?> defaults = context.getContextDefaults();
        if (defaults == null) {
            return;
        }
        DBSObject currentCatalog = defaults.getDefaultCatalog();
        DBSObject currentSchema = defaults.getDefaultSchema();
        String currentCatalogName = currentCatalog != null ? currentCatalog.getName() : null;
        boolean catalogMatches = database == null || database.equalsIgnoreCase(CommonUtils.notEmpty(currentCatalogName));
        boolean schemaMatches = schema == null || (currentSchema != null && schema.equalsIgnoreCase(currentSchema.getName()));
        if (catalogMatches && schemaMatches) {
            return;
        }
        DBPDataSource dataSource = context.getDataSource();
        DBExecUtils.setExecutionContextDefaults(monitor, dataSource, context,
            database != null ? database : currentCatalogName, currentCatalogName, schema);
    }

    private static boolean isTextSubstitution(@NotNull String originalName) {
        return originalName.startsWith("$") || originalName.startsWith("{{"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int toSqlType(@NotNull QueryLibraryParameter.Type type) {
        return switch (type) {
            case NUMBER -> Types.NUMERIC;
            case DATE -> Types.DATE;
            case DATETIME -> Types.TIMESTAMP;
            case BOOLEAN -> Types.BOOLEAN;
            default -> Types.VARCHAR;
        };
    }

    /**
     * Parameters in SQL text without a connection (the parameter list of the editor and of the run dialog), in order
     * of first use: {@code :name} outside strings, quoted names and comments (true = bound), and {@code ${name}} /
     * {@code {{name}}} anywhere, as the model parser finds variables (false = SQL text).
     */
    @NotNull
    static Map<String, Boolean> detect(@NotNull String sql) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        Matcher matcher = SQLQueryParameter.getVariablePattern().matcher(sql);
        List<int[]> variableRanges = new ArrayList<>();
        Map<Integer, String> variables = new LinkedHashMap<>();
        while (matcher.find()) {
            variableRanges.add(new int[]{matcher.start(), matcher.end()});
            variables.put(matcher.start(), SQLQueryParameter.getVariableName(matcher));
        }
        int length = sql.length();
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            if (variables.containsKey(i)) {
                result.putIfAbsent(variables.get(i), false);
                i = endOf(variableRanges, i);
            } else if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
            } else if (c == '[') {
                int close = sql.indexOf(']', i + 1);
                i = close < 0 ? length : close + 1;
            } else if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                int lineEnd = sql.indexOf('\n', i);
                i = lineEnd < 0 ? length : lineEnd + 1;
            } else if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2); //$NON-NLS-1$
                i = close < 0 ? length : close + 2;
            } else if (c == '$' && dollarTagEnd(sql, i) > 0) {
                // PostgreSQL dollar quoting ($$ ... $$, $tag$ ... $tag$): a code block, no parameters in it
                int tagEnd = dollarTagEnd(sql, i);
                String tag = sql.substring(i, tagEnd);
                int close = sql.indexOf(tag, tagEnd);
                i = close < 0 ? length : close + tag.length();
            } else if (c == ':' && i + 1 < length && isNameStart(sql.charAt(i + 1))
                && (i == 0 || (sql.charAt(i - 1) != ':' && !Character.isLetterOrDigit(sql.charAt(i - 1)) && sql.charAt(i - 1) != '_'))) {
                int end = i + 1;
                while (end < length && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                    end++;
                }
                result.putIfAbsent(sql.substring(i + 1, end), true);
                i = end;
            } else {
                i++;
            }
        }
        return result;
    }

    private static int endOf(@NotNull List<int[]> ranges, int start) {
        for (int[] range : ranges) {
            if (range[0] == start) {
                return range[1];
            }
        }
        return start + 1;
    }

    private static int skipQuoted(@NotNull String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                // A doubled quote is an escaped quote
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }

    /**
     * End of a dollar-quote tag ({@code $$} or {@code $tag$}) starting at {@code start}, or -1.
     */
    private static int dollarTagEnd(@NotNull String sql, int start) {
        int i = start + 1;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            if (i == start + 1 && Character.isDigit(sql.charAt(i))) {
                // $1 is a positional parameter, not a tag
                return -1;
            }
            i++;
        }
        return i < sql.length() && sql.charAt(i) == '$' ? i + 1 : -1;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }
}
