/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.debug.ui.internal;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.*;
import com.seizia.chromite.debug.core.DebugUtils;
import com.seizia.chromite.debug.ui.DatabaseLaunchShortcut;
import com.seizia.chromite.model.struct.DBSObject;

import java.util.Map;

public class DatabaseDebugLaunchShortcut extends DatabaseLaunchShortcut {

    private static final String CONFIG_TYPE = "com.seizia.chromite.debug.launchConfiguration";

    public DatabaseDebugLaunchShortcut() {
        super(CONFIG_TYPE, "Database Debug");
    }

    @Override
    protected ILaunchConfiguration createConfiguration(DBSObject launchable, Map<String, Object> databaseContext) throws CoreException {
        ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType type = manager.getLaunchConfigurationType(CONFIG_TYPE);
        ILaunchConfigurationWorkingCopy launchConfig = type.newInstance(null, launchable.getName());

        if (databaseContext != null) {
            DebugUtils.putContextInConfiguration(launchConfig, databaseContext);
        }

        return launchConfig;
    }

    @Override
    protected boolean isCandidate(ILaunchConfiguration config, DBSObject launchable, Map<String, Object> databaseContext) throws CoreException {
        return super.isCandidate(config, launchable, databaseContext);
    }

}
