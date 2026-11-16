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
package com.seizia.chromite.ext.db2.i.model;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.generic.model.GenericSQLDialect;
import com.seizia.chromite.model.exec.jdbc.JDBCDatabaseMetaData;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCDataSource;
import com.seizia.chromite.model.sql.SQLConstants;

public class DB2ISQLDialect extends GenericSQLDialect {
    private static final String[][] BEGIN_END_BLOCK = new String[][]{
        {SQLConstants.BLOCK_BEGIN, SQLConstants.BLOCK_END},
        {SQLConstants.KEYWORD_CASE, SQLConstants.BLOCK_END}
    };

    public DB2ISQLDialect() {
        super("IBM DB2 i", "db2_i");
    }

    @Override
    public boolean supportsOrderByIndex() {
        return false;
    }

    public void initDriverSettings(JDBCSession session, JDBCDataSource dataSource, JDBCDatabaseMetaData metaData) {
        super.initDriverSettings(session, dataSource, metaData);
        turnFunctionIntoKeyword("TRUNCATE");
    }

    @Override
    public String[][] getBlockBoundStrings() {
        return BEGIN_END_BLOCK;
    }

    @Override
    public boolean supportsAliasInSelect() {
        return true;
    }

    @NotNull
    @Override
    public MultiValueInsertMode getDefaultMultiValueInsertMode() {
        return MultiValueInsertMode.GROUP_ROWS;
    }

    @Override
    public boolean supportsAliasInConditions() {
        return false;
    }
}
