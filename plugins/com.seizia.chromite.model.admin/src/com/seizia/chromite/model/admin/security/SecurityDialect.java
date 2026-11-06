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
import org.jkiss.dbeaver.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.mssql.model.SQLServerDataSource;
import org.jkiss.dbeaver.ext.mysql.model.MySQLDataSource;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDataSource;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCTransactionManager;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.DBSInstance;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.rdb.DBSCatalog;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedure;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureType;
import org.jkiss.dbeaver.model.struct.rdb.DBSSchema;
import org.jkiss.dbeaver.model.struct.rdb.DBSSequence;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.model.struct.rdb.DBSView;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Database specific part of the security editors: reads logins, users, roles and permissions, and turns the edits into SQL.
 *
 * 설계: SWT에서 이 일은 드라이버마다 따로 흩어져 있다. PostgreSQL은 모델의 {@code PostgreRoleManager}·{@code PostgreCommandGrantPrivilege},
 * MySQL은 SWT 플러그인({@code ext.mysql.ui})의 {@code MySQLUserManager}·{@code MySQLCommandChangeUser}·{@code MySQLCommandGrantPrivilege},
 * SQL Server는 로그인 만들기·켜기·끄기·지우기만 있는 {@code SQLServerLoginManager}다(데이터베이스 사용자, 역할, 권한 모델이 없다).
 * Swing 제품에는 SWT 플러그인이 없고, 모델의 명령 묶음({@code AbstractCommandContext})은 명령 대상 객체마다 객체 관리자를 찾으므로
 * ({@code AbstractCommandContext.java:600-603}) 관리자가 없는 MySQL 사용자·SQL Server 데이터베이스 사용자 변경을 담을 수 없다.
 * 그래서 이 클래스는 SSMS 속성 창처럼 "읽은 상태 → 고친 상태"의 차이를 SQL 문 목록으로 만들고, 문 모양은 위 DBeaver 클래스가 만드는 것과
 * 같게 한다(각 하위 클래스 주석에 근거 줄을 적었다). 읽기는 매번 시스템 카탈로그를 직접 조회한다: 모델 캐시는 이 창이 바꾼 뒤 낡고,
 * 모델의 권한 읽기는 DB마다 빠진 정보가 있다(PostgreSQL 스키마·시퀀스 권한의 WITH GRANT OPTION, MySQL {@code SHOW GRANTS}의 대문자 이름).
 * 권한 종류 목록은 모델의 것을 쓴다(PostgreSQL {@code PostgrePrivilegeType}, MySQL {@code SHOW PRIVILEGES}).
 * <p>
 * 실행은 연결의 격리된 실행 문맥({@link DBSInstance#openIsolatedContext})에서 한다. SQL Server의 {@code USE}가 사용자 편집기·탐색기의
 * 연결을 바꾸지 않게 하기 위해서다. 암호가 든 문은 쿼리 관리자 기록을 끄고 실행한다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 */
public abstract class SecurityDialect {

    /**
     * Shown instead of a password in scripts.
     */
    protected static final String MASKED_PASSWORD = "'********'"; //$NON-NLS-1$

    /**
     * Meaning of {@link SecurityPrincipal#getEnabled()} in this database (the label of its check box).
     */
    public enum EnabledStyle {
        /** SQL Server: login enabled (ALTER LOGIN ENABLE / DISABLE) */
        ENABLED,
        /** PostgreSQL: LOGIN / NOLOGIN */
        CAN_LOGIN,
        /** MySQL/MariaDB: ACCOUNT UNLOCK / ACCOUNT LOCK (the check box is "locked", the value is "not locked") */
        NOT_LOCKED
    }

    /**
     * What the membership page lists.
     */
    public enum MembershipStyle {
        /** SQL Server fixed and user-defined server roles */
        SERVER_ROLES,
        /** PostgreSQL roles the role is a member of */
        MEMBER_OF,
        /** MySQL/MariaDB roles granted to the account */
        ROLES
    }

    /**
     * A failed statement of {@link #execute}; the message is the database error.
     */
    public static final class ExecutionException extends DBDatabaseException {
        @NotNull
        private final SecurityStatement statement;

        ExecutionException(@NotNull SecurityStatement statement, @NotNull Throwable cause, @NotNull DBPDataSource dataSource) {
            super(cause.getMessage(), cause, dataSource);
            this.statement = statement;
        }

        @NotNull
        public SecurityStatement getStatement() {
            return statement;
        }
    }

    /**
     * Reads one row of a query result.
     */
    @FunctionalInterface
    protected interface RowReader {
        void read(@NotNull JDBCResultSet resultSet) throws SQLException, DBException;
    }

    @NotNull
    protected final DBSObject context;

    protected SecurityDialect(@NotNull DBSObject context) {
        this.context = context;
    }

    /**
     * Security editor support of the object's database.
     *
     * @param context an object of the connection; for PostgreSQL its database is where object permissions are read and changed
     * @return null if the database is not supported
     */
    @Nullable
    public static SecurityDialect forObject(@Nullable DBSObject context) {
        if (context == null) {
            return null;
        }
        DBPDataSource dataSource = context.getDataSource();
        if (dataSource instanceof PostgreDataSource postgre && postgre.getServerType().supportsRoles()) {
            return new PostgreSecurityDialect(context, postgre);
        }
        if (dataSource instanceof SQLServerDataSource sqlServer) {
            return new SQLServerSecurityDialect(context, sqlServer);
        }
        if (dataSource instanceof MySQLDataSource mysql && mysql.supportsUserManagement()) {
            return new MySQLSecurityDialect(context, mysql);
        }
        return null;
    }

    @NotNull
    public DBPDataSource getDataSource() {
        return context.getDataSource();
    }

    // Principals

    /**
     * Whether the object is a login, user or role this editor can open.
     */
    public abstract boolean isPrincipal(@NotNull DBSObject object);

    /**
     * What "New …" creates in a navigator folder whose children are of this class, or null if the folder holds no principals.
     */
    @Nullable
    public abstract SecurityPrincipal.Kind getNewPrincipalKind(@NotNull Class<?> folderChildrenClass);

    /**
     * Reads a login, user or role with its memberships, user mappings and explicit permissions.
     */
    @NotNull
    public abstract SecurityPrincipal loadPrincipal(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object) throws DBException;

    /**
     * A new principal with the database defaults (and the role lists filled, nothing selected).
     */
    @NotNull
    public abstract SecurityPrincipal newPrincipal(@NotNull DBRProgressMonitor monitor, @NotNull SecurityPrincipal.Kind kind) throws DBException;

    public abstract boolean supportsPassword(@NotNull SecurityPrincipal principal);

    public boolean supportsHost() {
        return false;
    }

    public boolean supportsRename(@NotNull SecurityPrincipal principal) {
        return true;
    }

    @Nullable
    public abstract EnabledStyle getEnabledStyle(@NotNull SecurityPrincipal principal);

    @NotNull
    public abstract MembershipStyle getMembershipStyle();

    /**
     * SQL Server: DENY exists (other databases only grant and revoke).
     */
    public boolean supportsDeny() {
        return false;
    }

    /**
     * SQL Server: logins have users in databases (User Mapping page), and object permissions are per database.
     */
    public boolean supportsUserMapping() {
        return false;
    }

    /**
     * Whether the principal has a Permissions page (SQL Server: logins, through their database users; not server roles).
     */
    public boolean supportsPermissions(@NotNull SecurityPrincipal principal) {
        return true;
    }

    /**
     * Names of the schemas of a database (SQL Server default schema of a mapped user).
     */
    @NotNull
    public List<String> listSchemas(@NotNull DBRProgressMonitor monitor, @NotNull String database) throws DBException {
        return List.of();
    }

    /**
     * Databases to choose from (SQL Server default database and user mapping), or an empty list.
     */
    @NotNull
    public List<String> listDatabases(@NotNull DBRProgressMonitor monitor) throws DBException {
        return List.of();
    }

    // Permissions

    /**
     * Privileges that can be granted on objects of this type, in display order.
     */
    @NotNull
    public abstract List<String> getPrivileges(@NotNull Securable.Kind kind);

    /**
     * Objects that permissions can be granted on ("Add Objects…").
     *
     * @param database SQL Server: the database to list; other databases: ignored (the connection's database or all schemas)
     */
    @NotNull
    public abstract List<Securable> listSecurables(@NotNull DBRProgressMonitor monitor, @Nullable String database) throws DBException;

    /**
     * The securable of a navigator object, or null if permissions cannot be edited on it.
     */
    @Nullable
    public Securable toSecurable(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object) throws DBException {
        Securable.Kind kind = getSecurableKind(object);
        if (kind == null) {
            return null;
        }
        DBSCatalog catalog = DBUtils.getParentOfType(DBSCatalog.class, object);
        DBSSchema schema = DBUtils.getParentOfType(DBSSchema.class, object);
        return new Securable(kind, catalog == null ? null : catalog.getName(), schema == null ? null : schema.getName(), object.getName(), null);
    }

    /**
     * Whether "Permissions…" is offered for the object (no database access).
     */
    public boolean canEditPermissions(@NotNull DBSObject object) {
        return getSecurableKind(object) != null;
    }

    @Nullable
    protected Securable.Kind getSecurableKind(@NotNull DBSObject object) {
        if (object instanceof DBSView) {
            return Securable.Kind.VIEW;
        } else if (object instanceof DBSSequence) {
            return Securable.Kind.SEQUENCE;
        } else if (object instanceof DBSTable) {
            return Securable.Kind.TABLE;
        } else if (object instanceof DBSProcedure procedure) {
            return procedure.getProcedureType() == DBSProcedureType.FUNCTION ? Securable.Kind.FUNCTION : Securable.Kind.PROCEDURE;
        }
        return null;
    }

    /**
     * Explicit permissions on an object by grantee.
     */
    @NotNull
    public abstract PermissionGrid<String> loadObjectPermissions(@NotNull DBRProgressMonitor monitor, @NotNull Securable securable) throws DBException;

    /**
     * Users and roles that permissions on the object can be granted to.
     */
    @NotNull
    public abstract List<String> listGrantees(@NotNull DBRProgressMonitor monitor, @NotNull Securable securable) throws DBException;

    // Scripts

    /**
     * Statements that turn the principal as read ({@code original}) into the edited one (for a new principal: create it).
     */
    @NotNull
    public abstract List<SecurityStatement> generateScript(@NotNull SecurityPrincipal original, @NotNull SecurityPrincipal edited) throws DBException;

    /**
     * Statements that drop the principal.
     */
    @NotNull
    public abstract List<SecurityStatement> generateDropScript(@NotNull SecurityPrincipal principal);

    /**
     * Statements that change the permissions on one object by grantee.
     */
    @NotNull
    public List<SecurityStatement> generateObjectScript(
        @NotNull Securable securable,
        @NotNull PermissionGrid<String> original,
        @NotNull PermissionGrid<String> edited
    ) {
        List<SecurityStatement> statements = new ArrayList<>();
        Set<String> grantees = new LinkedHashSet<>(original.getRows());
        grantees.addAll(edited.getRows());
        for (String grantee : grantees) {
            statements.addAll(diffPermissions(securable, formatGrantee(grantee), original.getStates(grantee), edited.getStates(grantee),
                getStatementDatabase(securable)));
        }
        return statements;
    }

    /**
     * The script text: statements end with {@code ;}, database switches are shown as {@code USE}.
     */
    @NotNull
    public String formatScript(@NotNull List<SecurityStatement> statements) {
        StringBuilder script = new StringBuilder();
        String database = null;
        for (SecurityStatement statement : statements) {
            if (statement.database() != null && !statement.database().equals(database)) {
                database = statement.database();
                script.append(formatUse(database)).append(";\n"); //$NON-NLS-1$
            }
            script.append(statement.displaySql()).append(";\n"); //$NON-NLS-1$
        }
        return script.toString();
    }

    /**
     * Runs the statements in an isolated connection (committed at the end when the connection is not in auto-commit mode).
     */
    public void execute(@NotNull DBRProgressMonitor monitor, @NotNull List<SecurityStatement> statements) throws DBException {
        DBSInstance instance = DBUtils.getObjectOwnerInstance(context);
        if (instance == null) {
            throw new DBException("Not connected to " + getDataSource().getContainer().getName()); //$NON-NLS-1$
        }
        DBCExecutionContext executionContext = instance.openIsolatedContext(monitor, "Security changes", null); //$NON-NLS-1$
        try (JDBCSession session = (JDBCSession) executionContext.openSession(monitor, DBCExecutionPurpose.UTIL, "Apply security changes")) { //$NON-NLS-1$
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(executionContext);
            boolean commit = txnManager != null && txnManager.isSupportsTransactions() && !txnManager.isAutoCommit();
            monitor.beginTask("Apply security changes", statements.size()); //$NON-NLS-1$
            String database = null;
            SecurityStatement current = null;
            try {
                for (SecurityStatement statement : statements) {
                    if (monitor.isCanceled()) {
                        break;
                    }
                    current = statement;
                    monitor.subTask(statement.displaySql());
                    if (statement.database() != null && !statement.database().equals(database)) {
                        database = statement.database();
                        JDBCUtils.executeStatement(session, formatUse(database));
                    }
                    session.enableLogging(!statement.secret());
                    try {
                        JDBCUtils.executeStatement(session, statement.sql());
                    } finally {
                        session.enableLogging(true);
                    }
                    monitor.worked(1);
                }
                if (commit) {
                    txnManager.commit(session);
                }
            } catch (SQLException e) {
                if (commit) {
                    txnManager.rollback(session, null);
                }
                throw current == null ? new DBDatabaseException(e, getDataSource()) : new ExecutionException(current, e, getDataSource());
            } finally {
                monitor.done();
            }
        } finally {
            executionContext.close();
        }
    }

    // Helpers for the implementations

    /**
     * Statement that switches the database ({@link SecurityStatement#database()}).
     */
    @NotNull
    protected String formatUse(@NotNull String database) {
        return "USE " + DBUtils.getQuotedIdentifier(getDataSource(), database); //$NON-NLS-1$
    }

    /**
     * Database the object permission statements of the securable run in (SQL Server), or null.
     */
    @Nullable
    protected String getStatementDatabase(@NotNull Securable securable) {
        return null;
    }

    /**
     * Grantee as written in GRANT statements.
     */
    @NotNull
    protected String formatGrantee(@NotNull String grantee) {
        return DBUtils.getQuotedIdentifier(getDataSource(), grantee);
    }

    /**
     * Securable as written after {@code ON} (empty for SQL Server database permissions, which have no ON clause).
     */
    @NotNull
    protected abstract String formatTarget(@NotNull Securable securable);

    @NotNull
    protected String quote(@NotNull String identifier) {
        return DBUtils.getQuotedIdentifier(getDataSource(), identifier);
    }

    @NotNull
    protected String quoteString(@NotNull String value) {
        return SQLUtils.quoteString(getDataSource(), value);
    }

    /**
     * GRANT / REVOKE / DENY statements that turn the privileges of a grantee on a securable from {@code before} into {@code after}.
     * Privileges with the same change share one statement. Revokes come first so that a grant option removal precedes re-grants.
     */
    @NotNull
    protected List<SecurityStatement> diffPermissions(
        @NotNull Securable securable,
        @NotNull String grantee,
        @NotNull Map<String, PermissionGrid.State> before,
        @NotNull Map<String, PermissionGrid.State> after,
        @Nullable String database
    ) {
        List<String> revoke = new ArrayList<>();
        List<String> revokeCascade = new ArrayList<>();
        List<String> revokeGrantOption = new ArrayList<>();
        List<String> grant = new ArrayList<>();
        List<String> grantWith = new ArrayList<>();
        List<String> deny = new ArrayList<>();
        List<String> denyCascade = new ArrayList<>();
        Set<String> privileges = new LinkedHashSet<>(before.keySet());
        privileges.addAll(after.keySet());
        for (String privilege : privileges) {
            PermissionGrid.State oldState = before.getOrDefault(privilege, PermissionGrid.State.NONE);
            PermissionGrid.State newState = after.getOrDefault(privilege, PermissionGrid.State.NONE);
            if (oldState == newState) {
                continue;
            }
            switch (newState) {
                case NONE -> (oldState == PermissionGrid.State.GRANT_WITH ? revokeCascade : revoke).add(privilege);
                case GRANT -> (oldState == PermissionGrid.State.GRANT_WITH ? revokeGrantOption : grant).add(privilege);
                case GRANT_WITH -> grantWith.add(privilege);
                case DENY -> (oldState == PermissionGrid.State.GRANT_WITH ? denyCascade : deny).add(privilege);
            }
        }
        String target = formatTarget(securable);
        List<SecurityStatement> statements = new ArrayList<>();
        if (!revokeGrantOption.isEmpty()) {
            statements.add(SecurityStatement.inDatabase(database, revokeGrantOptionSql(target, revokeGrantOption, grantee)));
        }
        if (!revoke.isEmpty()) {
            statements.add(SecurityStatement.inDatabase(database, revokeSql(target, revoke, grantee, false)));
        }
        if (!revokeCascade.isEmpty()) {
            statements.add(SecurityStatement.inDatabase(database, revokeSql(target, revokeCascade, grantee, true)));
        }
        if (!grant.isEmpty()) {
            statements.add(SecurityStatement.inDatabase(database, grantSql(target, grant, grantee, false)));
        }
        if (!grantWith.isEmpty()) {
            statements.add(SecurityStatement.inDatabase(database, grantSql(target, grantWith, grantee, true)));
        }
        if (!deny.isEmpty()) {
            statements.add(SecurityStatement.inDatabase(database, denySql(target, deny, grantee, false)));
        }
        if (!denyCascade.isEmpty()) {
            statements.add(SecurityStatement.inDatabase(database, denySql(target, denyCascade, grantee, true)));
        }
        return statements;
    }

    @NotNull
    protected String grantSql(@NotNull String target, @NotNull List<String> privileges, @NotNull String grantee, boolean withGrantOption) {
        return "GRANT " + String.join(", ", privileges) + onClause(target) + " TO " + grantee //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (withGrantOption ? " WITH GRANT OPTION" : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    protected String revokeSql(@NotNull String target, @NotNull List<String> privileges, @NotNull String grantee, boolean cascade) {
        return "REVOKE " + String.join(", ", privileges) + onClause(target) + " FROM " + grantee //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + (cascade ? " CASCADE" : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    protected String revokeGrantOptionSql(@NotNull String target, @NotNull List<String> privileges, @NotNull String grantee) {
        return "REVOKE GRANT OPTION FOR " + String.join(", ", privileges) + onClause(target) + " FROM " + grantee + " CASCADE"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @NotNull
    protected String denySql(@NotNull String target, @NotNull List<String> privileges, @NotNull String grantee, boolean cascade) {
        throw new IllegalStateException("DENY is not supported by " + getDataSource().getContainer().getDriver().getName()); //$NON-NLS-1$
    }

    @NotNull
    protected static String onClause(@NotNull String target) {
        return target.isEmpty() ? "" : " ON " + target; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Runs a catalog query in the metadata connection.
     */
    protected void query(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String sql,
        @NotNull RowReader reader,
        @NotNull Object... parameters
    ) throws DBException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, context, "Read security information")) { //$NON-NLS-1$
            try (JDBCPreparedStatement statement = session.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setObject(i + 1, parameters[i]);
                }
                try (JDBCResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        reader.read(resultSet);
                    }
                }
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, getDataSource());
        }
    }

    /**
     * First column of every row of a catalog query.
     */
    @NotNull
    protected List<String> queryStrings(@NotNull DBRProgressMonitor monitor, @NotNull String sql, @NotNull Object... parameters) throws DBException {
        List<String> values = new ArrayList<>();
        query(monitor, sql, resultSet -> values.add(resultSet.getString(1)), parameters);
        return values;
    }

    /**
     * Membership changes: the roles switched on and off between two states.
     */
    protected static void diffFlags(
        @NotNull Map<String, Boolean> before,
        @NotNull Map<String, Boolean> after,
        @NotNull List<String> added,
        @NotNull List<String> removed
    ) {
        for (Map.Entry<String, Boolean> entry : after.entrySet()) {
            boolean was = Boolean.TRUE.equals(before.get(entry.getKey()));
            boolean is = Boolean.TRUE.equals(entry.getValue());
            if (is && !was) {
                added.add(entry.getKey());
            } else if (!is && was) {
                removed.add(entry.getKey());
            }
        }
    }
}
