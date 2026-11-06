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
import com.seizia.chromite.model.*;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.jdbc.struct.JDBCTableColumn;
import com.seizia.chromite.model.meta.*;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.*;
import com.seizia.chromite.model.struct.rdb.DBSTableColumn;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OracleTableColumn
 *
 * 설계: the length of VARCHAR2 and CHAR counts bytes or characters (CHAR_USED). Only the number was kept, so the DDL
 * of a character-length column said VARCHAR2(n), which is n bytes with the default NLS_LENGTH_SEMANTICS = BYTE: a
 * copy of the column did not hold n Korean characters (3 bytes each in AL32UTF8), and "VARCHAR2(n CHAR)" given as a
 * new column type (data transfer, table designer) lost its length. {@link #isCharLengthSemantics()} keeps the unit;
 * {@link OracleSQLDialect#getColumnTypeModifiers} writes it into the DDL.
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: keep CHAR length semantics (read from CHAR_USED, parsed from "(n CHAR)" / "(n BYTE)").
 */
public class OracleTableColumn extends JDBCTableColumn<OracleTableBase> implements
    DBSTableColumn, DBSTypedObjectEx, DBSTypedObjectExt3, DBPHiddenObject, DBPNamedObject2, DBSTypedObjectExt4<OracleDataType>, DBPObjectWithLazyDescription, DBSDescriptionEditable
{

    // Length qualifier of VARCHAR2(n CHAR) and CHAR(n CHAR)
    public static final String CHAR_LENGTH_SEMANTICS = "CHAR";
    private static final String BYTE_LENGTH_SEMANTICS = "BYTE";
    // "(100 CHAR)" or "(100 BYTE)" of a full type name
    private static final Pattern LENGTH_SEMANTICS_PATTERN = Pattern.compile(
        "\\(\\s*(\\d+)\\s+(" + CHAR_LENGTH_SEMANTICS + "|" + BYTE_LENGTH_SEMANTICS + ")\\s*\\)", Pattern.CASE_INSENSITIVE);

    private OracleDataType type;
    private OracleDataTypeModifier typeMod;
    private String comment;
    private boolean hidden;
    private boolean charLengthSemantics;

    public OracleTableColumn(OracleTableBase table)
    {
        super(table, false);
    }

    public OracleTableColumn(
        DBRProgressMonitor monitor,
        OracleTableBase table,
        @NotNull ResultSet dbResult)
        throws DBException
    {
        super(table, true);
        // Read default value first because it is of LONG type and has to be read before others
        setDefaultValue(JDBCUtils.safeGetString(dbResult, "DATA_DEFAULT"));

        setName(JDBCUtils.safeGetString(dbResult, "COLUMN_NAME"));
        setOrdinalPosition(JDBCUtils.safeGetInt(dbResult, "COLUMN_ID"));
        this.typeName = JDBCUtils.safeGetString(dbResult, "DATA_TYPE");
        this.type = OracleDataType.resolveDataType(
            monitor,
            getDataSource(),
            JDBCUtils.safeGetString(dbResult, "DATA_TYPE_OWNER"),
            this.typeName);
        this.typeMod = OracleDataTypeModifier.resolveTypeModifier(JDBCUtils.safeGetString(dbResult, "DATA_TYPE_MOD"));
        if (this.type != null) {
            this.typeName = type.getFullyQualifiedName(DBPEvaluationContext.DDL);
            this.valueType = type.getTypeID();
        }
        if (typeMod == OracleDataTypeModifier.REF) {
            this.valueType = Types.REF;
        }
        String charUsed = JDBCUtils.safeGetString(dbResult, "CHAR_USED");
        this.charLengthSemantics = "C".equals(charUsed);
        setMaxLength(JDBCUtils.safeGetLong(dbResult, charLengthSemantics ? "CHAR_LENGTH" : "DATA_LENGTH"));
        setRequired(!OracleConstants.RESULT_YES_VALUE.equals(JDBCUtils.safeGetString(dbResult, "NULLABLE")));
        Integer scale = JDBCUtils.safeGetInteger(dbResult, "DATA_SCALE");
        if (scale == null) {
            // Scale can be null in case when type was declared without parameters (examples: NUMBER, NUMBER(*), FLOAT)
            if (this.type != null && this.type.getScale() != null) {
                scale = this.type.getScale();
            }
        }
        setScale(scale);
        setPrecision(JDBCUtils.safeGetInteger(dbResult, "DATA_PRECISION"));
        this.hidden = JDBCUtils.safeGetBoolean(dbResult, "HIDDEN_COLUMN", OracleConstants.YES);
        if (JDBCUtils.safeGetBoolean(dbResult, "COMMENTS_LOADED")) {
            this.comment = CommonUtils.notEmpty(JDBCUtils.safeGetString(dbResult, "COMMENTS"));
        }
    }

    @NotNull
    @Override
    public OracleDataSource getDataSource()
    {
        return getTable().getDataSource();
    }

    @NotNull
    @Property(viewable = true, editable = true, updatable = true, order = 20, listProvider = ColumnTypeNameListProvider.class)
    @Override
    public String getFullTypeName() {
        return DBUtils.getFullTypeName(this);
    }

    @Override
    public void setFullTypeName(@NotNull String fullTypeName) throws DBException {
        Matcher matcher = LENGTH_SEMANTICS_PATTERN.matcher(fullTypeName);
        if (matcher.find()) {
            // The generic parser reads only numbers, "100 CHAR" became no length
            charLengthSemantics = CHAR_LENGTH_SEMANTICS.equalsIgnoreCase(matcher.group(2));
            fullTypeName = fullTypeName.substring(0, matcher.start()) + "(" + matcher.group(1) + ")" + fullTypeName.substring(matcher.end());
        } else {
            // Without a qualifier Oracle uses NLS_LENGTH_SEMANTICS, BYTE by default
            charLengthSemantics = false;
        }
        super.setFullTypeName(fullTypeName);
    }

    /**
     * True when the length of VARCHAR2/CHAR counts characters instead of bytes (CHAR_USED = 'C').
     */
    public boolean isCharLengthSemantics() {
        return charLengthSemantics;
    }

    @Override
    protected void validateTypeName(String typeName) throws DBException {
        if (getDataSource().resolveDataType(new VoidProgressMonitor(), typeName) == null) {
            throw new DBException("Bad data type name " + typeName);
        }
    }

    @Nullable
    @Override
    //@Property(viewable = false, editableExpr = "!object.table.view", updatableExpr = "!object.table.view", order = 21, listProvider = ColumnDataTypeListProvider.class)
    public OracleDataType getDataType() {
        return type;
    }

    @Override
    public void setDataType(@NotNull OracleDataType type)
    {
        this.type = type;
        this.typeName = type == null ? "" : type.getFullyQualifiedName(DBPEvaluationContext.DDL);
    }

    @Property(viewable = true, order = 30)
    public OracleDataTypeModifier getTypeMod()
    {
        return typeMod;
    }

    //@Property(name = "Data Type", viewable = true, editable = true, updatable = true, order = 20, listProvider = ColumnTypeNameListProvider.class)
    @NotNull
    @Override
    public String getTypeName()
    {
        return super.getTypeName();
    }

    @Property(viewable = false, editableExpr = "!object.table.view", updatableExpr = "!object.table.view", order = 40)
    @Override
    public long getMaxLength()
    {
        return super.getMaxLength();
    }

    @Override
    @Property(viewable = false, editableExpr = "!object.table.view", updatableExpr = "!object.table.view", order = 41)
    public Integer getPrecision()
    {
        return super.getPrecision();
    }

    @Nullable
    @Override
    @Property(viewable = false, editableExpr = "!object.table.view", updatableExpr = "!object.table.view", order = 42)
    public Integer getScale()
    {
        return super.getScale();
    }

    @Property(viewable = true, editableExpr = "!object.table.view", updatableExpr = "!object.table.view", order = 50)
    @Override
    public boolean isRequired()
    {
        return super.isRequired();
    }

    @Property(viewable = true, editableExpr = "!object.table.view", updatableExpr = "!object.table.view", order = 70)
    @Override
    public String getDefaultValue()
    {
        return super.getDefaultValue();
    }

    @Override
    public boolean isAutoGenerated()
    {
        return false;
    }

    public static class CommentLoadValidator implements IPropertyCacheValidator<OracleTableColumn> {
        @Override
        public boolean isPropertyCached(@NotNull OracleTableColumn object, @NotNull Object propertyId)
        {
            return object.comment != null;
        }
    }

    @Nullable
    @Override
    public String getDescription(@NotNull DBRProgressMonitor monitor) {
        return getComment(monitor);
    }

    @Property(viewable = true, editable = true, updatable = true, length = PropertyLength.MULTILINE, order = 100)
    @LazyProperty(cacheValidator = CommentLoadValidator.class)
    public String getComment(DBRProgressMonitor monitor) {
        if (isPersisted() && comment == null && !DBWorkbench.getPlatform().isUnitTestMode()) {
            // Load comments for all table columns
            getTable().loadColumnComments(monitor);
        }
        return comment;
    }

    @Override
    public void setDescription(@Nullable String description) {
        setComment(description);
    }

    public void setComment(String comment)
    {
        this.comment = comment;
    }

    void cacheComment() {
        if (this.comment == null) {
            this.comment = "";
        }
    }

    @Nullable
    @Override
    public String getDescription() {
        return comment;
    }

    @Override
    public boolean isHidden()
    {
        return hidden;
    }

    public static class ColumnDataTypeListProvider implements IPropertyValueListProvider<OracleTableColumn> {

        @Override
        public boolean allowCustomValue()
        {
            return false;
        }

        @Nullable
        @Override
        public Object[] getPossibleValues(OracleTableColumn column)
        {
            List<DBSDataType> dataTypes = new ArrayList<>(column.getTable().getDataSource().getLocalDataTypes());
            if (!dataTypes.contains(column.getDataType())) {
                dataTypes.add(column.getDataType());
            }
            dataTypes.sort(DBUtils.nameComparator());
            return dataTypes.toArray(new DBSDataType[0]);
        }
    }

}
