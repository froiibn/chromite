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
package com.seizia.chromite.model.admin.mysql;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.model.admin.AdminSql;
import com.seizia.chromite.model.admin.logs.LogMessages;
import com.seizia.chromite.model.admin.logs.ServerLogDialect;
import com.seizia.chromite.model.admin.logs.ServerLogEntry;
import com.seizia.chromite.model.admin.logs.ServerLogPage;
import com.seizia.chromite.model.admin.logs.ServerLogQuery;
import com.seizia.chromite.model.admin.logs.ServerLogSource;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL·MariaDB 서버 로그: 오류 로그({@code performance_schema.error_log}, MySQL 8.0.22+)와 느린 쿼리 로그({@code mysql.slow_log},
 * log_output에 TABLE이 있을 때).
 *
 * 설계:
 * <ul>
 *     <li>둘 다 표라서 시간 범위, 찾을 글자, 수준, 정렬(최신 것부터), 쪽 나누기({@code LIMIT ? OFFSET ?})를 서버가 한다. 커서는 OFFSET이다.</li>
 *     <li>읽을 수 없는 경우를 미리 가린다: MariaDB에는 오류 로그 표가 없고(파일 log_error나 표준 오류로만 쓴다), MySQL 8.0.22 전에도 없다.
 *     느린 쿼리 로그는 slow_query_log가 꺼져 있거나 log_output이 FILE뿐이면 표가 비어 있다. 권한은 표를 실제로 읽어 보아 판단한다
 *     (performance_schema.error_log의 SELECT, mysql.slow_log의 SELECT).</li>
 *     <li>시각은 서버가 글자로 바꾼 값을 읽는다({@code DATE_FORMAT}). 드라이버가 TIMESTAMP를 클라이언트 시간대로 바꾸지 않게 하려는 것이다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 서버 로그 (docs/chromite/swing-admin-tools.md)
 */
public class MySQLLogDialect extends ServerLogDialect {

    private static final String ERROR_LOG = "errorlog"; //$NON-NLS-1$
    private static final String SLOW_LOG = "slowlog"; //$NON-NLS-1$
    private static final String[] PRIORITIES = {"System", "Error", "Warning", "Note"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)"); //$NON-NLS-1$
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"); //$NON-NLS-1$
    private static final String TIME_FORMAT = "'%Y-%m-%d %H:%i:%s.%f'"; //$NON-NLS-1$

    @NotNull
    private final MySQLDataSource dataSource;

    public MySQLLogDialect(@NotNull MySQLDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public List<ServerLogSource> listSources(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session) throws DBException {
        try {
            List<String> settings = new ArrayList<>();
            AdminSql.query(session, "SELECT VERSION(), @@log_error, @@log_output, @@slow_query_log, @@slow_query_log_file", rs -> { //$NON-NLS-1$
                for (int i = 1; i <= 5; i++) {
                    settings.add(rs.getString(i));
                }
            });
            String version = settings.get(0);
            String errorFile = CommonUtils.isEmpty(settings.get(1)) || "stderr".equalsIgnoreCase(settings.get(1)) //$NON-NLS-1$
                ? LogMessages.log_mysql_stderr : settings.get(1);
            List<ServerLogSource> sources = new ArrayList<>();

            // Error log
            if (dataSource.isMariaDB()) {
                sources.add(ServerLogSource.unavailable(ERROR_LOG, LogMessages.log_mysql_error_log,
                    NLS.bind(LogMessages.log_mysql_mariadb_error_log, errorFile)));
            } else if (!isAtLeast(version, 8, 0, 22)) {
                sources.add(ServerLogSource.unavailable(ERROR_LOG, LogMessages.log_mysql_error_log,
                    NLS.bind(LogMessages.log_mysql_old_error_log, errorFile)));
            } else {
                SQLException error = AdminSql.probe(session, "SELECT 1 FROM performance_schema.error_log WHERE 1 = 0"); //$NON-NLS-1$
                if (error != null) {
                    sources.add(ServerLogSource.unavailable(ERROR_LOG, LogMessages.log_mysql_error_log,
                        NLS.bind(LogMessages.log_mysql_no_permission, "performance_schema.error_log", CommonUtils.notEmpty(error.getMessage())))); //$NON-NLS-1$
                } else {
                    List<ServerLogSource.Option> levels = new ArrayList<>();
                    for (String priority : PRIORITIES) {
                        levels.add(new ServerLogSource.Option(priority, priority));
                    }
                    sources.add(new ServerLogSource(ERROR_LOG, LogMessages.log_mysql_error_log, null, List.of(), levels, LogMessages.log_col_subsystem));
                }
            }

            // Slow query log
            String output = CommonUtils.notEmpty(settings.get(2));
            boolean slowOn = "1".equals(settings.get(3)) || "ON".equalsIgnoreCase(settings.get(3)); //$NON-NLS-1$ //$NON-NLS-2$
            if (!slowOn) {
                sources.add(ServerLogSource.unavailable(SLOW_LOG, LogMessages.log_mysql_slow_log, LogMessages.log_mysql_slow_off));
            } else if (!output.toUpperCase(Locale.ROOT).contains("TABLE")) { //$NON-NLS-1$
                sources.add(ServerLogSource.unavailable(SLOW_LOG, LogMessages.log_mysql_slow_log,
                    NLS.bind(LogMessages.log_mysql_slow_file, output, CommonUtils.notEmpty(settings.get(4)))));
            } else {
                SQLException error = AdminSql.probe(session, "SELECT 1 FROM mysql.slow_log WHERE 1 = 0"); //$NON-NLS-1$
                if (error != null) {
                    sources.add(ServerLogSource.unavailable(SLOW_LOG, LogMessages.log_mysql_slow_log,
                        NLS.bind(LogMessages.log_mysql_no_permission, "mysql.slow_log", CommonUtils.notEmpty(error.getMessage())))); //$NON-NLS-1$
                } else {
                    sources.add(new ServerLogSource(SLOW_LOG, LogMessages.log_mysql_slow_log, null, List.of(), List.of(), LogMessages.log_col_user_host));
                }
            }
            return sources;
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    private static boolean isAtLeast(@Nullable String version, int major, int minor, int patch) {
        if (version == null) {
            return false;
        }
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.find()) {
            return false;
        }
        int[] parts = {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))};
        int[] wanted = {major, minor, patch};
        for (int i = 0; i < 3; i++) {
            if (parts[i] != wanted[i]) {
                return parts[i] > wanted[i];
            }
        }
        return true;
    }

    @NotNull
    @Override
    public ServerLogPage read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @NotNull ServerLogQuery query)
        throws DBException {
        boolean slow = SLOW_LOG.equals(query.sourceId());
        String timeColumn = slow ? "start_time" : "LOGGED"; //$NON-NLS-1$ //$NON-NLS-2$
        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        if (query.last() != null) {
            conditions.add(timeColumn + " >= NOW(6) - INTERVAL ? SECOND"); //$NON-NLS-1$
            parameters.add(query.last().toSeconds());
        }
        if (query.from() != null) {
            conditions.add(timeColumn + " >= ?"); //$NON-NLS-1$
            parameters.add(ServerLogQuery.SQL_TIME.format(query.from()));
        }
        if (query.to() != null) {
            conditions.add(timeColumn + " <= ?"); //$NON-NLS-1$
            parameters.add(ServerLogQuery.SQL_TIME.format(query.to()));
        }
        String text = query.searchText();
        if (text != null) {
            conditions.add(slow
                ? "(LOCATE(LOWER(?), LOWER(CONVERT(sql_text USING utf8mb4))) > 0 OR LOCATE(LOWER(?), LOWER(user_host)) > 0)" //$NON-NLS-1$
                : "LOCATE(LOWER(?), LOWER(DATA)) > 0"); //$NON-NLS-1$
            parameters.add(text);
            if (slow) {
                parameters.add(text);
            }
        }
        if (!slow && query.levelId() != null) {
            conditions.add("PRIO = ?"); //$NON-NLS-1$
            parameters.add(query.levelId());
        }
        int offset = query.cursor() == null ? 0 : Integer.parseInt(query.cursor());
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String sql = slow
            ? "SELECT DATE_FORMAT(start_time, " + TIME_FORMAT + "), user_host, db, CAST(query_time AS CHAR), CAST(lock_time AS CHAR), " //$NON-NLS-1$ //$NON-NLS-2$
                + "rows_sent, rows_examined, CONVERT(sql_text USING utf8mb4) FROM mysql.slow_log" + where //$NON-NLS-1$
                + " ORDER BY start_time DESC LIMIT ? OFFSET ?" //$NON-NLS-1$
            : "SELECT DATE_FORMAT(LOGGED, " + TIME_FORMAT + "), PRIO, ERROR_CODE, SUBSYSTEM, THREAD_ID, DATA FROM performance_schema.error_log" //$NON-NLS-1$ //$NON-NLS-2$
                + where + " ORDER BY LOGGED DESC LIMIT ? OFFSET ?"; //$NON-NLS-1$
        // One more row than the page tells whether there are older entries
        parameters.add(query.limit() + 1);
        parameters.add(offset);
        List<ServerLogEntry> entries = new ArrayList<>();
        try {
            AdminSql.query(session, sql, rs -> {
                LocalDateTime time = parseTime(rs.getString(1));
                if (slow) {
                    String detail = NLS.bind(LogMessages.log_mysql_slow_detail, new Object[]{
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), CommonUtils.notEmpty(rs.getString(3))});
                    entries.add(new ServerLogEntry(time, null, rs.getString(2), CommonUtils.notEmpty(rs.getString(8)), detail));
                } else {
                    String code = rs.getString(3);
                    String thread = rs.getString(5);
                    String detail = (CommonUtils.isEmpty(code) ? "" : "ERROR_CODE: " + code) //$NON-NLS-1$ //$NON-NLS-2$
                        + (CommonUtils.isEmpty(thread) || "0".equals(thread) ? "" : (CommonUtils.isEmpty(code) ? "" : "\n") + "THREAD_ID: " + thread); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                    entries.add(new ServerLogEntry(time, rs.getString(2), rs.getString(4), CommonUtils.notEmpty(rs.getString(6)),
                        detail.isEmpty() ? null : detail));
                }
            }, parameters.toArray());
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
        boolean more = entries.size() > query.limit();
        if (more) {
            entries.remove(entries.size() - 1);
        }
        return new ServerLogPage(entries, more ? String.valueOf(offset + query.limit()) : null);
    }

    @Nullable
    private static LocalDateTime parseTime(@Nullable String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(text, TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
