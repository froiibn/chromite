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

import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mssql.SQLServerUtils;
import com.seizia.chromite.ext.mysql.MySQLDataSourceProvider;
import com.seizia.chromite.ext.postgresql.PostgreDataSourceProvider;
import com.seizia.chromite.model.DBPDataSourceProvider;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 서버의 데이터베이스 이름 목록. 연결 대화상자의 Database 칸이 저장 전 임시 연결로 읽어 고를 수 있게 보인다.
 *
 * 설계: SSMS "연결 속성 &gt; 데이터베이스에 연결: &lt;서버 찾아보기&gt;"처럼 서버가 가진 데이터베이스를 고르게 한다. 같은 칸이 있는
 * DB 가운데 SQL Server, PostgreSQL, MySQL·MariaDB(같은 공급자)만 대상이다. Oracle의 칸은 서비스 이름(SID)이라 서버에서 읽을 목록이 아니다.
 * 목록에는 로그인이 연결할 수 있는 DB만 둔다.
 * 들어갈 수 없는 DB를 고르면 저장한 연결이 실패하기 때문이다.
 * <ul>
 *     <li>SQL Server: {@code sys.databases}에서 온라인({@code state = 0})이고 로그인이 들어갈 수 있는({@code HAS_DBACCESS}) DB
 *     (보안 화면의 DB 목록과 같은 조건). 드라이버의 카탈로그 목록은 {@code sys.databases} 전체다.</li>
 *     <li>PostgreSQL: {@code pg_database}에서 연결을 허용하고({@code datallowconn}) 로그인에 CONNECT 권한이 있는 DB.
 *     pgjdbc의 카탈로그 목록은 권한을 보지 않는다. 같은 공급자의 변형(Redshift·YugabyteDB 등)이 이 질의를 받지 못하면, 자동 커밋
 *     연결에서는(실패한 질의가 트랜잭션을 깨뜨리지 않음) 카탈로그 목록으로 읽는다.</li>
 *     <li>MySQL·MariaDB(TiDB 등 MySQL 공급자를 잇는 것 포함): JDBC 카탈로그 목록({@code DatabaseMetaData.getCatalogs}).
 *     서버가 로그인이 볼 수 있는 DB만 돌려준다({@code SHOW DATABASES}와 같음).</li>
 * </ul>
 * 읽기 전용이다.
 *
 * 변경 이력:
 * - 2026-11-11 최초 작성: 연결 대화상자의 데이터베이스 목록 (docs/chromite/swing-conn-advanced.md "데이터베이스 목록에서 고르기")
 */
public final class ServerDatabaseNames {

    private static final Log log = Log.getLog(ServerDatabaseNames.class);

    private static final String SQL_SERVER_DATABASES =
        "SELECT name FROM sys.databases WHERE state = 0 AND HAS_DBACCESS(name) = 1 ORDER BY name"; //$NON-NLS-1$
    private static final String POSTGRESQL_DATABASES =
        "SELECT datname FROM pg_catalog.pg_database WHERE datallowconn AND has_database_privilege(datname, 'CONNECT') ORDER BY datname"; //$NON-NLS-1$

    private ServerDatabaseNames() {
    }

    /**
     * The driver's connections can list the server's databases.
     */
    public static boolean isSupported(@NotNull DBPDriver driver) {
        if (SQLServerUtils.isDriverSqlServer(driver)) {
            return true;
        }
        DBPDataSourceProvider provider = driver.getDataSourceProvider();
        return provider instanceof PostgreDataSourceProvider || provider instanceof MySQLDataSourceProvider;
    }

    /**
     * The database names the session's login can use.
     */
    @NotNull
    public static List<String> read(@NotNull DBCSession session) throws DBException {
        if (!(session instanceof JDBCSession jdbcSession)) {
            throw new DBException("Not a JDBC connection"); //$NON-NLS-1$
        }
        DBPDriver driver = session.getDataSource().getContainer().getDriver();
        try {
            if (SQLServerUtils.isDriverSqlServer(driver)) {
                return AdminSql.queryStrings(jdbcSession, SQL_SERVER_DATABASES);
            }
            if (driver.getDataSourceProvider() instanceof PostgreDataSourceProvider) {
                try {
                    return AdminSql.queryStrings(jdbcSession, POSTGRESQL_DATABASES);
                } catch (SQLException e) {
                    if (!jdbcSession.getAutoCommit()) {
                        throw e;
                    }
                    log.debug("Can't read the databases with their privileges, reading the catalogs", e); //$NON-NLS-1$
                }
            }
            return readCatalogs(jdbcSession);
        } catch (SQLException e) {
            throw new DBException("Can't read the database list", e); //$NON-NLS-1$
        }
    }

    @NotNull
    private static List<String> readCatalogs(@NotNull JDBCSession session) throws SQLException {
        List<String> names = new ArrayList<>();
        try (JDBCResultSet resultSet = session.getMetaData().getCatalogs()) {
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
        }
        return names;
    }
}
