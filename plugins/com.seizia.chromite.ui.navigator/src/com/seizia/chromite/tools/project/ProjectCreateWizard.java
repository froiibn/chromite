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
package com.seizia.chromite.tools.project;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.ui.INewWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.dialogs.WizardNewProjectCreationPage;
import org.eclipse.ui.wizards.newresource.BasicNewProjectResourceWizard;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.app.DBPPlatformDesktop;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.rcp.ChromiteNature;
import com.seizia.chromite.model.rcp.RCPProject;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableWithProgress;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.internal.UINavigatorMessages;
import com.seizia.chromite.ui.preferences.WizardPrefPage;
import com.seizia.chromite.ui.project.PrefPageProjectResourceSettings;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;

public class ProjectCreateWizard extends BasicNewProjectResourceWizard implements INewWizard {

    private final ProjectCreateData data = new ProjectCreateData();
    private WizardPrefPage projectSettingsPage;
    private IProject project;
    private ProjectCreateRemotePage remoteProjectPage;

    public ProjectCreateWizard() {
	}

    public IProject getProject() {
        return project;
    }

    @Override
    public void init(IWorkbench workbench, IStructuredSelection selection) {
        super.init(workbench, selection);
        setWindowTitle(UINavigatorMessages.dialog_project_create_wizard_title);
        setNeedsProgressMonitor(true);
    }

    @Override
    public void addPages() {
        if (DBWorkbench.isDistributed()) {
            remoteProjectPage = new ProjectCreateRemotePage("RemoteProjectCreate");
            addPage(remoteProjectPage);
        } else {
            super.addPages();

            final PrefPageProjectResourceSettings projectSettingsPref = new PrefPageProjectResourceSettings();
            projectSettingsPage = new WizardPrefPage(projectSettingsPref, "Resources", "Project resources");
            addPage(projectSettingsPage);
        }
    }

    @Override
    public IWizardPage getNextPage(IWizardPage page) {
        if (page instanceof WizardNewProjectCreationPage) {
            return projectSettingsPage;
        }
        return super.getNextPage(page);
    }

    @Override
	public boolean performFinish() {
        if (!DBWorkbench.getPlatform().getWorkspace().canManageProjects()) {
            DBWorkbench.getPlatformUI().showError(
                UINavigatorMessages.dialog_project_create_wizard_error_cannot_create,
                "You can't manage projects"
            );
            return false;
        }

        if (DBWorkbench.isDistributed()) {
            try {
                DBPProject newProject = DBPPlatformDesktop.getInstance().getWorkspace().createProject(
                    remoteProjectPage.getProjectName(),
                    remoteProjectPage.getProjectDescription()
                );
                project = ((RCPProject)newProject).getEclipseProject();
            } catch (DBException e) {
                DBWorkbench.getPlatformUI().showError(
                    UINavigatorMessages.dialog_project_create_wizard_error_cannot_create,
                    UINavigatorMessages.dialog_project_create_wizard_error_cannot_create_message,
                    e);
                return false;
            }

            return true;
        }
        if (super.performFinish()) {
            try {
                UIUtils.run(getContainer(), true, true, new DBRRunnableWithProgress() {
                    @Override
                    public void run(DBRProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                        try {
                            project = createProject(monitor);
                        } catch (Exception e) {
                            throw new InvocationTargetException(e);
                        }
                    }
                });
            } catch (InterruptedException ex) {
                return false;
            } catch (InvocationTargetException ex) {
                DBWorkbench.getPlatformUI().showError(
                        UINavigatorMessages.dialog_project_create_wizard_error_cannot_create,
                    UINavigatorMessages.dialog_project_create_wizard_error_cannot_create_message,
                    ex.getTargetException());
                return false;
            }
            return true;
        } else {
            return false;
        }
	}

    private IProject createProject(DBRProgressMonitor monitor) throws CoreException {
        final IProgressMonitor nestedMonitor = RuntimeUtils.getNestedMonitor(monitor);
        final IProject project = getNewProject();

        final IProjectDescription description = project.getDescription();
        if (!CommonUtils.isEmpty(data.getDescription())) {
            description.setComment(data.getDescription());
        }
        description.setNatureIds(new String[] {ChromiteNature.NATURE_ID});
        project.setDescription(description, nestedMonitor);

        if (!project.isOpen()) {
            project.open(nestedMonitor);
        }
        return project;
    }

}
