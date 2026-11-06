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
package com.seizia.chromite.ext.mysql.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mysql.MySQLConstants;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataTypeProvider;
import com.seizia.chromite.model.DBPIdentifierCase;
import com.seizia.chromite.model.exec.jdbc.JDBCDatabaseMetaData;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCDataSource;
import com.seizia.chromite.model.impl.jdbc.JDBCSQLDialect;
import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.sql.SQLDialectFeatures;
import com.seizia.chromite.model.sql.*;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.model.struct.DBStructUtils;
import com.seizia.chromite.model.struct.rdb.DBSProcedure;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.sql.Types;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL dialect
 *
 * 설계: {@link #convertExternalDataType} maps types of other databases for data transfer into new tables where the
 * generic name search picks a MySQL type that is narrower or needs a length: TIMESTAMP only covers 1970..2038, TEXT
 * holds 64 KB, VARBINARY/BINARY need a length, and JSON or character LOBs of other databases are CONTENT, which the
 * generic search maps to LONGBLOB.
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: data transfer type mapping — timestamps, uuid, JSON, unbounded text and binary data, LOBs,
 *   TINYINT of other databases.
 */
public class MySQLDialect extends JDBCSQLDialect implements SQLDialectSchemaController, SQLDialectDDLExtension {

    // Target types of convertExternalDataType
    private static final String TYPE_NAME_UUID = "UUID";
    private static final String TYPE_NAME_UUID_TEXT = "CHAR(36)";
    private static final String TYPE_NAME_JSON = "JSON";
    private static final String TYPE_NAME_LONGTEXT = "LONGTEXT";
    private static final String TYPE_NAME_LONGBLOB = "LONGBLOB";
    private static final String TYPE_NAME_DATETIME_MICROS = "DATETIME(6)";
    private static final String TYPE_NAME_SMALLINT = "SMALLINT";
    // VARCHAR holds 65535 bytes, that is 16383 utf8mb4 characters; VARBINARY holds 65535 bytes
    private static final long MAX_VARCHAR_CHARACTERS = 16383;
    private static final long MAX_VARBINARY_LENGTH = 65535;

    /** MySQL resolves an unqualified stored-program variable before a same-named table column.
     *
     * @see <a href="https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/local-variable-scope.html">Local variable resolution</a>
     */
    private static class DialectFeatures extends SQLDialectFeatures {
        private static final DialectFeatures INSTANCE = new DialectFeatures();

        @Override
        @NotNull
        public TrailingSelectIntoSupport getTrailingSelectIntoSupport() {
            return TrailingSelectIntoSupport.ANY;
        }

        @Override
        @NotNull
        public ProceduralVariableConflictPolicy getProceduralVariableConflictPolicy() {
            return ProceduralVariableConflictPolicy.PREFER_VARIABLE;
        }

        @Override
        @NotNull
        public Set<String> getTypeModifierKeywords() {
            Set<String> keywords = new HashSet<>(super.getTypeModifierKeywords());
            keywords.addAll(Set.of("SIGNED", "UNSIGNED", "ZEROFILL"));
            return Set.copyOf(keywords);
        }

        @Override
        @NotNull
        public Set<String> getSignatureModifierKeywords() {
            return Set.of("IN", "OUT", "INOUT");
        }

        /** A local variable belongs to its BEGIN/END block and may be shadowed in a nested block.
         *
         * @see <a href="https://docs.oracle.com/cd/E17952_01/mysql-8.4-en/declare-local-variable.html">Local variable DECLARE</a>
         */
        @Override
        @NotNull
        public LocalVariableVisibility getLocalVariableVisibility(@NotNull LocalVariableKind kind) {
            return LocalVariableVisibility.LEXICAL;
        }

        @Override
        @NotNull
        public EnumSet<ProjectionAliasVisibilityScope> getProjectionAliasVisibilityScope() {
            return EnumSet.of(
                ProjectionAliasVisibilityScope.GROUP_BY,
                ProjectionAliasVisibilityScope.HAVING,
                ProjectionAliasVisibilityScope.ORDER_BY
            );
        }
    }

    @NotNull
    @Override
    public SQLScriptVariableScope getScriptVariableScope() {
        return SQLScriptVariableScope.SESSION;
    }

    @Override
    @NotNull
    public SQLDialectFeatures getDialectFeatures() {
        return DialectFeatures.INSTANCE;
    }

    public static final String[] MYSQL_NON_TRANSACTIONAL_KEYWORDS = ArrayUtils.concatArrays(
        BasicSQLDialect.NON_TRANSACTIONAL_KEYWORDS,
        new String[]{
            "USE", "SHOW",
            "CREATE", "ALTER", "DROP",
            SQLConstants.KEYWORD_EXPLAIN, "DESCRIBE", "DESC"}
    );

    private static final String[] ADVANCED_KEYWORDS = {
        "AUTO_INCREMENT",
        "DATABASES",
        "COLUMNS",
        "ALGORITHM",
        "REPAIR",
        "ENGINE",
        "MANUAL",
        "STRAIGHT_JOIN"
    };

    public static final String[][] MYSQL_QUOTE_STRINGS = {
        {"`", "`"},
        {"\"", "\""},
    };

    private static final String[] MYSQL_EXTRA_FUNCTIONS = {
        "ADDDATE",
        "ADDTIME",
        "ANY_VALUE",
        "CAST",
        "COALESCE",
        "COLLATION",
        "COMPRESS",
        "DATE_ADD",
        "DATE_SUB",
        "DATEDIFF",
        "EXTRACT",
        "FIRST_VALUE",
        "FORMAT",
        "FOUND_ROWS",
        "FROM_BASE64",
        "GET_FORMAT",
        "GROUP_CONCAT",
        "HOUR",
        "DAY",
        "IFNULL",
        "ISNULL",
        "LAG",
        "LAST_VALUE",
        "LEAD",
        "LEAST",
        "LENGTH",
        "MAKEDATE",
        "MAKETIME",
        "MINUTE",
        "MONTH",
        "NULLIF",
        "RANDOM_BYTES",
        "REPLACE",
        "REGEXP_LIKE",
        "REGEXP_INSTR",
        "REGEXP_REPLACE",
        "REGEXP_SUBSTR",
        "SESSION_USER",
        "SPACE",
        "SUBSTR",
        "SUBTIME",
        "TIMEDIFF",
        "TO_BASE64",
        "TO_SECONDS",
        "UUID",
        "UUID_TO_BIN",
        "WEEKOFYEAR",
        "YEAR"
    };

    private static final String[] MYSQL_GEOMETRY_FUNCTIONS = {
        "ST_ASWKT",
        "ST_GEOMCOLLFROMTEXT",
        "ST_GEOMETRYCOLLECTIONFROMTEXT",
        "ST_GEOMFROMTEXT",
        "ST_LINEFROMTEXT",
        "ST_MLINEFROMTEXT",
        "ST_MPOINTFROMTEXT",
        "ST_MPOLYFROMTEXT",
        "ST_POINTFROMTEXT",
        "ST_POLYFROMTEXT"
    };

    private static final String[] JSON_FUNCTIONS = {
        "JSON_ARRAY",
        "JSON_ARRAYAGG",
        "JSON_ARRAY_APPEND",
        "JSON_ARRAY_INSERT",
        "JSON_CONTAINS",
        "JSON_CONTAINS_PATH",
        "JSON_DEPTH",
        "JSON_EXTRACT",
        "JSON_INSERT",
        "JSON_KEYS",
        "JSON_LENGTH",
        "JSON_MERGE",
        "JSON_MERGE_PATCH",
        "JSON_MERGE_PRESERVE",
        "JSON_OBJECT",
        "JSON_OBJECTAGG",
        "JSON_QUOTE",
        "JSON_REMOVE",
        "JSON_REPLACE",
        "JSON_SEARCH",
        "JSON_SET",
        "JSON_TABLE",
        "JSON_TYPE",
        "JSON_UNQUOTE",
        "JSON_VALID",
        "JSON_VALUE"
    };

    private static final Pattern ONE_OR_MORE_DIGITS_PATTERN = Pattern.compile("[0-9]+");

    private static final String[] EXEC_KEYWORDS =  { "CALL" };
    private int lowerCaseTableNames;

    public MySQLDialect() {
        super("MySQL", "mysql");
    }

    public MySQLDialect(String name, String id) {
        super(name, id);
    }

    public void initBaseDriverSettings(JDBCSession session, JDBCDataSource dataSource, JDBCDatabaseMetaData metaData) {
        super.initDriverSettings(session, dataSource, metaData);

        addTableQueryKeywords(SQLConstants.KEYWORD_EXPLAIN, "DESCRIBE", "DESC");
        addFunctions(List.of("SLEEP"));

        addSQLKeywords(Arrays.asList(ADVANCED_KEYWORDS));
        removeSQLKeyword("SOURCE");

        // CHAR is data type, not function
        removeSQLKeyword("CHAR");

        addDataTypes(List.of("CHAR"));
        addFunctions(Arrays.asList(MYSQL_EXTRA_FUNCTIONS));
        addFunctions(Arrays.asList(JSON_FUNCTIONS));
    }

    @Override
    public void initDriverSettings(JDBCSession session, JDBCDataSource dataSource, JDBCDatabaseMetaData metaData) {
        initBaseDriverSettings(session, dataSource, metaData);

        addDataTypes(Arrays.asList("GEOMETRY", "POINT"));
        addFunctions(Arrays.asList(MYSQL_GEOMETRY_FUNCTIONS));
    }

    @Override
    public void afterDataSourceInitialization(@NotNull DBPDataSource dataSource) {
        this.lowerCaseTableNames = ((MySQLDataSource) dataSource).getLowerCaseTableNames();
        this.setSupportsUnquotedMixedCase(lowerCaseTableNames != 2);
    }

    @Nullable
    @Override
    public String[][] getIdentifierQuoteStrings() {
        return MYSQL_QUOTE_STRINGS;
    }

    @NotNull
    @Override
    public DBPIdentifierCase storesQuotedCase() {
        return DBPIdentifierCase.MIXED;
    }

    @NotNull
    @Override
    public String[] getExecuteKeywords() {
        return EXEC_KEYWORDS;
    }

    @Override
    public int getSchemaUsage() {
        return SQLDialect.USAGE_ALL;
    }

    @Override
    public char getStringEscapeCharacter() {
        return '\\';
    }

    @Nullable
    @Override
    public String getScriptDelimiterRedefiner() {
        return "DELIMITER";
    }

    @Override
    public String[][] getBlockBoundStrings() {
        // No anonymous blocks in MySQL
        return null;
    }

    @Override
    public boolean useCaseInsensitiveNameLookup() {
        return lowerCaseTableNames != 0;
    }

    @Override
    public boolean mustBeQuoted(@NotNull String str, boolean forceCaseSensitive) {
        Matcher matcher = ONE_OR_MORE_DIGITS_PATTERN.matcher(str);
        if (matcher.lookingAt()) { // we should quote numeric names and names starts with number
            return true;
        }
        return super.mustBeQuoted(str, forceCaseSensitive);
    }

    @NotNull
    @Override
    protected String quoteIdentifier(@NotNull String str, @NotNull String[][] quoteStrings) {
        // Escape with first (default) quote string
        return quoteStrings[0][0] + escapeString(str, quoteStrings[0]) + quoteStrings[0][1];
    }

    @NotNull
    @Override
    public String escapeString(@NotNull String string) {
        return escapeString(string, null);
    }

    @NotNull
    protected String escapeString(@NotNull String string, @Nullable String[] quotes) {
        if (quotes != null) {
            string = string.replace(quotes[0], quotes[0] + quotes[0]);
        } else {
            string = super.escapeString(string);
        }

        return string.replaceAll("\\\\(?![_%?])", "\\\\\\\\");
    }

    @NotNull
    @Override
    public String unEscapeString(@Nullable String string) {
        return CommonUtils.notEmpty(string).replace("''", "'").replace("``", "`").replace("\\\\", "\\");
    }

    @NotNull
    @Override
    public MultiValueInsertMode getDefaultMultiValueInsertMode() {
        return MultiValueInsertMode.GROUP_ROWS;
    }

    @Override
    public boolean supportsAliasInSelect() {
        return true;
    }

    @Override
    public boolean supportsTableDropCascade() {
        return true;
    }

    @Override
    public boolean supportsCommentQuery() {
        return true;
    }

    @Override
    public String[] getSingleLineComments() {
        return new String[] { "-- ", "--\t", "#" };
    }

    @Override
    public String getTestSQL() {
        return "SELECT 1";
    }

    @NotNull
    public String[] getNonTransactionKeywords() {
        return MYSQL_NON_TRANSACTIONAL_KEYWORDS;
    }

    @Override
    public boolean isAmbiguousCountBroken() {
        return true;
    }

    @Override
    protected boolean useBracketsForExec(DBSProcedure procedure) {
        // Use brackets for CallableStatement. Support for procedures only
        return procedure.getProcedureType() == DBSProcedureType.PROCEDURE;
    }

    @Override
    public boolean supportsUuid() {
        return false;
    }

    @NotNull
    @Override
    public String escapeScriptValue(DBSTypedObject attribute, @NotNull Object value, @NotNull String strValue) {
        if (attribute.getTypeName().equalsIgnoreCase(MySQLConstants.TYPE_JSON)) {
            return '\'' + escapeString(strValue) + '\'';
        }
        return super.escapeScriptValue(attribute, value, strValue);
    }

    @Override
    public boolean validIdentifierStart(char c) {
        return c == '_' || SQLUtils.isLatinLetter(c);
    }

    @NotNull
    @Override
    public String getTypeCastClause(
        @NotNull DBSTypedObject attribute,
        @NotNull String expression,
        boolean isInCondition,
        boolean exprIsAttrRef
    ) {
        if (isInCondition && attribute.getTypeName().equalsIgnoreCase(MySQLConstants.TYPE_JSON)) {
            return "CAST(" + expression + " AS JSON)";
        } else {
            return super.getTypeCastClause(attribute, expression, isInCondition, exprIsAttrRef);
        }
    }

    @NotNull
    @Override
    public String getSchemaExistQuery(@NotNull String schemaName) {
        return "SHOW DATABASES LIKE " + getQuotedString(schemaName);
    }

    @NotNull
    @Override
    public String getCreateSchemaQuery(@NotNull String schemaName) {
        return "CREATE DATABASE " + schemaName;
    }

    @Nullable
    @Override
    public String getAutoIncrementKeyword() {
        return "AUTO_INCREMENT";
    }

    @Override
    public boolean supportsCreateIfExists() {
        return true;
    }

    @NotNull
    @Override
    public String getTimestampDataType() {
        return "TIMESTAMP";
    }

    @NotNull
    @Override
    public String getBigIntegerType() {
        return "BIGINT";
    }

    @NotNull
    @Override
    public String getClobDataType() {
        return "TEXT";
    }

    @NotNull
    @Override
    public String getBlobDataType() {
        return "BLOB";
    }

    @NotNull
    @Override
    public String getUuidDataType() {
        return "CHAR(36)";
    }

    @NotNull
    @Override
    public String getBooleanDataType() {
        return "TINYINT(1)";
    }

    @NotNull
    @Override
    public String getAlterColumnOperation() {
        return "MODIFY";
    }

    @Override
    public boolean supportsNoActionIndex() {
        return true;
    }

    @Override
    public boolean supportsAlterColumnSet() {
        return true;
    }

    @Override
    public boolean supportsAlterHasColumn() {
        return false;
    }

    @Nullable
    @Override
    public String convertExternalDataType(
        @NotNull SQLDialect sourceDialect,
        @NotNull DBSTypedObject sourceTypedObject,
        @Nullable DBPDataTypeProvider targetTypeProvider
    ) {
        String converted = super.convertExternalDataType(sourceDialect, sourceTypedObject, targetTypeProvider);
        if (converted != null || sourceDialect instanceof MySQLDialect) {
            return converted;
        }
        String typeName = SQLUtils.stripColumnTypeModifiers(CommonUtils.notEmpty(sourceTypedObject.getTypeName())).toLowerCase(Locale.ENGLISH);
        int typeID = sourceTypedObject.getTypeID();
        switch (typeName) {
            case MySQLConstants.TYPE_UUID:
            case "uniqueidentifier":
                // MariaDB 10.7+ has UUID, MySQL stores the text form
                return hasLocalDataType(targetTypeProvider, MySQLConstants.TYPE_UUID) ? TYPE_NAME_UUID : TYPE_NAME_UUID_TEXT;
            case MySQLConstants.TYPE_JSON:
            case "jsonb":
                // MariaDB JSON is LONGTEXT and is not in its type list
                return hasLocalDataType(targetTypeProvider, MySQLConstants.TYPE_JSON) ? TYPE_NAME_JSON : TYPE_NAME_LONGTEXT;
            case "clob":
            case "nclob":
                return TYPE_NAME_LONGTEXT;
            case "blob":
                // Oracle reports the LOB locator length (4000) as the BLOB length
                return TYPE_NAME_LONGBLOB;
        }
        if (typeID == Types.CLOB || typeID == Types.NCLOB) {
            return TYPE_NAME_LONGTEXT;
        }
        if (typeID == Types.BLOB) {
            return TYPE_NAME_LONGBLOB;
        }
        if (DBStructUtils.isTimestamp(sourceTypedObject)) {
            // MySQL TIMESTAMP covers 1970..2038 only. The offset of a zoned value is not kept (MySQL has no such type).
            return TYPE_NAME_DATETIME_MICROS;
        }
        if (typeID == Types.TINYINT) {
            // SQL Server tinyint is unsigned (0..255), MySQL TINYINT is signed
            return TYPE_NAME_SMALLINT;
        }
        long maxLength = sourceTypedObject.getMaxLength();
        DBPDataKind dataKind = sourceTypedObject.getDataKind();
        if (dataKind == DBPDataKind.STRING && (maxLength <= 0 || maxLength > MAX_VARCHAR_CHARACTERS)) {
            // PostgreSQL text, SQL Server nvarchar(max)/ntext: TEXT would cut values longer than 64 KB
            return TYPE_NAME_LONGTEXT;
        }
        if (dataKind == DBPDataKind.BINARY && (maxLength <= 0 || maxLength > MAX_VARBINARY_LENGTH)) {
            // PostgreSQL bytea, SQL Server varbinary(max): BINARY/VARBINARY without a length is BINARY(1) or invalid
            return TYPE_NAME_LONGBLOB;
        }
        return null;
    }

    private static boolean hasLocalDataType(@Nullable DBPDataTypeProvider typeProvider, @NotNull String typeName) {
        return typeProvider != null && typeProvider.getLocalDataType(typeName) != null;
    }

    @Nullable
    @Override
    public String getColumnCharsetModifier(@NotNull ColumnCharset charset) {
        if (charset == ColumnCharset.ASCII) {
            return "CHARACTER SET latin1";
        }
        return null;
    }
}
