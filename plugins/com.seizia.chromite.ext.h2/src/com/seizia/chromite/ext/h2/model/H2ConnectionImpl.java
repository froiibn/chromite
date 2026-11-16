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
package com.seizia.chromite.ext.h2.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.h2.util.H2Utils;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.jdbc.JDBCCallableStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCObjectSupplier;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.exec.JDBCConnectionImpl;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public class H2ConnectionImpl extends JDBCConnectionImpl {
    public H2ConnectionImpl(
        @NotNull JDBCExecutionContext context,
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionPurpose purpose,
        @NotNull String taskTitle
    ) {
        super(context, monitor, purpose, taskTitle);
    }

    @NotNull
    @Override
    protected JDBCStatement createStatementImpl(@NotNull JDBCObjectSupplier<Statement> original) throws SQLException {
        return new H2StatementImpl(this, original, !isLoggingEnabled());
    }

    @NotNull
    @Override
    protected JDBCPreparedStatement createPreparedStatementImpl(
        @NotNull JDBCObjectSupplier<PreparedStatement> statementSupplier,
        @Nullable String sql
    ) throws SQLException {
        validateQuery(sql);
        return super.createPreparedStatementImpl(statementSupplier, sql);
    }

    @NotNull
    @Override
    protected JDBCCallableStatement createCallableStatementImpl(
        @NotNull JDBCObjectSupplier<CallableStatement> statementSupplier,
        @Nullable String sql
    ) throws SQLException {
        validateQuery(sql);
        return super.createCallableStatementImpl(statementSupplier, sql);
    }

    void validateQuery(@Nullable String query) throws SQLException {
        if (query != null &&
            H2Utils.isClassLoadingRestricted() &&
            H2Utils.isJavaSourceDefinition(getOriginal(), query)
        ) {
            throw new SQLException(
                "Java source aliases and triggers are disabled for embedded H2 databases. " +
                "Allow all H2 classes in Preferences > Drivers > H2 and restart Chromite to enable them"
            );
        }
    }
}
