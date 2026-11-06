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
package com.seizia.chromite.ext.hana.model.data;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.hana.model.HANAConstants;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.data.DBDFormatSettings;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.data.DBDValueHandlerProvider;
import com.seizia.chromite.model.struct.DBSTypedObject;

public class HANAValueHandlerProvider implements DBDValueHandlerProvider {

    @Nullable
    @Override
    public DBDValueHandler getValueHandler(
        @NotNull DBPDataSource dataSource,
        @NotNull DBDFormatSettings preferences,
        @NotNull DBSTypedObject typedObject
    ) {
        switch (typedObject.getTypeName()) {
            case HANAConstants.DATA_TYPE_NAME_HALF_VECTOR:
                return HANAHalfVectorValueHandler.INSTANCE;
            case HANAConstants.DATA_TYPE_NAME_REAL_VECTOR:
                return HANARealVectorValueHandler.INSTANCE;
            case HANAConstants.DATA_TYPE_NAME_ST_GEOMETRY:
            case HANAConstants.DATA_TYPE_NAME_ST_POINT:
                return HANAGeometryValueHandler.INSTANCE;
            default:
                return null;
        }
    }

}
