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
package org.jkiss.dbeaver.model.struct;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.*;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDAttributeBindingMeta;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.edit.DBERegistry;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;
import org.jkiss.dbeaver.model.impl.sql.edit.SQLObjectEditor;
import org.jkiss.dbeaver.model.impl.sql.edit.struct.SQLTableManager;
import org.jkiss.dbeaver.model.messages.ModelMessages;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.SubTaskProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLConstants;
import org.jkiss.dbeaver.model.sql.SQLDataTypeConverter;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.rdb.*;
import org.jkiss.dbeaver.model.virtual.DBVUtils;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.sql.Types;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * DBUtils
 *
 * 설계: {@link #mapTargetDataType} picks a target column type for data transfer (Copy Tables, import into a new table).
 * Dialects convert well-known foreign type names first ({@link SQLDataTypeConverter}); the generic part then looks the
 * source type name up in the target. Integer and floating point types fall back to the source JDBC type and its value
 * range instead of parts of the type name, because vendor names (PostgreSQL int4, Oracle BINARY_DOUBLE) are unknown to
 * other databases and the same name can mean a narrower type elsewhere (MySQL FLOAT is single precision).
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: numeric fallback by JDBC type and value range (PostgreSQL int4 became MySQL TINYINT,
 *   float8 became FLOAT, MySQL DOUBLE became SQL Server int, Oracle NUMBER(12,2) became float and NUMBER became int),
 *   shared timestamp checks for dialect converters.
 * - 2026-11-07 Chromite: a decimal without precision (PostgreSQL numeric) no longer takes the target type of the same
 *   name, which has a fixed default precision and scale (SQL Server numeric(18,0), MySQL DECIMAL(10,0)).
 */
public final class DBStructUtils {

    private static final Log log = Log.getLog(DBStructUtils.class);

    // Value range ranks of integer and floating point types. A larger rank holds every value of a smaller one
    // within the same group (integers below FLOAT_RANK_BASE, floating point types above it).
    private static final int RANK_NONE = 0;
    private static final int RANK_TINYINT = 1;
    private static final int RANK_SMALLINT = 2;
    private static final int RANK_INTEGER = 3;
    private static final int RANK_BIGINT = 4;
    private static final int FLOAT_RANK_BASE = 10;
    private static final int RANK_REAL = 11;
    private static final int RANK_DOUBLE = 12;
    // Binary digits of an IEEE single precision value: JDBC FLOAT up to this precision is a REAL
    private static final int MAX_REAL_BINARY_PRECISION = 24;
    private static final String UNSIGNED_MODIFIER = "unsigned";

    // Standard and common vendor names. The target type actually used is the one with the smallest rank that still
    // holds the source range; the order breaks ties. Double precision names come first because some drivers report
    // single precision types with a large precision (Oracle BINARY_FLOAT as FLOAT with precision 38).
    private static final String[] INTEGER_TYPE_NAMES = {"tinyint", "smallint", "int2", "integer", "int", "int4", "bigint", "int8"};
    private static final String[] FLOAT_TYPE_NAMES = {"double precision", "double", "float8", "binary_double", "float4", "real", "binary_float", "float"};
    private static final String[] DECIMAL_TYPE_NAMES = {"numeric", "decimal", "number"};

    private static final String INT_DATA_TYPE = "int";
    private static final String INTEGER_DATA_TYPE = "integer";
    private static final String FLOAT_DATA_TYPE = "float";
    private static final String REAL_DATA_TYPE = "real";
    private static final String DOUBLE_DATA_TYPE = "double";
    private static final String TEXT_DATA_TYPE = "text";
    private static final String VARCHAR_DATA_TYPE = "varchar";
    private static final String VARCHAR2_DATA_TYPE = "varchar2";
    private static final int DEFAULT_VARCHAR_LENGTH = 100;

    @Nullable
    public static DBSEntityReferrer getEnumerableConstraint(@NotNull DBRProgressMonitor monitor, @NotNull DBDAttributeBinding attribute) throws DBException {
        DBSEntityAttribute entityAttribute = attribute.getEntityAttribute();
        if (entityAttribute != null) {
            return getEnumerableConstraint(monitor, entityAttribute);
        }
        return null;
    }

    @Nullable
    public static DBSEntityReferrer getEnumerableConstraint(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntityAttribute entityAttribute) throws DBException {
        List<DBSEntityReferrer> refs = DBUtils.getAttributeReferrers(monitor, entityAttribute, true);
        DBSEntityReferrer constraint = refs.isEmpty() ? null : refs.get(0);
        if (constraint != null) {
            DBSEntity associatedEntity = getAssociatedEntity(monitor, constraint);
            if (associatedEntity instanceof DBSDictionary dictionary) {
                if (dictionary.supportsDictionaryEnumeration()) {
                    return constraint;
                }
            }
        }
        return null;
    }

    @Nullable
    public static DBSEntity getAssociatedEntity(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSEntityConstraint constraint
    ) throws DBException {
        if (constraint instanceof DBSEntityAssociationLazy associationLazy) {
            return associationLazy.getAssociatedEntity(monitor);
        } else if (constraint instanceof DBSEntityAssociation association) {
            return association.getAssociatedEntity();
        }
        return null;
    }

    @NotNull
    public static String generateTableDDL(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSEntity table,
        @NotNull Map<String, Object> options,
        boolean addComments
    ) throws DBException {
        final DBERegistry editorsRegistry = DBWorkbench.getPlatform().getEditorsRegistry();
        final SQLObjectEditor<?, ?> entityEditor = editorsRegistry.getObjectManager(table.getClass(), SQLObjectEditor.class);
        if (entityEditor instanceof SQLTableManager tableManager) {
            DBEPersistAction[] ddlActions = tableManager.getTableDDL(monitor, table, options);
            return SQLUtils.generateScript(table.getDataSource(), ddlActions, addComments);
        }
        log.debug("Table editor not found for " + table.getClass().getName());
        return SQLUtils.generateCommentLine(table.getDataSource(), "Can't generate DDL: table editor not found for " + table.getClass().getName());
    }

    @NotNull
    public static String generateObjectDDL(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSObject object,
        @NotNull Map<String, Object> options,
        boolean addComments
    ) throws DBException {
        final DBERegistry editorsRegistry = DBWorkbench.getPlatform().getEditorsRegistry();
        final SQLObjectEditor entityEditor = editorsRegistry.getObjectManager(object.getClass(), SQLObjectEditor.class);
        if (entityEditor != null) {
            SQLObjectEditor.ObjectCreateCommand createCommand = entityEditor.makeCreateCommand(object, options);
            DBEPersistAction[] ddlActions = createCommand.getPersistActions(monitor, DBUtils.getDefaultContext(object, true), options);

            return SQLUtils.generateScript(object.getDataSource(), ddlActions, addComments);
        }
        log.debug("Object editor not found for " + object.getClass().getName());
        return SQLUtils.generateCommentLine(object.getDataSource(), "Can't generate DDL: object editor not found for " + object.getClass().getName());
    }

    public static String getTableDDL(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSEntity table,
        Map<String, Object> options,
        boolean addComments
    ) throws DBException {
        if (table instanceof DBPScriptObject scriptObject) {
            String definitionText = scriptObject.getObjectDefinitionText(monitor, options);
            if (!CommonUtils.isEmpty(definitionText)) {
                return definitionText;
            }
        }
        return generateTableDDL(monitor, table, options, addComments);
    }

    public static <T extends DBSEntity> void generateTableListDDL(
        @NotNull DBRProgressMonitor monitor,
        @NotNull StringBuilder sql,
        @NotNull Collection<T> tablesOrViews,
        Map<String, Object> options,
        boolean addComments
    ) throws DBException {
        List<T> goodTableList = new ArrayList<>();
        List<T> cycleTableList = new ArrayList<>();
        List<T> viewList = new ArrayList<>();

        DBStructUtils.sortTableList(monitor, tablesOrViews, goodTableList, cycleTableList, viewList);

        // Good tables: generate full DDL
        for (T table : goodTableList) {
            sql.append(getObjectNameComment(table, ModelMessages.struct_utils_object_ddl_definition));
            addDDLLine(sql, DBStructUtils.getTableDDL(monitor, table, options, addComments));
        }
        {
            // Cycle tables: generate CREATE TABLE and CREATE FOREIGN KEY separately
            // This doesn't work if table implementation doesn't support DDL restructure
            List<T> goodCycleTableList = new ArrayList<>();
            for (T table : cycleTableList) {
                if (
                    table instanceof DBPScriptObjectExt2 so2 &&
                    so2.supportsObjectDefinitionOption(DBPScriptObject.OPTION_DDL_SKIP_FOREIGN_KEYS) &&
                    so2.supportsObjectDefinitionOption(DBPScriptObject.OPTION_DDL_ONLY_FOREIGN_KEYS))
                {
                    goodCycleTableList.add(table);
                }
            }
            cycleTableList.removeAll(goodCycleTableList);

            if (!CommonUtils.getOption(options, DBPScriptObject.OPTION_DDL_SEPARATE_FOREIGN_KEYS_STATEMENTS, true)) {
                for (T table : goodCycleTableList) {
                    sql.append(getObjectNameComment(table, ModelMessages.struct_utils_object_ddl_definition));
                    addDDLLine(sql, DBStructUtils.getTableDDL(monitor, table, options, addComments));
                }
            } else {
                Map<String, Object> optionsNoFK = new HashMap<>(options);
                optionsNoFK.put(DBPScriptObject.OPTION_DDL_SKIP_FOREIGN_KEYS, true);
                for (T table : goodCycleTableList) {
                    sql.append(getObjectNameComment(table, ModelMessages.struct_utils_object_ddl_definition));
                    addDDLLine(sql, DBStructUtils.getTableDDL(monitor, table, optionsNoFK, addComments));
                }
                Map<String, Object> optionsOnlyFK = new HashMap<>(options);
                optionsOnlyFK.put(DBPScriptObject.OPTION_DDL_ONLY_FOREIGN_KEYS, true);
                for (T table : goodCycleTableList) {
                    sql.append(getObjectNameComment(table, ModelMessages.struct_utils_object_ddl_foreign_keys));
                    addDDLLine(sql, DBStructUtils.getTableDDL(monitor, table, optionsOnlyFK, addComments));
                }
            }

            // the rest - tables which can't split their DDL
            for (T table : cycleTableList) {
                sql.append(getObjectNameComment(table, ModelMessages.struct_utils_object_ddl_definition));
                addDDLLine(sql, DBStructUtils.getTableDDL(monitor, table, options, addComments));
            }
        }
        // Views: generate them after all tables.
        // TODO: find view dependencies and generate them in right order
        for (T table : viewList) {
            String objectNameComment = getObjectNameComment(table, ModelMessages.struct_utils_object_ddl_source);
            String tableDDL = DBStructUtils.getTableDDL(monitor, table, options, addComments);
            if (!tableDDL.startsWith(objectNameComment)) {
                sql.append(objectNameComment);
            }
            addDDLLine(sql, tableDDL);
        }
        monitor.done();
    }

    private static String getObjectNameComment(@NotNull DBSObject object, @NotNull String comment) {
        DBPDataSource dataSource = object.getDataSource();
        if (dataSource == null) {
            return "";
        }
        if (!dataSource.getContainer().getPreferenceStore().getBoolean(ModelPreferences.META_EXTRA_DDL_INFO)) {
            // Skip this step, then
            return "";
        }
        String[] singleLineComments = dataSource.getSQLDialect().getSingleLineComments();
        if (ArrayUtils.isEmpty(singleLineComments)) {
            return "";
        }
        String lf = GeneralUtils.getDefaultLineSeparator();
        return singleLineComments[0].trim() + " " + DBUtils.getObjectFullName(object, DBPEvaluationContext.DDL) +
            " " + comment + lf + lf;
    }

    public static void addDDLLine(@NotNull StringBuilder sql, @NotNull String ddl) {
        ddl = ddl.trim();
        if (!CommonUtils.isEmpty(ddl)) {
            sql.append(ddl);
            if (!ddl.endsWith(SQLConstants.DEFAULT_STATEMENT_DELIMITER)) {
                sql.append(SQLConstants.DEFAULT_STATEMENT_DELIMITER);
            }
            String lf = GeneralUtils.getDefaultLineSeparator();
            sql.append(lf).append(lf).append(lf);
        }
    }

    public static <T extends DBSEntity> void sortTableList(
        @NotNull DBRProgressMonitor monitor,
        @NotNull Collection<T> input,
        @NotNull List<T> simpleTables,
        @NotNull List<T> cyclicTables,
        @NotNull List<T> views
    ) throws DBException {
        monitor.beginTask("Sorting table list", input.size());
        List<T> realTables = new ArrayList<>();
        for (T entity : input) {
            if (entity instanceof DBSView || (entity instanceof DBSTable table && table.isView())) {
                views.add(entity);
            } else {
                realTables.add(entity);
            }
        }
        DBRProgressMonitor proxyMonitor = new SubTaskProgressMonitor(monitor);

        // 1. Get tables without FKs
        for (Iterator<T> iterator = realTables.iterator(); iterator.hasNext(); ) {
            if (monitor.isCanceled()) {
                break;
            }
            T table = iterator.next();
            try {
                if (CommonUtils.isEmpty(table.getAssociations(proxyMonitor))) {
                    simpleTables.add(table);
                    iterator.remove();
                }
            } catch (DBException e) {
                log.debug(e);
            }
            monitor.worked(1);
        }

        // 2. Get tables referring tables from p.1 only
        // 3. Repeat p.2 until something is found
        boolean refsFound = true;
        while (refsFound) {
            if (monitor.isCanceled()) {
                break;
            }
            refsFound = false;
            for (Iterator<T> iterator = realTables.iterator(); iterator.hasNext(); ) {
                if (monitor.isCanceled()) {
                    break;
                }
                T table = iterator.next();
                try {
                    boolean allGood = true;
                    for (DBSEntityAssociation ref : CommonUtils.safeCollection(table.getAssociations(proxyMonitor))) {
                        monitor.worked(1);
                        DBSEntity refEntity = ref.getAssociatedEntity();
                        if (refEntity == null || (!simpleTables.contains(refEntity) && refEntity != table)) {
                            allGood = false;
                            break;
                        }
                    }
                    if (allGood) {
                        simpleTables.add(table);
                        iterator.remove();
                        refsFound = true;
                    }
                } catch (DBException e) {
                    log.error(e);
                }
            }
        };

        // 4. The rest is cycled tables
        cyclicTables.addAll(realTables);
        monitor.done();
    }

    @NotNull
    public static String mapTargetDataType(
        @Nullable DBSObject objectContainer,
        @NotNull DBSTypedObject srcTypedObject,
        boolean addModifiers
    ) {
        boolean isBindingWithEntityAttr = false;
        if (srcTypedObject instanceof DBDAttributeBinding attributeBinding) {
            if (attributeBinding.getEntityAttribute() != null) {
                isBindingWithEntityAttr = true;
            }
        }
        if (objectContainer != null && (srcTypedObject instanceof DBSEntityAttribute || isBindingWithEntityAttr || (
                srcTypedObject instanceof DBSObject dbsObject &&  objectContainer.getDataSource() == dbsObject.getDataSource()))) {
            // If source and target datasources have the same type then just return the same type name
            DBPDataSource srcDataSource = ((DBSObject) srcTypedObject).getDataSource();
            assert srcDataSource != null;
            DBPDataSource tgtDataSource = objectContainer.getDataSource();
            assert tgtDataSource != null;
            if (srcDataSource.getClass() == tgtDataSource.getClass() && addModifiers) {
                return srcTypedObject.getFullTypeName();
            }
        }

        {
            SQLDataTypeConverter dataTypeConverter = objectContainer == null || objectContainer.getDataSource() == null ? null :
                DBUtils.getAdapter(SQLDataTypeConverter.class, objectContainer.getDataSource().getSQLDialect());
            if (dataTypeConverter != null && srcTypedObject instanceof DBSObject dbsObject) {
                DBPDataSource srcDataSource = dbsObject.getDataSource();
                assert srcDataSource != null;
                DBPDataSource tgtDataSource = objectContainer.getDataSource();
                String targetTypeName = dataTypeConverter.convertExternalDataType(
                    srcDataSource.getSQLDialect(),
                    srcTypedObject, DBUtils.getAdapter(DBPDataTypeProvider.class, tgtDataSource)
                );
                if (targetTypeName != null) {
                    return targetTypeName;
                }
            }
        }

        String typeName = srcTypedObject.getTypeName();
        String typeNameLower = typeName.toLowerCase(Locale.ENGLISH);
        DBPDataKind dataKind = srcTypedObject.getDataKind();

        DBPDataTypeProvider dataTypeProvider = DBUtils.getParentOfType(DBPDataTypeProvider.class, objectContainer);
        if (dataTypeProvider != null) {
            DBSDataType dataType = dataTypeProvider.getLocalDataType(typeName);
            if (dataType == null && typeName.contains("(")) {
                // It seems this data type has modifiers. Try to find without modifiers
                dataType = dataTypeProvider.getLocalDataType(SQLUtils.stripColumnTypeModifiers(typeName));
                if (dataType != null) {
                    int startPos = typeName.indexOf("(");
                    typeName = dataType + typeName.substring(startPos);
                }
            }
            if (dataType == null && typeNameLower.equals(DOUBLE_DATA_TYPE)) {
                dataType = dataTypeProvider.getLocalDataType("DOUBLE PRECISION");
                if (dataType != null) {
                    typeName = dataType.getTypeName();
                }
            }
            if (dataType != null && !DBPDataKind.canConsume(dataKind, dataType.getDataKind())) {
                // Type mismatch
                dataType = null;
            }
            if (dataKind == DBPDataKind.NUMERIC) {
                // Choose by the source JDBC type and value range. Guessing by name parts below picked the first
                // target type containing "int" (MySQL TINYINT for PostgreSQL int4/int8) or "float" (single precision
                // FLOAT for float8), and an equal name may be narrower in the target (SQL Server float -> MySQL FLOAT).
                int sourceRank = getNumericRank(srcTypedObject.getTypeID(), srcTypedObject.getPrecision(), srcTypedObject.getFullTypeName(), false);
                if (dataType != null && (isNarrowerNumericType(dataType, sourceRank) || isDecimalWithoutPrecision(srcTypedObject))) {
                    // A decimal without precision holds any scale; the target type of the same name without
                    // modifiers has a fixed default (SQL Server numeric(18,0), MySQL DECIMAL(10,0)) and cut the values
                    dataType = null;
                }
                if (dataType == null) {
                    DBSDataType rangeType = findNumericTargetType(dataTypeProvider, srcTypedObject, sourceRank);
                    if (rangeType != null) {
                        dataType = rangeType;
                        typeName = rangeType.getTypeName();
                    }
                }
            }
            if (dataType == null) {
                // Type not supported by target database
                // Let's try to find something similar
                Map<String, DBSDataType> possibleTypes = new LinkedHashMap<>();
                for (DBSDataType type : dataTypeProvider.getLocalDataTypes()) {
                    if (DBPDataKind.canConsume(type.getDataKind(), dataKind)) {
                        possibleTypes.put(type.getTypeName().toLowerCase(Locale.ENGLISH), type);
                    }
                }
                DBSDataType targetType = null;
                if (!possibleTypes.isEmpty()) {
                    // Try to get any partial match
                    targetType = possibleTypes.get(typeNameLower);
                    if (targetType == null && dataKind == DBPDataKind.NUMERIC) {
                        // Try to find appropriate type with the same scale/precision
                        for (DBSDataType type : possibleTypes.values()) {
                            if (CommonUtils.equalObjects(type.getScale(), srcTypedObject.getScale()) &&
                                CommonUtils.equalObjects(type.getPrecision(), srcTypedObject.getPrecision()))
                            {
                                targetType = type;
                                break;
                            }
                        }
                        if (targetType == null) {
                            if (typeNameLower.contains(FLOAT_DATA_TYPE) ||
                                typeNameLower.contains(REAL_DATA_TYPE) ||
                                (srcTypedObject.getScale() != null && srcTypedObject.getScale() > 0 && srcTypedObject.getScale() <= 6))
                            {
                                for (String psn : possibleTypes.keySet()) {
                                    if (psn.contains(FLOAT_DATA_TYPE) || psn.contains(REAL_DATA_TYPE)) {
                                        targetType = possibleTypes.get(psn);
                                        break;
                                    }
                                }
                            } else if (typeNameLower.contains(DOUBLE_DATA_TYPE) ||
                                (srcTypedObject.getScale() != null && srcTypedObject.getScale() > 0 && srcTypedObject.getScale() <= 15))
                            {
                                for (String psn : possibleTypes.keySet()) {
                                    if (psn.contains(DOUBLE_DATA_TYPE)) {
                                        targetType = possibleTypes.get(psn);
                                        break;
                                    }
                                }
                            } else if ((INT_DATA_TYPE.equals(typeNameLower) && possibleTypes.get(INTEGER_DATA_TYPE) != null)
                                || (INTEGER_DATA_TYPE.equals(typeNameLower) && possibleTypes.get(INT_DATA_TYPE) != null))
                            {
                                // Let's use the closest int/integer synonym
                                targetType = INT_DATA_TYPE.equals(typeNameLower)
                                    ? possibleTypes.get(INTEGER_DATA_TYPE) : possibleTypes.get(INT_DATA_TYPE);

                            } else if (typeNameLower.contains(INT_DATA_TYPE)) {
                                // Ok, probably we do not have int/integer types, let's find something similar
                                for (String psn : possibleTypes.keySet()) {
                                    if (psn.contains(INT_DATA_TYPE)) {
                                        targetType = possibleTypes.get(psn);
                                        break;
                                    }
                                }
                            }
                        }
                    } else if (targetType == null && dataKind == DBPDataKind.STRING) {
                        if (typeNameLower.contains(TEXT_DATA_TYPE) || srcTypedObject.getMaxLength() <= 0) {
                            // Search data types ending with "text" for the source data type including "text".
                            // Like "longtext", "ntext", "mediumtext".
                            // Other string data types can also be turned into the "text" data type if they have no length.
                            if (possibleTypes.containsKey(TEXT_DATA_TYPE)) {
                                targetType = possibleTypes.get(TEXT_DATA_TYPE);
                            } else {
                                for (Map.Entry<String, DBSDataType> type : possibleTypes.entrySet()) {
                                    if (type.getKey().endsWith(TEXT_DATA_TYPE) && type.getValue().getDataKind() == DBPDataKind.STRING) {
                                        targetType = type.getValue();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                if (targetType == null) {
                    typeName = DBUtils.getDefaultDataTypeName(objectContainer, dataKind);
                    typeNameLower = typeName.toLowerCase(Locale.ENGLISH);
                    if (!possibleTypes.isEmpty()) {
                        targetType = possibleTypes.get(typeNameLower);
                    }
                }
                if (targetType == null && !possibleTypes.isEmpty()) {
                    targetType = possibleTypes.values().iterator().next();
                }
                if (targetType != null) {
                    typeName = targetType.getTypeName();
                }
            }
            if (dataType != null) {
                dataKind = dataType.getDataKind();
                // Datatype caches ignore case, but we probably should use it with the original case
                if (typeName.equalsIgnoreCase(dataType.getTypeName())) {
                    typeName = dataType.getTypeName();
                }
            }
        }

        // Get type modifiers from target datasource
        if (addModifiers && objectContainer != null) {
            DBPDataSource dataSource = objectContainer.getDataSource();
            SQLDialect dialect = dataSource.getSQLDialect();
            String modifiers = dialect.getColumnTypeModifiers(dataSource, srcTypedObject, typeName, dataKind);
            if (modifiers != null) {
                typeName += modifiers;
            } else if (VARCHAR_DATA_TYPE.equals(typeNameLower) || VARCHAR2_DATA_TYPE.equals(typeNameLower)) {
                // Default max length value for varchar column, because many databases do not support varchar without modifiers.
                // VARCHAR2 - is a special Oracle and Oracle-based databases case.
                typeName += "(" + DEFAULT_VARCHAR_LENGTH + ")";
            }
        }
        return typeName;
    }

    /**
     * Value range rank of an integer or floating point type, {@link #RANK_NONE} for other types.
     * JDBC FLOAT is double precision unless its (binary) precision says otherwise; PostgreSQL reports float4 as FLOAT.
     * A FLOAT without precision counts as double for a source (keeps every value) and as single precision for a target
     * (Oracle BINARY_FLOAT reports FLOAT without precision).
     */
    private static int getNumericRank(int typeID, @Nullable Integer precision, @Nullable String fullTypeName, boolean target) {
        int rank = switch (typeID) {
            case Types.TINYINT -> RANK_TINYINT;
            case Types.SMALLINT -> RANK_SMALLINT;
            case Types.INTEGER -> RANK_INTEGER;
            case Types.BIGINT -> RANK_BIGINT;
            case Types.REAL -> RANK_REAL;
            case Types.DOUBLE -> RANK_DOUBLE;
            case Types.FLOAT -> {
                if (precision == null || precision <= 0) {
                    yield target ? RANK_REAL : RANK_DOUBLE;
                }
                yield precision <= MAX_REAL_BINARY_PRECISION ? RANK_REAL : RANK_DOUBLE;
            }
            default -> RANK_NONE;
        };
        if (rank != RANK_NONE && rank < FLOAT_RANK_BASE
            && CommonUtils.notEmpty(fullTypeName).toLowerCase(Locale.ENGLISH).contains(UNSIGNED_MODIFIER)) {
            // An unsigned integer needs the next signed type (MySQL INT UNSIGNED holds 4294967295)
            rank++;
        }
        return rank;
    }

    private static boolean isDecimalWithoutPrecision(@NotNull DBSTypedObject type) {
        return (type.getTypeID() == Types.NUMERIC || type.getTypeID() == Types.DECIMAL) && CommonUtils.toInt(type.getPrecision()) <= 0;
    }

    private static boolean isSameRankGroup(int rank1, int rank2) {
        return rank1 != RANK_NONE && rank2 != RANK_NONE && (rank1 < FLOAT_RANK_BASE) == (rank2 < FLOAT_RANK_BASE);
    }

    private static boolean isNarrowerNumericType(@NotNull DBSDataType targetType, int sourceRank) {
        int targetRank = getNumericRank(targetType.getTypeID(), targetType.getPrecision(), targetType.getTypeName(), true);
        return isSameRankGroup(targetRank, sourceRank) && targetRank < sourceRank;
    }

    /**
     * Finds the target type for a numeric source whose type name the target does not know (or knows as a narrower
     * type): the smallest integer or floating point type holding the source range, the target decimal type for a
     * decimal source with explicit precision (Oracle NUMBER(12,2)), or double precision for a decimal source without
     * precision (Oracle NUMBER). Returns null when the target has no such type,
     * then the name based search of {@link #mapTargetDataType} applies.
     */
    @Nullable
    private static DBSDataType findNumericTargetType(
        @NotNull DBPDataTypeProvider dataTypeProvider,
        @NotNull DBSTypedObject source,
        int sourceRank
    ) {
        if (sourceRank == RANK_NONE) {
            int typeID = source.getTypeID();
            if (typeID != Types.NUMERIC && typeID != Types.DECIMAL) {
                return null;
            }
            if (isDecimalWithoutPrecision(source)) {
                // A decimal without precision (Oracle NUMBER, PostgreSQL numeric) holds any scale: a fixed-scale type
                // or an integer (the name search picked int) would cut the fraction, double precision keeps the value
                // approximately
                sourceRank = RANK_DOUBLE;
            } else {
                for (String name : DECIMAL_TYPE_NAMES) {
                    DBSDataType type = dataTypeProvider.getLocalDataType(name);
                    if (type != null && type.getDataKind() == DBPDataKind.NUMERIC) {
                        return type;
                    }
                }
                return null;
            }
        }
        DBSDataType bestType = null;
        int bestRank = Integer.MAX_VALUE;
        for (String name : sourceRank < FLOAT_RANK_BASE ? INTEGER_TYPE_NAMES : FLOAT_TYPE_NAMES) {
            DBSDataType type = dataTypeProvider.getLocalDataType(name);
            if (type == null || type.getDataKind() != DBPDataKind.NUMERIC) {
                continue;
            }
            int rank = getNumericRank(type.getTypeID(), type.getPrecision(), type.getTypeName(), true);
            if (isSameRankGroup(rank, sourceRank) && rank >= sourceRank && rank < bestRank) {
                bestType = type;
                bestRank = rank;
            }
        }
        return bestType;
    }

    /**
     * True for a date-time type that keeps a time zone or an offset: PostgreSQL timestamptz, Oracle
     * TIMESTAMP WITH (LOCAL) TIME ZONE, SQL Server datetimeoffset. Their drivers report different JDBC types, so the
     * name is checked too.
     */
    public static boolean isTimestampWithTimeZone(@NotNull DBSTypedObject type) {
        if (type.getDataKind() != DBPDataKind.DATETIME) {
            return false;
        }
        if (type.getTypeID() == Types.TIMESTAMP_WITH_TIMEZONE) {
            return true;
        }
        String name = CommonUtils.notEmpty(type.getTypeName()).toLowerCase(Locale.ENGLISH);
        return name.equals("timestamptz") || name.equals("datetimeoffset") || (name.startsWith("timestamp") && name.contains("time zone"));
    }

    /**
     * True for a date-time type with date and time parts (JDBC TIMESTAMP, which includes Oracle DATE), with or
     * without a time zone. Dialect type converters map these to their widest date-time type because the type with the
     * same name can be narrower (MySQL TIMESTAMP ends in 2038, SQL Server datetime has 1/300 s precision).
     */
    public static boolean isTimestamp(@NotNull DBSTypedObject type) {
        return type.getDataKind() == DBPDataKind.DATETIME && (type.getTypeID() == Types.TIMESTAMP || isTimestampWithTimeZone(type));
    }

    /**
     * Get name of the attribute

     * @param attribute to get name of
     * @return attribute name
     */
    public static String getAttributeName(@NotNull DBSAttributeBase attribute) {
        return getAttributeName(attribute, DBPAttributeReferencePurpose.UNSPECIFIED);
    }

    /**
     * Get name of the attribute

     * @param attribute to get name of
     * @param purpose of the name usage
     * @return attribute name
     */
    public static String getAttributeName(@NotNull DBSAttributeBase attribute, DBPAttributeReferencePurpose purpose) {
        if (attribute instanceof DBDAttributeBindingMeta bindingMeta) {
            // For top-level query bindings we need to use table columns name instead of alias.
            // For nested attributes we should use aliases

            // Entity attribute obtain commented because it broke complex attributes full name construction
            // We can't use entity attr because only particular query metadata contains real structure
            DBSEntityAttribute entityAttribute = bindingMeta.getEntityAttribute();
            if (entityAttribute != null) {
                attribute = entityAttribute;
            }
        }
        // Do not quote pseudo attribute name
        return DBUtils.isPseudoAttribute(attribute)
            ? attribute.getName()
            : DBUtils.getObjectFullName(attribute, DBPEvaluationContext.DML);
    }

    public static boolean isConnectedContainer(DBPObject parent) {
        return !(parent instanceof DBSInstanceLazy il) || il.isInstanceConnected();
    }

    public static List<DBSObject> getRelatedDBSEntities(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSObject dbsObject
    ) throws DBException {
        var result = new LinkedHashSet<DBSObject>();
        if (dbsObject instanceof DBSEntity mainEntity) {
            result.add(mainEntity);
            try {
                var associations = DBVUtils.getAllAssociations(monitor, mainEntity);
                for (var assoc : associations) {
                    var associatedEntity = assoc.getAssociatedEntity();
                    if (associatedEntity != null) {
                        result.add(associatedEntity);
                    }
                }
            } catch (DBException e) {
                log.warn("Can't load associations for " + mainEntity.getName(), e);
            }
            var references = DBVUtils.getAllReferences(monitor, mainEntity);
            for (var ref : references) {
                var parent = ref.getParentObject();
                result.add(parent);
            }
        } else if (dbsObject instanceof DBSObjectContainer container) {
            var children = container.getChildren(monitor);
            if (children != null) {
                for (var child : children) {
                    if (child instanceof DBSEntity resultChild) {
                        result.add(resultChild);
                    }
                }
            }
        }

        return result.stream().toList();
    }

    public static boolean isSchemasSupported(@NotNull DBPDataSourceContainer dataSourceContainer) {
        DBCExecutionContext defaultContext = DBUtils.getDefaultContext(dataSourceContainer, false);
        if (defaultContext != null) {
            DBCExecutionContextDefaults<?,?> contextDefaults = defaultContext.getContextDefaults();
            if (contextDefaults != null) {
                if (contextDefaults.getDefaultSchema() != null || contextDefaults.getDefaultCatalog() != null ||
                    contextDefaults.supportsSchemaChange() || contextDefaults.supportsCatalogChange()
                ) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    public static DBSSchema getObjectSchema(@NotNull DBSObject dbsObject) {
        if (dbsObject instanceof DBSSchema schema) {
            return schema;
        }
        DBSObject parent = dbsObject;
        while (parent != null) {
            if (parent instanceof DBSSchema schema) {
                return schema;
            }
            parent = parent.getParentObject();
        }
        return null;
    }

    /**
     * Retrieves the schema name associated with the provided database object.
     */
    @Nullable
    public static String getObjectSchemaName(@NotNull DBSObject dbsObject) {
        DBSSchema schema = getObjectSchema(dbsObject);
        return schema == null ? null : schema.getName();
    }

    @Nullable
    public static DBSCatalog getObjectCatalog(@NotNull DBSObject dbsObject) {
        if (dbsObject instanceof DBSCatalog catalog) {
            return catalog;
        } else if (dbsObject instanceof DBSSchema) {
            DBSObject parent = dbsObject.getParentObject();
            if (parent instanceof DBSCatalog catalog) {
                return catalog;
            }
        }

        DBSObject parent = dbsObject;
        while (parent != null) {
            if (parent instanceof DBSCatalog catalog) {
                return catalog;
            }
            parent = parent.getParentObject();
        }

        return null;
    }

    /**
     * Retrieves the catalog name associated with the provided database object.
     */
    @Nullable
    public static String getObjectCatalogName(@NotNull DBSObject dbsObject) {
        DBSCatalog catalog = getObjectCatalog(dbsObject);
        return catalog == null ? null : catalog.getName();
    }

    public static boolean isPrimaryKey(@NotNull DBSTableColumn column) {
        var monitor = new VoidProgressMonitor();
        try {
            return matchesColumnInRefs(
                monitor,
                column,
                referrers(column.getParentObject().getConstraints(monitor), DBSEntityConstraintType.PRIMARY_KEY)
            );
        } catch (DBException e) {
            log.debug("Error reading primary key constraints for column: " + column.getName(), e);
            return false;
        }
    }

    public static boolean isForeignKey(@NotNull DBSTableColumn column) {
        var monitor = new VoidProgressMonitor();
        try {
            return matchesColumnInRefs(
                monitor,
                column,
                referrers(column.getParentObject().getAssociations(monitor), DBSEntityConstraintType.FOREIGN_KEY)
            );
        } catch (DBException e) {
            log.debug("Error reading foreign key associations for column: " + column.getName(), e);
            return false;
        }
    }

    @NotNull
    public static List<DBSEntityAssociation> readReferences(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSEntity singleSource,
        @NotNull Function<DBSEntityAttribute, DBDAttributeBinding> attributeBindingFunction
    ) throws DBException {
        List<DBSEntityAssociation> references = new ArrayList<>();
        monitor.beginTask("Read references", 1);
        Collection<? extends DBSEntityAssociation> refs = DBVUtils.getAllReferences(monitor, singleSource);
        {
            monitor.beginTask("Check references", refs.size());
            for (DBSEntityAssociation ref : refs) {
                if (monitor.isCanceled()) {
                    return references;
                }
                monitor.subTask("Check references " + ref.getName());
                boolean allMatch = true;
                DBSEntityConstraint ownConstraint = ref.getReferencedConstraint();
                if (ownConstraint instanceof DBSEntityReferrer) {
                    List<? extends DBSEntityAttributeRef> attributeReferences = ((DBSEntityReferrer) ownConstraint).getAttributeReferences(
                        monitor);
                    if (attributeReferences != null) {
                        for (DBSEntityAttributeRef ownAttrRef : attributeReferences) {
                            if (attributeBindingFunction.apply(ownAttrRef.getAttribute()) == null) {
                                // Attribute is not in the list - skip this association
                                allMatch = false;
                                break;
                            }
                        }
                    } else {
                        allMatch = false;
                    }
                }
                if (allMatch) {
                    references.add(ref);
                }
                monitor.worked(1);
            }
        }
        monitor.done();
        return references;
    }

    @NotNull
    public static List<DBSEntityAssociation> readAssociations(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSEntity singleSource,
        @NotNull Function<DBSEntityAttribute, DBDAttributeBinding> attributeBindingFunction
    ) throws DBException {
        List<DBSEntityAssociation> associations = new ArrayList<>();
        monitor.beginTask("Read associations", 1);
        Collection<? extends DBSEntityAssociation> fks = singleSource.getAssociations(monitor);
        if (fks != null) {
            monitor.beginTask("Check associations", fks.size());
            for (DBSEntityAssociation fk : fks) {
                if (monitor.isCanceled()) {
                    return associations;
                }
                monitor.subTask("Check association " + fk.getName());
                boolean allMatch = true;
                if (fk instanceof DBSEntityReferrer && fk.getReferencedConstraint() != null) {
                    for (DBSEntityAttributeRef ownAttr : ((DBSEntityReferrer) fk).getAttributeReferences(monitor)) {
                        if (attributeBindingFunction.apply(ownAttr.getAttribute()) == null) {
                            // Attribute is not in the list - skip this association
                            allMatch = false;
                            break;
                        }
                    }
                }
                if (allMatch) {
                    associations.add(fk);
                }
                monitor.worked(1);
            }
        }
        monitor.done();
        return associations;
    }

    @NotNull
    public static DBSEntityAssociation findForwardAssociationByName(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSEntity sourceEntity,
        @NotNull String associationName
    ) throws DBException {
        Collection<? extends DBSEntityAssociation> foreignKeys = sourceEntity.getAssociations(monitor);
        if (foreignKeys != null) {
            for (DBSEntityAssociation foreignKey : foreignKeys) {
                if (CommonUtils.equalObjects(associationName, foreignKey.getName())) {
                    return foreignKey;
                }
            }
        }
        throw new DBException("Association '" + associationName + "' not found in entity [" + sourceEntity.getName() + "]");
    }

    @NotNull
    public static DBSEntityAssociation findReverseAssociationByName(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSEntity sourceEntity,
        @NotNull String associationName
    ) throws DBException {
        for (DBSEntityAssociation reverseRef : DBVUtils.getAllReferences(monitor, sourceEntity)) {
            if (CommonUtils.equalObjects(associationName, reverseRef.getName())) {
                return reverseRef;
            }
        }
        throw new DBException("Reverse reference '" + associationName + "' not found in entity [" + sourceEntity.getName() + "]");
    }

    private static boolean matchesColumnInRefs(
        @Nullable DBRProgressMonitor monitor,
        @NotNull DBSTableColumn column,
        @NotNull Stream<DBSEntityReferrer> referrers
    ) {
        return referrers
            .flatMap(ref -> safeRefs(monitor, ref))
            .anyMatch(r -> r.getAttribute() == column);
    }

    @NotNull
    private static Stream<? extends DBSEntityAttributeRef> safeRefs(
        @Nullable DBRProgressMonitor monitor,
        @NotNull DBSEntityReferrer ref
    ) {
        try {
            List<? extends DBSEntityAttributeRef> refs = ref.getAttributeReferences(monitor);
            return refs == null ? Stream.empty() : refs.stream();
        } catch (DBException e) {
            log.debug("Failed to read attribute references for constraint: " + ref.getName(), e);
            return Stream.empty();
        }
    }

    @NotNull
    private static Stream<DBSEntityReferrer> referrers(
        @Nullable Collection<? extends DBSEntityConstraint> refs,
        @NotNull DBSEntityConstraintType filter)  {

        if (refs == null || refs.isEmpty()) {
            return Stream.empty();
        }
        return refs.stream()
            .filter(a -> a.getConstraintType() == filter)
            .filter(DBSEntityReferrer.class::isInstance)
            .map(DBSEntityReferrer.class::cast);
    }

}
