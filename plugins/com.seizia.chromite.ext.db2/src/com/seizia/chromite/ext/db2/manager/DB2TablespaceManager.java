/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2013-2015 Denis Forveille (titou10.titou10@gmail.com)
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
package com.seizia.chromite.ext.db2.manager;

import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.db2.model.DB2DataSource;
import com.seizia.chromite.ext.db2.model.DB2Tablespace;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;

/**
 * DB2 Tablespace Manager
 * 
 * @author Denis Forveille
 */
public class DB2TablespaceManager extends DB2AbstractDropOnlyManager<DB2Tablespace, DB2DataSource> {

    private static final String SQL_DROP = "DROP TABLESPACE %s";

    @Override
    public String buildDropStatement(DB2Tablespace db2Tablespace)
    {
        String name = db2Tablespace.getName();
        return String.format(SQL_DROP, name);
    }

    @Nullable
    @Override
    public DBSObjectCache<DB2DataSource, DB2Tablespace> getObjectsCache(DB2Tablespace db2Tablespace)
    {
        return db2Tablespace.getDataSource().getTablespaceCache();
    }
}
