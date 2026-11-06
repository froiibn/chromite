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
package com.seizia.chromite.ext.mssql.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.mssql.SQLServerConstants;
import com.seizia.chromite.ext.mssql.model.SQLServerExtendedProperty;
import com.seizia.chromite.ext.mssql.model.SQLServerExtendedPropertyOwner;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;

import java.util.List;
import java.util.Map;

public class SQLServerExtendedPropertyManager extends SQLObjectEditor<SQLServerExtendedProperty, SQLServerExtendedPropertyOwner> {
    @Override
    protected SQLServerExtendedProperty createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) throws DBException {
        final SQLServerExtendedPropertyOwner owner = (SQLServerExtendedPropertyOwner) container;

        return new SQLServerExtendedProperty(
            owner,
            owner.getDataSource().getLocalDataType(SQLServerConstants.TYPE_NVARCHAR),
            "New property",
            ""
        );
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options) throws DBException {
        actions.add(new SQLDatabasePersistAction(
            "Create extended property",
            command.getObject().getObjectDefinitionText(monitor, false, false)
        ));
    }

    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options) throws DBException {
        actions.add(new SQLDatabasePersistAction(
            "Alter extended property",
            command.getObject().getObjectDefinitionText(monitor, true, false)
        ));
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options) throws DBException {
        actions.add(new SQLDatabasePersistAction(
            "Drop extended property",
            command.getObject().getObjectDefinitionText(monitor, false, true)
        ));
    }

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, SQLServerExtendedProperty> getObjectsCache(SQLServerExtendedProperty object) {
        return object.getParentObject().getExtendedPropertyCache();
    }

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return 0;
    }
}
