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
package com.seizia.chromite.ext.vertica.model;

import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.generic.model.GenericUniqueKey;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.rdb.DBSTableCheckConstraint;

public class VerticaConstraint extends GenericUniqueKey implements DBSTableCheckConstraint {

    private String checkConstraintDefinition;
    private boolean isEnabled;

    public VerticaConstraint(GenericTableBase table, String name, @Nullable String remarks, DBSEntityConstraintType constraintType, boolean persisted, @Nullable String checkExpression, boolean isEnabled) {
        super(table, name, remarks, constraintType, persisted);
        this.checkConstraintDefinition = checkExpression;
        this.isEnabled = isEnabled;
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 4)
    public String getCheckConstraintDefinition() {
        return checkConstraintDefinition;
    }

    @Override
    public void setCheckConstraintDefinition(String expression) {
        this.checkConstraintDefinition = expression;
    }

    @Property(viewable = true, editable = true, updatable = true, order = 6)
    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }
}
