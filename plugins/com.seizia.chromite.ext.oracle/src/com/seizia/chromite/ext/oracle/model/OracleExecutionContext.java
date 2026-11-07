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
package com.seizia.chromite.ext.oracle.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.connection.DBPConnectionBootstrap;
import com.seizia.chromite.model.exec.*;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.JDBCRemoteInstance;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;

/**
 * OracleExecutionContext
 *
 * 변경 이력:
 * - 2026-11-08 Chromite: 이 세션에서 DBMS_OUTPUT이 켜져 있는지 기억함({@link #isServerOutputEnabled}). 설정은 언제든 바뀌지만 서버 상태는
 *   세션마다 따로 있어서, 설정과 다를 때만 그 세션에 ENABLE/DISABLE을 보낸다({@code OracleOutputReader.syncServerOutput})
 *   (docs/chromite/model-fixes.md "작은 모델·안전·라이선스 수정")
 */
public class OracleExecutionContext extends JDBCExecutionContext implements DBCExecutionContextDefaults<DBSCatalog, OracleSchema> {
    private static final Log log = Log.getLog(OracleExecutionContext.class);

    private String activeSchemaName;
    private volatile boolean serverOutputEnabled;

    OracleExecutionContext(@NotNull JDBCRemoteInstance instance, String purpose) {
        super(instance, purpose);
    }

    @NotNull
    @Override
    public OracleDataSource getDataSource() {
        return (OracleDataSource) super.getDataSource();
    }

    @NotNull
    @Override
    public OracleExecutionContext getContextDefaults() {
        return this;
    }

    public String getActiveSchemaName() {
        return activeSchemaName;
    }

    /**
     * Whether DBMS_OUTPUT was last enabled (true) or disabled on this session's server side.
     */
    boolean isServerOutputEnabled() {
        return serverOutputEnabled;
    }

    void setServerOutputEnabled(boolean serverOutputEnabled) {
        this.serverOutputEnabled = serverOutputEnabled;
    }

    @Override
    public DBSCatalog getDefaultCatalog() {
        return null;
    }

    @Override
    public OracleSchema getDefaultSchema() {
        try {
            return activeSchemaName == null ? null : getDataSource().getSchema(
                new VoidProgressMonitor(), activeSchemaName);
        } catch (Exception e) {
            log.error(e);
            return null;
        }
    }

    @Override
    public boolean supportsCatalogChange() {
        return false;
    }

    @Override
    public boolean supportsSchemaChange() {
        return true;
    }

    @Override
    public void setDefaultCatalog(DBRProgressMonitor monitor, DBSCatalog catalog, OracleSchema schema) throws DBCException {
        throw new DBCFeatureNotSupportedException();
    }

    @Override
    public void setDefaultSchema(DBRProgressMonitor monitor, OracleSchema schema) throws DBCException {
        final OracleSchema oldSelectedEntity = getDefaultSchema();
        if (schema == null || oldSelectedEntity == schema) {
            return;
        }
        setCurrentSchema(monitor, schema);
        activeSchemaName = schema.getName();

        // Send notifications
        DBUtils.fireObjectSelectionChange(oldSelectedEntity, schema, this);
    }

    @Override
    public boolean refreshDefaults(DBRProgressMonitor monitor, boolean useBootstrapSettings) throws DBException {
        // Check default active schema
        try (JDBCSession session = openSession(monitor, DBCExecutionPurpose.META, "Query active schema")) {
            if (useBootstrapSettings) {
                DBPConnectionBootstrap bootstrap = getBootstrapSettings();
                String bootstrapSchemaName = bootstrap.getDefaultSchemaName();
                if (!CommonUtils.isEmpty(bootstrapSchemaName) && !bootstrapSchemaName.equals(activeSchemaName)) {
                    setCurrentSchema(monitor, bootstrap.getDefaultSchemaName());
                }
            }
            // Get active schema
            this.activeSchemaName = OracleUtils.getCurrentSchema(session);
            if (this.activeSchemaName != null) {
                if (this.activeSchemaName.isEmpty()) {
                    this.activeSchemaName = null;
                }
            }
        } catch (Exception e) {
            throw new DBCException(e, this);
        }

        return true;
    }

    public void setCurrentSchema(DBRProgressMonitor monitor, OracleSchema object) throws DBCException {
        if (object == null) {
            log.debug("Null current schema");
            return;
        }
        setCurrentSchema(monitor, object.getName());
    }

    private void setCurrentSchema(DBRProgressMonitor monitor, String activeSchemaName) throws DBCException {
        DBSObject oldDefaultSchema = getDefaultSchema();
        try (JDBCSession session = openSession(monitor, DBCExecutionPurpose.UTIL, TASK_TITLE_SET_SCHEMA)) {
            OracleUtils.setCurrentSchema(session, activeSchemaName);
            this.activeSchemaName = activeSchemaName;
            DBSObject newDefaultSchema = getDefaultSchema();
            DBUtils.fireObjectSelectionChange(oldDefaultSchema, newDefaultSchema, this);
        } catch (SQLException e) {
            throw new DBCException(e, this);
        }
    }

    @NotNull
    @Override
    public DBCCachedContextDefaults getCachedDefault() {
        return new DBCCachedContextDefaults(null, activeSchemaName);
    }
}
