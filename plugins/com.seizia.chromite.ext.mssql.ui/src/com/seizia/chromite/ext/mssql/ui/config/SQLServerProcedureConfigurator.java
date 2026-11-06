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
package com.seizia.chromite.ext.mssql.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mssql.model.SQLServerProcedure;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.editors.object.struct.CreateProcedurePage;

import java.util.Map;

/**
 * SQLServerProcedureConfigurator
 */
public class SQLServerProcedureConfigurator implements DBEObjectConfigurator<SQLServerProcedure> {
    @NotNull
    @Override
    public SQLServerProcedure configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object container, @NotNull SQLServerProcedure newProcedure, @NotNull Map<String, Object> options) {
        return UITask.run(() -> {
            CreateProcedurePage editPage = new CreateProcedurePage(newProcedure);
            if (!editPage.edit()) {
                return null;
            }
            newProcedure.setProcedureType(editPage.getProcedureType());
            newProcedure.setName(editPage.getProcedureName());
            return newProcedure;
        });
    }

}

