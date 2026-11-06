/*
 * DBeaver - Universal Database Manager
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
package com.seizia.chromite.ext.altibase.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPSaveableObject;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

public abstract class AltibaseObject<PARENT extends DBSObject> implements DBSObject, DBPSaveableObject {
    
    protected final PARENT parent;
    protected String name;
    private boolean persisted;
    private long objectId;

    protected AltibaseObject(
        PARENT parent,
        String name,
        long objectId,
        boolean persisted) {
        this.parent = parent;
        this.name = CommonUtils.notEmpty(name);
        this.objectId = objectId;
        this.persisted = persisted;
    }

    protected AltibaseObject(
        PARENT parent,
        String name,
        boolean persisted) {
        this.parent = parent;
        this.name = name;
        this.persisted = persisted;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public PARENT getParentObject() {
        return parent;
    }

    @NotNull
    @Override
    public AltibaseDataSource getDataSource() {
        return (AltibaseDataSource) parent.getDataSource();
    }

    @NotNull
    @Override
    @Property(viewable = true, editable = true, order = 1)
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getObjectId() {
        return objectId;
    }

    @Override
    public boolean isPersisted() {
        return persisted;
    }

    @Override
    public void setPersisted(boolean persisted) {
        this.persisted = persisted;
    }
}
