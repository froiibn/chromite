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
import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRBlockingObject;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import org.jkiss.utils.CommonUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQL execution of agent tools: an isolated execution context per connection and database, read-only protection,
 * and cancellation.
 *
 * 설계(docs/chromite/ai-agent.md "실행 문맥", "읽기 전용 보호"):
 * <ul>
 *     <li>문맥: 연결(그리고 {@code database} 인수)마다 {@code openIsolatedContext}로 연 에이전트 전용 문맥 하나. 사용자 쿼리 창과
 *     탐색기 문맥을 건드리지 않으므로 자동 커밋·읽기 전용 상태를 마음대로 바꾸고 되돌릴 수 있다. PostgreSQL처럼 DB마다 인스턴스가 다르면
 *     그 DB의 인스턴스에서 열고, SQL Server·MySQL처럼 한 인스턴스면 연 문맥의 기본 DB를 바꾼다. 한 문맥은 잠금으로 한 번에 한 호출만 쓴다.</li>
 *     <li>연결이 다시 이어지면 데이터 소스 객체가 바뀐다. 문맥의 데이터 소스가 지금 연결의 것과 다르거나 끊겼으면 새로 연다.</li>
 *     <li>실행은 JDBC를 직접 쓴다({@link JDBCSession#getOriginal()}). 여러 결과 집합, 서버 메시지(SQLWarning: SQL Server PRINT,
 *     SET STATISTICS IO/TIME), 실행 중 취소를 DBeaver 문장 래퍼보다 직접 다루기 쉽다.</li>
 *     <li>트랜잭션 안전(사용자 규칙 2026-11-07, docs/chromite/ai-agent.md "트랜잭션 안전"): 열린 트랜잭션을 조용히 커밋·롤백하지 않는다.
 *     에이전트 문맥은 연결의 기본 커밋 방식을 그대로 쓰고, 수동 커밋 문맥에서 쓰기 뒤에 DB에 직접 물어
 *     ({@link #probeOpenTransaction}) 열린 트랜잭션을 {@code pending}으로 기록한다. 커밋·롤백은 {@link #endTransaction}만 한다
 *     (확인을 거친 commitTransaction/rollbackTransaction, 또는 실행기의 Commit/Roll back 답).</li>
 *     <li>{@link Mode#READ_ONLY}: 열린 트랜잭션이 없으면 자동 커밋 끔, 읽기 전용 트랜잭션(PostgreSQL {@code setReadOnly}, MySQL/MariaDB·Oracle
 *     {@code SET TRANSACTION READ ONLY}), 끝나면 롤백(쓴 것이 없어 잃는 것이 없음). 열린 트랜잭션이 있으면 그 안에서 그대로 읽고 롤백하지 않는다.
 *     {@link Mode#NATIVE}: 문맥의 커밋 방식 그대로(runSql, runScript, BACKUP 같은 트랜잭션 밖 명령). {@link Mode#ATOMIC}: 자동 커밋 문맥이면
 *     호출 전체를 한 트랜잭션으로 묶어 끝에 커밋, 수동 커밋 문맥이면 그대로 실행하고 열어 둔다(행 편집, 가져오기, 복사).</li>
 *     <li>취소: 실행 중 문장을 연결별로 기록해 {@code cancelQuery}가 {@link Statement#cancel()}을 부른다. 모니터에도 블록으로 등록해
 *     MCP 취소 알림·채팅 중지가 같은 문장을 취소한다.</li>
 *     <li>"단일 연결 사용" 연결은 격리 문맥을 열 수 없어 기본 문맥을 쓴다. 이때는 사용자 트랜잭션을 건드리지 않도록 자동 커밋을 바꾸지 않고,
 *     읽기 보호는 분류기만 한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public final class AgentSql {

    private static final Log log = Log.getLog(AgentSql.class);
    private static final String CONTEXT_NAME = "Chromite AI agent"; //$NON-NLS-1$

    public enum Mode {
        READ_ONLY,
        NATIVE,
        ATOMIC,
        /**
         * Run in a transaction that is always rolled back (Oracle EXPLAIN PLAN writes PLAN_TABLE; the actual plan of a DML
         * statement measures it without keeping its changes). Refused while the agent has an open transaction, which the
         * rollback would discard.
         */
        ROLLBACK
    }

    /**
     * Work done on the agent's JDBC connection.
     */
    @FunctionalInterface
    public interface Work<T> {
        T run(@NotNull Connection connection, @NotNull Runner runner) throws Exception;
    }

    private record ContextKey(@NotNull String containerId, @NotNull String database) {
    }

    private static final class AgentContext {
        final DBCExecutionContext context;
        final boolean isolated;
        final Object lock = new Object();
        // The agent left an uncommitted transaction open (manual-commit connection)
        volatile boolean pending;

        AgentContext(@NotNull DBCExecutionContext context, boolean isolated) {
            this.context = context;
            this.isolated = isolated;
        }
    }

    private static final Map<ContextKey, AgentContext> contexts = new ConcurrentHashMap<>();
    private static final Map<String, Set<Statement>> running = new ConcurrentHashMap<>();
    // setCurrentDatabase: per connection, {database, schema}
    private static final Map<String, String[]> defaults = new ConcurrentHashMap<>();

    private AgentSql() {
    }

    /**
     * Connects the connection if needed and returns its data source.
     */
    @NotNull
    public static DBPDataSource connect(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSourceContainer container)
        throws DBException {
        if (!container.isConnected()) {
            container.connect(monitor, true, true);
        }
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null) {
            throw new DBException("Connection '" + container.getName() + "' is not available");
        }
        return dataSource;
    }

    /**
     * Agent default database/schema set by {@code setCurrentDatabase}: {database, schema}, either may be null.
     */
    @Nullable
    public static String[] getAgentDefaults(@NotNull DBPDataSourceContainer container) {
        return defaults.get(container.getId());
    }

    public static void setAgentDefaults(
        @NotNull DBPDataSourceContainer container,
        @Nullable String database,
        @Nullable String schema
    ) {
        if (database == null && schema == null) {
            defaults.remove(container.getId());
        } else {
            defaults.put(container.getId(), new String[] {database, schema});
        }
    }

    /**
     * Runs work on the agent context of the connection (and database).
     *
     * @param database database (catalog) to run in, or null for the agent default (setCurrentDatabase) or the connection default
     */
    public static <T> T run(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container,
        @Nullable String database,
        @NotNull Mode mode,
        @NotNull Work<T> work
    ) throws DBException {
        DBPDataSource dataSource = connect(monitor, container);
        String[] agentDefaults = getAgentDefaults(container);
        String schema = null;
        if (database == null && agentDefaults != null) {
            database = agentDefaults[0];
            schema = agentDefaults[1];
        }
        AgentContext agentContext = getContext(monitor, container, dataSource, database, schema);
        DbFamily family = DbFamily.of(container);
        synchronized (agentContext.lock) {
            try (JDBCSession session = (JDBCSession) agentContext.context.openSession(
                monitor, DBCExecutionPurpose.UTIL, CONTEXT_NAME)
            ) {
                Connection connection = session.getOriginal();
                Runner runner = new Runner(monitor, container);
                if (!agentContext.isolated) {
                    // The user's own context: never touch its transaction mode
                    return work.run(connection, runner);
                }
                try {
                    return switch (mode) {
                        case READ_ONLY -> agentContext.pending
                            ? work.run(connection, runner)
                            : runReadOnly(connection, family, runner, work);
                        case NATIVE -> work.run(connection, runner);
                        case ATOMIC -> connection.getAutoCommit()
                            ? runAtomic(connection, runner, work)
                            : work.run(connection, runner);
                        case ROLLBACK -> {
                            if (agentContext.pending) {
                                throw new DBException("The agent has an uncommitted transaction on this connection; " +
                                    "commit or roll it back first (commitTransaction / rollbackTransaction)");
                            }
                            yield runRolledBack(connection, runner, work);
                        }
                    };
                } finally {
                    if ((mode == Mode.NATIVE || mode == Mode.ATOMIC) && !connection.getAutoCommit()) {
                        agentContext.pending = probeOpenTransaction(connection, family);
                    }
                }
            } catch (DBException e) {
                throw e;
            } catch (SQLException e) {
                throw new DBException(e.getMessage(), e);
            } catch (Exception e) {
                throw new DBException(CommonUtils.notEmpty(e.getMessage()), e);
            }
        }
    }

    private static <T> T runReadOnly(
        @NotNull Connection connection,
        @NotNull DbFamily family,
        @NotNull Runner runner,
        @NotNull Work<T> work
    ) throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        boolean readOnly = connection.isReadOnly();
        try {
            connection.setAutoCommit(false);
            switch (family) {
                // pgjdbc starts the transaction with BEGIN READ ONLY
                case POSTGRESQL -> connection.setReadOnly(true);
                case MYSQL, MARIADB, ORACLE -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("SET TRANSACTION READ ONLY"); //$NON-NLS-1$
                    }
                }
                default -> {
                    // SQL Server has no read-only transactions: the rollback below is the second guard
                }
            }
            return work.run(connection, runner);
        } finally {
            try {
                connection.rollback();
            } catch (SQLException e) {
                log.debug("Rollback of a read-only agent transaction failed", e);
            }
            try {
                if (connection.isReadOnly() != readOnly) {
                    connection.setReadOnly(readOnly);
                }
                connection.setAutoCommit(autoCommit);
            } catch (SQLException e) {
                log.debug("Can't restore the agent connection state", e);
            }
        }
    }

    private static <T> T runRolledBack(@NotNull Connection connection, @NotNull Runner runner, @NotNull Work<T> work)
        throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            return work.run(connection, runner);
        } finally {
            try {
                connection.rollback();
            } catch (SQLException e) {
                log.debug("Rollback of a measuring agent transaction failed", e);
            }
            connection.setAutoCommit(autoCommit);
        }
    }

    // Auto-commit context, nothing pending: the call's own statements in one transaction (what auto-commit would keep anyway,
    // without half-applied batches); a failure rolls back only this call's own work
    private static <T> T runAtomic(@NotNull Connection connection, @NotNull Runner runner, @NotNull Work<T> work)
        throws Exception {
        try {
            connection.setAutoCommit(false);
            T result = work.run(connection, runner);
            connection.commit();
            return result;
        } catch (Exception e) {
            try {
                connection.rollback();
            } catch (SQLException re) {
                log.debug("Rollback of an agent call failed", re);
            }
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * Whether the connection has an open transaction with work in it (asked from the database).
     * Unknown counts as open: a false "closed" could let a later call end the transaction silently.
     */
    static boolean probeOpenTransaction(@NotNull Connection connection, @NotNull DbFamily family) {
        String sql = switch (family) {
            case SQLSERVER -> "SELECT CASE WHEN @@TRANCOUNT > 0 THEN 1 ELSE 0 END"; //$NON-NLS-1$
            case POSTGRESQL -> "SELECT CASE WHEN txid_current_if_assigned() IS NULL THEN 0 ELSE 1 END"; //$NON-NLS-1$
            case MYSQL, MARIADB -> "SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id = CONNECTION_ID()"; //$NON-NLS-1$
            case ORACLE -> "SELECT CASE WHEN DBMS_TRANSACTION.LOCAL_TRANSACTION_ID IS NULL THEN 0 ELSE 1 END FROM dual"; //$NON-NLS-1$
            default -> null;
        };
        if (sql == null) {
            return true;
        }
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            return !rs.next() || rs.getInt(1) > 0;
        } catch (SQLException e) {
            // PostgreSQL: "current transaction is aborted" also means a transaction is open
            log.debug("Can't check the agent transaction state", e);
            return true;
        }
    }

    /**
     * Describes the agent's open transactions on a connection (one per agent context), empty when none.
     */
    @NotNull
    public static List<String> getPendingTransactions(@NotNull DBPDataSourceContainer container) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<ContextKey, AgentContext> e : contexts.entrySet()) {
            if (e.getKey().containerId().equals(container.getId()) && e.getValue().pending) {
                String database = e.getKey().database();
                result.add(database.isEmpty() ? "default database" : "database " + database);
            }
        }
        return result;
    }

    /**
     * Commits or rolls back the agent's open transactions on a connection. Only for an explicit, confirmed user decision.
     *
     * @return number of transactions ended
     */
    public static int endTransaction(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container,
        boolean commit
    ) throws DBException {
        int count = 0;
        for (Map.Entry<ContextKey, AgentContext> e : List.copyOf(contexts.entrySet())) {
            AgentContext agentContext = e.getValue();
            if (!e.getKey().containerId().equals(container.getId()) || !agentContext.pending) {
                continue;
            }
            synchronized (agentContext.lock) {
                try (JDBCSession session = (JDBCSession) agentContext.context.openSession(
                    monitor, DBCExecutionPurpose.UTIL, CONTEXT_NAME)
                ) {
                    Connection connection = session.getOriginal();
                    if (commit) {
                        connection.commit();
                    } else {
                        connection.rollback();
                    }
                    agentContext.pending = false;
                    count++;
                } catch (SQLException ex) {
                    throw new DBException((commit ? "Commit" : "Rollback") + " failed: " + ex.getMessage(), ex);
                }
            }
        }
        return count;
    }

    /**
     * Whether a context is one of the agent's own (so that it is not counted as a query window).
     */
    public static boolean isAgentContext(@NotNull DBCExecutionContext context) {
        return contexts.values().stream().anyMatch(a -> a.isolated && a.context == context);
    }

    /**
     * Whether the agent left a transaction open on this connection.
     */
    public static boolean hasPendingTransaction(@NotNull DBPDataSourceContainer container) {
        return !getPendingTransactions(container).isEmpty();
    }

    @NotNull
    private static AgentContext getContext(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container,
        @NotNull DBPDataSource dataSource,
        @Nullable String database,
        @Nullable String schema
    ) throws DBException {
        ContextKey key = new ContextKey(container.getId(), database == null ? "" : database.toLowerCase());
        AgentContext existing = contexts.get(key);
        if (existing != null) {
            if (existing.context.getDataSource() == dataSource && existing.context.isConnected()) {
                applySchema(monitor, dataSource, existing.context, schema);
                return existing;
            }
            contexts.remove(key);
            if (existing.isolated) {
                closeQuietly(existing.context);
            }
        }
        DBSInstance instance = dataSource.getDefaultInstance();
        DBSCatalog catalog = null;
        if (database != null) {
            DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, dataSource);
            DBSObject child = root == null ? null : root.getChild(monitor, database);
            if (!(child instanceof DBSCatalog found)) {
                throw new AIToolArgumentException("Database '" + database + "' not found on connection '" +
                    container.getName() + "'. Call listDatabases.");
            }
            catalog = found;
            DBSInstance owner = DBUtils.getObjectOwnerInstance(found);
            if (owner != null) {
                instance = owner;
            }
        }
        AgentContext agentContext;
        if (container.isForceUseSingleConnection()) {
            agentContext = new AgentContext(DBUtils.getDefaultContext(instance, false), false);
        } else {
            agentContext = new AgentContext(instance.openIsolatedContext(monitor, CONTEXT_NAME, null), true);
        }
        if (catalog != null && agentContext.isolated) {
            DBCExecutionContextDefaults<?, ?> contextDefaults = agentContext.context.getContextDefaults();
            if (contextDefaults != null && contextDefaults.supportsCatalogChange()
                && !CommonUtils.equalObjects(contextDefaults.getDefaultCatalog(), catalog)) {
                contextDefaults.setDefaultCatalog(monitor, catalog, null);
            }
        }
        applySchema(monitor, dataSource, agentContext.context, schema);
        contexts.put(key, agentContext);
        return agentContext;
    }

    private static void applySchema(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull DBCExecutionContext context,
        @Nullable String schema
    ) throws DBException {
        if (schema == null) {
            return;
        }
        DBCExecutionContextDefaults<?, ?> contextDefaults = context.getContextDefaults();
        if (contextDefaults == null || !contextDefaults.supportsSchemaChange()) {
            return;
        }
        if (contextDefaults.getDefaultSchema() != null && schema.equalsIgnoreCase(contextDefaults.getDefaultSchema().getName())) {
            return;
        }
        String catalog = contextDefaults.getDefaultCatalog() == null ? null : contextDefaults.getDefaultCatalog().getName();
        DBExecUtils.setExecutionContextDefaults(monitor, dataSource, context, catalog, catalog, schema);
    }

    /**
     * The agent context's default catalog/schema names (for getCurrentDatabase), or null when none is open.
     */
    @Nullable
    public static String[] describeContext(@NotNull DBPDataSourceContainer container) {
        String[] agentDefaults = getAgentDefaults(container);
        String database = agentDefaults == null || agentDefaults[0] == null ? "" : agentDefaults[0].toLowerCase();
        AgentContext agentContext = contexts.get(new ContextKey(container.getId(), database));
        if (agentContext == null) {
            return null;
        }
        DBCExecutionContextDefaults<?, ?> contextDefaults = agentContext.context.getContextDefaults();
        if (contextDefaults == null) {
            return new String[] {null, null};
        }
        return new String[] {
            contextDefaults.getDefaultCatalog() == null ? null : contextDefaults.getDefaultCatalog().getName(),
            contextDefaults.getDefaultSchema() == null ? null : contextDefaults.getDefaultSchema().getName()
        };
    }

    /**
     * Cancels the statements the agent is running on a connection.
     *
     * @return the number of statements asked to cancel
     */
    public static int cancelRunning(@NotNull DBPDataSourceContainer container) {
        Set<Statement> statements = running.get(container.getId());
        if (statements == null) {
            return 0;
        }
        int count = 0;
        for (Statement statement : List.copyOf(statements)) {
            try {
                statement.cancel();
                count++;
            } catch (SQLException e) {
                log.debug("Can't cancel an agent statement", e);
            }
        }
        return count;
    }

    /**
     * Forgets the agent contexts of a connection. Called after a disconnect, which the transaction guard of the disconnect
     * tool confirmed first (docs/chromite/ai-agent.md "트랜잭션 안전").
     */
    public static void closeContexts(@NotNull DBPDataSourceContainer container) {
        for (Map.Entry<ContextKey, AgentContext> e : List.copyOf(contexts.entrySet())) {
            if (e.getKey().containerId().equals(container.getId())) {
                contexts.remove(e.getKey());
                if (e.getValue().isolated) {
                    closeQuietly(e.getValue().context);
                }
            }
        }
    }

    private static void closeQuietly(@NotNull DBCExecutionContext context) {
        try {
            context.close();
        } catch (Exception e) {
            log.debug("Can't close an agent context", e);
        }
    }

    /**
     * Runs statements with cancellation, parameters and result reading.
     */
    public static final class Runner {
        private final DBRProgressMonitor monitor;
        private final DBPDataSourceContainer container;

        Runner(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSourceContainer container) {
            this.monitor = monitor;
            this.container = container;
        }

        @NotNull
        public DBRProgressMonitor getMonitor() {
            return monitor;
        }

        /**
         * Executes one statement and reads every result: {@code {results:[table...], updateCounts:[], messages:[]}}.
         *
         * @param parameters values of {@code ?} placeholders (null binds SQL NULL)
         */
        @NotNull
        public JsonObject execute(
            @NotNull Connection connection,
            @NotNull String sql,
            @Nullable List<Object> parameters,
            @NotNull AgentLimits limits,
            boolean objects
        ) throws SQLException {
            JsonObject result = new JsonObject();
            JsonArray results = new JsonArray();
            JsonArray updateCounts = new JsonArray();
            List<String> messages = new ArrayList<>();
            try (Statement statement = parameters == null ? connection.createStatement() : prepare(connection, sql, parameters)) {
                track(statement, true);
                try {
                    boolean isResultSet = parameters == null ? statement.execute(sql) : ((PreparedStatement) statement).execute();
                    while (true) {
                        collectWarnings(statement.getWarnings(), messages);
                        statement.clearWarnings();
                        if (isResultSet) {
                            try (ResultSet rs = statement.getResultSet()) {
                                results.add(AgentJson.readResultSet(rs, limits, objects));
                            }
                        } else {
                            int count = statement.getUpdateCount();
                            if (count == -1) {
                                break;
                            }
                            updateCounts.add(count);
                        }
                        isResultSet = statement.getMoreResults();
                    }
                } finally {
                    track(statement, false);
                }
            }
            result.add("results", results);
            if (!updateCounts.isEmpty()) {
                result.add("updateCounts", updateCounts);
            }
            if (!messages.isEmpty()) {
                JsonArray json = new JsonArray();
                messages.forEach(json::add);
                result.add("messages", json);
            }
            return result;
        }

        /**
         * Executes a query and returns one page of its rows: skips {@code offset} rows, reads up to the row limit.
         */
        @NotNull
        public JsonObject queryPage(
            @NotNull Connection connection,
            @NotNull String sql,
            int offset,
            @NotNull AgentLimits limits,
            boolean objects
        ) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.setMaxRows(offset + limits.maxRows() + 1);
                track(statement, true);
                try (ResultSet rs = statement.executeQuery(sql)) {
                    for (int i = 0; i < offset && rs.next(); i++) {
                        // skip
                    }
                    JsonObject table = AgentJson.readResultSet(rs, limits, objects);
                    table.addProperty("offset", offset);
                    if (table.get("hasMore").getAsBoolean()) {
                        table.addProperty("nextOffset", offset + table.get("rowCount").getAsInt());
                    }
                    List<String> messages = new ArrayList<>();
                    collectWarnings(statement.getWarnings(), messages);
                    if (!messages.isEmpty()) {
                        JsonArray json = new JsonArray();
                        messages.forEach(json::add);
                        table.add("messages", json);
                    }
                    return table;
                } finally {
                    track(statement, false);
                }
            }
        }

        /**
         * Registers a statement the caller runs itself, for cancelQuery and the monitor; call again with false when done.
         */
        public void track(@NotNull Statement statement, boolean start) {
            trackStatement(statement, start);
        }

        /**
         * Executes a query and returns its first result set as a table.
         */
        @NotNull
        public JsonObject query(
            @NotNull Connection connection,
            @NotNull String sql,
            @Nullable List<Object> parameters,
            @NotNull AgentLimits limits,
            boolean objects
        ) throws SQLException {
            JsonObject result = execute(connection, sql, parameters, limits, objects);
            JsonArray results = result.getAsJsonArray("results");
            if (results.isEmpty()) {
                JsonObject empty = new JsonObject();
                empty.add("columns", new JsonArray());
                empty.add("rows", new JsonArray());
                empty.addProperty("rowCount", 0);
                empty.addProperty("hasMore", false);
                return empty;
            }
            JsonObject table = results.get(0).getAsJsonObject();
            if (result.has("messages")) {
                table.add("messages", result.get("messages"));
            }
            return table;
        }

        /**
         * Runs an update with parameters and returns the update count.
         */
        public int update(@NotNull Connection connection, @NotNull String sql, @Nullable List<Object> parameters) throws SQLException {
            try (PreparedStatement statement = prepare(connection, sql, parameters == null ? List.of() : parameters)) {
                track(statement, true);
                try {
                    return statement.executeUpdate();
                } finally {
                    track(statement, false);
                }
            }
        }

        @NotNull
        private PreparedStatement prepare(
            @NotNull Connection connection,
            @NotNull String sql,
            @NotNull List<Object> parameters
        ) throws SQLException {
            PreparedStatement statement = connection.prepareStatement(sql);
            for (int i = 0; i < parameters.size(); i++) {
                Object value = parameters.get(i);
                if (value == null) {
                    statement.setNull(i + 1, java.sql.Types.VARCHAR);
                } else if (value instanceof Long l) {
                    statement.setLong(i + 1, l);
                } else if (value instanceof Integer n) {
                    statement.setInt(i + 1, n);
                } else if (value instanceof String s) {
                    statement.setString(i + 1, s);
                } else {
                    statement.setObject(i + 1, value);
                }
            }
            return statement;
        }

        private void trackStatement(@NotNull Statement statement, boolean start) {
            if (start) {
                running.computeIfAbsent(container.getId(), id -> ConcurrentHashMap.newKeySet()).add(statement);
                monitor.startBlock(new StatementBlock(statement), "Agent statement"); //$NON-NLS-1$
            } else {
                Set<Statement> statements = running.get(container.getId());
                if (statements != null) {
                    statements.remove(statement);
                }
                monitor.endBlock();
            }
        }
    }

    static void collectWarnings(@Nullable SQLWarning warning, @NotNull List<String> messages) {
        for (SQLWarning w = warning; w != null && messages.size() < 500; w = w.getNextWarning()) {
            if (w.getMessage() != null) {
                messages.add(w.getMessage());
            }
        }
    }

    private record StatementBlock(@NotNull Statement statement) implements DBRBlockingObject {
        @Override
        public void cancelBlock(@NotNull DBRProgressMonitor monitor, @Nullable Thread blockThread) throws DBException {
            try {
                statement.cancel();
            } catch (SQLException e) {
                throw new DBException("Can't cancel the statement", e);
            }
        }
    }
}
