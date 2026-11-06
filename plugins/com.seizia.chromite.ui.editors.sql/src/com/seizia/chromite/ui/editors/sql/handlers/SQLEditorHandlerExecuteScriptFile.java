/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ui.editors.sql.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPPlatformDesktop;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.DBNResource;
import com.seizia.chromite.model.rcp.RCPProject;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.model.task.DBTTaskManager;
import com.seizia.chromite.model.task.DBTTaskType;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.tasks.ui.DBTTaskConfigurator;
import com.seizia.chromite.tasks.ui.registry.TaskUIRegistry;
import com.seizia.chromite.tasks.ui.wizard.TaskConfigurationWizard;
import com.seizia.chromite.tasks.ui.wizard.TaskConfigurationWizardDialog;
import com.seizia.chromite.tools.sql.SQLScriptExecuteSettings;
import com.seizia.chromite.tools.sql.SQLTaskConstants;
import com.seizia.chromite.ui.editors.EditorUtils;
import com.seizia.chromite.ui.editors.SimpleDatabaseEditorContext;
import com.seizia.chromite.ui.editors.sql.internal.SQLEditorMessages;
import com.seizia.chromite.ui.navigator.dialogs.SelectDataSourceDialog;
import com.seizia.chromite.utils.RuntimeUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SQLEditorHandlerExecuteScriptFile extends AbstractHandler {

    private static final Log log = Log.getLog(SQLEditorHandlerExecuteScriptFile.class);

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell activeShell = HandlerUtil.getActiveShellChecked(event);
        IWorkbenchWindow workbenchWindow = HandlerUtil.getActiveWorkbenchWindowChecked(event);
        List<IFile> scripts = getSelectedScripts(HandlerUtil.getCurrentSelection(event));
        for (IFile script : scripts) {
            executeScriptFile(activeShell, workbenchWindow, script);
        }
        return null;
    }

    @NotNull
    private List<IFile> getSelectedScripts(@Nullable ISelection selection) {
        List<IFile> scripts = new ArrayList<>();
        if (selection instanceof IStructuredSelection && !selection.isEmpty()) {
            for (Object element : ((IStructuredSelection) selection).toList()) {
                DBNNode node = RuntimeUtils.getObjectAdapter(element, DBNNode.class);
                if (node instanceof DBNResource) {
                    IResource resource = ((DBNResource) node).getResource();
                    if (resource instanceof IFile) {
                        scripts.add((IFile) resource);
                    }
                }
            }
        }
        return scripts;
    }

    private void executeScriptFile(@NotNull Shell shell, @NotNull IWorkbenchWindow workbenchWindow, @NotNull IFile script) {
        DBPDataSourceContainer resolvedDataSource = EditorUtils.getFileDataSource(script);
        if (resolvedDataSource == null) {
            resolvedDataSource = chooseDataSource(shell, script);
            if (resolvedDataSource == null) {
                return;
            }
        }
        final DBPDataSourceContainer dataSourceContainer = resolvedDataSource;

        DBPProject project = DBPPlatformDesktop.getInstance().getWorkspace().getProject(script.getProject());
        if (!(project instanceof RCPProject rcpProject)) {
            DBWorkbench.getPlatformUI().showError(
                SQLEditorMessages.execute_script_file_error_title,
                NLS.bind(SQLEditorMessages.execute_script_file_project_not_found, script.getName()));
            return;
        }

        DBTTaskManager taskManager = project.getTaskManager();
        DBTTaskType taskType = taskManager.getRegistry().getTaskType(SQLTaskConstants.TASK_SCRIPT_EXECUTE);
        if (taskType == null) {
            DBWorkbench.getPlatformUI().showError(
                SQLEditorMessages.execute_script_file_error_title,
                SQLEditorMessages.execute_script_file_task_type_not_available);
            return;
        }
        if (!TaskUIRegistry.getInstance().supportsConfigurator(taskType)) {
            DBWorkbench.getPlatformUI().showError(
                SQLEditorMessages.execute_script_file_error_title,
                NLS.bind(SQLEditorMessages.execute_script_file_no_configuration_ui, taskType.getName()));
            return;
        }

        SQLScriptExecuteSettings settings = new SQLScriptExecuteSettings();
        settings.setScriptFiles(List.of(rcpProject.getResourcePath(script)));
        settings.setDataSources(List.of(dataSourceContainer));
        settings.setIgnoreErrors(false);

        Map<String, Object> config = new LinkedHashMap<>();
        settings.saveConfiguration(config);

        try {

            DBTTask task = taskManager.createTemporaryTask(taskType, NLS.bind(SQLEditorMessages.execute_script_file_task_name, script.getName()));
            task.setProperties(config);
            DBTTaskConfigurator configurator = TaskUIRegistry.getInstance().createConfigurator(taskType);
            TaskConfigurationWizard<?> wizard = configurator.createTaskConfigWizard(task);
            TaskConfigurationWizardDialog dialog = new TaskConfigurationWizardDialog(workbenchWindow, wizard);
            dialog.open();
        } catch (Exception e) {
            log.debug("Error opening execute script task for '" + script.getName() + "'", e);
            DBWorkbench.getPlatformUI()
                .showError(
                    SQLEditorMessages.execute_script_file_error_title,
                    NLS.bind(SQLEditorMessages.execute_script_file_open_error, script.getName()),
                    e);
        }
    }

    @Nullable
    private DBPDataSourceContainer chooseDataSource(@NotNull Shell shell, @NotNull IFile script) {
        SelectDataSourceDialog dialog = new SelectDataSourceDialog(
            shell,
            DBPPlatformDesktop.getInstance().getWorkspace().getProject(script.getProject()),
            null
        );
        if (dialog.open() == IDialogConstants.CANCEL_ID) {
            return null;
        }
        DBPDataSourceContainer dataSource = dialog.getDataSource();
        if (dataSource != null) {
            EditorUtils.setFileDataSource(script, new SimpleDatabaseEditorContext(dataSource));
        }
        return dataSource;
    }
}
