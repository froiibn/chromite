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
package com.seizia.chromite.ext.cubrid.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericSynonym;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.PropertyLength;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;

public class CubridSynonym extends GenericSynonym
{
    private String owner;
    private String targetName;
    private String targetOwner;
    private String description;

    public CubridSynonym(
            @NotNull GenericStructContainer container,
            @NotNull String name,
            @Nullable String description,
            @NotNull JDBCResultSet dbResult) {
        super(container, name, description);
        this.description = description;
        this.owner = JDBCUtils.safeGetString(dbResult, "synonym_owner_name");
        this.targetName = JDBCUtils.safeGetString(dbResult, "target_name");
        this.targetOwner = JDBCUtils.safeGetString(dbResult, "target_owner_name");
    }

    public CubridSynonym(
            @NotNull GenericStructContainer container,
            @NotNull String name) {
        super(container, name, null);
        this.owner = container.getName();
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public CubridUser getOwner() {
        return new CubridUser(getDataSource(), owner, null);
    }

    @Override
    public DBSObject getTargetObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        return getDataSource().findTable(monitor, null, targetOwner, targetName);
    }

    @Property(viewable = true, editable = true, updatable = true, order = 4)
    public String getTargetObject() {
        return targetName;
    }

    public void setTargetObject(String targetObject) {
        this.targetName = targetObject;
    }

    @Nullable
    @Override
    @Property(viewable = true, editable = true, updatable = true, length = PropertyLength.MULTILINE, order = 10)
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context) {
        return DBUtils.getQuotedIdentifier(this.getOwner()) + "." + DBUtils.getFullQualifiedName(getDataSource(), this);
    }

}
