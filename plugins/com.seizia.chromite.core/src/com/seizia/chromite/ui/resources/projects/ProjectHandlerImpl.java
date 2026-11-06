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
package com.seizia.chromite.ui.resources.projects;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.app.DBPPlatformDesktop;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.rcp.RCPProject;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.actions.GlobalUIPropertyTester;
import com.seizia.chromite.ui.dialogs.MultiPageWizardDialog;
import com.seizia.chromite.ui.project.EditProjectWizard;
import com.seizia.chromite.ui.resources.AbstractResourceHandler;

/**
 * Project handler
 */
public class ProjectHandlerImpl extends AbstractResourceHandler {

    @NotNull
    @Override
    public String getTypeName(@NotNull IResource resource) {
        return "project";
    }

    @Override
    public int getFeatures(IResource resource) {
        int features = FEATURE_CREATE_FOLDER;
        if (GlobalUIPropertyTester.canManageProjects()) {
            features |= FEATURE_RENAME;
            DBPProject activeProject = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
            if (!(activeProject instanceof RCPProject rcpProject) || resource != rcpProject.getEclipseProject()) {
                // FIXME: restrict private projects delete
                boolean isPrivateProject = false;//activeProject.isPrivateProject()
                if (!(DBWorkbench.isDistributed() && isPrivateProject)) {
                    features |= FEATURE_DELETE;
                }
            }
        }
        return features;
    }

    @Override
    public void openResource(@NotNull IResource resource) {
        DBPProject project = DBPPlatformDesktop.getInstance().getWorkspace().getProject((IProject) resource);
        if (!(project instanceof RCPProject rcpProject)) {
            DBWorkbench.getPlatformUI().showError("No project", "Can't get project metadata for resource " + resource.getName());
            return;
        }
        MultiPageWizardDialog dialog = new MultiPageWizardDialog(
            UIUtils.getActiveWorkbenchWindow(),
            new EditProjectWizard(rcpProject));
        dialog.open();
    }
}
