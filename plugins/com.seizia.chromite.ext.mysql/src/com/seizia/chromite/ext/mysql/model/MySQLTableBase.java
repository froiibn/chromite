/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ext.mysql.model;

import org.jkiss.code.NotNull;
import com.seizia.chromite.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCStructCache;
import com.seizia.chromite.model.impl.jdbc.struct.JDBCTable;
import com.seizia.chromite.model.impl.jdbc.struct.JDBCTableColumn;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBStructUtils;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import org.jkiss.utils.CommonUtils;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MySQLTable base
 */
public abstract class MySQLTableBase extends JDBCTable<MySQLDataSource, MySQLCatalog>
    implements DBPNamedObject2,DBPRefreshableObject, MySQLSourceObject
{
    private static final Log log = Log.getLog(MySQLTableBase.class);

    private boolean isPartition;

    protected MySQLTableBase(MySQLCatalog catalog)
    {
        super(catalog, false);
    }

    // Copy constructor
    protected MySQLTableBase(DBRProgressMonitor monitor, MySQLCatalog catalog, DBSEntity source) throws DBException {
        super(catalog, source, false);

        DBSObjectCache<MySQLTableBase, MySQLTableColumn> colCache = getContainer().getTableCache().getChildrenCache(this);
        // Copy columns
        for (DBSEntityAttribute srcColumn : CommonUtils.safeCollection(source.getAttributes(monitor))) {
            if (DBUtils.isHiddenObject(srcColumn)) {
                continue;
            }
            MySQLTableColumn column = new MySQLTableColumn(monitor, this, srcColumn);
            colCache.cacheObject(column);
        }
    }

    protected MySQLTableBase(
        MySQLCatalog catalog,
        ResultSet dbResult)
    {
        super(catalog, JDBCUtils.safeGetString(dbResult, 1), true);
    }

    public boolean isPartition() {
        return isPartition;
    }

    public void setPartition(boolean partition) {
        isPartition = partition;
    }

    @Override
    public JDBCStructCache<MySQLCatalog, ? extends JDBCTable, ? extends JDBCTableColumn> getCache()
    {
        return getContainer().getTableCache();
    }

    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context)
    {
        return DBUtils.getFullQualifiedName(getDataSource(),
            getContainer(),
            this);
    }

    @Override
    public List<MySQLTableColumn> getAttributes(@NotNull DBRProgressMonitor monitor)
        throws DBException
    {
        List<MySQLTableColumn> childColumns = getContainer().getTableCache().getChildren(monitor, getContainer(), this);
        if (childColumns == null) {
            return Collections.emptyList();
        }
        List<MySQLTableColumn> columns = new ArrayList<>(childColumns);
        columns.sort(DBUtils.orderComparator());
        return columns;
    }

    @Override
    public MySQLTableColumn getAttribute(@NotNull DBRProgressMonitor monitor, @NotNull String attributeName)
        throws DBException
    {
        return getContainer().getTableCache().getChild(monitor, getContainer(), this, attributeName);
    }

    public List<MySQLTableColumn> getCachedAttributes()
    {
        DBSObjectCache<MySQLTableBase, MySQLTableColumn> childrenCache = getContainer().getTableCache().getChildrenCache(this);
        if (childrenCache != null) {
            return childrenCache.getCachedObjects();
        }
        return Collections.emptyList();
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException
    {
        return getContainer().getTableCache().refreshObject(monitor, getContainer(), this);
    }

    public String getDDL(DBRProgressMonitor monitor, Map<String, Object> options)
        throws DBException
    {
        if (!isPersisted()) {
            return DBStructUtils.generateTableDDL(monitor, this, options, false);
        }
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Retrieve table DDL")) {
            try (PreparedStatement dbStat = session.prepareStatement(
                "SHOW CREATE " + (isView() ? "VIEW" : "TABLE") + " " + getFullyQualifiedName(DBPEvaluationContext.DDL))) {
                try (ResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                        if (isView()) {
                            return dbResult.getString("Create View");
                        } else {
                            return dbResult.getString("Create Table");
                        }
                    } else {
                        return "DDL is not available";
                    }
                }
            }
        } catch (SQLException ex) {
            throw new DBDatabaseException(ex, getDataSource());
        }
    }

}
