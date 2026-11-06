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
package com.seizia.chromite.tasks.ui.nativetool;

import org.eclipse.jface.dialogs.IMessageProvider;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.fs.DBFUtils;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.tasks.nativetool.AbstractImportExportSettings;
import com.seizia.chromite.tasks.nativetool.ExportSettingsExtension;
import com.seizia.chromite.tasks.ui.nativetool.internal.TaskNativeUIMessages;
import com.seizia.chromite.ui.UIUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

public abstract class AbstractNativeExportWizard<SETTINGS extends AbstractImportExportSettings<DBSObject> & ExportSettingsExtension<INFO>, INFO>
    extends AbstractNativeImportExportWizard<SETTINGS, DBSObject> {

    protected AbstractNativeExportWizard(Collection<DBSObject> objects, String title) {
        super(objects, title);
    }

    protected AbstractNativeExportWizard(DBTTask task) {
        super(task);
    }

    @Override
    public boolean performFinish() {
        //verify that output files do not yet exist
        SETTINGS settings = getSettings();
        for (INFO info: settings.getExportObjects()) {
            try {
                Path dir = DBFUtils.resolvePathFromString(getRunnableContext(), getProject(), settings.getOutputFolder(info));
                if (!Files.exists(dir)) {
                    try {
                        Files.createDirectories(dir);
                    } catch (IOException e) {
                        logPage.setMessage("Can't create directory '" + dir.toString() + "': " + e.getMessage(), IMessageProvider.ERROR);
                        getContainer().updateMessage();
                        continue;
                    }
                }
                Path file = DBFUtils.resolvePathFromString(getRunnableContext(), getProject(), settings.getOutputFile(info));
                if (!Files.exists(file) || Files.isDirectory(file)) {
                    continue;
                }
                boolean deleteFile = UIUtils.confirmAction(
                    TaskNativeUIMessages.tools_db_export_wizard_file_already_exists_title,
                    TaskNativeUIMessages.tools_db_export_wizard_file_already_exists_message
                );
                if (!deleteFile) {
                    return false;
                }
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    DBWorkbench.getPlatformUI().showError(
                        TaskNativeUIMessages.tools_db_export_wizard_file_have_not_been_deleted_title,
                        TaskNativeUIMessages.tools_db_export_wizard_file_have_not_been_deleted_message,
                        e
                    );
                    return false;
                }
            } catch (DBException e) {
                DBWorkbench.getPlatformUI().showError("Error resolving file", "Error during output file resolution", e);
                return false;
            }
        }

        return super.performFinish();
    }
}
