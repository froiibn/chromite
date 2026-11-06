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
package com.seizia.chromite.model.erd;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.*;
import com.seizia.chromite.model.struct.rdb.DBSTableConstraintColumn;

import java.util.ArrayList;
import java.util.List;

/**
 * Logical primary key
 */
public class ERDLogicalPrimaryKey implements DBSEntityConstraint,DBSEntityReferrer {

    private final Object entity;
    private final String name;
    private final String description;
    private final List<? extends DBSTableConstraintColumn> columns = new ArrayList<>();

    public ERDLogicalPrimaryKey(ERDElement<?> entity, String name, String description)
    {
        this.entity = entity.getObject();
        this.name = name;
        this.description = description;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource()
    {
        return entity instanceof DBSObject ? ((DBSObject) entity).getDataSource() : null;
    }

    @Nullable
    @Override
    public String getDescription()
    {
        return description;
    }

    @NotNull
    @Override
    public DBSEntity getParentObject()
    {
        return entity instanceof DBSEntity ? (DBSEntity) entity : null;
    }

    @NotNull
    @Override
    public DBSEntityConstraintType getConstraintType()
    {
        return DBSEntityConstraintType.PRIMARY_KEY;
    }

    @NotNull
    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public boolean isPersisted()
    {
        return false;
    }

    @Nullable
    @Override
    public List<? extends DBSEntityAttributeRef> getAttributeReferences(@Nullable DBRProgressMonitor monitor)
    {
        return columns;
    }
}
