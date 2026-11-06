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
package com.seizia.chromite.model.admin.space;

import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.impls.PostgreServerPostgreSQL;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.admin.mssql.SQLServerSpaceDialect;
import com.seizia.chromite.model.admin.mysql.MySQLSpaceDialect;
import com.seizia.chromite.model.admin.oracle.OracleSpaceDialect;
import com.seizia.chromite.model.admin.postgresql.PostgreSpaceDialect;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

/**
 * 공간 사용량(Disk Usage) 화면의 DB별 부분: 데이터베이스 크기, 파일·테이블스페이스, 크기가 큰 테이블을 읽는다.
 *
 * 설계: 보안 편집기({@code SecurityDialect})와 같이 DB마다 하위 클래스 하나를 두고, 매번 시스템 뷰를 직접 조회한다(모델 캐시는 크기를
 * 담지 않는다). DBeaver에는 이 화면이 없다(SWT의 테이블 속성 "Disk space"가 테이블 하나씩만 보인다).
 * <ul>
 *     <li>읽기 전용이다. 서버 설정이나 세션 설정을 바꾸지 않는다.</li>
 *     <li>권한을 먼저 본다: 읽을 수 없는 뷰는 질의하지 않고 표를 "읽을 수 없음"으로 두며, 무엇을 받아야 하는지 안내한다
 *     ({@link SpaceReport#notices()}).</li>
 *     <li>큰 테이블 표는 범위(SQL Server 데이터베이스, PostgreSQL·MySQL 스키마, Oracle 소유자) 하나 또는 전체에서 크기 순으로 위의 몇 개만 읽는다.</li>
 *     <li>Oracle은 Diagnostics/Tuning Pack이 필요한 AWR 뷰({@code DBA_HIST_*})를 쓰지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 공간 사용량 (docs/chromite/swing-admin-tools.md)
 */
public abstract class ServerSpaceDialect {

    /**
     * Space support of a connected data source.
     *
     * @return null if the database is not supported
     */
    @Nullable
    public static ServerSpaceDialect forDataSource(@NotNull DBPDataSource dataSource) {
        if (dataSource instanceof SQLServerDataSource sqlServer) {
            return new SQLServerSpaceDialect(sqlServer);
        }
        // Size functions and pg_tablespace_location (9.2+) of PostgreSQL itself, not of the PostgreSQL based servers
        if (dataSource instanceof PostgreDataSource postgre && postgre.getServerType() instanceof PostgreServerPostgreSQL
            && postgre.isServerVersionAtLeast(9, 2)) {
            return new PostgreSpaceDialect(postgre);
        }
        if (dataSource instanceof MySQLDataSource mysql) {
            return new MySQLSpaceDialect(mysql);
        }
        if (dataSource instanceof OracleDataSource oracle) {
            return new OracleSpaceDialect(oracle);
        }
        return null;
    }

    /**
     * Label of the scope of the table list ("Database", "Schema", "Owner").
     */
    @NotNull
    public abstract String getScopeLabel();

    /**
     * Whether the table list can cover all scopes at once (SQL Server reads one database at a time).
     */
    public boolean supportsAllScopes() {
        return true;
    }

    /**
     * Reads the sizes.
     *
     * @param scope      database, schema or owner of the table list; null for all (or the current database when all is not supported)
     * @param tableLimit how many of the largest tables to read
     */
    @NotNull
    public abstract SpaceReport read(@NotNull DBRProgressMonitor monitor, @NotNull JDBCSession session, @Nullable String scope, int tableLimit)
        throws DBException;
}
