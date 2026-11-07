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

import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleProcedurePackaged;
import com.seizia.chromite.ext.oracle.model.OracleRole;
import com.seizia.chromite.ext.oracle.model.OracleUser;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Oracle users and roles, their roles, system privileges and object privileges.
 *
 * 설계: 모델에는 사용자·역할을 읽는 {@code OracleUser}·{@code OracleRole}·{@code OraclePrivSystem}·{@code OraclePrivTable}만 있고 만들기·바꾸기
 * 관리자가 없다(ext.oracle {@code plugin.xml}의 objectManager에 사용자·역할이 없음). 사용자 만들기·지우기는 스키마 관리자
 * {@code OracleSchemaManager}(스키마 노드에서 {@code CREATE USER … IDENTIFIED BY}, {@code DROP USER … CASCADE})가 맡는다. SWT의 권한 탭도
 * 범용 편집기의 읽기 표였다. 그래서 다른 DB처럼 {@link SecurityDialect}의 "읽은 상태 → 고친 상태" 차이로 문을 만든다(Swing 보안 편집기 #49를
 * 그대로 쓴다). 문 모양은 Oracle SQL 참조의 CREATE/ALTER/DROP USER, CREATE/DROP ROLE, GRANT/REVOKE를 따른다.
 * <ul>
 *     <li>사용자: 암호({@code IDENTIFIED BY "…"}, 미리 보기는 가림, 비우면 새 사용자는 {@code NO AUTHENTICATION}), 계정 잠금
 *     ({@code ACCOUNT LOCK / UNLOCK}, 화면의 "계정 잠김"). 이름 바꾸기는 Oracle에 없어 막는다.</li>
 *     <li>역할 소속: {@code GRANT <role> TO …} / {@code REVOKE <role> FROM …}. 목록은 {@code DBA_ROLES} 전부(CONNECT, RESOURCE 같은 Oracle 역할 포함).</li>
 *     <li>시스템 권한: 보안 개체 목록의 데이터베이스 행(이름 = 현재 컨테이너, {@code SYS_CONTEXT('USERENV', 'CON_NAME')})이다. SQL Server의
 *     데이터베이스 권한처럼 {@code ON} 없는 GRANT이고, "권한 부여"는 {@code WITH ADMIN OPTION}이다. 종류는 {@code SYSTEM_PRIVILEGE_MAP}.</li>
 *     <li>객체 권한: 테이블·뷰·시퀀스·독립 함수·프로시저({@code DBA_TAB_PRIVS}). "권한 부여"는 {@code WITH GRANT OPTION}이다. Oracle에는
 *     {@code REVOKE GRANT OPTION FOR}가 없어 권한 부여만 끄면 {@code REVOKE} 뒤 {@code GRANT}로 다시 준다. 객체 권한 REVOKE는 Oracle이
 *     다시 준 권한까지 연쇄로 거두므로 {@code CASCADE}를 붙이지 않는다(문법에 없음).</li>
 *     <li>지우기: {@code DROP USER}(CASCADE 없음 — 모델의 스키마 관리자와 달리 객체가 있으면 서버가 거부하고 오류를 보인다. 객체까지 지우려면
 *     스크립트를 쿼리 창으로 열어 CASCADE를 붙인다), {@code DROP ROLE}.</li>
 *     <li>새 이름: Oracle 규칙처럼 따옴표 없는 식별자로 쓸 수 있는 이름은 대문자로 바꾼다({@code chr_user} → {@code CHR_USER}). 그 밖의 이름은
 *     따옴표로 감싸 그대로 쓴다.</li>
 *     <li>읽기는 DBA 뷰({@code DBA_USERS}, {@code DBA_ROLES}, {@code DBA_ROLE_PRIVS}, {@code DBA_SYS_PRIVS}, {@code DBA_TAB_PRIVS},
 *     {@code DBA_OBJECTS})다. 사용자 관리는 DBA 작업이고, 못 읽으면 서버 오류가 그대로 보인다. ORACLE LICENSE GUARD: 모두 팩이 필요 없는 뷰다.</li>
 *     <li>목록에서 Oracle이 관리하는 사용자·역할({@code ORACLE_MAINTAINED = 'Y'}, 12c+)은 권한 받을 대상과 객체 목록에서 뺀다. 역할 소속 목록에는 둔다.</li>
 * </ul>
 * 패키지·형식·디렉터리 같은 그 밖의 객체 권한과 열 단위 권한({@code DBA_COL_PRIVS}), 23ai의 스키마 권한은 다루지 않는다(표에 나오지 않고 바꾸지도 않는다).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: Oracle 사용자·역할·권한 (docs/chromite/swing-security.md "Oracle")
 */
final class OracleSecurityDialect extends SecurityDialect {

    /**
     * A name Oracle keeps in upper case when it is written without quotes.
     */
    private static final Pattern UNQUOTED_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]*"); //$NON-NLS-1$
    private static final String STATE_YES = "YES"; //$NON-NLS-1$

    private static final Map<Securable.Kind, List<String>> OBJECT_PRIVILEGES = new EnumMap<>(Securable.Kind.class);

    static {
        // Oracle SQL Language Reference, GRANT, "Object Privileges"
        OBJECT_PRIVILEGES.put(Securable.Kind.TABLE, List.of("SELECT", "READ", "INSERT", "UPDATE", "DELETE", "ALTER", "INDEX", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "REFERENCES", "DEBUG", "FLASHBACK")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        OBJECT_PRIVILEGES.put(Securable.Kind.VIEW, List.of("SELECT", "READ", "INSERT", "UPDATE", "DELETE", "REFERENCES", "DEBUG", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "FLASHBACK", "MERGE VIEW")); //$NON-NLS-1$ //$NON-NLS-2$
        OBJECT_PRIVILEGES.put(Securable.Kind.SEQUENCE, List.of("SELECT", "ALTER")); //$NON-NLS-1$ //$NON-NLS-2$
        OBJECT_PRIVILEGES.put(Securable.Kind.FUNCTION, List.of("EXECUTE", "DEBUG")); //$NON-NLS-1$ //$NON-NLS-2$
        OBJECT_PRIVILEGES.put(Securable.Kind.PROCEDURE, List.of("EXECUTE", "DEBUG")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    private final OracleDataSource dataSource;
    /**
     * System privilege names ({@code SYSTEM_PRIVILEGE_MAP}), read with the principal (the privilege list is asked in the EDT).
     */
    @NotNull
    private List<String> systemPrivileges = List.of();
    /**
     * The securable that system privileges are granted on: the current container (PDB) or database.
     */
    @Nullable
    private Securable database;

    OracleSecurityDialect(@NotNull DBSObject context, @NotNull OracleDataSource dataSource) {
        super(context);
        this.dataSource = dataSource;
    }

    @Override
    public boolean isPrincipal(@NotNull DBSObject object) {
        return object instanceof OracleUser || object instanceof OracleRole;
    }

    @Nullable
    @Override
    public SecurityPrincipal.Kind getNewPrincipalKind(@NotNull Class<?> folderChildrenClass) {
        if (OracleUser.class.isAssignableFrom(folderChildrenClass)) {
            return SecurityPrincipal.Kind.USER;
        }
        return OracleRole.class.isAssignableFrom(folderChildrenClass) ? SecurityPrincipal.Kind.ROLE : null;
    }

    @NotNull
    @Override
    public SecurityPrincipal loadPrincipal(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object) throws DBException {
        boolean user = object instanceof OracleUser;
        String name = object.getName();
        SecurityPrincipal principal = new SecurityPrincipal(user ? SecurityPrincipal.Kind.USER : SecurityPrincipal.Kind.ROLE, false, object, name);
        boolean[] found = new boolean[1];
        if (user) {
            query(monitor, "SELECT ACCOUNT_STATUS FROM DBA_USERS WHERE USERNAME = ?", resultSet -> { //$NON-NLS-1$
                found[0] = true;
                // OPEN, LOCKED, EXPIRED & LOCKED, LOCKED(TIMED), ...
                principal.setEnabled(!CommonUtils.notEmpty(resultSet.getString(1)).contains("LOCKED")); //$NON-NLS-1$
            }, name);
        } else {
            query(monitor, "SELECT ROLE FROM DBA_ROLES WHERE ROLE = ?", resultSet -> found[0] = true, name); //$NON-NLS-1$
        }
        if (!found[0]) {
            throw new DBException((user ? "User " : "Role ") + name + " does not exist"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        readLists(monitor);
        for (String role : listRoles(monitor, name)) {
            principal.getMemberships().put(role, false);
        }
        query(monitor, "SELECT GRANTED_ROLE FROM DBA_ROLE_PRIVS WHERE GRANTEE = ?", //$NON-NLS-1$
            resultSet -> principal.getMemberships().put(resultSet.getString(1), true), name);
        PermissionGrid<Securable> permissions = principal.getPermissions();
        Securable databaseRow = getDatabase();
        permissions.addRow(databaseRow);
        query(monitor, "SELECT PRIVILEGE, ADMIN_OPTION FROM DBA_SYS_PRIVS WHERE GRANTEE = ?", //$NON-NLS-1$
            resultSet -> permissions.set(databaseRow, resultSet.getString(1), readState(resultSet.getString(2))), name);
        query(monitor, """
                SELECT p.OWNER, p.TABLE_NAME, p.PRIVILEGE, p.GRANTABLE,
                    (SELECT MIN(o.OBJECT_TYPE) FROM DBA_OBJECTS o WHERE o.OWNER = p.OWNER AND o.OBJECT_NAME = p.TABLE_NAME
                        AND o.OBJECT_TYPE IN ('TABLE', 'VIEW', 'SEQUENCE', 'FUNCTION', 'PROCEDURE')) AS OBJECT_TYPE
                FROM DBA_TAB_PRIVS p WHERE p.GRANTEE = ?""", //$NON-NLS-1$
            resultSet -> {
                Securable.Kind kind = toKind(resultSet.getString("OBJECT_TYPE")); //$NON-NLS-1$
                if (kind != null) {
                    permissions.set(new Securable(kind, null, resultSet.getString("OWNER"), resultSet.getString("TABLE_NAME"), null), //$NON-NLS-1$ //$NON-NLS-2$
                        resultSet.getString("PRIVILEGE"), readState(resultSet.getString("GRANTABLE"))); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }, name);
        return principal;
    }

    @NotNull
    @Override
    public SecurityPrincipal newPrincipal(@NotNull DBRProgressMonitor monitor, @NotNull SecurityPrincipal.Kind kind) throws DBException {
        SecurityPrincipal principal = new SecurityPrincipal(kind, true, null, ""); //$NON-NLS-1$
        if (kind == SecurityPrincipal.Kind.USER) {
            // CREATE USER makes an open (unlocked) account
            principal.setEnabled(true);
        }
        readLists(monitor);
        for (String role : listRoles(monitor, null)) {
            principal.getMemberships().put(role, false);
        }
        // The system privileges row is there from the start (CREATE SESSION is what a new user needs first)
        principal.getPermissions().addRow(getDatabase());
        return principal;
    }

    @Override
    public boolean supportsPassword(@NotNull SecurityPrincipal principal) {
        return principal.getKind() == SecurityPrincipal.Kind.USER;
    }

    @Override
    public boolean supportsRename(@NotNull SecurityPrincipal principal) {
        // Oracle has no ALTER USER / ALTER ROLE ... RENAME
        return false;
    }

    @Nullable
    @Override
    public EnabledStyle getEnabledStyle(@NotNull SecurityPrincipal principal) {
        return principal.getKind() == SecurityPrincipal.Kind.USER ? EnabledStyle.NOT_LOCKED : null;
    }

    @NotNull
    @Override
    public MembershipStyle getMembershipStyle() {
        return MembershipStyle.ROLES;
    }

    @NotNull
    @Override
    public List<String> getPrivileges(@NotNull Securable.Kind kind) {
        return kind == Securable.Kind.DATABASE ? systemPrivileges : OBJECT_PRIVILEGES.getOrDefault(kind, List.of());
    }

    @NotNull
    @Override
    public List<Securable> listSecurables(@NotNull DBRProgressMonitor monitor, @Nullable String database) throws DBException {
        readLists(monitor);
        List<Securable> securables = new ArrayList<>();
        securables.add(getDatabase());
        query(monitor, "SELECT o.OWNER, o.OBJECT_NAME, o.OBJECT_TYPE FROM DBA_OBJECTS o " + //$NON-NLS-1$
                "WHERE o.OBJECT_TYPE IN ('TABLE', 'VIEW', 'SEQUENCE', 'FUNCTION', 'PROCEDURE') AND o.GENERATED = 'N' AND o.SECONDARY = 'N' AND " + //$NON-NLS-1$
                getUserObjectFilter("o.OWNER"), //$NON-NLS-1$
            resultSet -> {
                Securable.Kind kind = toKind(resultSet.getString(3));
                if (kind != null) {
                    securables.add(new Securable(kind, null, resultSet.getString(1), resultSet.getString(2), null));
                }
            });
        securables.sort(null);
        return securables;
    }

    @Nullable
    @Override
    protected Securable.Kind getSecurableKind(@NotNull DBSObject object) {
        // Procedures and functions of a package are granted through the package (not handled here)
        return object instanceof OracleProcedurePackaged ? null : super.getSecurableKind(object);
    }

    @NotNull
    @Override
    public PermissionGrid<String> loadObjectPermissions(@NotNull DBRProgressMonitor monitor, @NotNull Securable securable) throws DBException {
        PermissionGrid<String> grid = new PermissionGrid<>();
        query(monitor, "SELECT GRANTEE, PRIVILEGE, GRANTABLE FROM DBA_TAB_PRIVS WHERE OWNER = ? AND TABLE_NAME = ?", //$NON-NLS-1$
            resultSet -> grid.set(resultSet.getString(1), resultSet.getString(2), readState(resultSet.getString(3))),
            CommonUtils.notEmpty(securable.schema()), securable.name());
        return grid;
    }

    @NotNull
    @Override
    public List<String> listGrantees(@NotNull DBRProgressMonitor monitor, @NotNull Securable securable) throws DBException {
        List<String> grantees = new ArrayList<>();
        grantees.add("PUBLIC"); //$NON-NLS-1$
        grantees.addAll(queryStrings(monitor, "SELECT NAME FROM (" + //$NON-NLS-1$
            "SELECT USERNAME AS NAME FROM DBA_USERS WHERE " + getUserObjectFilter("USERNAME") + //$NON-NLS-1$ //$NON-NLS-2$
            " UNION SELECT ROLE FROM DBA_ROLES WHERE " + getMaintainedFilter() + //$NON-NLS-1$
            ") ORDER BY NAME")); //$NON-NLS-1$
        grantees.remove(securable.schema());
        return grantees;
    }

    @NotNull
    @Override
    public List<SecurityStatement> generateScript(@NotNull SecurityPrincipal original, @NotNull SecurityPrincipal edited) throws DBException {
        List<SecurityStatement> statements = new ArrayList<>();
        boolean user = edited.getKind() == SecurityPrincipal.Kind.USER;
        String password = edited.getPassword();
        if (password != null && password.indexOf('"') >= 0) {
            throw new DBException("An Oracle password cannot contain a double quote"); //$NON-NLS-1$
        }
        String name;
        if (edited.isNew()) {
            name = quote(normalizeNewName(edited.getName()));
            if (!user) {
                statements.add(SecurityStatement.of("CREATE ROLE " + name)); //$NON-NLS-1$
            } else if (CommonUtils.isEmpty(password)) {
                // A schema only account (Oracle 18c+): nobody can log in until a password is set
                statements.add(SecurityStatement.of("CREATE USER " + name + " NO AUTHENTICATION" + lockClause(edited))); //$NON-NLS-1$ //$NON-NLS-2$
            } else {
                statements.add(new SecurityStatement(
                    "CREATE USER " + name + " IDENTIFIED BY \"" + password + "\"" + lockClause(edited), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "CREATE USER " + name + " IDENTIFIED BY " + MASKED_PASSWORD + lockClause(edited), //$NON-NLS-1$ //$NON-NLS-2$
                    null, true));
            }
        } else {
            name = quote(original.getName());
            if (user && !CommonUtils.isEmpty(password)) {
                statements.add(new SecurityStatement(
                    "ALTER USER " + name + " IDENTIFIED BY \"" + password + "\"", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "ALTER USER " + name + " IDENTIFIED BY " + MASKED_PASSWORD, //$NON-NLS-1$ //$NON-NLS-2$
                    null, true));
            }
            if (user && !CommonUtils.equalObjects(original.getEnabled(), edited.getEnabled())) {
                statements.add(SecurityStatement.of("ALTER USER " + name + " ACCOUNT " //$NON-NLS-1$ //$NON-NLS-2$
                    + (Boolean.FALSE.equals(edited.getEnabled()) ? "LOCK" : "UNLOCK"))); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        diffFlags(original.getMemberships(), edited.getMemberships(), added, removed);
        for (String role : removed) {
            statements.add(SecurityStatement.of("REVOKE " + quote(role) + " FROM " + name)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String role : added) {
            statements.add(SecurityStatement.of("GRANT " + quote(role) + " TO " + name)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        PermissionGrid<Securable> before = original.getPermissions();
        PermissionGrid<Securable> after = edited.getPermissions();
        List<Securable> securables = new ArrayList<>(before.getRows());
        for (Securable securable : after.getRows()) {
            if (!securables.contains(securable)) {
                securables.add(securable);
            }
        }
        for (Securable securable : securables) {
            statements.addAll(diffPermissions(securable, name, before.getStates(securable), after.getStates(securable), null));
        }
        return statements;
    }

    @NotNull
    @Override
    public List<SecurityStatement> generateDropScript(@NotNull SecurityPrincipal principal) {
        String keyword = principal.getKind() == SecurityPrincipal.Kind.USER ? "DROP USER " : "DROP ROLE "; //$NON-NLS-1$ //$NON-NLS-2$
        return List.of(SecurityStatement.of(keyword + quote(principal.getName())));
    }

    @NotNull
    @Override
    protected String formatGrantee(@NotNull String grantee) {
        // PUBLIC is a keyword; "PUBLIC" would be a user of that name
        return "PUBLIC".equals(grantee) ? grantee : quote(grantee); //$NON-NLS-1$
    }

    @NotNull
    @Override
    protected String formatTarget(@NotNull Securable securable) {
        if (securable.kind() == Securable.Kind.DATABASE) {
            // System privileges: GRANT <privilege> TO <grantee>
            return ""; //$NON-NLS-1$
        }
        return securable.schema() == null ? quote(securable.name()) : quote(securable.schema()) + "." + quote(securable.name()); //$NON-NLS-1$
    }

    /**
     * Oracle has no {@code REVOKE GRANT OPTION FOR}: a privilege that keeps only its grant (or admin) option switched
     * off is revoked and granted again.
     */
    @NotNull
    @Override
    protected List<SecurityStatement> diffPermissions(
        @NotNull Securable securable,
        @NotNull String grantee,
        @NotNull Map<String, PermissionGrid.State> before,
        @NotNull Map<String, PermissionGrid.State> after,
        @Nullable String database
    ) {
        Map<String, PermissionGrid.State> adjusted = new LinkedHashMap<>(before);
        List<String> regranted = new ArrayList<>();
        for (Map.Entry<String, PermissionGrid.State> entry : before.entrySet()) {
            if (entry.getValue() == PermissionGrid.State.GRANT_WITH && after.get(entry.getKey()) == PermissionGrid.State.GRANT) {
                regranted.add(entry.getKey());
                adjusted.remove(entry.getKey());
            }
        }
        List<SecurityStatement> statements = new ArrayList<>();
        if (!regranted.isEmpty()) {
            statements.add(SecurityStatement.of(revokeSql(formatTarget(securable), regranted, grantee, false)));
        }
        statements.addAll(super.diffPermissions(securable, grantee, adjusted, after, database));
        return statements;
    }

    @NotNull
    @Override
    protected String grantSql(@NotNull String target, @NotNull List<String> privileges, @NotNull String grantee, boolean withGrantOption) {
        if (target.isEmpty()) {
            // System privileges and roles have the admin option instead of the grant option
            return "GRANT " + String.join(", ", privileges) + " TO " + grantee + (withGrantOption ? " WITH ADMIN OPTION" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
        return super.grantSql(target, privileges, grantee, withGrantOption);
    }

    @NotNull
    @Override
    protected String revokeSql(@NotNull String target, @NotNull List<String> privileges, @NotNull String grantee, boolean cascade) {
        // Oracle revokes object privileges granted on by the grantee itself; REVOKE has no CASCADE (only CASCADE CONSTRAINTS)
        return super.revokeSql(target, privileges, grantee, false);
    }

    @NotNull
    private Securable getDatabase() {
        Securable securable = database;
        if (securable == null) {
            throw new IllegalStateException("The current container was not read"); //$NON-NLS-1$
        }
        return securable;
    }

    /**
     * Reads the current container and the system privilege names once.
     */
    private void readLists(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (database == null) {
            List<String> names = queryStrings(monitor, "SELECT SYS_CONTEXT('USERENV', 'CON_NAME') FROM DUAL"); //$NON-NLS-1$
            database = new Securable(Securable.Kind.DATABASE, null, null, names.isEmpty() ? dataSource.getName() : names.getFirst(), null);
        }
        if (systemPrivileges.isEmpty()) {
            systemPrivileges = queryStrings(monitor, "SELECT NAME FROM SYSTEM_PRIVILEGE_MAP ORDER BY NAME"); //$NON-NLS-1$
        }
    }

    @NotNull
    private List<String> listRoles(@NotNull DBRProgressMonitor monitor, @Nullable String exclude) throws DBException {
        List<String> roles = queryStrings(monitor, "SELECT ROLE FROM DBA_ROLES ORDER BY ROLE"); //$NON-NLS-1$
        roles.remove(exclude);
        return roles;
    }

    /**
     * SQL condition: the owner (or user) column is a user that Oracle does not maintain.
     */
    @NotNull
    private String getUserObjectFilter(@NotNull String userColumn) {
        if (dataSource.isAtLeastV12()) {
            return userColumn + " IN (SELECT USERNAME FROM DBA_USERS WHERE ORACLE_MAINTAINED = 'N')"; //$NON-NLS-1$
        }
        return userColumn + " NOT IN ('SYS', 'SYSTEM')"; //$NON-NLS-1$
    }

    @NotNull
    private String getMaintainedFilter() {
        return dataSource.isAtLeastV12() ? "ORACLE_MAINTAINED = 'N'" : "1 = 1"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    private static String normalizeNewName(@NotNull String name) {
        return UNQUOTED_NAME.matcher(name).matches() ? name.toUpperCase(Locale.ENGLISH) : name;
    }

    @NotNull
    private static String lockClause(@NotNull SecurityPrincipal principal) {
        return Boolean.FALSE.equals(principal.getEnabled()) ? " ACCOUNT LOCK" : ""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Nullable
    private static Securable.Kind toKind(@Nullable String objectType) {
        if (objectType == null) {
            return null;
        }
        return switch (objectType) {
            case "TABLE" -> Securable.Kind.TABLE; //$NON-NLS-1$
            case "VIEW" -> Securable.Kind.VIEW; //$NON-NLS-1$
            case "SEQUENCE" -> Securable.Kind.SEQUENCE; //$NON-NLS-1$
            case "FUNCTION" -> Securable.Kind.FUNCTION; //$NON-NLS-1$
            case "PROCEDURE" -> Securable.Kind.PROCEDURE; //$NON-NLS-1$
            default -> null;
        };
    }

    @NotNull
    private static PermissionGrid.State readState(@Nullable String grantable) {
        return STATE_YES.equals(grantable) ? PermissionGrid.State.GRANT_WITH : PermissionGrid.State.GRANT;
    }
}
