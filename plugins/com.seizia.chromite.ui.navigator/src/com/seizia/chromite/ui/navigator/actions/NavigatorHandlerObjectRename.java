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
package com.seizia.chromite.ui.navigator.actions;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.edit.DBECommand;
import com.seizia.chromite.model.edit.DBEObjectManager;
import com.seizia.chromite.model.edit.DBEObjectRenamer;
import com.seizia.chromite.model.navigator.DBNContainer;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.rm.RMConstants;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.TasksJob;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.dialogs.EnterNameDialog;
import com.seizia.chromite.ui.internal.UINavigatorMessages;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;

public class NavigatorHandlerObjectRename extends NavigatorHandlerObjectBase {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        final ISelection selection = HandlerUtil.getCurrentSelection(event);

        if (selection instanceof IStructuredSelection structSelection) {
            Object element = structSelection.getFirstElement();
            DBNNode node = RuntimeUtils.getObjectAdapter(element, DBNNode.class);
            if (node != null) {
                DBPProject nodeProject = node.getOwnerProjectOrNull();
                if (nodeProject == null || nodeProject.hasRealmPermission(RMConstants.PERMISSION_PROJECT_RESOURCE_EDIT)) {
                    renameNode(HandlerUtil.getActiveWorkbenchWindow(event), HandlerUtil.getActiveShell(event), node, null, this);
                }
            }
        }
        return null;
    }

    public static boolean renameNode(IWorkbenchWindow workbenchWindow, Shell shell, final DBNNode node, String newName, Object uiSource)
    {
        String oldName = node instanceof DBNDatabaseNode ? ((DBNDatabaseNode) node).getPlainNodeName(true,
            false) : node.getNodeDisplayName();
        if (oldName == null) {
            oldName = "?";
        }
        if (newName == null) {
            newName = EnterNameDialog.chooseName(shell,
                NLS.bind(UINavigatorMessages.actions_navigator_rename_object, node.getNodeTypeLabel()), oldName);
        }
        if (CommonUtils.isEmpty(newName) || newName.equals(oldName)) {
            return false;
        }

        if (node.supportsRename()) {
            try {
                // Rename with null monitor because it is some local resource
                String finalNewName = newName;
                UIUtils.runInProgressService(monitor -> {
                    try {
                        node.rename(monitor, finalNewName);
                    } catch (DBException e) {
                        throw new InvocationTargetException(e);
                    }
                });
                return true;
            } catch (Exception e) {
                DBWorkbench.getPlatformUI().showError(
                    UINavigatorMessages.actions_navigator_rename_object_exception_title,
                    NLS.bind(UINavigatorMessages.actions_navigator_rename_object_exception_message, oldName), e);
            }
        }
        if (node instanceof DBNDatabaseNode dbNode) {
            return renameDatabaseObject(
                workbenchWindow,
                dbNode,
                CommonUtils.toString(UIUtils.normalizePropertyValue(newName)), uiSource);
        }
        return false;
    }

    public static boolean renameDatabaseObject(IWorkbenchWindow workbenchWindow, DBNDatabaseNode node, String newName, Object uiSource)
    {
        try {
            if (node.getParentNode() instanceof DBNContainer) {
                DBSObject object = node.getObject();
                if (object != null) {
                    DBEObjectRenamer objectRenamer = DBWorkbench.getPlatform().getEditorsRegistry().getObjectManager(object.getClass(), DBEObjectRenamer.class);
                    if (objectRenamer != null) {
                        CommandTarget commandTarget = getCommandTarget(
                            workbenchWindow,
                            node.getParentNode(),
                            node,
                            object.getClass(),
                            false);

                        Map<String, Object> options = new LinkedHashMap<>();
                        options.put(DBEObjectManager.OPTION_UI_SOURCE, uiSource);
                        objectRenamer.renameObject(commandTarget.getContext(), object, options, newName);
                        if (object.isPersisted() && commandTarget.getEditor() == null) {
                            if (!showScript(workbenchWindow, commandTarget.getContext(), DBPScriptObject.EMPTY_OPTIONS,
                                UINavigatorMessages.actions_navigator_rename_script)) {
                                commandTarget.getContext().resetChanges(true);
                                return false;
                            } else {
                                ObjectSaver renamer = new ObjectSaver(commandTarget.getContext(), DBPScriptObject.EMPTY_OPTIONS);
                                TasksJob.runTask(NLS.bind(UINavigatorMessages.actions_navigator_rename_database_object,
                                    object.getName()), renamer);
                            }
                        } else {
                            for (DBECommand command : commandTarget.getContext().getFinalCommands()) {
                                //System.out.println(command);
                            }
                        }
                        return true;
                    }
                }
            }
        } catch (Throwable e) {
            DBWorkbench.getPlatformUI().showError(
                UINavigatorMessages.actions_navigator_rename_database_object_exception_title,
                NLS.bind(UINavigatorMessages.actions_navigator_rename_database_object_exception_message,
                    node.getNodeDisplayName()),
                e);
            return false;
        }
        return false;
    }

}