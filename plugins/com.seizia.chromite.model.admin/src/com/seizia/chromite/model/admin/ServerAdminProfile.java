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
package com.seizia.chromite.model.admin;

import com.seizia.chromite.model.admin.altibase.AltibaseBlockingLockManager;
import com.seizia.chromite.model.admin.mssql.SQLServerLockManager;
import com.seizia.chromite.model.admin.mysql.MySQLLockManager;
import com.seizia.chromite.model.admin.oracle.OracleActivitySessionManager;
import com.seizia.chromite.model.admin.oracle.OracleBlockingLockManager;
import com.seizia.chromite.model.admin.postgresql.PostgreActivitySessionManager;
import com.seizia.chromite.model.admin.postgresql.PostgreBlockingLockManager;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.ext.mssql.model.session.SQLServerSession;
import com.seizia.chromite.ext.mssql.model.session.SQLServerSessionManager;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.mysql.model.session.MySQLSession;
import com.seizia.chromite.ext.mysql.model.session.MySQLSessionManager;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.lock.OracleLock;
import com.seizia.chromite.ext.oracle.model.lock.OracleLockManager;
import com.seizia.chromite.ext.oracle.model.session.OracleServerSession;
import com.seizia.chromite.ext.oracle.model.session.OracleServerSessionManager;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.impls.PostgreServerPostgreSQL;
import com.seizia.chromite.ext.postgresql.model.lock.PostgreLock;
import com.seizia.chromite.ext.postgresql.model.lock.PostgreLockManager;
import com.seizia.chromite.ext.postgresql.model.session.PostgreSession;
import com.seizia.chromite.ext.postgresql.model.session.PostgreSessionManager;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.admin.locks.DBAServerLock;
import com.seizia.chromite.model.admin.locks.DBAServerLockItem;
import com.seizia.chromite.model.admin.locks.DBAServerLockManager;
import com.seizia.chromite.model.admin.sessions.DBAServerSession;
import com.seizia.chromite.model.admin.sessions.DBAServerSessionManager;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.admin.locks.LockGraphManager;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * DB 종류별 서버 관리 도구 설정: 세션 관리자, 세션 목록 필터, 세션 명령, 잠금 관리자.
 *
 * 설계: SWT에서는 DB마다 UI 플러그인에 세션 편집기({@code PostgreSessionEditor}, {@code SQLServerSessionEditor},
 * {@code MySQLSessionEditor}, {@code OracleSessionEditor})와 잠금 편집기({@code PostgreLockEditor}, {@code OracleLockEditor})가 있고,
 * 각 편집기가 도구 막대 단추(필터, 끝내기 명령)와 모델 옵션 키를 직접 다룬다. Swing 화면은 SWT UI 플러그인을 쓸 수 없고 DB별 화면을 따로
 * 만들 이유도 없어, 그 차이만 이 클래스에 모으고 화면은 하나로 둔다. 옵션 키는 모델 클래스의 상수를 그대로 쓴다.
 * <ul>
 *     <li>필터({@link FilterOption})는 세션 관리자의 읽기 옵션 하나다. SWT 편집기의 체크 단추와 같은 기본값을 쓴다.</li>
 *     <li>명령({@link SessionAction})은 {@code DBAServerSessionManager#alterSession}에 넘길 옵션이다. 확인 질문은 화면이 한다.</li>
 *     <li>"내 세션만"은 SWT에 없는 Chromite 기능이다. 서버 세션 관리자에 옵션이 없어 세션의 사용자 속성을 연결 설정의 사용자 이름과
 *     비교하는 화면 쪽 필터로 한다. 그래서 세션의 사용자를 읽는 방법({@link #getSessionUser})을 DB마다 둔다.</li>
 *     <li>잠금 관리자는 데이터 소스 어댑터로 제공되지 않아(SWT 잠금 편집기도 직접 만든다) 여기서 만든다.
 *     SQL Server와 MySQL/MariaDB는 DBeaver에 없어 이 번들의 관리자({@link SQLServerLockManager}, {@link MySQLLockManager})를 쓴다.</li>
 *     <li>PostgreSQL(9.6+, 서버 종류 PostgreSQL)과 Oracle(10g+)은 SQL Server와 같은 수준(대기, 막는 세션, 트랜잭션 경과 시간, 객체로 풀린
 *     잠금 대상, 권한 확인)이 되도록 이 번들의 관리자({@link PostgreActivitySessionManager}, {@link PostgreBlockingLockManager},
 *     {@link OracleActivitySessionManager}, {@link OracleBlockingLockManager})를 쓴다. PostgreSQL 계열 다른 서버(Greenplum, Redshift 등)와
 *     그보다 오래된 버전은 {@code pg_blocking_pids}, {@code BLOCKING_SESSION}이 없어 DBeaver 관리자를 그대로 쓴다.</li>
 *     <li>권한 확인({@link #checkAccess})은 연결마다 첫 작업에서 한 번 한다. 결과({@link ServerAdminAccess})의 안내와 명령 사용 여부는 화면이 쓴다.</li>
 *     <li>Altibase는 세션이 데이터 소스 어댑터({@code AltibaseServerSessionManager})이고, 잠금은 다른 DB와 같은 행 모양의
 *     {@link AltibaseBlockingLockManager}다(DBeaver {@code AltibaseLockManager}는 행 모양이 달라 쓰지 않는다).</li>
 *     <li>그 밖의 DB는 데이터 소스 어댑터의 세션 관리자와 그 관리자가 정한 끝내기 옵션({@code getTerminateOptions})만 쓴다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: Swing Activity Monitor, 잠금 보기 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-07 PostgreSQL·Oracle 세션·잠금 관리자를 이 번들의 것으로 바꾸고 권한 확인을 더함 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-08 Altibase 잠금 관리자를 더함 (docs/chromite/swing-admin-tools.md "Altibase 잠금")
 * - 2026-11-08 Oracle 18c+ 쿼리 취소(ALTER SYSTEM CANCEL SQL) 명령 (docs/chromite/swing-admin-tools.md "Oracle RAC와 쿼리 취소")
 */
public final class ServerAdminProfile {

    private static final Log log = Log.getLog(ServerAdminProfile.class);

    /**
     * A read option of the session manager shown as a check box.
     *
     * @param optionKey    key in the options of {@code DBAServerSessionManager#getSessions}
     * @param defaultValue value of a new window (as in the SWT editors)
     */
    public record FilterOption(@NotNull String optionKey, @NotNull String label, @NotNull String toolTip, boolean defaultValue) {
    }

    /**
     * A command on the selected sessions ({@code DBAServerSessionManager#alterSession}).
     *
     * @param options            options passed to alterSession
     * @param queryOnly          stops the running statement only; the session stays connected
     * @param needsActiveQuery   enabled only for sessions that run a query
     * @param immediateOptionKey option asked in the confirmation (a check box), or null
     */
    public record SessionAction(
        @NotNull String label,
        @NotNull String toolTip,
        @NotNull Map<String, Object> options,
        boolean queryOnly,
        boolean needsActiveQuery,
        @Nullable String immediateOptionKey
    ) {
        public boolean isEnabled(@NotNull Collection<? extends DBAServerSession> sessions) {
            if (sessions.isEmpty()) {
                return false;
            }
            return !needsActiveQuery || sessions.stream().anyMatch(session -> !CommonUtils.isEmpty(session.getActiveQuery()));
        }
    }

    /**
     * Reads what the login may do (see {@link ServerAdminAccess}).
     */
    @FunctionalInterface
    private interface AccessChecker {
        @NotNull
        ServerAdminAccess check(@NotNull JDBCSession session) throws SQLException;
    }

    @Nullable
    private final DBAServerSessionManager<DBAServerSession> sessionManager;
    private final List<FilterOption> filterOptions = new ArrayList<>();
    private final List<SessionAction> sessionActions = new ArrayList<>();
    @Nullable
    private Function<DBAServerSession, String> sessionUserReader;
    @Nullable
    private DBAServerLockManager<DBAServerLock, DBAServerLockItem> lockManager;
    /**
     * Session ids of a lock for its lock items, for the DBeaver lock managers (PostgreSQL based servers and PostgreSQL
     * before 9.6, Oracle before 10g).
     */
    @Nullable
    private Function<DBAServerLock, Map<String, Object>> lockSessionOptions;
    @Nullable
    private AccessChecker accessChecker;
    private volatile ServerAdminAccess access = ServerAdminAccess.FULL;
    private volatile boolean accessChecked;

    @SuppressWarnings("unchecked")
    private ServerAdminProfile(@Nullable DBAServerSessionManager<?> sessionManager) {
        this.sessionManager = (DBAServerSessionManager<DBAServerSession>) sessionManager;
    }

    /**
     * The tools of a connected data source.
     */
    @NotNull
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ServerAdminProfile forDataSource(@NotNull DBPDataSource dataSource) {
        ServerAdminProfile profile;
        if (dataSource instanceof PostgreDataSource postgreDataSource) {
            // PostgreSessionEditor, PostgreLockEditor
            // pg_blocking_pids and the wait columns of pg_stat_activity: PostgreSQL 9.6+, not the PostgreSQL based servers
            boolean blockingPids = postgreDataSource.getServerType() instanceof PostgreServerPostgreSQL
                && postgreDataSource.isServerVersionAtLeast(9, 6);
            if (blockingPids) {
                PostgreActivitySessionManager sessionManager = new PostgreActivitySessionManager(postgreDataSource);
                profile = new ServerAdminProfile(sessionManager);
                profile.accessChecker = sessionManager::checkAccess;
            } else {
                profile = new ServerAdminProfile(DBUtils.getAdapter(DBAServerSessionManager.class, dataSource));
            }
            profile.filterOptions.add(new FilterOption(PostgreSessionManager.OPTION_SHOW_IDLE,
                ServerAdminMessages.filter_show_idle, ServerAdminMessages.filter_show_idle_tip, true));
            profile.sessionActions.add(new SessionAction(ServerAdminMessages.action_terminate,
                ServerAdminMessages.action_terminate_tip, Map.of(), false, false, null));
            profile.sessionActions.add(new SessionAction(ServerAdminMessages.action_cancel_query,
                ServerAdminMessages.action_cancel_query_tip, Map.of(PostgreSessionManager.OPTION_QUERY_CANCEL, true), true, false, null));
            profile.sessionUserReader = session -> session instanceof PostgreSession postgreSession ? postgreSession.getUser() : null;
            if (blockingPids) {
                profile.lockManager = (DBAServerLockManager) new PostgreBlockingLockManager(postgreDataSource);
            } else {
                profile.lockManager = (DBAServerLockManager) new PostgreLockManager(postgreDataSource);
                profile.lockSessionOptions = lock -> {
                    PostgreLock postgreLock = (PostgreLock) lock;
                    Map<String, Object> options = new LinkedHashMap<>();
                    options.put(PostgreLockManager.pidHold, postgreLock.getHold_pid());
                    options.put(PostgreLockManager.pidWait, postgreLock.getWait_pid());
                    return options;
                };
            }
        } else if (dataSource instanceof SQLServerDataSource sqlServerDataSource) {
            // SQLServerSessionEditor; DBeaver has no SQL Server lock editor
            profile = new ServerAdminProfile(new SQLServerSessionManager(sqlServerDataSource));
            profile.filterOptions.add(new FilterOption(SQLServerSessionManager.OPTION_SHOW_ONLY_CONNECTIONS,
                ServerAdminMessages.filter_only_connections, ServerAdminMessages.filter_only_connections_tip, true));
            profile.sessionActions.add(new SessionAction(ServerAdminMessages.action_terminate,
                ServerAdminMessages.action_terminate_tip, Map.of(), false, false, null));
            profile.sessionUserReader = session -> session instanceof SQLServerSession sqlServerSession ? sqlServerSession.getLoginName() : null;
            profile.lockManager = (DBAServerLockManager) new SQLServerLockManager(sqlServerDataSource);
        } else if (dataSource instanceof MySQLDataSource mySQLDataSource) {
            // MySQLSessionEditor (MySQL and MariaDB); DBeaver has no MySQL lock editor
            profile = new ServerAdminProfile(new MySQLSessionManager(mySQLDataSource));
            profile.filterOptions.add(new FilterOption(MySQLSessionManager.OPTION_HIDE_SLEEPING,
                ServerAdminMessages.filter_hide_sleeping, ServerAdminMessages.filter_hide_sleeping_tip, false));
            if (mySQLDataSource.supportsSysSchema()) {
                profile.filterOptions.add(new FilterOption(MySQLSessionManager.OPTION_SHOW_PERFORMANCE,
                    ServerAdminMessages.filter_show_performance, ServerAdminMessages.filter_show_performance_tip, false));
            }
            profile.sessionActions.add(new SessionAction(ServerAdminMessages.action_kill_session,
                ServerAdminMessages.action_kill_session_tip, Map.of(MySQLSessionManager.PROP_KILL_QUERY, false), false, false, null));
            profile.sessionActions.add(new SessionAction(ServerAdminMessages.action_kill_query,
                ServerAdminMessages.action_kill_query_tip, Map.of(MySQLSessionManager.PROP_KILL_QUERY, true), true, true, null));
            profile.sessionUserReader = session -> session instanceof MySQLSession mySQLSession ? mySQLSession.getUser() : null;
            profile.lockManager = (DBAServerLockManager) new MySQLLockManager(mySQLDataSource);
        } else if (dataSource instanceof OracleDataSource oracleDataSource) {
            // OracleSessionEditor, OracleLockEditor
            // V$SESSION.BLOCKING_SESSION and SQL_ID: Oracle 10g+
            if (oracleDataSource.isAtLeastV10()) {
                OracleActivitySessionManager sessionManager = new OracleActivitySessionManager(oracleDataSource);
                OracleBlockingLockManager lockManager = new OracleBlockingLockManager(oracleDataSource);
                profile = new ServerAdminProfile(sessionManager);
                profile.lockManager = (DBAServerLockManager) lockManager;
                profile.accessChecker = session -> sessionManager.checkAccess(session, lockManager);
            } else {
                profile = new ServerAdminProfile(new OracleServerSessionManager(oracleDataSource));
                profile.lockManager = (DBAServerLockManager) new OracleLockManager(oracleDataSource);
                profile.lockSessionOptions = lock -> {
                    OracleLock oracleLock = (OracleLock) lock;
                    Map<String, Object> options = new LinkedHashMap<>();
                    options.put(OracleLockManager.sidHold, oracleLock.getHold_sid());
                    options.put(OracleLockManager.sidWait, oracleLock.getWait_sid());
                    return options;
                };
            }
            profile.filterOptions.add(new FilterOption(OracleServerSessionManager.OPTION_SHOW_BACKGROUND,
                ServerAdminMessages.filter_show_background, ServerAdminMessages.filter_show_background_tip, false));
            profile.filterOptions.add(new FilterOption(OracleServerSessionManager.OPTION_SHOW_INACTIVE,
                ServerAdminMessages.filter_show_inactive, ServerAdminMessages.filter_show_inactive_tip, false));
            profile.sessionActions.add(new SessionAction(ServerAdminMessages.action_kill_session,
                ServerAdminMessages.action_kill_session_tip, Map.of(OracleServerSessionManager.PROP_KILL_SESSION, true), false, false,
                OracleServerSessionManager.PROP_IMMEDIATE));
            profile.sessionActions.add(new SessionAction(ServerAdminMessages.action_disconnect_session,
                ServerAdminMessages.action_disconnect_session_tip, Map.of(), false, false, OracleServerSessionManager.PROP_IMMEDIATE));
            if (oracleDataSource.isAtLeastV10() && oracleDataSource.isServerVersionAtLeast(18, 0)) {
                // ALTER SYSTEM CANCEL SQL (OracleActivitySessionManager, the 10g+ branch above): Oracle 18c+,
                // same ALTER SYSTEM privilege as Kill Session
                profile.sessionActions.add(new SessionAction(ServerAdminMessages.action_cancel_query,
                    ServerAdminMessages.action_cancel_query_tip, Map.of(OracleActivitySessionManager.PROP_CANCEL_SQL, true), true, true, null));
            }
            profile.sessionUserReader = session -> session instanceof OracleServerSession oracleSession ? oracleSession.getUser() : null;
        } else if (dataSource instanceof AltibaseDataSource altibaseDataSource) {
            // AltibaseSessionEditor (the data source adapter), AltibaseLockEditor
            DBAServerSessionManager<?> manager = DBUtils.getAdapter(DBAServerSessionManager.class, dataSource);
            profile = new ServerAdminProfile(manager);
            if (manager != null) {
                profile.sessionActions.add(new SessionAction(ServerAdminMessages.action_terminate,
                    ServerAdminMessages.action_terminate_tip, manager.getTerminateOptions(), false, false, null));
            }
            profile.lockManager = (DBAServerLockManager) new AltibaseBlockingLockManager(altibaseDataSource);
        } else {
            // Other databases: the session manager adapter and its own terminate options
            DBAServerSessionManager<?> manager = DBUtils.getAdapter(DBAServerSessionManager.class, dataSource);
            profile = new ServerAdminProfile(manager);
            if (manager != null) {
                profile.sessionActions.add(new SessionAction(ServerAdminMessages.action_terminate,
                    ServerAdminMessages.action_terminate_tip, manager.getTerminateOptions(), false, false, null));
            }
        }
        return profile;
    }

    /**
     * The session manager, or null if the database has none.
     */
    @Nullable
    public DBAServerSessionManager<DBAServerSession> getSessionManager() {
        return sessionManager;
    }

    @NotNull
    public List<FilterOption> getFilterOptions() {
        return filterOptions;
    }

    @NotNull
    public List<SessionAction> getSessionActions() {
        return sessionActions;
    }

    /**
     * Whether the sessions can be filtered by user ("only my sessions").
     */
    public boolean supportsUserFilter() {
        return sessionUserReader != null;
    }

    /**
     * The database user of a session, or null if unknown.
     */
    @Nullable
    public String getSessionUser(@NotNull DBAServerSession session) {
        return sessionUserReader == null ? null : sessionUserReader.apply(session);
    }

    /**
     * Checks once what the login may do (the first task of a connection runs it). A failed check leaves everything
     * allowed: the server then reports what it refuses.
     */
    public void checkAccess(@NotNull DBCSession session) {
        if (accessChecked) {
            return;
        }
        accessChecked = true;
        if (accessChecker != null && session instanceof JDBCSession jdbcSession) {
            try {
                access = accessChecker.check(jdbcSession);
            } catch (SQLException e) {
                log.debug("Error checking the permissions of the login", e);
            }
        }
    }

    /**
     * What the login may do: everything until {@link #checkAccess} has run, and for databases without a check.
     */
    @NotNull
    public ServerAdminAccess getAccess() {
        return access;
    }

    /**
     * The lock manager, or null if the database has none.
     */
    @Nullable
    public DBAServerLockManager<DBAServerLock, DBAServerLockItem> getLockManager() {
        return lockManager;
    }

    /**
     * Options of {@code DBAServerLockManager#getLockItems} for the locks of the session that waits ({@code holding}
     * false) or of the session that blocks it, as in the SWT lock editors ({@code LockManagerViewer#refreshDetail}).
     */
    @NotNull
    public Map<String, Object> getLockItemOptions(@NotNull DBAServerLock lock, boolean holding) {
        if (lock instanceof BlockingSessionLock blockingLock) {
            return AbstractBlockingLockManager.getLockItemOptions(blockingLock, holding);
        }
        Map<String, Object> options = new LinkedHashMap<>();
        if (lockSessionOptions != null) {
            options.putAll(lockSessionOptions.apply(lock));
        }
        options.put(LockGraphManager.keyType, holding ? LockGraphManager.typeHold : LockGraphManager.typeWait);
        return options;
    }
}
