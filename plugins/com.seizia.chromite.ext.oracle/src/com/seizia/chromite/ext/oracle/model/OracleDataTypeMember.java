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
package com.seizia.chromite.ext.oracle.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.impl.DBObjectNameCaseTransformer;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSEntityElement;

import java.sql.ResultSet;

/**
 * Oracle data type member
 */
public abstract class OracleDataTypeMember implements DBSEntityElement
{

    private static final Log log = Log.getLog(OracleDataTypeMember.class);

    private OracleDataType ownerType;
    protected String name;
    protected int number;
    private boolean inherited;
    private boolean persisted;

    protected OracleDataTypeMember(OracleDataType ownerType)
    {
        this.ownerType = ownerType;
        this.persisted = false;
    }

    protected OracleDataTypeMember(OracleDataType ownerType, ResultSet dbResult)
    {
        this.ownerType = ownerType;
        this.inherited = JDBCUtils.safeGetBoolean(dbResult, "INHERITED", OracleConstants.YES);
        this.persisted = true;
    }

    @NotNull
    public OracleDataType getOwnerType()
    {
        return ownerType;
    }

    @Nullable
    @Override
    public String getDescription()
    {
        return null;
    }

    @NotNull
    @Override
    public OracleDataType getParentObject()
    {
        return ownerType;
    }

    @NotNull
    @Override
    public OracleDataSource getDataSource()
    {
        return ownerType.getDataSource();
    }

    @Override
    public boolean isPersisted()
    {
        return persisted;
    }

    @NotNull
    @Override
    @Property(viewable = true, editable = true, valueTransformer = DBObjectNameCaseTransformer.class, order = 1)
    public String getName()
    {
        return name;
    }

    public int getNumber()
    {
        return number;
    }

    @Property(viewable = true, order = 20)
    public boolean isInherited()
    {
        return inherited;
    }
}
