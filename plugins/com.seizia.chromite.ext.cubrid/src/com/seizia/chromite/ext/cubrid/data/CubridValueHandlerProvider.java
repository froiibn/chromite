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
package com.seizia.chromite.ext.cubrid.data;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.cubrid.CubridConstants;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.data.DBDFormatSettings;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.data.DBDValueHandlerProvider;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCResultSet;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.impl.data.DefaultValueHandler;
import com.seizia.chromite.model.impl.jdbc.exec.JDBCColumnMetaData;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.BeanUtils;

public class CubridValueHandlerProvider implements DBDValueHandlerProvider {

    @Override
    public DBDValueHandler getValueHandler(
        @NotNull DBPDataSource dataSource,
        @NotNull DBDFormatSettings preferences,
        @NotNull DBSTypedObject typedObject
    ) {
        boolean isEnableOID = DBWorkbench.getPlatform().getPreferenceStore().getBoolean(CubridConstants.OID_NAVIGATOR);
        if (typedObject instanceof JDBCColumnMetaData columnMeta) {
            String columnName = columnMeta.getName();
            String tableName = columnMeta.getEntityName();
            String typeName = typedObject.getTypeName();
            if (isEnableOID && CubridConstants.CLASS.equals(typeName) && columnName.equals(tableName)) {
                return new CubridOIDValueHandler();
            }
        }
        return null;
    }

    class CubridOIDValueHandler extends DefaultValueHandler {
        @Override
        public Object fetchValueObject(@NotNull DBCSession session, @NotNull DBCResultSet resultSet, @NotNull DBSTypedObject type, int index) throws DBCException {
            String oidValue = null;
            Object originalValue = resultSet.getAttributeValue(index);
            try {
                oidValue = (String) BeanUtils.invokeObjectMethod(originalValue, "getOidString");
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return oidValue;
        }
    }
}
