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

package com.seizia.chromite.ext.dameng.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.dameng.model.DamengSchema;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.dialogs.EnterNameDialog;
import org.jkiss.utils.CommonUtils;

import java.util.Map;

/**
 * @author Shengkai Bai
 */
public class DamengSchemaConfigurator implements DBEObjectConfigurator<DamengSchema> {
    @NotNull
    @Override
    public DamengSchema configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object container, @NotNull DamengSchema damengSchema, @NotNull Map<String, Object> options) {
        return UITask.run(() -> {
            String schemaName = EnterNameDialog.chooseName(
                    UIUtils.getActiveWorkbenchShell(),
                    "New Schema",
                    ""
            );
            if (CommonUtils.isEmpty(schemaName)) {
                return null;
            }
            damengSchema.setName(schemaName);
            return damengSchema;
        });
    }
}
