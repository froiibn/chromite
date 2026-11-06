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
package com.seizia.chromite.ui.ai.controls;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNModel;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.*;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.ai.internal.AIUIActivator;
import com.seizia.chromite.ui.ai.internal.AIUIMessages;
import com.seizia.chromite.ui.dialogs.BaseDialog;
import com.seizia.chromite.ui.navigator.database.DatabaseObjectsSelectorPanel;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ScopeSelectorDialog extends BaseDialog {

    private static final String DIALOG_ID = "DBeaver.AIScopeSelectorDialog";//$NON-NLS-1$

    private final DBRRunnableContext runnableContext;
    private final DBPDataSourceContainer dataSourceContainer;
    private final DBCExecutionContext executionContext;
    private DatabaseObjectsSelectorPanel selectorPanel;
    private List<? extends DBNNode> selectedNodes;

    public ScopeSelectorDialog(
        @NotNull Shell parentShell,
        @NotNull DBRRunnableContext runnableContext,
        @NotNull DBPDataSourceContainer container,
        @NotNull DBCExecutionContext executionContext,
        @NotNull List<? extends DBNNode> selectedNodes
    ) {
        super(parentShell, "Customize database objects accessible to AI", null);
        this.runnableContext = runnableContext;
        this.dataSourceContainer = container;
        this.executionContext = executionContext;
        this.selectedNodes = selectedNodes;
    }

    @Override
    protected IDialogSettings getDialogBoundsSettings() {
        return UIUtils.getSettingsSection(AIUIActivator.getInstance().getDialogSettings(), DIALOG_ID);
    }

    @NotNull
    @Override
    protected Point getInitialSize() {
        Point size = super.getInitialSize();
        size.x = getShell().computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
        return size;
    }

    @NotNull
    @Override
    protected Composite createDialogArea(@NotNull Composite parent) {
        Composite dialogArea = super.createDialogArea(parent);

        DBPProject project = dataSourceContainer.getProject();
        DBNModel navigatorModel = project.getNavigatorModel();

        // Default root node is datasource
        // But if the datasource consists of databases which are isolated instance (e.g. PostgreSQL) then
        // too node is the default database (cross database queries are not supported in this cases)
        DBNDatabaseNode rootNode = navigatorModel.getNodeByObject(dataSourceContainer);
        DBCExecutionContextDefaults<?,?> contextDefaults = executionContext.getContextDefaults();
        if (contextDefaults != null && false) {
            DBSCatalog defaultCatalog = contextDefaults.getDefaultCatalog();
            if (defaultCatalog instanceof DBSInstance) {
                DBNDatabaseNode catalogNode = navigatorModel.getNodeByObject(new VoidProgressMonitor(), defaultCatalog, false);
                if (catalogNode != null) {
                    rootNode = catalogNode;
                }
            }
        }

        DBNDatabaseNode finalRootNode = rootNode;
        selectorPanel = new DatabaseObjectsSelectorPanel(
            dialogArea,
            this.runnableContext,
            SWT.SINGLE | SWT.BORDER | SWT.CHECK,
            true
        ) {
            @Override
            protected DBPProject getSelectedProject() {
                return project;
            }

            @Override
            protected DBNNode getRootNode() {
                return finalRootNode;
            }

            @Override
            protected boolean isDatabaseFolderVisible(DBNDatabaseFolder folder) {
                return isSupportedNode(folder);
            }

            @Override
            protected boolean isDatabaseObjectVisible(DBSObject obj) {
                return obj instanceof DBSStructContainer || obj instanceof DBSEntityContainer || obj instanceof DBSEntity;
            }

        };
        selectorPanel.getCheckboxTreeManager().enableLazyCheck(ScopeSelectorDialog::isSupportedNode);
        selectorPanel.getNavigatorTree().getViewer().expandToLevel(2);
        selectorPanel.checkNodes(selectedNodes, true);
        selectorPanel.setSelection(selectedNodes);
        selectorPanel.getNavigatorTree().getCheckboxViewer().addCheckStateListener(event -> updateOkButton());
        selectorPanel.getNavigatorTree().getViewer().addDoubleClickListener(event -> updateOkButton());

        UIUtils.createInfoLabel(dialogArea, "Customize database metadata accessible to AI.");

        return dialogArea;
    }

    @Override
    protected void createButtonsForButtonBar(@NotNull Composite parent) {
        super.createButtonsForButtonBar(parent);
        updateOkButton();
    }

    private void updateOkButton() {
        Button okButton = getButton(IDialogConstants.OK_ID);
        if (okButton != null) {
            okButton.setEnabled(selectorPanel.getCheckedNodes().stream().anyMatch(ScopeSelectorDialog::isSupportedNode));
        }
    }

    private static boolean isSupportedNode(@NotNull DBNNode node) {
        if (node instanceof DBNDatabaseFolder folder) {
            Class<? extends DBSObject> childrenClass = folder.getChildrenClass();
            return childrenClass != null && (
                DBSEntity.class.isAssignableFrom(childrenClass) ||
                DBSEntityContainer.class.isAssignableFrom(childrenClass) ||
                DBSStructContainer.class.isAssignableFrom(childrenClass)
            );
        }
        if (node instanceof DBNDatabaseNode databaseNode) {
            DBSObject object = databaseNode.getObject();
            return object instanceof DBSEntity || object instanceof DBSEntityContainer || object instanceof DBSStructContainer;
        }
        return false;
    }

    @Override
    protected void okPressed() {
        List<DBNNode> checkedNodes = selectorPanel.getCheckedNodes();
        Set<DBNNode> resolvedNodes = new LinkedHashSet<>();
        try {
            runnableContext.run(true, true, monitor -> {
                monitor.beginTask(AIUIMessages.scope_selector_loading, checkedNodes.size());
                try {
                    for (DBNNode node : checkedNodes) {
                        collectSelectedNodes(monitor, node, resolvedNodes);
                        monitor.worked(1);
                    }
                    if (monitor.isCanceled()) {
                        throw new InterruptedException();
                    }
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                } finally {
                    monitor.done();
                }
            });
        } catch (InvocationTargetException e) {
            DBWorkbench.getPlatformUI().showError(AIUIMessages.scope_selector_load_error, null, e.getTargetException());
            return;
        } catch (InterruptedException e) {
            return;
        }
        if (resolvedNodes.isEmpty()) {
            MessageDialog.openInformation(getShell(), AIUIMessages.scope_selector_customize, AIUIMessages.scope_selector_empty);
            return;
        }
        selectedNodes = new ArrayList<>(resolvedNodes);
        super.okPressed();
    }

    private static void collectSelectedNodes(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBNNode node,
        @NotNull Set<DBNNode> result
    ) throws DBException, InterruptedException {
        if (monitor.isCanceled()) {
            throw new InterruptedException();
        }
        if (!isSupportedNode(node)) {
            return;
        }
        if (node instanceof DBNDatabaseFolder folder) {
            DBNNode[] children = folder.getChildren(monitor);
            if (monitor.isCanceled()) {
                throw new InterruptedException();
            }
            if (children != null) {
                for (DBNNode child : children) {
                    collectSelectedNodes(monitor, child, result);
                }
            }
        } else {
            result.add(node);
        }
    }

    public List<? extends DBNNode> getSelectedNodes() {
        return selectedNodes;
    }
}
