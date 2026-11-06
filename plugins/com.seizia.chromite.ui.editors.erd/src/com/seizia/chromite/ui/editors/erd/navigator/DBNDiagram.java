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
package com.seizia.chromite.ui.editors.erd.navigator;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBIcon;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.app.DBPResourceHandler;
import com.seizia.chromite.model.erd.ERDConstants;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.DBNResource;
import com.seizia.chromite.utils.RuntimeUtils;

/**
 * DBNDiagram
 */
public class DBNDiagram extends DBNResource {
    private static final DBIcon DIAGRAM_ICON = new DBIcon(RuntimeUtils.makePlatformResourceUrl(
        ERDConstants.PLUGIN_ID, "icons/erd.svg"));

    @Nullable
    private DBPImage image;

    public DBNDiagram(@NotNull DBNNode parentNode, @NotNull IResource resource, @NotNull DBPResourceHandler handler)
            throws DBException, CoreException {
        super(parentNode, resource, handler);
    }

    @NotNull
    @Override
    public DBPImage getResourceNodeIcon() {
        if (image == null) {
            image = DIAGRAM_ICON;
        }
        return image;
    }

}
