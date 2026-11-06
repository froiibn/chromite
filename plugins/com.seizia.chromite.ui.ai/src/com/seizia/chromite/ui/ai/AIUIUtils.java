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
package com.seizia.chromite.ui.ai;

import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Shell;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.ai.AIContextSettings;
import com.seizia.chromite.model.ai.AIIcons;
import com.seizia.chromite.model.ai.registry.AISettingsManager;
import com.seizia.chromite.model.rm.RMConstants;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.ai.internal.AIUIMessages;
import com.seizia.chromite.ui.ai.preferences.AIPreferencePageEngines;


public class AIUIUtils {
    private static final Log log = Log.getLog(AIUIUtils.class);

    private AIUIUtils() {
        // prevents instantiation
    }

    public static boolean confirmMetaTransfer(@NotNull AIContextSettings settings) {
        if (settings.isMetaTransferConfirmed()) {
            return true;
        }

        if (UIUtils.confirmAction(UIUtils.getActiveWorkbenchShell(),
            AIUIMessages.confirm_meta_transfer_usage_title,
            NLS.bind(AIUIMessages.confirm_meta_transfer_usage_message, settings.getDataSourceContainer().getName()),
            AIIcons.AI
        )) {
            settings.setMetaTransferConfirmed(true);
            try {
                settings.saveSettings();
            } catch (DBException e) {
                DBWorkbench.getPlatformUI().showError(AIUIMessages.confirm_meta_transfer_usage_title, null, e);
            }
            return true;
        }

        return false;
    }

    public static void showPreferences(@NotNull Shell shell) {
        if (!DBWorkbench.getPlatform().getWorkspace().hasRealmPermission(RMConstants.PERMISSION_CONFIGURATION_MANAGER)) {
            return;
        }
        UIUtils.showPreferencesFor(
            shell,
            AISettingsManager.getInstance().getSettings(),
            AIPreferencePageEngines.PAGE_ID
        );
    }
}
