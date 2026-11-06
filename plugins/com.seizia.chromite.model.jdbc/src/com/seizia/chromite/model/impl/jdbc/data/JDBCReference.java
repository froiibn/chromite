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
package com.seizia.chromite.model.impl.jdbc.data;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDReference;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSDataType;

import java.sql.Ref;
import java.sql.SQLException;

/**
 * Reference holder
 */
public class JDBCReference implements DBDReference {

    private static final Log log = Log.getLog(JDBCReference.class);

    private DBSDataType type;
    private Object value;
    private Object refObject;

    public JDBCReference(@NotNull DBSDataType type, @Nullable Object value) throws DBCException {
        this.type = type;
        this.value = value;
    }

    @Nullable
    public Object getValue() throws DBCException {
        return value;
    }

    @Nullable
    @Override
    public Object getRawValue() {
        return value;
    }

    @Override
    public boolean isNull() {
        return value == null;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void release() {
        type = null;
        value = null;
    }

    @NotNull
    @Override
    public DBSDataType getReferencedType() {
        return type;
    }

    @Nullable
    @Override
    public Object getReferencedObject(@NotNull DBCSession session) throws DBCException {
        if (refObject == null && value instanceof Ref ref) {
            try {
                DBRProgressMonitor monitor = session.getProgressMonitor();
                monitor.subTask("Read reference '" + type.getName() + "'");
                Object refValue = ref.getObject();
                DBDValueHandler valueHandler = DBUtils.findValueHandler(session, type);
                refObject = valueHandler.getValueFromObject(session, type, refValue, false, false);
            } catch (SQLException e) {
                throw new DBCException("Can't obtain object reference");
            }
        }
        return refObject;
    }

    @Override
    public String toString() {
        try {
            return value == null ? DBConstants.NULL_VALUE_LABEL :
                value instanceof Ref ? ((Ref) value).getBaseTypeName() : value.toString();
        } catch (SQLException e) {
            return value.toString();
        }
    }
}
