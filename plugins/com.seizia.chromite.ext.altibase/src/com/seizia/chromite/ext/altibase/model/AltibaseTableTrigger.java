/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ext.altibase.model;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;

/**
 * AltibaseTableTrigger
 */
public class AltibaseTableTrigger extends AltibaseTrigger<GenericTableBase> {
    public AltibaseTableTrigger(GenericTableBase container, String name, String description, JDBCResultSet dbResult) {
        super(container, name, description, dbResult);
    }

    @Override
    public AltibaseTable getTable() {
        return (AltibaseTable) getParentObject();
    }

    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context) {
        return DBUtils.getFullQualifiedName(getDataSource(), getTable().getCatalog(), getTable().getSchema(), this);
    }
}
