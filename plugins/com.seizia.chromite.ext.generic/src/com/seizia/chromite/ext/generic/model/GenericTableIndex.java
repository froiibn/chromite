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
package com.seizia.chromite.ext.generic.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.impl.jdbc.struct.JDBCTableIndex;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.PropertyLength;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.rdb.DBSIndexType;

import java.util.ArrayList;
import java.util.List;

/**
 * GenericTable
 */
public class GenericTableIndex extends JDBCTableIndex<GenericStructContainer, GenericTableBase> {
    private boolean nonUnique;
    private final String qualifier;
    private final long cardinality;
    private List<GenericTableIndexColumn> columns;

    public GenericTableIndex(
        @NotNull GenericTableBase table,
        boolean nonUnique,
        String qualifier,
        long cardinality,
        String indexName,
        DBSIndexType indexType,
        boolean persisted
    ) {
        super(table.getContainer(), table, indexName, indexType, persisted);
        this.nonUnique = nonUnique;
        this.qualifier = qualifier;
        this.cardinality = cardinality;
    }

    /**
     * Copy constructor
     *
     * @param source source index
     */
    GenericTableIndex(@NotNull GenericTableIndex source) {
        super(source);
        this.nonUnique = source.nonUnique;
        this.qualifier = source.qualifier;
        this.cardinality = source.cardinality;
        if (source.columns != null) {
            this.columns = new ArrayList<>(source.columns.size());
            for (GenericTableIndexColumn sourceColumn : source.columns) {
                this.columns.add(new GenericTableIndexColumn(this, sourceColumn));
            }
        }
    }

    @NotNull
    @Override
    public GenericDataSource getDataSource() {
        return getTable().getDataSource();
    }

    @Nullable
    @Override
    @Property(viewable = true, length = PropertyLength.MULTILINE, order = 100)
    public String getDescription() {
        return null;
    }

    @Override
    @Property(viewable = true, order = 4)
    public boolean isUnique() {
        return !nonUnique;
    }

    public void setUnique(boolean unique) {
        this.nonUnique = !unique;
    }

    @Property(viewable = true, order = 5)
    public String getQualifier() {
        return qualifier;
    }

    @Property(viewable = true, order = 5)
    public long getCardinality() {
        return cardinality;
    }

    @Nullable
    @Override
    public List<GenericTableIndexColumn> getAttributeReferences(@Nullable DBRProgressMonitor monitor) {
        return columns;
    }

    @Nullable
    public GenericTableIndexColumn getColumn(@NotNull String columnName) {
        return DBUtils.findObject(columns, columnName);
    }

    public void setColumns(List<GenericTableIndexColumn> columns) {
        this.columns = columns;
    }

    public void addColumn(@NotNull GenericTableIndexColumn column) {
        if (columns == null) {
            columns = new ArrayList<>();
        }
        columns.add(column);
    }

    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context) {
        return DBUtils.getFullQualifiedName(
            getDataSource(),
            getTable().getCatalog(),
            getTable().getSchema(),
            this
        );
    }

}
