/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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

package com.seizia.chromite.ext.mssql.edit.generic;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.generic.edit.GenericTriggerManager;
import com.seizia.chromite.ext.mssql.model.generic.SQLServerGenericTrigger;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;

import java.util.List;
import java.util.Map;

/**
 * SQLServerTableTriggerManager
 */
public class SQLServerGenericTriggerManager extends GenericTriggerManager<SQLServerGenericTrigger> {

    @Override
    protected void addObjectDeleteActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectDeleteCommand command,
        @NotNull Map<String, Object> options
    ) {
        SQLServerGenericTrigger trigger = command.getObject();
        DBSObject defaultDatabase = DBUtils.getDefaultContext(trigger.getDataSource(), true).getContextDefaults().getDefaultCatalog();
        if (defaultDatabase != trigger.getTable().getCatalog()) {
            actions.add(new SQLDatabasePersistAction("Set current database", "USE " + DBUtils.getQuotedIdentifier(trigger.getTable().getCatalog()), false)); //$NON-NLS-2$
        }

        super.addObjectDeleteActions(monitor, executionContext, actions, command, options);

        if (defaultDatabase != trigger.getTable().getCatalog()) {
            actions.add(new SQLDatabasePersistAction("Set current schema ", "USE " + DBUtils.getQuotedIdentifier(defaultDatabase), false)); //$NON-NLS-2$
        }
    }
}

