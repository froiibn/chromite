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
import com.seizia.chromite.model.navigator.DBNDataSource;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import com.seizia.chromite.model.net.DBWHandlerType;
import com.seizia.chromite.model.net.DBWUtils;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceConnections;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.navigator.actions.NavigatorNodeActionHandlerAbstract;
import org.jkiss.utils.CommonUtils;

/**
 * Tunnel action handler
 */
public class NNAHDataSourceTunnel extends NavigatorNodeActionHandlerAbstract {

    @Override
    public boolean isEnabledFor(@NotNull DBNNode node) {
        if (node instanceof DBNDataSource dbnDataSource) {
            return dbnDataSource.hasNetworkHandlers();
        }
        return false;
    }

    @Override
    @Nullable
    public DBPImage getNodeActionIcon(@NotNull DBNNode node) {
        return UIIcon.BUTTON_TUNNEL;
    }

    @Override
    @Nullable
    public String getNodeActionToolTip(@NotNull DBNNode node) {
        StringBuilder tip = new StringBuilder("Network handlers enabled:");
        for (DBWHandlerConfiguration handler : DBWUtils.getActualNetworkHandlers(((DBNDataSource) node).getDataSourceContainer())) {
            if (handler.isEnabled()) {
                tip.append("\n  -").append(handler.getTitle());
                String hostName = handler.getStringProperty(DBWHandlerConfiguration.PROP_HOST);
                if (!CommonUtils.isEmpty(hostName)) {
                    tip.append(": ").append(hostName);
                }
            }
        }
        return tip.toString();
    }

    @Override
    public void handleNodeAction(@NotNull DBNNode node, boolean defaultAction) {
        if (node instanceof DBNDatabaseNode dbNode) {
            DBPDataSourceContainer dataSourceContainer = dbNode.getDataSourceContainer();

            String nhId = null;
            for (DBWHandlerConfiguration nhc : DBWUtils.getActualNetworkHandlers(dataSourceContainer)) {
                if (nhc.isEnabled() && nhc.getType() == DBWHandlerType.TUNNEL) {
                    nhId = nhc.getId();
                    break;
                }
            }
            UIServiceConnections serviceConnections = DBWorkbench.getService(UIServiceConnections.class);
            if (serviceConnections != null) {
                serviceConnections.openConnectionEditor(dataSourceContainer, "ConnectionPageNetworkHandler." + nhId);
            }
        }
    }
}
