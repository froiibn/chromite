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
package com.seizia.chromite.model.impl.struct;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.*;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.PropertyLength;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.rdb.DBSProcedure;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;

/**
 * AbstractProcedure
 */
public abstract class AbstractProcedure<
    DATASOURCE extends DBPDataSource,
    CONTAINER extends DBSObjectContainer>
    implements DBSProcedure, DBPSaveableObject, DBPImageProvider, DBPNamedObject2
{
    protected CONTAINER container;
    protected String name;
    protected String description;
    protected boolean persisted;

    protected AbstractProcedure(CONTAINER container, boolean persisted)
    {
        this.container = container;
        this.persisted = persisted;
    }

    protected AbstractProcedure(CONTAINER container, boolean persisted, String name, String description)
    {
        this(container, persisted);
        this.name = name;
        this.description = description;
    }

    @Override
    public CONTAINER getContainer()
    {
        return container;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName()
    {
        return name;
    }

    @Override
    public void setName(@NotNull String name)
    {
        this.name = name;
    }

    @Nullable
    @Override
    @Property(viewable = true, length = PropertyLength.MULTILINE, order = 100)
    public String getDescription()
    {
        return description;
    }

    public void setDescription(String description)
    {
        this.description = description;
    }

    @NotNull
    @Override
    public DATASOURCE getDataSource()
    {
        return (DATASOURCE) container.getDataSource();
    }

    @Override
    public boolean isPersisted()
    {
        return persisted;
    }

    @Override
    public void setPersisted(boolean persisted)
    {
        this.persisted = persisted;
    }

    @Override
    public DBSObject getParentObject()
    {
        return container;
    }

    @Nullable
    @Override
    public DBPImage getObjectImage() {
        if (getProcedureType() == DBSProcedureType.FUNCTION) {
            return DBIcon.TREE_FUNCTION;
        } else {
            return DBIcon.TREE_PROCEDURE;
        }
    }
}
