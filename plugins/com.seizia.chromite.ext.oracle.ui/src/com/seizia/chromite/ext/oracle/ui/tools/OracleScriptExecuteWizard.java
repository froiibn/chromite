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

package com.seizia.chromite.ext.oracle.ui.tools;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbench;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.tasks.OracleScriptExecuteSettings;
import com.seizia.chromite.ext.oracle.tasks.OracleTasks;
import com.seizia.chromite.ext.oracle.ui.internal.OracleUIMessages;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.registry.task.TaskPreferenceStore;
import com.seizia.chromite.tasks.ui.nativetool.AbstractNativeScriptExecuteWizard;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

class OracleScriptExecuteWizard extends AbstractNativeScriptExecuteWizard<OracleScriptExecuteSettings, DBSObject, OracleDataSource> {

    private OracleScriptExecuteWizardPageSettings mainPage;

    OracleScriptExecuteWizard(DBTTask task) {
        super(task);
    }

    OracleScriptExecuteWizard(OracleDataSource oracleSchema) {
        super(Collections.singleton(oracleSchema), OracleUIMessages.tools_script_execute_wizard_page_name);
    }

    OracleScriptExecuteWizard(@NotNull OracleDataSource oracleSchema, @Nullable Path sourceFile) {
        super(Collections.singleton(oracleSchema), OracleUIMessages.tools_script_execute_wizard_page_name, sourceFile);
    }

    @Override
    public void init(@NotNull IWorkbench workbench, @Nullable IStructuredSelection selection) {
        super.init(workbench, selection);

        this.mainPage = new OracleScriptExecuteWizardPageSettings(this);
    }

    @Override
    public String getTaskTypeId() {
        return OracleTasks.TASK_SCRIPT_EXECUTE;
    }

    @Override
    public void saveTaskState(DBRRunnableContext runnableContext, DBTTask task, Map<String, Object> state) {
        mainPage.saveState();

        getSettings().saveSettings(runnableContext, new TaskPreferenceStore(state));
    }

    @Override
    protected OracleScriptExecuteSettings createSettings() {
        return new OracleScriptExecuteSettings();
    }

    @Override
    public void addPages() {
        addTaskConfigPages();
        addPage(mainPage);
        super.addPages();
    }

}
