/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.model.impl.jdbc.exec;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * ResultSet container.
 * May be used as "fake" statement to wrap result sets returned by connection metadata or something.
 */
public class JDBCFakeStatementImpl extends JDBCPreparedStatementImpl<PreparedStatement> {

    private JDBCResultSetImpl resultSet;
    private boolean closed;

    public JDBCFakeStatementImpl(
        @NotNull JDBCSession connection,
        @Nullable String queryText,
        boolean disableLogging
    ) throws SQLException {
        super(connection, () -> JDBCVoidStatementImpl.INSTANCE, queryText, disableLogging);
    }

    @Override
    public boolean execute() throws SQLException {
        return false;
    }

    @Override
    public boolean executeStatement() throws DBCException {
        return false;
    }

    @Override
    public int executeUpdate() throws SQLException {
        return 0;
    }

    @Override
    public JDBCResultSet executeQuery() {
        return resultSet;
    }

    @Override
    public JDBCResultSet getResultSet() {
        return resultSet;
    }

    @Override
    public void close() {
        // Fake statements can be closed twice (explicitly and by owner resultset close)
        // So do real close only once
        if (!closed) {
            super.close();
            closed = true;
        }
    }

    public void setResultSet(JDBCResultSetImpl resultSet) {
        this.resultSet = resultSet;
    }
}