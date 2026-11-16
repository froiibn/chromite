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
import com.seizia.chromite.ext.mysql.MySQLUtils;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.mysql.model.MySQLPrivilege;
import com.seizia.chromite.ext.mysql.model.MySQLUser;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MySQL and MariaDB accounts ({@code 'user'@'host'}), MariaDB roles, and their database, table and routine privileges.
 *
 * 설계: DBeaver의 MySQL 사용자 편집은 모두 SWT 플러그인({@code ext.mysql.ui/config})에 있어 Swing 제품에서 쓸 수 없다.
 * 문 모양은 그 클래스와 같게 했다.
 * <ul>
 *     <li>만들기·암호: {@code MySQLCommandChangeUser.generateCreateScript}·{@code generateAlterScript}
 *     ({@code CREATE USER 'u'@'h' IDENTIFIED BY …}, {@link MySQLUtils#isAlterUSerSupported}이면 {@code ALTER USER … IDENTIFIED BY},
 *     아니면 {@code SET PASSWORD … = PASSWORD(…)}).</li>
 *     <li>이름 바꾸기·지우기: {@code MySQLUserManager.CommandRenameUser}({@code RENAME USER … TO …})와 {@code CommandDropUser}({@code DROP USER}).</li>
 *     <li>권한: {@code MySQLCommandGrantPrivilege}({@code GRANT <PRIV> ON `db`.`table` TO 'u'@'h' [WITH GRANT OPTION]}, 권한 이름은
 *     {@link MySQLPrivilege#getFixedPrivilegeName()}의 대문자). MySQL의 GRANT OPTION은 권한마다가 아니라 (계정, 객체)마다 하나라서
 *     {@link #diffPermissions}가 객체 단위로 다룬다.</li>
 * </ul>
 * 권한 종류는 모델의 {@link MySQLDataSource#getPrivileges}({@code SHOW PRIVILEGES})를 Context 열로 거른다. 현재 권한은 모델의
 * {@code MySQLUser.getGrants}({@code SHOW GRANTS})가 이름을 대문자로 바꿔 버려(MySQLUser.java:185) 대소문자를 가리는 서버에서 객체를 찾지 못하므로,
 * {@code information_schema}의 SCHEMA_PRIVILEGES·TABLE_PRIVILEGES와 {@code mysql.procs_priv}에서 읽는다.
 * 계정 잠금(ACCOUNT LOCK)은 MariaDB 10.4.2+와 MySQL 5.7.6+, 역할은 MariaDB({@code mysql.roles_mapping})만 다룬다.
 * <p>
 * 자원 제한(2026-11-08, SWT 감사 D-M5): SWT 일반 탭({@code MySQLUserEditorGeneral})의 네 값 MAX_QUERIES_PER_HOUR·MAX_UPDATES_PER_HOUR·
 * MAX_CONNECTIONS_PER_HOUR·MAX_USER_CONNECTIONS를 {@code mysql.user}의 열(max_questions 등, 모델 {@code MySQLUser}가 읽는 열)에서 읽는다.
 * 문은 {@code MySQLCommandChangeUser.generateAlterScript}와 같은 {@code ALTER USER … WITH …}(바뀐 값만)이고, 새 사용자는 {@code CREATE USER … WITH …}다.
 * ALTER USER가 없는 서버({@link MySQLUtils#isAlterUSerSupported}가 거짓)에서 SWT는 {@code UPDATE mysql.user}를 썼는데 FLUSH PRIVILEGES 없이는
 * 적용되지 않으므로, 그 서버들이 문서로 정한 {@code GRANT USAGE ON *.* TO … WITH …}를 쓴다. 역할에는 자원 제한이 없다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 * - 2026-11-08 자원 제한(MAX_QUERIES_PER_HOUR 등) (docs/chromite/swing-security.md "MySQL/MariaDB 자원 제한")
 */
final class MySQLSecurityDialect extends SecurityDialect {

    private static final String GRANT_OPTION = "GRANT OPTION"; //$NON-NLS-1$
    private static final String SYSTEM_SCHEMAS = "('information_schema', 'performance_schema', 'mysql', 'sys')"; //$NON-NLS-1$

    /**
     * Resource limits (the keyword of {@code WITH}) and their {@code mysql.user} columns, in the order of the SWT General tab.
     */
    private static final Map<String, String> LIMIT_COLUMNS = new LinkedHashMap<>();

    static {
        LIMIT_COLUMNS.put("MAX_QUERIES_PER_HOUR", "max_questions"); //$NON-NLS-1$ //$NON-NLS-2$
        LIMIT_COLUMNS.put("MAX_UPDATES_PER_HOUR", "max_updates"); //$NON-NLS-1$ //$NON-NLS-2$
        LIMIT_COLUMNS.put("MAX_CONNECTIONS_PER_HOUR", "max_connections"); //$NON-NLS-1$ //$NON-NLS-2$
        LIMIT_COLUMNS.put("MAX_USER_CONNECTIONS", "max_user_connections"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    private final MySQLDataSource dataSource;
    private final boolean mariaDB;
    private final Map<Securable.Kind, List<String>> privileges = new EnumMap<>(Securable.Kind.class);

    MySQLSecurityDialect(@NotNull DBSObject context, @NotNull MySQLDataSource dataSource) {
        super(context);
        this.dataSource = dataSource;
        // The MySQL driver can be connected to a MariaDB server: check the server, not only the driver
        this.mariaDB = dataSource.isMariaDB()
            || CommonUtils.notEmpty(dataSource.getInfo().getDatabaseProductVersion()).toLowerCase(Locale.ENGLISH).contains("mariadb"); //$NON-NLS-1$
    }

    @Override
    public boolean isPrincipal(@NotNull DBSObject object) {
        return object instanceof MySQLUser;
    }

    @Nullable
    @Override
    public SecurityPrincipal.Kind getNewPrincipalKind(@NotNull Class<?> folderChildrenClass) {
        return MySQLUser.class.isAssignableFrom(folderChildrenClass) ? SecurityPrincipal.Kind.USER : null;
    }

    @NotNull
    @Override
    public SecurityPrincipal loadPrincipal(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object) throws DBException {
        loadPrivilegeTypes(monitor);
        MySQLUser user = (MySQLUser) object;
        boolean[] role = new boolean[1];
        Map<String, Long> limits = new LinkedHashMap<>();
        query(monitor, "SELECT * FROM mysql.user WHERE User = ? AND Host = ?", //$NON-NLS-1$
            resultSet -> {
                role[0] = "Y".equalsIgnoreCase(JDBCUtils.safeGetString(resultSet, "is_role")); //$NON-NLS-1$ //$NON-NLS-2$
                for (Map.Entry<String, String> limit : LIMIT_COLUMNS.entrySet()) {
                    limits.put(limit.getKey(), JDBCUtils.safeGetLong(resultSet, limit.getValue()));
                }
            },
            user.getUserName(), user.getHost());
        SecurityPrincipal principal = new SecurityPrincipal(role[0] ? SecurityPrincipal.Kind.ROLE : SecurityPrincipal.Kind.USER,
            false, object, user.getUserName());
        principal.setHost(user.getHost());
        if (!role[0]) {
            principal.getLimits().putAll(limits);
        }
        if (!role[0] && supportsAccountLock()) {
            List<String> locked = mariaDB
                ? queryStrings(monitor, "SELECT JSON_VALUE(Priv, '$.account_locked') FROM mysql.global_priv WHERE User = ? AND Host = ?", //$NON-NLS-1$
                    user.getUserName(), user.getHost())
                : queryStrings(monitor, "SELECT account_locked FROM mysql.user WHERE User = ? AND Host = ?", user.getUserName(), user.getHost()); //$NON-NLS-1$
            String value = locked.isEmpty() ? null : locked.getFirst();
            principal.setEnabled(!("true".equalsIgnoreCase(value) || "1".equals(value) || "Y".equalsIgnoreCase(value))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (supportsRoles()) {
            for (String roleName : listRoles(monitor)) {
                if (!(role[0] && roleName.equals(user.getUserName()))) {
                    principal.getMemberships().put(roleName, false);
                }
            }
            query(monitor, "SELECT Role FROM mysql.roles_mapping WHERE User = ? AND Host = ?", //$NON-NLS-1$
                resultSet -> principal.getMemberships().put(resultSet.getString(1), true), user.getUserName(), user.getHost());
        }
        String grantee = "'" + user.getUserName() + "'@'" + user.getHost() + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        query(monitor, "SELECT TABLE_SCHEMA, PRIVILEGE_TYPE, IS_GRANTABLE FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE = ?", //$NON-NLS-1$
            resultSet -> principal.getPermissions().set(
                new Securable(Securable.Kind.DATABASE, resultSet.getString(1), null, resultSet.getString(1), null),
                resultSet.getString(2), readState(resultSet.getString(3))),
            grantee);
        query(monitor, "SELECT p.TABLE_SCHEMA, p.TABLE_NAME, p.PRIVILEGE_TYPE, p.IS_GRANTABLE, t.TABLE_TYPE " + //$NON-NLS-1$
                "FROM information_schema.TABLE_PRIVILEGES p LEFT JOIN information_schema.TABLES t " + //$NON-NLS-1$
                "ON t.TABLE_SCHEMA = p.TABLE_SCHEMA AND t.TABLE_NAME = p.TABLE_NAME WHERE p.GRANTEE = ?", //$NON-NLS-1$
            resultSet -> principal.getPermissions().set(
                new Securable("VIEW".equals(resultSet.getString(5)) ? Securable.Kind.VIEW : Securable.Kind.TABLE, //$NON-NLS-1$
                    resultSet.getString(1), null, resultSet.getString(2), null),
                resultSet.getString(3), readState(resultSet.getString(4))),
            grantee);
        query(monitor, "SELECT Db, Routine_name, Routine_type, Proc_priv FROM mysql.procs_priv WHERE User = ? AND Host = ?", //$NON-NLS-1$
            resultSet -> readRoutinePrivileges(principal.getPermissions(), new Securable(
                    "FUNCTION".equalsIgnoreCase(resultSet.getString(3)) ? Securable.Kind.FUNCTION : Securable.Kind.PROCEDURE, //$NON-NLS-1$
                    resultSet.getString(1), null, resultSet.getString(2), null),
                resultSet.getString(4)),
            user.getUserName(), user.getHost());
        return principal;
    }

    @NotNull
    @Override
    public SecurityPrincipal newPrincipal(@NotNull DBRProgressMonitor monitor, @NotNull SecurityPrincipal.Kind kind) throws DBException {
        loadPrivilegeTypes(monitor);
        SecurityPrincipal principal = new SecurityPrincipal(SecurityPrincipal.Kind.USER, true, null, ""); //$NON-NLS-1$
        principal.setHost("%"); //$NON-NLS-1$
        if (supportsAccountLock()) {
            principal.setEnabled(true);
        }
        // 0 = no limit, the server default of a new account
        for (String limit : LIMIT_COLUMNS.keySet()) {
            principal.getLimits().put(limit, 0L);
        }
        if (supportsRoles()) {
            for (String roleName : listRoles(monitor)) {
                principal.getMemberships().put(roleName, false);
            }
        }
        return principal;
    }

    @Override
    public boolean supportsPassword(@NotNull SecurityPrincipal principal) {
        return principal.getKind() == SecurityPrincipal.Kind.USER;
    }

    @Override
    public boolean supportsHost() {
        return true;
    }

    @Nullable
    @Override
    public EnabledStyle getEnabledStyle(@NotNull SecurityPrincipal principal) {
        return principal.getEnabled() == null ? null : EnabledStyle.NOT_LOCKED;
    }

    @NotNull
    @Override
    public MembershipStyle getMembershipStyle() {
        return MembershipStyle.ROLES;
    }

    @NotNull
    @Override
    public List<String> getPrivileges(@NotNull Securable.Kind kind) {
        return privileges.getOrDefault(kind, List.of());
    }

    @NotNull
    @Override
    public List<Securable> listSecurables(@NotNull DBRProgressMonitor monitor, @Nullable String database) throws DBException {
        loadPrivilegeTypes(monitor);
        List<Securable> securables = new ArrayList<>();
        query(monitor, "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN " + SYSTEM_SCHEMAS, //$NON-NLS-1$
            resultSet -> securables.add(new Securable(Securable.Kind.DATABASE, resultSet.getString(1), null, resultSet.getString(1), null)));
        query(monitor, "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA NOT IN " + SYSTEM_SCHEMAS, //$NON-NLS-1$
            resultSet -> securables.add(new Securable("VIEW".equals(resultSet.getString(3)) ? Securable.Kind.VIEW : Securable.Kind.TABLE, //$NON-NLS-1$
                resultSet.getString(1), null, resultSet.getString(2), null)));
        query(monitor, "SELECT ROUTINE_SCHEMA, ROUTINE_NAME, ROUTINE_TYPE FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA NOT IN " + SYSTEM_SCHEMAS, //$NON-NLS-1$
            resultSet -> securables.add(new Securable("FUNCTION".equals(resultSet.getString(3)) ? Securable.Kind.FUNCTION : Securable.Kind.PROCEDURE, //$NON-NLS-1$
                resultSet.getString(1), null, resultSet.getString(2), null)));
        securables.sort(null);
        return securables;
    }

    @NotNull
    @Override
    public PermissionGrid<String> loadObjectPermissions(@NotNull DBRProgressMonitor monitor, @NotNull Securable securable) throws DBException {
        loadPrivilegeTypes(monitor);
        PermissionGrid<String> grid = new PermissionGrid<>();
        if (securable.kind() == Securable.Kind.FUNCTION || securable.kind() == Securable.Kind.PROCEDURE) {
            query(monitor, "SELECT User, Host, Proc_priv FROM mysql.procs_priv WHERE Db = ? AND Routine_name = ? AND Routine_type = ?", //$NON-NLS-1$
                resultSet -> {
                    String grantee = formatAccount(resultSet.getString(1), resultSet.getString(2));
                    grid.addRow(grantee);
                    readRoutinePrivileges(grid, grantee, resultSet.getString(3));
                },
                securable.catalog(), securable.name(), securable.kind().name());
        } else {
            query(monitor, "SELECT GRANTEE, PRIVILEGE_TYPE, IS_GRANTABLE FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", //$NON-NLS-1$
                resultSet -> grid.set(normalizeGrantee(resultSet.getString(1)), resultSet.getString(2), readState(resultSet.getString(3))),
                securable.catalog(), securable.name());
        }
        return grid;
    }

    @NotNull
    @Override
    public List<String> listGrantees(@NotNull DBRProgressMonitor monitor, @NotNull Securable securable) throws DBException {
        List<String> grantees = new ArrayList<>();
        query(monitor, "SELECT * FROM mysql.user ORDER BY User, Host", //$NON-NLS-1$
            resultSet -> {
                String user = JDBCUtils.safeGetString(resultSet, "User"); //$NON-NLS-1$
                boolean role = "Y".equalsIgnoreCase(JDBCUtils.safeGetString(resultSet, "is_role")); //$NON-NLS-1$ //$NON-NLS-2$
                if (!CommonUtils.isEmpty(user)) {
                    grantees.add(role ? quoteString(user) : formatAccount(user, CommonUtils.notEmpty(JDBCUtils.safeGetString(resultSet, "Host")))); //$NON-NLS-1$
                }
            });
        return grantees;
    }

    @NotNull
    @Override
    public List<SecurityStatement> generateScript(@NotNull SecurityPrincipal original, @NotNull SecurityPrincipal edited) {
        List<SecurityStatement> statements = new ArrayList<>();
        boolean role = edited.getKind() == SecurityPrincipal.Kind.ROLE;
        String account = role ? quote(edited.getName()) : formatAccount(edited.getName(), CommonUtils.notEmpty(edited.getHost()));
        boolean alterUser = MySQLUtils.isAlterUSerSupported(dataSource);
        if (edited.isNew()) {
            String lock = Boolean.FALSE.equals(edited.getEnabled()) ? " ACCOUNT LOCK" : ""; //$NON-NLS-1$ //$NON-NLS-2$
            // CREATE USER takes WITH <limits> on the servers that have ALTER USER (the same versions), before the lock option
            String limits = formatLimits(Map.of(), edited.getLimits());
            if (alterUser && !limits.isEmpty()) {
                lock = " WITH" + limits + lock; //$NON-NLS-1$
            }
            if (CommonUtils.isEmpty(edited.getPassword())) {
                statements.add(SecurityStatement.of("CREATE USER " + account + lock)); //$NON-NLS-1$
            } else {
                statements.add(new SecurityStatement(
                    "CREATE USER " + account + " IDENTIFIED BY " + quoteString(edited.getPassword()) + lock, //$NON-NLS-1$ //$NON-NLS-2$
                    "CREATE USER " + account + " IDENTIFIED BY " + MASKED_PASSWORD + lock, //$NON-NLS-1$ //$NON-NLS-2$
                    null, true));
            }
            if (!alterUser && !limits.isEmpty()) {
                statements.add(SecurityStatement.of("GRANT USAGE ON *.* TO " + account + " WITH" + limits)); //$NON-NLS-1$ //$NON-NLS-2$
            }
        } else {
            if (!role && (!original.getName().equals(edited.getName()) || !CommonUtils.equalObjects(original.getHost(), edited.getHost()))) {
                statements.add(SecurityStatement.of("RENAME USER " + //$NON-NLS-1$
                    formatAccount(original.getName(), CommonUtils.notEmpty(original.getHost())) + " TO " + account)); //$NON-NLS-1$
            }
            if (!CommonUtils.isEmpty(edited.getPassword())) {
                if (alterUser) {
                    statements.add(new SecurityStatement(
                        "ALTER USER " + account + " IDENTIFIED BY " + quoteString(edited.getPassword()), //$NON-NLS-1$ //$NON-NLS-2$
                        "ALTER USER " + account + " IDENTIFIED BY " + MASKED_PASSWORD, //$NON-NLS-1$ //$NON-NLS-2$
                        null, true));
                } else {
                    statements.add(new SecurityStatement(
                        "SET PASSWORD FOR " + account + " = PASSWORD(" + quoteString(edited.getPassword()) + ")", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        "SET PASSWORD FOR " + account + " = PASSWORD(" + MASKED_PASSWORD + ")", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        null, true));
                }
            }
            if (edited.getEnabled() != null && !CommonUtils.equalObjects(original.getEnabled(), edited.getEnabled())) {
                statements.add(SecurityStatement.of("ALTER USER " + account + (edited.getEnabled() ? " ACCOUNT UNLOCK" : " ACCOUNT LOCK"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            String limits = formatLimits(original.getLimits(), edited.getLimits());
            if (!limits.isEmpty()) {
                statements.add(SecurityStatement.of((alterUser ? "ALTER USER " + account : "GRANT USAGE ON *.* TO " + account) //$NON-NLS-1$ //$NON-NLS-2$
                    + " WITH" + limits)); //$NON-NLS-1$
            }
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        diffFlags(original.getMemberships(), edited.getMemberships(), added, removed);
        for (String roleName : removed) {
            statements.add(SecurityStatement.of("REVOKE " + quote(roleName) + " FROM " + account)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String roleName : added) {
            statements.add(SecurityStatement.of("GRANT " + quote(roleName) + " TO " + account)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Set<Securable> securables = new LinkedHashSet<>(original.getPermissions().getRows());
        securables.addAll(edited.getPermissions().getRows());
        for (Securable securable : securables) {
            statements.addAll(diffPermissions(securable, account,
                original.getPermissions().getStates(securable), edited.getPermissions().getStates(securable), null));
        }
        return statements;
    }

    @NotNull
    @Override
    public List<SecurityStatement> generateDropScript(@NotNull SecurityPrincipal principal) {
        if (principal.getKind() == SecurityPrincipal.Kind.ROLE) {
            return List.of(SecurityStatement.of("DROP ROLE " + quote(principal.getName()))); //$NON-NLS-1$
        }
        return List.of(SecurityStatement.of("DROP USER " + formatAccount(principal.getName(), CommonUtils.notEmpty(principal.getHost())))); //$NON-NLS-1$
    }

    @NotNull
    @Override
    protected String formatGrantee(@NotNull String grantee) {
        // Grantee keys are already written as in GRANT: 'user'@'host' or 'role'
        return grantee;
    }

    @NotNull
    @Override
    protected String formatTarget(@NotNull Securable securable) {
        String database = quote(CommonUtils.notEmpty(securable.catalog()));
        return switch (securable.kind()) {
            case DATABASE, SCHEMA -> database + ".*"; //$NON-NLS-1$
            case FUNCTION -> "FUNCTION " + database + "." + quote(securable.name()); //$NON-NLS-1$ //$NON-NLS-2$
            case PROCEDURE -> "PROCEDURE " + database + "." + quote(securable.name()); //$NON-NLS-1$ //$NON-NLS-2$
            default -> database + "." + quote(securable.name()); //$NON-NLS-1$
        };
    }

    /**
     * MySQL keeps one grant option per account and object: the With Grant check boxes of an object act together.
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
        Set<String> granted = new LinkedHashSet<>(after.keySet());
        List<String> removed = new ArrayList<>(before.keySet());
        removed.removeAll(granted);
        List<String> added = new ArrayList<>(granted);
        added.removeAll(before.keySet());
        boolean wasWith = before.containsValue(PermissionGrid.State.GRANT_WITH);
        boolean isWith = after.containsValue(PermissionGrid.State.GRANT_WITH);
        String target = formatTarget(securable);
        List<SecurityStatement> statements = new ArrayList<>();
        if (!removed.isEmpty()) {
            statements.add(SecurityStatement.of(revokeSql(target, removed, grantee, false)));
        }
        if (wasWith && !isWith) {
            statements.add(SecurityStatement.of("REVOKE " + GRANT_OPTION + " ON " + target + " FROM " + grantee)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (isWith && !wasWith && !granted.isEmpty()) {
            statements.add(SecurityStatement.of(grantSql(target, new ArrayList<>(granted), grantee, true)));
        } else if (!added.isEmpty()) {
            statements.add(SecurityStatement.of(grantSql(target, added, grantee, isWith)));
        }
        return statements;
    }

    /**
     * The {@code WITH} options of the limits that differ from {@code before} (a new account: those that are not 0), with a leading space.
     */
    @NotNull
    private static String formatLimits(@NotNull Map<String, Long> before, @NotNull Map<String, Long> after) {
        StringBuilder options = new StringBuilder();
        for (Map.Entry<String, Long> limit : after.entrySet()) {
            long value = limit.getValue() == null ? 0 : limit.getValue();
            Long old = before.get(limit.getKey());
            if (old == null ? value != 0 : old != value) {
                options.append(' ').append(limit.getKey()).append(' ').append(value);
            }
        }
        return options.toString();
    }

    private boolean supportsAccountLock() {
        return mariaDB ? dataSource.isServerVersionAtLeast(10, 4) : dataSource.isServerVersionAtLeast(5, 7);
    }

    private boolean supportsRoles() {
        return mariaDB && dataSource.isServerVersionAtLeast(10, 0);
    }

    @NotNull
    private List<String> listRoles(@NotNull DBRProgressMonitor monitor) throws DBException {
        return queryStrings(monitor, "SELECT User FROM mysql.user WHERE is_role = 'Y' ORDER BY User"); //$NON-NLS-1$
    }

    /**
     * Privilege names by object type from {@code SHOW PRIVILEGES} (Context: Databases, Tables, Functions, Procedures).
     */
    private void loadPrivilegeTypes(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (!privileges.isEmpty()) {
            return;
        }
        List<String> database = new ArrayList<>();
        List<String> table = new ArrayList<>();
        List<String> routine = new ArrayList<>();
        for (MySQLPrivilege privilege : dataSource.getPrivileges(monitor)) {
            String name = privilege.getFixedPrivilegeName().toUpperCase(Locale.ENGLISH);
            if (name.equals(GRANT_OPTION)) {
                continue;
            }
            String context = privilege.getContext();
            boolean tables = context.contains("Tables"); //$NON-NLS-1$
            boolean routines = context.contains("Functions") || context.contains("Procedures"); //$NON-NLS-1$ //$NON-NLS-2$
            if (tables || routines || context.contains("Databases")) { //$NON-NLS-1$
                database.add(name);
            }
            if (tables) {
                table.add(name);
            }
            if (routines) {
                routine.add(name);
            }
        }
        privileges.put(Securable.Kind.DATABASE, database);
        privileges.put(Securable.Kind.TABLE, table);
        privileges.put(Securable.Kind.VIEW, table);
        privileges.put(Securable.Kind.FUNCTION, routine);
        privileges.put(Securable.Kind.PROCEDURE, routine);
    }

    /**
     * {@code mysql.procs_priv.Proc_priv} is a SET like {@code Execute,Alter Routine,Grant}; Grant is the grant option.
     */
    private static <K> void readRoutinePrivileges(@NotNull PermissionGrid<K> grid, @NotNull K row, @Nullable String privileges) {
        List<String> names = new ArrayList<>();
        boolean grantOption = false;
        for (String name : CommonUtils.notEmpty(privileges).split(",")) { //$NON-NLS-1$
            String privilege = name.trim().toUpperCase(Locale.ENGLISH);
            if (privilege.equals("GRANT")) { //$NON-NLS-1$
                grantOption = true;
            } else if (!privilege.isEmpty()) {
                names.add(privilege);
            }
        }
        for (String name : names) {
            grid.set(row, name, grantOption ? PermissionGrid.State.GRANT_WITH : PermissionGrid.State.GRANT);
        }
    }

    @NotNull
    private String formatAccount(@NotNull String user, @NotNull String host) {
        // A MariaDB role has an empty host and is written without it
        return host.isEmpty() ? quoteString(user) : quoteString(user) + "@" + quoteString(host); //$NON-NLS-1$
    }

    /**
     * information_schema writes grantees as {@code 'user'@'host'} and roles as {@code 'role'@''}.
     */
    @NotNull
    private static String normalizeGrantee(@NotNull String grantee) {
        return grantee.endsWith("@''") ? grantee.substring(0, grantee.length() - 3) : grantee; //$NON-NLS-1$
    }

    @NotNull
    private static PermissionGrid.State readState(@Nullable String grantable) {
        return "YES".equalsIgnoreCase(grantable) ? PermissionGrid.State.GRANT_WITH : PermissionGrid.State.GRANT; //$NON-NLS-1$
    }
}
