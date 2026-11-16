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
package com.seizia.chromite.model.admin.logs;

import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.impls.PostgreServerPostgreSQL;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.admin.mssql.SQLServerLogDialect;
import com.seizia.chromite.model.admin.mysql.MySQLLogDialect;
import com.seizia.chromite.model.admin.oracle.OracleLogDialect;
import com.seizia.chromite.model.admin.postgresql.PostgreLogDialect;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.util.List;
import java.util.Locale;

/**
 * 서버 로그(Server Logs) 화면의 DB별 부분: 읽을 수 있는 로그와 그 보관본을 알려 주고, 조건에 맞는 항목을 최신 것부터 한 쪽씩 읽는다.
 *
 * 설계: DBeaver에는 이 화면이 없다. DB마다 로그를 SQL로 읽는 방법이 하나뿐이라(SQL Server {@code xp_readerrorlog}, PostgreSQL
 * {@code pg_read_binary_file}, MySQL {@code performance_schema.error_log}·{@code mysql.slow_log}, Oracle {@code V$DIAG_ALERT_EXT})
 * 하위 클래스가 그 방법과 권한 확인을 맡는다.
 * <ul>
 *     <li>권한·설정을 먼저 본다({@link #listSources}). 읽을 수 없는 로그는 이유와 함께 목록에 남는다.</li>
 *     <li>큰 로그를 통째로 읽지 않는다: 쪽마다 {@link ServerLogQuery#limit()}개까지만 돌려주고 이어 읽을 위치를 준다.
 *     서버가 거르고 자를 수 있는 DB(MySQL, Oracle)는 서버에서, 그렇지 않은 DB는 읽으면서 거른다.</li>
 *     <li>읽기 전용이다. 로그를 돌리거나(rotate) 서버 설정을 바꾸지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 서버 로그 (docs/chromite/swing-admin-tools.md)
 */
public abstract class ServerLogDialect {

    /**
     * Log support of a connected data source.
     *
     * @return null if the database is not supported
     */
    @Nullable
    public static ServerLogDialect forDataSource(@NotNull DBPDataSource dataSource) {
        if (dataSource instanceof SQLServerDataSource sqlServer) {
            return new SQLServerLogDialect(sqlServer);
        }
        if (dataSource instanceof PostgreDataSource postgre && postgre.getServerType() instanceof PostgreServerPostgreSQL) {
            return new PostgreLogDialect(postgre);
        }
        if (dataSource instanceof MySQLDataSource mysql) {
            return new MySQLLogDialect(mysql);
        }
        if (dataSource instanceof OracleDataSource oracle) {
            return new OracleLogDialect(oracle);
        }
        return null;
    }

    /**
     * The logs of the server, with the reason for those the login or the server settings do not allow to read.
     */
    @NotNull
    public abstract List<ServerLogSource> listSources(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session) throws DBException;

    /**
     * Reads one page of matching entries, newest first.
     */
    @NotNull
    public abstract ServerLogPage read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @NotNull ServerLogQuery query)
        throws DBException;

    /**
     * Whether a message contains the search text, ignoring case (for the logs filtered while reading).
     */
    protected static boolean containsText(@NotNull String message, @Nullable String detail, @Nullable String text) {
        if (text == null) {
            return true;
        }
        String needle = text.toLowerCase(Locale.ROOT);
        return message.toLowerCase(Locale.ROOT).contains(needle) || (detail != null && detail.toLowerCase(Locale.ROOT).contains(needle));
    }
}
