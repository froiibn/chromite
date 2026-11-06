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
import com.seizia.chromite.ext.mimer.model.MimerDatabank;
import com.seizia.chromite.ext.mimer.model.MimerDatabankPrivilege;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.rm.RMConstants;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.runtime.DBWorkbench;

import java.util.Map;

/**
 * Adds CREATE / DROP support for Mimer SQL databank privileges, so a databank's "Privileges"
 * folder can grant/revoke the right to create tables / sequences in it: {@code GRANT
 * TABLE|SEQUENCE ON DATABANK "n" TO "ident" [WITH GRANT OPTION]} / {@code REVOKE TABLE|SEQUENCE
 * ON DATABANK "n" FROM "ident"}. No {@code [RESTRICT|CASCADE]} clause, same convention as {@link
 * MimerObjectPrivilegeManager}/{@link MimerSequencePrivilegeManager}.
 *
 * @author Mimer Information Technology
 */
public class MimerDatabankPrivilegeManager extends AbstractMimerPrivilegeManager<MimerDatabankPrivilege, MimerDatabank> {

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        // Mimer SQL's built-in system databanks (SQLDB/TRANSDB/LOGDB/SYSDB) can't be granted on - the
        // "Privileges" folder is hidden for them (plugin.xml visibleIf), this is the backstop.
        if (container instanceof MimerDatabank databank && databank.isSystemDatabank()) {
            return false;
        }
        return DBWorkbench.getPlatform().getWorkspace().hasRealmPermission(RMConstants.PERMISSION_METADATA_EDITOR);
    }

    @Nullable
    @Override
    public DBSObjectCache<MimerDatabank, MimerDatabankPrivilege> getObjectsCache(@NotNull MimerDatabankPrivilege object) {
        return object.getDatabank().getPrivilegeCache();
    }

    @NotNull
    @Override
    protected MimerDatabankPrivilege createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new MimerDatabankPrivilege((MimerDatabank) container, getBaseObjectName(), MimerDatabankPrivilege.PRIVILEGE_TYPES[0]);
    }
}
