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
package com.seizia.chromite.ext.h2.model;

import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericTable;
import com.seizia.chromite.model.DBPObjectStatistics;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;

public class H2Table extends GenericTable implements DBPObjectStatistics {

    private Long tableDiskSize;

    H2Table(GenericStructContainer container, @Nullable String tableName, @Nullable String tableType, @Nullable JDBCResultSet dbResult) {
        super(container, tableName, tableType, dbResult);
    }

    @Override
    public boolean hasStatistics() {
        return tableDiskSize != null;
    }

    @Override
    public long getStatObjectSize() {
        return tableDiskSize == null ? 0 : tableDiskSize;
    }

    public long getTableDiskSize() {
        return tableDiskSize;
    }

    public void setTableDiskSize(long tableDiskSize) {
        this.tableDiskSize = tableDiskSize;
    }
}
