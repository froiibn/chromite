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
package com.seizia.chromite.ext.mssql.model.data;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mssql.SQLServerConstants;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.data.DBDFormatSettings;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.impl.jdbc.data.handlers.JDBCStandardValueHandlerProvider;
import com.seizia.chromite.model.struct.DBSTypedObject;

public class SQLServerStandardValueHandlerProvider extends JDBCStandardValueHandlerProvider {

    @Nullable
    @Override
    public DBDValueHandler getValueHandler(
        @NotNull DBPDataSource dataSource,
        @NotNull DBDFormatSettings preferences,
        @NotNull DBSTypedObject typedObject
    ) {
        String typeName = typedObject.getTypeName();
        if (typeName.equals(SQLServerConstants.TYPE_MONEY) || typeName.equals(SQLServerConstants.TYPE_SMALLMONEY)) {
            return SQLServerMoneyValueHandler.INSTANCE;
        } else if (typeName.equals(SQLServerConstants.TYPE_SQL_VARIANT)) {
            return SQLServerSQLVariantValueHandler.INSTANCE;
        } else if (typeName.equals(SQLServerConstants.TYPE_DATETIMEOFFSET)) {
            return new SQLServerDateTimeOffsetHandler(preferences);
        } else {
            return null;
        }
    }
}
