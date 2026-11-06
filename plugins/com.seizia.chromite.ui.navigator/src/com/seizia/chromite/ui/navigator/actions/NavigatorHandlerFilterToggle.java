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
package com.seizia.chromite.ui.navigator.actions;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.handlers.HandlerUtil;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.DBNDatabaseItem;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.meta.DBXTreeItem;
import com.seizia.chromite.model.struct.DBSObjectFilter;
import com.seizia.chromite.ui.navigator.NavigatorUtils;

import java.util.Collections;

public class NavigatorHandlerFilterToggle extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        final ISelection selection = HandlerUtil.getCurrentSelection(event);
        DBNNode node = NavigatorUtils.getSelectedNode(selection);
        if (node instanceof DBNDatabaseItem) {
            node = node.getParentNode();
        }
        if (node instanceof DBNDatabaseFolder) {
            final DBNDatabaseFolder folder = (DBNDatabaseFolder) node;
            DBXTreeItem itemsMeta = folder.getItemsMeta();
            if (itemsMeta != null) {
                final DBSObjectFilter nodeFilter = folder.getNodeFilter(itemsMeta, true);
                if (nodeFilter != null) {
                    nodeFilter.setEnabled(!nodeFilter.isEnabled());
                    NavigatorHandlerRefresh.refreshNavigator(Collections.singleton(folder));
                }
            }

        }
        return null;
    }

}