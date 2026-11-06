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
package com.seizia.chromite.ext.mysql.data;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mysql.MySQLConstants;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.data.DBDFormatSettings;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.data.DBDValueHandlerProvider;
import com.seizia.chromite.model.impl.jdbc.data.handlers.JDBCObjectValueHandler;
import com.seizia.chromite.model.struct.DBSTypedObject;

import java.util.Locale;

/**
 * MySQL data types provider
 */
public class MySQLValueHandlerProvider implements DBDValueHandlerProvider {

    @Nullable
    @Override
    public DBDValueHandler getValueHandler(
        @NotNull DBPDataSource dataSource,
        @NotNull DBDFormatSettings preferences,
        @NotNull DBSTypedObject typedObject
    ) {
        DBPDataKind dataKind = typedObject.getDataKind();
        if (dataKind == DBPDataKind.DATETIME) {
            return new MySQLDateTimeValueHandler(preferences);
        } else if (dataKind == DBPDataKind.NUMERIC) {
            return new MySQLNumberValueHandler(typedObject, preferences);
        }

        String typeName = typedObject.getTypeName().toLowerCase(Locale.ENGLISH);
        switch (typeName) {
            case MySQLConstants.TYPE_JSON:
                return MySQLJSONValueHandler.INSTANCE;
            case MySQLConstants.TYPE_GEOMETRY:
            case MySQLConstants.TYPE_POINT:
            case MySQLConstants.TYPE_LINESTRING:
            case MySQLConstants.TYPE_POLYGON:
            case MySQLConstants.TYPE_MULTIPOINT:
            case MySQLConstants.TYPE_MULTILINESTRING:
            case MySQLConstants.TYPE_MULTIPOLYGON:
            case MySQLConstants.TYPE_GEOMETRYCOLLECTION:
            case MySQLConstants.TYPE_GEOGRAPHY:
            case MySQLConstants.TYPE_GEOGRAPHYPOINT:
                return MySQLGeometryValueHandler.INSTANCE;
            case MySQLConstants.TYPE_ENUM:
            case MySQLConstants.TYPE_SET:
                return JDBCObjectValueHandler.INSTANCE;
            case MySQLConstants.TYPE_UUID:
                return MySQLUUIDValueHandlerProvider.INSTANCE;
        }

        return null;
    }

}