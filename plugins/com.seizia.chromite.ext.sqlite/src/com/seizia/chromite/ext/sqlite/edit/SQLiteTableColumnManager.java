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
package com.seizia.chromite.ext.sqlite.edit;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.edit.GenericTableColumnManager;
import com.seizia.chromite.ext.generic.model.GenericTableColumn;
import com.seizia.chromite.ext.sqlite.SQLiteUtils;
import com.seizia.chromite.ext.sqlite.model.SQLiteTable;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectRenamer;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.List;
import java.util.Map;

/**
 * SQLiteTableColumnManager
 */
public class SQLiteTableColumnManager extends GenericTableColumnManager implements DBEObjectRenamer<GenericTableColumn> {

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return true;
    }

    @NotNull
    @Override
    protected ColumnModifier[] getSupportedModifiers(
        GenericTableColumn column, Map<String, Object> options
    ) {
        return new ColumnModifier[]{
            DataTypeModifier, sqliteDefaultModifier, NotNullModifier
        };
    }

    protected SQLiteDefaultModifier sqliteDefaultModifier = new SQLiteDefaultModifier();

    @Override
    public boolean canDeleteObject(@NotNull GenericTableColumn object) {
        return true;
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options) throws DBException {
        throw new DBException("Column deletion needs table recreation");
    }

    @Override
    public void deleteObject(@NotNull DBECommandContext commandContext, @NotNull GenericTableColumn object, @NotNull Map<String, Object> options) throws DBException {
        ObjectDeleteCommand deleteCommand = new ObjectDeleteCommand(object, ModelMessages.model_jdbc_delete_object);
        commandContext.addCommand(
            deleteCommand,
            new DeleteObjectReflector<>(this),
            true);
        if (object.getTable() instanceof SQLiteTable table && table.isPersisted()) {
            SQLiteUtils.makeRecreateTableCommand(commandContext, table, deleteCommand);
        }
    }

    @Override
    protected void addObjectRenameActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectRenameCommand command, @NotNull Map<String, Object> options) {
        final GenericTableColumn column = command.getObject();

        actions.add(
            new SQLDatabasePersistAction(
                "Rename column",
                "ALTER TABLE " + column.getTable().getFullyQualifiedName(DBPEvaluationContext.DDL) + " RENAME "
                    + "COLUMN " +
                    DBUtils.getQuotedIdentifier(column.getDataSource(), command.getOldName()) +
                    " TO " + DBUtils.getQuotedIdentifier(column.getDataSource(), command.getNewName())));
    }

    @Override
    public void renameObject(@NotNull DBECommandContext commandContext, @NotNull GenericTableColumn object, @NotNull Map<String, Object> options, @NotNull String newName) throws DBException {
        processObjectRename(commandContext, object, options, newName);
    }

    private class SQLiteDefaultModifier extends BaseDefaultModifier {
        @Override
        protected void appendDefaultValue(@NotNull StringBuilder sql, @NotNull String defaultValue, boolean useQuotes) {
            sql.append("(");
            super.appendDefaultValue(sql, defaultValue, useQuotes);
            sql.append(")");
        }
    }

}
