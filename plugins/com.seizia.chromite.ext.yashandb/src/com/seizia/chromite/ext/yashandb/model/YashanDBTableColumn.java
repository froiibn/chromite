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
import com.seizia.chromite.ext.oracle.model.OracleTableColumn;
import com.seizia.chromite.model.impl.DBPositiveNumberTransformer;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.sql.ResultSet;


/**
 * YashanDBTableColumn
 */
public class YashanDBTableColumn extends OracleTableColumn {

    public YashanDBTableColumn(@NotNull DBRProgressMonitor monitor, @NotNull OracleTableBase table, @NotNull ResultSet dbResult)
            throws DBException {
        super(monitor, table, dbResult);
    }

    public YashanDBTableColumn(@NotNull OracleTableBase table) {
        super(table);
    }

    @Property(viewable = true, order = 15, valueRenderer = DBPositiveNumberTransformer.class)
    public int getOrdinalPosition() {
        // YashanDB column position start 0
        return ordinalPosition + 1;
    }

}
