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
package com.seizia.chromite.model.sql;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPIdentifierCase;
import com.seizia.chromite.model.DBPKeywordType;
import com.seizia.chromite.model.data.DBDBinaryFormatter;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.exec.DBCLogicalOperator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.parser.SQLTokenPredicateSet;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.model.struct.rdb.DBSProcedure;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameter;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;
import org.jkiss.utils.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * SQL dialect
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: {@link #isCallStatementTarget}를 추가. SQL 자동완성이 CALL/EXEC 뒤에 부를 수 있는 루틴만 제안하도록
 *   데이터베이스마다 다른 규칙(SQL Server는 스칼라 함수도 EXEC 가능)을 방언에 둔다 (docs/chromite/swing-sql-completion.md)
 * - 2026-11-08 Chromite: {@link #supportsMergeStatement}를 추가. 결과 행에서 MERGE를 만들 때 MERGE가 있는 데이터베이스에서만
 *   제안하도록 방언이 알린다(키워드 목록은 모든 방언에 MERGE를 넣으므로 근거가 되지 못함) (docs/chromite/swing-result-generate-sql.md)
 */
public interface SQLDialect {

    int USAGE_NONE = 0;
    int USAGE_DML = 1;
    int USAGE_DDL = 2;
    int USAGE_PROC = 4;
    int USAGE_INDEX = 8;
    int USAGE_PRIV = 8;
    int USAGE_ALL = Integer.MAX_VALUE;

    enum MultiValueInsertMode {
        NOT_SUPPORTED,
        GROUP_ROWS,
        PLAIN,
        INSERT_ALL
    }

    @NotNull
    SQLQueryGenerator getQueryGenerator();

    @NotNull
    String getDialectId();

    /**
     * Dialect name
     * @return SQL dialect name
     */
    @NotNull
    String getDialectName();

    /**
     * Retrieves strings used to quote SQL identifiers.
     * This method returns null or empty array if identifier quoting is not supported.
     *
     * @return the array of string pairs
     */
    @Nullable
    String[][] getIdentifierQuoteStrings();

    /**
     * Retrieves strings used to quote SQL strings.
     *
     * @return the array of string pairs
     */
    @NotNull
    String[][] getStringQuoteStrings();

    /**
     * Data query keywords. By default it is SELECT
     */
    @NotNull
    String[] getQueryKeywords();

    /**
     * Retrieves a list of execute keywords. If database doesn't support implicit execute returns empty list or null.
     */
    @NotNull
    String[] getExecuteKeywords();

    /**
     * Retrieves a list of execute keywords. If database doesn't support implicit execute returns empty list or null.
     */
    @NotNull
    String[] getDDLKeywords();

    @NotNull
    String[] getDMLKeywords();

    /**
     * Keywords that can start a statement in the SQL editor completion.
     * Unlike execute keywords, these do not affect statement parsing or parameter binding.
     */
    @NotNull
    default Collection<String> getCompletionStatementKeywords() {
        List<String> keywords = new ArrayList<>();
        Collections.addAll(keywords, getQueryKeywords());
        Collections.addAll(keywords, getDMLKeywords());
        Collections.addAll(keywords, getDDLKeywords());
        Collections.addAll(keywords, getExecuteKeywords());
        return keywords;
    }

    /**
     * Retrieves a list of all of this database's SQL keywords
     * that are NOT also SQL92 keywords.
     *
     * @return the list of this database's keywords that are not also
     *         SQL92 keywords
     */
    @NotNull
    Collection<String> getReservedWords();

    @NotNull
    Collection<String> getFunctions();
    @NotNull
    Collection<String> getDataTypes(@Nullable DBPDataSource dataSource);
    @Nullable
    DBPKeywordType getKeywordType(@NotNull String word);
    @NotNull
    List<String> getMatchedKeywords(@NotNull String word);

    boolean isKeywordStart(@NotNull String word);

    boolean isEntityQueryWord(@NotNull String word);

    boolean isAttributeQueryWord(@NotNull String word);

    int getKeywordNextLineIndent(@NotNull String word);

    /**
     * Retrieves the string that can be used to escape wildcard characters.
     * This is the string that can be used to escape '_' or '%' in
     * the catalog search parameters that are a pattern (and therefore use one
     * of the wildcard characters).
     * <p/>
     * <P>The '_' character represents any single character;
     * the '%' character represents any sequence of zero or
     * more characters.
     *
     * @return the string used to escape wildcard characters
     */
    @NotNull
    String getSearchStringEscape();

    /**
     * Strings (in single or double quotes) escape character. Zero (i.e. no scape character) by default.
     * Back slash in many dialects
     */
    char getStringEscapeCharacter();

    /**
     * Catalog name usage in queries
     * @return catalog usage
     */
    int getCatalogUsage();

    /**
     * Schema name usage in queries
     * @return schema usage
     */
    int getSchemaUsage();

    /**
     * Retrieves the <code>String</code> that this database uses as the
     * separator between a catalog and table name.
     *
     * @return the separator string
     */
    @NotNull
    String getCatalogSeparator();

    /**
     * Retrieves the <code>String</code> that this database uses as the
     * separator between a structured objects (e.g. schema and table).
     *
     * @return the separator string
     */
    char getStructSeparator();

    @NotNull
    String[] getParametersPrefixes();

    /**
     * Script delimiter characters
     * @return array of possible script delimiters with first element as default delimiter
     */
    @NotNull
    String[] getScriptDelimiters();

    /**
     * Script delimiters that separate batches rather than individual statements.
     */
    @NotNull
    default String[] getBatchDelimiters() {
        return new String[0];
    }

    /**
     * Returns the lifetime of variables introduced by standalone SQL statements, or {@code null} when unsupported.
     */
    @Nullable
    default SQLScriptVariableScope getScriptVariableScope() {
        return null;
    }

    @Nullable
    String getScriptDelimiterRedefiner();

    /**
     * SQL block statements (BEGIN/END).
     * Null if not supported
     */
    @Nullable
    String[][] getBlockBoundStrings();

    /**
     * Script block header string.
     * Begins SQL block header (most typical: DECLARE).
     * @return block header string or null (not supported)
     */
    @Nullable
    String[] getBlockHeaderStrings();

    /**
     * Inner block prefixes strings.
     * Determines if the block is a child of the header block.
     * @return inner block prefixes or null (if not supported)
     */
    @Nullable
    String[] getInnerBlockPrefixes();

    /**
     * Retrieves whether a catalog appears at the start of a fully qualified
     * table name.  If not, the catalog appears at the end.
     *
     * @return <code>true</code> if the catalog name appears at the beginning
     *         of a fully qualified table name; <code>false</code> otherwise
     */
    boolean isCatalogAtStart();

    /**
     * SQL state type
     * @return sql state type
     */
    @NotNull
    SQLStateType getSQLStateType();

    boolean isWordStart(int ch);

    boolean isWordPart(int ch);

    boolean validIdentifierStart(char c);
    /**
     * Checks that specified character is a valid identifier part. Non-valid characters should be quoted in queries.
     * @param c character
     * @param quoted is identifier quoted
     * @return true or false
     */
    boolean validIdentifierPart(char c, boolean quoted);

    boolean useCaseInsensitiveNameLookup();

    boolean hasCaseSensitiveFiltration();

    boolean supportsUnquotedMixedCase();

    boolean supportsQuotedMixedCase();

    boolean supportsSubqueries();

    /**
     * Returns true when database dialect allows <b>table</b> alias in FROM clause
     */
    boolean supportsAliasInSelect();

    boolean supportsAliasInUpdate();

    boolean supportsAsKeywordBeforeAliasInFromClause();
    /**
     * Column name to list all table columns. Usually asterisk (*).
     */
    @Nullable
    String getAllAttributesAlias();

    /**
     * Column name to use in grouping queries like COUNT. Usually asterisk (*).
     */
    @Nullable
    String getDefaultGroupAttribute();

    boolean supportsAliasInConditions();

    /**
     * Returns offset and limit query parts. Limit syntax is different for databases.
     */
    String getOffsetLimitQueryPart(int offset, int limit);

    /**
     * Returns clob comparing part for where clause.
     */
    String getClobComparingPart(@NotNull String columnName);

    /**
     * Checks whether dialect supports alias for queries with HAVING syntax.
     */
    boolean supportsAliasInHaving();

    boolean supportsTableDropCascade();

    boolean supportsOrderByIndex();

    boolean supportsNestedComments();

    /**
     * Check whether dialect support plain comment queries (queries which contains only comments)
     */
    boolean supportsCommentQuery();

    boolean supportsNullability();

    boolean supportsColumnAutoIncrement();

    @Nullable
    SQLExpressionFormatter getCaseInsensitiveExpressionFormatter(@NotNull DBCLogicalOperator operator);

    @NotNull
    DBPIdentifierCase storesUnquotedCase();

    @NotNull
    DBPIdentifierCase storesQuotedCase();

    /**
     * Enables to call particular cast operator or function for special attribute name.
     * Formats attribute name.
     * @param attribute   attribute data to help decide whether cast and how to cast
     * @return            casted attribute name
     */
    String getCastedAttributeName(@NotNull DBSAttributeBase attribute, String attributeName);

    /**
     * Enables to call particular cast operator or function for special data types.
     * Wraps expression with cast if needed.
     * @param attribute   value attribute to help decide whether cast and how to cast
     * @param expression      string representation for cast, considered as NOT a reference to the given attribute
     * @param isInCondition helps to understand the application place of the method
     * @return            casted string
     */
    @NotNull
    default String getTypeCastClause(@NotNull DBSTypedObject attribute, @NotNull String expression, boolean isInCondition) {
        return this.getTypeCastClause(attribute, expression, isInCondition, false);
    }

    /**
     * Enables to call particular cast operator or function for special data types.
     * Wraps expression with cast if needed.
     * @param attribute   value attribute to help decide whether cast and how to cast
     * @param expression      string representation for cast, considered as NOT a reference to the given attribute
     * @param isInCondition helps to understand the application place of the method
     * @param exprIsAttrRef true when expression represents a reference to the given attribute
     * @return            casted string
     */
    @NotNull
    String getTypeCastClause(@NotNull DBSTypedObject attribute, @NotNull String expression, boolean isInCondition, boolean exprIsAttrRef);

    /**
     * Quoting functions
     */

    boolean isQuotedIdentifier(String identifier);

    String getQuotedIdentifier(String identifier, boolean forceCaseSensitive, boolean forceQuotes);

    /**
     * @param str - string that must be unquoted for this check - otherwise method will return true
     * @param forceCaseSensitive the level of case sensitivity
     * @return true if incoming string must be quoted
     */
    boolean mustBeQuoted(@NotNull String str, boolean forceCaseSensitive);

    String getUnquotedIdentifier(String identifier);

    String getUnquotedIdentifier(String identifier, boolean unescapeQuotesInsideIdentifier);

    boolean isQuotedString(@NotNull String string);

    @NotNull
    String getQuotedString(@NotNull String string);

    @NotNull
    String getUnquotedString(@NotNull String string);

    /**
     * Escapes string to make usable inside of SQL queries.
     * Basically it has to escape only ' character which delimits strings.
     * @param string string to escape
     * @return escaped string
     */
    @NotNull
    String escapeString(@NotNull String string);

    @NotNull
    String unEscapeString(@Nullable String string);

    /**
     * Encode value to string format (to use it in scripts, e.g. in INSERT/UPDATE statements)
     * @param attribute   value attribute to help decide whether value should be escaped or not
     * @param value       original value
     * @param strValue    string representation (default result)
     */
    @NotNull
    String escapeScriptValue(DBSTypedObject attribute, @NotNull Object value, @NotNull String strValue);

    /**
     * Default multi-value insertion mode
     * Used e.g. to SQL export
     * @return MultiValueInsertMode enum value
     */
    @NotNull
    MultiValueInsertMode getDefaultMultiValueInsertMode();

    @NotNull
    String addFiltersToQuery(
        @Nullable DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull String query,
        @NotNull DBDDataFilter filter
    ) throws DBException;

    /**
     * Two-item array containing begin and end of multi-line comments.
     * @return string array or null if multi-line comments are not supported
     */
    @Nullable
    Pair<String, String> getMultiLineComments();

    /**
     * List of possible single-line comment prefixes
     * @return comment prefixes or null if single line comments are nto supported
     */
    String[] getSingleLineComments();

    /**
     * True if anonymous SQL blocks must be finished with delimiter
     */
    boolean isDelimiterAfterQuery();

    /**
     * True if anonymous SQL blocks must be finished with delimiter
     */
    boolean isDelimiterAfterBlock();

    /**
     * True if dialect requires delimiter for a query which starts with @firstKeyword and ends with @lastKeyword
     */
    boolean needsDelimiterFor(String firstKeyword, String lastKeyword);

    /**
     * Reports about broken CRLF. Queries mustn't contain CRLF line feeds, only LF.
     * This actually seems to be Oracle 9 and earlier JDBC driver issue.
     */
    boolean isCRLFBroken();

    @NotNull
    DBDBinaryFormatter getNativeBinaryFormatter();

    @Nullable
    String getTestSQL();

    @NotNull
    Pair<String, String> getInClauseParentheses();

    /**
     * Dual table name.
     * Used to evaluate expressions, call procedures, etc.
     * @return fully qualified table name or null if table name is not needed.
     */
    @Nullable
    String getDualTableName();

    /**
     * Returns true if the comments need to be removed from the statement if
     * they are right before the block declaration
     */
    boolean isStripCommentsBeforeBlocks();

    /**
     * Returns true if need to escape backslash character
     */
    boolean isEscapeBackslash();

    /**
     * Returns true if query is definitely transactional. Otherwise returns false, however it still may be transactional.
     * You need to check query results to ensure that it is not transactional.
     */
    boolean isTransactionModifyingQuery(String queryString);

    @Nullable
    String[] getTransactionCommitKeywords();

    @Nullable
    String[] getTransactionRollbackKeywords();

    /**
     * Generates full type name for drivers which don't implement {@link DBSTypedObject#getFullTypeName()}
     */
    @Nullable
    String getColumnTypeModifiers(DBPDataSource dataSource, @NotNull DBSTypedObject column, @NotNull String typeName, @NotNull DBPDataKind dataKind);

    /**
     * Formats stored procedure call. By default returns @sqlText.
     */
    String formatStoredProcedureCall(DBPDataSource dataSource, String sqlText);

    /**
     * Generates stored procedure call. Parameters (optionally) can be surrounded by cast(:param as paramType).
     */
    void generateStoredProcedureCall(
        StringBuilder sql,
        DBSProcedure proc,
        Collection<? extends DBSProcedureParameter> parameters,
        boolean castParams
    );

    /**
     * Whether the routine can be named by a call statement ({@code CALL}, {@code EXEC}).
     * SQL completion proposes only these routines after the call keyword.
     * By default functions are excluded: most databases call them only inside expressions
     * (PostgreSQL and MySQL {@code CALL} reject functions, Oracle {@code EXEC} needs an assignment).
     */
    default boolean isCallStatementTarget(@NotNull DBSProcedure procedure) {
        return procedure.getProcedureType() != DBSProcedureType.FUNCTION;
    }

    boolean isDisableScriptEscapeProcessing();

    boolean supportsAlterTableStatement();

    boolean supportsIndexCreateAndDrop();

    boolean supportsInsertAllDefaultValuesStatement();

    /**
     * Whether the database has the SQL:2003 {@code MERGE INTO … USING … ON … WHEN MATCHED … WHEN NOT MATCHED …}
     * statement. Off by default: MySQL, MariaDB and SQLite have none, and PostgreSQL only since version 15 (its
     * upsert is {@code INSERT … ON CONFLICT}, an insert replace method of the dialect).
     */
    default boolean supportsMergeStatement() {
        return false;
    }

    boolean supportsUuid();

    /**
     * Generates a set of connection-specific dialect features which require special handling during SQL parsing
     * (empty by default)
     * @return a set of token predicates
     */
    @NotNull
    SQLTokenPredicateSet getSkipTokenPredicates();

    /**
     * Returns the dialect features object containing semantic-analyzer-only configuration.
     * <p>
     * Dialects that need custom behaviour create a nested subclass of
     * {@link SQLDialectFeatures} and return it from here.
     */
    @NotNull
    SQLDialectFeatures getDialectFeatures();

    default void afterDataSourceInitialization(@NotNull DBPDataSource dataSource) {
    }

    default boolean useEmptyStringForNulls() {
        return false;
    }

    default boolean supportsQualifiedColumnNames() {
        return true;
    }

    @NotNull
    default String toSortableTextColumn(@NotNull String columnName) {
        return columnName;
    }

    /**
     * Indicates whether the database dialect supports ordering
     * by column position/index (e.g. {@code ORDER BY 1}).
     */
    default boolean supportsColumnIndexOrdering() {
        return true;
    }

    /**
     * Quotes ALL database identifiers by default.
     */
    default boolean isQuoteIdentifiersAlways() {
        return false;
    }

}
