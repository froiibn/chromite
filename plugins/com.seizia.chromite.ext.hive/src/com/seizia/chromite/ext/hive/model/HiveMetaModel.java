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
package com.seizia.chromite.ext.hive.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.*;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModel;
import com.seizia.chromite.ext.hive.model.plan.HiveQueryPlanner;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.plan.DBCQueryPlanner;
import com.seizia.chromite.model.impl.jdbc.JDBCDataSource;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.format.SQLFormatUtils;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSIndexType;

import java.sql.SQLException;
import java.util.Map;

/**
 * HiveMetaModel
 */
public class HiveMetaModel extends GenericMetaModel
{
    public HiveMetaModel() {
        super();
    }

    @NotNull
    @Override
    public GenericDataSource createDataSourceImpl(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSourceContainer container) throws DBException {
        return new HiveDataSource(monitor, container, this);
    }

    @NotNull
    @Override
    public DBCQueryPlanner getQueryPlanner(@NotNull GenericDataSource dataSource) {
        return new HiveQueryPlanner((HiveDataSource) dataSource);
    }

    @NotNull
    @Override
    public GenericTableBase createTableOrViewImpl(@NotNull GenericStructContainer container, @Nullable String tableName, @Nullable String tableType, @Nullable JDBCResultSet dbResult) {
        if (tableType != null && isView(tableType)) {
            return new GenericView(
                    container,
                    tableName,
                    tableType,
                    dbResult);
        }
        return new HiveTable(container, tableName, tableType, dbResult);
    }

    @NotNull
    @Override
    public GenericTableIndex createIndexImpl(@NotNull GenericTableBase table, boolean nonUnique, String qualifier, long cardinality, String indexName, DBSIndexType indexType, boolean persisted) {
        return new HiveIndex((HiveTable) table, indexName, persisted, "", indexType.getName(), null);
    }

    @Override
    public String getTableDDL(@NotNull DBRProgressMonitor monitor, @NotNull GenericTableBase sourceObject, @NotNull Map<String, Object> options) throws DBException {
        GenericDataSource dataSource = sourceObject.getDataSource();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, sourceObject, "Read Hive view/table source")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SHOW CREATE TABLE " + sourceObject.getFullyQualifiedName(DBPEvaluationContext.DDL)))
            {
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
    public boolean supportsTableDDLSplit(@NotNull GenericTableBase sourceObject) {
        return false;
    }

    @Override
    public String getViewDDL(@NotNull DBRProgressMonitor monitor, @NotNull GenericView sourceObject, @NotNull Map<String, Object> options) throws DBException {
        return getTableDDL(monitor, sourceObject, options);
    }

    @Override
    public GenericTableColumn createTableColumnImpl(
        @NotNull DBRProgressMonitor monitor,
        @Nullable JDBCResultSet dbResult,
        @NotNull GenericTableBase table,
        String columnName,
        String typeName,
        int valueType,
        int sourceType,
        int ordinalPos,
        long columnSize,
        long charLength,
        Integer scale,
        Integer precision,
        int radix,
        boolean notNull,
        String remarks,
        String defaultValue,
        boolean autoIncrement,
        boolean autoGenerated
    ) {
        return new HiveTableColumn(table, columnName, typeName, valueType, sourceType, ordinalPos, columnSize, charLength, scale, precision, radix, notNull, remarks, defaultValue, autoIncrement, autoGenerated);
    }

    @Override
    public boolean supportsNotNullColumnModifiers(DBSObject object) {
        DBPDataSource dataSource = object.getDataSource();
        if (dataSource instanceof JDBCDataSource) {
            return ((JDBCDataSource) dataSource).isServerVersionAtLeast(3, 0);
        } else {
            return false;
        }

    }
}
