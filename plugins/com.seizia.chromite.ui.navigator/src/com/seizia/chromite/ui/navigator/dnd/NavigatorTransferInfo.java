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
package com.seizia.chromite.ui.navigator.dnd;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.ide.FileStoreEditorInput;
import org.eclipse.ui.part.EditorInputTransfer;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.editors.DatabaseNodeEditorInput;
import com.seizia.chromite.ui.editors.EditorUtils;
import com.seizia.chromite.ui.editors.entity.EntityEditor;

import java.io.File;

public class NavigatorTransferInfo {
    private static final Log log = Log.getLog(NavigatorTransferInfo.class);

    private final String name;
    private final DBNNode node;
    private final Object object;

    public NavigatorTransferInfo(@NotNull String name, @NotNull DBNNode node, @Nullable Object object) {
        this.node = node;
        this.object = object;
        this.name = name;
    }

    @NotNull
    public String getName() {
        return name;
    }

    @Nullable
    public Object getObject() {
        return object;
    }

    @Nullable
    public EditorInputTransfer.EditorInputData createEditorInputData() {
        if (node instanceof DBNDatabaseNode) {
            final DatabaseNodeEditorInput input = new DatabaseNodeEditorInput((DBNDatabaseNode) node);
            return EditorInputTransfer.createEditorInputData(EntityEditor.ID, input);
        }

        final File file = new File(name);

        if (file.exists()) {
            try {
                final IWorkbenchWindow window = UIUtils.getActiveWorkbenchWindow();
                final IEditorDescriptor editor = EditorUtils.getFileEditorDescriptor(file, window);
                final IEditorInput input = new FileStoreEditorInput(EFS.getStore(file.toURI()));
                return EditorInputTransfer.createEditorInputData(editor.getId(), input);
            } catch (Exception e) {
                log.warn("Error creating editor input for file '" + file + "'", e);
            }
        }

        return null;
    }
}
