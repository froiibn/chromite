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
package com.seizia.chromite.ext.cubrid.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.cubrid.model.CubridDataSource;
import com.seizia.chromite.ext.cubrid.model.CubridPartition;
import com.seizia.chromite.ext.cubrid.model.CubridTable;
import com.seizia.chromite.ext.cubrid.model.CubridTableColumn;
import com.seizia.chromite.ext.generic.edit.GenericTableColumnManager;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.generic.model.GenericTableColumn;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectRenamer;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.edit.prop.DBECommandComposite;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.DBECommandAbstract;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSDataType;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

public class CubridTableColumnManager extends GenericTableColumnManager implements DBEObjectRenamer<GenericTableColumn> {
    public ColumnModifier<GenericTableColumn> CubridDataTypeModifier = (monitor, column, sql, command) -> {
        final String typeName = column.getTypeName();
        DBPDataKind dataKind = column.getDataKind();
        sql.append(' ').append(typeName);
        String modifiers = SQLUtils.getColumnTypeModifiers(column.getDataSource(), column, typeName, dataKind);
        if (modifiers != null && !typeName.equalsIgnoreCase("STRING")) {
            sql.append(modifiers);
        } else if (modifiers == null && typeName.equalsIgnoreCase("VARCHAR")) {
            sql.append('(').append(column.getPrecision()).append(')');
        }
    };

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        if (container instanceof CubridPartition) {
            return false;
        }
        return super.canCreateObject(container);
    }

    @NotNull
    @Override
    protected CubridTableColumn createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) throws DBException {
        CubridTable table = (CubridTable) container;
        DBSDataType columnType = findBestDataType(table, DBConstants.DEFAULT_DATATYPE_NAMES);
        int columnSize = columnType != null && columnType.getDataKind() == DBPDataKind.STRING ? 100 : 0;

        CubridTableColumn column = new CubridTableColumn(table, null, null, false, false, null);
        column.setOrdinalPosition(table.getCachedAttributes().size() + 1);
        column.setName(getNewColumnName(monitor, context, table));
        column.setTypeName(columnType == null ? "INTEGER" : columnType.getName());
        column.setMaxLength(columnSize);
        column.setRequired(false);
        column.setDescription(null);
        column.setDefaultValue(null);
        column.setAutoIncrement(false);
        column.setPersisted(false);
        return column;
    }

    @NotNull
    @Override
    public StringBuilder getNestedDeclaration(
        @NotNull DBRProgressMonitor monitor,
        @NotNull GenericTableBase owner,
        @NotNull DBECommandAbstract<GenericTableColumn> command,
        @NotNull Map<String, Object> options
    ) {
        StringBuilder decl = new StringBuilder(40);
        CubridTableColumn column = (CubridTableColumn) command.getObject();
        String columnName = DBUtils.getQuotedIdentifier(column.getDataSource(), column.getName());

        if (command instanceof SQLObjectEditor.ObjectRenameCommand) {
            columnName = DBUtils.getQuotedIdentifier(column.getDataSource(), ((ObjectRenameCommand) command).getNewName());
        }
        decl.append(columnName);
        for (ColumnModifier<GenericTableColumn> modifier : new ColumnModifier[] {CubridDataTypeModifier, NotNullModifier}) {
            modifier.appendModifier(monitor, column, decl, command);
        }
        if (((DBECommandComposite) command).hasProperty("required")) {
            decl.append(column.isRequired() ? "" : " NULL");
        }
        if (!CommonUtils.isEmpty(column.getDefaultValue()) || ((DBECommandComposite) command).hasProperty("defaultValue")) {
            if (CommonUtils.isEmpty(column.getDefaultValue())) {
                decl.append(" DEFAULT NULL");
            } else {
                DefaultModifier.appendModifier(monitor, column, decl, command);
            }
        }
        if (column.isAutoIncrement()) {
            decl.append(" AUTO_INCREMENT");
        }
        if (!CommonUtils.isEmpty(column.getDescription()) || ((DBECommandComposite) command).hasProperty("description")) {
            decl.append(" COMMENT ").append(SQLUtils.quoteString(column, CommonUtils.notEmpty(column.getDescription())));
        }
        return decl;
    }

    @Override
    protected void addObjectModifyActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actionList,
        @NotNull ObjectChangeCommand command,
        @NotNull Map<String, Object> options
    ) {
        final CubridTableColumn column = (CubridTableColumn) command.getObject();
        boolean isView = column.getTable().isView();
        String table = column.getTable().getFullyQualifiedName(DBPEvaluationContext.DDL);
        String columnName = DBUtils.getQuotedIdentifier(column.getDataSource(), column.getName());
        String query;
        if (column.isForeignKey() || isView) {
            if (command.hasProperty("description")) {
                query = (isView ? "ALTER VIEW " : "ALTER TABLE ") + table + " COMMENT ON COLUMN " + columnName + " = "
                        + SQLUtils.quoteString(column, CommonUtils.notEmpty(column.getDescription()));
                actionList.add(new SQLDatabasePersistAction("Modify column", query));
            }
        } else {
            query = "ALTER TABLE " + table + " MODIFY " + getNestedDeclaration(monitor, column.getTable(), command, options);
            actionList.add(new SQLDatabasePersistAction("Modify column", query));
        }
    }

    @Override
    protected void addObjectRenameActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectRenameCommand command,
        @NotNull Map<String, Object> options
    ) {
        CubridTableColumn column = (CubridTableColumn) command.getObject();
        CubridDataSource dataSource = (CubridDataSource) column.getDataSource();
        boolean isView = column.getTable().isView();
        String table = column.getTable().getFullyQualifiedName(DBPEvaluationContext.DDL);
        actions.add(new SQLDatabasePersistAction(
            "Rename column",
            (isView ? "ALTER VIEW " : "ALTER TABLE ") + table + " RENAME COLUMN "
            + DBUtils.getQuotedIdentifier(dataSource, command.getOldName()) + " AS "
            + DBUtils.getQuotedIdentifier(dataSource, command.getNewName())
        ));
    }

    @Override
    public void renameObject(
        @NotNull DBECommandContext commandContext,
        @NotNull GenericTableColumn object,
        @NotNull Map<String, Object> options,
        @NotNull String newName
    ) throws DBException {
        if (!((CubridDataSource) object.getDataSource()).isShard()) {
            processObjectRename(commandContext, object, options, newName);
        }
    }

    @NotNull
    protected String getNewColumnName(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull CubridTable parent
    ) throws DBException {
        int suffix = 1;
        while (true) {
            String name = "column" + suffix++;
            if (parent.getAttribute(monitor, name) == null) {
                return name;
            }
        }
    }

    @Override
    public boolean canRenameObject(@NotNull GenericTableColumn object) {
        return !((CubridDataSource) object.getDataSource()).isShard();
    }
}
