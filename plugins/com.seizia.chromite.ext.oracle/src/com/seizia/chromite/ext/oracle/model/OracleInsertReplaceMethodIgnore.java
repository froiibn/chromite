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
package com.seizia.chromite.ext.oracle.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.data.DBDInsertReplaceMethod;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.model.struct.rdb.DBSTableConstraint;
import org.jkiss.utils.CommonUtils;

import java.util.Collection;
import java.util.Optional;

public class OracleInsertReplaceMethodIgnore implements DBDInsertReplaceMethod {
    private static final Log log = Log.getLog(OracleInsertReplaceMethodIgnore.class);

    @Nullable
    @Override
    public String getOpeningClause(@NotNull DBRProgressMonitor monitor, @NotNull DBSTable table) {
        if (table != null) {
            try {
                Collection<? extends DBSTableConstraint> constraints = table.getConstraints(monitor);
                if (!CommonUtils.isEmpty(constraints)) {
                    Optional<? extends DBSTableConstraint> tableConstraint = constraints
                            .stream().filter(key -> key.getConstraintType() == DBSEntityConstraintType.PRIMARY_KEY).findFirst();
                    if (tableConstraint.isPresent()) {
                        DBSTableConstraint constraint = tableConstraint.get();
                        return "INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(" + table.getName() + ", " + constraint.getName() + ") */ INTO";
                    }
                }
            } catch (DBException e) {
                log.debug("Can't read table constraints list");
            }
        }
        return "INSERT INTO";
    }

    @Nullable
    @Override
    public String getTrailingClause(@NotNull DBRProgressMonitor monitor, @NotNull DBSTable table, @NotNull DBSAttributeBase[] attributes) {
        return null;
    }
}
