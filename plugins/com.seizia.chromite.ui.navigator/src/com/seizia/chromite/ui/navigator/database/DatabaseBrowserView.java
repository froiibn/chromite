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
package com.seizia.chromite.ui.navigator.database;

import org.eclipse.swt.widgets.Composite;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.navigator.DBNEmptyNode;
import com.seizia.chromite.model.navigator.DBNModel;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.DBNProject;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIExecutionQueue;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.navigator.INavigatorFilter;
import com.seizia.chromite.ui.navigator.SmartNavigatorTreeFilter;
import org.jkiss.utils.CommonUtils;

public class DatabaseBrowserView extends NavigatorViewBase {
    public static final String VIEW_ID = "com.seizia.chromite.core.databaseBrowser";

    private static final Log log = Log.getLog(DatabaseBrowserView.class);
    private DBNNode rootNode;

    public DatabaseBrowserView()
    {
        super();
    }

    @Override
    protected INavigatorFilter getNavigatorFilter() {
        return new SmartNavigatorTreeFilter();
    }

    @Override
    public DBNNode getRootNode() {
        if (rootNode == null) {
            String secondaryId = getViewSite().getSecondaryId();
            if (!CommonUtils.isEmpty(secondaryId)) {
                try {
                    rootNode = getNodeFromSecondaryId(secondaryId);
                } catch (DBException e) {
                    DBWorkbench.getPlatformUI().showError("Open database browser", "Can't find database navigator node", e);
                }
            }
        }
        if (rootNode != null) {
            return rootNode;
        }
        return getDefaultRootNode();
    }

    @NotNull
    protected DBNNode getDefaultRootNode() {
        DBNProject projectNode = getGlobalNavigatorModel().getRoot().getProjectNode(DBWorkbench.getPlatform().getWorkspace().getActiveProject());
        return projectNode == null ? new DBNEmptyNode() : projectNode.getDatabases();
    }

    @Override
    public void createPartControl(@NotNull Composite parent) {
        super.createPartControl(parent);
        getNavigatorTree().setFilterObjectType(getDefaultFilterType());

        String secondaryId = getViewSite().getSecondaryId();
        if (!CommonUtils.isEmpty(secondaryId)) {
            UIExecutionQueue.queueExec(() -> {
                try {
                    DBNNode node = getNodeFromSecondaryId(secondaryId);
                    setPartName(node.getNodeDisplayName());
                    setTitleImage(SwtIcons.getImage(node.getNodeIconDefault()));
                } catch (DBException e) {
                    log.error("Error creating DB browser view", e);
                }
            });
        }
    }

    @NotNull
    protected DatabaseNavigatorTreeFilterObjectType getDefaultFilterType() {
        return DatabaseNavigatorTreeFilterObjectType.table;
    }

    @NotNull
    public static String getSecondaryIdFromNode(@NotNull DBNNode node) {
        String prefix = "";
        DBPProject project = node.getOwnerProjectOrNull();
        if (project != null) {
            prefix = project.getName();
        }
        // We can't use colon in secondary ID
        return prefix + "|" + node.getNodeUri().replace(":", "~");
    }

    @NotNull
    public static DBNNode getNodeFromSecondaryId(@NotNull String id) throws DBException {
        int divPos = id.indexOf('|');
        if (divPos == -1) {
            throw new DBException("Bad secondary ID: " + id);
        }
        String projectName = id.substring(0, divPos);
        String nodePath = id.substring(divPos + 1).replace("~", ":");
        final DBNModel navigatorModel = DBWorkbench.getPlatform().getNavigatorModel();
        DBPProject projectMeta = CommonUtils.isEmpty(projectName) ? null : DBWorkbench.getPlatform().getWorkspace().getProject(projectName);
        DBNNode node;
        {
            if (projectMeta != null) {
                navigatorModel.ensureProjectLoaded(projectMeta);
            }
            node = UIUtils.runWithMonitor(monitor -> {
                monitor.beginTask("Find navigator node", 1);
                try {
                    monitor.subTask("Find node " + nodePath);
                    return projectMeta == null ?
                        navigatorModel.getNodeByPath(monitor, nodePath) :
                        navigatorModel.getNodeByPath(monitor, projectMeta, nodePath);
                } finally {
                    monitor.done();
                }
            });
        }
        if (node == null) {
            log.error("Node " + nodePath + " not found for browse view");
            node = new DBNEmptyNode();
        }
        return node;
    }

}
