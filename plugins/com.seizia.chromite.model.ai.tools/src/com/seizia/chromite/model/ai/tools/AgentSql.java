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
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.ai.agent.AIPendingTransaction;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.ai.tools.internal.AIToolsMessages;
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
import java.util.concurrent.locks.ReentrantLock;

/**
 * SQL execution of agent tools: an isolated execution context per connection and database, read-only protection,
 * and cancellation.
 *
 * 설계(docs/chromite/ai-agent.md "실행 문맥", "읽기 전용 보호"):
 * <ul>
 *     <li>문맥: 연결(그리고 {@code database} 인수)마다 {@code openIsolatedContext}로 연 에이전트 전용 문맥의 작은 풀(최대 {@value #MAX_CONTEXTS}개).
 *     사용자 쿼리 창과 탐색기 문맥을 건드리지 않으므로 자동 커밋·읽기 전용 상태를 마음대로 바꾸고 되돌릴 수 있다. PostgreSQL처럼 DB마다 인스턴스가
 *     다르면 그 DB의 인스턴스에서 열고, SQL Server·MySQL처럼 한 인스턴스면 연 문맥의 기본 DB를 바꾼다. 한 문맥은 잠금으로 한 번에 한 호출만 쓴다.
 *     문맥이 하나뿐이면 잠금에 걸린 쓰기(예: 블로킹된 CREATE INDEX) 하나가 그 연결의 모든 진단과 취소 확인을 막는다(2026-11-07 시험에서 확인).
 *     그래서 바쁜 문맥이 있으면 새 문맥을 연다. 쓰기는 열린 트랜잭션이 있는 문맥을 이어서 써야 하므로 그 문맥을 기다리고, 읽기는 그 문맥이 비어
 *     있으면 그것을(아직 커밋하지 않은 변경이 보이게), 아니면 다른 문맥을 쓴다.</li>
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
 *     <li>에이전트의 열린 트랜잭션 안에서 실행하는 호출은(PostgreSQL) 먼저 통계 스냅숏을 비운다({@link #clearStatisticsSnapshot}).
 *     PostgreSQL은 트랜잭션에서 처음 읽은 {@code pg_stat_activity}·통계 뷰를 트랜잭션이 끝날 때까지 그대로 돌려주어, 진단 도구가 그 뒤에 생긴
 *     세션과 대기를 보지 못한다(Activity Monitor 잠금 탭과 같은 원인, docs/chromite/swing-admin-tools.md "모니터링 연결의 커밋 방식").
 *     열린 트랜잭션이 없는 읽기는 호출마다 새 트랜잭션이라 해당하지 않는다.</li>
 *     <li>취소: 실행 중 문장을 연결별로 기록해 {@code cancelQuery}가 {@link Statement#cancel()}을 부른다. 모니터에도 블록으로 등록해
 *     MCP 취소 알림·채팅 중지가 같은 문장을 취소한다.</li>
 *     <li>"단일 연결 사용" 연결은 격리 문맥을 열 수 없어 기본 문맥을 쓴다. 이때는 사용자 트랜잭션을 건드리지 않도록 자동 커밋을 바꾸지 않고,
 *     읽기 보호는 분류기만 한다.</li>
 *     <li>유휴 연결 닫기: 에이전트 문장은 쿼리 관리자에 기록되지 않으므로 사용 중 여부와 마지막 사용 시각({@link #isInUse},
 *     {@link #getLastUseTime})을 {@link AgentSessionsService}로 알린다. 열린 트랜잭션이 있거나 실행 중인 연결은 닫히지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-07 열린 트랜잭션의 DB 목록({@link #getPendingTransactionDatabases})과 확인 대화상자용 지역화된 설명
 *   ({@link #pendingAgentTransaction})을 나눔. 모델에 돌려주는 {@link #getPendingTransactions}는 영어 그대로
 *   (docs/chromite/swing-l10n.md "남은 한국어 문구 정리")
 * - 2026-11-07 유휴 연결 닫기용 사용 중 여부와 마지막 사용 시각 (docs/chromite/idle-transactions.md "유휴 연결 닫기")
 * - 2026-11-07 격리 문맥 이름을 기능의 공식 이름 "Chromite Agent"로 바꿈. 서버 세션 목록의 프로그램 이름에 보이는 값이다
 *   (docs/chromite/ai-agent.md "명칭", "실행 문맥")
 * - 2026-11-08 열린 에이전트 트랜잭션 안의 호출은 PostgreSQL 통계 스냅숏을 먼저 비움(docs/chromite/ai-agent.md "실행 문맥")
 */
public final class AgentSql {

    private static final Log log = Log.getLog(AgentSql.class);
    private static final String CONTEXT_NAME = "Chromite Agent"; //$NON-NLS-1$

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
        final ReentrantLock lock = new ReentrantLock();
        // The agent left an uncommitted transaction open (manual-commit connection)
        volatile boolean pending;

        AgentContext(@NotNull DBCExecutionContext context, boolean isolated) {
            this.context = context;
            this.isolated = isolated;
        }
    }

    private static final int MAX_CONTEXTS = 4;
    // Pools of agent contexts; a pool list is guarded by synchronizing on it
    private static final Map<ContextKey, List<AgentContext>> pools = new ConcurrentHashMap<>();
    private static final Map<String, Set<Statement>> running = new ConcurrentHashMap<>();
    // Per connection: when the agent last finished work on it (the query manager does not see the agent's statements)
    private static final Map<String, Long> lastUse = new ConcurrentHashMap<>();
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
        AgentContext agentContext = acquire(monitor, container, dataSource, database, schema, mode);
        DbFamily family = DbFamily.of(container);
        try {
            try (JDBCSession session = (JDBCSession) agentContext.context.openSession(
                monitor, DBCExecutionPurpose.UTIL, CONTEXT_NAME)
            ) {
                Connection connection = session.getOriginal();
                Runner runner = new Runner(monitor, container);
                if (!agentContext.isolated) {
                    // The user's own context: never touch its transaction mode
                    return work.run(connection, runner);
                }
                if (agentContext.pending && family == DbFamily.POSTGRESQL) {
                    clearStatisticsSnapshot(connection);
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
                        case ROLLBACK -> runRolledBack(connection, runner, work);
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
        } finally {
            lastUse.put(container.getId(), System.currentTimeMillis());
            agentContext.lock.unlock();
        }
    }

    /**
     * The call continues the agent's open transaction (PostgreSQL): the server would answer {@code pg_stat_activity} and the
     * statistics views from the snapshot taken at their first read in that transaction, so sessions, waits and counters that
     * changed since would not show. Clearing it changes nothing else in the transaction.
     */
    private static void clearStatisticsSnapshot(@NotNull Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_catalog.pg_stat_clear_snapshot()"); //$NON-NLS-1$
        } catch (SQLException e) {
            // An aborted transaction fails the call's own statements with the server's message
            log.debug("Can't clear the statistics snapshot of the agent transaction", e);
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
                // MySQL/MariaDB: SET TRANSACTION would only apply to the NEXT transaction, and a call that starts none (SHOW)
                // would leave it for a later write; START TRANSACTION begins the read-only transaction now
                case MYSQL, MARIADB -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("START TRANSACTION READ ONLY"); //$NON-NLS-1$
                    }
                }
                // Oracle: SET TRANSACTION begins the transaction
                case ORACLE -> {
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
     * English: the text goes to the AI model (getTransactionStatus).
     */
    @NotNull
    public static List<String> getPendingTransactions(@NotNull DBPDataSourceContainer container) {
        List<String> result = new ArrayList<>();
        for (String database : getPendingTransactionDatabases(container)) {
            result.add(database.isEmpty() ? "default database" : "database " + database); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result;
    }

    /**
     * Databases of the agent's open transactions on a connection (one per agent context; empty string for the default
     * database), empty when none.
     */
    @NotNull
    public static List<String> getPendingTransactionDatabases(@NotNull DBPDataSourceContainer container) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<ContextKey, List<AgentContext>> e : pools.entrySet()) {
            if (!e.getKey().containerId().equals(container.getId())) {
                continue;
            }
            for (AgentContext agentContext : snapshot(e.getValue())) {
                if (agentContext.pending) {
                    result.add(e.getKey().database());
                }
            }
        }
        return result;
    }

    /**
     * The agent's open transaction in a database as the confirmation dialog lists it, in the UI language.
     *
     * @param database        database of the transaction, empty for the default database
     * @param implicitCommitBy product that commits it implicitly before the call's DDL, or null
     */
    @NotNull
    public static AIPendingTransaction pendingAgentTransaction(@NotNull String database, @Nullable String implicitCommitBy) {
        String description;
        if (implicitCommitBy == null) {
            description = database.isEmpty() ? AIToolsMessages.pending_agent_work_default
                : NLS.bind(AIToolsMessages.pending_agent_work_database, database);
        } else {
            description = database.isEmpty() ? NLS.bind(AIToolsMessages.pending_agent_work_ddl_default, implicitCommitBy)
                : NLS.bind(AIToolsMessages.pending_agent_work_ddl_database, database, implicitCommitBy);
        }
        return new AIPendingTransaction(AIToolsMessages.pending_owner_agent, description, true);
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
        for (Map.Entry<ContextKey, List<AgentContext>> e : List.copyOf(pools.entrySet())) {
            if (!e.getKey().containerId().equals(container.getId())) {
                continue;
            }
            for (AgentContext agentContext : snapshot(e.getValue())) {
                if (!agentContext.pending) {
                    continue;
                }
                agentContext.lock.lock();
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
                } finally {
                    agentContext.lock.unlock();
                }
            }
        }
        return count;
    }

    /**
     * Whether a context is one of the agent's own (so that it is not counted as a query window).
     */
    public static boolean isAgentContext(@NotNull DBCExecutionContext context) {
        for (List<AgentContext> pool : pools.values()) {
            for (AgentContext agentContext : snapshot(pool)) {
                if (agentContext.isolated && agentContext.context == context) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the agent left a transaction open on this connection.
     */
    public static boolean hasPendingTransaction(@NotNull DBPDataSourceContainer container) {
        return !getPendingTransactions(container).isEmpty();
    }

    /**
     * Whether the agent works on the connection now (one of its contexts is taken) or left a transaction open on it.
     */
    public static boolean isInUse(@NotNull DBPDataSourceContainer container) {
        for (Map.Entry<ContextKey, List<AgentContext>> e : pools.entrySet()) {
            if (!e.getKey().containerId().equals(container.getId())) {
                continue;
            }
            for (AgentContext agentContext : snapshot(e.getValue())) {
                if (agentContext.pending || agentContext.lock.isLocked()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * When the agent last finished work on the connection, or 0 when it never worked on it.
     */
    public static long getLastUseTime(@NotNull DBPDataSourceContainer container) {
        return lastUse.getOrDefault(container.getId(), 0L);
    }

    /**
     * Picks a context of the pool and locks it (see the class comment for the rules); the caller unlocks it.
     */
    @NotNull
    private static AgentContext acquire(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container,
        @NotNull DBPDataSource dataSource,
        @Nullable String database,
        @Nullable String schema,
        @NotNull Mode mode
    ) throws DBException {
        ContextKey key = new ContextKey(container.getId(), database == null ? "" : database.toLowerCase());
        List<AgentContext> pool = pools.computeIfAbsent(key, k -> new ArrayList<>());
        AgentContext chosen = null;
        boolean locked = false;
        synchronized (pool) {
            // A reconnect replaces the data source: contexts of the old one are gone (with any transaction they had)
            for (AgentContext agentContext : List.copyOf(pool)) {
                if (agentContext.context.getDataSource() != dataSource || !agentContext.context.isConnected()) {
                    pool.remove(agentContext);
                    if (agentContext.isolated) {
                        closeQuietly(agentContext.context);
                    }
                }
            }
            AgentContext pending = pool.stream().filter(c -> c.pending).findFirst().orElse(null);
            if (pending != null && (mode == Mode.NATIVE || mode == Mode.ATOMIC)) {
                // Writes continue the open transaction
                chosen = pending;
            } else {
                if (pending != null && mode == Mode.READ_ONLY && pending.lock.tryLock()) {
                    chosen = pending;
                    locked = true;
                }
                for (AgentContext agentContext : pool) {
                    if (chosen == null && !agentContext.pending && agentContext.lock.tryLock()) {
                        chosen = agentContext;
                        locked = true;
                    }
                }
                if (chosen == null && pool.size() < MAX_CONTEXTS) {
                    chosen = open(monitor, container, dataSource, database);
                    chosen.lock.lock();
                    locked = true;
                    pool.add(chosen);
                }
                if (chosen == null) {
                    chosen = pool.stream().filter(c -> !c.pending).findFirst().orElse(pool.getFirst());
                }
            }
        }
        if (!locked) {
            chosen.lock.lock();
        }
        try {
            if (mode == Mode.ROLLBACK && chosen.pending) {
                throw new DBException("The agent has an uncommitted transaction on this connection; " +
                    "commit or roll it back first (commitTransaction / rollbackTransaction)");
            }
            applySchema(monitor, dataSource, chosen.context, schema);
        } catch (DBException e) {
            chosen.lock.unlock();
            throw e;
        }
        return chosen;
    }

    @NotNull
    private static AgentContext open(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container,
        @NotNull DBPDataSource dataSource,
        @Nullable String database
    ) throws DBException {
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
                DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, dataSource);
                if (root != null) {
                    DBExecUtils.changeDefaultObject(monitor, root, contextDefaults, catalog.getName(), null, null);
                }
            }
        }
        return agentContext;
    }

    @NotNull
    private static List<AgentContext> snapshot(@NotNull List<AgentContext> pool) {
        synchronized (pool) {
            return List.copyOf(pool);
        }
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
        for (Map.Entry<ContextKey, List<AgentContext>> e : List.copyOf(pools.entrySet())) {
            if (e.getKey().containerId().equals(container.getId())) {
                pools.remove(e.getKey());
                for (AgentContext agentContext : snapshot(e.getValue())) {
                    if (agentContext.isolated) {
                        closeQuietly(agentContext.context);
                    }
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
                if (value instanceof NullValue typed) {
                    statement.setNull(i + 1, typed.sqlType());
                } else if (value == null) {
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

    /**
     * SQL NULL of a given JDBC type: PostgreSQL infers a parameter's type from its first use, so an integer parameter that is
     * NULL must not be sent as VARCHAR ({@code pid = $1} would fail with "integer = character varying").
     */
    public record NullValue(int sqlType) {
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
