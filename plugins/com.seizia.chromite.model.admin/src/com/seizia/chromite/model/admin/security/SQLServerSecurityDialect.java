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
package com.seizia.chromite.model.admin.security;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.ext.mssql.model.SQLServerLogin;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL Server logins, their database users (User Mapping) and the users' permissions.
 *
 * 설계: DBeaver 모델에는 서버 로그인({@link SQLServerLogin})과 그 관리자({@code SQLServerLoginManager}: 만들기·켜기/끄기·이름 바꾸기·지우기)만
 * 있고, 데이터베이스 사용자·데이터베이스 역할·권한은 모델 클래스도 탐색기 폴더도 없다. 그래서 SSMS 로그인 속성 창처럼 로그인 하나에서
 * 서버 역할, 데이터베이스별 사용자(매핑, 기본 스키마, 데이터베이스 역할), 그 사용자의 권한을 모두 편집한다(탐색기 트리는 바꾸지 않는다:
 * {@code ext.mssql/plugin.xml}은 다른 작업과 겹치고, 새 모델 클래스가 필요하다).
 * <ul>
 *     <li>로그인 문은 {@code SQLServerLoginManager}와 같다: {@code CREATE LOGIN … WITH PASSWORD =}(SQLServerLoginManager.java:59-61),
 *     {@code ALTER LOGIN … ENABLE|DISABLE}(74행), {@code ALTER LOGIN … WITH NAME =}(104행), {@code DROP LOGIN}(82행).
 *     기본 데이터베이스와 암호 정책(CHECK_POLICY, CHECK_EXPIRATION)은 관리자에 없어 SSMS 스크립트와 같은 {@code ALTER LOGIN … WITH}로 쓴다.</li>
 *     <li>데이터베이스 안의 문(사용자, 역할, 권한)은 {@link SecurityStatement#database()}를 붙여 그 데이터베이스에서 실행한다(스크립트에는 {@code USE}).
 *     읽기는 세 부분 이름({@code [db].sys.database_principals})으로 해서 연결의 현재 데이터베이스를 바꾸지 않는다.</li>
 *     <li>식별자는 SSMS 스크립트처럼 늘 대괄호로 감싼다.</li>
 * </ul>
 * 권한 종류 목록은 모델에 없어 SSMS Securables 페이지가 보여 주는 객체 종류별 목록(Microsoft 문서 "Permissions (Database Engine)")을 쓴다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 */
final class SQLServerSecurityDialect extends SecurityDialect {

    static final String OPTION_CHECK_POLICY = "CHECK_POLICY"; //$NON-NLS-1$
    static final String OPTION_CHECK_EXPIRATION = "CHECK_EXPIRATION"; //$NON-NLS-1$

    private static final String TYPE_SQL_LOGIN = "S"; //$NON-NLS-1$
    private static final String TYPE_SERVER_ROLE = "R"; //$NON-NLS-1$

    private static final Map<Securable.Kind, List<String>> PRIVILEGES = new EnumMap<>(Securable.Kind.class);

    static {
        PRIVILEGES.put(Securable.Kind.DATABASE, List.of("CONNECT", "SELECT", "INSERT", "UPDATE", "DELETE", "EXECUTE", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "CREATE TABLE", "CREATE VIEW", "CREATE PROCEDURE", "CREATE FUNCTION", "CREATE SCHEMA", "ALTER", "CONTROL", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "VIEW DEFINITION", "SHOWPLAN", "BACKUP DATABASE")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        PRIVILEGES.put(Securable.Kind.SCHEMA, List.of("SELECT", "INSERT", "UPDATE", "DELETE", "EXECUTE", "REFERENCES", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "ALTER", "CONTROL", "TAKE OWNERSHIP", "VIEW DEFINITION", "CREATE SEQUENCE")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        PRIVILEGES.put(Securable.Kind.TABLE, List.of("SELECT", "INSERT", "UPDATE", "DELETE", "REFERENCES", "ALTER", "CONTROL", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "TAKE OWNERSHIP", "VIEW DEFINITION", "VIEW CHANGE TRACKING")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        PRIVILEGES.put(Securable.Kind.VIEW, List.of("SELECT", "INSERT", "UPDATE", "DELETE", "REFERENCES", "ALTER", "CONTROL", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "TAKE OWNERSHIP", "VIEW DEFINITION")); //$NON-NLS-1$ //$NON-NLS-2$
        PRIVILEGES.put(Securable.Kind.SEQUENCE, List.of("UPDATE", "REFERENCES", "ALTER", "CONTROL", "TAKE OWNERSHIP", "VIEW DEFINITION")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        PRIVILEGES.put(Securable.Kind.FUNCTION, List.of("EXECUTE", "REFERENCES", "ALTER", "CONTROL", "TAKE OWNERSHIP", "VIEW DEFINITION")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        PRIVILEGES.put(Securable.Kind.PROCEDURE, List.of("EXECUTE", "ALTER", "CONTROL", "TAKE OWNERSHIP", "VIEW DEFINITION")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    SQLServerSecurityDialect(@NotNull DBSObject context, @NotNull SQLServerDataSource dataSource) {
        super(context);
    }

    @Override
    public boolean isPrincipal(@NotNull DBSObject object) {
        return object instanceof SQLServerLogin;
    }

    @Nullable
    @Override
    public SecurityPrincipal.Kind getNewPrincipalKind(@NotNull Class<?> folderChildrenClass) {
        return SQLServerLogin.class.isAssignableFrom(folderChildrenClass) ? SecurityPrincipal.Kind.LOGIN : null;
    }

    @NotNull
    @Override
    public SecurityPrincipal loadPrincipal(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object) throws DBException {
        String[] type = new String[1];
        SecurityPrincipal[] result = new SecurityPrincipal[1];
        query(monitor, """
                SELECT p.type, p.is_disabled, p.default_database_name, l.is_policy_checked, l.is_expiration_checked
                FROM sys.server_principals p LEFT JOIN sys.sql_logins l ON l.principal_id = p.principal_id
                WHERE p.name = ?""", //$NON-NLS-1$
            resultSet -> {
                type[0] = resultSet.getString(1);
                SecurityPrincipal principal = new SecurityPrincipal(
                    TYPE_SERVER_ROLE.equals(type[0]) ? SecurityPrincipal.Kind.ROLE : SecurityPrincipal.Kind.LOGIN, false, object, object.getName());
                if (principal.getKind() == SecurityPrincipal.Kind.LOGIN) {
                    principal.setEnabled(!resultSet.getBoolean(2));
                    principal.setDefaultDatabase(CommonUtils.notEmpty(resultSet.getString(3)));
                }
                if (TYPE_SQL_LOGIN.equals(type[0])) {
                    principal.getOptions().put(OPTION_CHECK_POLICY, resultSet.getBoolean(4));
                    principal.getOptions().put(OPTION_CHECK_EXPIRATION, resultSet.getBoolean(5));
                }
                result[0] = principal;
            }, object.getName());
        SecurityPrincipal principal = result[0];
        if (principal == null) {
            throw new DBException("Login " + object.getName() + " does not exist"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String role : listServerRoles(monitor, object.getName())) {
            principal.getMemberships().put(role, false);
        }
        query(monitor, """
                SELECT r.name FROM sys.server_role_members m
                JOIN sys.server_principals r ON r.principal_id = m.role_principal_id
                JOIN sys.server_principals u ON u.principal_id = m.member_principal_id
                WHERE u.name = ?""", //$NON-NLS-1$
            resultSet -> principal.getMemberships().put(resultSet.getString(1), true), object.getName());
        if (principal.getKind() == SecurityPrincipal.Kind.LOGIN) {
            for (String database : listAccessibleDatabases(monitor)) {
                SecurityPrincipal.DatabaseMapping mapping = readMapping(monitor, database, object.getName());
                principal.getMappings().put(database, mapping);
                if (mapping.isMapped()) {
                    readUserPermissions(monitor, database, object.getName(), principal.getPermissions());
                }
            }
        }
        return principal;
    }

    @NotNull
    @Override
    public SecurityPrincipal newPrincipal(@NotNull DBRProgressMonitor monitor, @NotNull SecurityPrincipal.Kind kind) throws DBException {
        SecurityPrincipal principal = new SecurityPrincipal(SecurityPrincipal.Kind.LOGIN, true, null, ""); //$NON-NLS-1$
        principal.setEnabled(true);
        principal.setDefaultDatabase("master"); //$NON-NLS-1$
        // SSMS New Login defaults: enforce password policy on, expiration off
        principal.getOptions().put(OPTION_CHECK_POLICY, true);
        principal.getOptions().put(OPTION_CHECK_EXPIRATION, false);
        for (String role : listServerRoles(monitor, null)) {
            principal.getMemberships().put(role, false);
        }
        for (String database : listAccessibleDatabases(monitor)) {
            principal.getMappings().put(database, readMapping(monitor, database, null));
        }
        return principal;
    }

    @Override
    public boolean supportsPassword(@NotNull SecurityPrincipal principal) {
        // Windows logins and groups have no password; their options are empty
        return principal.isNew() || principal.getOptions().containsKey(OPTION_CHECK_POLICY);
    }

    @Nullable
    @Override
    public EnabledStyle getEnabledStyle(@NotNull SecurityPrincipal principal) {
        return principal.getKind() == SecurityPrincipal.Kind.LOGIN ? EnabledStyle.ENABLED : null;
    }

    @NotNull
    @Override
    public MembershipStyle getMembershipStyle() {
        return MembershipStyle.SERVER_ROLES;
    }

    @Override
    public boolean supportsDeny() {
        return true;
    }

    @Override
    public boolean supportsUserMapping() {
        return true;
    }

    @Override
    public boolean supportsPermissions(@NotNull SecurityPrincipal principal) {
        // Object permissions belong to database users; a server role has none
        return principal.getKind() == SecurityPrincipal.Kind.LOGIN;
    }

    @NotNull
    @Override
    public List<String> listDatabases(@NotNull DBRProgressMonitor monitor) throws DBException {
        return queryStrings(monitor, "SELECT name FROM sys.databases ORDER BY name"); //$NON-NLS-1$
    }

    @NotNull
    @Override
    public List<String> listSchemas(@NotNull DBRProgressMonitor monitor, @NotNull String database) throws DBException {
        return queryStrings(monitor, "SELECT name FROM " + quote(database) + ".sys.schemas ORDER BY name"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    @Override
    public List<String> getPrivileges(@NotNull Securable.Kind kind) {
        return PRIVILEGES.getOrDefault(kind, List.of());
    }

    @NotNull
    @Override
    public List<Securable> listSecurables(@NotNull DBRProgressMonitor monitor, @Nullable String database) throws DBException {
        List<Securable> securables = new ArrayList<>();
        if (database == null) {
            return securables;
        }
        String db = quote(database);
        securables.add(new Securable(Securable.Kind.DATABASE, database, null, database, null));
        query(monitor, "SELECT name FROM " + db + ".sys.schemas WHERE schema_id < 16384 AND name NOT IN ('sys', 'INFORMATION_SCHEMA', 'guest')", //$NON-NLS-1$ //$NON-NLS-2$
            resultSet -> securables.add(new Securable(Securable.Kind.SCHEMA, database, null, resultSet.getString(1), null)));
        query(monitor, "SELECT s.name, o.name, o.type FROM " + db + ".sys.objects o JOIN " + db + ".sys.schemas s ON s.schema_id = o.schema_id " + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "WHERE o.is_ms_shipped = 0 AND o.type IN ('U', 'V', 'P', 'FN', 'SO')", //$NON-NLS-1$
            resultSet -> {
                Securable.Kind kind = getObjectKind(resultSet.getString(3));
                if (kind != null) {
                    securables.add(new Securable(kind, database, resultSet.getString(1), resultSet.getString(2), null));
                }
            });
        securables.sort(null);
        return securables;
    }

    @NotNull
    @Override
    public PermissionGrid<String> loadObjectPermissions(@NotNull DBRProgressMonitor monitor, @NotNull Securable securable) throws DBException {
        PermissionGrid<String> grid = new PermissionGrid<>();
        String db = quote(CommonUtils.notEmpty(securable.catalog()));
        query(monitor, "SELECT dp.name, pe.permission_name, pe.state FROM " + db + ".sys.database_permissions pe " + //$NON-NLS-1$ //$NON-NLS-2$
                "JOIN " + db + ".sys.database_principals dp ON dp.principal_id = pe.grantee_principal_id " + //$NON-NLS-1$ //$NON-NLS-2$
                "WHERE pe.class = 1 AND pe.minor_id = 0 AND pe.major_id = OBJECT_ID(?)", //$NON-NLS-1$
            resultSet -> grid.set(resultSet.getString(1), resultSet.getString(2), readState(resultSet.getString(3))),
            db + "." + formatTarget(securable)); //$NON-NLS-1$
        return grid;
    }

    @NotNull
    @Override
    public List<String> listGrantees(@NotNull DBRProgressMonitor monitor, @NotNull Securable securable) throws DBException {
        // Fixed database roles, dbo and the system users cannot be grantees of GRANT
        return queryStrings(monitor, "SELECT name FROM " + quote(CommonUtils.notEmpty(securable.catalog())) + ".sys.database_principals " + //$NON-NLS-1$ //$NON-NLS-2$
            "WHERE type IN ('S', 'U', 'G', 'R', 'E', 'X', 'C', 'K') AND is_fixed_role = 0 AND name NOT IN ('dbo', 'sys', 'INFORMATION_SCHEMA') ORDER BY name"); //$NON-NLS-1$
    }

    @NotNull
    @Override
    public List<SecurityStatement> generateScript(@NotNull SecurityPrincipal original, @NotNull SecurityPrincipal edited) {
        List<SecurityStatement> statements = new ArrayList<>();
        String name = quote(edited.getName());
        boolean role = edited.getKind() == SecurityPrincipal.Kind.ROLE;
        if (edited.isNew()) {
            String password = CommonUtils.notEmpty(edited.getPassword());
            String options = ", DEFAULT_DATABASE = " + quote(CommonUtils.notEmpty(edited.getDefaultDatabase())) + //$NON-NLS-1$
                ", CHECK_EXPIRATION = " + onOff(edited, OPTION_CHECK_EXPIRATION) + ", CHECK_POLICY = " + onOff(edited, OPTION_CHECK_POLICY); //$NON-NLS-1$ //$NON-NLS-2$
            statements.add(new SecurityStatement(
                "CREATE LOGIN " + name + " WITH PASSWORD = " + quoteString(password) + options, //$NON-NLS-1$ //$NON-NLS-2$
                "CREATE LOGIN " + name + " WITH PASSWORD = " + MASKED_PASSWORD + options, //$NON-NLS-1$ //$NON-NLS-2$
                null, true));
            if (Boolean.FALSE.equals(edited.getEnabled())) {
                statements.add(SecurityStatement.of("ALTER LOGIN " + name + " DISABLE")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        } else {
            String keyword = role ? "ALTER SERVER ROLE " : "ALTER LOGIN "; //$NON-NLS-1$ //$NON-NLS-2$
            if (!original.getName().equals(edited.getName())) {
                statements.add(SecurityStatement.of(keyword + quote(original.getName()) + " WITH NAME = " + name)); //$NON-NLS-1$
            }
            if (!CommonUtils.isEmpty(edited.getPassword())) {
                statements.add(new SecurityStatement(
                    "ALTER LOGIN " + name + " WITH PASSWORD = " + quoteString(edited.getPassword()), //$NON-NLS-1$ //$NON-NLS-2$
                    "ALTER LOGIN " + name + " WITH PASSWORD = " + MASKED_PASSWORD, //$NON-NLS-1$ //$NON-NLS-2$
                    null, true));
            }
            if (!CommonUtils.equalObjects(original.getDefaultDatabase(), edited.getDefaultDatabase()) && !CommonUtils.isEmpty(edited.getDefaultDatabase())) {
                statements.add(SecurityStatement.of("ALTER LOGIN " + name + " WITH DEFAULT_DATABASE = " + quote(edited.getDefaultDatabase()))); //$NON-NLS-1$ //$NON-NLS-2$
            }
            // CHECK_EXPIRATION needs CHECK_POLICY: switch expiration off first and on last
            boolean expirationChanged = isChanged(original, edited, OPTION_CHECK_EXPIRATION);
            boolean expirationOn = isOn(edited, OPTION_CHECK_EXPIRATION);
            if (expirationChanged && !expirationOn) {
                statements.add(SecurityStatement.of("ALTER LOGIN " + name + " WITH CHECK_EXPIRATION = OFF")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (isChanged(original, edited, OPTION_CHECK_POLICY)) {
                statements.add(SecurityStatement.of("ALTER LOGIN " + name + " WITH CHECK_POLICY = " + onOff(edited, OPTION_CHECK_POLICY))); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (expirationChanged && expirationOn) {
                statements.add(SecurityStatement.of("ALTER LOGIN " + name + " WITH CHECK_EXPIRATION = ON")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!CommonUtils.equalObjects(original.getEnabled(), edited.getEnabled()) && edited.getEnabled() != null) {
                statements.add(SecurityStatement.of("ALTER LOGIN " + name + (edited.getEnabled() ? " ENABLE" : " DISABLE"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        diffFlags(original.getMemberships(), edited.getMemberships(), added, removed);
        for (String serverRole : removed) {
            statements.add(SecurityStatement.of("ALTER SERVER ROLE " + quote(serverRole) + " DROP MEMBER " + name)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String serverRole : added) {
            statements.add(SecurityStatement.of("ALTER SERVER ROLE " + quote(serverRole) + " ADD MEMBER " + name)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (Map.Entry<String, SecurityPrincipal.DatabaseMapping> entry : edited.getMappings().entrySet()) {
            String database = entry.getKey();
            SecurityPrincipal.DatabaseMapping before = original.getMappings().get(database);
            statements.addAll(generateMappingScript(database, name, before, entry.getValue(), original.getPermissions(), edited.getPermissions()));
        }
        return statements;
    }

    @NotNull
    private List<SecurityStatement> generateMappingScript(
        @NotNull String database,
        @NotNull String login,
        @Nullable SecurityPrincipal.DatabaseMapping before,
        @NotNull SecurityPrincipal.DatabaseMapping after,
        @NotNull PermissionGrid<Securable> permissionsBefore,
        @NotNull PermissionGrid<Securable> permissionsAfter
    ) {
        List<SecurityStatement> statements = new ArrayList<>();
        boolean wasMapped = before != null && before.isMapped();
        if (!after.isMapped()) {
            if (wasMapped) {
                statements.add(SecurityStatement.inDatabase(database, "DROP USER " + quote(CommonUtils.notEmpty(before.getUserName())))); //$NON-NLS-1$
            }
            return statements;
        }
        String user = quote(CommonUtils.notEmpty(after.getUserName()));
        String schema = after.getDefaultSchema();
        if (!wasMapped) {
            statements.add(SecurityStatement.inDatabase(database, "CREATE USER " + user + " FOR LOGIN " + login + //$NON-NLS-1$ //$NON-NLS-2$
                (CommonUtils.isEmpty(schema) ? "" : " WITH DEFAULT_SCHEMA = " + quote(schema)))); //$NON-NLS-1$ //$NON-NLS-2$
        } else {
            if (!CommonUtils.equalObjects(before.getUserName(), after.getUserName())) {
                statements.add(SecurityStatement.inDatabase(database, "ALTER USER " + quote(CommonUtils.notEmpty(before.getUserName())) + " WITH NAME = " + user)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!CommonUtils.equalObjects(CommonUtils.notEmpty(before.getDefaultSchema()), CommonUtils.notEmpty(schema)) && !CommonUtils.isEmpty(schema)) {
                statements.add(SecurityStatement.inDatabase(database, "ALTER USER " + user + " WITH DEFAULT_SCHEMA = " + quote(schema))); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        diffFlags(wasMapped ? before.getRoles() : Map.of(), after.getRoles(), added, removed);
        for (String role : removed) {
            statements.add(SecurityStatement.inDatabase(database, "ALTER ROLE " + quote(role) + " DROP MEMBER " + user)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String role : added) {
            statements.add(SecurityStatement.inDatabase(database, "ALTER ROLE " + quote(role) + " ADD MEMBER " + user)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Set<Securable> securables = new LinkedHashSet<>();
        for (Securable securable : permissionsBefore.getRows()) {
            if (database.equals(securable.catalog()) && wasMapped) {
                securables.add(securable);
            }
        }
        for (Securable securable : permissionsAfter.getRows()) {
            if (database.equals(securable.catalog())) {
                securables.add(securable);
            }
        }
        for (Securable securable : securables) {
            statements.addAll(diffPermissions(securable, user,
                wasMapped ? permissionsBefore.getStates(securable) : Map.of(), permissionsAfter.getStates(securable), database));
        }
        return statements;
    }

    @NotNull
    @Override
    public List<SecurityStatement> generateDropScript(@NotNull SecurityPrincipal principal) {
        String keyword = principal.getKind() == SecurityPrincipal.Kind.ROLE ? "DROP SERVER ROLE " : "DROP LOGIN "; //$NON-NLS-1$ //$NON-NLS-2$
        return List.of(SecurityStatement.of(keyword + quote(principal.getName())));
    }

    @NotNull
    @Override
    protected String quote(@NotNull String identifier) {
        return "[" + identifier.replace("]", "]]") + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @NotNull
    @Override
    protected String formatUse(@NotNull String database) {
        return "USE " + quote(database); //$NON-NLS-1$
    }

    @Nullable
    @Override
    protected String getStatementDatabase(@NotNull Securable securable) {
        return securable.catalog();
    }

    @NotNull
    @Override
    protected String formatGrantee(@NotNull String grantee) {
        return quote(grantee);
    }

    @NotNull
    @Override
    protected String formatTarget(@NotNull Securable securable) {
        return switch (securable.kind()) {
            case DATABASE -> ""; //$NON-NLS-1$
            case SCHEMA -> "SCHEMA::" + quote(securable.name()); //$NON-NLS-1$
            default -> quote(CommonUtils.notEmpty(securable.schema())) + "." + quote(securable.name()); //$NON-NLS-1$
        };
    }

    @NotNull
    @Override
    protected String denySql(@NotNull String target, @NotNull List<String> privileges, @NotNull String grantee, boolean cascade) {
        return "DENY " + String.join(", ", privileges) + onClause(target) + " TO " + grantee + (cascade ? " CASCADE" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @NotNull
    private List<String> listServerRoles(@NotNull DBRProgressMonitor monitor, @Nullable String exclude) throws DBException {
        // public holds every login and cannot be changed
        List<String> roles = queryStrings(monitor, "SELECT name FROM sys.server_principals WHERE type = 'R' AND name <> 'public' ORDER BY name"); //$NON-NLS-1$
        roles.remove(exclude);
        return roles;
    }

    @NotNull
    private List<String> listAccessibleDatabases(@NotNull DBRProgressMonitor monitor) throws DBException {
        return queryStrings(monitor, "SELECT name FROM sys.databases WHERE state = 0 AND HAS_DBACCESS(name) = 1 ORDER BY name"); //$NON-NLS-1$
    }

    /**
     * The user of the login in a database and its database roles (an unmapped login gets the role list only).
     */
    @NotNull
    private SecurityPrincipal.DatabaseMapping readMapping(@NotNull DBRProgressMonitor monitor, @NotNull String database, @Nullable String login) throws DBException {
        SecurityPrincipal.DatabaseMapping mapping = new SecurityPrincipal.DatabaseMapping();
        String db = quote(database);
        if (login != null) {
            query(monitor, "SELECT name, default_schema_name FROM " + db + ".sys.database_principals WHERE sid = SUSER_SID(?)", //$NON-NLS-1$ //$NON-NLS-2$
                resultSet -> {
                    mapping.setMapped(true);
                    mapping.setUserName(resultSet.getString(1));
                    mapping.setDefaultSchema(CommonUtils.notEmpty(resultSet.getString(2)));
                }, login);
        }
        if (login == null) {
            // SUSER_SID('') is the SID of the current login: a new login must not inherit its role memberships
            for (String role : queryStrings(monitor, "SELECT name FROM " + db + ".sys.database_principals WHERE type = 'R' AND name <> 'public' ORDER BY name")) { //$NON-NLS-1$ //$NON-NLS-2$
                mapping.getRoles().put(role, false);
            }
            return mapping;
        }
        query(monitor, "SELECT r.name, CASE WHEN EXISTS (SELECT 1 FROM " + db + ".sys.database_role_members m " + //$NON-NLS-1$ //$NON-NLS-2$
                "JOIN " + db + ".sys.database_principals u ON u.principal_id = m.member_principal_id " + //$NON-NLS-1$ //$NON-NLS-2$
                "WHERE m.role_principal_id = r.principal_id AND u.sid = SUSER_SID(?)) THEN 1 ELSE 0 END " + //$NON-NLS-1$
                "FROM " + db + ".sys.database_principals r WHERE r.type = 'R' AND r.name <> 'public' ORDER BY r.name", //$NON-NLS-1$ //$NON-NLS-2$
            resultSet -> mapping.getRoles().put(resultSet.getString(1), resultSet.getInt(2) == 1), login);
        return mapping;
    }

    private void readUserPermissions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String database,
        @NotNull String login,
        @NotNull PermissionGrid<Securable> permissions
    ) throws DBException {
        String db = quote(database);
        query(monitor, "SELECT pe.class, pe.permission_name, pe.state, s.name, o.name, o.type, sc.name FROM " + db + ".sys.database_permissions pe " + //$NON-NLS-1$ //$NON-NLS-2$
                "JOIN " + db + ".sys.database_principals dp ON dp.principal_id = pe.grantee_principal_id " + //$NON-NLS-1$ //$NON-NLS-2$
                "LEFT JOIN " + db + ".sys.objects o ON pe.class = 1 AND o.object_id = pe.major_id " + //$NON-NLS-1$ //$NON-NLS-2$
                "LEFT JOIN " + db + ".sys.schemas s ON s.schema_id = o.schema_id " + //$NON-NLS-1$ //$NON-NLS-2$
                "LEFT JOIN " + db + ".sys.schemas sc ON pe.class = 3 AND sc.schema_id = pe.major_id " + //$NON-NLS-1$ //$NON-NLS-2$
                "WHERE dp.sid = SUSER_SID(?) AND pe.minor_id = 0 AND pe.class IN (0, 1, 3)", //$NON-NLS-1$
            resultSet -> {
                Securable securable = readSecurable(resultSet, database);
                if (securable != null) {
                    permissions.set(securable, resultSet.getString(2), readState(resultSet.getString(3)));
                }
            }, login);
    }

    @Nullable
    private static Securable readSecurable(@NotNull JDBCResultSet resultSet, @NotNull String database) throws SQLException {
        return switch (resultSet.getInt(1)) {
            case 0 -> new Securable(Securable.Kind.DATABASE, database, null, database, null);
            case 3 -> new Securable(Securable.Kind.SCHEMA, database, null, resultSet.getString(7), null);
            default -> {
                Securable.Kind kind = getObjectKind(resultSet.getString(6));
                yield kind == null ? null : new Securable(kind, database, resultSet.getString(4), resultSet.getString(5), null);
            }
        };
    }

    @Nullable
    private static Securable.Kind getObjectKind(@Nullable String type) {
        return switch (CommonUtils.notEmpty(type).trim()) {
            case "U" -> Securable.Kind.TABLE; //$NON-NLS-1$
            case "V" -> Securable.Kind.VIEW; //$NON-NLS-1$
            case "P", "PC" -> Securable.Kind.PROCEDURE; //$NON-NLS-1$ //$NON-NLS-2$
            case "FN", "FS" -> Securable.Kind.FUNCTION; //$NON-NLS-1$ //$NON-NLS-2$
            case "SO" -> Securable.Kind.SEQUENCE; //$NON-NLS-1$
            default -> null;
        };
    }

    @NotNull
    private static PermissionGrid.State readState(@Nullable String state) {
        return switch (CommonUtils.notEmpty(state)) {
            case "W" -> PermissionGrid.State.GRANT_WITH; //$NON-NLS-1$
            case "D" -> PermissionGrid.State.DENY; //$NON-NLS-1$
            default -> PermissionGrid.State.GRANT;
        };
    }

    private static boolean isOn(@NotNull SecurityPrincipal principal, @NotNull String option) {
        return Boolean.TRUE.equals(principal.getOptions().get(option));
    }

    private static boolean isChanged(@NotNull SecurityPrincipal original, @NotNull SecurityPrincipal edited, @NotNull String option) {
        return edited.getOptions().containsKey(option) && isOn(original, option) != isOn(edited, option);
    }

    @NotNull
    private static String onOff(@NotNull SecurityPrincipal principal, @NotNull String option) {
        return isOn(principal, option) ? "ON" : "OFF"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
