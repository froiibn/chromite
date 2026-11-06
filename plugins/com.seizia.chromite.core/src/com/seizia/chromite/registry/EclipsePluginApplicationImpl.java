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
package com.seizia.chromite.registry;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.core.ChromiteActivator;
import com.seizia.chromite.core.DesktopPlatform;
import com.seizia.chromite.core.DesktopUI;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.rcp.DesktopApplicationImpl;
import com.seizia.chromite.runtime.ui.DBPPlatformUI;

/**
 * EclipseApplicationImpl
 */
public abstract class EclipsePluginApplicationImpl extends DesktopApplicationImpl {

    public EclipsePluginApplicationImpl() {
        initializeApplicationServices();
    }

    @Override
    public boolean isStandalone() {
        return false;
    }

    @Override
    public boolean isPrimaryInstance() {
        return false;
    }

    @Override
    public boolean isHeadlessMode() {
        return false;
    }

    @Override
    public String getInfoDetails() {
        return "Eclipse";
    }

    @Nullable
    @Override
    public String getDefaultProjectName() {
        return "DBeaver";
    }

    @NotNull
    @Override
    public Class<? extends DBPPlatform> getPlatformClass() {
        return DesktopPlatform.class;
    }

    @Nullable
    @Override
    public Class<? extends DBPPlatformUI> getPlatformUIClass() {
        return DesktopUI.class;
    }

    @NotNull
    @Override
    public DBPPreferenceStore getPreferenceStore() {
        return ChromiteActivator.getInstance().getPreferences();

    }

}
