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
package com.seizia.chromite.model.admin.postgresql;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.session.PostgreSession;
import com.seizia.chromite.ext.postgresql.model.session.PostgreSessionManager;
import com.seizia.chromite.model.admin.ActivityAccessMessages;
import com.seizia.chromite.model.admin.ServerAdminAccess;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Activity Monitor의 PostgreSQL 세션 관리자: DBeaver {@link PostgreSessionManager}에 대기·막는 세션·트랜잭션 경과 시간과
 * 권한에 따른 "자기 세션만" 읽기를 더했다.
 *
 * 설계:
 * <ul>
 *     <li>읽기 질의는 {@code pg_stat_activity}에 {@code pg_blocking_pids}(9.6+)와 {@code now() - xact_start}를 더한다.
 *     경과 시간을 서버에서 계산해 클라이언트 시계와 상관없게 한다. 그래서 PostgreSQL 9.6 이상(서버 종류 PostgreSQL)에만 쓴다
 *     ({@code ServerAdminProfile}).</li>
 *     <li>superuser도 pg_monitor(pg_read_all_stats)도 아닌 로그인은 남의 세션의 SQL·상태가 "insufficient privilege"로만 보인다.
 *     그런 행을 늘어놓는 대신 자기 세션만 읽고 안내한다({@link #checkAccess}). 안내는 화면이 보인다.</li>
 *     <li>끝내기·취소({@code pg_terminate_backend}/{@code pg_cancel_backend})는 DBeaver와 같은 함수지만 결과(boolean)를 확인한다.
 *     세션이 이미 없으면 서버는 경고만 하고 false를 돌려주므로, 그대로 두면 끝낸 것처럼 보인다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: PostgreSQL 세션·잠금 (docs/chromite/swing-admin-tools.md)
 */
public class PostgreActivitySessionManager extends PostgreSessionManager {

    private static final String PRIVILEGED_QUERY_V10 =
        "SELECT r.rolsuper OR pg_catalog.pg_has_role(r.oid, 'pg_read_all_stats', 'USAGE')\n" +
        "FROM pg_catalog.pg_roles r WHERE r.rolname = current_user"; //$NON-NLS-1$
    private static final String PRIVILEGED_QUERY_V96 =
        "SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user"; //$NON-NLS-1$

    private final PostgreDataSource dataSource;
    private volatile boolean ownSessionsOnly;

    public PostgreActivitySessionManager(@NotNull PostgreDataSource dataSource) {
        super(dataSource);
        this.dataSource = dataSource;
    }

    /**
     * Whether the login sees the details of all sessions (superuser or pg_read_all_stats, which pg_monitor includes);
     * otherwise only its own sessions are read from now on.
     */
    @NotNull
    public ServerAdminAccess checkAccess(@NotNull JDBCSession session) throws SQLException {
        boolean privileged;
        try (JDBCPreparedStatement dbStat = session.prepareStatement(
                dataSource.isServerVersionAtLeast(10, 0) ? PRIVILEGED_QUERY_V10 : PRIVILEGED_QUERY_V96);
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            privileged = dbResult.next() && dbResult.getBoolean(1);
        }
        ownSessionsOnly = !privileged;
        return privileged ? ServerAdminAccess.FULL
            : new ServerAdminAccess(ActivityAccessMessages.access_postgres_own_sessions, true, true, true);
    }

    @NotNull
    @Override
    public List<PostgreSession> getSessions(@NotNull DBCSession session, @NotNull Map<String, Object> options) throws DBException {
        try (JDBCPreparedStatement dbStat = ((JDBCSession) session).prepareStatement(generateSessionReadQuery(options));
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            List<PostgreSession> sessions = new ArrayList<>();
            while (dbResult.next()) {
                sessions.add(new PostgreActivitySession(dbResult));
            }
            return sessions;
        } catch (SQLException e) {
            throw new DBDatabaseException(e, session.getDataSource());
        }
    }

    @Override
    public void alterSession(@NotNull DBCSession session, @NotNull String sessionId, @NotNull Map<String, Object> options) throws DBException {
        boolean cancel = CommonUtils.toBoolean(options.get(OPTION_QUERY_CANCEL));
        try (JDBCPreparedStatement dbStat = ((JDBCSession) session).prepareStatement(cancel
                ? "SELECT pg_catalog.pg_cancel_backend(?)" //$NON-NLS-1$
                : "SELECT pg_catalog.pg_terminate_backend(?)")) { //$NON-NLS-1$
            dbStat.setInt(1, Integer.parseInt(sessionId));
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                if (!dbResult.next() || !dbResult.getBoolean(1)) {
                    throw new DBException(NLS.bind(ActivityAccessMessages.session_not_ended, sessionId));
                }
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, session.getDataSource());
        }
    }

    @NotNull
    @Override
    public String generateSessionReadQuery(@NotNull Map<String, Object> options) {
        StringBuilder sql = new StringBuilder(
            "SELECT sa.*,\n" +
            "pg_catalog.array_to_string(pg_catalog.pg_blocking_pids(sa.pid), ', ') AS blocked_by,\n" +
            "CAST(EXTRACT(EPOCH FROM (pg_catalog.now() - sa.xact_start)) AS bigint) AS xact_seconds\n" +
            "FROM pg_catalog.pg_stat_activity sa\n" +
            "WHERE 1 = 1"); //$NON-NLS-1$
        if (!CommonUtils.getOption(options, OPTION_SHOW_IDLE)) {
            sql.append("\nAND (sa.state IS NULL OR sa.state NOT LIKE 'idle%')"); //$NON-NLS-1$
        }
        if (ownSessionsOnly) {
            sql.append("\nAND sa.usename = current_user"); //$NON-NLS-1$
        }
        return sql.toString();
    }
}
