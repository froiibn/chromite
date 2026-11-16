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
package com.seizia.chromite.ext.cubrid.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameter;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameterKind;

public class CubridProcedureParameter implements DBSProcedureParameter {
    private String argName;
    private String dataType;
    private String mode;
    private String comment;
    private CubridProcedure procedure;

    public CubridProcedureParameter(
            @NotNull CubridProcedure procedure,
            @NotNull String argName,
            @NotNull String dataType,
            @NotNull String mode,
            @Nullable String comment) {
        this.procedure = procedure;
        this.argName = argName;
        this.dataType = dataType;
        this.mode = mode;
        this.comment = comment;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return argName;
    }

    @NotNull
    @Property(viewable = true, order = 3)
    public String getDataType() {
        return dataType;
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 5)
    public String getDescription() {
        return comment;
    }

    @Nullable
    @Override
    public DBSTypedObject getParameterType() {
        return (DBSTypedObject) this;
    }

    @NotNull
    @Override
    public CubridProcedure getParentObject() {
        return procedure;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 4)
    public DBSProcedureParameterKind getParameterKind() {
        switch (mode) {
            case "IN":
                return DBSProcedureParameterKind.IN;
            case "INOUT":
                return DBSProcedureParameterKind.INOUT;
            case "OUT":
                return DBSProcedureParameterKind.OUT;
            default:
                return DBSProcedureParameterKind.UNKNOWN;
        }
    }

    @Nullable
    @Override
    public DBPDataSource getDataSource() {
        return procedure.getDataSource();
    }

    @NotNull
    @Override
    public boolean isPersisted() {
        return true;
    }
}
