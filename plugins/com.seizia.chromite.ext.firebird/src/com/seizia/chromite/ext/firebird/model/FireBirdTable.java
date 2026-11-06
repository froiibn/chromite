/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.firebird.FireBirdUtils;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericTable;
import com.seizia.chromite.ext.generic.model.GenericTableColumn;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.*;

public class FireBirdTable extends GenericTable implements FireBirdTableBase, DBPNamedObject2 {

    private int keyLength;
    private String externalFile;
    private String ownerName;
    private Map<String, String> columnDomainTypes;

    public FireBirdTable(GenericStructContainer container, @Nullable String tableName, @Nullable String tableType, @Nullable JDBCResultSet dbResult) {
        super(container, tableName, tableType, dbResult);

        if (dbResult != null) {
            keyLength = JDBCUtils.safeGetInt(dbResult, "RDB$DBKEY_LENGTH");
            externalFile = JDBCUtils.safeGetStringTrimmed(dbResult, "RDB$EXTERNAL_FILE");
            ownerName = JDBCUtils.safeGetStringTrimmed(dbResult, "RDB$OWNER_NAME");
        }
    }

    @Property(viewable = true, order = 20)
    public String getOwnerName() {
        return ownerName;
    }

    @Property(viewable = true, order = 21)
    public int getKeyLength() {
        return keyLength;
    }

    @Property(viewable = true, order = 22)
    public String getExternalFile() {
        return externalFile;
    }

    @Override
    protected boolean isTruncateSupported() {
        return false;
    }

    @Override
    public synchronized List<FireBirdTableColumn> getAttributes(@NotNull DBRProgressMonitor monitor) throws DBException {
        Collection<? extends GenericTableColumn> childColumns = super.getAttributes(monitor);
        if (childColumns == null) {
            return Collections.emptyList();
        }
        List<FireBirdTableColumn> columns = new ArrayList<>();
        for (GenericTableColumn gtc : childColumns) {
            columns.add((FireBirdTableColumn) gtc);
        }
        columns.sort(DBUtils.orderComparator());
        return columns;
    }

    @Nullable
    @Override
    public String getColumnDomainType(@NotNull DBRProgressMonitor monitor, @NotNull FireBirdTableColumn column) throws DBException {
        if (columnDomainTypes == null) {
            columnDomainTypes = FireBirdUtils.readColumnDomainTypes(monitor, this);
        }
        return columnDomainTypes.get(column.getName());
    }

    @Override
    public void setColumnDomainTypes(@NotNull Map<String, String> columnDomainTypes) {
        this.columnDomainTypes = columnDomainTypes;
    }

}
