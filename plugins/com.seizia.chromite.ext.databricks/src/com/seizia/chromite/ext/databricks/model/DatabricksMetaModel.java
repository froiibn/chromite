/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ext.databricks.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.databricks.DatabricksConstants;
import com.seizia.chromite.ext.databricks.DatabricksDataSource;
import com.seizia.chromite.ext.databricks.model.types.DatabricksDataTypeCache;
import com.seizia.chromite.ext.generic.model.*;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModel;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCQueryTransformProvider;
import com.seizia.chromite.model.exec.DBCQueryTransformType;
import com.seizia.chromite.model.exec.DBCQueryTransformer;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCBasicDataTypeCache;
import com.seizia.chromite.model.impl.jdbc.struct.JDBCDataType;
import com.seizia.chromite.model.impl.sql.QueryTransformerLimit;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.format.SQLFormatUtils;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DatabricksMetaModel extends GenericMetaModel implements DBCQueryTransformProvider {

    private static final Log log = Log.getLog(DatabricksMetaModel.class);

    private List<ViewInfo> tempViewsList = new ArrayList<>();

    public DatabricksMetaModel() {
    }

    @Nullable
    @Override
    public DBCQueryTransformer createQueryTransformer(@NotNull DBCQueryTransformType type) {
        if (type == DBCQueryTransformType.RESULT_SET_LIMIT) {
            return new QueryTransformerLimit(false, false);
        }
        return null;
    }

    @NotNull
    @Override
    public GenericDataSource createDataSourceImpl(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSourceContainer container) throws DBException {
        return new DatabricksDataSource(monitor, container, this);
    }

    @Override
    public GenericSchema createSchemaImpl(
        @NotNull GenericDataSource dataSource,
        @Nullable GenericCatalog catalog,
        @NotNull String schemaName
    ) {
        return new DatabricksSchema(dataSource, catalog, schemaName);
    }

    @Nullable
    @Override
    public List<GenericSchema> loadSchemas(
        @NotNull JDBCSession session,
        @NotNull GenericDataSource dataSource,
        @Nullable GenericCatalog catalog
    ) throws DBException {
        List<GenericSchema> schemas = new ArrayList<>();
        try (JDBCPreparedStatement dbStat = session.prepareStatement(
            "SHOW SCHEMAS" + (catalog == null ? "" : " IN " + DBUtils.getQuotedIdentifier(catalog))
        )) {
            dbStat.executeStatement();
            try (JDBCResultSet dbResult = dbStat.getResultSet()) {
                while (dbResult.next()) {
                    String schemaName = JDBCUtils.safeGetStringTrimmed(dbResult, DatabricksConstants.SCHEMA_NAME);
                    schemas.add(new DatabricksSchema(dataSource, catalog, schemaName));
                }
            }
        } catch (SQLException e) {
            log.debug("Cannot load schemas with query", e);
            return super.loadSchemas(session, dataSource, catalog);
        }

        return schemas;
    }

    @Override
    public boolean isSystemSchema(GenericSchema schema) {
        return "global_temp".equals(schema.getName()) || "information_schema".equalsIgnoreCase(schema.getName());
    }

    @Override
    public JDBCStatement prepareTableLoadStatement(
        @NotNull JDBCSession session,
        @NotNull GenericStructContainer owner,
        @Nullable GenericTableBase object,
        @Nullable String objectName
    ) throws SQLException {
        DBSCatalog originalCatalog = null;
        try {
            var contextDefaults = session.getExecutionContext().getContextDefaults();
            if (contextDefaults != null) {
                originalCatalog = contextDefaults.getDefaultCatalog();
                if (contextDefaults.getDefaultCatalog() == null) {
                    contextDefaults.setDefaultCatalog(session.getProgressMonitor(), owner.getCatalog(), null);
                }
            }
        } catch (DBCException e) {
            log.debug("Can't read current views list", e);
        }

        try {
            try (JDBCStatement statement = session.createStatement()) {
                statement.execute("USE CATALOG " + DBUtils.getQuotedIdentifier(owner.getCatalog()));
                try (JDBCPreparedStatement dbStat = session.prepareStatement("SHOW VIEWS IN " + DBUtils.getQuotedIdentifier(owner))) {
                    try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                        while (dbResult.next()) {
                            String namespace = JDBCUtils.safeGetString(dbResult, "namespace");
                            if (CommonUtils.isEmpty(namespace)) {
                                // Probably temporary view
                                continue;
                            }
                            String viewName = JDBCUtils.safeGetString(dbResult, "viewName");
                            if (CommonUtils.isNotEmpty(viewName)) {
                                tempViewsList.add(new ViewInfo(owner, viewName));
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("Can't read current views list", e);
        } finally {
            if (originalCatalog != null && originalCatalog != owner.getCatalog()) {
                try (JDBCStatement statement = session.createStatement()) {
                    statement.execute("USE CATALOG " + DBUtils.getQuotedIdentifier(originalCatalog));
                } catch (SQLException e) {
                    log.debug("Can't restore original catalog", e);
                }
            }
        }

        return super.prepareTableLoadStatement(session, owner, object, objectName);
    }

    @NotNull
    @Override
    public GenericTableBase createTableOrViewImpl(
        @NotNull GenericStructContainer container,
        @Nullable String tableName,
        @Nullable String tableType,
        @Nullable JDBCResultSet dbResult) {
        if ((CommonUtils.isNotEmpty(tableName) && !tempViewsList.isEmpty()
             && tempViewsList.stream().anyMatch(e -> e.name.equalsIgnoreCase(tableName))) ||
            tableType != null && isView(tableType))
        {
            return new DatabricksView(
                container,
                tableName,
                tableType,
                dbResult);
        } else {
            return new DatabricksTable(
                container,
                tableName,
                tableType,
                dbResult);
        }
    }

    @Override
    public boolean supportsTableDDLSplit(@NotNull GenericTableBase sourceObject) {
        return false;
    }

    @Override
    public boolean isTableCommentEditable() {
        return true;
    }

    @Override
    public String getTableDDL(@NotNull DBRProgressMonitor monitor, @NotNull GenericTableBase sourceObject, @NotNull Map<String, Object> options) throws DBException {
        GenericDataSource dataSource = sourceObject.getDataSource();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, sourceObject, "Read Databricks view/table source")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SHOW CREATE TABLE " + sourceObject.getFullyQualifiedName(DBPEvaluationContext.DDL))) {
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    StringBuilder sql = new StringBuilder();
                    while (dbResult.next()) {
                        String line = dbResult.getString(1);
                        if (line == null) {
                            continue;
                        }
                        sql.append(line).append("\n");
                    }
                    String ddl = sql.toString();
                    if (sourceObject.isView()) {
                        ddl = ddl.replace("CREATE VIEW", "CREATE OR REPLACE VIEW");
                        return SQLFormatUtils.formatSQL(sourceObject.getDataSource(), ddl);
                    }
                    return ddl;
                }
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    @Override
    public String getViewDDL(@NotNull DBRProgressMonitor monitor, @NotNull GenericView sourceObject, @NotNull Map<String, Object> options) throws DBException {
        return getTableDDL(monitor, sourceObject, options);
    }

    private static class ViewInfo {
        GenericStructContainer schema;
        String name;

        ViewInfo(GenericStructContainer schema, String name) {
            this.schema = schema;
            this.name = name;
        }
    }

    @Override
    public JDBCBasicDataTypeCache<GenericStructContainer, ? extends JDBCDataType> createDataTypeCache(
        @NotNull GenericStructContainer container
    ) {
        return new DatabricksDataTypeCache(container);
    }
}
