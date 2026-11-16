/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2013-2015 Denis Forveille (titou10.titou10@gmail.com)
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ext.db2.model.security;

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.db2.DB2Constants;
import com.seizia.chromite.ext.db2.model.DB2Schema;
import com.seizia.chromite.ext.db2.model.DB2Tablespace;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;

/**
 * DB2 Authorisations on Tablespaces
 * 
 * @author Denis Forveille
 */
public class DB2AuthTablespace extends DB2AuthBase {

    private DB2AuthHeldType usage;

    // -----------------------
    // Constructors
    // -----------------------
    public DB2AuthTablespace(DBRProgressMonitor monitor, DB2Grantee db2Grantee, DB2Tablespace db2Tablespace, ResultSet resultSet)
        throws DBException
    {
        super(monitor, db2Grantee, db2Tablespace, resultSet);

        this.usage = CommonUtils.valueOf(DB2AuthHeldType.class, JDBCUtils.safeGetString(resultSet, "USAGEAUTH"));
    }

    // -----------------
    // Properties
    // -----------------
    @Property(viewable = true, order = 2)
    public DBSObject getObject()
    {
        return super.getObject();
    }

    @Override
    @Property(hidden = true)
    public DB2Schema getObjectSchema()
    {
        return super.getObjectSchema();
    }

    @Property(viewable = true, order = 20, category = DB2Constants.CAT_AUTH)
    public DB2AuthHeldType getUsage()
    {
        return usage;
    }

}
