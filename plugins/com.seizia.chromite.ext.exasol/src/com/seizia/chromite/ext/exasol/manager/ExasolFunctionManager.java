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
package com.seizia.chromite.ext.exasol.manager;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.exasol.model.ExasolFunction;
import com.seizia.chromite.ext.exasol.model.ExasolSchema;
import com.seizia.chromite.ext.exasol.tools.ExasolUtils;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

public class ExasolFunctionManager extends SQLObjectEditor<ExasolFunction, ExasolSchema> {

    @Override
    public DBSObjectCache<ExasolSchema, ExasolFunction> getObjectsCache(ExasolFunction object) {
        return object.getContainer().functionCache;
    }

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_EDITOR_ON_CREATE;
    }

    @Override
    protected void validateObjectProperties(@NotNull DBRProgressMonitor monitor, @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options)
            throws DBException {
        if (CommonUtils.isEmpty(command.getObject().getName()))
        {
            throw new DBException("Function name cannot be empty");
        }
    }
    

    @Override
    protected ExasolFunction createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) throws DBException {
        ExasolFunction newScript =  new ExasolFunction((ExasolSchema) container);
        newScript.setName("function_name");
        newScript.setObjectDefinitionText("FUNCTION function_name() RETURNS INTEGER");
        return newScript;
    }
    
    private void createOrReplaceScriptQuery(List<DBEPersistAction> actions, ExasolFunction script, Boolean replace)
    {
        actions.add(
                new SQLDatabasePersistAction("Create Script", "OPEN SCHEMA " + script.getSchema().getName()));
        if (replace) { 
            actions.add(
                new SQLDatabasePersistAction("Create Script", "CREATE OR REPLACE " + script.getSql()));
        } else {
        actions.add(
                new SQLDatabasePersistAction("Create Script", "CREATE " + script.getSql()));
        }
    }
    
    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions,
                                          @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options) {
        createOrReplaceScriptQuery(actions, command.getObject(), false);
    }

    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions,
                                          @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options) {
        actions.add(
                new SQLDatabasePersistAction("Create Script", "DROP FUNCTION " + command.getObject().getFullyQualifiedName(DBPEvaluationContext.DDL)));
    }
    
    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actionList,
                                          @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options)
    {
        if (command.getProperties().size() > 1 || command.getProperty("description") == null )
        {
            createOrReplaceScriptQuery(actionList, command.getObject(),true);
        }
    }
    
    @Override
    protected void addObjectExtraActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions,
                                         @NotNull NestedObjectCommand<ExasolFunction, PropertyHandler> command, @NotNull Map<String, Object> options)
    {
        if (command.getProperty("description") != null) {
            actions.add(new SQLDatabasePersistAction("Comment on Script","COMMENT ON FUNCTION " + 
                            command.getObject().getFullyQualifiedName(DBPEvaluationContext.DDL) + " IS " +
                            SQLUtils.quoteString(command.getObject(), ExasolUtils.quoteString(command.getObject().getDescription()))));
        }
    }

}