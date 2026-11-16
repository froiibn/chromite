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
package com.seizia.chromite.model.impl.jdbc.struct;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPSaveableObject;
import com.seizia.chromite.model.impl.DBObjectNameCaseTransformer;
import com.seizia.chromite.model.impl.struct.AbstractTableConstraint;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.rdb.DBSTableConstraintColumn;

/**
 * JDBC abstract constraint
 */
public abstract class JDBCTableConstraint<TABLE extends JDBCTable, COLUMN extends DBSTableConstraintColumn>
    extends AbstractTableConstraint<TABLE, COLUMN>
    implements DBPSaveableObject {
    private boolean persisted;

    protected JDBCTableConstraint(
        @NotNull TABLE table,
        @Nullable String name,
        @Nullable String description,
        DBSEntityConstraintType constraintType,
        boolean persisted
    ) {
        super(table, name, description, constraintType);
        this.persisted = persisted;
    }

    // Copy constructor
    protected JDBCTableConstraint(@NotNull TABLE table, @NotNull DBSEntityConstraint source, boolean persisted) {
        super(table, source);
        this.persisted = persisted;
    }

    @NotNull
    @Property(viewable = true, editable = true, valueTransformer = DBObjectNameCaseTransformer.class, order = 1)
    @Override
    public String getName() {
        return super.getName();
    }

    @Override
    public boolean isPersisted() {
        return persisted;
    }

    @Override
    public void setPersisted(boolean persisted) {
        this.persisted = persisted;
    }
}
