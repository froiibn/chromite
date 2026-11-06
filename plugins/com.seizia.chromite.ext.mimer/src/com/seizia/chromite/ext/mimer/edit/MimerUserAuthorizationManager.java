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
import com.seizia.chromite.ext.mimer.model.MimerUser;
import com.seizia.chromite.ext.mimer.model.MimerUserAuthorization;
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
 * CREATE / DROP support for a Mimer SQL user's login authorizations.
 * {@code ALTER IDENT "u" ADD OS_USER '...'} / {@code ALTER IDENT "u" DROP
 * OS_USER '...'} (no {@code CASCADE} - nothing depends on an authorization). No modify support -
 * there's no "SET" form, only add/drop by value, same as every other add/drop-only ident-related
 * manager in this plugin.
 *
 * @author Mimer Information Technology
 */
public class MimerUserAuthorizationManager extends SQLObjectEditor<MimerUserAuthorization, MimerUser> {

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return DBWorkbench.getPlatform().getWorkspace().hasRealmPermission(RMConstants.PERMISSION_METADATA_EDITOR);
    }

    @Override
    public boolean canEditObject(@NotNull MimerUserAuthorization object) {
        return false;
    }

    @Nullable
    @Override
    public DBSObjectCache<MimerUser, MimerUserAuthorization> getObjectsCache(@NotNull MimerUserAuthorization object) {
        return object.getUser().getAuthorizationCache();
    }

    @NotNull
    @Override
    protected String getBaseObjectName() {
        return "NEW_OS_USER";
    }

    @NotNull
    @Override
    protected MimerUserAuthorization createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new MimerUserAuthorization((MimerUser) container, getBaseObjectName());
    }

    @Override
    protected void addObjectCreateActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectCreateCommand command,
        @NotNull Map<String, Object> options
    ) {
        actions.add(new SQLDatabasePersistAction("Add authorization", command.getObject().buildAddDDL()));
    }

    @Override
    protected void addObjectDeleteActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<DBEPersistAction> actions,
        @NotNull ObjectDeleteCommand command,
        @NotNull Map<String, Object> options
    ) {
        actions.add(new SQLDatabasePersistAction("Drop authorization", command.getObject().buildDropDDL()));
    }
}
