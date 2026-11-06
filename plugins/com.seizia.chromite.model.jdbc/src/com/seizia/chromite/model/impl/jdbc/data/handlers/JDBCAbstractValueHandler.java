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
package com.seizia.chromite.model.impl.jdbc.data.handlers;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCResultSet;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.data.BaseValueHandler;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.struct.DBSTypedObject;

import java.sql.SQLException;

/**
 * Base JDBC value handler
 */
public abstract class JDBCAbstractValueHandler extends BaseValueHandler {

    @Override
    public final Object fetchValueObject(
        @NotNull DBCSession session,
        @NotNull DBCResultSet resultSet,
        @NotNull DBSTypedObject type,
        int index
    ) throws DBCException {
        try {
            if (resultSet instanceof JDBCResultSet) {
                // JDBC uses 1-based indexes
                return fetchColumnValue(session, (JDBCResultSet) resultSet, type, index + 1);
            } else {
                return resultSet.getAttributeValue(index);
            }
        }
        catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        }
    }

    @Override
    public final void bindValueObject(
        @NotNull DBCSession session,
        @NotNull DBCStatement statement,
        @NotNull DBSTypedObject columnMetaData,
        int index,
        @Nullable Object value
    ) throws DBCException {
        try {
            // JDBC uses 1-based indexes
            this.bindParameter((JDBCSession) session, (JDBCPreparedStatement) statement, columnMetaData, index + 1, value);
        }
        catch (SQLException e) {
            throw new DBCException(ModelMessages.model_jdbc_exception_could_not_bind_statement_parameter, e);
        }
    }

    @Nullable
    protected abstract Object fetchColumnValue(
        @NotNull DBCSession session,
        @NotNull JDBCResultSet resultSet,
        @NotNull DBSTypedObject type,
        int index
    ) throws DBCException, SQLException;

    /**
     * Binds parameter value
     * @param session       session
     * @param statement     statement
     * @param paramType     parameter type
     * @param paramIndex    parameter index (1-based)
     * @param value         parameter value
     */
    protected abstract void bindParameter(
        @NotNull JDBCSession session,
        @NotNull JDBCPreparedStatement statement,
        @NotNull DBSTypedObject paramType,
        int paramIndex,
        @Nullable Object value
    ) throws DBCException, SQLException;

}