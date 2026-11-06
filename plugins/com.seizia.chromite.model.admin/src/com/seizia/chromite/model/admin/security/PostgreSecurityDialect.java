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
import com.seizia.chromite.ext.postgresql.PostgreConstants;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.model.PostgrePrivilegeType;
import com.seizia.chromite.ext.postgresql.model.PostgreProcedure;
import com.seizia.chromite.ext.postgresql.model.PostgreProcedureKind;
import com.seizia.chromite.ext.postgresql.model.PostgreRole;
import com.seizia.chromite.ext.postgresql.model.PostgreSchema;
import com.seizia.chromite.ext.postgresql.model.PostgreSequence;
import com.seizia.chromite.ext.postgresql.model.PostgreServerExtension;
import com.seizia.chromite.ext.postgresql.model.PostgreTable;
import com.seizia.chromite.ext.postgresql.model.PostgreView;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL roles (users are roles that can log in) and their permissions.
 *
 * 설계: 문 모양은 모델의 관리자와 같다.
 * <ul>
 *     <li>역할 옵션: {@code PostgreRoleManager.addRoleOptions}(ext.postgresql {@code PostgreRoleManager.java:138-182}) —
 *     서버 확장({@link PostgreServerExtension})이 지원하는 옵션만 쓰고, 만들 때 PASSWORD·VALID UNTIL을 붙인다.
 *     바꿀 때는 바뀐 옵션만 {@code ALTER ROLE}에 쓴다(관리자는 모든 옵션을 다시 쓴다).</li>
 *     <li>이름 바꾸기·지우기: {@code PostgreRoleManager.java:116-130}({@code ALTER ROLE … RENAME TO}, {@code DROP ROLE}).</li>
 *     <li>암호 바꾸기: {@code PostgresUserPasswordManager}({@code ALTER USER … WITH PASSWORD}, 기록 끔).</li>
 *     <li>역할 소속: {@code PostgreRole.getObjectDefinitionText}의 {@code GRANT <role> TO <member>}(PostgreRole.java:479-488).</li>
 *     <li>권한: {@code PostgreCommandGrantPrivilege.getPersistActions}(PostgreCommandGrantPrivilege.java:146)의
 *     {@code GRANT <privs> ON <TABLE|SEQUENCE|FUNCTION|SCHEMA|DATABASE> <name> TO <role>}, PUBLIC은 따옴표 없이(같은 파일 233행).</li>
 * </ul>
 * 권한은 ACL 열({@code relacl}, {@code proacl}, {@code nspacl}, {@code datacl})을 {@code aclexplode}로 펼쳐 읽는다. 모델의
 * {@code PostgreRole.getPrivileges}도 같은 카탈로그를 쓰지만 스키마·시퀀스 권한의 WITH GRANT OPTION을 버리고(PostgreRole.java:629-636),
 * 객체 소유자의 암묵적 권한은 이 창에서 고칠 대상이 아니므로 뺀다({@code grantee <> owner}).
 * 권한 종류는 모델의 {@link PostgreDataSource#getSupportedPrivilegeTypes()}를 객체 종류로 거른다({@code RolePermissionsEditor}와 같음).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 */
final class PostgreSecurityDialect extends SecurityDialect {

    static final String OPTION_SUPERUSER = "SUPERUSER"; //$NON-NLS-1$
    static final String OPTION_CREATEDB = "CREATEDB"; //$NON-NLS-1$
    static final String OPTION_CREATEROLE = "CREATEROLE"; //$NON-NLS-1$
    static final String OPTION_INHERIT = "INHERIT"; //$NON-NLS-1$
    static final String OPTION_REPLICATION = "REPLICATION"; //$NON-NLS-1$
    static final String OPTION_BYPASSRLS = "BYPASSRLS"; //$NON-NLS-1$

    private static final String COLUMN_KIND = "kind"; //$NON-NLS-1$

    @NotNull
    private final PostgreDataSource dataSource;
    private final Map<Securable.Kind, List<String>> privileges = new EnumMap<>(Securable.Kind.class);
    /**
     * Database of the context object: ACLs, schemas and objects are per database (the role list is per cluster).
     */
    @NotNull
    private final String databaseName;

    PostgreSecurityDialect(@NotNull DBSObject context, @NotNull PostgreDataSource dataSource) {
        super(context);
        this.dataSource = dataSource;
        PostgreDatabase database = context instanceof PostgreDatabase contextDatabase
            ? contextDatabase : DBUtils.getParentOfType(PostgreDatabase.class, context);
        this.databaseName = (database != null ? database : dataSource.getDefaultInstance()).getName();
        Map<Securable.Kind, Class<?>> classes = new EnumMap<>(Securable.Kind.class);
        classes.put(Securable.Kind.DATABASE, PostgreDatabase.class);
        classes.put(Securable.Kind.SCHEMA, PostgreSchema.class);
        classes.put(Securable.Kind.TABLE, PostgreTable.class);
        classes.put(Securable.Kind.VIEW, PostgreView.class);
        classes.put(Securable.Kind.SEQUENCE, PostgreSequence.class);
        classes.put(Securable.Kind.FUNCTION, PostgreProcedure.class);
        classes.put(Securable.Kind.PROCEDURE, PostgreProcedure.class);
        for (Map.Entry<Securable.Kind, Class<?>> entry : classes.entrySet()) {
            List<String> names = new ArrayList<>();
            for (PostgrePrivilegeType type : dataSource.getSupportedPrivilegeTypes()) {
                if (type.isValid() && type != PostgrePrivilegeType.ALL && type.supportsType(entry.getValue())) {
                    names.add(type.name());
                }
            }
            privileges.put(entry.getKey(), names);
        }
    }

    @Override
    public boolean isPrincipal(@NotNull DBSObject object) {
        return object instanceof PostgreRole role && !role.isPublicRole();
    }

    @Nullable
    @Override
    public SecurityPrincipal.Kind getNewPrincipalKind(@NotNull Class<?> folderChildrenClass) {
        return PostgreRole.class.isAssignableFrom(folderChildrenClass) ? SecurityPrincipal.Kind.ROLE : null;
    }

    @NotNull
    @Override
    public SecurityPrincipal loadPrincipal(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object) throws DBException {
        SecurityPrincipal principal = new SecurityPrincipal(SecurityPrincipal.Kind.ROLE, false, object, object.getName());
        boolean[] found = new boolean[1];
        query(monitor, """
                SELECT r.*, CASE WHEN r.rolvaliduntil IS NULL OR r.rolvaliduntil = 'infinity' THEN ''
                    ELSE pg_catalog.to_char(r.rolvaliduntil, 'YYYY-MM-DD HH24:MI:SS') END AS valid_until
                FROM pg_catalog.pg_roles r WHERE r.rolname = ?""", //$NON-NLS-1$
            resultSet -> {
                found[0] = true;
                principal.setEnabled(JDBCUtils.safeGetBoolean(resultSet, "rolcanlogin")); //$NON-NLS-1$
                principal.setExpiry(CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "valid_until"))); //$NON-NLS-1$
                for (String option : getOptionIds()) {
                    principal.getOptions().put(option, JDBCUtils.safeGetBoolean(resultSet, getOptionColumn(option)));
                }
            }, object.getName());
        if (!found[0]) {
            throw new DBException("Role " + object.getName() + " does not exist"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String role : listRoles(monitor, object.getName())) {
            principal.getMemberships().put(role, false);
        }
        query(monitor, """
                SELECT r.rolname FROM pg_catalog.pg_auth_members m
                JOIN pg_catalog.pg_roles r ON r.oid = m.roleid
                JOIN pg_catalog.pg_roles u ON u.oid = m.member
                WHERE u.rolname = ?""", //$NON-NLS-1$
            resultSet -> principal.getMemberships().put(resultSet.getString(1), true), object.getName());
        readPermissions(monitor, "a.grantee = (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = ?)", //$NON-NLS-1$
            (resultSet, securable) -> principal.getPermissions().set(securable, resultSet.getString("privilege_type"), readState(resultSet)), //$NON-NLS-1$
            object.getName());
        return principal;
    }

    @NotNull
    @Override
    public SecurityPrincipal newPrincipal(@NotNull DBRProgressMonitor monitor, @NotNull SecurityPrincipal.Kind kind) throws DBException {
        SecurityPrincipal principal = new SecurityPrincipal(SecurityPrincipal.Kind.ROLE, true, null, ""); //$NON-NLS-1$
        // CREATE ROLE defaults (PostgreSQL docs): LOGIN is off, INHERIT is on; the dialog starts with a login role like SSMS New Login
        principal.setEnabled(true);
        principal.setExpiry(""); //$NON-NLS-1$
        for (String option : getOptionIds()) {
            principal.getOptions().put(option, OPTION_INHERIT.equals(option));
        }
        for (String role : listRoles(monitor, null)) {
            principal.getMemberships().put(role, false);
        }
        return principal;
    }

    @Override
    public boolean supportsPassword(@NotNull SecurityPrincipal principal) {
        return true;
    }

    @NotNull
    @Override
    public EnabledStyle getEnabledStyle(@NotNull SecurityPrincipal principal) {
        return EnabledStyle.CAN_LOGIN;
    }

    @NotNull
    @Override
    public MembershipStyle getMembershipStyle() {
        return MembershipStyle.MEMBER_OF;
    }

    @NotNull
    @Override
    public List<String> getPrivileges(@NotNull Securable.Kind kind) {
        return privileges.getOrDefault(kind, List.of());
    }

    @NotNull
    @Override
    public List<Securable> listSecurables(@NotNull DBRProgressMonitor monitor, @Nullable String database) throws DBException {
        List<Securable> securables = new ArrayList<>();
        query(monitor, "SELECT o.kind, o.schema_name, o.object_name, o.signature FROM (" + getObjectsQuery(false) + ") o " + //$NON-NLS-1$ //$NON-NLS-2$
                "WHERE o.schema_name IS NULL OR (o.schema_name NOT IN ('pg_catalog', 'information_schema') AND o.schema_name NOT LIKE 'pg\\_%')", //$NON-NLS-1$
            resultSet -> securables.add(readSecurable(resultSet)));
        securables.sort(null);
        return securables;
    }

    @Nullable
    @Override
    public Securable toSecurable(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object) throws DBException {
        if (object instanceof PostgreProcedure procedure) {
            List<String> arguments = queryStrings(monitor, "SELECT pg_catalog.pg_get_function_identity_arguments(CAST(? AS oid))", //$NON-NLS-1$
                procedure.getObjectId());
            return new Securable(procedure.getKind() == PostgreProcedureKind.p ? Securable.Kind.PROCEDURE : Securable.Kind.FUNCTION,
                procedure.getDatabase().getName(), procedure.getSchema().getName(), procedure.getName(),
                arguments.isEmpty() ? "" : CommonUtils.notEmpty(arguments.getFirst())); //$NON-NLS-1$
        }
        return super.toSecurable(monitor, object);
    }

    @NotNull
    @Override
    public PermissionGrid<String> loadObjectPermissions(@NotNull DBRProgressMonitor monitor, @NotNull Securable securable) throws DBException {
        PermissionGrid<String> grid = new PermissionGrid<>();
        readPermissions(monitor,
            "o.kind = ? AND o.schema_name IS NOT DISTINCT FROM ? AND o.object_name = ? AND o.signature IS NOT DISTINCT FROM ?", //$NON-NLS-1$
            (resultSet, ignored) -> grid.set(resultSet.getString("grantee"), resultSet.getString("privilege_type"), readState(resultSet)), //$NON-NLS-1$ //$NON-NLS-2$
            securable.kind().name(), securable.schema(), securable.name(), securable.signature());
        return grid;
    }

    @NotNull
    @Override
    public List<String> listGrantees(@NotNull DBRProgressMonitor monitor, @NotNull Securable securable) throws DBException {
        // Predefined roles (pg_monitor, pg_read_all_data, ...) are left out: they get their rights from the server, not from GRANT ON
        List<String> grantees = new ArrayList<>();
        grantees.add(PostgreConstants.PUBLIC_ROLE_NAME.toUpperCase());
        grantees.addAll(queryStrings(monitor,
            "SELECT rolname FROM pg_catalog.pg_roles WHERE rolname NOT LIKE 'pg\\_%' ORDER BY rolname")); //$NON-NLS-1$
        return grantees;
    }

    @NotNull
    @Override
    public List<SecurityStatement> generateScript(@NotNull SecurityPrincipal original, @NotNull SecurityPrincipal edited) {
        List<SecurityStatement> statements = new ArrayList<>();
        String name = quote(edited.getName());
        if (edited.isNew()) {
            StringBuilder options = new StringBuilder();
            for (Map.Entry<String, Boolean> option : edited.getOptions().entrySet()) {
                appendOption(options, option.getKey(), Boolean.TRUE.equals(option.getValue()));
            }
            appendOption(options, "LOGIN", Boolean.TRUE.equals(edited.getEnabled())); //$NON-NLS-1$
            String validUntil = CommonUtils.isEmpty(edited.getExpiry()) ? "" : " VALID UNTIL " + quoteString(edited.getExpiry()); //$NON-NLS-1$ //$NON-NLS-2$
            String create = "CREATE ROLE " + name + options; //$NON-NLS-1$
            if (CommonUtils.isEmpty(edited.getPassword())) {
                statements.add(SecurityStatement.of(create + validUntil));
            } else {
                statements.add(new SecurityStatement(
                    create + " PASSWORD " + quoteString(edited.getPassword()) + validUntil, //$NON-NLS-1$
                    create + " PASSWORD " + MASKED_PASSWORD + validUntil, //$NON-NLS-1$
                    null, true));
            }
        } else {
            if (!original.getName().equals(edited.getName())) {
                statements.add(SecurityStatement.of("ALTER ROLE " + quote(original.getName()) + " RENAME TO " + name)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            StringBuilder options = new StringBuilder();
            for (Map.Entry<String, Boolean> option : edited.getOptions().entrySet()) {
                boolean on = Boolean.TRUE.equals(option.getValue());
                if (on != Boolean.TRUE.equals(original.getOptions().get(option.getKey()))) {
                    appendOption(options, option.getKey(), on);
                }
            }
            if (!CommonUtils.equalObjects(original.getEnabled(), edited.getEnabled())) {
                appendOption(options, "LOGIN", Boolean.TRUE.equals(edited.getEnabled())); //$NON-NLS-1$
            }
            if (!CommonUtils.equalObjects(CommonUtils.notEmpty(original.getExpiry()), CommonUtils.notEmpty(edited.getExpiry()))) {
                options.append(" VALID UNTIL ").append(quoteString(CommonUtils.isEmpty(edited.getExpiry()) ? "infinity" : edited.getExpiry())); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!options.isEmpty()) {
                statements.add(SecurityStatement.of("ALTER ROLE " + name + options)); //$NON-NLS-1$
            }
            if (!CommonUtils.isEmpty(edited.getPassword())) {
                statements.add(new SecurityStatement(
                    "ALTER ROLE " + name + " WITH PASSWORD " + quoteString(edited.getPassword()), //$NON-NLS-1$ //$NON-NLS-2$
                    "ALTER ROLE " + name + " WITH PASSWORD " + MASKED_PASSWORD, //$NON-NLS-1$ //$NON-NLS-2$
                    null, true));
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
        return List.of(SecurityStatement.of("DROP ROLE " + quote(principal.getName()))); //$NON-NLS-1$
    }

    @NotNull
    @Override
    protected String formatGrantee(@NotNull String grantee) {
        // PUBLIC is a keyword, "PUBLIC" would be a role of that name (PostgreCommandGrantPrivilege.getGranteeName)
        return PostgreConstants.PUBLIC_ROLE_NAME.equalsIgnoreCase(grantee) ? PostgreConstants.PUBLIC_ROLE_NAME.toUpperCase() : quote(grantee);
    }

    @NotNull
    @Override
    protected String formatTarget(@NotNull Securable securable) {
        String qualified = securable.schema() == null ? quote(securable.name()) : quote(securable.schema()) + "." + quote(securable.name()); //$NON-NLS-1$
        return switch (securable.kind()) {
            case DATABASE -> "DATABASE " + quote(securable.name()); //$NON-NLS-1$
            case SCHEMA -> "SCHEMA " + quote(securable.name()); //$NON-NLS-1$
            case TABLE, VIEW -> "TABLE " + qualified; //$NON-NLS-1$
            case SEQUENCE -> "SEQUENCE " + qualified; //$NON-NLS-1$
            case FUNCTION -> "FUNCTION " + qualified + "(" + CommonUtils.notEmpty(securable.signature()) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case PROCEDURE -> "PROCEDURE " + qualified + "(" + CommonUtils.notEmpty(securable.signature()) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        };
    }

    /**
     * Options of {@code CREATE ROLE} this server supports, in the order {@code PostgreRoleManager.addRoleOptions} writes them.
     */
    @NotNull
    private List<String> getOptionIds() {
        PostgreServerExtension extension = dataSource.getServerType();
        List<String> options = new ArrayList<>();
        if (extension.supportsSuperusers()) {
            options.add(OPTION_SUPERUSER);
        }
        if (extension.supportsRolesWithCreateDBAbility()) {
            options.add(OPTION_CREATEDB);
        }
        options.add(OPTION_CREATEROLE);
        if (extension.supportsInheritance()) {
            options.add(OPTION_INHERIT);
        }
        if (extension.supportsRoleReplication()) {
            options.add(OPTION_REPLICATION);
        }
        if (extension.supportsRoleBypassRLS()) {
            options.add(OPTION_BYPASSRLS);
        }
        return options;
    }

    @NotNull
    private static String getOptionColumn(@NotNull String option) {
        return switch (option) {
            case OPTION_SUPERUSER -> "rolsuper"; //$NON-NLS-1$
            case OPTION_CREATEDB -> "rolcreatedb"; //$NON-NLS-1$
            case OPTION_CREATEROLE -> "rolcreaterole"; //$NON-NLS-1$
            case OPTION_INHERIT -> "rolinherit"; //$NON-NLS-1$
            case OPTION_REPLICATION -> "rolreplication"; //$NON-NLS-1$
            default -> "rolbypassrls"; //$NON-NLS-1$
        };
    }

    private static void appendOption(@NotNull StringBuilder options, @NotNull String option, boolean on) {
        options.append(on ? " " : " NO").append(option); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    private List<String> listRoles(@NotNull DBRProgressMonitor monitor, @Nullable String exclude) throws DBException {
        List<String> roles = queryStrings(monitor, "SELECT rolname FROM pg_catalog.pg_roles ORDER BY rolname"); //$NON-NLS-1$
        roles.remove(exclude);
        return roles;
    }

    /**
     * Objects with their ACL and owner: tables, views, sequences, routines, schemas and the current database.
     */
    @NotNull
    private String getObjectsQuery(boolean withAcl) {
        String routineKind = dataSource.isServerVersionAtLeast(11, 0)
            ? "CASE WHEN p.prokind = 'p' THEN 'PROCEDURE' ELSE 'FUNCTION' END" //$NON-NLS-1$
            : "'FUNCTION'"; //$NON-NLS-1$
        String aclFilter = withAcl ? " AND %s IS NOT NULL" : ""; //$NON-NLS-1$ //$NON-NLS-2$
        return """
            SELECT CASE WHEN c.relkind = 'S' THEN 'SEQUENCE' WHEN c.relkind IN ('v', 'm') THEN 'VIEW' ELSE 'TABLE' END AS kind,
                n.nspname AS schema_name, c.relname AS object_name, CAST(NULL AS text) AS signature, c.relacl AS acl, c.relowner AS owner
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')""" + aclFilter.formatted("c.relacl") + """

            UNION ALL
            SELECT %s, n.nspname, p.proname, pg_catalog.pg_get_function_identity_arguments(p.oid), p.proacl, p.proowner
            FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE true""".formatted(routineKind) + aclFilter.formatted("p.proacl") + """

            UNION ALL
            SELECT 'SCHEMA', CAST(NULL AS name), n.nspname, NULL, n.nspacl, n.nspowner FROM pg_catalog.pg_namespace n
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\\_%'""" + aclFilter.formatted("n.nspacl") + """

            UNION ALL
            SELECT 'DATABASE', NULL, d.datname, NULL, d.datacl, d.datdba FROM pg_catalog.pg_database d
            WHERE d.datname = pg_catalog.current_database()""" + aclFilter.formatted("d.datacl"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
    }

    @FunctionalInterface
    private interface PermissionReader {
        void read(@NotNull JDBCResultSet resultSet, @NotNull Securable securable) throws SQLException;
    }

    private void readPermissions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String filter,
        @NotNull PermissionReader reader,
        @NotNull Object... parameters
    ) throws DBException {
        String sql = """
            SELECT o.kind, o.schema_name, o.object_name, o.signature, a.privilege_type, a.is_grantable,
                CASE WHEN a.grantee = 0 THEN 'PUBLIC' ELSE pg_catalog.pg_get_userbyid(a.grantee) END AS grantee
            FROM (""" + getObjectsQuery(true) + """
            ) o CROSS JOIN LATERAL pg_catalog.aclexplode(o.acl) a
            WHERE a.grantee <> o.owner AND """ + " " + filter; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        query(monitor, sql, resultSet -> reader.read(resultSet, readSecurable(resultSet)), parameters);
    }

    @NotNull
    private Securable readSecurable(@NotNull JDBCResultSet resultSet) throws SQLException {
        Securable.Kind kind = Securable.Kind.valueOf(resultSet.getString(COLUMN_KIND));
        return new Securable(kind, databaseName, resultSet.getString("schema_name"), resultSet.getString("object_name"), //$NON-NLS-1$ //$NON-NLS-2$
            resultSet.getString("signature")); //$NON-NLS-1$
    }

    @NotNull
    private static PermissionGrid.State readState(@NotNull JDBCResultSet resultSet) throws SQLException {
        return resultSet.getBoolean("is_grantable") ? PermissionGrid.State.GRANT_WITH : PermissionGrid.State.GRANT; //$NON-NLS-1$
    }
}
