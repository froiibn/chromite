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
package com.seizia.chromite.ext.oracle.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.oracle.model.OracleProcedureStandalone;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.editors.object.struct.CreateProcedurePage;
import com.seizia.chromite.utils.GeneralUtils;

import java.util.Map;

/**
 * OracleProcedureConfigurator
 */
public class OracleProcedureConfigurator implements DBEObjectConfigurator<OracleProcedureStandalone> {

    @NotNull
    @Override
    public OracleProcedureStandalone configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object container, @NotNull OracleProcedureStandalone procedure, @NotNull Map<String, Object> options) {
        return new UITask<OracleProcedureStandalone>() {
            @Override
            protected OracleProcedureStandalone runTask() {
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
                        (procedureType == DBSProcedureType.FUNCTION ? "\tRETURN 1;" + GeneralUtils.getDefaultLineSeparator() : "") +
                        "END " + procedureName + ";" + GeneralUtils.getDefaultLineSeparator());


                return procedure;
            }
        }.execute();
    }

}
