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
package com.seizia.chromite.ext.teradata.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.*;
import com.seizia.chromite.ext.generic.model.meta.GenericMetaModel;
import com.seizia.chromite.model.*;
import com.seizia.chromite.model.data.DBDFormatSettings;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.data.DBDValueHandlerProvider;
import com.seizia.chromite.model.exec.DBCQueryTransformProvider;
import com.seizia.chromite.model.exec.DBCQueryTransformType;
import com.seizia.chromite.model.exec.DBCQueryTransformer;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.jdbc.data.handlers.JDBCContentValueHandler;
import com.seizia.chromite.model.impl.sql.QueryTransformerTop;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSTypedObject;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * TeradataMetaModel
 */
public class TeradataMetaModel extends GenericMetaModel implements DBDValueHandlerProvider, DBCQueryTransformProvider
{
    public TeradataMetaModel() {
        super();
    }

    @NotNull
    @Override
    public GenericDataSource createDataSourceImpl(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSourceContainer container) throws DBException {
        return new GenericDataSource(monitor, container, this, new TeradataSQLDialect());
    }

    @NotNull
    @Override
    public GenericTableBase createTableOrViewImpl(@NotNull GenericStructContainer container, @Nullable String tableName, @Nullable String tableType, @Nullable JDBCResultSet dbResult) {
        if (tableType != null && isView(tableType)) {
            return new GenericView(container, tableName, tableType, dbResult);
        } else {
            return new TeradataTable(container, tableName, tableType, dbResult);
        }
    }

    @Override
    public String getTableDDL(
        @NotNull DBRProgressMonitor monitor,
        @NotNull GenericTableBase sourceObject,
        @NotNull Map<String, Object> options
    ) throws DBException {
        GenericDataSource dataSource = sourceObject.getDataSource();
        boolean isView = sourceObject.isView();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, sourceObject, "Read Teradata object DDL")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SHOW " + (isView ? "VIEW" : "TABLE") + " " + sourceObject.getFullyQualifiedName(DBPEvaluationContext.DDL))) {
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    StringBuilder sql = new StringBuilder();
                    while (dbResult.nextRow()) {
                        sql.append(dbResult.getString(1));
                    }
                    String description = sourceObject.getDescription();
                    if (CommonUtils.getOption(options, DBPScriptObject.OPTION_INCLUDE_COMMENTS) && description != null) {
                        sql.append("\n\nCOMMENT ON TABLE ").append(sourceObject.getFullyQualifiedName(DBPEvaluationContext.DDL))
                                .append(" IS ").append(SQLUtils.quoteString(sourceObject, description)).append(";");
                    }
                    return sql.toString();
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
    public String getViewDDL(
        @NotNull DBRProgressMonitor monitor,
        @NotNull GenericView sourceObject,
        @NotNull Map<String, Object> options
    ) throws DBException {
        return getTableDDL(monitor, sourceObject, options);
    }

    @Override
    public String getProcedureDDL(@NotNull DBRProgressMonitor monitor, @NotNull GenericProcedure sourceObject) throws DBException {
        GenericDataSource dataSource = sourceObject.getDataSource();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, sourceObject, "Read Teradata procedure source")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SHOW " + sourceObject.getProcedureType().name() + " " +
                    sourceObject.getFullyQualifiedName(DBPEvaluationContext.DDL))
            ) {
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    StringBuilder sql = new StringBuilder();
                    while (dbResult.nextRow()) {
                        sql.append(dbResult.getString(1));
                    }
                    if (sql.isEmpty()) {
                        return super.getProcedureDDL(monitor, sourceObject);
                    }
                    return sql.toString();
                }
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        }
    }

    @Override
    public String getAutoIncrementClause(GenericTableColumn column) {
        return "GENERATED ALWAYS AS IDENTITY";
    }

    @Nullable
    @Override
    public DBDValueHandler getValueHandler(
        @NotNull DBPDataSource dataSource,
        @NotNull DBDFormatSettings preferences,
        @NotNull DBSTypedObject typedObject
    ) {
        if ("JSON".equals(typedObject.getTypeName())) {
            return JDBCContentValueHandler.INSTANCE;
        }
        return null;
    }

    @Override
    public boolean isTableCommentEditable() {
        return true;
    }

    @Override
    public boolean isTableColumnCommentEditable() {
        return true;
    }

    @Override
    public boolean supportsTriggers(@NotNull GenericDataSource dataSource) {
        return true;
    }

    @NotNull
    @Override
    public JDBCStatement prepareTableTriggersLoadStatement(@NotNull JDBCSession session, @NotNull GenericStructContainer genericStructContainer, @Nullable GenericTableBase forParent) throws SQLException {
        String sql = "SELECT TriggerName as TRIGGER_NAME, TableName as OWNER,\n" +
                "ActionTime,\n" +
                "Event,\n" +
                "CASE EnabledFlag\n" +
                "WHEN 'Y' THEN 'ENABLED'\n" +
                "WHEN 'N' THEN 'DISABLED'\n" +
                "END as status,\n" +
                "CASE Kind\n" +
                "WHEN 'R' THEN 'ROW'\n" +
                "WHEN 'S' THEN 'STATEMENT'\n" +
                "end as triggerKind,\n" +
                "RequestText as definition,\n" +
                "CreateTimeStamp as createDate,\n" +
                "TriggerComment as description\n" +
                "FROM DBC.TriggersV\n" +
                "WHERE SubjectTableDataBaseName=?\n" +
                (forParent != null ? "AND TableName=?" : "");
        JDBCPreparedStatement dbStat = session.prepareStatement(sql);
        dbStat.setString(1, genericStructContainer.getName());
        if (forParent != null) {
            dbStat.setString(2, forParent.getName());
        }
        return dbStat;
    }

    @NotNull
    @Override
    public GenericTableTrigger createTableTriggerImpl(
        @NotNull JDBCSession session,
        @NotNull GenericStructContainer genericStructContainer,
        @NotNull GenericTableBase genericTableBase,
        String triggerName,
        @NotNull JDBCResultSet dbResult
    ) {
        if (CommonUtils.isEmpty(triggerName)) {
            triggerName = JDBCUtils.safeGetString(dbResult, 1);
        }
        String description = JDBCUtils.safeGetString(dbResult, "description");
        return new TeradataTrigger(genericTableBase, triggerName, description, dbResult);
    }

    @Override
    public List<? extends GenericTrigger> loadTriggers(@NotNull DBRProgressMonitor monitor, @NotNull GenericStructContainer container, @Nullable GenericTableBase table) throws DBException {
        if (table == null) {
            return Collections.emptyList();
        }
        try (JDBCSession session = DBUtils.openMetaSession(monitor, container, "Read triggers")) {
            String sql = "SELECT TriggerName,\n" +
                    "ActionTime,\n" +
                    "Event,\n" +
                    "CASE EnabledFlag\n" +
                    "WHEN 'Y' THEN 'ENABLED'\n" +
                    "WHEN 'N' THEN 'DISABLED'\n" +
                    "END as status,\n" +
                    "CASE Kind\n" +
                    "WHEN 'R' THEN 'ROW'\n" +
                    "WHEN 'S' THEN 'STATEMENT'\n" +
                    "end as triggerKind,\n" +
                    "RequestText as definition,\n" +
                    "CreateTimeStamp as createDate,\n" +
                    "TriggerComment as description\n" +
                    "FROM DBC.TriggersV\n" +
                    "WHERE SubjectTableDataBaseName=?\n" +
                    "AND TableName=?";
            try (JDBCPreparedStatement dbStat = session.prepareStatement(sql)) {
                dbStat.setString(1, table.getSchema().getName());
                dbStat.setString(2, table.getName());
                List<GenericTrigger> result = new ArrayList<>();
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        String name = JDBCUtils.safeGetString(dbResult, 1);
                        String description = JDBCUtils.safeGetString(dbResult, "description");
                        result.add(new TeradataTrigger(table, name, description, dbResult));
                    }
                }
                return result;
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, container.getDataSource());
        }
    }

    @Nullable
    @Override
    public DBCQueryTransformer createQueryTransformer(@NotNull DBCQueryTransformType type) {
        if (type == DBCQueryTransformType.RESULT_SET_LIMIT) {
            return new QueryTransformerTop();
        }
        return null;
    }
}
