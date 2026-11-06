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

package com.seizia.chromite.model.struct;

import org.jkiss.code.NotNullWhen;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPNamedObject;
import com.seizia.chromite.model.DBPObjectWithDescription;
import com.seizia.chromite.model.DBPPersistedObject;

/**
 * Meta object
 */
public interface DBSObject extends DBPNamedObject, DBPObjectWithDescription, DBPPersistedObject {

    /**
     * Parent object
     *
     * @return parent object or null
     */
    @Nullable
    DBSObject getParentObject();

    /**
     * Datasource which this object belongs.
     * It can be null if object was detached from data source.
     * @return datasource reference or null
     */
    @NotNullWhen("!(this instanceof DBPDataSourceContainer)")
    DBPDataSource getDataSource();

}
