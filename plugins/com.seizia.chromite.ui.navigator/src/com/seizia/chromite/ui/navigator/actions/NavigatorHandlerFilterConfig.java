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
package com.seizia.chromite.ui.navigator.actions;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.navigator.*;
import com.seizia.chromite.model.navigator.meta.DBXTreeItem;
import com.seizia.chromite.model.navigator.meta.DBXTreeNode;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectFilter;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.internal.UINavigatorMessages;
import com.seizia.chromite.ui.navigator.NavigatorUtils;
import com.seizia.chromite.ui.navigator.UIServiceFilterConfig;
import com.seizia.chromite.ui.navigator.dialogs.EditObjectFilterDialog;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class NavigatorHandlerFilterConfig extends NavigatorHandlerObjectCreateBase implements IElementUpdater {
    private static final Log log = Log.getLog(NavigatorHandlerFilterConfig.class);

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        final ISelection selection = HandlerUtil.getCurrentSelection(event);
        DBNNode node = NavigatorUtils.getSelectedNode(selection);
        if (node instanceof DBNDatabaseNode dbNode) {
            configureFilters(HandlerUtil.getActiveShell(event), dbNode);
        }
        return null;
    }

    public static void configureFilters(@NotNull Shell shell, @NotNull DBNDatabaseNode dbNode) {
        try {
            DBNDatabaseNode parentNode = !(dbNode instanceof DBNDatabaseFolder) &&
                dbNode.getParentNode() instanceof DBNDatabaseNode parent ? parent : dbNode;
            DBXTreeItem itemsMeta = UIUtils.runWithMonitor(monitor -> DBNUtils.getValidItemsMeta(monitor, parentNode));
            if (itemsMeta != null) {
                UIServiceFilterConfig uiServiceFilterConfig = DBWorkbench.findService(UIServiceFilterConfig.class);
                if (uiServiceFilterConfig == null) {
                    FilterConfigDelegate handler = new FilterConfigDelegate(shell, dbNode, parentNode, itemsMeta);
                    handler.configFilterInDialog();
                } else {
                    uiServiceFilterConfig.configFilterInDialog(shell, dbNode, parentNode, itemsMeta);
                }
            } else if (log.isDebugEnabled()) {
                log.debug("Cannot find items meta for node: " + parentNode.getNodeUri());
            }
        } catch (DBException e) {
            log.error(e);
        }
    }

    @Override
    public void updateElement(UIElement element, Map parameters) {
        if (!updateUI) {
            return;
        }
        DBNNode node = NavigatorUtils.getSelectedNode(element);
        if (node instanceof DBNDatabaseItem) {
            node = node.getParentNode();
        }
        if (node != null) {
            element.setText(NLS.bind(UINavigatorMessages.actions_navigator_filter_objects, node.getNodeTypeLabel()));
        }
    }

    public static boolean isGlobalFilter(@NotNull DBNDatabaseNode originalNode, @Nullable DBNNode parentNode) {
        return originalNode.getValueObject() instanceof DBPDataSource
            // if the parent node is at datasource level, child is database/catalog
            || (parentNode instanceof DBNDatabaseNode dbNode && dbNode.getValueObject() instanceof DBPDataSource);
    }

    public static class FilterConfigDelegate {

        protected final Shell shell;

        protected final DBNDatabaseNode originalNode;

        protected final DBNDatabaseNode parentNode;

        protected final DBXTreeItem itemsMeta;

        protected final DBPDataSourceRegistry dsRegistry;


        public FilterConfigDelegate(
            @NotNull Shell shell,
            @NotNull DBNDatabaseNode originalNode,
            @NotNull DBNDatabaseNode parentNode,
            @NotNull DBXTreeItem itemsMeta
        ) {
            this.shell = shell;
            this.originalNode = originalNode;
            this.parentNode = parentNode;
            this.itemsMeta = itemsMeta;
            this.dsRegistry = originalNode.getOwnerProject().getDataSourceRegistry();
        }

        public void configFilterInDialog() throws DBException {
            boolean globalFilter = isGlobalFilter(originalNode, parentNode);
            String dialogObjectTitle = createDialogTitle(globalFilter);
            DBSObjectFilter objectFilter = getObjectFilter();
            EditObjectFilterDialog dialog = new EditObjectFilterDialog(
                shell,
                dsRegistry,
                dialogObjectTitle,
                objectFilter,
                globalFilter
            );
            processDialogResponse(dialog);
        }

        @NotNull
        protected DBSObjectFilter getObjectFilter() {
            return Objects.requireNonNullElseGet(
                parentNode.getNodeFilter(itemsMeta, true),
                DBSObjectFilter::new
            );
        }

        protected void processDialogResponse(@NotNull EditObjectFilterDialog dialog) throws DBException {
            switch (dialog.open()) {
                case IDialogConstants.OK_ID -> processOKResponse(dialog);
                case EditObjectFilterDialog.SHOW_GLOBAL_FILTERS_ID -> precessGlobalFilterResponse();
                default -> {
                    // do nothing
                }
            }
        }

        protected void processOKResponse(@NotNull EditObjectFilterDialog dialog) throws DBException {
            parentNode.setNodeFilter(itemsMeta, dialog.getFilter(), true);
            NavigatorHandlerRefresh.refreshNavigator(Collections.singletonList(parentNode));
        }

        protected void precessGlobalFilterResponse() throws DBException {
            Class<?> childrenClass = null;
            if (originalNode instanceof DBNDatabaseFolder folder) {
                childrenClass = folder.getChildrenClass();
            } else {
                List<DBXTreeNode> childMeta = parentNode.getMeta().getChildren(parentNode);
                if (!childMeta.isEmpty() && childMeta.getFirst() instanceof DBXTreeItem item) {
                    childrenClass = parentNode.getChildrenClass(item);
                }
            }
            if (childrenClass == null) {
                DBWorkbench.getPlatformUI().showMessageBox(
                    "Bad node", "Cannot use node '" + originalNode.getNodeUri() + "' for filters", true);
                return;
            }
            DBPDataSourceContainer dataSourceContainer = originalNode.getDataSourceContainer();
            DBSObjectFilter globalFilterForObject = Objects.requireNonNullElseGet(
                dataSourceContainer.getObjectFilter(childrenClass, null, true),
                DBSObjectFilter::new
            );
            EditObjectFilterDialog globalFilterDialog = new EditObjectFilterDialog(
                shell,
                dsRegistry,
                createDialogTitle(true),
                globalFilterForObject,
                true
            );
            if (globalFilterDialog.open() == IDialogConstants.OK_ID) {
                // Set global filter
                dataSourceContainer.setObjectFilter(childrenClass, null, globalFilterDialog.getFilter());
                dataSourceContainer.persistConfiguration();
                NavigatorHandlerRefresh.refreshNavigator(Collections.singletonList(parentNode));
            }
        }

        protected String createDialogTitle(boolean globalFilter) {
            String parentName = "?";
            if (originalNode.getValueObject() instanceof DBSObject dbsObject) {
                parentName = dbsObject.getName();
            }
            return globalFilter ?
                "All " + originalNode.getNodeTypeLabel()
                : originalNode.getNodeTypeLabel() + " of " + parentName;
        }
    }

}