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
package com.seizia.chromite.ui.datadam.mcp;

import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.datadam.DDDesktopPreferences;
import com.seizia.chromite.model.datadam.mcp.DDMcpConnectionSettings;
import com.seizia.chromite.ui.IObjectPropertyConfigurator;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.ai.preferences.PrefPageDataSourceAI;
import com.seizia.chromite.ui.datadam.internal.DDMcpMessages;

public class DDMcpConnectionConfigurator implements IObjectPropertyConfigurator<PrefPageDataSourceAI, PrefPageDataSourceAI> {
    private Button enableCheck;

    public static boolean appliesTo() {
        return DDDesktopPreferences.getStore().getBoolean(DDDesktopPreferences.MCP_DESKTOP_ENABLED);
    }

    @Override
    public void createControl(
        @NotNull Composite parent,
        @NotNull PrefPageDataSourceAI page,
        @NotNull Runnable propertyChangeListener
    ) {
        DBPDataSourceContainer connection = page.getConnection();
        if (connection != null && appliesTo()) {
            Composite group = UIUtils.createTitledComposite(
                parent, DDMcpMessages.mcp_connection_settings_group, 1, GridData.FILL_HORIZONTAL);
            enableCheck = UIUtils.createCheckbox(
                group,
                DDMcpMessages.mcp_connection_settings_enabled,
                DDMcpMessages.mcp_connection_settings_enabled_tip,
                false,
                1
            );
        }
    }

    @Override
    public void loadSettings(@NotNull PrefPageDataSourceAI page) {
        DBPDataSourceContainer connection = page.getConnection();
        if (connection != null && enableCheck != null) {
            enableCheck.setSelection(DDMcpConnectionSettings.isEnabled(connection));
        }
    }

    @Override
    public void saveSettings(@NotNull PrefPageDataSourceAI page) {
        DBPDataSourceContainer connection = page.getConnection();
        if (connection != null && enableCheck != null) {
            DDMcpConnectionSettings.setEnabled(connection, enableCheck.getSelection());
        }
    }

    @Override
    public void resetSettings(@NotNull PrefPageDataSourceAI page) {
        if (enableCheck != null) {
            enableCheck.setSelection(false);
        }
    }

    @Override
    public boolean isComplete() {
        return true;
    }
}
