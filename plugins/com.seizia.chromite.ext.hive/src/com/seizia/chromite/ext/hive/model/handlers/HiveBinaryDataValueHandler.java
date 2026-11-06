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
package com.seizia.chromite.ext.hive.model.handlers;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.data.DBDValueBinder;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.data.formatters.BinaryFormatterHex;
import com.seizia.chromite.model.impl.jdbc.data.JDBCContentBytes;
import com.seizia.chromite.model.impl.jdbc.data.handlers.JDBCContentValueHandler;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSTypedObject;

import java.sql.SQLException;

public class HiveBinaryDataValueHandler extends JDBCContentValueHandler implements DBDValueBinder {

    private final BinaryFormatterHex hexFormatter = new BinaryFormatterHex();

    @Override
    protected void bindParameter(
        @NotNull JDBCSession session,
        @NotNull JDBCPreparedStatement statement,
        @NotNull DBSTypedObject paramType,
        int paramIndex,
        @Nullable Object value
    ) throws DBCException, SQLException {
        if (value instanceof JDBCContentBytes jdbcContentBytes && !jdbcContentBytes.isNull()) {
            setBytes(statement, paramIndex, jdbcContentBytes.getRawValue());
        } else {
            super.bindParameter(session, statement, paramType, paramIndex, value);
        }
    }

    @NotNull
    @Override
    public String makeQueryBind(@NotNull DBSAttributeBase attribute, @NotNull Object value) throws DBCException {
        return "unhex(?)";
    }

    private void setBytes(@NotNull JDBCPreparedStatement statement, int paramIndex, @NotNull byte[] data) throws SQLException {
        statement.setString(paramIndex, hexFormatter.toString(data, 0, data.length));
    }
}
