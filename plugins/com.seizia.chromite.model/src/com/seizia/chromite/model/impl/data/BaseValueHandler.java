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
package com.seizia.chromite.model.impl.data;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.DBValueFormatting;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.data.DBDValue;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCLogicalOperator;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.utils.MimeTypes;

/**
 * Base value handler
 */
public abstract class BaseValueHandler implements DBDValueHandler {

    @NotNull
    @Override
    public String getValueContentType(@NotNull DBSTypedObject attribute) {
        return MimeTypes.TEXT_PLAIN;
    }

    @Override
    public Object createNewValueObject(@NotNull DBCSession session, @NotNull DBSTypedObject type) throws DBCException {
        return getValueFromObject(session, type, null, false, false);
    }

    @Override
    public void releaseValueObject(Object value)
    {
        if (value instanceof DBDValue) {
            ((DBDValue)value).release();
        }
    }

    @NotNull
    @Override
    public String getValueDisplayString(@NotNull DBSTypedObject column, Object value, @NotNull DBDDisplayFormat format) {
        return DBValueFormatting.getDefaultValueDisplayString(value, format);
    }


    @NotNull
    @Override
    public DBCLogicalOperator[] getSupportedOperators(@NotNull DBSTypedObject attribute) {
        return DBUtils.getAttributeOperators(attribute);
    }

}