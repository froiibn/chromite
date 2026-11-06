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
package com.seizia.chromite.ext.oracle.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.oracle.model.source.OracleSourceObject;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectState;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;
import com.seizia.chromite.utils.GeneralUtils;

import java.sql.ResultSet;
import java.util.Map;

/**
 * GenericProcedure
 */
public class OracleProcedureStandalone extends OracleProcedureBase<OracleSchema> implements OracleSourceObject, DBPRefreshableObject
{

    private boolean valid;
    protected String sourceDeclaration;

    public OracleProcedureStandalone(
        OracleSchema schema,
        ResultSet dbResult)
    {
        super(
            schema,
            JDBCUtils.safeGetString(dbResult, OracleConstants.COLUMN_OBJECT_NAME),
            JDBCUtils.safeGetLong(dbResult, "OBJECT_ID"),
            DBSProcedureType.valueOf(JDBCUtils.safeGetString(dbResult, OracleConstants.COLUMN_OBJECT_TYPE)));
        this.valid = OracleConstants.RESULT_STATUS_VALID.equals(JDBCUtils.safeGetString(dbResult, OracleConstants.COLUMN_STATUS));
    }

    public OracleProcedureStandalone(OracleSchema oracleSchema, String name, DBSProcedureType procedureType)
    {
        super(oracleSchema, name, 0l, procedureType);
        sourceDeclaration =
            procedureType.name() + " " + name + GeneralUtils.getDefaultLineSeparator() +
            "IS" + GeneralUtils.getDefaultLineSeparator() +
            "BEGIN" + GeneralUtils.getDefaultLineSeparator() +
            "END " + name + ";" + GeneralUtils.getDefaultLineSeparator();
    }

    @Property(viewable = true, order = 3)
    public boolean isValid()
    {
        return valid;
    }

    @NotNull
    @Override
    public OracleSchema getSchema()
    {
        return getParentObject();
    }

    @Override
    public OracleSourceType getSourceType()
    {
        return getProcedureType() == DBSProcedureType.PROCEDURE ?
            OracleSourceType.PROCEDURE :
            OracleSourceType.FUNCTION;
    }

    @Override
    @Nullable
    public Integer getOverloadNumber()
    {
        return null;
    }

    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context)
    {
        return DBUtils.getFullQualifiedName(getDataSource(),
            getSchema(),
            this);
    }

    @NotNull
    @Override
    @Property(hidden = true, editable = true, updatable = true, order = -1)
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBCException
    {
        if (sourceDeclaration == null && monitor != null) {
            sourceDeclaration = OracleUtils.getSource(monitor, this, false, true);
        }
        return sourceDeclaration;
    }

    public void setObjectDefinitionText(String sourceDeclaration)
    {
        this.sourceDeclaration = sourceDeclaration;
    }

    @Override
    public DBEPersistAction[] getCompileActions(DBRProgressMonitor monitor)
    {
        return new DBEPersistAction[] {
            new OracleObjectPersistAction(
                getProcedureType() == DBSProcedureType.PROCEDURE ?
                    OracleObjectType.PROCEDURE : OracleObjectType.FUNCTION,
                "Compile procedure",
                "ALTER " + getSourceType().name() + " " + getFullyQualifiedName(DBPEvaluationContext.DDL) + " COMPILE"
            )};
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState()
    {
        return valid ? DBSObjectState.NORMAL : DBSObjectState.INVALID;
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) throws DBCException
    {
        this.valid = OracleUtils.getObjectStatus(monitor, this,
            getProcedureType() == DBSProcedureType.PROCEDURE ?
                    OracleObjectType.PROCEDURE : OracleObjectType.FUNCTION);
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        return getSchema().getProceduresCache().refreshObject(monitor, getSchema(), this);
    }
}
