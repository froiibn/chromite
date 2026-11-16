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
package com.seizia.chromite.ext.yashandb.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.oracle.model.OracleTableBase;
import com.seizia.chromite.ext.oracle.model.OracleTableTrigger;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.sql.ResultSet;
import java.util.Map;


/**
 * YashanDBTableTrigger
 */
public class YashanDBTableTrigger extends OracleTableTrigger {

    public YashanDBTableTrigger(@NotNull OracleTableBase table, @NotNull ResultSet dbResult) {
        super(table, dbResult);
    }

    public YashanDBTableTrigger(@NotNull OracleTableBase table, @NotNull String name) {
        super(table, name);
    }

    @NotNull
    @Override
    @Property(hidden = true, editable = true, updatable = true, order = -1)
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options)
            throws DBException {
        if (sourceDeclaration == null && monitor != null) {
            sourceDeclaration = YashanDBUtils.getSource(monitor, this, false, false);
        }
        return sourceDeclaration;
    }
}
