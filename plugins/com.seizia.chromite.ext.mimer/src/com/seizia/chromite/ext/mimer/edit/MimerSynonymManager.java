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
package com.seizia.chromite.ext.mimer.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.mimer.model.MimerSchema;
import com.seizia.chromite.ext.mimer.model.MimerSynonym;
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
 * Adds CREATE / DROP support for Mimer SQL synonyms, so "Create New Synonym" is available on
 * the Synonyms tree node. No ALTER support beyond Comment - Mimer SQL has no way to retarget or
 * rename an existing synonym, only drop and recreate.
 *
 * @author Mimer Information Technology
 */
public class MimerSynonymManager extends SQLObjectEditor<MimerSynonym, MimerSchema> {

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return DBWorkbench.getPlatform().getWorkspace().hasRealmPermission(RMConstants.PERMISSION_METADATA_EDITOR);
    }

    @Override
    public boolean canEditObject(@NotNull MimerSynonym object) {
        return true;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public DBSObjectCache<? extends DBSObject, MimerSynonym> getObjectsCache(@NotNull MimerSynonym object) {
        // GenericObjectContainer#getSynonymCache() is parameterized on the base GenericSynonym,
        // not MimerSynonym - safe to narrow since MimerMetaModel#createSynonymImpl is the only
        // thing that ever populates it, and it only ever constructs MimerSynonym instances.
        return (DBSObjectCache<? extends DBSObject, MimerSynonym>) (DBSObjectCache<?, ?>)
            ((MimerSchema) object.getParentObject()).getSynonymCache();
    }

    @NotNull
    @Override
    protected String getBaseObjectName() {
        return "NEW_SYNONYM";
    }

    @NotNull
    @Override
    protected MimerSynonym createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new MimerSynonym((MimerSchema) container, getBaseObjectName());
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) {
        MimerSynonym synonym = command.getObject();
        actions.add(new SQLDatabasePersistAction("Create synonym", synonym.getObjectDefinitionText(monitor, options)));
    }

    @Override
    protected void addObjectDeleteActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectDeleteCommand command,
        @NotNull Map<String, Object> options
    ) {
        MimerSynonym synonym = command.getObject();
        // DROP SYNONYM takes no RESTRICT/CASCADE clause - plain drop only.
        actions.add(new SQLDatabasePersistAction("Drop synonym",
            "DROP SYNONYM \"" + synonym.getParentObject().getName().replace("\"", "\"\"")
                + "\".\"" + synonym.getName().replace("\"", "\"\"") + "\""));
    }

    @Override
    protected void addObjectModifyActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actionList,
        @NotNull ObjectChangeCommand command,
        @NotNull Map<String, Object> options
    ) throws DBException {
        if (!command.hasProperty("comment")) {
            return;
        }
        MimerSynonym synonym = command.getObject();
        String name = "\"" + synonym.getParentObject().getName().replace("\"", "\"\"")
            + "\".\"" + synonym.getName().replace("\"", "\"\"") + "\"";
        MimerUtils.addCommentModifyAction(actionList, synonym, "SYNONYM", name, "Comment synonym", monitor);
    }
}
