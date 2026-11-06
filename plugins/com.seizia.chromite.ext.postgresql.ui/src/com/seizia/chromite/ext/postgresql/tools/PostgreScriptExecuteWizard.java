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

package com.seizia.chromite.ext.postgresql.tools;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbench;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.postgresql.PostgreMessages;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.tasks.PostgreSQLTasks;
import com.seizia.chromite.ext.postgresql.tasks.PostgreScriptExecuteSettings;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.registry.task.TaskPreferenceStore;
import com.seizia.chromite.tasks.ui.nativetool.AbstractNativeScriptExecuteWizard;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

class PostgreScriptExecuteWizard extends AbstractNativeScriptExecuteWizard<PostgreScriptExecuteSettings, DBSObject, PostgreDatabase> {

    private PostgreScriptExecuteWizardPageSettings mainPage;

    PostgreScriptExecuteWizard(DBTTask task) {
        super(task);
    }

    PostgreScriptExecuteWizard(PostgreDatabase catalog) {
        super(Collections.singleton(catalog), PostgreMessages.wizard_script_title_execute_script);
        getSettings().setDatabase(catalog);
    }

    PostgreScriptExecuteWizard(@Nullable PostgreDatabase catalog, @Nullable Path file) {
        super(Collections.singleton(catalog), PostgreMessages.wizard_script_title_execute_script, file);

        getSettings().setDatabase(catalog);
    }

    @Override
    public void init(@NotNull IWorkbench workbench, @Nullable IStructuredSelection selection) {
        super.init(workbench, selection);
        this.mainPage = new PostgreScriptExecuteWizardPageSettings(this);
    }

    @Override
    public boolean isVerbose() {
        return false;
    }

    @Override
    public String getTaskTypeId() {
        return PostgreSQLTasks.TASK_SCRIPT_EXECUTE;
    }

    @Override
    public void saveTaskState(DBRRunnableContext runnableContext, DBTTask task, Map<String, Object> state) {
        mainPage.saveState();

        getSettings().saveSettings(runnableContext, new TaskPreferenceStore(state));
    }

    @Override
    protected PostgreScriptExecuteSettings createSettings() {
        return new PostgreScriptExecuteSettings();
    }

    @Override
    public void addPages() {
        addTaskConfigPages();
        addPage(mainPage);
        super.addPages();
    }

}
