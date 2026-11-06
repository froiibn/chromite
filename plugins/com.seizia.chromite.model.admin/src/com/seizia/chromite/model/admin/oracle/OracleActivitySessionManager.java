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
package com.seizia.chromite.model.admin.oracle;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.session.OracleServerSession;
import com.seizia.chromite.ext.oracle.model.session.OracleServerSessionManager;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Activity Monitor의 Oracle 세션 관리자: DBeaver {@link OracleServerSessionManager}에 막는 세션·트랜잭션 경과 시간과 권한 확인을 더했다.
 *
 * 설계:
 * <ul>
 *     <li>읽기 질의는 DBeaver 질의와 같은 뷰({@code GV$SESSION}, {@code GV$SQL}, {@code GV$SESS_IO})에 {@code GV$TRANSACTION}
 *     (트랜잭션 시작 시각)을 더한다. DBeaver 질의는 {@code GV$SQL}을 인스턴스 번호 없이 바깥 조인해 RAC에서 행이 겹칠 수 있어,
 *     문장은 인스턴스·SQL_ID·자식 번호로 찾는 하위 질의로 바꿨다. 막는 세션은 {@code GV$SESSION.BLOCKING_SESSION}(10g+)이다.</li>
 *     <li>필터(Show background, Show inactive)와 끝내기 명령(KILL/DISCONNECT SESSION)은 DBeaver 관리자 것을 그대로 쓴다.</li>
 *     <li>권한 확인({@link #checkAccess}): 세션·잠금에 쓰는 동적 성능 뷰를 하나씩 읽어 보고(SELECT_CATALOG_ROLE, SELECT ANY DICTIONARY,
 *     개별 GRANT 어느 쪽이든 실제로 읽히는지가 기준), 세션을 끝내려면 {@code ALTER SYSTEM} 권한({@code SESSION_PRIVS})을 본다.</li>
 *     <li>라이선스: ASH·AWR({@code V$ACTIVE_SESSION_HISTORY}, {@code DBA_HIST_*}), SQL Monitor 뷰는 쓰지 않는다
 *     (Diagnostics/Tuning Pack 라이선스가 필요하다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: Oracle 세션·잠금 (docs/chromite/swing-admin-tools.md)
 */
public class OracleActivitySessionManager extends OracleServerSessionManager {

    private static final String[] SESSION_VIEWS = {"GV$SESSION", "GV$SQL", "GV$SESS_IO", "GV$TRANSACTION"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    private static final String[] LOCK_VIEWS = {"V$SESSION", "V$LOCK", "V$SQL"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    // ORA-00942 table or view does not exist, ORA-01031 insufficient privileges
    private static final int ORA_NO_TABLE = 942;
    private static final int ORA_NO_PRIVILEGE = 1031;

    public OracleActivitySessionManager(@NotNull OracleDataSource dataSource) {
        super(dataSource);
    }

    /**
     * Checks which views the login can read and whether it may kill sessions; tells the lock manager where object
     * names come from.
     */
    @NotNull
    public ServerAdminAccess checkAccess(@NotNull JDBCSession session, @NotNull OracleBlockingLockManager lockManager) throws SQLException {
        List<String> missing = new ArrayList<>();
        boolean sessions = canReadAll(session, SESSION_VIEWS, missing);
        boolean locks = canReadAll(session, LOCK_VIEWS, missing);
        boolean dbaObjects = canRead(session, OracleBlockingLockManager.DBA_OBJECTS);
        if (!dbaObjects) {
            missing.add(OracleBlockingLockManager.DBA_OBJECTS);
        }
        lockManager.setObjectsView(dbaObjects ? OracleBlockingLockManager.DBA_OBJECTS : OracleBlockingLockManager.ALL_OBJECTS);
        boolean kill;
        try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT COUNT(*) FROM SESSION_PRIVS WHERE PRIVILEGE = 'ALTER SYSTEM'"); //$NON-NLS-1$
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            kill = dbResult.next() && dbResult.getInt(1) > 0;
        }
        List<String> notices = new ArrayList<>();
        if (!missing.isEmpty()) {
            notices.add(NLS.bind(ActivityAccessMessages.access_oracle_missing_views, String.join(", ", missing))); //$NON-NLS-1$
        }
        if (!kill) {
            notices.add(ActivityAccessMessages.access_oracle_no_alter_system);
        }
        return notices.isEmpty() ? ServerAdminAccess.FULL
            : new ServerAdminAccess(String.join(" ", notices), sessions, locks, kill); //$NON-NLS-1$
    }

    private static boolean canReadAll(@NotNull JDBCSession session, @NotNull String[] views, @NotNull List<String> missing) throws SQLException {
        boolean all = true;
        for (String view : views) {
            if (!canRead(session, view)) {
                missing.add(view);
                all = false;
            }
        }
        return all;
    }

    private static boolean canRead(@NotNull JDBCSession session, @NotNull String view) throws SQLException {
        // Parsing is enough: no rows are read
        try (JDBCPreparedStatement dbStat = session.prepareStatement("SELECT 1 FROM " + view + " WHERE 1 = 0"); //$NON-NLS-1$ //$NON-NLS-2$
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == ORA_NO_TABLE || e.getErrorCode() == ORA_NO_PRIVILEGE) {
                return false;
            }
            throw e;
        }
    }

    @NotNull
    @Override
    public Collection<OracleServerSession> getSessions(@NotNull DBCSession session, @NotNull Map<String, Object> options) throws DBException {
        try (JDBCPreparedStatement dbStat = ((JDBCSession) session).prepareStatement(generateSessionReadQuery(options));
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            List<OracleServerSession> sessions = new ArrayList<>();
            while (dbResult.next()) {
                sessions.add(new OracleActivitySession(dbResult));
            }
            return sessions;
        } catch (SQLException e) {
            throw new DBDatabaseException(e, session.getDataSource());
        }
    }

    @NotNull
    @Override
    public String generateSessionReadQuery(@NotNull Map<String, Object> options) {
        StringBuilder sql = new StringBuilder(
            "SELECT s.*,\n" +
            "(SELECT q.SQL_FULLTEXT FROM GV$SQL q WHERE q.INST_ID = s.INST_ID AND q.SQL_ID = s.SQL_ID\n" +
            "  AND q.CHILD_NUMBER = s.SQL_CHILD_NUMBER AND ROWNUM = 1) AS SQL_FULLTEXT,\n" +
            "io.BLOCK_GETS, io.CONSISTENT_GETS, io.PHYSICAL_READS, io.BLOCK_CHANGES, io.CONSISTENT_CHANGES,\n" +
            "ROUND((SYSDATE - t.START_DATE) * 86400) AS XACT_SECONDS\n" +
            "FROM GV$SESSION s\n" +
            "LEFT JOIN GV$SESS_IO io ON io.INST_ID = s.INST_ID AND io.SID = s.SID\n" +
            "LEFT JOIN GV$TRANSACTION t ON t.INST_ID = s.INST_ID AND t.ADDR = s.TADDR\n" +
            "WHERE 1 = 1"); //$NON-NLS-1$
        if (!CommonUtils.getOption(options, OPTION_SHOW_BACKGROUND)) {
            sql.append("\nAND s.TYPE = 'USER'"); //$NON-NLS-1$
        }
        if (!CommonUtils.getOption(options, OPTION_SHOW_INACTIVE)) {
            sql.append("\nAND s.STATUS <> 'INACTIVE'"); //$NON-NLS-1$
        }
        return sql.toString();
    }
}
