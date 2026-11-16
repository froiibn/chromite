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

package com.seizia.chromite.ext.dameng.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.dameng.model.DamengDataSource;
import com.seizia.chromite.ext.dameng.model.DamengSchema;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
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
 * @author Shengkai Bai
 */
public class DamengSchemaManager extends SQLObjectEditor<DamengSchema, DamengDataSource> {

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return true;
    }

    @Override
    public boolean canDeleteObject(@NotNull DamengSchema object) {
        return true;
    }

    @NotNull
    @Override
    protected String getBaseObjectName() {
        return "NEW_SCHEMA";
    }

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    @Override
    public DBSObjectCache<? extends DBSObject, DamengSchema> getObjectsCache(DamengSchema object) {
        return object.getDataSource().getSchemaCache();
    }


    @Override
    protected DamengSchema createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        DamengDataSource dataSource = (DamengDataSource) container;
        DamengSchema damengSchema = new DamengSchema(dataSource, "NEW_SCHEMA", false);
        setNewObjectName(monitor, dataSource, damengSchema);
        return damengSchema;
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull SQLObjectEditor<DamengSchema, DamengDataSource>.ObjectCreateCommand command, @NotNull Map<String, Object> options) throws DBException {
        actions.add(
                new SQLDatabasePersistAction(
                        "Create schema",
                        "CREATE SCHEMA " + DBUtils.getObjectFullName(command.getObject(), DBPEvaluationContext.DDL))
        );
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions, @NotNull SQLObjectEditor<DamengSchema, DamengDataSource>.ObjectDeleteCommand command, @NotNull Map<String, Object> options) throws DBException {
        actions.add(
                new SQLDatabasePersistAction(
                        "Drop schema",
                        "DROP SCHEMA " + DBUtils.getObjectFullName(command.getObject(), DBPEvaluationContext.DDL) + " RESTRICT")
        );
    }

}
