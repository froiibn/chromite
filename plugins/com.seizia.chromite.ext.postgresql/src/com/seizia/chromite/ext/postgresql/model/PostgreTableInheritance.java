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
package com.seizia.chromite.ext.postgresql.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.exec.DBCFeatureNotSupportedException;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAssociation;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;

import java.util.List;

/**
 * PostgreTableInheritance
 */
public class PostgreTableInheritance extends PostgreTableConstraintBase<PostgreTableConstraintColumn> implements DBSEntityAssociation
{
    private final PostgreTableBase superTable;
    private int sequenceNum;

    public PostgreTableInheritance(
        @NotNull PostgreTableBase table,
        @NotNull PostgreTableBase superTable,
        int sequenceNum,
        boolean persisted)
    {
        super(table,
            table.getFullyQualifiedName(DBPEvaluationContext.DDL) + "->" + superTable.getFullyQualifiedName(DBPEvaluationContext.DDL),
            DBSEntityConstraintType.INHERITANCE);
        this.setPersisted(persisted);
        this.superTable = superTable;
        this.sequenceNum = sequenceNum;
    }

    @Override
    public boolean isInherited() {
        // Inheritance itself can't be inherited
        return false;
    }

    @Nullable
    @Override
    public DBSEntityConstraint getReferencedConstraint() {
        return this;
    }

    @Nullable
    @Override
    @Property(viewable = true)
    public PostgreTableBase getAssociatedEntity() {
        return this.superTable;
    }

    @Property(viewable = true)
    public int getSequenceNum() {
        return sequenceNum;
    }

    @Nullable
    @Override
    public List<PostgreTableConstraintColumn> getAttributeReferences(@Nullable DBRProgressMonitor monitor) throws DBException {
        return null;
    }

    @Override
    public void setAttributeReferences(List<PostgreTableConstraintColumn> postgreTableConstraintColumns) throws DBException {
        throw new DBCFeatureNotSupportedException();
    }

    @Override
    void cacheAttributes(DBRProgressMonitor monitor, List<? extends PostgreTableConstraintColumn> children, boolean secondPass) {

    }

}
