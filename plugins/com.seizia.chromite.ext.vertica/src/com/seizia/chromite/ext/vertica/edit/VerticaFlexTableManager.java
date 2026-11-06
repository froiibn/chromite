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
package com.seizia.chromite.ext.vertica.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.generic.edit.GenericTableManager;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.vertica.VerticaConstants;
import com.seizia.chromite.ext.vertica.model.VerticaFlexTable;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.Map;

public class VerticaFlexTableManager extends GenericTableManager {

    @Override
    protected GenericTableBase createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        GenericStructContainer structContainer = (GenericStructContainer) container;
        String tableName = getNewChildName(monitor, structContainer, "new_flex_table");
        return structContainer.getDataSource().getMetaModel().createTableOrViewImpl((GenericStructContainer) container , tableName, VerticaConstants.TYPE_FLEX_TABLE, null);
    }

    @Override
    protected String getCreateTableType(GenericTableBase table) {
        if (table instanceof VerticaFlexTable) {
            return VerticaConstants.TYPE_FLEX_TABLE;
        }
        return super.getCreateTableType(table);
    }

    @Override
    protected String getDropTableType(GenericTableBase table) {
        return "TABLE";
    }
}
