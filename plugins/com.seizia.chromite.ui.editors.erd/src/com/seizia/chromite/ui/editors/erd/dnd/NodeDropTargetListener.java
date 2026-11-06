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
/*
 * Created on Jul 14, 2004
 */
package com.seizia.chromite.ui.editors.erd.dnd;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.gef.EditPartViewer;
import org.eclipse.gef.Request;
import org.eclipse.gef.RequestConstants;
import org.eclipse.gef.dnd.AbstractTransferDropTargetListener;
import org.eclipse.gef.requests.CreateRequest;
import org.eclipse.gef.requests.CreationFactory;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPNamedObject;
import com.seizia.chromite.model.erd.DiagramObjectCollector;
import com.seizia.chromite.model.erd.ERDEntity;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableWithResult;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.dnd.DatabaseObjectTransfer;
import com.seizia.chromite.ui.editors.erd.editor.ERDEditorPart;
import com.seizia.chromite.ui.editors.erd.internal.ERDUIActivator;
import com.seizia.chromite.ui.editors.erd.model.DiagramCollectSettingsDefault;
import com.seizia.chromite.ui.editors.erd.part.DiagramPart;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Provides a listener for dropping nodes onto the editor drawing
 */
public class NodeDropTargetListener extends AbstractTransferDropTargetListener {

    public NodeDropTargetListener(@NotNull EditPartViewer viewer) {
        super(viewer, DatabaseObjectTransfer.getInstance());
    }

    @Override
    protected void updateTargetRequest() {

    }

    @Override
    protected Request createTargetRequest() {
        CreateRequest request = new CreateRequest();
        request.setFactory(new CreationFactory() {
            @Override
            public Object getNewObject() {
                Collection<DBPNamedObject> objects = DatabaseObjectTransfer.getInstance().getObject();
                ERDEditorPart editor = ((DiagramPart) getViewer().getRootEditPart().getContents()).getEditor();
                if (objects == null || editor == null || editor.getDiagram() == null) {
                    return null;
                }

                DBRRunnableWithResult<List<ERDEntity>> collector = new DBRRunnableWithResult<>() {
                    @Override
                    public void run(DBRProgressMonitor monitor) {
                        result = DiagramObjectCollector.generateEntityList(
                            monitor,
                            editor.getDiagram(),
                            editor.getDiagramProject(),
                            objects,
                            new DiagramCollectSettingsDefault(),
                            true,
                            editor.isERD()
                        );
                    }
                };
                try {
                    UIUtils.runInProgressService(collector);
                } catch (InvocationTargetException e) {
                    DBWorkbench.getPlatformUI().showError("Entity collect error", "Error during diagram entities collect", e);
                } catch (InterruptedException e) {
                    // ignore
                }
                final List<String> errorMessages = editor.getDiagram().getErrorMessages();
                if (!errorMessages.isEmpty()) {
                    final List<Status> statuses = new ArrayList<>(errorMessages.size());
                    for (String error : errorMessages) {
                        statuses.add(new Status(Status.ERROR, ERDUIActivator.PLUGIN_ID, error));
                    }
                    DBWorkbench.getPlatformUI().showError(
                        "Diagram request error",
                        "Error(s) occurred during diagram request. If these errors are recoverable then fix errors and then repeat request",
                        new MultiStatus(ERDUIActivator.PLUGIN_ID, 0, statuses.toArray(new IStatus[0]), null, null)
                    );
                    editor.getDiagram().clearErrorMessages();
                }
                return collector.getResult();
            }

            @Override
            public Object getObjectType() {
                return RequestConstants.REQ_CREATE;
            }
        });
        request.setLocation(getDropLocation());
        return request;
    }

}

