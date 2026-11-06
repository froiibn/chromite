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
package com.seizia.chromite.ui.editors.data.internal;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.impl.preferences.BundlePreferenceStore;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import org.osgi.framework.BundleContext;

public class DataEditorsActivator extends AbstractUIPlugin {

    // The plug-in ID
    public static final String PLUGIN_ID = "com.seizia.chromite.ui.editors.data";

    // The shared instance
    @Nullable
    private static DataEditorsActivator plugin;
    @Nullable
    private DBPPreferenceStore preferences;

    public DataEditorsActivator() {
    }

    @Override
    public void start(@NotNull BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        preferences = new BundlePreferenceStore(getBundle());

    }

    @Override
    public void stop(@NotNull BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    @Nullable
    public static DataEditorsActivator getDefault() {
        return plugin;
    }

    @Nullable
    public DBPPreferenceStore getPreferences() {
        return preferences;
    }
}
