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
package com.seizia.chromite.ext.spanner.ui.config;

import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.spanner.model.SpannerConstants;
import com.seizia.chromite.ext.spanner.ui.internal.SpannerMessages;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.access.DBAAuthModel;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.controls.ConfigurationFileSelector;
import com.seizia.chromite.ui.controls.TextWithOpenFile;
import com.seizia.chromite.ui.dialogs.connection.DatabaseNativeAuthModelConfigurator;
import org.jkiss.utils.CommonUtils;

public class SpannerAuthConfigurator extends DatabaseNativeAuthModelConfigurator {

    private TextWithOpenFile privateKeyFile;

    @Override
    public void createControl(@NotNull Composite authPanel, DBAAuthModel<?> object, @NotNull Runnable propertyChangeListener) {
        UIUtils.createControlLabel(authPanel, SpannerMessages.label_private_key_path);
        privateKeyFile = new ConfigurationFileSelector(authPanel, SpannerMessages.label_private_key_path, new String[] { "*", "*.json" } );
        GridData gd = new GridData(GridData.HORIZONTAL_ALIGN_BEGINNING | GridData.FILL_HORIZONTAL);
        privateKeyFile.setLayoutData(gd);
    }

    @Override
    public void loadSettings(@NotNull DBPDataSourceContainer dataSource) {
        super.loadSettings(dataSource);
        DBPConnectionConfiguration connectionInfo = dataSource.getConnectionConfiguration();
        if (privateKeyFile != null) {
            privateKeyFile.setText(CommonUtils.notEmpty(connectionInfo.getProviderProperty(SpannerConstants.DRIVER_PROP_PVTKEYPATH)));
        }
    }

    @Override
    public void saveSettings(@NotNull DBPDataSourceContainer dataSource) {
        DBPConnectionConfiguration connectionInfo = dataSource.getConnectionConfiguration();
        if (privateKeyFile != null) {
            connectionInfo.setProviderProperty(SpannerConstants.DRIVER_PROP_PVTKEYPATH, privateKeyFile.getText().trim());
        }
        super.saveSettings(dataSource);
    }
}
