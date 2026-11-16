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
package com.seizia.chromite.ext.mssql.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.mssql.SQLServerConstants;
import com.seizia.chromite.ext.mssql.SQLServerMessages;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.access.DBAUserPasswordManager;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;

public class SQLServerLoginPasswordManager implements DBAUserPasswordManager {

    private final SQLServerDataSource dataSource;

    SQLServerLoginPasswordManager(SQLServerDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void changeUserPassword(@NotNull DBRProgressMonitor monitor, @NotNull String loginName, @NotNull String newPassword, @NotNull String oldPassword) throws DBException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, dataSource, "Change user login password")) {
            session.enableLogging(false);
            JDBCUtils.executeSQL(session, "ALTER LOGIN " + DBUtils.getQuotedIdentifier(dataSource, loginName) + " WITH PASSWORD =" + SQLUtils.quoteString(dataSource, CommonUtils.notEmpty(newPassword)) +
                " OLD_PASSWORD =" + SQLUtils.quoteString(dataSource, CommonUtils.notEmpty(oldPassword)));
        } catch (SQLException e) {
            throw new DBCException(getPasswordPolicyErrorMessage(e), e);
        }
    }

    @NotNull
    static String getPasswordPolicyErrorMessage(@NotNull SQLException e) {
        String detail = switch (e.getErrorCode()) {
            case SQLServerConstants.EC_PASSWORD_TOO_SHORT -> ": password is too short";
            case SQLServerConstants.EC_PASSWORD_TOO_LONG -> ": password is too long";
            case SQLServerConstants.EC_PASSWORD_NOT_COMPLEX -> ": password is not complex enough";
            case SQLServerConstants.EC_PASSWORD_NOT_SATISFACTORY -> ": password does not meet policy requirements";
            case SQLServerConstants.EC_PASSWORD_RECENTLY_USED -> ": password was recently used";
            case SQLServerConstants.EC_PASSWORD_FILTER_REJECTED -> ": password was rejected by a password filter";
            default -> "";
        };
        return SQLServerMessages.password_change_error_message + detail;
    }
}
