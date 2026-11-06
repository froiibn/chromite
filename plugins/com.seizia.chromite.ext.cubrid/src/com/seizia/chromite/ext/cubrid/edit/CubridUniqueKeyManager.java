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
package com.seizia.chromite.ext.cubrid.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.cubrid.model.CubridTable;
import com.seizia.chromite.ext.cubrid.model.CubridUniqueKey;
import com.seizia.chromite.ext.generic.GenericConstants;
import com.seizia.chromite.ext.generic.edit.GenericPrimaryKeyManager;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;

import java.util.Map;

public class CubridUniqueKeyManager extends GenericPrimaryKeyManager {
  
    @Override
    protected CubridUniqueKey createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object from,
        @NotNull Map<String, Object> options) {
        CubridTable table = (CubridTable) container;
        return new CubridUniqueKey(table, GenericConstants.BASE_CONSTRAINT_NAME, null, DBSEntityConstraintType.PRIMARY_KEY, false);
    }
}
