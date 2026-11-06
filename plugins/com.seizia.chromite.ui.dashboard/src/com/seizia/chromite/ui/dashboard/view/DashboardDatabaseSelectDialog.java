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
package com.seizia.chromite.ui.dashboard.view;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.DBPNamedObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.connection.DBPDataSourceProviderDescriptor;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.controls.NamedObjectPatternFilter;
import com.seizia.chromite.ui.controls.TreeContentProvider;
import com.seizia.chromite.ui.dashboard.internal.UIDashboardActivator;
import com.seizia.chromite.ui.dashboard.internal.UIDashboardMessages;
import com.seizia.chromite.ui.dialogs.BaseDialog;
import com.seizia.chromite.ui.dialogs.DialogUtils;
import org.jkiss.utils.CommonUtils;

import java.util.List;

/**
 * Database select dialog
 */
public class DashboardDatabaseSelectDialog extends BaseDialog {

    private static final String DIALOG_ID = "DBeaver.DashboardDatabaseSelectDialog";//$NON-NLS-1$

    private DBPNamedObject target;
    private TreeViewer treeViewer;

    public DashboardDatabaseSelectDialog(Shell shell) {
        super(shell, UIDashboardMessages.dialog_dashboard_database_select_title, null);
    }

    @Override
    protected IDialogSettings getDialogBoundsSettings() {
        return UIUtils.getSettingsSection(UIDashboardActivator.getDefault().getDialogSettings(), DIALOG_ID);
    }

    @Override
    protected Composite createDialogArea(Composite parent) {
        Composite dialogArea = super.createDialogArea(parent);

        {
            treeViewer = DialogUtils.createFilteredTree(dialogArea, SWT.BORDER, new NamedObjectPatternFilter(), null);
            GridData gd = new GridData(GridData.FILL_BOTH);
            gd.heightHint = 300;
            gd.widthHint = 300;
            treeViewer.getControl().setLayoutData(gd);
            treeViewer.getTree().setHeaderVisible(true);
            UIUtils.createTreeColumn(treeViewer.getTree(), SWT.LEFT, UIDashboardMessages.dialog_dashboard_database_select_column_name);
            UIUtils.createTreeColumn(treeViewer.getTree(), SWT.LEFT, UIDashboardMessages.dialog_dashboard_database_select_column_description);

            treeViewer.setContentProvider(new TreeContentProvider() {
                @Override
                public Object[] getChildren(Object parentElement) {
                    List<? extends DBPNamedObject> result = null;
                    if (parentElement instanceof List) {
                        result = (List) parentElement;
                    } else if (parentElement instanceof DBPDataSourceProviderDescriptor) {
                        result = ((DBPDataSourceProviderDescriptor) parentElement).getEnabledDrivers();
                        if (result.size() <= 1) {
                            result = null;
                        }
                    }
                    if (result == null) {
                        return new Object[0];
                    }
                    result.sort(DBUtils.nameComparator());
                    return result.toArray();
                }

                @Override
                public boolean hasChildren(Object element) {
                    if (element instanceof DBPDriver) {
                        return false;
                    }
                    return ((DBPDataSourceProviderDescriptor) element).getEnabledDrivers().size() > 1;
                }
            });
            treeViewer.setLabelProvider(new CellLabelProvider() {
                @Override
                public void update(ViewerCell cell) {
                    DBPNamedObject element = (DBPNamedObject) cell.getElement();
                    if (cell.getColumnIndex() == 0) {
                        cell.setText(element.getName());
                        DBPImage icon = null;
                        if (element instanceof DBPDriver) {
                            icon = ((DBPDriver) element).getIcon();
                        } else if (element instanceof DBPDataSourceProviderDescriptor) {
                            icon = ((DBPDataSourceProviderDescriptor) element).getIcon();
                        }
                        if (icon != null) {
                            cell.setImage(SwtIcons.getImage(icon));
                        }
                    } else {
                        if (element instanceof DBPDriver) {
                            cell.setText(CommonUtils.notEmpty(((DBPDriver) element).getDescription()));
                        } else if (element instanceof DBPDataSourceProviderDescriptor) {
                            cell.setText(((DBPDataSourceProviderDescriptor) element).getDescription());
                        }
                    }
                }
            });
            treeViewer.setInput(DBWorkbench.getPlatform().getDataSourceProviderRegistry().getDataSourceProviders());

            treeViewer.addDoubleClickListener(event -> {
                if (target != null) {
                    okPressed();
                }
            });
            treeViewer.addSelectionChangedListener(event -> {
                this.target = null;
                ISelection selection = event.getSelection();
                if (selection instanceof IStructuredSelection) {
                    Object selectedObject = ((IStructuredSelection) selection).getFirstElement();
                    if (selectedObject instanceof DBPNamedObject) {
                        this.target = (DBPNamedObject) selectedObject;
                    }
                }
                this.updateButtons();
            });

            UIUtils.asyncExec(() -> {
                treeViewer.expandAll();
                UIUtils.packColumns(treeViewer.getTree(), true, null);
            });
        }

        return dialogArea;
    }

    @Override
    protected Control createContents(Composite parent) {
        Control contents = super.createContents(parent);
        updateButtons();
        return contents;
    }

    private void updateButtons() {
        getButton(IDialogConstants.OK_ID).setEnabled(target != null);
    }

    public DBPNamedObject getTarget() {
        return target;
    }

}