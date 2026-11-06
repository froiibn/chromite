/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2013-2015 Denis Forveille (titou10.titou10@gmail.com)
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
package com.seizia.chromite.ext.db2.model.cache;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.db2.model.DB2Tablespace;
import com.seizia.chromite.ext.db2.model.DB2TablespaceContainer;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCObjectCache;

import java.sql.SQLException;

/**
 * Cache for DB2 Tablespaces Containers
 * 
 * @author Denis Forveille
 */
public class DB2TablespaceContainerCache extends JDBCObjectCache<DB2Tablespace, DB2TablespaceContainer> {

    private static final String SQL = "SELECT T.* FROM TABLE(SNAP_GET_CONTAINER('',-1)) AS T WHERE T.TBSP_ID= ? order by T.CONTAINER_ID WITH UR";

    @NotNull
    @Override
    protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, @NotNull DB2Tablespace ts) throws SQLException
    {
        final JDBCPreparedStatement dbStat = session.prepareStatement(SQL);
        dbStat.setInt(1, ts.getTbspaceId());
        return dbStat;
    }

    @Override
    protected DB2TablespaceContainer fetchObject(@NotNull JDBCSession session, @NotNull DB2Tablespace ts, @NotNull JDBCResultSet resultSet)
        throws SQLException, DBException
    {
        return new DB2TablespaceContainer(ts, resultSet);
    }
}