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
package com.seizia.chromite.ext.postgresql.model.data;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.data.handlers.JDBCAbstractValueHandler;
import com.seizia.chromite.model.struct.DBSTypedObject;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.sql.Types;

/**
 * A value handler for values of an unknown type. Retrieves/updates value as if it were a string.
 */
public class PostgreUnknownValueHandler extends JDBCAbstractValueHandler {
    public static final PostgreUnknownValueHandler INSTANCE = new PostgreUnknownValueHandler();

    @Nullable
    @Override
    protected Object fetchColumnValue(
        @NotNull DBCSession session,
        @NotNull JDBCResultSet resultSet,
        @NotNull DBSTypedObject type,
        int index
    ) throws DBCException, SQLException {
        return resultSet.getString(index);
    }

    @Override
    protected void bindParameter(
        @NotNull JDBCSession session,
        @NotNull JDBCPreparedStatement statement,
        @NotNull DBSTypedObject paramType,
        int paramIndex,
        Object value
    ) throws DBCException, SQLException {
        if (value == null) {
            statement.setNull(paramIndex, Types.VARCHAR);
        } else {
            statement.setString(paramIndex, CommonUtils.toString(value));
        }
    }

    @NotNull
    @Override
    public Class<?> getValueObjectType(@NotNull DBSTypedObject attribute) {
        return String.class;
    }

    @Nullable
    @Override
    public Object getValueFromObject(
        @NotNull DBCSession session,
        @NotNull DBSTypedObject type,
        @Nullable Object object,
        boolean copy,
        boolean validateValue
    ) throws DBCException {
        return CommonUtils.toString(object);
    }
}
