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
package com.seizia.chromite.ext.mysql.tools;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.ui.IWorkbench;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mysql.tasks.MySQLDatabaseExportInfo;
import com.seizia.chromite.ext.mysql.tasks.MySQLExportSettings;
import com.seizia.chromite.ext.mysql.tasks.MySQLTasks;
import com.seizia.chromite.ext.mysql.ui.internal.MySQLUIMessages;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.registry.task.TaskPreferenceStore;
import com.seizia.chromite.tasks.ui.nativetool.AbstractNativeExportWizard;
import com.seizia.chromite.ui.ShellUtils;
import com.seizia.chromite.ui.UIUtils;
import org.jkiss.utils.CommonUtils;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

class MySQLExportWizard extends AbstractNativeExportWizard<MySQLExportSettings, MySQLDatabaseExportInfo> {
    private MySQLExportWizardPageObjects objectsPage;
    private MySQLExportWizardPageSettings settingsPage;

    MySQLExportWizard(Collection<DBSObject> objects) {
        super(objects, MySQLUIMessages.tools_db_export_wizard_task_name);
        getSettings().fillExportObjectsFromInput();
    }

    MySQLExportWizard(DBTTask task) {
        super(task);
    }

    @Override
    protected MySQLExportSettings createSettings() {
        return new MySQLExportSettings();
    }

    @Override
    public String getTaskTypeId() {
        return MySQLTasks.TASK_DATABASE_BACKUP;
    }

    @Override
    public void saveTaskState(DBRRunnableContext runnableContext, DBTTask task, Map<String, Object> state) {
        if (objectsPage.getControl() != null) {
            objectsPage.saveState();
        }
        if (settingsPage.getControl() != null) {
            settingsPage.saveState();
        }
        getSettings().saveSettings(runnableContext, new TaskPreferenceStore(state));
    }

    @Override
    public void init(@NotNull IWorkbench workbench, @Nullable IStructuredSelection selection) {
        super.init(workbench, selection);
        objectsPage = new MySQLExportWizardPageObjects(this);
        settingsPage = new MySQLExportWizardPageSettings(this);
    }

    @Override
    public void addPages() {
        addTaskConfigPages();
        addPage(objectsPage);
        addPage(settingsPage);
        super.addPages();
    }

    @Override
    public IWizardPage getNextPage(@NotNull IWizardPage page) {
        if (page == settingsPage) {
            return null;
        }
        return super.getNextPage(page);
    }

    @Override
    public IWizardPage getPreviousPage(@NotNull IWizardPage page) {
        if (page == logPage) {
            return settingsPage;
        }
        return super.getPreviousPage(page);
    }

    @Override
	public void onSuccess(long workTime) {
        UIUtils.showMessageBox(
            getShell(),
            MySQLUIMessages.tools_db_export_wizard_title,
            CommonUtils.truncateString(NLS.bind(MySQLUIMessages.tools_db_export_wizard_message_export_completed, getObjectsName()), 255),
            SWT.ICON_INFORMATION);

        Set<String> set = getSettings().getExportObjects().stream().map(it -> getSettings().getOutputFolder(it)).collect(Collectors.toSet());
        set.forEach(ShellUtils::launchProgram);
	}
}
