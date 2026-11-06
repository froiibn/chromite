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
package com.seizia.chromite.model.dashboard.navigator;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBIcon;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.app.DBPResourceHandler;
import com.seizia.chromite.model.dashboard.DashboardConstants;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.DBNResource;
import com.seizia.chromite.model.navigator.NavigatorResources;
import com.seizia.chromite.utils.RuntimeUtils;

/**
 * DBNDashboardFolder
 */
public class DBNDashboardFolder extends DBNResource {
    private static final DBIcon FOLDER_ICON = new DBIcon(RuntimeUtils.makePlatformResourceUrl(
        DashboardConstants.DASHBOARDS_PLUGIN_ID, "icons/dashboard_folder.svg"));

    @Nullable
    private DBPImage image;

    public DBNDashboardFolder(@NotNull DBNNode parentNode, @NotNull IResource resource, @NotNull DBPResourceHandler handler)
            throws DBException, CoreException {
        super(parentNode, resource, handler);
    }

    @Override
    protected void dispose(boolean reflect) {
        super.dispose(reflect);
    }

    @NotNull
    @Override
    public DBPImage getResourceNodeIcon() {
        IResource resource = getResource();
        if (NavigatorResources.isRootResource(getOwnerProject(), resource)) {
            if (image == null) {
                image = FOLDER_ICON;
            }
            return image;
        }
        return super.getResourceNodeIcon();
    }

    @Override
    public boolean supportsDrop(@Nullable DBNNode otherNode) {
        return otherNode instanceof DBNDashboard || super.supportsDrop(otherNode);
    }

}
