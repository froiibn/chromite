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
import org.jkiss.code.Nullable;
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
 * 설계(Chromite): 세션을 여는 길({@code OracleDataSource.initializeContextState}: 연결, Invalidate/Reconnect, 쿼리 창의 새 세션)은
 * 스키마 목록을 읽지 않는다. Invalidate/Reconnect는 인스턴스의 배타 잠금을 쥔 채 이 길을 지나는데, 목록을 읽는 다른 스레드는
 * 목록 캐시의 모니터를 쥔 채 닫힌 메타데이터 세션을 다시 열려고 그 잠금을 기다린다. 그래서 세션의 스키마는 이름으로 정하고
 * ({@link #setCurrentSchemaFrom}), 바뀜 알림은 메모리에 있는 목록에서만 개체를 찾는다(목록이 없으면 알림 없음, 목록을 읽은 뒤의
 * 그리기가 기본값을 맞게 보인다).
 *
 * 변경 이력:
 * - 2026-11-08 Chromite: 이 세션에서 DBMS_OUTPUT이 켜져 있는지 기억함({@link #isServerOutputEnabled}). 설정은 언제든 바뀌지만 서버 상태는
 *   세션마다 따로 있어서, 설정과 다를 때만 그 세션에 ENABLE/DISABLE을 보낸다({@code OracleOutputReader.syncServerOutput})
 *   (docs/chromite/model-fixes.md "작은 모델·안전·라이선스 수정")
 * - 2026-11-11 Chromite: 쿼리 창의 {@code SET SERVEROUTPUT}이 이 세션만 켜고 끈 값({@link #getServerOutputOverride}). 연결 설정보다 앞서고
 *   설정은 바꾸지 않는다. 세션 객체가 살아 있는 동안(다시 연결해도) 유지된다 (docs/chromite/oracle-sqlplus-commands.md)
 * - 2026-11-22 Chromite: {@link #isDefaultSchemaLoaded}. {@link #getDefaultSchema}는 스키마 목록이 비어 있으면(Refresh 직후) 서버에서
 *   읽는데, 개체 탐색기가 그림을 그리다 이것을 불러 이벤트 스레드가 메타데이터 연결을 기다렸다(앱 변경 #164,
 *   docs/chromite/swing-explorer-productivity.md "기본값 굵게 표시")
 * - 2026-11-22 Chromite: {@link #setCurrentSchemaFrom}, 바뀜 알림의 {@link #getLoadedSchema}. 세션을 다시 여는 스레드가 배타 잠금을 쥔 채
 *   {@link #getDefaultSchema}로 목록을 읽으려다, 목록을 읽으며 그 잠금을 기다리던 배경 작업(쿼리 창 자동 완성)과 서로 기다려 연결이
 *   앱을 다시 시작할 때까지 멈췄다(앱 변경 #165, docs/chromite/cancel-busy-connection.md "세션을 다시 열 때의 교착")
 */
public class OracleExecutionContext extends JDBCExecutionContext implements DBCExecutionContextDefaults<DBSCatalog, OracleSchema> {
    private static final Log log = Log.getLog(OracleExecutionContext.class);

    private String activeSchemaName;
    private volatile boolean serverOutputEnabled;
    // SET SERVEROUTPUT of the query window that runs on this session (null: follow the connection's setting)
    private volatile Boolean serverOutputOverride;
    // Buffer size of SET SERVEROUTPUT ON SIZE n (null: UNLIMITED)
    private volatile Integer serverOutputSize;

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

    /**
     * Whether the query window turned DBMS_OUTPUT on (true) or off (false) on this session, or null if it did not.
     */
    @Nullable
    Boolean getServerOutputOverride() {
        return serverOutputOverride;
    }

    /**
     * The buffer size of the window's {@code SET SERVEROUTPUT ON} (null: UNLIMITED).
     */
    @Nullable
    Integer getServerOutputSize() {
        return serverOutputSize;
    }

    void setServerOutputOverride(boolean enabled, @Nullable Integer size) {
        this.serverOutputSize = size;
        this.serverOutputOverride = enabled;
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

    /**
     * The schema list of the data source is read (getSchema would read it otherwise).
     */
    @Override
    public boolean isDefaultSchemaLoaded() {
        return activeSchemaName == null || getDataSource().schemaCache.isFullyCached();
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

    /**
     * Gives this session the current schema of another session of the connection (a session opened again by
     * Invalidate/Reconnect, or a new one), by the name that session holds. The schema list is not read: see the class
     * comment. When the schema cannot be set (dropped since), the session keeps the schema the server gave it, and this
     * context reads that name.
     */
    public void setCurrentSchemaFrom(@NotNull DBRProgressMonitor monitor, @NotNull OracleExecutionContext source) throws DBException {
        String schemaName = source.getActiveSchemaName();
        if (schemaName == null) {
            log.debug("Null current schema");
            return;
        }
        try {
            setCurrentSchema(monitor, schemaName);
        } catch (DBCException e) {
            log.warn("Can't set current schema '" + schemaName + "': " + e.getMessage());
            refreshDefaults(monitor, false);
        }
    }

    private void setCurrentSchema(DBRProgressMonitor monitor, String activeSchemaName) throws DBCException {
        DBSObject oldDefaultSchema = getLoadedSchema(this.activeSchemaName);
        try (JDBCSession session = openSession(monitor, DBCExecutionPurpose.UTIL, TASK_TITLE_SET_SCHEMA)) {
            OracleUtils.setCurrentSchema(session, activeSchemaName);
            this.activeSchemaName = activeSchemaName;
            DBSObject newDefaultSchema = getLoadedSchema(activeSchemaName);
            DBUtils.fireObjectSelectionChange(oldDefaultSchema, newDefaultSchema, this);
        } catch (SQLException e) {
            throw new DBCException(e, this);
        }
    }

    /**
     * The schema of the name from the schema list in memory, without reading the list: null while it is not read (or
     * does not hold the name).
     */
    @Nullable
    private OracleSchema getLoadedSchema(@Nullable String schemaName) {
        if (schemaName == null) {
            return null;
        }
        OracleDataSource dataSource = getDataSource();
        if (dataSource.publicSchema != null && dataSource.publicSchema.getName().equals(schemaName)) {
            return dataSource.publicSchema;
        }
        // The main session opens inside the data source's constructor, before its caches exist (as in getSchema)
        return dataSource.schemaCache == null ? null : dataSource.schemaCache.getCachedObject(schemaName);
    }

    @NotNull
    @Override
    public DBCCachedContextDefaults getCachedDefault() {
        return new DBCCachedContextDefaults(null, activeSchemaName);
    }
}
