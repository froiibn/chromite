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
package com.seizia.chromite.ext.yashandb.ui.config;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.oracle.ui.config.OracleSchemaConfigurator;
import com.seizia.chromite.ext.oracle.ui.config.OracleSchemaConfigurator.NewUserDialog;
import com.seizia.chromite.ext.yashandb.model.YashanDBDataSource;
import com.seizia.chromite.ext.yashandb.model.YashanDBSchema;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.UIUtils;

import java.util.Map;

/**
 * YashanDBSchemaConfigurator
 */
public class YashanDBSchemaConfigurator implements DBEObjectConfigurator<YashanDBSchema> {

    @NotNull
    @Override
    public YashanDBSchema configureObject(@NotNull DBRProgressMonitor monitor,
                                          @Nullable DBECommandContext commandContext, @NotNull Object container,
                                          @NotNull YashanDBSchema newSchema,
                                          @NotNull Map<String, Object> options) {
        return new UITask<YashanDBSchema>() {
            @NotNull
            @Override
            protected YashanDBSchema runTask() {
                NewUserDialog dialog = new OracleSchemaConfigurator.NewUserDialog(UIUtils.getActiveWorkbenchShell(),
                    (YashanDBDataSource) container);
                if (dialog.open() != IDialogConstants.OK_ID) {
                    return null;
                }
                newSchema.setName(dialog.getUser().getName());
                newSchema.setUser(dialog.getUser());

                return newSchema;
            }
        }.execute();
    }

}
