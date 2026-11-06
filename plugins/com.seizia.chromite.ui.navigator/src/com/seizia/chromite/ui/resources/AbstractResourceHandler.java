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
package com.seizia.chromite.ui.resources;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.app.DBPResourceHandler;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.DBNResource;
import com.seizia.chromite.model.navigator.NavigatorResources;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.ProgramInfo;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.editors.NodeEditorInput;
import com.seizia.chromite.ui.editors.entity.FolderEditor;
import org.jkiss.utils.CommonUtils;

import java.util.List;

/**
 * Abstract resource handler
 */
public abstract class AbstractResourceHandler implements DBPResourceHandler {

    @Override
    public int getFeatures(IResource resource) {
        if (resource instanceof IFolder) {
            if (resource.getParent() instanceof IFolder) {
                return FEATURE_DELETE | FEATURE_MOVE_INTO | FEATURE_RENAME | FEATURE_CREATE_FOLDER;
            }
            return FEATURE_MOVE_INTO | FEATURE_CREATE_FOLDER;
        }
        return 0;
    }

    @NotNull
    @Override
    public DBNResource makeNavigatorNode(@NotNull DBNNode parentNode, @NotNull IResource resource) throws CoreException, DBException {
        return new DBNResource(parentNode, resource, this);
    }

    @Override
    public void openResource(@NotNull IResource resource) throws CoreException, DBException {
        if (resource instanceof IFolder) {
            DBNResource node = NavigatorResources.getNodeByResource(DBWorkbench.getPlatform().getNavigatorModel(), resource);
            if (node != null) {
                NodeEditorInput nodeInput = new NodeEditorInput(node);
                UIUtils.getActiveWorkbenchWindow().getActivePage().openEditor(
                    nodeInput,
                    FolderEditor.class.getName());
            }
        }
        //throw new DBException("Resource open is not implemented");
    }

    @NotNull
    @Override
    public String getTypeName(@NotNull IResource resource) {
        return "resource";
    }

    @Nullable
    @Override
    public String getResourceDescription(@NotNull IResource resource) {
        return resource.getName();
    }

    @Nullable
    @Override
    public List<DBPDataSourceContainer> getAssociatedDataSources(DBNResource resource) {
        return null;
    }

    @NotNull
    @Override
    public String getResourceNodeName(@NotNull IResource resource) {
        return resource.getName();
    }

    @Override
    public DBPImage getResourceIcon(@NotNull IResource resource) {
        if (resource instanceof IContainer) {
            return null;
        }
        String fileExtension = resource.getFileExtension();
        if (!CommonUtils.isEmpty(fileExtension)) {
            ProgramInfo program = ProgramInfo.getProgram(fileExtension);
            if (program != null && program.getImage() != null) {
                return program.getImage();
            }
        }
        return null;
    }

}
