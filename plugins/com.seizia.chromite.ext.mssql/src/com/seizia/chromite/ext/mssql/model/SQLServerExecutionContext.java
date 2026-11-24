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
package com.seizia.chromite.ext.mssql.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mssql.SQLServerConstants;
import com.seizia.chromite.ext.mssql.SQLServerUtils;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.connection.DBPConnectionBootstrap;
import com.seizia.chromite.model.exec.DBCCachedContextDefaults;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.JDBCRemoteInstance;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;

/**
 * SQLServerExecutionContext
 *
 * 변경 이력:
 * - 2026-11-12 {@link #refreshDefaults}: 서버가 알려 준 현재 DB가 캐시에 없으면(다른 세션이 만든 DB) DB 목록을 다시 읽음. 그 DB로 USE 하면
 *   쿼리 창의 DB 선택이 "&lt; N/A &gt;"가 되고 상태 줄에서 DB가 빠졌다(앱 변경 #79, docs/chromite/swing-sql-editor-session-ui.md "캐시에 없는 현재 DB")
 * - 2026-11-22 {@link #isDefaultSchemaLoaded}: {@link #getDefaultSchema}는 기본 데이터베이스의 스키마 목록이 없으면 서버에서 읽는다.
 *   개체 탐색기가 그림을 그리다 그 읽기를 하지 않도록 먼저 묻는다(앱 변경 #164, docs/chromite/swing-explorer-productivity.md "기본값 굵게 표시")
 * - 2026-11-22 {@link #setDefaultSchemaFrom}: 다시 열거나 새로 여는 세션이 기본 스키마를 이름으로 받음. 스키마 목록을 읽지 않으므로
 *   Invalidate/Reconnect의 배타 잠금 아래에서 목록을 쥔 스레드를 기다리지 않는다(앱 변경 #165, docs/chromite/cancel-busy-connection.md)
 * - 2026-11-25 {@link #setCurrentDatabaseFrom}: 다시 열거나 새로 여는 세션이 현재 DB를 이름으로 받고, 받지 못하면 세션의 실제 DB를 읽음.
 *   목록에 없는 DB(탐색기 필터)였던 쿼리 창이 Invalidate/Reconnect·끊긴 세션 다시 열기 뒤 그 DB를 보이면서 로그인 기본 DB에서 실행했다
 *   (docs/chromite/swing-sql-editor-session-ui.md "다시 연결 뒤의 DB")
 * - 2026-11-25 대체 읽기는 이름만 읽고 DB 목록을 다시 읽지 않음. 세션을 다시 여는 길은 인스턴스의 배타 잠금 아래라 목록 읽기가
 *   목록을 쥔 채 그 잠금을 기다리는 스레드와 서로 기다릴 수 있다(#165·#350과 같은 모양)
 */
public class SQLServerExecutionContext extends JDBCExecutionContext implements DBCExecutionContextDefaults<SQLServerDatabase, SQLServerSchema> {
    private static final Log log = Log.getLog(SQLServerExecutionContext.class);

    //private SQLServerDatabase activeDatabase;
    private String activeDatabaseName;
    private String activeSchemaName;
    private String currentUser;

    SQLServerExecutionContext(@NotNull JDBCRemoteInstance instance, String purpose) {
        super(instance, purpose);
    }

    @NotNull
    @Override
    public SQLServerDataSource getDataSource() {
        return (SQLServerDataSource) super.getDataSource();
    }

    @NotNull
    @Override
    public SQLServerExecutionContext getContextDefaults() {
        return this;
    }

    public String getActiveDatabaseName() {
        return activeDatabaseName;
    }

    @Override
    public SQLServerDatabase getDefaultCatalog() {
        return getDataSource().getDatabase(activeDatabaseName);
    }

    @Nullable
    @Override
    public SQLServerSchema getDefaultSchema() {
        if (CommonUtils.isEmpty(activeSchemaName)) {
            return null;
        }
        try {
            SQLServerDatabase defaultCatalog = getDefaultCatalog();
            if (defaultCatalog == null) {
                return null;
            }
            SQLServerSchema schema = defaultCatalog.getSchema(new VoidProgressMonitor(), activeSchemaName);
            if (schema == null) {
                // If DBO is not the default schema and default schema doesn't exist (or was filtered out) let's try DBO though
                schema = defaultCatalog.getSchema(new VoidProgressMonitor(), SQLServerConstants.DEFAULT_SCHEMA_NAME);
            }
            return schema;
        } catch (DBException e) {
            log.error(e);
            return null;
        }
    }

    /**
     * The default database's schema list is read (getDefaultSchema would read it otherwise).
     */
    @Override
    public boolean isDefaultSchemaLoaded() {
        SQLServerDatabase defaultCatalog = CommonUtils.isEmpty(activeSchemaName) ? null : getDefaultCatalog();
        return defaultCatalog == null || defaultCatalog.isSchemaListLoaded();
    }

    @Override
    public boolean supportsCatalogChange() {
        // Azure SQL Database doesn't support changing database
        return !SQLServerUtils.isDriverAzure(getDataSource().getContainer().getDriver())
            || (dataSource instanceof SQLServerDataSource sqlServerDataSource
            && sqlServerDataSource.isDataWarehouseServer(new VoidProgressMonitor()));
    }

    @Override
    public boolean supportsSchemaChange() {
        return false;
    }

    @Override
    public void setDefaultCatalog(@NotNull DBRProgressMonitor monitor, @NotNull SQLServerDatabase catalog, @Nullable SQLServerSchema schema)
    throws DBCException {
        if (activeDatabaseName != null && activeDatabaseName.equals(catalog.getName())) {
            return;
        }
        final SQLServerDatabase oldActiveDatabase = getDefaultCatalog();

        if (!setCurrentDatabase(monitor, catalog)) {
            return;
        }
        try {
            catalog.getSchemas(monitor);
        } catch (DBException e) {
            log.debug("Error caching database schemas", e);
        }
        activeDatabaseName = catalog.getName();

        // Send notifications
        DBUtils.fireObjectSelectionChange(oldActiveDatabase, catalog, this);

        if (schema != null) {
            setDefaultSchema(monitor, schema);
        }
    }

    @Override
    public void setDefaultSchema(DBRProgressMonitor monitor, SQLServerSchema schema) throws DBCException {
        if (activeSchemaName != null && activeSchemaName.equals(schema.getName())) {
            return;
        }
        final SQLServerSchema oldActiveSchema = getDefaultSchema();

//        if (!setCurrentSchema(monitor, schema.getName())) {
//            return;
//        }
        activeSchemaName = schema.getName();

        // Send notifications
        DBUtils.fireObjectSelectionChange(oldActiveSchema, schema, this);
    }

    /**
     * Takes the default schema of another session of the connection (a session opened again by Invalidate/Reconnect, or a
     * new one) by the name that session holds. The schema list is not read: Invalidate/Reconnect calls this under the
     * instance's exclusive lock, which a thread reading the list may wait for. The change notice carries the schemas of
     * the list in memory, none while it is not read.
     */
    void setDefaultSchemaFrom(@NotNull SQLServerExecutionContext source) {
        String schemaName = source.activeSchemaName;
        if (CommonUtils.isEmpty(schemaName) || schemaName.equals(activeSchemaName)) {
            return;
        }
        SQLServerSchema oldActiveSchema = getLoadedDefaultSchema();
        activeSchemaName = schemaName;
        DBUtils.fireObjectSelectionChange(oldActiveSchema, getLoadedDefaultSchema(), this);
    }

    /**
     * Gives this session the current database of another session of the connection (a session opened again by
     * Invalidate/Reconnect or after it was lost, or a new query window session) by the name that session holds; the
     * database list is not looked at, so a database it does not hold (Object Explorer filter) is kept too. When the
     * database cannot be set (dropped, offline, no access since) or there is no name, the database and schema this
     * context holds are read from the session (names only, the database list is not read: the reopen path holds the
     * instance's exclusive lock): a reopened session otherwise still held the name it had before while the server put it
     * in the login's default database.
     *
     * @return true when the session uses the source's database
     */
    boolean setCurrentDatabaseFrom(@NotNull DBRProgressMonitor monitor, @NotNull SQLServerExecutionContext source) throws DBException {
        String databaseName = source.activeDatabaseName;
        if (!CommonUtils.isEmpty(databaseName) && setCurrentDatabase(monitor, databaseName)) {
            return true;
        }
        // Names only: the reopen path holds the instance's exclusive lock, and reading the database list here can wait for
        // a thread that holds the list while it waits for that lock; the query window reads the list after its next run
        refreshDefaults(monitor, false, false);
        return false;
    }

    /**
     * {@link #getDefaultSchema} from the schema list in memory only: null while the list is not read.
     */
    @Nullable
    private SQLServerSchema getLoadedDefaultSchema() {
        SQLServerDatabase defaultCatalog = CommonUtils.isEmpty(activeSchemaName) ? null : getDefaultCatalog();
        if (defaultCatalog == null) {
            return null;
        }
        SQLServerSchema schema = defaultCatalog.getSchema(activeSchemaName);
        return schema != null ? schema : defaultCatalog.getSchema(SQLServerConstants.DEFAULT_SCHEMA_NAME);
    }

    @Override
    public boolean refreshDefaults(DBRProgressMonitor monitor, boolean useBootstrapSettings) throws DBException {
        return refreshDefaults(monitor, useBootstrapSettings, true);
    }

    /**
     * @param readDatabaseList false on the path that reopens a session ({@link #setCurrentDatabaseFrom}): only the names
     *                         the server gives are read, the database list is not read again
     */
    private boolean refreshDefaults(DBRProgressMonitor monitor, boolean useBootstrapSettings, boolean readDatabaseList) throws DBException {
        boolean refreshed = false;
        // Check default active schema
        try (JDBCSession session = openSession(monitor, DBCExecutionPurpose.META, "Query active schema and database")) {
            String currentDatabase = null;
            String currentSchema = null;
            try {
                try (JDBCStatement dbStat = session.createStatement()) {
                    String query = "SELECT db_name(), schema_name(), original_login()";
                    if (SQLServerUtils.isDriverBabelfish(session.getDataSource().getContainer().getDriver())) {
                        query = "SELECT db_name(), s.name AS schema_name, session_user AS original_login FROM sys.schemas s";
                    } else if (SQLServerUtils.isDriverAzure(session.getDataSource().getContainer().getDriver())) {
                        query = "SELECT db_name(), schema_name(), CURRENT_USER AS original_login";
                    }
                    try (JDBCResultSet dbResult = dbStat.executeQuery(query)) {
                        dbResult.next();
                        currentDatabase = dbResult.getString(1);
                        currentSchema = dbResult.getString(2);
                        currentUser = dbResult.getString(3);
                    }
                }
            } catch (Throwable e) {
                log.debug("Error getting current user: " + e.getMessage());
            }
            if (!CommonUtils.isEmpty(currentDatabase) && (activeDatabaseName == null || !CommonUtils.equalObjects(currentDatabase, activeDatabaseName))) {
                activeDatabaseName = currentDatabase;
                refreshed = true;
            }
            // A database created after the list was read (another session): read it, getDefaultCatalog looks in the cache.
            // The main context opens inside the data source's constructor, before its caches exist (the list is read by initialize)
            SQLServerDataSource.DatabaseCache databaseCache = getDataSource().getDatabaseCache();
            if (readDatabaseList && !CommonUtils.isEmpty(currentDatabase) && databaseCache != null) {
                try {
                    databaseCache.getObjectReadingNew(monitor, getDataSource(), currentDatabase);
                } catch (DBException e) {
                    log.debug("Error reading the database list again", e);
                }
            }
            if (CommonUtils.isEmpty(currentSchema)) {
                currentSchema = SQLServerConstants.DEFAULT_SCHEMA_NAME;
            }
            if (activeSchemaName == null || !CommonUtils.equalObjects(currentSchema, activeSchemaName)) {
                activeSchemaName = currentSchema;
                refreshed = true;
            }
            if (useBootstrapSettings) {
                DBPConnectionBootstrap bootstrap = getBootstrapSettings();
                if (!CommonUtils.isEmpty(bootstrap.getDefaultCatalogName()) && supportsCatalogChange() && !CommonUtils.equalObjects(bootstrap.getDefaultCatalogName(), activeDatabaseName)) {
                    setCurrentDatabase(monitor, bootstrap.getDefaultCatalogName());
                    refreshed = true;
                }
/*
                if (!CommonUtils.isEmpty(bootstrap.getDefaultSchemaName()) && supportsSchemaChange()) {
                    setCurrentSchema(monitor, bootstrap.getDefaultSchemaName());
                    refreshed = true;
                }
*/
            }
        }

        return refreshed;
    }

    boolean setCurrentDatabase(DBRProgressMonitor monitor, SQLServerDatabase object) throws DBCException {
        if (object == null) {
            log.debug("Null current schema");
            return false;
        }
        String databaseName = object.getName();
        return setCurrentDatabase(monitor, databaseName);
    }

    private boolean setCurrentDatabase(DBRProgressMonitor monitor, String databaseName) {
        try (JDBCSession session = openSession(monitor, DBCExecutionPurpose.UTIL, "Set active database")) {
            SQLServerUtils.setCurrentDatabase(session, databaseName);
            activeDatabaseName = databaseName;
            return true;
        } catch (SQLException e) {
            log.error(e);
            return false;
        }
    }

/*
    private boolean setCurrentSchema(DBRProgressMonitor monitor, String schemaName) {
        try (JDBCSession session = openSession(monitor, DBCExecutionPurpose.UTIL, "Set active schema")) {
            SQLServerUtils.setCurrentSchema(session, currentUser, schemaName);
            activeSchemaName = schemaName;
            return true;
        } catch (SQLException e) {
            log.error(e);
            return false;
        }
    }
*/

    @NotNull
    @Override
    public DBCCachedContextDefaults getCachedDefault() {
        return new DBCCachedContextDefaults(activeDatabaseName, activeSchemaName);
    }
}
