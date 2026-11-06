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
package com.seizia.chromite.ext.altibase.ui.config;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.altibase.model.AltibaseProcedureStandAlone;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.editors.object.struct.CreateProcedurePage;
import com.seizia.chromite.utils.GeneralUtils;

import java.util.Map;

/**
 * AltibaseProcedureConfigurator
 */
public class AltibaseProcedureConfigurator implements DBEObjectConfigurator<AltibaseProcedureStandAlone> {

    @NotNull
    @Override
    public AltibaseProcedureStandAlone configureObject(
        @NotNull DBRProgressMonitor monitor, DBECommandContext commandContext,
        Object container, @NotNull AltibaseProcedureStandAlone procedure, @NotNull Map<String, Object> options) {
        return new UITask<AltibaseProcedureStandAlone>() {
            @Override
            protected AltibaseProcedureStandAlone runTask() {
                CreateProcedurePage editPage = new CreateProcedurePage(procedure);
                if (!editPage.edit()) {
                    return null;
                }
                DBSProcedureType procedureType = editPage.getProcedureType();
                String procedureName = editPage.getProcedureName();

                procedure.setName(procedureName);
                procedure.setProcedureType(procedureType);

                procedure.setObjectDefinitionText(
                        "CREATE OR REPLACE " + procedureType.name() + " " + procedureName +
                        (procedureType == DBSProcedureType.FUNCTION ? "() RETURN NUMBER" : "") + GeneralUtils.getDefaultLineSeparator() +
                        "IS" + GeneralUtils.getDefaultLineSeparator() +
                        "BEGIN" + GeneralUtils.getDefaultLineSeparator() +
                        (procedureType == DBSProcedureType.FUNCTION ? "\tRETURN 1;" : "") +
                        GeneralUtils.getDefaultLineSeparator() + 
                        "END");

                return procedure;
            }
        }.execute();
    }
}
