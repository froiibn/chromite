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

package com.seizia.chromite.ui.navigator.actions.node;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.navigator.DBNDataSource;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceConnections;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.navigator.actions.NavigatorNodeActionHandlerAbstract;

/**
 * Read-only action handler
 */
public class NNAHDataSourceReadOnly extends NavigatorNodeActionHandlerAbstract {

    @Override
    public boolean isEnabledFor(@NotNull DBNNode node) {
        if (node instanceof DBNDataSource dbnDataSource) {
            return dbnDataSource.getDataSourceContainer().isConnectionReadOnly();
        }
        return false;
    }

    @Override
    @Nullable
    public DBPImage getNodeActionIcon(@NotNull DBNNode node) {
        return UIIcon.BUTTON_READ_ONLY;
    }

    @Override
    @Nullable
    public String getNodeActionToolTip(@NotNull DBNNode node) {
        return "Connection is read-only.\nYou cannot change data or database structure.";
    }

    @Override
    public void handleNodeAction(@NotNull DBNNode node, boolean defaultAction) {
        if (node instanceof DBNDatabaseNode dbNode) {
            DBPDataSourceContainer dataSourceContainer = dbNode.getDataSourceContainer();
            UIServiceConnections serviceConnections = DBWorkbench.getService(UIServiceConnections.class);
            if (serviceConnections != null) {
                serviceConnections.openConnectionEditor(dataSourceContainer, "ConnectionPageGeneral");
            }
        }
    }

}
