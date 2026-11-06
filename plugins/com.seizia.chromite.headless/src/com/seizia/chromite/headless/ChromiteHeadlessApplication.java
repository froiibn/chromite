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
package com.seizia.chromite.headless;

import org.eclipse.equinox.app.IApplicationContext;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.app.DBPApplication;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.rcp.DesktopApplicationImpl;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.DBPPlatformUI;
import com.seizia.chromite.utils.GeneralUtils;
import com.seizia.chromite.utils.RuntimeUtils;

/**
 * Headless application
 */
public class ChromiteHeadlessApplication extends DesktopApplicationImpl {

    private static final Log log = Log.getLog(ChromiteHeadlessApplication.class);

    public ChromiteHeadlessApplication() {
        // Initialize platform
        initializeApplicationServices();
    }

    @NotNull
    @Override
    public Object start(IApplicationContext context) {
        DBPPlatform platform = DBWorkbench.getPlatform();
        DBPApplication application = platform.getApplication();
        if (RuntimeUtils.isWindows()
            && platform.getPreferenceStore().getBoolean(ModelPreferences.PROP_USE_WIN_TRUST_STORE_TYPE)
        ) {
            System.setProperty(GeneralUtils.PROP_TRUST_STORE_TYPE, GeneralUtils.VALUE_TRUST_STORE_TYPE_WINDOWS);
        }
        log.debug("Starting headless test application " + application.getClass().getName());

        return EXIT_OK;
    }

    @Override
    public void stop() {
        log.debug("Starting headless test application");
        super.stop();
    }

    @NotNull
    @Override
    public Class<? extends DBPPlatform> getPlatformClass() {
        return ChromiteTestPlatform.class;
    }

    @Override
    public Class<? extends DBPPlatformUI> getPlatformUIClass() {
        return ChromiteTestPlatformUI.class;
    }

    @Nullable
    @Override
    public String getDefaultProjectName() {
        return "ChromiteTests";
    }

    @Override
    public boolean isHeadlessMode() {
        return true;
    }

    @NotNull
    @Override
    public DBPPreferenceStore getPreferenceStore() {
        return ChromiteTestActivator.getInstance().getPreferences();
    }
}
