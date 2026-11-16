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

package com.seizia.chromite.ext.dameng.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.dameng.DamengConstants;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.access.DBAPrivilege;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;

/**
 * @author Shengkai Bai
 */
public class DamengPrivUser implements DBAPrivilege {

    private DamengRole damengRole;

    private long userId;

    private String name;

    public DamengPrivUser(DamengRole damengRole, JDBCResultSet dbResult) {
        this.damengRole = damengRole;
        this.name = JDBCUtils.safeGetString(dbResult, DamengConstants.NAME);
        this.userId = JDBCUtils.safeGetLong(dbResult, DamengConstants.ID);
    }

    @NotNull
    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @Override
    public DBSObject getParentObject() {
        return damengRole;
    }

    @Override
    public DBPDataSource getDataSource() {
        return damengRole.getDataSource();
    }

    @Property(viewable = true)
    public DamengUser getUser(DBRProgressMonitor monitor) throws DBException {
        return ((DamengDataSource) getDataSource()).getUserById(monitor, userId);
    }
}
