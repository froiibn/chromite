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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPContextProvider;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLParametersProviderBase;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryParameter;
import com.seizia.chromite.model.sql.SQLScriptContext;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.data.SQLQueryDataContainer;
import com.seizia.chromite.model.sql.registry.SQLQueryParameterRegistry;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;

/**
 * 쿼리 창의 매개변수 바인딩. Query Library 쿼리(실행 창, 예약 실행)와 기록에서 다시 연 바인딩 실행({@link Recorded})은 {@code :name}을
 * prepared statement의 {@code ?}로 바꿔 값을 바인딩하고, {@code ${name}} 변수는 값을 SQL 글자로 끼운다. 평범한 쿼리 창의 스크립트가
 * 실행 중에 만난 매개변수는 엔진의 매개변수 제공자({@link ScriptProvider})가 같은 대화상자({@link QueryParameterDialog})로 묻고 값을 SQL 글자로 넣는다.
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
 *     <li>결과를 다시 읽을 때(필터, 서버 정렬, 다음 페이지, Export Data)도 같은 값을 바인딩한다({@link #createDataContainer}). 데이터 컨테이너는
 *     {@code ?} 글자가 아니라 {@code :name}이 남은 글자를 갖고, 필터를 넣은 최종 글자에서 매개변수를 다시 찾아 이름으로 바인딩한다
 *     ({@code SQLQueryDataContainer.ParameterBinder}). 필터·정렬이 쿼리를 감싸거나 ORDER BY를 바꿔도 {@code ?}의 순서에 기대지 않는다.</li>
 *     <li>Query History에는 {@code :name}이 남은 글자와 바인딩한 값·형식을 남긴다({@link #getHistoryText}, {@link #getHistoryParameters}).
 *     기록에서 Execute하면 같은 값을 다시 바인딩하고, Open한 창은 실행할 때 그 값을 채운 대화상자로 묻는다({@link Recorded}).
 *     처음에는 값을 SQL 리터럴로 넣은 글자를 남겼는데, 리터럴은 서버마다 뜻이 달라(Oracle의 날짜 문자열은 NLS 설정에 따름, SQL Server에는 불리언
 *     리터럴이 없음) 다시 실행하면 처음과 다르게 동작할 수 있었다.</li>
 *     <li>평범한 쿼리 창의 {@code :name}은 SWT와 같이 SQL 글자다(사용자가 {@code 'abc'}처럼 따옴표까지 입력, {@code IN (1,2)}처럼 SQL 조각도 됨).
 *     문장마다 스크립트 변수({@code @set})가 값을 줄 수 있어 엔진이 실행 중에 값이 없는 매개변수만 묻는다. 그래서 이 경로는 바인딩으로 바꾸지 않고
 *     대화상자만 합쳤다. 값은 DBeaver의 {@link SQLQueryParameterRegistry}(작업 공간의 {@code parameter-bindings.xml})에 이름별로 기억한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md "매개변수 바인딩")
 * - 2026-11-06 Query History용 글자({@link #getHistoryText}): 값을 SQL 리터럴로 넣은 원래 문장. 엔진이 보낸 {@code ?} 문장이 기록되어
 *   기록에서 다시 열거나 실행할 수 없던 문제 (docs/chromite/swing-query-library.md "매개변수 바인딩")
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-08 쿼리 창의 매개변수 제공자({@link ScriptProvider}, 예전 {@code SqlParameterDialog.Provider})를 옮겨 와 매개변수 입력을 하나로 합침,
 *   결과를 다시 읽을 때도 바인딩({@link #createDataContainer}), 기록에는 리터럴 대신 {@code :name} 글자와 값({@link #getHistoryParameters}, {@link Recorded})
 *   (docs/chromite/swing-query-library.md "매개변수 대화상자와 바인딩 하나로")
 */
final class QueryParameterBinder implements SqlParameterBinding, SQLQueryDataContainer.ParameterBinder {

    private static final Log log = Log.getLog(QueryParameterBinder.class);

    /**
     * Values bound to the {@code ?} markers of one rewritten query, in order; kept as the query's data.
     *
     * @param namedText  the query with its {@code :name} markers (variables written in): results are read again from it,
     *                   and the query history keeps it
     * @param parameters the bound parameters with their values, for the query history
     */
    private record BoundValues(
        @NotNull List<Object> values,
        @NotNull List<QueryLibraryParameter.Type> types,
        @NotNull String namedText,
        @NotNull List<QueryHistory.Parameter> parameters
    ) {
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
        this(item.getParameters(), values, item.getDatabase(), item.getSchema());
    }

    /**
     * @param definitions parameter types (not changed)
     * @param values      values by parameter name
     */
    private QueryParameterBinder(
        @NotNull List<QueryLibraryParameter> definitions,
        @NotNull Map<String, String> values,
        @Nullable String database,
        @Nullable String schema
    ) {
        values.forEach((name, value) -> this.values.put(name.toLowerCase(Locale.ROOT), value));
        for (QueryLibraryParameter parameter : definitions) {
            this.definitions.put(parameter.getName().toLowerCase(Locale.ROOT), parameter.copy());
        }
        this.database = database;
        this.schema = schema;
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
        return query.getData() instanceof BoundValues bound ? bound.namedText() : query.getText();
    }

    @Nullable
    @Override
    public List<QueryHistory.Parameter> getHistoryParameters(@NotNull SQLQuery query) {
        return query.getData() instanceof BoundValues bound ? bound.parameters() : null;
    }

    @NotNull
    @Override
    public SQLQueryDataContainer createDataContainer(
        @NotNull DBPContextProvider contextProvider,
        @NotNull SQLQuery query,
        @NotNull SQLScriptContext scriptContext,
        @NotNull Log log
    ) {
        if (!(query.getData() instanceof BoundValues bound)) {
            return SqlParameterBinding.super.createDataContainer(contextProvider, query, scriptContext, log);
        }
        // The text with the ":name" markers: a re-read finds them again in the filtered text and binds them by name
        SQLQuery namedQuery = new SQLQuery(query.getDataSource(), bound.namedText(), query.getOffset(), query.getLength());
        SQLQueryDataContainer container = new SQLQueryDataContainer(contextProvider, namedQuery, scriptContext, log);
        container.setParameterBinder(this);
        return container;
    }

    @NotNull
    @Override
    public SQLQuery prepareQuery(@NotNull SQLQuery query) throws DBException {
        return CommonUtils.isEmpty(query.getParameters()) ? query : rewrite(query);
    }

    @Override
    public void bindParameters(@NotNull DBCStatement statement, @NotNull SQLQuery query) throws DBCException {
        bind(statement, query);
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
        // The same text with the ":name" markers kept: re-reads and the query history bind the values to it again
        StringBuilder namedText = new StringBuilder(text.length());
        List<Object> boundValues = new ArrayList<>();
        List<QueryLibraryParameter.Type> boundTypes = new ArrayList<>();
        Map<String, QueryHistory.Parameter> boundParameters = new LinkedHashMap<>();
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
            namedText.append(text, position, start);
            if (isTextSubstitution(originalName)) {
                newText.append(CommonUtils.notEmpty(value));
                namedText.append(CommonUtils.notEmpty(value));
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
                namedText.append(text, start, end);
                boundParameters.putIfAbsent(key, new QueryHistory.Parameter(parameter.getName(), value, getHistoryType(type).getId()));
            }
            position = end;
        }
        newText.append(text, position, text.length());
        namedText.append(text, position, text.length());
        SQLQuery rewritten = new SQLQuery(query.getDataSource(), newText.toString(), query.getOffset(), query.getLength());
        if (!boundValues.isEmpty()) {
            rewritten.setData(new BoundValues(boundValues, boundTypes, namedText.toString(), List.copyOf(boundParameters.values())));
        }
        return rewritten;
    }

    /**
     * The type a value is bound with: choice and lookup values are text (their lists are not recorded in the history).
     */
    @NotNull
    private static QueryLibraryParameter.Type getHistoryType(@NotNull QueryLibraryParameter.Type type) {
        return switch (type) {
            case NUMBER, DATE, DATETIME, BOOLEAN -> type;
            default -> QueryLibraryParameter.Type.TEXT;
        };
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

    //////////////////////////////////////////////////////////
    // A bound run reopened from the query history

    /**
     * The bound values of a run reopened from the query history (Query History: Open, Execute). The window binds the
     * {@code :name} parameters of its text as values again, with the recorded types; a parameter the text got since then
     * is text. Values typed in the dialog replace the recorded ones for the window's next runs.
     */
    static final class Recorded {
        private final List<QueryLibraryParameter> definitions = new ArrayList<>();
        private final Map<String, String> values = new LinkedHashMap<>();

        Recorded(@NotNull List<QueryHistory.Parameter> parameters) {
            for (QueryHistory.Parameter parameter : parameters) {
                QueryLibraryParameter definition = new QueryLibraryParameter(parameter.name());
                definition.setType(QueryLibraryParameter.Type.fromId(parameter.type()));
                definitions.add(definition);
                values.put(parameter.name(), CommonUtils.notEmpty(parameter.value()));
            }
        }

        /**
         * Makes the binding of a run (EDT).
         *
         * @param title     the window name, in the dialog title
         * @param container the connection (lookup values), or null
         * @param sql       the text that runs
         * @param ask       false to use the recorded values without asking when every parameter has one (History: Execute)
         * @return the binding, or null if canceled
         */
        @Nullable
        SqlParameterBinding prepareExecution(
            @NotNull Component parent,
            @NotNull String title,
            @Nullable DBPDataSourceContainer container,
            @NotNull String sql,
            boolean ask
        ) {
            Map<String, Boolean> kinds = detect(sql);
            if (kinds.isEmpty()) {
                return SqlParameterBinding.NONE;
            }
            List<QueryLibraryParameter> fields = QueryParameterDefinitionsDialog.merge(definitions, kinds);
            if (ask || !hasValues(fields)) {
                Map<String, String> answer = QueryParameterDialog.open(parent, title, fields, kinds, values, container);
                if (answer == null) {
                    return null;
                }
                values.putAll(answer);
            }
            return new QueryParameterBinder(fields, values, null, null);
        }

        private boolean hasValues(@NotNull List<QueryLibraryParameter> fields) {
            for (QueryLibraryParameter field : fields) {
                if (!values.containsKey(field.getName())) {
                    return false;
                }
            }
            return true;
        }
    }

    //////////////////////////////////////////////////////////
    // Parameters of a query window's script (values as SQL text)

    /**
     * The engine's parameter provider of a query window: the engine calls it in the job thread only for the parameters
     * of a statement that script variables and defaults do not fill. It asks in the EDT ({@link QueryParameterDialog},
     * script mode) and waits; the engine writes the values into the statement as SQL text (SWT:
     * {@code SQLEditorParametersProvider} + {@code SQLQueryParameterBindDialog}).
     */
    static final class ScriptProvider extends SQLParametersProviderBase {
        private final Component parent;

        ScriptProvider(@NotNull Component parent) {
            this.parent = parent;
        }

        @Nullable
        @Override
        protected Boolean collectAndAssignVariables(
            @NotNull SQLScriptContext scriptContext,
            @NotNull SQLQuery sqlStatement,
            @NotNull List<SQLQueryParameter> parameters,
            @NotNull Supplier<DBDDataReceiver> dataReceiverSupplier
        ) {
            QueryParameterDialog.Action[] action = {QueryParameterDialog.Action.CANCEL};
            try {
                SwingUtilities.invokeAndWait(() -> action[0] = askScriptValues(parent, sqlStatement, parameters));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (InvocationTargetException e) {
                log.error("Parameter dialog failed", e.getTargetException());
                return false;
            }
            switch (action[0]) {
                case RUN -> {
                    // Values are remembered for the rest of the script, as in the SWT editor
                    for (SQLQueryParameter parameter : parameters) {
                        if (parameter.isNamed()) {
                            if (scriptContext.hasVariable(parameter.getName())) {
                                scriptContext.setVariable(parameter.getName(), parameter.getValue());
                            } else if (scriptContext.hasVariable(parameter.getVarName())) {
                                scriptContext.setVariable(parameter.getVarName(), parameter.getValue());
                            } else {
                                scriptContext.setParameterDefaultValue(parameter.getName(), parameter.getValue());
                            }
                        }
                    }
                    return true;
                }
                case IGNORE -> {
                    scriptContext.setIgnoreParameters(true);
                    return null;
                }
                default -> {
                    return false;
                }
            }
        }
    }

    /**
     * Asks for the values of a statement's parameters (EDT) and, on Execute, assigns them and remembers them by name.
     * Parameters with the same name are one field, in the order the names first appear; unnamed ones ({@code ?}) are
     * numbered.
     */
    @NotNull
    private static QueryParameterDialog.Action askScriptValues(
        @Nullable Component parent,
        @NotNull SQLQuery query,
        @NotNull List<SQLQueryParameter> parameters
    ) {
        Map<String, List<SQLQueryParameter>> parametersByName = new LinkedHashMap<>();
        for (SQLQueryParameter parameter : parameters) {
            String key = parameter.isNamed() ? parameter.getName() : "?" + (parameter.getOrdinalPosition() + 1); //$NON-NLS-1$
            parametersByName.computeIfAbsent(key, k -> new ArrayList<>()).add(parameter);
        }
        SQLQueryParameterRegistry registry = SQLQueryParameterRegistry.getInstance();
        List<QueryParameterDialog.Field> fields = new ArrayList<>();
        Map<String, String> initialValues = new LinkedHashMap<>();
        for (Map.Entry<String, List<SQLQueryParameter>> entry : parametersByName.entrySet()) {
            SQLQueryParameter first = entry.getValue().getFirst();
            String value = first.getValue();
            if (value == null && first.isNamed()) {
                SQLQueryParameterRegistry.ParameterInfo saved = registry.getParameter(first.getName());
                value = saved != null ? saved.value : null;
            }
            initialValues.put(entry.getKey(), CommonUtils.notEmpty(value));
            String marker = first.isNamed() ? CommonUtils.notEmpty(first.getOriginalName()) : "?"; //$NON-NLS-1$
            fields.add(new QueryParameterDialog.Field(new QueryLibraryParameter(entry.getKey()), marker, true));
        }
        QueryParameterDialog.Answer answer = QueryParameterDialog.openForScript(parent, query.getText(), fields, initialValues);
        if (answer.action() == QueryParameterDialog.Action.RUN) {
            for (Map.Entry<String, List<SQLQueryParameter>> entry : parametersByName.entrySet()) {
                String value = answer.values().getOrDefault(entry.getKey(), ""); //$NON-NLS-1$
                for (SQLQueryParameter parameter : entry.getValue()) {
                    parameter.setValue(value);
                    parameter.setVariableSet(!CommonUtils.isEmpty(value));
                    if (parameter.isNamed()) {
                        registry.setParameter(parameter.getName(), value);
                    }
                }
            }
            registry.save();
        }
        return answer.action();
    }
}
