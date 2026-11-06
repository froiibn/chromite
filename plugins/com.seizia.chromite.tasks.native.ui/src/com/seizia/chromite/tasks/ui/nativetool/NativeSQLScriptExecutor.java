/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.tasks.ui.wizard.TaskConfigurationWizard;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.actions.exec.SQLScriptExecutor;

import java.nio.file.Path;

public abstract class NativeSQLScriptExecutor<CONTAINER extends DBSObject> implements SQLScriptExecutor<CONTAINER> {

    public void execute(@NotNull CONTAINER container, @Nullable Path file) throws DBException {
        NativeToolWizardDialog dialog = new NativeToolWizardDialog(
            UIUtils.getActiveWorkbenchWindow(),
            createTaskConfigurationWizard(container, file));
        dialog.open();
    }

    @NotNull
    protected abstract TaskConfigurationWizard<?> createTaskConfigurationWizard(
        @NotNull CONTAINER container,
        @Nullable Path editor);
}
