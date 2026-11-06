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
package com.seizia.chromite.ui.editors.erd.action;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.gef.commands.Command;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.ISources;
import org.eclipse.ui.handlers.HandlerUtil;
import com.seizia.chromite.model.DBPNamedObject;
import com.seizia.chromite.model.erd.DiagramObjectCollector;
import com.seizia.chromite.model.erd.ERDEntity;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.dnd.DatabaseObjectTransfer;
import com.seizia.chromite.ui.editors.erd.editor.ERDEditorAdapter;
import com.seizia.chromite.ui.editors.erd.editor.ERDEditorPart;
import com.seizia.chromite.ui.editors.erd.model.DiagramCollectSettingsDefault;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;

public class ERDHandlerPaste extends AbstractHandler {
    public ERDHandlerPaste() {

    }

    @Override
    public boolean isEnabled()
    {
        final Collection<DBPNamedObject> objects = DatabaseObjectTransfer.getFromClipboard();
        if (objects == null || CommonUtils.isEmpty(objects)) {
            return false;
        }
        for (DBPNamedObject object : objects) {
            if (object instanceof DBSTable || object instanceof DBSObjectContainer) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Control control = (Control) HandlerUtil.getVariable(event, ISources.ACTIVE_FOCUS_CONTROL_NAME);
        if (control != null) {
            ERDEditorPart editor = ERDEditorAdapter.getEditor(control);
            if (editor != null && !editor.isReadOnly()) {
                final Collection<DBPNamedObject> objects = DatabaseObjectTransfer.getInstance().getObject();
                if (!CommonUtils.isEmpty(objects)) {
                    try {
                        UIUtils.runInProgressService(monitor -> {
                            final List<ERDEntity> erdEntities = DiagramObjectCollector.generateEntityList(
                                monitor,
                                editor.getDiagram(),
                                editor.getDiagramProject(),
                                objects,
                                new DiagramCollectSettingsDefault(),
                                true,
                                editor.isERD());
                            if (!CommonUtils.isEmpty(erdEntities)) {
                                UIUtils.syncExec(() -> {
                                    Command command = editor.getDiagramPart().createEntityAddCommand(erdEntities, new Point(10, 10));
                                    editor.getCommandStack().execute(command);
                                });
                            }
                        });
                    } catch (InvocationTargetException e) {
                        DBWorkbench.getPlatformUI().showError("Entity collect error", "Error during diagram entities collect", e);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
            }
        }
        return null;
    }

}
