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
package com.seizia.chromite.ext.oracle.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.access.DBARole;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCObjectCache;
import com.seizia.chromite.model.meta.Association;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;

/**
 * OracleRole
 */
public class OracleRole extends OracleGrantee implements DBARole {
    private static final Log log = Log.getLog(OracleRole.class);

    private String name;
    private String authentication;
    private String authenticationType;
    private final UserCache userCache = new UserCache();

    public OracleRole(@NotNull OracleDataSource dataSource, @Nullable ResultSet resultSet) {
        super(dataSource);
        this.name = JDBCUtils.safeGetString(resultSet, "ROLE");
        this.authentication = JDBCUtils.safeGetStringTrimmed(resultSet, "PASSWORD_REQUIRED");
        this.authenticationType = JDBCUtils.safeGetStringTrimmed(resultSet, "AUTHENTICATION_TYPE");
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 2)
    public String getName() {
        return name;
    }

    @Nullable
    @Property(viewable = true, order = 3)
    public String getAuthentication() {
        return authentication;
    }

    public boolean isDefaultRoleAllowed() {
        return CommonUtils.isEmpty(authenticationType) ? "NO".equals(authentication) : "NONE".equals(authenticationType);
    }

    @NotNull
    @Association
    public Collection<OraclePrivUser> getUserPrivs(@NotNull DBRProgressMonitor monitor) throws DBException {
        return userCache.getAllObjects(monitor, this);
    }

    @Nullable
    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        userCache.clearCache();
        return super.refreshObject(monitor);
    }

    static class UserCache extends JDBCObjectCache<OracleRole, OraclePrivUser> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull OracleRole owner) throws SQLException {
            final JDBCPreparedStatement dbStat = session.prepareStatement(
                    "SELECT * FROM DBA_ROLE_PRIVS WHERE GRANTED_ROLE=? ORDER BY GRANTEE");
            dbStat.setString(1, owner.getName());
            return dbStat;
        }

        @NotNull
        @Override
        protected OraclePrivUser fetchObject(
            @NotNull JDBCSession session, @NotNull OracleRole owner, @NotNull JDBCResultSet resultSet
        ) throws SQLException, DBException {
            return new OraclePrivUser(owner, resultSet);
        }
    }

}
