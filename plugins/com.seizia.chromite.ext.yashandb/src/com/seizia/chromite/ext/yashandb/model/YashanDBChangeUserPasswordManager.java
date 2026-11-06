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
package com.seizia.chromite.ext.yashandb.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.oracle.model.OracleConstants;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.access.DBAUserPasswordManager;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.DBObjectNameCaseTransformer;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.sql.SQLException;

public class YashanDBChangeUserPasswordManager implements DBAUserPasswordManager {

    private final YashanDBDataSource dataSource;

    public YashanDBChangeUserPasswordManager(@NotNull YashanDBDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void changeUserPassword(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String userName,
        @NotNull String newPassword,
        @NotNull String oldPassword
    ) throws DBException {
        if (newPassword.indexOf('"') >= 0) {
            throw new DBCException("YashanDB passwords cannot contain double quotes");
        }
        if (OracleConstants.SCHEMA_SYS.equals(DBUtils.getUnQuotedNormalizedIdentifier(dataSource.getSQLDialect(), userName))) {
            throw new DBCException("The YashanDB SYS password must be changed with the yaspwd tool on the database server");
        }
        dataSource.validateUserPassword(monitor, userName, oldPassword);
        try (JDBCSession session = DBUtils.openMetaSession(monitor, dataSource, "Change user password")) {
            session.enableLogging(false);
            String transformedUserName = DBObjectNameCaseTransformer.transformName(dataSource, userName);
            JDBCUtils.executeSQL(session, "ALTER USER " + DBUtils.getQuotedIdentifier(dataSource, transformedUserName)
                + " IDENTIFIED BY " + DBUtils.getQuotedIdentifier(dataSource, newPassword));
        } catch (SQLException e) {
            throw new DBCException("Error changing user password", e);
        }
    }
}
