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

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.ui.dialogs.ActiveWizardDialog;
import com.seizia.chromite.ui.dialogs.connection.MigrateConnectionWizard;
import com.seizia.chromite.ui.navigator.NavigatorUtils;
import com.seizia.chromite.ui.navigator.database.NavigatorViewBase;

public class DataSourceMigrateHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {

        NavigatorViewBase navigatorView = NavigatorUtils.getActiveNavigatorView(event);
        if (navigatorView != null) {
            ISelection currentSelection = navigatorView.getNavigatorViewer().getSelection();
            DBPProject selectedProject = NavigatorUtils.getSelectedProject(currentSelection, navigatorView);
            ActiveWizardDialog dialog = new ActiveWizardDialog(
                HandlerUtil.getActiveWorkbenchWindow(event),
                new MigrateConnectionWizard(
                    selectedProject.getDataSourceRegistry(),
                    currentSelection instanceof IStructuredSelection ss ? ss : null));
            dialog.open();
        }

        return null;
    }
}
