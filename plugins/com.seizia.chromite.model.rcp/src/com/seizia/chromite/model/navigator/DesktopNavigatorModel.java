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
package com.seizia.chromite.model.navigator;

import org.jkiss.code.Nullable;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.app.DBPPlatformDesktop;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.app.DBPWorkspace;
import com.seizia.chromite.model.rcp.RCPProject;
import com.seizia.chromite.runtime.DBWorkbench;

public class DesktopNavigatorModel extends DBNModel {

    private NavigatorResourceListener resourceListener;

    public DesktopNavigatorModel(DBPPlatform platform, @Nullable DBPWorkspace workspace) {
        super(platform, workspace);
    }

    public void initialize() {
        super.initialize();
        if (isGlobal()) {
            DBPPlatform platform = DBWorkbench.getPlatform();
            if (platform instanceof DBPPlatformDesktop platformDesktop) {
                resourceListener = new NavigatorResourceListener(this);
                platformDesktop.getWorkspace().getEclipseWorkspace().addResourceChangeListener(
                    resourceListener
                );
            }
        }
    }

    public void dispose() {
        if (isGlobal()) {
            DBPPlatform platform = DBWorkbench.getPlatform();
            if (platform instanceof DBPPlatformDesktop platformDesktop) {
                platformDesktop.getWorkspace().getEclipseWorkspace().removeResourceChangeListener(
                    resourceListener);
                resourceListener = null;
            }
        }
        super.dispose();
    }

    @Override
    protected DBNProject createProjectNode(DBNRoot parent, DBPProject project) {
        DBPPlatform platform = DBWorkbench.getPlatform();
        if (platform instanceof DBPPlatformDesktop platformDesktop && project instanceof RCPProject rcpProject) {
            return new DBNProjectDesktop(
                parent,
                rcpProject
            );
        } else {
            throw new IllegalStateException("Desktop navigator model can be used only in RCP applications");
        }
    }

}
