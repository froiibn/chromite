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
package com.seizia.chromite.ext.timeplus.edit;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.generic.edit.GenericTableColumnManager;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.generic.model.GenericTableColumn;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.DBECommandAbstract;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

public class TimeplusTableColumnManager extends GenericTableColumnManager {

    @Override
    public boolean canEditObject(@NotNull GenericTableColumn object) {
        return !object.isPersisted();
    }

    @Override
    public boolean canDeleteObject(@NotNull GenericTableColumn object) {
        return !object.isPersisted();
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) {
        GenericTableBase table = command.getObject().getTable();
        String sql = "ALTER STREAM " + DBUtils.getObjectFullName(table, DBPEvaluationContext.DDL)
            + " ADD COLUMN " + getNestedDeclaration(monitor, table, command, options);
        actions.add(new SQLDatabasePersistAction(ModelMessages.model_jdbc_create_new_table_column, sql));
    }

    @Override
    public StringBuilder getNestedDeclaration(
        @NotNull DBRProgressMonitor monitor,
        @NotNull GenericTableBase owner,
        @NotNull DBECommandAbstract<GenericTableColumn> command,
        @NotNull Map<String, Object> options
    ) {
        StringBuilder declaration = super.getNestedDeclaration(monitor, owner, command, options);
        String description = command.getObject().getDescription();
        if (CommonUtils.isNotEmpty(description)) {
            declaration.append(" COMMENT ").append(SQLUtils.quoteString(command.getObject(), description));
        }
        return declaration;
    }
}
