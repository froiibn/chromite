/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ext.generic.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.impl.jdbc.struct.JDBCAttribute;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameter;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameterKind;

/**
 * GenericTable
 */
public class GenericProcedureParameter extends JDBCAttribute implements DBSProcedureParameter {
    private final String remarks;
    private final GenericProcedure procedure;
    private final DBSProcedureParameterKind parameterKind;

    public GenericProcedureParameter(
        @NotNull GenericProcedure procedure,
        String columnName,
        String typeName,
        int valueType,
        int ordinalPosition,
        int columnSize,
        Integer scale,
        Integer precision,
        boolean notNull,
        String remarks,
        @NotNull DBSProcedureParameterKind parameterKind
    ) {
        super(
            columnName,
            typeName,
            valueType,
            ordinalPosition,
            columnSize,
            scale,
            precision,
            notNull,
            false
        );
        this.remarks = remarks;
        this.procedure = procedure;
        this.parameterKind = parameterKind;
    }

    @NotNull
    @Override
    public GenericDataSource getDataSource() {
        return procedure.getDataSource();
    }

    @Override
    public GenericProcedure getParentObject() {
        return procedure;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 30)
    public DBSProcedureParameterKind getParameterKind() {
        return parameterKind;
    }

    @Nullable
    @Override
    public String getDescription() {
        return remarks;
    }

    @NotNull
    @Override
    public DBSTypedObject getParameterType() {
        return this;
    }
}
