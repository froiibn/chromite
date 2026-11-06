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

package com.seizia.chromite.ui.navigator.actions.node;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.navigator.DBNBrowseSettings;
import com.seizia.chromite.model.navigator.DBNDataSource;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.registry.DataSourceNavigatorSettings;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceConnections;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.internal.UINavigatorMessages;
import com.seizia.chromite.ui.navigator.actions.NavigatorNodeActionHandlerAbstract;

/**
 * Connection view handler
 */
public class NNAHDataSourceConnectionView extends NavigatorNodeActionHandlerAbstract {

    @Override
    public boolean isEnabledFor(@NotNull DBNNode node) {
        if (node instanceof DBNDataSource dbnDataSource) {
            DBNBrowseSettings chosenSettings = dbnDataSource.getDataSourceContainer().getNavigatorSettings();
            return !DataSourceNavigatorSettings.getProductDefaultSettings().equals(chosenSettings);
        }
        return false;
    }

    @Override
    @Nullable
    public DBPImage getNodeActionIcon(@NotNull DBNNode node) {
        if (node instanceof DBNDataSource dbnDataSource) {
            DBNBrowseSettings chosenSettings = dbnDataSource.getDataSourceContainer().getNavigatorSettings();
            if (DataSourceNavigatorSettings.PRESET_SIMPLE.getSettings().equals(chosenSettings)) {
                return UIIcon.SIMPLE_MODE;
            } else if (DataSourceNavigatorSettings.PRESET_ADVANCED.getSettings().equals(chosenSettings)) {
                return UIIcon.ADVANCED_MODE;
            } else {
                return UIIcon.CUSTOM_MODE;
            }
        }
        return null;
    }

    @Override
    @Nullable
    public String getNodeActionToolTip(@NotNull DBNNode node) {
        if (node instanceof DBNDataSource dbnDataSource) {
            DBNBrowseSettings chosenSettings = dbnDataSource.getDataSourceContainer().getNavigatorSettings();
            if (DataSourceNavigatorSettings.PRESET_SIMPLE.getSettings().equals(chosenSettings)) {
                return UINavigatorMessages.navigator_node_action_connection_view_simple_tooltip;
            } else if (DataSourceNavigatorSettings.PRESET_ADVANCED.getSettings().equals(chosenSettings)) {
                return UINavigatorMessages.navigator_node_action_connection_view_advanced_tooltip;
            } else {
                return UINavigatorMessages.navigator_node_action_connection_view_custom_tooltip;
            }
        }
        return null;
    }

    @Override
    public void handleNodeAction(@NotNull DBNNode node, boolean defaultAction) {
        if (node instanceof DBNDatabaseNode  dbnDatabaseNode) {
            DBPDataSourceContainer dataSourceContainer = dbnDatabaseNode.getDataSourceContainer();
            UIServiceConnections serviceConnections = DBWorkbench.getService(UIServiceConnections.class);
            if (serviceConnections != null) {
                serviceConnections.openConnectionEditor(dataSourceContainer, "ConnectionPageGeneral");
            }
        }
    }
}
