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
package com.seizia.chromite.ext.databricks.model.types;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.impl.struct.AbstractAttribute;
import com.seizia.chromite.model.struct.DBSDataType;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSTypedObjectEx;

public class DatabricksDataTypeAttribute<OWNER extends DatabricksDataType & DBSEntity>
    extends AbstractAttribute implements DBSEntityAttribute, DBSTypedObjectEx {

    @NotNull
    private final OWNER owner;

    @NotNull
    private final DBSDataType attributeType;

    DatabricksDataTypeAttribute(
        @NotNull OWNER owner,
        @NotNull DBSDataType attr,
        @NotNull String name,
        int position
    ) {
        super(name, attr.getTypeName(), attr.getTypeID(), position, -1, null, null, false, false);
        this.owner = owner;
        this.attributeType = attr;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof DatabricksDataTypeAttribute<?> other && this.attributeType.equals(other.attributeType);
    }

    @NotNull
    @Override
    public DBSEntity getParentObject() {
        return this.owner;
    }

    @Nullable
    @Override
    public DBPDataSource getDataSource() {
        return this.owner.getDataSource();
    }

    @NotNull
    @Override
    public DBPDataKind getDataKind() {
        return this.attributeType.getDataKind();
    }

    @Nullable
    @Override
    public String getDefaultValue() {
        return null;
    }

    @NotNull
    @Override
    public DBSDataType getDataType() {
        return this.attributeType;
    }
}
