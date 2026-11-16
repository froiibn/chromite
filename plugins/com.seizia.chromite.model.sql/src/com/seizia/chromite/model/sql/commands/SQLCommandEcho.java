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
package com.seizia.chromite.model.sql.commands;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLControlCommand;
import com.seizia.chromite.model.sql.SQLControlCommandHandler;
import com.seizia.chromite.model.sql.SQLControlResult;
import com.seizia.chromite.model.sql.SQLScriptContext;
import com.seizia.chromite.model.sql.eval.ScriptVariablesResolver;
import com.seizia.chromite.utils.GeneralUtils;

/**
 * Control command handler
 */
public class SQLCommandEcho implements SQLControlCommandHandler {

    @NotNull
    @Override
    public SQLControlResult handleCommand(@NotNull DBRProgressMonitor monitor, @NotNull SQLControlCommand command, @NotNull SQLScriptContext scriptContext) throws DBException {
        String parameter = command.getParameter();
        if (parameter != null) {
            parameter = GeneralUtils.replaceVariables(parameter, new ScriptVariablesResolver(scriptContext), true);
        }
        scriptContext.getOutputWriter().println(null, parameter);

        return SQLControlResult.success();
    }

}
