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
package com.seizia.chromite.model.impl.struct;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityType;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.model.struct.rdb.DBSTrigger;

import java.util.List;

/**
 * AbstractTable
 */
public abstract class AbstractTable<
    DATASOURCE extends DBPDataSource,
    CONTAINER extends DBSObject>
    implements DBSTable, DBPNamedObject2
{
    private final CONTAINER container;
    private String tableName;

    protected AbstractTable(@NotNull CONTAINER container) {
        this.container = container;
        this.tableName = "";
    }

    // Copy constructor
    protected AbstractTable(@NotNull CONTAINER container, @NotNull DBSEntity source) {
        this(container);
        this.tableName = source.getName();
    }

    protected AbstractTable(@NotNull CONTAINER container, @Nullable String tableName) {
        this(container);
        this.tableName = tableName;
    }

    @NotNull
    public CONTAINER getContainer()
    {
        return container;
    }

    @NotNull
    @Override
    public DBSEntityType getEntityType()
    {
        return DBUtils.isView(this) ? DBSEntityType.VIEW : DBSEntityType.TABLE;
    }

    @NotNull
    @Override
    @Property(viewable = true, editable = true, order = 1)
    public String getName()
    {
        return tableName;
    }

    @Override
    public void setName(@NotNull String tableName)
    {
        this.tableName = tableName;
    }

    @NotNull
    @Override
    @SuppressWarnings("unchecked")
    public DATASOURCE getDataSource()
    {
        return (DATASOURCE) container.getDataSource();
    }

    @Override
    public boolean isPersisted()
    {
        return true;
    }

    @Override
    public CONTAINER getParentObject()
    {
        return container;
    }

    public String toString()
    {
        return getFullyQualifiedName(DBPEvaluationContext.UI);
    }

    @Nullable
    @Override
    public List<? extends DBSTrigger> getTriggers(@NotNull DBRProgressMonitor monitor) throws DBException {
        return null;
    }
}
