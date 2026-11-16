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
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracle 서버 로그: 경고 로그(alert log)를 {@code V$DIAG_ALERT_EXT}로 읽는다(11gR2+).
 *
 * 설계:
 * <ul>
 *     <li>{@code V$DIAG_ALERT_EXT}는 ADR의 XML 경고 로그를 보이는 뷰이고 Diagnostics/Tuning Pack 뷰가 아니다(ORACLE LICENSE GUARD:
 *     AWR·ASH 뷰는 쓰지 않는다). 권한은 뷰를 실제로 읽어 보아 판단한다. SELECT_CATALOG_ROLE에는 이 뷰가 없어(23ai에서 ORA-00942로 확인)
 *     SELECT ANY DICTIONARY나 SYS가 준 {@code V_$DIAG_ALERT_EXT}의 SELECT가 필요하다.</li>
 *     <li>시간 범위, 찾을 글자, 수준, 정렬, 쪽 나누기를 서버가 한다(ROWNUM으로 자른다: 12c 전에도 되게). 뷰가 로그 파일 전체를 읽으므로
 *     화면의 기본 시간 범위를 좁게 둔다.</li>
 *     <li>수준은 MESSAGE_LEVEL(1 Critical, 2 Severe, 8 Important, 16 Normal)이다. MESSAGE_TYPE은 23ai에서 "파일 크기 바꿈 완료"가 3(ERROR)으로
 *     나오는 등 이름과 맞지 않아(2026-11-07 Oracle Free 23ai에서 확인) 수준으로 쓰지 않고 상세에만 보인다. 오류 줄은 "ORA- 오류" 선택으로
 *     메시지의 "ORA-"를 찾는다.</li>
 *     <li>PDB에 연결하면 그 PDB의 항목만 보인다(뷰가 CON_ID로 거른다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 서버 로그 (docs/chromite/swing-admin-tools.md)
 */
public class OracleLogDialect extends ServerLogDialect {

    private static final String ALERT_LOG = "alert"; //$NON-NLS-1$
    private static final String VIEW = "V$DIAG_ALERT_EXT"; //$NON-NLS-1$
    // Level filter ids: MESSAGE_LEVEL values, and ORA for the messages with an ORA- error
    private static final String LEVEL_CRITICAL = "1"; //$NON-NLS-1$
    private static final String LEVEL_SEVERE = "2"; //$NON-NLS-1$
    private static final String LEVEL_IMPORTANT = "8"; //$NON-NLS-1$
    private static final String LEVEL_NORMAL = "16"; //$NON-NLS-1$
    private static final String LEVEL_ORA = "ora"; //$NON-NLS-1$

    @NotNull
    private final OracleDataSource dataSource;

    public OracleLogDialect(@NotNull OracleDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @NotNull
    @Override
    public List<ServerLogSource> listSources(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session) {
        if (!dataSource.isAtLeastV11()) {
            return List.of(ServerLogSource.unavailable(ALERT_LOG, LogMessages.log_oracle_alert_log, LogMessages.log_oracle_version));
        }
        SQLException error = AdminSql.probe(session, "SELECT 1 FROM " + VIEW + " WHERE 1 = 0"); //$NON-NLS-1$ //$NON-NLS-2$
        if (error != null) {
            return List.of(ServerLogSource.unavailable(ALERT_LOG, LogMessages.log_oracle_alert_log,
                NLS.bind(LogMessages.log_oracle_no_permission, VIEW, CommonUtils.notEmpty(error.getMessage()).trim())));
        }
        List<ServerLogSource.Option> levels = List.of(
            new ServerLogSource.Option(LEVEL_ORA, LogMessages.log_oracle_level_ora),
            new ServerLogSource.Option(LEVEL_CRITICAL, LogMessages.log_level_critical),
            new ServerLogSource.Option(LEVEL_SEVERE, LogMessages.log_level_severe),
            new ServerLogSource.Option(LEVEL_IMPORTANT, LogMessages.log_level_important),
            new ServerLogSource.Option(LEVEL_NORMAL, LogMessages.log_level_normal));
        return List.of(new ServerLogSource(ALERT_LOG, LogMessages.log_oracle_alert_log, null, List.of(), levels, LogMessages.log_col_component));
    }

    @NotNull
    @Override
    public ServerLogPage read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @NotNull ServerLogQuery query)
        throws DBException {
        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        if (query.last() != null) {
            conditions.add("ORIGINATING_TIMESTAMP >= SYSTIMESTAMP - NUMTODSINTERVAL(?, 'SECOND')"); //$NON-NLS-1$
            parameters.add(query.last().toSeconds());
        }
        // The custom range compares the time as written in the log (its own time zone)
        if (query.from() != null) {
            conditions.add("CAST(ORIGINATING_TIMESTAMP AS TIMESTAMP) >= TO_TIMESTAMP(?, 'YYYY-MM-DD HH24:MI:SS')"); //$NON-NLS-1$
            parameters.add(ServerLogQuery.SQL_TIME.format(query.from()));
        }
        if (query.to() != null) {
            conditions.add("CAST(ORIGINATING_TIMESTAMP AS TIMESTAMP) <= TO_TIMESTAMP(?, 'YYYY-MM-DD HH24:MI:SS')"); //$NON-NLS-1$
            parameters.add(ServerLogQuery.SQL_TIME.format(query.to()));
        }
        String text = query.searchText();
        if (text != null) {
            conditions.add("INSTR(UPPER(MESSAGE_TEXT), UPPER(?)) > 0"); //$NON-NLS-1$
            parameters.add(text);
        }
        if (LEVEL_ORA.equals(query.levelId())) {
            conditions.add("MESSAGE_TEXT LIKE '%ORA-%'"); //$NON-NLS-1$
        } else if (query.levelId() != null) {
            conditions.add("MESSAGE_LEVEL = ?"); //$NON-NLS-1$
            parameters.add(Integer.parseInt(query.levelId()));
        }
        int offset = query.cursor() == null ? 0 : Integer.parseInt(query.cursor());
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String sql = "SELECT * FROM (SELECT a.*, ROWNUM rn FROM (" //$NON-NLS-1$
            + "SELECT CAST(ORIGINATING_TIMESTAMP AS TIMESTAMP) log_time, MESSAGE_LEVEL, MESSAGE_TYPE, COMPONENT_ID, MODULE_ID, PROCESS_ID, " //$NON-NLS-1$
            + "CON_ID, PROBLEM_KEY, MESSAGE_TEXT FROM " + VIEW + where //$NON-NLS-1$
            + " ORDER BY ORIGINATING_TIMESTAMP DESC) a WHERE ROWNUM <= ?) WHERE rn > ?"; //$NON-NLS-1$
        // One more row than the page tells whether there are older entries
        parameters.add(offset + query.limit() + 1);
        parameters.add(offset);
        List<ServerLogEntry> entries = new ArrayList<>();
        try {
            AdminSql.query(session, sql, rs -> {
                Timestamp time = rs.getTimestamp(1);
                StringBuilder detail = new StringBuilder();
                appendDetail(detail, "MESSAGE_LEVEL", rs.getString(2)); //$NON-NLS-1$
                appendDetail(detail, "MESSAGE_TYPE", rs.getString(3)); //$NON-NLS-1$
                appendDetail(detail, "MODULE_ID", rs.getString(5)); //$NON-NLS-1$
                appendDetail(detail, "PROCESS_ID", rs.getString(6)); //$NON-NLS-1$
                appendDetail(detail, "CON_ID", rs.getString(7)); //$NON-NLS-1$
                appendDetail(detail, "PROBLEM_KEY", rs.getString(8)); //$NON-NLS-1$
                entries.add(new ServerLogEntry(time == null ? null : time.toLocalDateTime(), levelName(rs.getString(2)), rs.getString(4),
                    CommonUtils.notEmpty(rs.getString(9)).stripTrailing(), detail.isEmpty() ? null : detail.toString()));
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
    private static String levelName(@Nullable String level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case LEVEL_CRITICAL -> LogMessages.log_level_critical;
            case LEVEL_SEVERE -> LogMessages.log_level_severe;
            case LEVEL_IMPORTANT -> LogMessages.log_level_important;
            case LEVEL_NORMAL -> LogMessages.log_level_normal;
            default -> level;
        };
    }

    private static void appendDetail(@NotNull StringBuilder detail, @NotNull String name, @Nullable String value) {
        if (!CommonUtils.isEmpty(value)) {
            if (!detail.isEmpty()) {
                detail.append('\n');
            }
            detail.append(name).append(": ").append(value); //$NON-NLS-1$
        }
    }
}
