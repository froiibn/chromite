/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ui.dashboard.navigator;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.ui.INewWizard;
import org.eclipse.ui.IWorkbench;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPPlatformDesktop;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.app.DBPResourceHandler;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.dashboard.model.DashboardConfiguration;
import com.seizia.chromite.ui.dashboard.model.DashboardConfigurationList;
import com.seizia.chromite.ui.dashboard.view.DataSourceDashboardView;

public class DashboardCreateWizard extends Wizard implements INewWizard {

    private final DBPDataSourceContainer dataSourceContainer;
    @Nullable
    private IFolder folder;
    private DashboardCreateWizardPage pageContent;
	private String errorMessage;
    private IStructuredSelection entitySelection;
    @Nullable
    private DBPProject project;

    public DashboardCreateWizard() {
        this.dataSourceContainer = null;
    }

    public DashboardCreateWizard(DBPDataSourceContainer dataSourceContainer) {
        this.dataSourceContainer = dataSourceContainer;
	}

	@Override
    public void init(IWorkbench workbench, IStructuredSelection selection) {
        setWindowTitle("Create dashboard");
        setNeedsProgressMonitor(true);

        if (dataSourceContainer != null) {
            this.project = dataSourceContainer.getProject();
        } else {
            IFolder dashboardFolder = null;
            if (selection != null) {
                Object element = selection.getFirstElement();
                if (element != null) {
                    dashboardFolder = Platform.getAdapterManager().getAdapter(element, IFolder.class);
                }
            }
            if (dashboardFolder == null) {
                DBPProject activeProject = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
                if (activeProject == null) {
                    errorMessage = "Can't create dashboard without active project";
                } else {
                    try {
                        dashboardFolder = DashboardResourceHandler.getDashboardsFolder(activeProject, true);
                    } catch (CoreException e) {
                        errorMessage = e.getMessage();
                    }
                }

                // Check for entity selection
                if (selection != null && !selection.isEmpty()) {
                    if (Platform.getAdapterManager().getAdapter(selection.getFirstElement(), DBSEntity.class) != null) {
                        entitySelection = selection;
                    }
                }
            }
            if (dashboardFolder != null) {
                this.folder = dashboardFolder;
                this.project = DBPPlatformDesktop.getInstance().getWorkspace().getProject(dashboardFolder.getProject());
            }
        }
    }

    @Override
    public void addPages() {
        super.addPages();
        pageContent = new DashboardCreateWizardPage(project, dataSourceContainer);
        addPage(pageContent);
        if (getContainer() != null) {
            //WizardDialog call
            pageContent.setErrorMessage(errorMessage);
		}
    }
    
    @Override
    public void setContainer(IWizardContainer wizardContainer) {
    	super.setContainer(wizardContainer);
    	if (pageContent != null) {
    		//New Wizard call
            pageContent.setErrorMessage(errorMessage);
		}
    }
    
	@Override
	public boolean performFinish() {
        try {
            if (dataSourceContainer != null) {
                DashboardConfigurationList configurationList = new DashboardConfigurationList(dataSourceContainer);
                configurationList.checkDefaultDashboardExistence();
                // Add fake default dashboard
                DashboardConfiguration dashboard = configurationList.createDashboard(
                    pageContent.getDashboardId(),
                    pageContent.getDashboardName());
                dashboard.setInitDefaultCharts(pageContent.isInitDefCharts());
                configurationList.saveConfiguration();
                DataSourceDashboardView.openView(
                    UIUtils.getActiveWorkbenchWindow(),
                    dataSourceContainer.getProject(),
                    dataSourceContainer,
                    dashboard.getDashboardId());
                return true;
            } else {
                IFile diagramFile = DashboardResourceHandler.createDashboard(
                    pageContent.getDashboardName(),
                    folder,
                    new VoidProgressMonitor());

                DBPResourceHandler handler = DBPPlatformDesktop.getInstance().getWorkspace().getResourceHandler(diagramFile);
                if (handler != null) {
                    handler.openResource(diagramFile);
                    return true;
                }
            }
        } catch (Exception e) {
            DBWorkbench.getPlatformUI().showError("Error creating dashboard", null, e);
        }
        return false;
	}

}
