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
package com.seizia.chromite.model.admin.mssql;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.model.admin.AdminSql;
import com.seizia.chromite.model.admin.logs.LogMessages;
import com.seizia.chromite.model.admin.logs.ServerLogDialect;
import com.seizia.chromite.model.admin.logs.ServerLogEntry;
import com.seizia.chromite.model.admin.logs.ServerLogPage;
import com.seizia.chromite.model.admin.logs.ServerLogQuery;
import com.seizia.chromite.model.admin.logs.ServerLogSource;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SQL Server 서버 로그: 오류 로그(보관본 번호를 고름)와 SQL Server 에이전트 로그.
 *
 * 설계:
 * <ul>
 *     <li>읽기는 {@code xp_readerrorlog}(보관본, 로그 종류 1 오류 로그·2 에이전트, 찾을 글자, 시작·끝 시각, 정렬)다. {@code sp_readerrorlog}는
 *     시간 범위와 정렬 인수가 없다. 찾을 글자와 시간 범위는 서버가 거르고 결과는 최신 것부터 받는다. 서버가 쪽 나누기를 하지 않으므로 앞 쪽들의
 *     행을 건너뛰고 쪽 크기만큼 읽은 뒤 결과를 닫는다(커서 = 건너뛸 행 수).</li>
 *     <li>권한: 2022부터는 VIEW ANY ERROR LOG, 그 전에는 securityadmin이 필요하다. 버전마다 다른 권한 이름을 맞추지 않고 보관본 목록
 *     ({@code sp_enumerrorlogs})을 실제로 읽어 보아 판단한다(목록을 읽는 권한과 로그를 읽는 권한이 같다).</li>
 *     <li>에이전트 로그의 수준(1 오류, 2 경고, 3 정보)은 서버가 거르지 못해 읽으면서 거른다. Linux와 에이전트를 쓰지 않는 서버에는 파일이 없을 수
 *     있다. 그때는 읽을 때 서버 오류를 그대로 보인다(보관본 목록은 Linux에서 비어 있어 미리 알 수 없다).</li>
 *     <li>찾을 글자는 N'' 문자열로 넣는다. 확장 프로시저는 nvarchar만 받고, 드라이버의 "문자열을 유니코드로 보내기" 설정과 상관없게 하려는 것이다.
 *     시간은 이 클래스가 만든 형식의 글자다.</li>
 *     <li>Azure SQL Database·Synapse(엔진 에디션 5, 6, 11)에는 이 로그가 없다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 서버 로그 (docs/chromite/swing-admin-tools.md)
 */
public class SQLServerLogDialect extends ServerLogDialect {

    private static final String ERROR_LOG = "errorlog"; //$NON-NLS-1$
    private static final String AGENT_LOG = "agent"; //$NON-NLS-1$
    private static final int TYPE_ERROR_LOG = 1;
    private static final int TYPE_AGENT_LOG = 2;
    private static final Set<Integer> UNSUPPORTED_EDITIONS = Set.of(5, 6, 11);
    // Agent log ErrorLevel
    private static final String LEVEL_ERROR = "1"; //$NON-NLS-1$
    private static final String LEVEL_WARNING = "2"; //$NON-NLS-1$
    private static final String LEVEL_INFO = "3"; //$NON-NLS-1$

    @NotNull
    private final SQLServerDataSource dataSource;

    public SQLServerLogDialect(@NotNull SQLServerDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public List<ServerLogSource> listSources(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session) throws DBException {
        try {
            String edition = AdminSql.queryString(session, "SELECT CAST(SERVERPROPERTY('EngineEdition') AS int)"); //$NON-NLS-1$
            if (edition != null && UNSUPPORTED_EDITIONS.contains(Integer.parseInt(edition))) {
                return List.of(ServerLogSource.unavailable(ERROR_LOG, LogMessages.log_mssql_error_log, LogMessages.log_mssql_edition));
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
        List<ServerLogSource.Option> archives = new ArrayList<>();
        try {
            readArchives(session, TYPE_ERROR_LOG, archives);
        } catch (SQLException e) {
            // Permission error 27219 or 229; both logs need the same permission
            String reason = NLS.bind(LogMessages.log_mssql_no_permission, CommonUtils.notEmpty(e.getMessage()));
            return List.of(ServerLogSource.unavailable(ERROR_LOG, LogMessages.log_mssql_error_log, reason),
                ServerLogSource.unavailable(AGENT_LOG, LogMessages.log_mssql_agent_log, reason));
        }
        List<ServerLogSource.Option> agentArchives = new ArrayList<>();
        try {
            readArchives(session, TYPE_AGENT_LOG, agentArchives);
        } catch (SQLException e) {
            // Not fatal: the current agent log may still be readable
        }
        if (agentArchives.isEmpty()) {
            agentArchives.add(new ServerLogSource.Option("0", LogMessages.log_archive_current)); //$NON-NLS-1$
        }
        List<ServerLogSource.Option> agentLevels = List.of(
            new ServerLogSource.Option(LEVEL_ERROR, LogMessages.log_level_error),
            new ServerLogSource.Option(LEVEL_WARNING, LogMessages.log_level_warning),
            new ServerLogSource.Option(LEVEL_INFO, LogMessages.log_level_information));
        return List.of(
            new ServerLogSource(ERROR_LOG, LogMessages.log_mssql_error_log, null, archives, List.of(), LogMessages.log_col_process),
            new ServerLogSource(AGENT_LOG, LogMessages.log_mssql_agent_log, null, agentArchives, agentLevels, null));
    }

    /**
     * Archive numbers with their last write time, current log first.
     */
    private static void readArchives(@NotNull JDBCSession session, int logType, @NotNull List<ServerLogSource.Option> archives) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        AdminSql.query(session, "EXEC sys.sp_enumerrorlogs " + logType, //$NON-NLS-1$
            rs -> rows.add(new Object[]{rs.getInt(1), CommonUtils.notEmpty(rs.getString(2)).replaceAll("\\s+", " ").trim()})); //$NON-NLS-1$ //$NON-NLS-2$
        rows.sort((a, b) -> Integer.compare((Integer) a[0], (Integer) b[0]));
        for (Object[] row : rows) {
            int number = (Integer) row[0];
            archives.add(new ServerLogSource.Option(String.valueOf(number), number == 0
                ? NLS.bind(LogMessages.log_archive_current_at, row[1])
                : NLS.bind(LogMessages.log_archive_number, number, row[1])));
        }
    }

    @NotNull
    @Override
    public ServerLogPage read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @NotNull ServerLogQuery query)
        throws DBException {
        boolean agent = AGENT_LOG.equals(query.sourceId());
        int archive = query.archiveId() == null ? 0 : Integer.parseInt(query.archiveId());
        String text = query.searchText();
        String start = null;
        String end = null;
        try {
            if (query.last() != null) {
                // The log is written in the server's local time
                start = AdminSql.queryString(session, "SELECT CONVERT(varchar(19), DATEADD(second, -?, GETDATE()), 120)", //$NON-NLS-1$
                    (int) Math.min(Integer.MAX_VALUE, query.last().toSeconds()));
            }
            if (query.from() != null) {
                start = ServerLogQuery.SQL_TIME.format(query.from());
            }
            if (query.to() != null) {
                end = ServerLogQuery.SQL_TIME.format(query.to());
            }
            String sql = "EXEC sys.xp_readerrorlog " + archive + ", " + (agent ? TYPE_AGENT_LOG : TYPE_ERROR_LOG) //$NON-NLS-1$ //$NON-NLS-2$
                + ", " + unicodeLiteral(text == null ? "" : text) + ", N'', " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (start == null ? "NULL" : "'" + start + "'") + ", " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                + (end == null ? "NULL" : "'" + end + "'") + ", N'desc'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            int skip = query.cursor() == null ? 0 : Integer.parseInt(query.cursor());
            List<ServerLogEntry> entries = new ArrayList<>();
            int consumed = 0;
            boolean more = false;
            try (JDBCPreparedStatement statement = session.prepareStatement(sql)) {
                // The agent log returns no result set at all (not an empty one) when nothing matches the search text
                if (!statement.execute()) {
                    return new ServerLogPage(entries, null);
                }
                JDBCResultSet rs = statement.getResultSet();
                while (rs != null && rs.next()) {
                    if (monitor.isCanceled()) {
                        more = true;
                        break;
                    }
                    consumed++;
                    if (consumed <= skip) {
                        continue;
                    }
                    Timestamp time = rs.getTimestamp(1);
                    String second = rs.getString(2);
                    String message = CommonUtils.notEmpty(rs.getString(3)).stripTrailing();
                    String level = null;
                    String source = null;
                    if (agent) {
                        if (query.levelId() != null && !query.levelId().equals(second)) {
                            continue;
                        }
                        level = levelName(second);
                    } else {
                        source = second;
                    }
                    entries.add(new ServerLogEntry(time == null ? null : time.toLocalDateTime(), level, source, message, null));
                    if (entries.size() >= query.limit()) {
                        more = true;
                        break;
                    }
                }
            }
            return new ServerLogPage(entries, more ? String.valueOf(consumed) : null);
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    @Nullable
    private static String levelName(@Nullable String level) {
        if (LEVEL_ERROR.equals(level)) {
            return LogMessages.log_level_error;
        } else if (LEVEL_WARNING.equals(level)) {
            return LogMessages.log_level_warning;
        } else if (LEVEL_INFO.equals(level)) {
            return LogMessages.log_level_information;
        }
        return level;
    }

    @NotNull
    private static String unicodeLiteral(@NotNull String text) {
        return "N'" + text.replace("'", "''") + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
