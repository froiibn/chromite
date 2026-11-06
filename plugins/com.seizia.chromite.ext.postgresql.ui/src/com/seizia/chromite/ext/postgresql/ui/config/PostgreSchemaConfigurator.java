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

package com.seizia.chromite.ext.postgresql.ui.config;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.postgresql.model.PostgreSchema;
import com.seizia.chromite.ext.postgresql.ui.PostgreCreateSchemaDialog;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.UIUtils;

import java.util.Map;

/**
 * Postgre sequence configurator
 */
public class PostgreSchemaConfigurator implements DBEObjectConfigurator<PostgreSchema> {
    @NotNull
    @Override
    public PostgreSchema configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object parent, @NotNull PostgreSchema schema, @NotNull Map<String, Object> options) {
        return new UITask<PostgreSchema>() {
            @Override
            protected PostgreSchema runTask() {
                PostgreCreateSchemaDialog dialog = new PostgreCreateSchemaDialog(UIUtils.getActiveWorkbenchShell(), schema);
                if (dialog.open() != IDialogConstants.OK_ID) {
                    return null;
                }
                schema.setName(dialog.getName());
                schema.setOwner(dialog.getOwner());
                return schema;
            }
        }.execute();
    }

}
