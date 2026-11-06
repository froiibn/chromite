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
import com.seizia.chromite.ext.generic.edit.GenericForeignKeyManager;
import com.seizia.chromite.ext.generic.model.GenericTableForeignKey;
import com.seizia.chromite.ext.sqlite.SQLiteUtils;
import com.seizia.chromite.ext.sqlite.model.SQLiteTable;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.List;
import java.util.Map;

public class SQLiteTableForeignKeyManager extends GenericForeignKeyManager {
    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return true;
    }

    @Override
    public boolean canEditObject(@NotNull GenericTableForeignKey object) {
        return true;
    }

    @Override
    public boolean canDeleteObject(@NotNull GenericTableForeignKey object) {
        return true;
    }
 
    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options) throws DBException {
        throw new DBException("Forein key creation needs table recreation");
    }

    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options) throws DBException {
        throw new DBException("Forein key modification needs table recreation");
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options) throws DBException {
        throw new DBException("Forein key deletion needs table recreation");
    }

    @Override
    public void deleteObject(@NotNull DBECommandContext commandContext, @NotNull GenericTableForeignKey object, @NotNull Map<String, Object> options) throws DBException {
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
    protected void createObjectReferences(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext commandContext,
        @NotNull ObjectCreateCommand createCommand
    ) {
        if (createCommand.getObject().getTable() instanceof SQLiteTable table && table.isPersisted()) {
            SQLiteUtils.makeRecreateTableCommand(commandContext, table, createCommand);
        }
    }

}
