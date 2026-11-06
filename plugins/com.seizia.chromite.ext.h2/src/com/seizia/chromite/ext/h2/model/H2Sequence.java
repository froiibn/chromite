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
package com.seizia.chromite.ext.h2.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.generic.model.GenericSequence;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.IPropertyValueValidator;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.PropertyLength;
import org.jkiss.utils.CommonUtils;

public class H2Sequence extends GenericSequence {

    private long startValue;
    private long cache;
    private boolean isCycle;
    private String dataType;
    private String description;

    H2Sequence(GenericStructContainer container, String name, String description, Number lastValue, Number minValue, Number maxValue, Number incrementBy, @NotNull JDBCResultSet dbResult) {
        super(container, name, description, lastValue, minValue, maxValue, incrementBy);
        this.description = description;
        if (getDataSource().isServerVersionAtLeast(2, 0)) {
            this.startValue = JDBCUtils.safeGetLong(dbResult, "START_VALUE");
            this.cache = JDBCUtils.safeGetLong(dbResult, "CACHE");
            this.isCycle = CommonUtils.getBoolean(JDBCUtils.safeGetString(dbResult, "CYCLE_OPTION"), false);
            this.dataType = JDBCUtils.safeGetString(dbResult, "DATA_TYPE");
        }
    }

    @Property(viewable = true, order = 5, visibleIf = H2SequenceFieldsValueValidator.class)
    public long getStartValue() {
        return startValue;
    }

    @Property(viewable = true, order = 6, visibleIf = H2SequenceFieldsValueValidator.class)
    public long getCache() {
        return cache;
    }

    @Property(viewable = true, order = 7, visibleIf = H2SequenceFieldsValueValidator.class)
    public boolean isCycle() {
        return isCycle;
    }

    @Property(viewable = true, order = 8, visibleIf = H2SequenceFieldsValueValidator.class)
    public String getDataType() {
        return dataType;
    }

    @Nullable
    @Override
    @Property(viewable = true, editable = true, updatable = true, order = 5, length = PropertyLength.MULTILINE)
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public static class H2SequenceFieldsValueValidator implements IPropertyValueValidator<H2Sequence, Object> {

        @Override
        public boolean isValidValue(@NotNull H2Sequence object, @Nullable Object value) throws IllegalArgumentException {
            return object.getDataSource().isServerVersionAtLeast(2, 0);
        }
    }
}
