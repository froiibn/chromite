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
package com.seizia.chromite.core;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import com.seizia.chromite.ChromitePreferences;
import com.seizia.chromite.LogOutputStream;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.impl.preferences.BundlePreferenceStore;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.qm.QMConstants;
import com.seizia.chromite.model.qm.QMObjectType;
import com.seizia.chromite.ui.editors.DatabaseEditorPreferences.BreadcrumbLocation;
import com.seizia.chromite.ui.preferences.UIPreferences;
import com.seizia.chromite.ui.screenreaders.ScreenReader;
import com.seizia.chromite.ui.screenreaders.ScreenReaderPreferences;
import com.seizia.chromite.ui.services.ApplicationPolicyService;
import com.seizia.chromite.utils.PrefUtils;
import com.seizia.chromite.utils.RuntimeUtils;
import com.seizia.chromite.utils.SystemVariablesResolver;

import java.io.File;
import java.util.Arrays;

public class DesktopPreferencesInitializer extends AbstractPreferenceInitializer {
    public DesktopPreferencesInitializer() {
    }

    @Override
    public void initializeDefaultPreferences() {
        // Init default preferences
        DBPPreferenceStore store = new BundlePreferenceStore(ChromiteActivator.getInstance().getBundle());

        // Resources
        //PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.DEFAULT_RESOURCE_ENCODING, GeneralUtils.UTF8_ENCODING);

        // Agent
        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.AGENT_ENABLED, true);
        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.AGENT_LONG_OPERATION_NOTIFY, RuntimeUtils.isWindows());
        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.AGENT_LONG_OPERATION_TIMEOUT, 30);
        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.SECURITY_USE_BOUNCY_CASTLE, true);

        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.NAVIGATOR_EDITOR_FULL_NAME, false);

        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.TEXT_EDIT_UNDO_LEVEL, 200);

        // General UI
        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.UI_AUTO_UPDATE_CHECK,
            !ApplicationPolicyService.getInstance().isInstallUpdateDisabled());
        PrefUtils.setDefaultPreferenceValue(store, UIPreferences.UI_USE_EMBEDDED_AUTH, false);
        PrefUtils.setDefaultPreferenceValue(store, UIPreferences.DRIVER_SELECTOR_SHOW_COMMERCIAL_DRIVERS, false);
        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.UI_SHOW_HOLIDAY_DECORATIONS, true);
        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.UI_STATUS_BAR_SHOW_BREADCRUMBS, BreadcrumbLocation.IN_STATUS_BAR);
        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.UI_STATUS_BAR_SHOW_STATUS_LINE, true);
        // SWT can only rescale at runtime on Windows; elsewhere the Eclipse zoom-restart prompt never helps.
        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.UI_SHOW_ZOOM_RESTART_PROMPT, RuntimeUtils.isWindows());

        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.UI_KEEP_DATABASE_EDITORS, true);
        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.UI_KEEP_DATABASE_EDITORS_ON_DISCONNECT, true);
        PrefUtils.setDefaultPreferenceValue(store, ScreenReaderPreferences.PREF_SCREEN_READER_ACCESSIBILITY, ScreenReader.DEFAULT);
        PrefUtils.setDefaultPreferenceValue(store, ScreenReaderPreferences.PREF_FORCE_FOCUS_ON_EDITOR, false);

        // QM
        PrefUtils.setDefaultPreferenceValue(store, QMConstants.PROP_HISTORY_DAYS, 90);
        PrefUtils.setDefaultPreferenceValue(store, QMConstants.PROP_ENTRIES_PER_PAGE, 200);
        PrefUtils.setDefaultPreferenceValue(store, QMConstants.PROP_OBJECT_TYPES,
            QMObjectType.toString(Arrays.asList(QMObjectType.txn, QMObjectType.query)));
        PrefUtils.setDefaultPreferenceValue(store, QMConstants.PROP_QUERY_TYPES,
            DBCExecutionPurpose.USER + "," + DBCExecutionPurpose.USER_FILTERED + "," + DBCExecutionPurpose.USER_SCRIPT);
        PrefUtils.setDefaultPreferenceValue(store, QMConstants.PROP_STORE_LOG_FILE, false);

        // Logs
        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.LOGS_DEBUG_ENABLED, true);
        PrefUtils.setDefaultPreferenceValue(store, ChromitePreferences.LOGS_DEBUG_LOCATION,
            "${" + SystemVariablesResolver.VAR_WORKSPACE + "}" + File.separator + ".metadata" + File.separator + DBConstants.DEBUG_LOG_FILE_NAME);

        PrefUtils.setDefaultPreferenceValue(store, LogOutputStream.LOGS_MAX_FILE_SIZE, LogOutputStream.DEFAULT_MAX_LOG_SIZE);
        PrefUtils.setDefaultPreferenceValue(store, LogOutputStream.LOGS_MAX_FILES_COUNT, LogOutputStream.DEFAULT_MAX_LOG_FILES_COUNT);
    }
}
