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
package com.seizia.chromite.ext.postgresql.model.impls.redshift;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.postgresql.PostgreConstants;
import com.seizia.chromite.ext.postgresql.model.PostgreDataType;
import com.seizia.chromite.ext.postgresql.model.PostgreOid;
import com.seizia.chromite.ext.postgresql.model.PostgreTableColumn;
import com.seizia.chromite.ext.postgresql.model.data.type.PostgreTypeHandler;
import com.seizia.chromite.ext.postgresql.model.data.type.PostgreTypeHandlerProvider;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.gis.GisConstants;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

/**
 * RedshiftTableColumn base
 */
public class RedshiftTableColumn extends PostgreTableColumn {
    private String columnEncoding;
    private boolean distKey;
    private int sortKey;

    public RedshiftTableColumn(RedshiftTable table) {
        super(table);
    }

    public RedshiftTableColumn(DBRProgressMonitor monitor, RedshiftTable table, JDBCResultSet dbResult) throws DBException {
        super(monitor, table, dbResult);

        columnEncoding = JDBCUtils.safeGetString(dbResult, "encoding");
        distKey = JDBCUtils.safeGetBoolean(dbResult, "attisdistkey");
        sortKey = JDBCUtils.safeGetInt(dbResult, "attsortkeyord");
    }

    @Property(viewable = true, order = 21)
    public String getColumnEncoding() {
        return columnEncoding;
    }

    @Property(viewable = false, order = 22)
    public boolean isDistKey() {
        return distKey;
    }

    @Property(viewable = false, order = 23)
    public int getSortKey() {
        return sortKey;
    }

    @Override
    public int getAttributeGeometrySRID(DBRProgressMonitor monitor) {
        return GisConstants.SRID_SIMPLE;
    }

    @Nullable
    @Override
    public String getAttributeGeometryType(DBRProgressMonitor monitor) {
        return getTypeName();
    }

    @NotNull
    @Override
    @Property(viewable = true, editable = true, updatable = true, order = 20, listProvider = DataTypeListProvider.class)
    public String getFullTypeName() {
        PostgreDataType dataType = getDataType();
        if (dataType != null && dataType.getObjectId() == PostgreOid.BPCHAR) {
            // Redshift stores char columns with bpchar id in pg_type table for some reason.
            // You can create bpchar column in Redshift but only without type modifiers.
            final PostgreTypeHandler handler = PostgreTypeHandlerProvider.getTypeHandler(dataType);
            if (handler != null) {
                return PostgreConstants.TYPE_CHAR + handler.getTypeModifiersString(dataType, getTypeMod());
            }
            return PostgreConstants.TYPE_CHAR;
        }
        return super.getFullTypeName();
    }
}
