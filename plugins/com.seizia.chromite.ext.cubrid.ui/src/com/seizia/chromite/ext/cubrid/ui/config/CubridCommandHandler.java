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
package com.seizia.chromite.ext.cubrid.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.cubrid.model.CubridPrivilage;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.edit.prop.DBECommandComposite;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CubridCommandHandler extends DBECommandComposite<CubridPrivilage, CubridPrivilageHandler>
{

    protected CubridCommandHandler(CubridPrivilage object) {
        super(object, "Update User");
    }

    @Nullable
    @Override
    public DBEPersistAction[] getPersistActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull Map<String, Object> options) {
        List<DBEPersistAction> actions = new ArrayList<>();
        StringBuilder builder = new StringBuilder();
        if (getObject().isPersisted()) {
            builder.append("ALTER USER ");
            builder.append(DBUtils.getQuotedIdentifier(getObject()));
        }
        buildBody(builder);
        actions.add(new SQLDatabasePersistAction("Update User", builder.toString()));
        return actions.toArray(new DBEPersistAction[0]);

    }

    private void buildBody(StringBuilder builder) {
        for (Object key : getProperties().keySet()) {
            switch (key.toString()) {
                case "PASSWORD":
                    builder.append(" PASSWORD ").append(SQLUtils.quoteString(getObject(), getProperty(key).toString()));
                    break;
                case "DESCRIPTION":
                    builder.append(" COMMENT ").append(SQLUtils.quoteString(getObject(), getProperty(key).toString()));
                default:
                    break;
            }
        }
    }
}