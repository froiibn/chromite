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
package com.seizia.chromite.ext.oracle.data;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.oracle.model.OracleConstants;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.data.DBDFormatSettings;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.data.DBDValueHandlerProvider;
import com.seizia.chromite.model.impl.jdbc.data.handlers.JDBCContentValueHandler;
import com.seizia.chromite.model.struct.DBSTypedObject;

import java.sql.Types;

/**
 * Oracle data types provider
 */
public class OracleValueHandlerProvider implements DBDValueHandlerProvider {

    @Override
    public DBDValueHandler getValueHandler(
        @NotNull DBPDataSource dataSource,
        @NotNull DBDFormatSettings preferences,
        @NotNull DBSTypedObject typedObject
    ) {
        switch (typedObject.getTypeID()) {
            case Types.BLOB:
                return OracleBLOBValueHandler.INSTANCE;
            case Types.CLOB:
            case Types.NCLOB:
                return OracleCLOBValueHandler.INSTANCE;
            case Types.TIME_WITH_TIMEZONE:
            case Types.TIMESTAMP_WITH_TIMEZONE:
            case OracleConstants.DATA_TYPE_TIMESTAMP_WITH_TIMEZONE:
                if (((OracleDataSource)dataSource).isDriverVersionAtLeast(12, 2)) {
                    return new OracleTemporalAccessorValueHandler(preferences);
                } else {
                    return new OracleTimestampValueHandler(preferences, dataSource);
                }
            case Types.STRUCT:
                return OracleObjectValueHandler.INSTANCE;
            case OracleConstants.DATA_TYPE_REFCURSOR:
                return OracleRefCursorValueHandler.INSTANCE;
        }

        final String typeName = typedObject.getTypeName();
        switch (typeName) {
            case OracleConstants.TYPE_NAME_XML:
            case OracleConstants.TYPE_FQ_XML:
                return OracleXMLValueHandler.INSTANCE;
            case OracleConstants.TYPE_NAME_BFILE:
                return OracleBFILEValueHandler.INSTANCE;
            case OracleConstants.TYPE_NAME_REFCURSOR:
                return OracleRefCursorValueHandler.INSTANCE;
            case OracleConstants.TYPE_NAME_JSON:
                return JDBCContentValueHandler.INSTANCE;
        }

        if (typeName.contains(OracleConstants.TYPE_NAME_TIMESTAMP) || typedObject.getDataKind() == DBPDataKind.DATETIME) {
            return new OracleTimestampValueHandler(preferences, dataSource);
        } else {
            return null;
        }
    }

}