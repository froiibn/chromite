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
package com.seizia.chromite.ext.sqlite.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.ext.generic.model.GenericExecutionContext;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModel;
import com.seizia.chromite.ext.sqlite.SQLiteExtensionConfirmation;
import com.seizia.chromite.ext.sqlite.SQLiteExtensionLoading;
import com.seizia.chromite.ext.sqlite.SQLiteExtensionLoadingException;
import com.seizia.chromite.ext.sqlite.SQLiteExtensionPermissionStore;
import com.seizia.chromite.ext.sqlite.SQLiteExtensionSettings;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceInfo;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCDatabaseMetaData;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.JDBCRemoteInstance;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLConstants;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.struct.DBSDataType;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * SQLite data source.
 * <p>
 * 설계: 모델에는 데이터베이스(스키마) 단계가 없고 루트의 자식이 바로 테이블이다. SQLite가 쿼리에서 읽는 데이터베이스 이름 중
 * {@code main}만 이 데이터 원본 자신이다({@code temp}와 ATTACH한 데이터베이스의 테이블은 모델에 없음).
 * <p>
 * 변경 이력:
 * - 2026-11-15 {@link #getChild}: 같은 이름의 테이블이 없으면 {@code main}(대소문자 무시)은 데이터 원본 자신. 쿼리 글의
 *   {@code main.t}가 {@code t}로 풀려 결과 그리드가 묶이고, 풀리지 않는 {@code temp.t}·{@code other.t}는 읽기 전용이 된다
 *   (앱 변경 #125, docs/chromite/swing-frontend.md "결과 그리드의 테이블 묶기")
 */
public class SQLiteDataSource extends GenericDataSource {

    /** The schema name SQLite gives the database opened by the connection */
    private static final String MAIN_DATABASE_NAME = "main";

    private SQLiteExtensionLoading extensionLoading;

    public SQLiteDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container,
        @NotNull GenericMetaModel metaModel
    ) throws DBException {
        super(monitor, container, metaModel, new SQLiteSQLDialect());

    }

    public SQLiteDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container,
        @NotNull GenericMetaModel metaModel,
        @NotNull SQLDialect dialect
    ) throws DBException {
        super(monitor, container, metaModel, dialect);
    }


    @NotNull
    @Override
    protected JDBCExecutionContext createExecutionContext(@NotNull JDBCRemoteInstance instance, @NotNull String type) {
        return new GenericExecutionContext(instance, type) {
            @Override
            protected boolean initContextBootstrap(@NotNull DBRProgressMonitor monitor, boolean autoCommit) throws DBException {
                try {
                    return super.initContextBootstrap(monitor, autoCommit);
                } catch (DBException exception) {
                    if (SQLiteExtensionLoading.isLoadingDenied(exception)) {
                        throw new SQLiteExtensionLoadingException(exception, this);
                    }
                    throw exception;
                }
            }
        };
    }

    @NotNull
    @Override
    protected DBPDataSourceInfo createDataSourceInfo(@NotNull DBRProgressMonitor monitor, @NotNull JDBCDatabaseMetaData metaData) {
        return new SQLiteDataSourceInfo(container.getDriver(), metaData);
    }

    @Override
    protected boolean isPopulateClientAppName() {
        return false;
    }

    @Nullable
    @Override
    public DBSDataType getLocalDataType(@Nullable String typeName) {
        if (typeName == null) {
            return super.getLocalDataType(defaultAffinity().name());
        }
        // Resolve type name according to https://www.sqlite.org/datatype3.html
        // except for the GUID type name considered as BLOB to allow its conversion to UUID
        typeName = typeName.toUpperCase(Locale.ENGLISH);
        SQLiteAffinity affinity;
        if (typeName.startsWith(SQLConstants.DATA_TYPE_INT)) {
            affinity = SQLiteAffinity.INTEGER;
        } else if (typeName.contains("CHAR") || typeName.contains("CLOB") || typeName.contains("TEXT") || typeName.startsWith("DATE") || typeName.startsWith("TIME")) {
            affinity = SQLiteAffinity.TEXT;
        } else if (typeName.contains("BLOB") || typeName.contains("GUID")) {
            affinity = SQLiteAffinity.BLOB;
        } else if (typeName.startsWith("REAL") || typeName.startsWith("FLOA") || typeName.startsWith("DOUB")) {
            affinity = SQLiteAffinity.REAL;
        } else if (typeName.contains(SQLConstants.DATA_TYPE_INT) || typeName.contains("NUMERIC") || typeName.contains("DECIMAL") ||
            typeName.contains("BOOL")) {
            affinity = SQLiteAffinity.NUMERIC;
        } else {
            affinity = defaultAffinity();
        }
        return super.getLocalDataType(affinity.name());
    }

    private static SQLiteAffinity defaultAffinity() {
        // If type is unknown, let's assume it's a text. Otherwise, search and data editor doesn't work right.
        return SQLiteAffinity.TEXT;
    }

    @Override
    protected boolean isConnectionReadOnlyBroken() {
        return true;
    }

    @NotNull
    @Override
    protected Map<String, String> getInternalConnectionProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDriver driver,
        @Nullable JDBCExecutionContext context,
        @NotNull String purpose,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) throws DBCException {
        Map<String, String> connectionsProps = new HashMap<>();
        if (getContainer().isConnectionReadOnly()) {
            // Read-only prop
            connectionsProps.put("open_mode", "1");  //1 == readonly
        }
        String enabled = connectionInfo.getProviderProperty(SQLiteExtensionLoading.PROP_ENABLED);
        if (enabled != null) {
            connectionsProps.put(SQLiteExtensionLoading.ENABLE_LOAD_EXTENSION, enabled);
        } else if (CommonUtils.isNotEmpty(
            connectionInfo.getProviderProperty(SQLiteExtensionLoading.PROP_EXTENSIONS))) {
            connectionsProps.put(SQLiteExtensionLoading.ENABLE_LOAD_EXTENSION, Boolean.TRUE.toString());
        }
        return connectionsProps;
    }

    @Override
    protected void validateConnectionParameters(
        @NotNull DBRProgressMonitor monitor,
        @Nullable String url,
        @NotNull Properties connectProps
    ) throws DBCException {
        SQLiteExtensionSettings settings = SQLiteExtensionSettings.capture(getContainer(), url, connectProps);
        var application = DBWorkbench.getPlatform().getApplication();
        if (settings.enabled() && (application.isHeadlessMode() || application.isMultiuser())) {
            throw new DBCException("SQLite native extensions require local approval in the desktop application");
        }
        getExtensionLoading().validate(settings, connectProps, SQLiteExtensionPermissionStore.getInstance(), () -> {
            SQLiteExtensionConfirmation confirmation = DBWorkbench.getService(SQLiteExtensionConfirmation.class);
            return confirmation != null && confirmation.confirmExtensionLoading(settings, true);
        });
    }

    @NotNull
    private synchronized SQLiteExtensionLoading getExtensionLoading() {
        // generic datasource opens its first connection from the superclass constructor
        if (extensionLoading == null) {
            extensionLoading = new SQLiteExtensionLoading();
        }
        return extensionLoading;
    }

    @NotNull
    @Override
    public Class<? extends DBSObject> getPrimaryChildType(@Nullable DBRProgressMonitor monitor) throws DBException {
        return SQLiteTable.class;
    }

    /**
     * A table of that name, else the main database itself for {@code main} (SQLite compares database names ignoring
     * case), so that a name qualified with {@code main} resolves to this data source's table. A table named
     * {@code main} keeps its name. Only when the children are tables: a subclass whose children are databases
     * (Cloudflare D1) has no single main database.
     */
    @Nullable
    @Override
    public DBSObject getChild(@NotNull DBRProgressMonitor monitor, @NotNull String childName) throws DBException {
        DBSObject child = super.getChild(monitor, childName);
        if (child == null && MAIN_DATABASE_NAME.equalsIgnoreCase(childName)
            && DBSEntity.class.isAssignableFrom(getPrimaryChildType(monitor))) {
            return this;
        }
        return child;
    }

    @NotNull
    @Override
    public ErrorType discoverErrorType(@NotNull Throwable error) {
        if (error instanceof SQLException && ((SQLException) error).getErrorCode() == 19) {
            // SQLite doesn't have a specific Exception SQL state for the constraint violation, but it has a specific vendor code
            return ErrorType.UNIQUE_KEY_VIOLATION;
        }
        return super.discoverErrorType(error);
    }

    public boolean supportsStrictTyping() {
        return isServerVersionAtLeast(3, 37);
    }
}
