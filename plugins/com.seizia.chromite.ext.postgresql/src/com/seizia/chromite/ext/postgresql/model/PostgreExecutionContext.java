/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.ext.postgresql.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.postgresql.PostgreConstants;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.connection.DBPConnectionBootstrap;
import com.seizia.chromite.model.exec.*;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PostgreExecutionContext
 *
 * 설계: {@link #refreshDefaults}는 세션의 실제 search_path·현재 스키마를 읽기만 한다. 쿼리 창이 실행 뒤마다 부르므로
 * (docs/chromite/swing-sql-editor-session-ui.md "현재 DB 따라가기") 읽기가 실패해도 알던 값을 지우지 않고, 연결 설정의 역할(SET ROLE)은
 * 세션을 시작할 때만 정한다. 수동 커밋 세션에 트랜잭션이 없을 때는 BEGIN 없이 읽는다: 그냥 읽으면 pgjdbc가 BEGIN을 먼저 보내, 사용자가 커밋한
 * 뒤에도 앱이 연 트랜잭션이 세션에 남아 스냅숏(xmin)과 카탈로그 잠금을 쥔다(앱이 사용자 세션에 묻지 않은 트랜잭션을 여는 것).
 * <p>
 * 기본 스키마는 OID가 아니라 이름({@code activeSchemaName})으로 기억하고 개체는 메모리에 있는 스키마 목록에서만 찾는다. 세션을 여는 길
 * ({@code PostgreDataSource.initializeContextState}: 연결, Invalidate/Reconnect, 쿼리 창의 새 세션)은 스키마 목록을 읽지 않는다
 * ({@link #refreshDefaults(DBRProgressMonitor, boolean, boolean)}). Invalidate/Reconnect는 인스턴스의 배타 잠금을 쥔 채 이 길을 지나는데, 목록을
 * 읽는 다른 스레드는 목록 캐시의 모니터를 쥔 채 닫힌 메타데이터 세션을 다시 열려고 그 잠금을 기다린다. 목록이 없을 때는 이름만 두고, 목록을
 * 읽으면 {@link #getDefaultSchema}가 다시 묻지 않고 그 이름의 개체를 돌려준다. 쿼리 창이 실행 뒤에 부르는 {@link #refreshDefaults}는 잠금
 * 밖이라 전처럼 목록을 읽는다(다른 세션이 만든 스키마 포함).
 *
 * 변경 이력:
 * - 2026-11-11 refreshDefaults: 읽은 뒤에 값을 바꿈(중단된 트랜잭션에서 읽기 실패 시 활성 스키마가 사라지던 것), SET ROLE은 시작할 때만(앱 변경 #33)
 * - 2026-11-12 setActiveSchema: 서버의 current_schema()가 캐시에 없으면(다른 세션이 만든 스키마) 스키마 목록을 다시 읽음. 그 스키마로
 *   SET search_path 하면 쿼리 창이 예전 스키마(public)를 보였다(앱 변경 #79, docs/chromite/swing-sql-editor-session-ui.md "캐시에 없는 현재 DB")
 * - 2026-11-15 refreshDefaults: 트랜잭션이 없는 수동 커밋 세션에서는 BEGIN 없이 읽음({@link #queryWithoutBegin}). 쿼리 창이 커밋·롤백 뒤,
 *   DDL 뒤 새로 고침 뒤에 기본 스키마를 읽으면 세션이 idle in transaction으로 남아 xmin(VACUUM이 지우지 못함)과 pg_settings 잠금을 쥐었고,
 *   상태 줄은 아무것도 보이지 않았다(앱 변경 #111, docs/chromite/idle-transactions.md "앱이 사용자 세션에 연 트랜잭션")
 * - 2026-11-22 기본 스키마를 이름으로 기억({@code activeSchemaId} 삭제), 세션을 여는 길은 스키마 목록을 읽지 않음, {@link #setDefaultSchemaFrom}.
 *   Invalidate/Reconnect가 배타 잠금을 쥔 채 {@code setActiveSchema}의 목록 읽기에서, 목록을 쥔 채 그 잠금을 기다리던 배경 작업(쿼리 창
 *   자동 완성)과 서로 기다려 연결이 앱을 다시 시작할 때까지 멈췄다(앱 변경 #170, docs/chromite/cancel-busy-connection.md "세션을 다시 열 때의 교착")
 * - 2026-11-28 격리 문맥(쿼리 창의 자기 세션 등)의 DB 바꾸기는 {@code JDBCExecutionContext.reconnectTo}로 다시 연결. 전에는 끊고 다시 연
 *   세션을 쿼리 관리자가 추적하지 않아 그 뒤 트랜잭션이 앱에 보이지 않았고, 문맥은 처음 DB의 문맥 목록에 남았다
 *   (docs/chromite/swing-sql-editor-session-ui.md "자기 세션을 다른 DB로 다시 연결할 때")
 */
public class PostgreExecutionContext extends JDBCExecutionContext implements DBCExecutionContextDefaults<PostgreDatabase, PostgreSchema> {
    private final List<String> searchPath = new ArrayList<>();
    private String activeUser;
    // The server's current schema (current_schema()) or the schema Chromite put first in search_path
    @Nullable
    private String activeSchemaName;
    private boolean isolatedContext;

    public PostgreExecutionContext(@NotNull PostgreDatabase database, @NotNull String purpose) {
        super(database, purpose);
    }

    @NotNull
    @Override
    public PostgreDataSource getDataSource() {
        return (PostgreDataSource) super.getDataSource();
    }

    @Nullable
    @Override
    public PostgreExecutionContext getContextDefaults() {
        return this;
    }

    @NotNull
    @Override
    public PostgreDatabase getDefaultCatalog() {
        // Get value from cached, used in getCachedDefault()
        return (PostgreDatabase) getOwnerInstance();
    }

    @Nullable
    @Override
    public PostgreSchema getDefaultSchema() {
        // From the schema list in memory only, used in getCachedDefault()
        PostgreDatabase database = getDefaultCatalog();
        if (activeSchemaName != null) {
            PostgreSchema schema = database.schemaCache.getCachedObject(activeSchemaName);
            if (schema != null) {
                return schema;
            }
        }
        if (!database.schemaCache.isFullyCached()) {
            // Not read yet: whether the current schema is listed is not known
            return null;
        }
        // The current schema is not listed (hidden by a filter): the first listed schema of the search path
        for (String schemaName : searchPath) {
            PostgreSchema schema = database.schemaCache.getCachedObject(schemaName);
            if (schema != null) {
                return schema;
            }
        }
        return null;
    }

    /**
     * The name of the default schema: the schema {@link #getDefaultSchema} finds while the schema list is in memory,
     * else the name this session was given (the list is not read yet).
     */
    @Nullable
    private String getDefaultSchemaName() {
        PostgreSchema schema = getDefaultSchema();
        if (schema != null) {
            return schema.getName();
        }
        return getDefaultCatalog().schemaCache.isFullyCached() ? null : activeSchemaName;
    }

    @Override
    public boolean supportsCatalogChange() {
        return true;
    }

    @Override
    public boolean supportsSchemaChange() {
        return true;
    }

    @Override
    public void setDefaultCatalog(
        @NotNull DBRProgressMonitor monitor,
        @NotNull PostgreDatabase catalog,
        @Nullable PostgreSchema schema
    ) throws DBCException {
        setDefaultCatalog(monitor, catalog, schema, false);
    }

    void setDefaultCatalog(
        @NotNull DBRProgressMonitor monitor,
        @NotNull PostgreDatabase catalog,
        @Nullable PostgreSchema schema,
        boolean force
    ) throws DBCException {
        try {
            catalog.checkInstanceConnection(monitor);

            DBSObject oldInstance = getOwnerInstance();
            boolean catalogChanged = false, schemaChanged = false;
            if (oldInstance != catalog) {
                // Changing catalog means reconnect
                // Change it only for isolated editor contexts
                if (isolatedContext) {
                    reconnectTo(monitor, catalog);
                } else {
                    getDataSource().setActiveDatabase(catalog, this);
                }
                catalogChanged = true;
            }
            if (schema != null) {
                if (catalogChanged && !isolatedContext) {
                    // Catalog has been changed. Get the new one and change schema there
                    PostgreDatabase newInstance = getDataSource().getDefaultInstance();
                    PostgreExecutionContext newContext = (PostgreExecutionContext) newInstance.getDefaultContext(false);
                    newContext.changeDefaultSchema(monitor, schema, true, force);
                } else {
                    schemaChanged = changeDefaultSchema(monitor, schema, true, force);
                }
            }
            if (catalogChanged || schemaChanged) {
                DBUtils.fireObjectSelectionChange(oldInstance, catalog, this);
            }
        } catch (DBException e) {
            throw new DBCException("Error changing default database", e);
        }
    }

    @Override
    public void setDefaultSchema(@NotNull DBRProgressMonitor monitor, @NotNull PostgreSchema schema) throws DBCException {
        setDefaultCatalog(monitor, schema.getDatabase(), schema, false);
    }

    private boolean changeDefaultSchema(
        @NotNull DBRProgressMonitor monitor,
        @NotNull PostgreSchema schema,
        boolean reflect,
        boolean force
    ) throws DBException {
        var oldActiveSchema = getDefaultSchema();
        if (oldActiveSchema != null && oldActiveSchema.getObjectId() == schema.getObjectId() && !force) {
            return false;
        }
        if (schema.isExternal() || schema.isSystem()) {
            return false;
        }

        setSearchPath(monitor, schema.getName());

        if (reflect) {
            DBUtils.fireObjectSelectionChange(oldActiveSchema, schema, this);
        }

        return true;
    }

    @Override
    public boolean refreshDefaults(@NotNull DBRProgressMonitor monitor, boolean useBootstrapSettings) throws DBException {
        return refreshDefaults(monitor, useBootstrapSettings, true);
    }

    /**
     * Reads the session's search path, current schema and user.
     *
     * @param readSchemaList false on the path that opens a session (see the class comment): the schema list is not read,
     *                       the current schema is kept by name until it is
     */
    boolean refreshDefaults(@NotNull DBRProgressMonitor monitor, boolean useBootstrapSettings, boolean readSchemaList) throws DBException {
        // Read first, then replace: a read that fails (a transaction aborted by an error) keeps the values known before
        // instead of leaving no active schema. The query window reads the defaults again after its runs.
        String searchPathStr;
        List<String> result;
        Connection idleConnection = getIdleManualCommitConnection();
        if (idleConnection != null) {
            try {
                List<String> searchPathRow = queryWithoutBegin(idleConnection, getDataSource().getServerType().getSearchPathQuery());
                searchPathStr = searchPathRow.isEmpty() ? "" : CommonUtils.notEmpty(searchPathRow.getFirst());
                result = queryWithoutBegin(idleConnection, "SELECT current_schema(),session_user");
            } catch (SQLException e) {
                throw new DBCException(e, this);
            }
        } else {
            try (var session = openSession(monitor, DBCExecutionPurpose.META, "Read context defaults")) {
                monitor.subTask("Retrieve active search path");

                searchPathStr = CommonUtils.notEmpty(JDBCUtils.queryString(
                    session,
                    getDataSource().getServerType().getSearchPathQuery()
                ));

                monitor.subTask("Retrieve active schema and user");

                result = JDBCUtils.queryStrings(session, "SELECT current_schema(),session_user");
            } catch (SQLException e) {
                throw new DBCException(e, this);
            }
        }
        this.activeUser = null;
        this.searchPath.clear();
        for (String str : searchPathStr.split(",")) {
            searchPath.add(DBUtils.getUnQuotedIdentifier(getDataSource(), str.trim()));
        }
        setActiveSchema(monitor, result.getFirst(), readSchemaList);
        setActiveUser(result.getLast());

        if (useBootstrapSettings) {
            DBPConnectionBootstrap bootstrap = getBootstrapSettings();
            String bsSchemaName = bootstrap.getDefaultSchemaName();
            if (!CommonUtils.isEmpty(bsSchemaName)) {
                setSearchPath(monitor, bsSchemaName);
            }
            // Only when the session starts: a later read of the defaults must not undo the user's own SET ROLE
            setSessionRole(monitor);
        }
        return true;
    }

    /**
     * The session's pgjdbc connection when it is in manual commit and has no transaction (the server's ReadyForQuery
     * status, no round trip), else null. A query there would make the driver send BEGIN first, and the session would stay
     * idle in transaction after the read, holding a snapshot (xmin) and catalog locks the user never asked for.
     */
    @Nullable
    private Connection getIdleManualCommitConnection() {
        Connection connection = getConnectionOrNull();
        if (connection == null) {
            return null;
        }
        try {
            if (connection.getAutoCommit()) {
                return null;
            }
            Object state = connection.getClass().getMethod("getTransactionState").invoke(connection); //$NON-NLS-1$
            return state != null && "IDLE".equals(state.toString()) ? connection : null; //$NON-NLS-1$
        } catch (SQLException | ReflectiveOperationException | RuntimeException e) {
            // Not pgjdbc, or closed: the usual read
            return null;
        }
    }

    /**
     * Runs a query without letting pgjdbc begin a transaction ({@code BaseConnection.execSQLQuery}, the driver's own
     * path for its internal queries, which suppresses BEGIN) and returns the columns of its first row. Not recorded by
     * the query manager, like the transaction probe.
     */
    @NotNull
    private static List<String> queryWithoutBegin(@NotNull Connection connection, @NotNull String query) throws SQLException {
        ResultSet resultSet;
        try {
            resultSet = (ResultSet) connection.getClass().getMethod("execSQLQuery", String.class).invoke(connection, query); //$NON-NLS-1$
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("Can't read the session defaults", e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new SQLException("Can't read the session defaults", e);
        }
        try (Statement statement = resultSet.getStatement(); resultSet) {
            List<String> values = new ArrayList<>();
            if (resultSet.next()) {
                for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                    values.add(resultSet.getString(i));
                }
            }
            return values;
        }
    }

    private void setActiveSchema(
        @NotNull DBRProgressMonitor monitor,
        @Nullable String activeSchemaName,
        boolean readSchemaList
    ) throws DBException {
        if (readSchemaList) {
            // Pre-cache schemas, we need them anyway
            getDefaultCatalog().getSchemas(monitor);
            if (CommonUtils.isNotEmpty(activeSchemaName)) {
                // A schema created after the list was read (another session) is read too: the server says it is the current one
                getDefaultCatalog().schemaCache.getObjectReadingNew(monitor, getDefaultCatalog(), activeSchemaName);
            }
        }
        // The schema object comes from the list when getDefaultSchema is asked (the search path when this one is not listed)
        this.activeSchemaName = CommonUtils.isEmpty(activeSchemaName) ? null : activeSchemaName;
    }

    private void setActiveUser(@Nullable String activeUser) {
        this.activeUser = activeUser;
    }

    @Nullable
    public String getActiveUser() {
        return activeUser;
    }

    /**
     * Computes the effective {@code search_path}.
     * <p>
     * The path consists of two parts (from first used to last used):
     * <ul>
     *     <li>the selected schema, if present</li>
     *     <li>the default search_path retrieved from {@code pg_settings}</li>
     * </ul>
     *
     * @return the effective {@code search_path}
     */
    @NotNull
    public List<String> computeSearchPath() {
        var path = new ArrayList<>(searchPath);

        var activeSchemaName = getDefaultSchemaName();
        if (activeSchemaName != null) {
            path.remove(activeSchemaName);
            path.addFirst(activeSchemaName);
        }

        return List.copyOf(path);
    }

    /**
     * Takes the default schema of another session of the same database (a session opened again by Invalidate/Reconnect,
     * or a new one) by its name, without reading the schema list (see the class comment). While the list is in memory
     * this is "Set as Default" with that schema (its checks and change notice); while it is not, the name goes first in
     * the search path and no notice is sent: the Object Explorer draws the default once the list is read.
     *
     * @return whether the search path was set
     */
    boolean setDefaultSchemaFrom(@NotNull DBRProgressMonitor monitor, @NotNull String schemaName) throws DBException {
        PostgreSchema schema = getDefaultCatalog().schemaCache.getCachedObject(schemaName);
        if (schema != null) {
            changeDefaultSchema(monitor, schema, true, true);
            return true;
        }
        if (getDefaultCatalog().schemaCache.isFullyCached()) {
            // The list read without it: the schema is gone
            return false;
        }
        setSearchPath(monitor, schemaName);
        return true;
    }

    private void setSearchPath(@NotNull DBRProgressMonitor monitor, @NotNull String activeSchemaName) throws DBException {
        // No list read (see the class comment): while the list is not in memory the name is taken as it is (the server
        // skips a search path schema that does not exist); a list that does not hold it says the schema does not exist
        var schemaCache = getDefaultCatalog().schemaCache;
        if (!schemaCache.isFullyCached() || schemaCache.getCachedObject(activeSchemaName) != null) {
            this.activeSchemaName = activeSchemaName;
        }

        var searchPath = computeSearchPath().stream()
            .map(name -> DBUtils.getQuotedIdentifier(getDataSource(), name, true, true))
            .collect(Collectors.joining(","));

        try (JDBCSession session = openSession(monitor, DBCExecutionPurpose.UTIL, "Change search path")) {
            DBExecUtils.tryExecuteRecover(session, session.getDataSource(), param -> {
                try {
                    JDBCUtils.executeSQL(session, "SET search_path = " + searchPath);
                } catch (SQLException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (DBException e) {
            throw new DBCException("Error setting search path", e, this);
        }
    }

    private void setSessionRole(@NotNull DBRProgressMonitor monitor) throws DBCException {
        final String roleName = getDataSource().getContainer().getConnectionConfiguration().getProviderProperty(PostgreConstants.PROP_CHOSEN_ROLE);
        if (CommonUtils.isEmpty(roleName)) {
            return;
        }
        try (JDBCSession session = openSession(monitor, DBCExecutionPurpose.UTIL, "Set active role")) {
            try (JDBCStatement dbStat = session.createStatement()) {
                String sql = "SET ROLE " + getDataSource().getSQLDialect().getQuotedIdentifier(roleName, false, true);
                dbStat.executeUpdate(sql);
            }
        } catch (SQLException e) {
            throw new DBCException(e, this);
        }
    }

    public void setIsolatedContext(boolean isolatedContext) {
        this.isolatedContext = isolatedContext;
    }

    @NotNull
    @Override
    public DBCCachedContextDefaults getCachedDefault() {
        //Method get cashed value
        return new DBCCachedContextDefaults(getOwnerInstance().getName(), getDefaultSchemaName());
    }

    @Override
    public boolean isDefaultsChangeTransactional() {
        return true;
    }
}
