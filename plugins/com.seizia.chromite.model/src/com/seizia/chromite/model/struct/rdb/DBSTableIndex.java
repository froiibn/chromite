/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.model.struct.rdb;

import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPQualifiedObject;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.DBSEntityReferrer;
import com.seizia.chromite.model.struct.DBSObject;

import java.util.List;

/**
 * DBSTableIndex
 */
public interface DBSTableIndex extends DBSEntityConstraint, DBSEntityReferrer, DBPQualifiedObject
{
    /**
     * Index container. In complex databases it is schema or catalog where index defined.
     * Also the table can be index container.
     * @return container
     */
    DBSObject getContainer();

    DBSEntity getTable();

    boolean isUnique();

    boolean isPrimary();

    DBSIndexType getIndexType();

    @Nullable
    List<? extends DBSTableIndexColumn> getAttributeReferences(@Nullable DBRProgressMonitor monitor)
        throws DBException;

}
