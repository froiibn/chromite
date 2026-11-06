/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
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
package org.jkiss.dbeaver.ext.oracle.data;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.data.DBDFormatSettings;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.impl.jdbc.data.handlers.JDBCNumberValueHandler;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;

import java.sql.SQLException;

/**
 * BINARY_FLOAT and BINARY_DOUBLE values.
 *
 * 설계: the Oracle driver sends setDouble/setFloat values as NUMBER, whose exponent ends at 125, so IEEE values that
 * BINARY_DOUBLE holds (1.2e300, 2.5e-300 of PostgreSQL float8 or SQL Server float) failed with "Overflow" or
 * "Underflow". Binding with the driver's BINARY_DOUBLE/BINARY_FLOAT type codes sends the IEEE value itself.
 * Values that are not numbers (text typed into the grid) keep the generic binding.
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: created (Copy Tables into Oracle BINARY_DOUBLE columns).
 */
public class OracleBinaryFloatValueHandler extends JDBCNumberValueHandler {

    public static final String TYPE_NAME_BINARY_FLOAT = "BINARY_FLOAT";
    public static final String TYPE_NAME_BINARY_DOUBLE = "BINARY_DOUBLE";

    // oracle.jdbc.OracleTypes.BINARY_FLOAT and BINARY_DOUBLE (the bundle does not link the driver)
    private static final int ORACLE_TYPE_BINARY_FLOAT = 100;
    private static final int ORACLE_TYPE_BINARY_DOUBLE = 101;

    public OracleBinaryFloatValueHandler(@NotNull DBSTypedObject type, @NotNull DBDFormatSettings formatSettings) {
        super(type, formatSettings);
    }

    @Override
    protected void bindParameter(
        @NotNull JDBCSession session,
        @NotNull JDBCPreparedStatement statement,
        @NotNull DBSTypedObject paramType,
        int paramIndex,
        Object value
    ) throws SQLException, DBCException {
        if (value instanceof Number number) {
            if (TYPE_NAME_BINARY_FLOAT.equals(paramType.getTypeName())) {
                statement.setObject(paramIndex, number.floatValue(), ORACLE_TYPE_BINARY_FLOAT);
            } else {
                statement.setObject(paramIndex, number.doubleValue(), ORACLE_TYPE_BINARY_DOUBLE);
            }
        } else {
            super.bindParameter(session, statement, paramType, paramIndex, value);
        }
    }
}
