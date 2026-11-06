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
package com.seizia.chromite.ext.db2.manager;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.db2.model.DB2DataSource;
import com.seizia.chromite.ext.db2.model.DB2Schema;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
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

/**
 * DB2 Schema Manager
 * 
 * @author Denis Forveille
 */
public class DB2SchemaManager extends SQLObjectEditor<DB2Schema, DB2DataSource> {

    private static final String SQL_CREATE_SCHEMA = "CREATE SCHEMA %s";
    private static final String SQL_DROP_SCHEMA = "DROP SCHEMA %s RESTRICT";

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource)
    {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, DB2Schema> getObjectsCache(DB2Schema object)
    {
        return object.getDataSource().getSchemaCache();
    }

    @Override
    protected DB2Schema createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new DB2Schema((DB2DataSource) container, "NEW_SCHEMA");
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options)
    {
        SQLDatabasePersistAction action = new SQLDatabasePersistAction("Create schema", String.format(SQL_CREATE_SCHEMA,
            DBUtils.getQuotedIdentifier(command.getObject())));
        actions.add(action);
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options)
    {
        String schemaName = command.getObject().getName();
        DBEPersistAction action = new SQLDatabasePersistAction("Drop schema (SQL)", String.format(SQL_DROP_SCHEMA,
            DBUtils.getQuotedIdentifier(command.getObject())));
        actions.add(action);
    }

}
