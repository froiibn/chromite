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
import com.seizia.chromite.utils.RuntimeUtils;

/**
 * DBNDashboard
 */
public class DBNDashboard extends DBNResource {
    private static final DBIcon DASHBOARD_ICON = new DBIcon(RuntimeUtils.makePlatformResourceUrl(
        DashboardConstants.DASHBOARDS_PLUGIN_ID, "icons/dashboard.svg"));

    @Nullable
    private DBPImage image;

    public DBNDashboard(@NotNull DBNNode parentNode, @NotNull IResource resource, @NotNull DBPResourceHandler handler)
            throws DBException, CoreException {
        super(parentNode, resource, handler);
    }

    @NotNull
    @Override
    public DBPImage getResourceNodeIcon() {
        if (image == null) {
            image = DASHBOARD_ICON;
        }
        return image;
    }

}
