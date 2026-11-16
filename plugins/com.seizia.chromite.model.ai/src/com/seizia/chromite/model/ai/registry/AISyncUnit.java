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
package com.seizia.chromite.model.ai.registry;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.WorkspaceConfigEventManager;
import com.seizia.chromite.model.app.DBPWorkspace;
import com.seizia.chromite.model.ai.internal.AIMessages;
import com.seizia.chromite.model.sync.DBPFileSyncUnit;
import com.seizia.chromite.model.sync.DBPSyncScope;
import com.seizia.chromite.model.sync.DBPSyncTarget;
import com.seizia.chromite.registry.BasePlatformImpl;

import java.util.Map;

public class AISyncUnit extends DBPFileSyncUnit {

    public AISyncUnit() {
        super(
            "ai",
            AIMessages.ai_sync_unit_name,
            DBPWorkspace.METADATA_FOLDER + "/" + BasePlatformImpl.CONFIG_FOLDER
                + "/" + AISettingsManager.AI_CONFIGURATION_FILE_NAME,
            DBPSyncScope.WORKSPACE,
            true);
    }

    @Override
    public void write(@NotNull DBPSyncTarget target, @NotNull Map<String, byte[]> resources) throws DBException {
        super.write(target, resources);
        WorkspaceConfigEventManager.fireConfigChangedEvent(AISettingsManager.AI_CONFIGURATION_FILE_NAME);
    }
}
