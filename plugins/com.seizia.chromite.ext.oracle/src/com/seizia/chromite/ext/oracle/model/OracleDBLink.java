/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.util.Date;

/**
 * DB Link
 */
public class OracleDBLink extends OracleSchemaObject {

    private static final Log log = Log.getLog(OracleDBLink.class);

    private String userName;
    private String host;
    private Date created;

    protected OracleDBLink(DBRProgressMonitor progressMonitor, OracleSchema schema, ResultSet dbResult)
    {
        super(schema, JDBCUtils.safeGetString(dbResult, "DB_LINK"), true);
        this.userName = JDBCUtils.safeGetString(dbResult, "USERNAME");
        this.host = JDBCUtils.safeGetString(dbResult, "HOST");
        this.created = JDBCUtils.safeGetTimestamp(dbResult, "CREATED");

    }

    @Property(viewable = true, editable = true, order = 2)
    public String getUserName()
    {
        return userName;
    }

    @Property(viewable = true, editable = true, order = 3)
    public String getHost()
    {
        return host;
    }

    @Property(viewable = true, order = 4)
    public Date getCreated()
    {
        return created;
    }

    public static Object resolveObject(DBRProgressMonitor monitor, OracleSchema schema, String dbLink) throws DBException
    {
        if (CommonUtils.isEmpty(dbLink)) {
            return null;
        }
        final OracleDBLink object = schema.dbLinkCache.getObject(monitor, schema, dbLink);
        if (object == null) {
            log.warn("DB Link '" + dbLink + "' not found in schema '" + schema.getName() + "'");
            return dbLink;
        }
        return object;
    }
}
