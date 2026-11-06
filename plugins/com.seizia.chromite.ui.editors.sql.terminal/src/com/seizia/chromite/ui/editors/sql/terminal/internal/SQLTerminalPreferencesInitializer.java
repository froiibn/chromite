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
package com.seizia.chromite.ui.editors.sql.terminal.internal;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.editors.sql.terminal.SQLTerminalPreferencesConstants;
import com.seizia.chromite.utils.PrefUtils;

public class SQLTerminalPreferencesInitializer extends AbstractPreferenceInitializer {

    public SQLTerminalPreferencesInitializer() {
    }

    @Override
    public void initializeDefaultPreferences() {
        // Init default preferences
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        PrefUtils.setDefaultPreferenceValue(store, SQLTerminalPreferencesConstants.SHOW_TERMINAL_VIEW_BY_DEFAULT, false);
        PrefUtils.setDefaultPreferenceValue(store, SQLTerminalPreferencesConstants.SHOW_QUERY_TEXT, true);
        PrefUtils.setDefaultPreferenceValue(store, SQLTerminalPreferencesConstants.SHOW_SERVER_OUTPUT, true);
    }

}
