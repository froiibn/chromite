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
package com.seizia.chromite.ui.data.managers.image;

import org.eclipse.jface.action.IContributionManager;
import org.eclipse.swt.SWT;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.data.DBDContent;
import com.seizia.chromite.model.data.DBDContentStorage;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.controls.imageview.AbstractImageViewer;
import com.seizia.chromite.ui.controls.imageview.BrowserImageViewer;
import com.seizia.chromite.ui.controls.imageview.SWTImageViewer;
import com.seizia.chromite.ui.controls.resultset.ResultSetPreferences;
import com.seizia.chromite.ui.data.IStreamValueEditorPersistent;
import com.seizia.chromite.ui.data.IValueController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
* ImagePanelEditor
*/
public class ImagePanelEditor implements IStreamValueEditorPersistent<AbstractImageViewer> {

    @Override
    public AbstractImageViewer createControl(IValueController valueController) {
        final DBPPreferenceStore preferenceStore = valueController.getExecutionContext() != null
            ? valueController.getExecutionContext().getDataSource().getContainer().getPreferenceStore()
            : DBWorkbench.getPlatform().getPreferenceStore();
        if (preferenceStore.getBoolean(ResultSetPreferences.RESULT_IMAGE_USE_BROWSER_BASED_RENDERER)) {
            return new BrowserImageViewer(valueController.getEditPlaceholder(), SWT.NONE);
        } else {
            return new SWTImageViewer(valueController.getEditPlaceholder(), SWT.NONE);
        }
    }

    @Override
    public void primeEditorValue(
        @NotNull DBRProgressMonitor monitor,
        @Nullable AbstractImageViewer control,
        @NotNull DBDContent value
    ) throws DBException {
        monitor.subTask("Read image value");
        DBDContentStorage data = value.getContents(monitor);
        if (data != null) {
            try (InputStream contentStream = data.getContentStream()) {
                if (!(new UITask<Boolean>() {
                    @Override
                    protected Boolean runTask() {
                        if (control != null && !control.isDisposed()) {
                            return control.loadImage(contentStream);
                        } else {
                            return true; // already read
                        }
                    }
                }).runTask());

            } catch (IOException e) {
                throw new DBException("Error reading stream value", e);
            }
        } else {
            control.clearImage();
        }
    }

    @Override
    public void extractEditorValue(@NotNull DBRProgressMonitor monitor, @NotNull AbstractImageViewer control, @NotNull DBDContent value) throws DBException {
        // Not implemented
    }

    @Override
    public void contributeActions(@NotNull IContributionManager manager, @NotNull final AbstractImageViewer control) throws DBCException {
        control.fillToolBar(manager);
    }

    @Override
    public void contributeSettings(@NotNull IContributionManager manager, @NotNull AbstractImageViewer control) throws DBCException {

    }

    @Nullable
    @Override
    public Path getExternalFilePath(@NotNull AbstractImageViewer control) {
        return control.getExternalFilePath();
    }
}
