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
package com.seizia.chromite.ext.mimer.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.mimer.model.MimerSchema;
import com.seizia.chromite.ext.mimer.model.MimerStatement;
import com.seizia.chromite.ext.mimer.model.MimerUtils;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.rm.RMConstants;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.runtime.DBWorkbench;

import java.util.List;
import java.util.Map;

/**
 * CREATE/DROP support for Mimer SQL statements. Mimer SQL has no {@code ALTER STATEMENT} that
 * changes the text (only {@code ... REFRESH}, a recompile, not modeled here), so editing an
 * existing statement's body drops and recreates it - same pattern as {@link MimerModuleManager}.
 * {@code DROP STATEMENT} takes no {@code CASCADE} clause at all, unlike Domain/Schema/Trigger/
 * Module here, so no {@code FEATURE_DELETE_CASCADE}. {@link #addObjectCreateActions} executes {@link
 * MimerStatement#getObjectDefinitionText} verbatim - the configurator dialog seeds it with a
 * header + placeholder body, finished via the Source tab.
 *
 * @author Mimer Information Technology
 */
public class MimerStatementManager extends SQLObjectEditor<MimerStatement, MimerSchema> {

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_EDITOR_ON_CREATE;
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return DBWorkbench.getPlatform().getWorkspace().hasRealmPermission(RMConstants.PERMISSION_METADATA_EDITOR);
    }

    @Override
    public boolean canEditObject(@NotNull MimerStatement object) {
        return true;
    }

    @Nullable
    @Override
    public DBSObjectCache<? extends DBSObject, MimerStatement> getObjectsCache(@NotNull MimerStatement object) {
        return object.getSchema().getStatementCache();
    }

    @NotNull
    @Override
    protected String getBaseObjectName() {
        return "NEW_STATEMENT";
    }

    @NotNull
    @Override
    protected MimerStatement createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new MimerStatement((MimerSchema) container, getBaseObjectName());
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        actions.add(new SQLDatabasePersistAction("Create statement", command.getObject().getObjectDefinitionText(monitor, options)));
    }

    @Override
    protected void addObjectDeleteActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectDeleteCommand command,
        @NotNull Map<String, Object> options
    ) {
        actions.add(new SQLDatabasePersistAction("Drop statement", buildDropStatement(command.getObject())));
    }

    /**
     * See the class Javadoc - Mimer SQL has no text-changing {@code ALTER STATEMENT}, so editing an
     * existing statement's body means dropping and recreating it. Fires only when the body
     * actually changed.
     */
    @Override
    protected void addObjectModifyActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actionList,
        @NotNull ObjectChangeCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        MimerStatement statement = command.getObject();
        if (command.hasProperty("objectDefinitionText")) {
            actionList.add(new SQLDatabasePersistAction("Drop statement", buildDropStatement(statement)));
            actionList.add(new SQLDatabasePersistAction("Create statement", statement.getObjectDefinitionText(monitor, options)));
        }
        if (command.hasProperty("comment")) {
            String name = "\"" + statement.getSchema().getName().replace("\"", "\"\"")
                + "\".\"" + statement.getName().replace("\"", "\"\"") + "\"";
            MimerUtils.addCommentModifyAction(actionList, statement, "STATEMENT", name, "Comment statement", monitor);
        }
    }

    @NotNull
    private static String buildDropStatement(@NotNull MimerStatement statement) {
        return "DROP STATEMENT \"" + statement.getSchema().getName().replace("\"", "\"\"")
            + "\".\"" + statement.getName().replace("\"", "\"\"") + "\"";
    }
}
