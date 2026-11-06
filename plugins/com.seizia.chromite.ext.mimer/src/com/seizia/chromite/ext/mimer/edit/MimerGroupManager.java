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
import com.seizia.chromite.ext.mimer.model.MimerDataSource;
import com.seizia.chromite.ext.mimer.model.MimerGroup;
import com.seizia.chromite.ext.mimer.model.MimerUtils;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.rm.RMConstants;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.runtime.DBWorkbench;

import java.util.List;
import java.util.Map;

/**
 * Adds CREATE / DROP support for Mimer SQL groups, so "Create New Group" is available on
 * the DBA Views &gt; Groups tree node: {@code CREATE IDENT "n" AS GROUP} /
 * {@code DROP IDENT "n" [CASCADE]}. {@code CASCADE} is offered via the delete-confirmation
 * "Cascade" checkbox with an extra "are you sure" prompt (see {@link MimerCascadeDropUtil});
 * omitted, {@code RESTRICT} is implicit and the drop fails loud rather than silently revoking
 * every privilege the group held. See {@link MimerGroupMemberManager} for group
 * membership (GRANT/REVOKE MEMBER).
 *
 * @author Mimer Information Technology
 */
public class MimerGroupManager extends SQLObjectEditor<MimerGroup, MimerDataSource> {

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY | FEATURE_DELETE_CASCADE;
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return DBWorkbench.getPlatform().getWorkspace().hasRealmPermission(RMConstants.PERMISSION_METADATA_EDITOR);
    }

    @Override
    public boolean canEditObject(@NotNull MimerGroup object) {
        return true;
    }

    @Nullable
    @Override
    public DBSObjectCache<MimerDataSource, MimerGroup> getObjectsCache(@NotNull MimerGroup object) {
        return object.getDataSource().getGroupCache();
    }

    @NotNull
    @Override
    protected String getBaseObjectName() {
        return "NEW_GROUP";
    }

    @NotNull
    @Override
    protected MimerGroup createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new MimerGroup((MimerDataSource) container, getBaseObjectName());
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) {
        actions.add(new SQLDatabasePersistAction("Create group", command.getObject().buildCreateDDL()));
    }

    @Override
    protected void addObjectDeleteActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectDeleteCommand command,
        @NotNull Map<String, Object> options
    ) {
        actions.add(MimerCascadeDropUtil.dropAction("Drop group",
            command.getObject().buildDropDDL(), options, "group", command.getObject().getName(), executionContext));
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
        MimerGroup group = command.getObject();
        String quotedName = "\"" + group.getName().replace("\"", "\"\"") + "\"";
        MimerUtils.addCommentModifyAction(actionList, group, "IDENT", quotedName, "Comment group", monitor);
    }
}
