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
package com.seizia.chromite.ext.sqlite.model;

import com.seizia.chromite.ext.generic.model.GenericDataSourceInfo;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.exec.jdbc.JDBCDatabaseMetaData;

public class SQLiteDataSourceInfo extends GenericDataSourceInfo {

    private final boolean isRemote;

    public SQLiteDataSourceInfo(DBPDriver driver, JDBCDatabaseMetaData metaData) {
        super(driver, metaData);
        this.isRemote = !driver.isEmbedded();
    }

    @Override
    public boolean supportsNullableUniqueConstraints() {
        return true;
    }

    // In LibSQL we don't have proper resulset metadata
    @Override
    public boolean needsTableMetaForColumnResolution() {
        return !isRemote;
    }
}
