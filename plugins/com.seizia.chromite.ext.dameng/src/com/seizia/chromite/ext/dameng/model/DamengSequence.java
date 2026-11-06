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
package com.seizia.chromite.ext.dameng.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.generic.model.GenericSequence;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBPSaveableObject;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.util.Map;

public class DamengSequence extends GenericSequence implements DBPSaveableObject, DBPScriptObject, DBPRefreshableObject {

    private long cacheSize;
    private boolean isCycle;
    private boolean isOrder;
    private String sql;

    public DamengSequence(
        @NotNull GenericStructContainer container,
        @NotNull String name,
        @Nullable String description,
        long lastValue,
        long minValue,
        long maxValue,
        long incrementBy,
        @NotNull JDBCResultSet dbResult
    ) {
        super(container, name, description, lastValue, minValue, maxValue, incrementBy);
        this.cacheSize = JDBCUtils.safeGetLong(dbResult, "CACHE_SIZE");
        this.isCycle = JDBCUtils.safeGetBoolean(dbResult, "IS_CYCLE");
        this.isOrder = JDBCUtils.safeGetBoolean(dbResult, "IS_ORDER");
    }

    public DamengSequence(@NotNull GenericStructContainer container, @NotNull String name) {
        super(container, name);
    }

    @Override
    @Property(viewable = true, order = 3, editable = true)
    public Long getMinValue() {
        return super.getMinValue().longValue();
    }

    @Override
    @Property(viewable = true, order = 4, editable = true, updatable = true)
    public Long getMaxValue() {
        return super.getMaxValue().longValue();
    }

    @Override
    @Property(viewable = true, order = 5, editable = true, updatable = true)
    public Long getIncrementBy() {
        return super.getIncrementBy().longValue();
    }

    @Property(viewable = true, order = 6)
    public long getCacheSize() {
        return cacheSize;
    }

    @Property(viewable = true, order = 7)
    public boolean isCycle() {
        return isCycle;
    }

    @Property(viewable = true, order = 8)
    public boolean isOrder() {
        return isOrder;
    }

    @Nullable
    @Override
    public String getDescription() {
        return super.getDescription();
    }

    @NotNull
    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) {
        if (CommonUtils.isEmpty(sql)) {
            sql = buildStatement(false);
        }
        return sql;
    }

    public String buildStatement(boolean forUpdate) {
        StringBuilder sb = new StringBuilder();
        if (forUpdate) {
            sb.append("ALTER");
        } else {
            sb.append("CREATE");
        }
        sb.append(" SEQUENCE ");
        sb.append(getFullyQualifiedName(DBPEvaluationContext.DDL)).append(" ");
        if (getIncrementBy() != null) {
            sb.append("INCREMENT BY ").append(getIncrementBy()).append(" ");
        }
        if (!isPersisted() && getMinValue() != null) {
            sb.append("START WITH ").append(getMinValue()).append(" ");
        }
        if (getMaxValue() != null) {
            sb.append("MAXVALUE ").append(getMaxValue()).append(" ");
        }
        if (getMinValue() != null) {
            sb.append("MINVALUE ").append(getMinValue()).append(" ");
        }
        return sb.toString();
    }

    @Nullable
    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) {
        this.sql = null;
        return this;
    }
}
