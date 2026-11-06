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
package com.seizia.chromite.ui.editors.erd.navigator;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.actions.AbstractDataSourceHandler;
import com.seizia.chromite.ui.editors.erd.editor.ERDEditorEmbedded;
import com.seizia.chromite.ui.navigator.actions.NavigatorHandlerObjectOpen;
import com.seizia.chromite.utils.GeneralUtils;

public class ViewDiagramHandler extends AbstractDataSourceHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (selection instanceof IStructuredSelection) {
            for (Object item : ((IStructuredSelection) selection).toList()) {
                DBSObject object = GeneralUtils.adapt(item, DBSEntity.class);
                if (object == null) {
                    object = GeneralUtils.adapt(item, DBSObjectContainer.class);
                }
                if (object != null) {
                    DBNDatabaseNode node = NavigatorHandlerObjectOpen.getNodeByObject(object);
                    if (node != null) {
                        NavigatorHandlerObjectOpen.openEntityEditor(
                            node,
                            ERDEditorEmbedded.class.getName(),
                            null,
                            null,
                            UIUtils.getActiveWorkbenchWindow(),
                            true);
                    }
                }
            }
        }
        return null;
    }

}