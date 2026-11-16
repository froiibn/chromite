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
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.data.JDBCContentChars;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.utils.MimeTypes;

import java.sql.SQLException;
import java.sql.Types;

/**
 * JSON content
 */
public class PostgreContentJSON extends JDBCContentChars {

    public PostgreContentJSON(DBCExecutionContext executionContext, String json)
    {
        super(executionContext, json);
    }

    private PostgreContentJSON(PostgreContentJSON copyFrom) {
        super(copyFrom);
    }

    @NotNull
    @Override
    public String getContentType()
    {
        return MimeTypes.TEXT_JSON;
    }

    @Override
    public void bindParameter(
        JDBCSession session,
        JDBCPreparedStatement preparedStatement,
        DBSTypedObject columnType,
        int paramIndex)
        throws DBCException
    {
        try {
            if (data != null) {
                preparedStatement.setObject(paramIndex, data, Types.OTHER);
            } else {
                preparedStatement.setNull(paramIndex, columnType.getTypeID());
            }
        }
        catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        }
    }


    @NotNull
    @Override
    public PostgreContentJSON cloneValue(@NotNull DBRProgressMonitor monitor)
    {
        return new PostgreContentJSON(this);
    }

}
