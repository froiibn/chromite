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
package com.seizia.chromite.ext.firebird.model;

import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.generic.model.GenericProcedure;
import com.seizia.chromite.ext.generic.model.GenericProcedureParameter;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameterKind;

public class FireBirdProcedureParameter extends GenericProcedureParameter {

    private final String defaultValue;
    private final String fieldName;
    private final String relationName;

    public FireBirdProcedureParameter(
            GenericProcedure procedure,
            String columnName,
            String typeName,
            int valueType,
            int ordinalPosition,
            int columnSize,
            Integer scale,
            Integer precision,
            boolean notNull,
            String remarks,
            DBSProcedureParameterKind parameterKind,
            String defaultValue,
            String fieldName,
            String relationName
    ) {
        super(procedure, columnName, typeName, valueType, ordinalPosition, columnSize, scale, precision, notNull, remarks, parameterKind);
        this.defaultValue = defaultValue;
        this.fieldName = fieldName;
        this.relationName = relationName;
    }

    @Nullable
    public String getDefaultValue() {
        return defaultValue;
    }

    @Nullable
    public String getFieldName() {
        return fieldName;
    }

    @Nullable
    public String getRelationName() {
        return relationName;
    }
}
