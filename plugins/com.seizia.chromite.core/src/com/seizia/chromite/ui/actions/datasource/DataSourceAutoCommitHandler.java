/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ui.actions.datasource;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.core.CoreMessages;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceContainerProvider;
import com.seizia.chromite.model.DBPTransactionIsolation;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.app.DBPWorkspace;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.rm.RMConstants;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.actions.AbstractDataSourceHandler;
import com.seizia.chromite.utils.GeneralUtils;

import java.util.Map;

public class DataSourceAutoCommitHandler extends AbstractDataSourceHandler implements IElementUpdater {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        DBPWorkspace workspace = DBWorkbench.getPlatform().getWorkspace();
        if (!workspace.hasRealmPermission(RMConstants.PERMISSION_PROJECT_ADMIN) &&
            !workspace.hasRealmPermission(RMConstants.PERMISSION_DATABASE_DEVELOPER)
        ) {
            UIUtils.showMessageBox(
                null,
                CoreMessages.action_menu_transaction_commit_mode_edit_restricted_dialog_title,
                CoreMessages.action_menu_transaction_commit_mode_edit_restricted_dialog_description,
                SWT.ICON_WARNING
            );
            return null;
        }
        DBCExecutionContext context = getActiveExecutionContext(event, true);
        if (context != null) {
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
            if (txnManager != null) {
                try {
                    final DBPDataSourceContainer container = context.getDataSource().getContainer();
                    boolean newAutocommit = !container.isDefaultAutoCommit();
                    if (context.isConnected()) {
                        // Get flag from connection
                        newAutocommit = !txnManager.isAutoCommit();
                    }
                    boolean autoCommit = newAutocommit;
                    new AbstractJob("Set auto-commit") {
                        @NotNull
                        @Override
                        protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                            monitor.beginTask("Change connection auto-commit to " + autoCommit, 1);
                            try {
                                monitor.subTask("Change context '" + context.getContextName() + "' auto-commit state");
                                txnManager.setAutoCommit(monitor, autoCommit);
                            } catch (Exception e) {
                                return GeneralUtils.makeExceptionStatus(e);
                            } finally {
                                monitor.done();
                            }
                            return Status.OK_STATUS;
                        }
                    }.schedule();
                } catch (DBException e) {
                    DBWorkbench.getPlatformUI().showError("Auto-Commit", "Error while toggle auto-commit", e);
                }
            }
        }
        return null;
    }

    @Override
    public void updateElement(UIElement element, Map parameters) {
        IWorkbenchWindow workbenchWindow = element.getServiceLocator().getService(IWorkbenchWindow.class);
        if (workbenchWindow == null || workbenchWindow.getActivePage() == null) {
            return;
        }
        IEditorPart activeEditor = workbenchWindow.getActivePage().getActiveEditor();
        if (activeEditor == null) {
            return;
        }

        boolean autoCommit = true;
        DBPTransactionIsolation isolation = null;
        DBCExecutionContext context = getExecutionContextFromPart(activeEditor);
        if (context != null && context.isConnected()) {
            DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
            if (txnManager != null) {
                try {
                    // Change auto-commit mode
                    autoCommit = txnManager.isAutoCommit();
                    isolation = txnManager.getTransactionIsolation();
                } catch (DBCException e) {
                    log.warn(e);
                }
            }
        } else if (activeEditor instanceof DBPDataSourceContainerProvider) {
            DBPDataSourceContainer container = ((DBPDataSourceContainerProvider) activeEditor).getDataSourceContainer();
            if (container != null) {
                autoCommit = container.isDefaultAutoCommit();
                isolation = container.getActiveTransactionsIsolation();
            }
        }
        element.setChecked(autoCommit);
        // Update command image
        element.setIcon(SwtIcons.getImageDescriptor(autoCommit ? UIIcon.TXN_COMMIT_AUTO : UIIcon.TXN_COMMIT_MANUAL));
        String isolationName = isolation == null ? "?" : isolation.getTitle();
        String text = autoCommit ? NLS.bind(CoreMessages.action_menu_transaction_manualcommit_name, isolationName) : CoreMessages.action_menu_transaction_autocommit_name;
        element.setText(text);
        element.setTooltip(text);
    }
}