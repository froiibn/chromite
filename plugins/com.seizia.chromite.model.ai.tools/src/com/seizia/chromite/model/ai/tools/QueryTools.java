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
package com.seizia.chromite.model.ai.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.ai.agent.AIPendingTransaction;
import com.seizia.chromite.model.ai.agent.AIToolAccess;
import com.seizia.chromite.model.ai.agent.AIToolAccessClassifier;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;
import com.seizia.chromite.model.ai.agent.AIToolConfirmationAnswer;
import com.seizia.chromite.model.ai.agent.AIToolPreviewProvider;
import com.seizia.chromite.model.ai.agent.AIToolTransactionGuard;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.sql.SQLControlCommand;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryType;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.SQLSyntaxManager;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.sql.exec.SQLScriptBatches;
import com.seizia.chromite.model.sql.format.SQLFormatUtils;
import com.seizia.chromite.model.sql.parser.SQLScriptParser;
import com.seizia.chromite.utils.GeneralUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Query tools: SELECT with paging, read-only diagnostic SQL, any SQL and scripts (WRITE), cancel, validate, format, history.
 *
 * 설계:
 * <ul>
 *     <li>{@code runSelect}·{@code runReadOnlySql}은 READ다. {@link AgentSqlClassifier}가 읽기라고 증명하지 못한 문장은 거부하고 {@code runSql}을 쓰라고
 *     알린다. 실행은 읽기 전용 보호 안에서 한다({@link AgentSql.Mode#READ_ONLY}).</li>
 *     <li>{@code runSql}·{@code runScript}는 WRITE다. 문장에 따라 DANGEROUS로 올린다({@link AIToolAccessClassifier}). 확인 요청에 문장 전체를 보인다
 *     ({@link AIToolPreviewProvider}). 연결의 커밋 방식 그대로 실행하고({@link AgentSql.Mode#NATIVE}), 수동 커밋 연결이면 트랜잭션이 열려 있다고 알린다.
 *     MySQL/MariaDB·Oracle의 DDL은 열린 트랜잭션을 암묵적으로 커밋하므로, 에이전트 트랜잭션이 열려 있으면 실행기가 Commit / Roll back / Cancel을 묻는다.</li>
 *     <li>{@code runScript}는 쿼리 창이 실행하는 단위({@link SQLScriptBatches#extractQueries})로 보낸다: SQL Server처럼 배치 구분자({@code GO})가 있는
 *     방언은 배치 하나를 한 번에, 아니면 문장 하나씩. 쿼리 창의 명령({@code DELIMITER}, {@code @set} 등)은 서버로 보내지 않고 건너뛴 것으로 알린다.</li>
 *     <li>{@code getQueryHistory}는 Swing 쿼리 창 기록 파일({@code .metadata/chromite-query-history.jsonl}, Swing {@code QueryHistory})을 읽기만 한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-07 확인 대화상자의 열린 트랜잭션 설명을 AgentSql.pendingAgentTransaction으로 만듦(화면 언어를 따름)
 * - 2026-11-08 {@code transactionNote}: 열린 트랜잭션은 수동 커밋만이 아님(자동 커밋 문맥의 BEGIN TRAN) (docs/chromite/ai-agent.md "트랜잭션 안전")
 * - 2026-11-12 {@code runScript}: 실패한 문장이 있으면 호출을 오류로 끝내되 문장마다 한 일을 모두 돌려줌(전에는 실패가 있어도 성공으로 감사되고
 *   채팅 카드도 완료였다). 실패한 GO 배치 안의 결과·뒤 문장도 담음 (docs/chromite/ai-agent.md "배치의 부분 실패")
 * - 2026-11-12 {@code transactionOpen}: "단일 연결" 연결의 공유 세션에 남은 트랜잭션도 열림으로 알림(전에는 수동 커밋에서 INSERT가 커밋 안 된 채
 *   {@code transactionOpen: false}) (docs/chromite/ai-agent.md "배치의 부분 실패" 공유 세션)
 * - 2026-11-12 {@code validateSql}: "단일 연결" 연결에서는 문장이 여럿인 글을 서버에서 검사하지 않음(앱 변경 #91). EXPLAIN 뒤의 문장이
 *   공유 세션에서 실제로 실행됐다(PostgreSQL {@code SELECT 1; INSERT …}) (docs/chromite/ai-agent.md "공유 세션에서 롤백에 기대는 호출")
 * - 2026-11-12 {@code runSql}: 끝의 {@code ;}를 쿼리 창의 파서 판단대로 남김({@link AgentSqlClassifier#trimTrailingDelimiter}, 앱 변경 #87)
 *   (docs/chromite/ai-agent.md "문장 끝의 구분자")
 * - 2026-11-12 {@code runScript}: GO 배치를 한 번에 보내고 쿼리 창의 명령은 보내지 않음(앱 변경 #88). 배치를 문장으로 쪼개 보내서 배치 안의
 *   변수가 다음 문장에서 사라졌고, MariaDB {@code DELIMITER}가 서버 구문 오류였다 (docs/chromite/ai-agent.md "runScript의 실행 단위")
 */
public class QueryTools extends AgentTool implements AIToolAccessClassifier, AIToolPreviewProvider, AIToolTransactionGuard {

    private static final String HISTORY_FILE = "chromite-query-history.jsonl"; //$NON-NLS-1$
    private static final int SCRIPT_RESULT_ROWS = 20;

    @NotNull
    @Override
    protected JsonElement call(
        @NotNull AIToolCallContext context,
        @NotNull String toolId,
        @NotNull Map<String, Object> args
    ) throws DBException {
        return switch (toolId) {
            case "runSelect" -> runSelect(context, args);
            case "runReadOnlySql" -> runReadOnlySql(context, args);
            case "runSql" -> runSql(context, args);
            case "runScript" -> runScript(context, args);
            case "cancelQuery" -> cancel(context);
            case "validateSql" -> validate(context, args);
            case "formatSql" -> format(context, args);
            case "getQueryHistory" -> history(context, args);
            default -> throw unknownTool(toolId);
        };
    }

    // ------------------------------------------------------------------ read

    @NotNull
    private static JsonElement runSelect(@NotNull AIToolCallContext context, @NotNull Map<String, Object> args) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        DBPDataSource dataSource = AgentSql.connect(context.getMonitor(), container);
        String sql = AgentSqlClassifier.stripTrailingDelimiter(AgentArgs.require(args, "sql"));
        requireReadOnly(dataSource, sql, "runSelect");
        AgentLimits limits = AgentLimits.of(args);
        int offset = Math.max(0, AgentArgs.getInt(args, "offset", 0));
        boolean objects = !"arrays".equalsIgnoreCase(AgentArgs.getString(args, "format"));
        return AgentSql.run(context.getMonitor(), container, AgentArgs.getString(args, "database"), AgentSql.Mode.READ_ONLY,
            (connection, runner) -> runner.queryPage(connection, sql, offset, limits, objects));
    }

    @NotNull
    private static JsonElement runReadOnlySql(@NotNull AIToolCallContext context, @NotNull Map<String, Object> args) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        DBPDataSource dataSource = AgentSql.connect(context.getMonitor(), container);
        String sql = AgentSqlClassifier.stripTrailingDelimiter(AgentArgs.require(args, "sql"));
        requireReadOnly(dataSource, sql, "runReadOnlySql");
        AgentLimits limits = AgentLimits.of(args);
        return AgentSql.run(context.getMonitor(), container, AgentArgs.getString(args, "database"), AgentSql.Mode.READ_ONLY,
            (connection, runner) -> runner.execute(connection, sql, null, limits, true));
    }

    static void requireReadOnly(@Nullable DBPDataSource dataSource, @NotNull String sql, @NotNull String tool) throws AIToolArgumentException {
        AgentSqlClassifier.Verdict verdict = AgentSqlClassifier.classify(dataSource, sql);
        if (!verdict.readOnly()) {
            throw new AIToolArgumentException(tool + " runs only statements that are provably read-only, and " + verdict.reason() +
                ". Rewrite it as a single SELECT, or use runSql (WRITE: the user confirms it).");
        }
    }

    // ------------------------------------------------------------------ write

    @NotNull
    private static JsonElement runSql(@NotNull AIToolCallContext context, @NotNull Map<String, Object> args) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        DBPDataSource dataSource = AgentSql.connect(context.getMonitor(), container);
        String sql = AgentSqlClassifier.trimTrailingDelimiter(dataSource, AgentArgs.require(args, "sql"));
        AgentLimits limits = AgentLimits.of(args);
        JsonObject result = AgentSql.run(context.getMonitor(), container, AgentArgs.getString(args, "database"), AgentSql.Mode.NATIVE,
            (connection, runner) -> runner.execute(connection, sql, null, limits, true));
        addTransactionState(container, result);
        return result;
    }

    @NotNull
    private static JsonElement runScript(@NotNull AIToolCallContext context, @NotNull Map<String, Object> args) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        DBPDataSource dataSource = AgentSql.connect(context.getMonitor(), container);
        List<SQLScriptElement> units = scriptUnits(dataSource, AgentArgs.require(args, "script"));
        List<String> statements = units.stream().map(element -> element.getText().strip()).toList();
        boolean stopOnError = AgentArgs.getBoolean(args, "stopOnError", true);
        AgentLimits limits = AgentLimits.of(args, SCRIPT_RESULT_ROWS);
        JsonObject result = AgentSql.run(context.getMonitor(), container, AgentArgs.getString(args, "database"), AgentSql.Mode.NATIVE,
            (connection, runner) -> {
                JsonArray list = new JsonArray();
                int failed = 0;
                int skipped = 0;
                // Units sent to the server after the first failed one
                int ranAfter = 0;
                SQLException firstError = null;
                int firstFailed = 0;
                for (int i = 0; i < statements.size(); i++) {
                    if (runner.getMonitor().isCanceled()) {
                        break;
                    }
                    JsonObject item = new JsonObject();
                    item.addProperty("index", i + 1);
                    item.addProperty("sql", statements.get(i).length() > 300 ? statements.get(i).substring(0, 300) + "..." : statements.get(i));
                    if (units.get(i) instanceof SQLControlCommand) {
                        // DELIMITER, @set, @include...: the query window handles them itself and never sends them
                        item.addProperty("skipped", "A command of the query window, not SQL: not sent to the server");
                        skipped++;
                        list.add(item);
                        continue;
                    }
                    if (firstError != null) {
                        ranAfter++;
                    }
                    try {
                        JsonObject r = runner.execute(connection, statements.get(i), null, limits, true);
                        r.entrySet().forEach(e -> item.add(e.getKey(), e.getValue()));
                    } catch (SQLException e) {
                        failed++;
                        if (firstError == null) {
                            firstError = e;
                            firstFailed = i + 1;
                        }
                        item.addProperty("error", e.getMessage());
                        // A statement (a GO batch) that failed after it had returned something, or went on after its error
                        if (e instanceof AgentSql.PartialResultException partial) {
                            partial.getResult().entrySet().forEach(x -> item.add(x.getKey(), x.getValue()));
                        }
                        list.add(item);
                        if (stopOnError) {
                            break;
                        }
                        continue;
                    }
                    list.add(item);
                }
                JsonObject json = new JsonObject();
                json.addProperty("statements", statements.size());
                json.addProperty("executed", list.size() - skipped);
                if (skipped > 0) {
                    json.addProperty("skipped", skipped);
                }
                json.addProperty("failed", failed);
                json.add("results", list);
                if (firstError != null) {
                    // A script with a failed statement is a failed call, whose result still lists what every statement did
                    String message = failed + " of " + statements.size() + " statements failed; statement " + firstFailed + ": " + firstError.getMessage();
                    if (ranAfter > 0) {
                        json.addProperty("ranAfterError", ranAfter + " statement(s) after the first failed one ran. " +
                            "The error did not undo them; transactionNote says whether they are committed.");
                        message += " (" + ranAfter + " statement(s) after it ran)";
                    } else if (list.size() < statements.size()) {
                        message += " (the script stopped there)";
                    }
                    throw new AgentSql.PartialResultException(message, firstError, json, false);
                }
                return json;
            });
        addTransactionState(container, result);
        return result;
    }

    static void addTransactionState(@NotNull DBPDataSourceContainer container, @NotNull JsonObject result) {
        boolean open = AgentSql.hasPendingTransaction(container);
        // A "single connection" connection: the work is in the shared session's transaction, not in one of the agent's own
        boolean shared = !open && AgentSql.isSharedSessionTransactionOpen(container);
        result.addProperty("transactionOpen", open || shared);
        if (open) {
            result.addProperty("transactionNote", "The agent's transaction is open: its changes or locks are not committed. " +
                "Ask the user, then call commitTransaction or rollbackTransaction (both ask the user to confirm).");
        } else if (shared) {
            result.addProperty("transactionNote", AgentSql.SHARED_SESSION_NOTE);
        }
    }

    @NotNull
    static List<String> splitScript(@NotNull DBPDataSource dataSource, @NotNull String script) {
        List<String> statements = new ArrayList<>();
        for (SQLScriptElement element : SQLScriptParser.parseScript(dataSource, script)) {
            String text = element.getText().strip();
            if (!text.isEmpty()) {
                statements.add(text);
            }
        }
        return statements;
    }

    /**
     * What runScript sends, unit by unit: the elements the query window executes ({@link SQLScriptBatches#extractQueries}).
     * For a dialect with a batch delimiter (SQL Server, Sybase: {@code GO}) a unit is the whole batch between delimiters, so
     * its variables, temporary objects, SET options and error handling stay together as on the server; otherwise one statement.
     * Not {@link #splitScript}, which splits a batch into its statements: the tools that must find a second statement (the plan
     * tools, the classifier) keep using that one.
     */
    @NotNull
    private static List<SQLScriptElement> scriptUnits(@NotNull DBPDataSource dataSource, @NotNull String script) {
        List<SQLScriptElement> units = new ArrayList<>();
        for (SQLScriptElement element : SQLScriptBatches.extractQueries(dataSource, script, 0, script.length())) {
            if (!element.getText().isBlank()) {
                units.add(element);
            }
        }
        return units;
    }

    @NotNull
    private static JsonElement cancel(@NotNull AIToolCallContext context) throws DBException {
        JsonObject json = new JsonObject();
        json.addProperty("canceled", AgentSql.cancelRunning(requireConnection(context)));
        return json;
    }

    // ------------------------------------------------------------------ validate, format, history

    @NotNull
    private static JsonElement validate(@NotNull AIToolCallContext context, @NotNull Map<String, Object> args) throws DBException {
        DBPDataSourceContainer container = requireConnection(context);
        DBPDataSource dataSource = AgentSql.connect(context.getMonitor(), container);
        String sql = AgentSqlClassifier.stripTrailingDelimiter(AgentArgs.require(args, "sql"));
        SQLQuery query = new SQLQuery(dataSource, sql);
        JsonObject json = new JsonObject();
        SQLQueryType type = query.getType();
        json.addProperty("type", type.name());
        Throwable parseError = query.getParseError();
        json.addProperty("parsed", parseError == null && type != SQLQueryType.UNKNOWN);
        if (parseError != null) {
            json.addProperty("parseError", parseError.getMessage());
        }
        AgentSqlClassifier.Verdict verdict = AgentSqlClassifier.classify(dataSource, sql);
        json.addProperty("readOnly", verdict.readOnly());
        json.addProperty("access", AgentSqlClassifier.access(dataSource, sql).name());
        // Server-side check without running the statement
        DbFamily family = DbFamily.of(container);
        String check = switch (family) {
            case SQLSERVER -> "NOEXEC";
            case POSTGRESQL, MYSQL, MARIADB -> type == SQLQueryType.SELECT || type == SQLQueryType.INSERT ||
                type == SQLQueryType.UPDATE || type == SQLQueryType.DELETE ? "EXPLAIN" : null;
            default -> null;
        };
        // PostgreSQL and MySQL EXPLAIN covers the first statement only, and the ones after it run: on an isolated context the
        // read-only transaction stops them, in a shared session they would really run (SQL Server's NOEXEC is not relied on
        // for a text with several statements either)
        boolean unsafe = check != null && AgentSql.isSharedSession(container) && splitScript(dataSource, sql).size() > 1;
        if (unsafe) {
            json.addProperty("serverNote", "Not checked on the server: the text holds more than one statement, and this " +
                "connection's single shared session (also the user's query windows' session) would run the ones after the first. " +
                "Validate one statement at a time.");
        }
        if (check != null && !unsafe && AgentArgs.getBoolean(args, "server", true)) {
            String serverError = AgentSql.run(context.getMonitor(), container, AgentArgs.getString(args, "database"),
                AgentSql.Mode.READ_ONLY, (connection, runner) -> serverCheck(connection, runner, check, sql));
            json.addProperty("serverChecked", check);
            json.addProperty("serverValid", serverError == null);
            if (serverError != null) {
                json.addProperty("serverError", serverError);
            }
        }
        return json;
    }

    // NOEXEC compiles and resolves names without executing; EXPLAIN plans without executing. Returns the error or null.
    @Nullable
    private static String serverCheck(@NotNull Connection connection, @NotNull AgentSql.Runner runner, @NotNull String check, @NotNull String sql)
        throws SQLException {
        try (Statement statement = connection.createStatement()) {
            runner.track(statement, true);
            try {
                if (check.equals("NOEXEC")) {
                    statement.execute("SET NOEXEC ON"); //$NON-NLS-1$
                    try {
                        statement.execute(sql);
                        return null;
                    } finally {
                        statement.execute("SET NOEXEC OFF"); //$NON-NLS-1$
                    }
                }
                statement.execute("EXPLAIN " + sql); //$NON-NLS-1$
                return null;
            } catch (SQLException e) {
                return e.getMessage();
            } finally {
                runner.track(statement, false);
            }
        }
    }

    @NotNull
    private static JsonElement format(@NotNull AIToolCallContext context, @NotNull Map<String, Object> args) throws DBException {
        DBPDataSourceContainer container = context.getConnection();
        DBPDataSource dataSource = container == null ? null : AgentSql.connect(context.getMonitor(), container);
        SQLDialect dialect = SQLUtils.getDialectFromDataSource(dataSource);
        DBPPreferenceStore preferences = container != null ? container.getPreferenceStore()
            : com.seizia.chromite.runtime.DBWorkbench.getPlatform().getPreferenceStore();
        SQLSyntaxManager syntaxManager = new SQLSyntaxManager();
        syntaxManager.init(dialect, preferences);
        JsonObject json = new JsonObject();
        json.addProperty("sql", SQLFormatUtils.formatSQL(dataSource, syntaxManager, AgentArgs.require(args, "sql"), "    "));
        return json;
    }

    @NotNull
    private static JsonElement history(@NotNull AIToolCallContext context, @NotNull Map<String, Object> args) throws DBException {
        Path file = GeneralUtils.getMetadataFolder().resolve(HISTORY_FILE);
        DBPDataSourceContainer container = context.getConnection();
        String contains = AgentArgs.getString(args, "contains");
        AgentLimits limits = AgentLimits.of(args, 50);
        JsonArray entries = new JsonArray();
        if (Files.exists(file)) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new DBException("Can't read the query history", e);
            }
            for (int i = lines.size() - 1; i >= 0 && entries.size() < limits.maxRows(); i--) {
                JsonObject entry;
                try {
                    entry = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue;
                }
                if (container != null && !container.getId().equals(string(entry, "connectionId"))) {
                    continue;
                }
                String query = string(entry, "query");
                if (contains != null && (query == null || !query.toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT)))) {
                    continue;
                }
                if (query != null && query.length() > limits.maxCellChars()) {
                    entry.addProperty("query", query.substring(0, limits.maxCellChars()) + "...");
                }
                if (entry.has("time")) {
                    entry.addProperty("time", java.time.Instant.ofEpochMilli(entry.get("time").getAsLong()).toString());
                }
                entries.add(entry);
            }
        }
        JsonObject json = new JsonObject();
        json.add("entries", entries);
        json.addProperty("count", entries.size());
        return json;
    }

    @Nullable
    private static String string(@NotNull JsonObject json, @NotNull String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    // ------------------------------------------------------------------ policy hooks

    @NotNull
    @Override
    public AIToolAccess classify(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments) throws DBException {
        String toolId = context.getTool().getId();
        if (!toolId.equals("runSql") && !toolId.equals("runScript")) {
            return context.getTool().getAccess();
        }
        DBPDataSource dataSource = dataSourceOrNull(context);
        AIToolAccess access = AIToolAccess.WRITE;
        for (String statement : statements(context, arguments, dataSource)) {
            access = access.max(AgentSqlClassifier.access(dataSource, statement));
        }
        return access;
    }

    @Nullable
    @Override
    public String getPreview(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments) {
        Object sql = arguments.containsKey("script") ? arguments.get("script") : arguments.get("sql");
        return sql == null ? null : sql.toString();
    }

    @NotNull
    @Override
    public List<AIPendingTransaction> findPendingTransactions(
        @NotNull AIToolCallContext context,
        @NotNull Map<String, Object> arguments
    ) throws DBException {
        return implicitCommitRisk(context, statements(context, arguments, dataSourceOrNull(context)));
    }

    @Override
    public void resolvePendingTransactions(
        @NotNull AIToolCallContext context,
        @NotNull Map<String, Object> arguments,
        @NotNull AIToolConfirmationAnswer answer
    ) throws DBException {
        AgentSql.endTransaction(context.getMonitor(), requireConnection(context), answer == AIToolConfirmationAnswer.COMMIT);
    }

    /**
     * MySQL/MariaDB and Oracle commit the open transaction before a DDL statement: with an open agent transaction that is an
     * implicit commit the user must decide on first.
     */
    @NotNull
    static List<AIPendingTransaction> implicitCommitRisk(@NotNull AIToolCallContext context, @NotNull List<String> statements) {
        DBPDataSourceContainer container = context.getConnection();
        List<AIPendingTransaction> result = new ArrayList<>();
        if (container == null || !container.isConnected() || !AgentSql.hasPendingTransaction(container)) {
            return result;
        }
        DbFamily family = DbFamily.of(container);
        if (family != DbFamily.MYSQL && family != DbFamily.MARIADB && family != DbFamily.ORACLE) {
            return result;
        }
        for (String statement : statements) {
            String head = statement.stripLeading().toUpperCase(Locale.ROOT);
            if (head.startsWith("CREATE") || head.startsWith("ALTER") || head.startsWith("DROP") || head.startsWith("TRUNCATE") ||
                head.startsWith("RENAME") || head.startsWith("GRANT") || head.startsWith("REVOKE")) {
                for (String database : AgentSql.getPendingTransactionDatabases(container)) {
                    result.add(AgentSql.pendingAgentTransaction(database, family.getDisplayName()));
                }
                break;
            }
        }
        return result;
    }

    @NotNull
    private static List<String> statements(
        @NotNull AIToolCallContext context,
        @NotNull Map<String, Object> arguments,
        @Nullable DBPDataSource dataSource
    ) {
        String toolId = context.getTool().getId();
        if (toolId.equals("runScript")) {
            String script = AgentArgs.getString(arguments, "script");
            if (script == null) {
                return List.of();
            }
            return dataSource == null ? List.of(script) : splitScript(dataSource, script);
        }
        String sql = AgentArgs.getString(arguments, "sql");
        return sql == null ? List.of() : List.of(sql);
    }

    @Nullable
    private static DBPDataSource dataSourceOrNull(@NotNull AIToolCallContext context) {
        DBPDataSourceContainer container = context.getConnection();
        return container == null ? null : container.getDataSource();
    }
}
