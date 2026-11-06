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
package org.jkiss.dbeaver.ext.mssql.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.mssql.SQLServerConstants;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPDataTypeProvider;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.sql.SQLDialectFeatures;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;
import org.jkiss.dbeaver.model.struct.DBStructUtils;
import org.jkiss.utils.CommonUtils;

import java.sql.Types;
import java.util.Locale;
import java.util.Set;

/**
 * SQL Server dialect
 *
 * 설계: {@link #convertExternalDataType} maps types of other databases for data transfer into new tables.
 * Character data of other databases is Unicode (PostgreSQL, MySQL utf8mb4, Oracle AL32UTF8) while varchar, char and
 * text follow the code page of the column collation (SQL_Latin1_General_CP1_CI_AS by default), so Korean text turned
 * into '?'. Character types therefore become nvarchar/nchar, as the SQL Server Import and Export Wizard does.
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: data transfer type mapping — Unicode character types, uuid, JSON and LOBs, timestamps
 *   (datetime2/datetimeoffset instead of datetime), signed tinyint.
 */
public class SQLServerDialectMssql extends SQLServerDialectBase {

    // Longest nvarchar(n)/nchar(n); longer or unbounded character data needs nvarchar(max)
    private static final int MAX_NCHAR_LENGTH = 4000;
    private static final String MAX_LENGTH_MODIFIER = "(MAX)";

    public SQLServerDialectMssql() {
        super("SQLServer", "sqlserver");
    }

    @NotNull
    @Override
    public SQLDialectFeatures getDialectFeatures() {
        return DialectFeatures.INSTANCE;
    }

    @Nullable
    @Override
    public String convertExternalDataType(
        @NotNull SQLDialect sourceDialect,
        @NotNull DBSTypedObject sourceTypedObject,
        @Nullable DBPDataTypeProvider targetTypeProvider
    ) {
        if (sourceDialect instanceof SQLServerDialectBase) {
            // Types of SQL Server (and Sybase) keep their own names and code pages
            return super.convertExternalDataType(sourceDialect, sourceTypedObject, targetTypeProvider);
        }
        String typeName = SQLUtils.stripColumnTypeModifiers(CommonUtils.notEmpty(sourceTypedObject.getTypeName())).toLowerCase(Locale.ENGLISH);
        int typeID = sourceTypedObject.getTypeID();
        switch (typeName) {
            case "uuid":
            case SQLServerConstants.TYPE_UNIQUEIDENTIFIER:
                return SQLServerConstants.TYPE_UNIQUEIDENTIFIER;
            case "json":
            case "jsonb":
            case "clob":
            case "nclob":
                // JSON text and character LOBs are CONTENT, which the generic search maps to varbinary
                return SQLServerConstants.TYPE_NVARCHAR + MAX_LENGTH_MODIFIER;
            case "blob":
                // Oracle reports the LOB locator length (4000) as the BLOB length
                return SQLServerConstants.TYPE_VARBINARY + MAX_LENGTH_MODIFIER;
        }
        if (typeID == Types.CLOB || typeID == Types.NCLOB) {
            return SQLServerConstants.TYPE_NVARCHAR + MAX_LENGTH_MODIFIER;
        }
        if (typeID == Types.BLOB) {
            return SQLServerConstants.TYPE_VARBINARY + MAX_LENGTH_MODIFIER;
        }
        if (DBStructUtils.isTimestamp(sourceTypedObject)) {
            // datetime has 1/300 s precision and starts in 1753; datetime2 holds every timestamp value
            return DBStructUtils.isTimestampWithTimeZone(sourceTypedObject) ?
                SQLServerConstants.TYPE_DATETIMEOFFSET : SQLServerConstants.TYPE_DATETIME2;
        }
        if (typeID == Types.TINYINT && !CommonUtils.notEmpty(sourceTypedObject.getFullTypeName()).toLowerCase(Locale.ENGLISH).contains("unsigned")) {
            // SQL Server tinyint is unsigned (0..255), a signed TINYINT of other databases goes down to -128
            return SQLServerConstants.TYPE_SMALLINT;
        }
        if (sourceTypedObject.getDataKind() == DBPDataKind.STRING) {
            boolean fixedLength = typeID == Types.CHAR || typeID == Types.NCHAR
                || typeName.equals(SQLServerConstants.TYPE_CHAR) || typeName.equals(SQLServerConstants.TYPE_NCHAR)
                || typeName.equals("bpchar") || typeName.equals("character");
            long maxLength = sourceTypedObject.getMaxLength();
            if (maxLength > 0 && maxLength <= MAX_NCHAR_LENGTH) {
                return (fixedLength ? SQLServerConstants.TYPE_NCHAR : SQLServerConstants.TYPE_NVARCHAR) + "(" + maxLength + ")";
            }
            return SQLServerConstants.TYPE_NVARCHAR + MAX_LENGTH_MODIFIER;
        }
        return super.convertExternalDataType(sourceDialect, sourceTypedObject, targetTypeProvider);
    }

    private static class DialectFeatures extends SQLServerDialectBase.DialectFeatures {
        private static final DialectFeatures INSTANCE = new DialectFeatures();

        @NotNull
        @Override
        public Set<String> getSignatureModifierKeywords() {
            return Set.of("OUT", "OUTPUT", "READONLY");
        }

        @NotNull
        @Override
        public ProceduralSyntaxFamily getProceduralSyntaxFamily() {
            return ProceduralSyntaxFamily.TSQL;
        }

        /** T-SQL variables declared inside a procedure remain visible across nested BEGIN/END blocks.
         *
         * @see <a href="https://learn.microsoft.com/en-us/sql/t-sql/language-elements/declare-local-variable-transact-sql">DECLARE</a>
         */
        @NotNull
        @Override
        public LocalVariableVisibility getLocalVariableVisibility(@NotNull LocalVariableKind kind) {
            return LocalVariableVisibility.PROGRAM;
        }
    }

}
