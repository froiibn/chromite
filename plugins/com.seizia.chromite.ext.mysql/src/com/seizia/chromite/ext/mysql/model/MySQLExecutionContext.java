/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ext.mysql.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mysql.MySQLUtils;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.connection.DBPConnectionBootstrap;
import com.seizia.chromite.model.exec.*;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.JDBCRemoteInstance;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;

/**
 * MySQLExecutionContext
 *
 * 변경 이력:
 * - 2026-11-12 {@link #refreshDefaults}: 서버가 알려 준 현재 DB가 캐시에 없으면(다른 세션이 만든 DB) DB 목록을 다시 읽음. 그 DB로 USE 하면
 *   쿼리 창의 DB 선택이 "&lt; N/A &gt;"가 되었다(앱 변경 #79, docs/chromite/swing-sql-editor-session-ui.md "캐시에 없는 현재 DB")
 * - 2026-11-25 {@link #refreshDefaults(DBRProgressMonitor, boolean, boolean)}: 세션을 다시 여는 길(읽기 전용 연결의 Invalidate/Reconnect)은 DB 목록을
 *   다시 읽지 않음. 그 길은 인스턴스의 배타 잠금 아래에 있고, 목록을 쥔 채 그 잠금을 기다리는 스레드와 서로 기다릴 수 있었다(앱 변경 #186,
 *   docs/chromite/cancel-busy-connection.md "세션을 다시 열 때의 교착")
 * - 2026-11-25 {@link #setCurrentDatabaseFrom}: 다시 열거나 새로 여는 세션이 현재 DB를 이름으로 받고, 받지 못하면 세션의 실제 DB를 읽음.
 *   목록에 없는 DB(탐색기 필터)였던 쿼리 창이 Invalidate/Reconnect·끊긴 세션 다시 열기 뒤 그 DB를 보이면서 DB 없이 실행했다
 *   (docs/chromite/swing-sql-editor-session-ui.md "다시 연결 뒤의 DB")
 * - 2026-11-25 대체 읽기는 이름만 읽고 DB 목록을 다시 읽지 않음({@code refreshDefaults(monitor, false, false)}, #186의 같은 길). 세션을 다시 여는
 *   길은 인스턴스의 배타 잠금 아래라 목록 읽기가 목록을 쥔 채 그 잠금을 기다리는 스레드와 서로 기다릴 수 있다(#165·#350과 같은 모양)
 */
public class MySQLExecutionContext extends JDBCExecutionContext implements DBCExecutionContextDefaults<MySQLCatalog, DBSSchema> {
    private static final Log log = Log.getLog(MySQLExecutionContext.class);

    //private MySQLCatalog activeDatabase;
    private String activeDatabaseName;

    MySQLExecutionContext(@NotNull JDBCRemoteInstance instance, String purpose) {
        super(instance, purpose);
    }

    @NotNull
    @Override
    public MySQLDataSource getDataSource() {
        return (MySQLDataSource) super.getDataSource();
    }

    @NotNull
    @Override
    public MySQLExecutionContext getContextDefaults() {
        return this;
    }

    public String getActiveDatabaseName() {
        return activeDatabaseName;
    }

    @Override
    public MySQLCatalog getDefaultCatalog() {
        return CommonUtils.isEmpty(activeDatabaseName) ? null : getDataSource().getCatalog(activeDatabaseName);
    }

    @Override
    public DBSSchema getDefaultSchema() {
        return null;
    }

    @Override
    public boolean supportsCatalogChange() {
        return true;
    }

    @Override
    public boolean supportsSchemaChange() {
        return false;
    }

    @Override
    public void setDefaultCatalog(DBRProgressMonitor monitor, MySQLCatalog catalog, DBSSchema schema) throws DBCException {
        if (activeDatabaseName != null && activeDatabaseName.equals(catalog.getName())) {
            return;
        }
        final MySQLCatalog oldActiveDatabase = getDefaultCatalog();

        // MySQL driver bug - it doesn't ley to change active schema in read-only mode (#9167)
        boolean connectionReadOnly = isConnectionReadOnly(monitor);
        if (connectionReadOnly) {
            setConnectionReadOnly(monitor, false);
        }
        try {
            if (!setCurrentDatabase(monitor, catalog)) {
                return;
            }
        } finally {
            if (connectionReadOnly) {
                setConnectionReadOnly(monitor, true);
            }
        }
        activeDatabaseName = catalog.getName();

        // Send notifications
        DBUtils.fireObjectSelectionChange(oldActiveDatabase, catalog, this);
    }

    private void setConnectionReadOnly(DBRProgressMonitor monitor, boolean readOnly) {
        try {
            getConnection(monitor).setReadOnly(readOnly);
        } catch (Exception e) {
            log.debug(e);
        }
    }

    private boolean isConnectionReadOnly(DBRProgressMonitor monitor) {
        try {
            return getConnection(monitor).isReadOnly();
        } catch (Exception e) {
            log.debug(e);
            return false;
        }
    }

    @Override
    public void setDefaultSchema(DBRProgressMonitor monitor, DBSSchema schema) throws DBCException {
        throw new DBCFeatureNotSupportedException();
    }

    @Override
    public boolean refreshDefaults(DBRProgressMonitor monitor, boolean useBootstrapSettings) throws DBException {
        return refreshDefaults(monitor, useBootstrapSettings, true);
    }

    /**
     * Reads the session's current database.
     *
     * @param readCatalogList false on the path that reopens a session from a source session: Invalidate/Reconnect runs it
     *                        under the instance's exclusive lock, and reading the database list again there can wait for a
     *                        thread that holds the list while it waits for that lock. The name is kept; the query window
     *                        reads the list again after its next run
     */
    boolean refreshDefaults(DBRProgressMonitor monitor, boolean useBootstrapSettings, boolean readCatalogList) throws DBException {
        // Check default active schema
        try (JDBCSession session = openSession(monitor, DBCExecutionPurpose.META, "Query active database")) {
            if (useBootstrapSettings) {
                DBPConnectionBootstrap bootstrap = getBootstrapSettings();
                if (!CommonUtils.isEmpty(bootstrap.getDefaultCatalogName())) {
                    setCurrentDatabaseName(monitor, bootstrap.getDefaultCatalogName());
                }
            }

            activeDatabaseName = MySQLUtils.determineCurrentDatabase(session);
        } catch (DBException e) {
            throw new DBCException(e, this);
        }
        // A database created after the list was read (another session): read it, getDefaultCatalog looks in the cache.
        // The main context opens inside the data source's constructor, before its caches exist (the list is read by initialize)
        MySQLDataSource.CatalogCache catalogCache = getDataSource().getCatalogCache();
        if (readCatalogList && !CommonUtils.isEmpty(activeDatabaseName) && catalogCache != null) {
            try {
                catalogCache.getObjectReadingNew(monitor, getDataSource(), activeDatabaseName);
            } catch (DBException e) {
                log.debug("Error reading the database list again", e);
            }
        }

        return true;
    }

    /**
     * Gives this session the current database of another session of the connection (a session opened again by
     * Invalidate/Reconnect or after it was lost, or a new query window session) by the name that session holds; the
     * catalog list is not looked at, so a database it does not hold (Object Explorer filter) is kept too. When the
     * database cannot be set (dropped, no access since), the database name this context holds is read from the session
     * ({@link #refreshDefaults(DBRProgressMonitor, boolean, boolean)} without the catalog list): a reopened session otherwise still held the name it had
     * before while it ran without it. A source without a database leaves the session as the server opened it.
     */
    void setCurrentDatabaseFrom(@NotNull DBRProgressMonitor monitor, @NotNull MySQLExecutionContext source) throws DBException {
        String databaseName = source.activeDatabaseName;
        if (CommonUtils.isEmpty(databaseName)) {
            return;
        }
        try {
            setCurrentDatabaseName(monitor, databaseName);
        } catch (DBCException e) {
            log.warn("Can't set current database '" + databaseName + "': " + e.getMessage());
            // The name only, not the catalog list (the reopen path, see readCatalogList)
            refreshDefaults(monitor, false, false);
        }
    }

    boolean setCurrentDatabase(DBRProgressMonitor monitor, MySQLCatalog object) throws DBCException {
        if (object == null) {
            log.debug("Null current database");
            return false;
        }
        String databaseName = object.getName();
        return setCurrentDatabaseName(monitor, databaseName);
    }

    private boolean setCurrentDatabaseName(DBRProgressMonitor monitor, String databaseName) throws DBCException {
        try (JDBCSession session = openSession(monitor, DBCExecutionPurpose.UTIL, "Set active catalog")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement("use " + DBUtils.getQuotedIdentifier(getDataSource(), databaseName))) {
                dbStat.execute();
            } catch (SQLException e) {
                throw new DBCException(e, session.getExecutionContext());
            }
            this.activeDatabaseName = databaseName;
            return true;
        }
    }

    @NotNull
    @Override
    public DBCCachedContextDefaults getCachedDefault() {
        return new DBCCachedContextDefaults(activeDatabaseName, null);
    }
}
