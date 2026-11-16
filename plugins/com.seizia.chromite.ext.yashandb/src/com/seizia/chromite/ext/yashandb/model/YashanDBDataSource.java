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
package com.seizia.chromite.ext.yashandb.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.oracle.model.OracleConstants;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleExecutionContext;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.ext.oracle.model.auth.OracleAuthModelDatabaseNative;
import com.seizia.chromite.ext.yashandb.model.session.YashanDBServerSessionManager;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.access.DBAUserPasswordManager;
import com.seizia.chromite.model.admin.sessions.DBAServerSessionManager;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.plan.DBCQueryPlanner;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;

/**
 * YashanDBDataSource
 */
public class YashanDBDataSource extends OracleDataSource {

    private static final Log log = Log.getLog(YashanDBDataSource.class);

    private final YashanDBTablespaceCache yashanDBTablespaceCache = new YashanDBTablespaceCache();
    private final YashanDBDataTypeCache yashanDBDataTypeCache = new YashanDBDataTypeCache();

    public YashanDBDataSource(@NotNull DBPDataSourceContainer container) {
        super(container);
    }

    public YashanDBDataSource(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSourceContainer container)
            throws DBException {
        super(monitor, container, new YashanDBSQLDialect());
    }

    @NotNull
    @Override
    public YashanDBTablespaceCache getTablespaceCache() {
        return this.yashanDBTablespaceCache;
    }

    @NotNull
    @Override
    public YashanDBDataTypeCache getDataTypeCache() {
        return this.yashanDBDataTypeCache;
    }

    @Override
    public boolean isAtLeastV9() {
        return false;
    }

    @Override
    protected boolean isUserPasswordAuthentication(@NotNull DBPConnectionConfiguration connectionInfo) {
        return super.isUserPasswordAuthentication(connectionInfo)
            || OracleAuthModelDatabaseNative.ID.equals(connectionInfo.getAuthModelId());
    }

    @Override
    public void initialize(@NotNull DBRProgressMonitor monitor) throws DBException {
        super.initialize(monitor);

        this.publicSchema = new YashanDBSchema(this, 1, OracleConstants.USER_PUBLIC);

        DBPConnectionConfiguration connectionInfo = getContainer().getConnectionConfiguration();
        {
            try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Load data source meta info")) {
                this.isAdmin = "YES".equals(
                    JDBCUtils.queryString(session, "SELECT 'YES' FROM dba_role_privs WHERE GRANTED_ROLE='DBA'"));
                this.isAdminVisible = isAdmin;
                if (!isAdminVisible) {
                    String showAdmin = connectionInfo.getProviderProperty(OracleConstants.PROP_ALWAYS_SHOW_DBA);
                    if (showAdmin != null) {
                        isAdminVisible = CommonUtils.getBoolean(showAdmin, false);
                    }
                }
            } catch (SQLException e) {
                log.warn(e);
            }
        }
    }

    @Override
    protected void initializeContextState(@NotNull DBRProgressMonitor monitor, @NotNull JDBCExecutionContext context,
                                          @Nullable JDBCExecutionContext initFrom) throws DBException {
        if (outputReader == null) {
            outputReader = new OracleOutputReader();
        }
        // Enable DBMS output
        outputReader.enableServerOutput(monitor, context, outputReader.isServerOutputEnabled());
        if (initFrom != null) {
            ((OracleExecutionContext) context).setCurrentSchema(monitor,
                ((OracleExecutionContext) initFrom).getDefaultSchema());
        } else {
            ((OracleExecutionContext) context).refreshDefaults(monitor, true);
        }
    }

    @NotNull
    @Override
    public Class<? extends OracleSchema> getPrimaryChildType(@Nullable DBRProgressMonitor monitor) throws DBException {
        return YashanDBSchema.class;
    }

    @NotNull
    @Override
    public YashanDBSchema createSchemaImpl(@NotNull OracleDataSource owner, @NotNull JDBCResultSet resultSet) {
        return new YashanDBSchema(owner, resultSet);
    }

    @NotNull
    @Override
    public YashanDBDataSource getDataSource() {
        return this;
    }

    @Nullable
    @Override
    public <T> T getAdapter(@NotNull Class<T> adapter) {
        if (adapter == DBAServerSessionManager.class) {
            return adapter.cast(new YashanDBServerSessionManager(this));
        } else if (adapter == DBAUserPasswordManager.class) {
            return adapter.cast(new YashanDBChangeUserPasswordManager(this));
        } else if (adapter == DBCQueryPlanner.class) {
            // YashanDB not support
            return null;
        }
        return super.getAdapter(adapter);
    }

    static class YashanDBTablespaceCache extends TablespaceCache {

        @NotNull
        @Override
        protected YashanDBTablespace fetchObject(@NotNull JDBCSession session, @NotNull OracleDataSource owner,
                                                 @NotNull JDBCResultSet resultSet) throws SQLException, DBException {
            return new YashanDBTablespace(owner, resultSet);
        }
    }

    static class YashanDBDataTypeCache extends DataTypeCache {

        @NotNull
        @Override
        protected YashanDBDataType fetchObject(@NotNull JDBCSession session, @NotNull OracleDataSource owner,
                                               @NotNull JDBCResultSet resultSet) throws SQLException, DBException {
            return new YashanDBDataType(owner, resultSet);
        }
    }
}
