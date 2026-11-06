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

package com.seizia.chromite.model.app;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.DBNResource;

import java.util.List;

/**
 * Resource handler
 */
public interface DBPResourceHandler {

    int FEATURE_OPEN            = 1;
    int FEATURE_DELETE          = 2;
    int FEATURE_CREATE_FOLDER   = 4;
    int FEATURE_RENAME          = 8;
    int FEATURE_MOVE_INTO       = 16;

    int getFeatures(IResource resource);

    @NotNull
    DBNNode makeNavigatorNode(@NotNull DBNNode parentNode, @NotNull IResource resource) throws CoreException, DBException;

    void openResource(@NotNull IResource resource) throws CoreException, DBException;

    @NotNull
    String getTypeName(@NotNull IResource resource);

    DBPImage getResourceIcon(@NotNull IResource resource);

    @Nullable
    String getResourceDescription(@NotNull IResource resource);

    @Nullable
    List<DBPDataSourceContainer> getAssociatedDataSources(DBNResource resource);

    @NotNull
    String getResourceNodeName(@NotNull IResource resource);
}
