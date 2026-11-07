/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.tools.compare.schema;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataTypeProvider;
import com.seizia.chromite.model.DBPOverloadedObject;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.DBPSystemObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNModel;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSDataType;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAssociation;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSEntityAttributeRef;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.DBSEntityReferrer;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.DBStructUtils;
import com.seizia.chromite.model.struct.rdb.DBSForeignKeyModifyRule;
import com.seizia.chromite.model.struct.rdb.DBSProcedure;
import com.seizia.chromite.model.struct.rdb.DBSSequence;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.model.struct.rdb.DBSTableForeignKey;
import com.seizia.chromite.model.struct.rdb.DBSTableIndex;
import com.seizia.chromite.model.struct.rdb.DBSTableIndexColumn;
import com.seizia.chromite.model.struct.rdb.DBSTrigger;
import com.seizia.chromite.model.struct.rdb.DBSView;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Compares the objects of two schemas (or databases), on the same or different connections.
 *
 * 설계:
 * <ul>
 *     <li>읽기: 컨테이너의 탐색기 노드를 먼저 새로 고치고(Object Explorer의 F5와 같음, 메타데이터 캐시를 비움) 그 아래 폴더를
 *     내려가며 객체를 모은다. 드라이버마다 시퀀스·루틴을 돌려주는 메서드가 달라도(공통 인터페이스 없음) 탐색기 구조는
 *     {@code plugin.xml}로 모든 드라이버에 있으므로 100개가 넘는 드라이버에 같은 코드가 돈다. 객체 종류는 모델 인터페이스로 나눈다
 *     ({@link DBSTable}, {@link DBSView}, {@link DBSProcedure}, {@link DBSSequence}). 시스템 객체와 숨은 객체는 뺀다.
 *     테이블의 열·키·외래 키·인덱스·트리거는 모델 API로 읽는다({@link DBSEntity#getAttributes} 등, 테이블 디자이너와 같음).</li>
 *     <li>짝짓기: 이름이 같은 것, 남은 것 중 대소문자만 다른 것(다른 종류 DB: PostgreSQL {@code users} ↔ Oracle {@code USERS}).
 *     오버로드된 루틴은 인자 목록을 붙인 이름({@link DBPOverloadedObject})으로 짝짓는다. 외래 키는 이름보다 정의(열 → 참조 테이블(열))로,
 *     인덱스는 이름 다음에 정의(UNIQUE, 열)로 짝짓는다. 이름은 DB가 자동으로 붙이는 경우가 많기 때문이다(SQL Server {@code PK__…}).
 *     기본 키는 테이블에 하나라 이름을 보지 않고 열만 비교한다.</li>
 *     <li>열 비교: 형식은 대상 DB의 형식으로 바꾼 원본 형식(데이터 옮기기와 같은 {@link DBStructUtils#mapTargetDataType})과 대상 형식을
 *     비교한다. 그래서 같은 종류 DB에서는 원본 형식 그대로, 다른 종류에서는 "원본 열로 대상에 만들 형식"과 비교된다. NULL 허용, 기본값도
 *     비교한다. 기본값은 바깥 괄호(SQL Server {@code ((0))}), PostgreSQL 형 변환({@code ::text}), 공백을 걷어 비교하고, 다른 종류 DB에서는
 *     식별 열·자동 생성 열의 기본값을 비교하지 않는다(시퀀스 기본값과 IDENTITY는 같은 뜻이다).</li>
 *     <li>정의 비교(뷰·루틴·트리거·시퀀스): 같은 종류 DB일 때만 한다. 원본 정의의 스키마 이름을 대상 이름으로 바꾸고({@link SchemaNameMapper})
 *     공백을 하나로 모아 비교한다. 다른 종류 DB 사이에서는 문법이 달라 비교하지 않고 "비교 안 함" 개수로 센다.
 *     PostgreSQL 같은 서버는 정의를 정규화해 돌려주므로 서버 버전이 다르면 같은 뷰도 다르게 보일 수 있다.</li>
 * </ul>
 * 모든 메서드는 작업 스레드에서 부른다(DB를 읽는다).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/schema-compare.md)
 */
public final class SchemaComparer {

    private static final Log log = Log.getLog(SchemaComparer.class);

    /**
     * Folder levels walked below the container (SQL Server: database &gt; Programmability &gt; Stored Procedures).
     */
    private static final int MAX_FOLDER_DEPTH = 3;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+"); //$NON-NLS-1$
    /**
     * PostgreSQL casts in defaults ({@code 'a'::character varying}, {@code '{}'::integer[]}).
     */
    private static final Pattern CAST = Pattern.compile("::\"?[A-Za-z_][\\w ]*\"?(\\([^)]*\\))?(\\[])*"); //$NON-NLS-1$
    private static final Pattern LITERAL = Pattern.compile("-?\\d+(\\.\\d+)?|'([^']|'')*'"); //$NON-NLS-1$

    private record Pair<T>(@Nullable T source, @Nullable T target) {
    }

    private final DBRProgressMonitor monitor;
    private final DBSObjectContainer source;
    private final DBSObjectContainer target;
    private final boolean sameDialect;
    private final SchemaNameMapper nameMapper;
    private final Map<DBSEntity, DBSEntity> tablePairs = new IdentityHashMap<>();
    private int identical;
    private int notCompared;

    private SchemaComparer(@NotNull DBRProgressMonitor monitor, @NotNull DBSObjectContainer source, @NotNull DBSObjectContainer target) {
        this.monitor = monitor;
        this.source = source;
        this.target = target;
        DBPDataSource sourceDataSource = source.getDataSource();
        DBPDataSource targetDataSource = target.getDataSource();
        this.sameDialect = sourceDataSource != null && targetDataSource != null && sourceDataSource.getClass() == targetDataSource.getClass();
        this.nameMapper = new SchemaNameMapper(source, target, sameDialect);
    }

    /**
     * Reads both containers again from the database and compares them.
     */
    @NotNull
    public static SchemaCompareResult compare(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSObjectContainer source,
        @NotNull DBSObjectContainer target
    ) throws DBException {
        DBSObjectContainer refreshedSource = refresh(monitor, source);
        DBSObjectContainer refreshedTarget = refresh(monitor, target);
        return new SchemaComparer(monitor, refreshedSource, refreshedTarget).run();
    }

    /**
     * Refreshes the container's navigator node (the metadata caches below it), as F5 in Object Explorer; the refreshed
     * node may hold a new object.
     */
    @NotNull
    private static DBSObjectContainer refresh(@NotNull DBRProgressMonitor monitor, @NotNull DBSObjectContainer container) throws DBException {
        DBNDatabaseNode node = getNavigatorModel().getNodeByObject(monitor, container, true);
        if (node == null) {
            return container;
        }
        DBNNode refreshed = node.refreshNode(monitor, SchemaComparer.class);
        if (refreshed instanceof DBNDatabaseNode databaseNode && databaseNode.getObject() instanceof DBSObjectContainer refreshedContainer) {
            return refreshedContainer;
        }
        return container;
    }

    @NotNull
    private static DBNModel getNavigatorModel() {
        return DBWorkbench.getPlatform().getNavigatorModel();
    }

    @NotNull
    private SchemaCompareResult run() throws DBException {
        monitor.beginTask("Compare schemas", 3);
        monitor.subTask("Read " + source.getName());
        Map<SchemaCompareItem.Kind, List<DBSObject>> sourceObjects = readObjects(source);
        monitor.worked(1);
        monitor.subTask("Read " + target.getName());
        Map<SchemaCompareItem.Kind, List<DBSObject>> targetObjects = readObjects(target);
        monitor.worked(1);

        List<SchemaCompareItem> items = new ArrayList<>();
        for (SchemaCompareItem.Kind kind : List.of(SchemaCompareItem.Kind.TABLE, SchemaCompareItem.Kind.VIEW,
            SchemaCompareItem.Kind.ROUTINE, SchemaCompareItem.Kind.SEQUENCE)) {
            List<Pair<DBSObject>> pairs = match(sourceObjects.get(kind), targetObjects.get(kind),
                List.of(SchemaComparer::getObjectKey, o -> getObjectKey(o).toLowerCase(Locale.ROOT)));
            for (Pair<DBSObject> pair : pairs) {
                if (monitor.isCanceled()) {
                    break;
                }
                SchemaCompareItem item = compareObjects(kind, pair.source(), pair.target());
                if (item != null) {
                    items.add(item);
                }
            }
        }
        monitor.worked(1);
        monitor.done();
        return new SchemaCompareResult(source, target, sameDialect, items, tablePairs, nameMapper, identical, notCompared);
    }

    //////////////////////////////////////////////////////////
    // Reading

    @NotNull
    private Map<SchemaCompareItem.Kind, List<DBSObject>> readObjects(@NotNull DBSObjectContainer container) throws DBException {
        Map<SchemaCompareItem.Kind, List<DBSObject>> objects = new EnumMap<>(SchemaCompareItem.Kind.class);
        for (SchemaCompareItem.Kind kind : SchemaCompareItem.Kind.values()) {
            objects.put(kind, new ArrayList<>());
        }
        Set<DBSObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        DBNDatabaseNode node = getNavigatorModel().getNodeByObject(monitor, container, true);
        if (node != null) {
            collect(node, objects, seen, 0);
        } else {
            // No navigator node (should not happen for a container picked in Object Explorer): its direct children
            for (DBSObject child : CommonUtils.safeCollection(container.getChildren(monitor))) {
                addObject(child, objects, seen);
            }
        }
        return objects;
    }

    private void collect(
        @NotNull DBNDatabaseNode node,
        @NotNull Map<SchemaCompareItem.Kind, List<DBSObject>> objects,
        @NotNull Set<DBSObject> seen,
        int depth
    ) throws DBException {
        DBNDatabaseNode[] children = node.getChildren(monitor);
        if (children == null) {
            return;
        }
        for (DBNDatabaseNode child : children) {
            if (monitor.isCanceled()) {
                return;
            }
            if (child instanceof DBNDatabaseFolder) {
                if (depth < MAX_FOLDER_DEPTH) {
                    collect(child, objects, seen, depth + 1);
                }
            } else {
                // Other items (data types, packages, ...) are not compared; a table's parts are read with the model API
                addObject(child.getObject(), objects, seen);
            }
        }
    }

    private static void addObject(
        @Nullable DBSObject object,
        @NotNull Map<SchemaCompareItem.Kind, List<DBSObject>> objects,
        @NotNull Set<DBSObject> seen
    ) {
        SchemaCompareItem.Kind kind = classify(object);
        if (kind != null && !isHidden(object) && seen.add(object)) {
            objects.get(kind).add(object);
        }
    }

    @Nullable
    private static SchemaCompareItem.Kind classify(@Nullable DBSObject object) {
        // Before tables: a PostgreSQL sequence is a table too (PostgreSequence extends PostgreTableBase)
        if (object instanceof DBSSequence) {
            return SchemaCompareItem.Kind.SEQUENCE;
        }
        if (object instanceof DBSView || object instanceof DBSTable table && table.isView()) {
            return SchemaCompareItem.Kind.VIEW;
        }
        if (object instanceof DBSTable) {
            return SchemaCompareItem.Kind.TABLE;
        }
        if (object instanceof DBSProcedure) {
            return SchemaCompareItem.Kind.ROUTINE;
        }
        return null;
    }

    private static boolean isHidden(@Nullable Object object) {
        return object == null || DBUtils.isHiddenObject(object) || object instanceof DBPSystemObject systemObject && systemObject.isSystem();
    }

    /**
     * The name objects are matched by: routines with their argument types (overloads share a name).
     */
    @NotNull
    private static String getObjectKey(@NotNull DBSObject object) {
        if (object instanceof DBPOverloadedObject overloaded && !CommonUtils.isEmpty(overloaded.getOverloadedName())) {
            return overloaded.getOverloadedName();
        }
        return object.getName();
    }

    /**
     * Pairs objects by the keys, in order: a key is tried on the objects the earlier keys left unmatched. Sources come
     * first in their order, then the targets without a source.
     */
    @NotNull
    private static <T> List<Pair<T>> match(
        @NotNull List<? extends T> sources,
        @NotNull List<? extends T> targets,
        @NotNull List<Function<T, String>> keys
    ) {
        Map<T, T> matched = new IdentityHashMap<>();
        List<T> unmatchedTargets = new ArrayList<>(targets);
        for (Function<T, String> key : keys) {
            Map<String, Deque<T>> byKey = new HashMap<>();
            for (T target : unmatchedTargets) {
                String value = key.apply(target);
                if (value != null) {
                    byKey.computeIfAbsent(value, k -> new ArrayDeque<>()).add(target);
                }
            }
            for (T source : sources) {
                if (matched.containsKey(source)) {
                    continue;
                }
                String value = key.apply(source);
                Deque<T> candidates = value == null ? null : byKey.get(value);
                if (candidates != null && !candidates.isEmpty()) {
                    T target = candidates.poll();
                    matched.put(source, target);
                    unmatchedTargets.remove(target);
                }
            }
        }
        List<Pair<T>> pairs = new ArrayList<>();
        for (T source : sources) {
            pairs.add(new Pair<>(source, matched.get(source)));
        }
        for (T target : unmatchedTargets) {
            pairs.add(new Pair<>(null, target));
        }
        return pairs;
    }

    //////////////////////////////////////////////////////////
    // Top-level objects

    @Nullable
    private SchemaCompareItem compareObjects(
        @NotNull SchemaCompareItem.Kind kind,
        @Nullable DBSObject sourceObject,
        @Nullable DBSObject targetObject
    ) throws DBException {
        if (targetObject == null) {
            return new SchemaCompareItem(kind, getObjectKey(Objects.requireNonNull(sourceObject)), SchemaCompareItem.Status.ONLY_IN_SOURCE, sourceObject, null);
        }
        if (sourceObject == null) {
            return new SchemaCompareItem(kind, getObjectKey(targetObject), SchemaCompareItem.Status.ONLY_IN_TARGET, null, targetObject);
        }
        SchemaCompareItem item = new SchemaCompareItem(kind, getObjectKey(targetObject), SchemaCompareItem.Status.DIFFERENT, sourceObject, targetObject);
        switch (kind) {
            case TABLE -> {
                tablePairs.put((DBSEntity) sourceObject, (DBSEntity) targetObject);
                compareTable(item, (DBSEntity) sourceObject, (DBSEntity) targetObject);
            }
            case SEQUENCE -> compareSequence(item, (DBSSequence) sourceObject, (DBSSequence) targetObject);
            default -> compareDefinition(item, sourceObject, targetObject);
        }
        if (item.getChildren().isEmpty() && item.getDifferences().isEmpty()) {
            return null;
        }
        return item;
    }

    /**
     * Views, routines and triggers: their source text, between the same database type only.
     */
    private void compareDefinition(@NotNull SchemaCompareItem item, @NotNull DBSObject sourceObject, @NotNull DBSObject targetObject) throws DBException {
        if (!sameDialect) {
            notCompared++;
            return;
        }
        String sourceText = readDefinition(monitor, sourceObject);
        String targetText = readDefinition(monitor, targetObject);
        if (sourceText == null || targetText == null) {
            notCompared++;
            return;
        }
        if (normalizeDefinition(nameMapper.remap(sourceText)).equals(normalizeDefinition(targetText))) {
            identical++;
        } else {
            item.addDifference(SchemaCompareItem.Property.DEFINITION, null, null);
        }
    }

    private void compareSequence(@NotNull SchemaCompareItem item, @NotNull DBSSequence sourceSequence, @NotNull DBSSequence targetSequence) throws DBException {
        if (!sameDialect) {
            notCompared++;
            return;
        }
        // Some drivers read the values lazily with a monitor (PostgreSequence.getAdditionalInfo) and the DBSSequence
        // getters return unread zeros; making the definition reads them, as the View DDL command does
        readDefinition(monitor, sourceSequence);
        readDefinition(monitor, targetSequence);
        // The current value is data, not structure
        compareValue(item, SchemaCompareItem.Property.INCREMENT, sourceSequence.getIncrementBy(), targetSequence.getIncrementBy());
        compareValue(item, SchemaCompareItem.Property.MIN_VALUE, sourceSequence.getMinValue(), targetSequence.getMinValue());
        compareValue(item, SchemaCompareItem.Property.MAX_VALUE, sourceSequence.getMaxValue(), targetSequence.getMaxValue());
        if (item.getDifferences().isEmpty()) {
            identical++;
        }
    }

    private static void compareValue(@NotNull SchemaCompareItem item, @NotNull SchemaCompareItem.Property property, @Nullable Number sourceValue, @Nullable Number targetValue) {
        String sourceText = sourceValue == null ? null : sourceValue.toString();
        String targetText = targetValue == null ? null : targetValue.toString();
        if (!Objects.equals(sourceText, targetText)) {
            item.addDifference(property, sourceText, targetText);
        }
    }

    /**
     * The object's CREATE statement as the "View DDL" command shows it, or null if the object has none.
     */
    @Nullable
    public static String readDefinition(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object) throws DBException {
        if (!(object instanceof DBPScriptObject scriptObject)) {
            return null;
        }
        String text = scriptObject.getObjectDefinitionText(monitor, new HashMap<>());
        return CommonUtils.isEmptyTrimmed(text) ? null : text.strip();
    }

    @NotNull
    static String normalizeDefinition(@NotNull String text) {
        String normalized = WHITESPACE.matcher(text).replaceAll(" ").strip();
        while (normalized.endsWith(";")) { //$NON-NLS-1$
            normalized = normalized.substring(0, normalized.length() - 1).strip();
        }
        return normalized;
    }

    //////////////////////////////////////////////////////////
    // Tables

    private void compareTable(@NotNull SchemaCompareItem item, @NotNull DBSEntity sourceTable, @NotNull DBSEntity targetTable) throws DBException {
        compareColumns(item, sourceTable, targetTable);
        comparePrimaryKeys(item, sourceTable, targetTable);
        compareForeignKeys(item, sourceTable, targetTable);
        if (sourceTable instanceof DBSTable sourceRdb && targetTable instanceof DBSTable targetRdb) {
            compareIndexes(item, sourceTable, targetTable, sourceRdb, targetRdb);
            compareTriggers(item, sourceRdb, targetRdb);
        }
        if (item.getChildren().isEmpty()) {
            identical++;
        }
    }

    @NotNull
    static List<DBSEntityAttribute> getColumns(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity table) throws DBException {
        List<DBSEntityAttribute> columns = new ArrayList<>();
        for (DBSEntityAttribute attribute : CommonUtils.safeCollection(table.getAttributes(monitor))) {
            if (!DBUtils.isHiddenObject(attribute)) {
                columns.add(attribute);
            }
        }
        return columns;
    }

    private void compareColumns(@NotNull SchemaCompareItem item, @NotNull DBSEntity sourceTable, @NotNull DBSEntity targetTable) throws DBException {
        List<Pair<DBSEntityAttribute>> pairs = match(getColumns(monitor, sourceTable), getColumns(monitor, targetTable),
            List.of(DBSObject::getName, a -> a.getName().toLowerCase(Locale.ROOT)));
        for (Pair<DBSEntityAttribute> pair : pairs) {
            DBSEntityAttribute sourceColumn = pair.source();
            DBSEntityAttribute targetColumn = pair.target();
            if (targetColumn == null) {
                item.addChild(new SchemaCompareItem(SchemaCompareItem.Kind.COLUMN, Objects.requireNonNull(sourceColumn).getName(),
                    SchemaCompareItem.Status.ONLY_IN_SOURCE, sourceColumn, null));
                continue;
            }
            if (sourceColumn == null) {
                item.addChild(new SchemaCompareItem(SchemaCompareItem.Kind.COLUMN, targetColumn.getName(),
                    SchemaCompareItem.Status.ONLY_IN_TARGET, null, targetColumn));
                continue;
            }
            SchemaCompareItem child = new SchemaCompareItem(SchemaCompareItem.Kind.COLUMN, targetColumn.getName(),
                SchemaCompareItem.Status.DIFFERENT, sourceColumn, targetColumn);
            String sourceType = getTargetType(sourceColumn);
            String targetType = targetColumn.getFullTypeName();
            if (!isSameType(sourceType, targetType)) {
                child.addDifference(SchemaCompareItem.Property.TYPE,
                    sameDialect ? sourceType : sourceColumn.getFullTypeName() + " → " + sourceType, targetType); //$NON-NLS-1$
            }
            if (sourceColumn.isRequired() != targetColumn.isRequired()) {
                child.addDifference(SchemaCompareItem.Property.NULLABLE, nullability(sourceColumn), nullability(targetColumn));
            }
            String sourceDefault = sourceColumn.getDefaultValue();
            // Between database types, a sequence default and IDENTITY mean the same (auto-generated values), and an
            // expression of the source (now()) can't be written for the target: only literal defaults are compared
            boolean compareDefault = sameDialect || !sourceColumn.isAutoGenerated() && !targetColumn.isAutoGenerated()
                && (CommonUtils.isEmptyTrimmed(sourceDefault) || toLiteral(sourceDefault) != null);
            if (compareDefault && !normalizeDefault(sourceDefault == null ? null : nameMapper.remap(sourceDefault))
                .equals(normalizeDefault(targetColumn.getDefaultValue()))) {
                child.addDifference(SchemaCompareItem.Property.DEFAULT, sourceDefault, targetColumn.getDefaultValue());
            }
            if (!child.getDifferences().isEmpty()) {
                item.addChild(child);
            }
        }
    }

    /**
     * The type a column of the source gets in the target: the same type in the same database type, else the type the
     * data transfer maps it to.
     */
    @NotNull
    private String getTargetType(@NotNull DBSEntityAttribute sourceColumn) {
        return sameDialect ? sourceColumn.getFullTypeName() : DBStructUtils.mapTargetDataType(target, sourceColumn, true);
    }

    /**
     * True when two types are the same in the target. Between database types the mapped type may be another name of the
     * target's type (PostgreSQL {@code int} and {@code int4}, {@code serial4} for an int column with a sequence): both
     * names are then looked up in the target's data types and match by JDBC type and data kind with the same modifiers.
     */
    private boolean isSameType(@NotNull String sourceType, @NotNull String targetType) {
        if (normalizeType(sourceType).equals(normalizeType(targetType))) {
            return true;
        }
        if (sameDialect || !getModifiers(sourceType).equals(getModifiers(targetType))) {
            return false;
        }
        DBPDataTypeProvider provider = DBUtils.getAdapter(DBPDataTypeProvider.class, target.getDataSource());
        if (provider == null) {
            return false;
        }
        DBSDataType sourceDataType = provider.getLocalDataType(SQLUtils.stripColumnTypeModifiers(sourceType));
        DBSDataType targetDataType = provider.getLocalDataType(SQLUtils.stripColumnTypeModifiers(targetType));
        return sourceDataType != null && targetDataType != null && sourceDataType.getTypeID() == targetDataType.getTypeID()
            && sourceDataType.getDataKind() == targetDataType.getDataKind();
    }

    /**
     * The normalized text in parentheses of a type ({@code (12,2)}), or "".
     */
    @NotNull
    private static String getModifiers(@NotNull String type) {
        String normalized = normalizeType(type);
        int start = normalized.indexOf('(');
        return start < 0 ? "" : normalized.substring(start); //$NON-NLS-1$
    }

    @NotNull
    private static String nullability(@NotNull DBSEntityAttribute column) {
        return column.isRequired() ? "NOT NULL" : "NULL"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A type without spaces and letter case; DECIMAL is NUMERIC (synonyms in the SQL standard and in the databases that
     * have both; the data transfer maps PostgreSQL numeric to SQL Server numeric where the table has decimal).
     */
    @NotNull
    static String normalizeType(@Nullable String type) {
        if (type == null) {
            return ""; //$NON-NLS-1$
        }
        String normalized = WHITESPACE.matcher(type).replaceAll("").toLowerCase(Locale.ROOT); //$NON-NLS-1$
        return normalized.startsWith("decimal") ? "numeric" + normalized.substring("decimal".length()) : normalized; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The default as a literal any database reads the same way (a number or a quoted string, without casts, outer
     * parentheses and a national prefix), or null for an expression.
     */
    @Nullable
    static String toLiteral(@NotNull String value) {
        String text = CAST.matcher(value.strip()).replaceAll("").strip(); //$NON-NLS-1$
        while (text.length() >= 2 && text.charAt(0) == '(' && text.charAt(text.length() - 1) == ')' && isWrapped(text)) {
            text = text.substring(1, text.length() - 1).strip();
        }
        if (text.length() > 1 && (text.charAt(0) == 'N' || text.charAt(0) == 'n') && text.charAt(1) == '\'') {
            text = text.substring(1);
        }
        return LITERAL.matcher(text).matches() ? text : null;
    }

    /**
     * A default without the parts databases add around the same value: outer parentheses (SQL Server {@code ((0))}),
     * PostgreSQL casts, a national string prefix ({@code N'a'}), spaces and letter case.
     */
    @NotNull
    static String normalizeDefault(@Nullable String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        String text = CAST.matcher(value.strip()).replaceAll(""); //$NON-NLS-1$
        text = WHITESPACE.matcher(text).replaceAll(" ").strip(); //$NON-NLS-1$
        while (text.length() >= 2 && text.charAt(0) == '(' && text.charAt(text.length() - 1) == ')' && isWrapped(text)) {
            text = text.substring(1, text.length() - 1).strip();
        }
        if (text.length() > 1 && (text.charAt(0) == 'N' || text.charAt(0) == 'n') && text.charAt(1) == '\'') {
            text = text.substring(1);
        }
        if (text.equalsIgnoreCase("NULL")) { //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }
        return text.toLowerCase(Locale.ROOT);
    }

    /**
     * True when the first parenthesis closes at the last character (not "(a) + (b)").
     */
    private static boolean isWrapped(@NotNull String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && i < text.length() - 1) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    @Nullable
    static DBSEntityConstraint getPrimaryKey(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity table) throws DBException {
        for (DBSEntityConstraint constraint : CommonUtils.safeCollection(table.getConstraints(monitor))) {
            if (constraint.getConstraintType() == DBSEntityConstraintType.PRIMARY_KEY && constraint instanceof DBSEntityReferrer
                && !DBUtils.isHiddenObject(constraint)) {
                return constraint;
            }
        }
        return null;
    }

    /**
     * Column names of a key, in key order.
     */
    @NotNull
    static List<String> getColumnNames(@NotNull DBRProgressMonitor monitor, @Nullable DBSEntityConstraint key) throws DBException {
        List<String> names = new ArrayList<>();
        if (key instanceof DBSEntityReferrer referrer) {
            for (DBSEntityAttributeRef reference : CommonUtils.safeCollection(referrer.getAttributeReferences(monitor))) {
                DBSEntityAttribute attribute = reference.getAttribute();
                names.add(attribute != null ? attribute.getName() : reference instanceof DBSObject object ? object.getName() : "?"); //$NON-NLS-1$
            }
        }
        return names;
    }

    private void comparePrimaryKeys(@NotNull SchemaCompareItem item, @NotNull DBSEntity sourceTable, @NotNull DBSEntity targetTable) throws DBException {
        DBSEntityConstraint sourceKey = getPrimaryKey(monitor, sourceTable);
        DBSEntityConstraint targetKey = getPrimaryKey(monitor, targetTable);
        if (sourceKey == null && targetKey == null) {
            return;
        }
        if (targetKey == null) {
            item.addChild(new SchemaCompareItem(SchemaCompareItem.Kind.PRIMARY_KEY, sourceKey.getName(), SchemaCompareItem.Status.ONLY_IN_SOURCE, sourceKey, null));
        } else if (sourceKey == null) {
            item.addChild(new SchemaCompareItem(SchemaCompareItem.Kind.PRIMARY_KEY, targetKey.getName(), SchemaCompareItem.Status.ONLY_IN_TARGET, null, targetKey));
        } else {
            String sourceColumns = String.join(", ", getColumnNames(monitor, sourceKey)); //$NON-NLS-1$
            String targetColumns = String.join(", ", getColumnNames(monitor, targetKey)); //$NON-NLS-1$
            if (!sourceColumns.equalsIgnoreCase(targetColumns)) {
                SchemaCompareItem child = new SchemaCompareItem(SchemaCompareItem.Kind.PRIMARY_KEY, targetKey.getName(),
                    SchemaCompareItem.Status.DIFFERENT, sourceKey, targetKey);
                child.addDifference(SchemaCompareItem.Property.COLUMNS, sourceColumns, targetColumns);
                item.addChild(child);
            }
        }
    }

    @NotNull
    static List<DBSEntityAssociation> getForeignKeys(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity table) throws DBException {
        List<DBSEntityAssociation> keys = new ArrayList<>();
        for (DBSEntityAssociation association : CommonUtils.safeCollection(table.getAssociations(monitor))) {
            if (association.getConstraintType() == DBSEntityConstraintType.FOREIGN_KEY && association instanceof DBSEntityReferrer
                && !DBUtils.isHiddenObject(association)) {
                keys.add(association);
            }
        }
        return keys;
    }

    /**
     * "columns → referenced table (referenced columns)", lower case: foreign keys with the same definition match
     * whatever their names.
     */
    @NotNull
    private String getForeignKeySignature(@NotNull DBSEntityAssociation key) {
        try {
            DBSEntity referenced = key.getAssociatedEntity();
            return (String.join(",", getColumnNames(monitor, key)) + "->" + (referenced == null ? "?" : referenced.getName()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "(" + String.join(",", getColumnNames(monitor, key.getReferencedConstraint())) + ")").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } catch (DBException e) {
            log.debug("Can't read foreign key " + key.getName(), e); //$NON-NLS-1$
            return key.getName();
        }
    }

    private void compareForeignKeys(@NotNull SchemaCompareItem item, @NotNull DBSEntity sourceTable, @NotNull DBSEntity targetTable) throws DBException {
        List<Pair<DBSEntityAssociation>> pairs = match(getForeignKeys(monitor, sourceTable), getForeignKeys(monitor, targetTable),
            List.of(this::getForeignKeySignature, k -> k.getName().toLowerCase(Locale.ROOT)));
        for (Pair<DBSEntityAssociation> pair : pairs) {
            DBSEntityAssociation sourceKey = pair.source();
            DBSEntityAssociation targetKey = pair.target();
            if (targetKey == null) {
                item.addChild(new SchemaCompareItem(SchemaCompareItem.Kind.FOREIGN_KEY, Objects.requireNonNull(sourceKey).getName(),
                    SchemaCompareItem.Status.ONLY_IN_SOURCE, sourceKey, null));
                continue;
            }
            if (sourceKey == null) {
                item.addChild(new SchemaCompareItem(SchemaCompareItem.Kind.FOREIGN_KEY, targetKey.getName(),
                    SchemaCompareItem.Status.ONLY_IN_TARGET, null, targetKey));
                continue;
            }
            SchemaCompareItem child = new SchemaCompareItem(SchemaCompareItem.Kind.FOREIGN_KEY, targetKey.getName(),
                SchemaCompareItem.Status.DIFFERENT, sourceKey, targetKey);
            String sourceSignature = getForeignKeySignature(sourceKey);
            String targetSignature = getForeignKeySignature(targetKey);
            if (!sourceSignature.equals(targetSignature)) {
                child.addDifference(SchemaCompareItem.Property.REFERENCE, sourceSignature, targetSignature);
            }
            if (sourceKey instanceof DBSTableForeignKey sourceFk && targetKey instanceof DBSTableForeignKey targetFk) {
                compareRule(child, SchemaCompareItem.Property.DELETE_RULE, sourceFk.getDeleteRule(), targetFk.getDeleteRule());
                compareRule(child, SchemaCompareItem.Property.UPDATE_RULE, sourceFk.getUpdateRule(), targetFk.getUpdateRule());
            }
            if (!child.getDifferences().isEmpty()) {
                item.addChild(child);
            }
        }
    }

    private static void compareRule(
        @NotNull SchemaCompareItem item,
        @NotNull SchemaCompareItem.Property property,
        @Nullable DBSForeignKeyModifyRule sourceRule,
        @Nullable DBSForeignKeyModifyRule targetRule
    ) {
        // RESTRICT and NO ACTION behave the same unless checks are deferred; databases report either one
        String sourceText = ruleId(sourceRule);
        String targetText = ruleId(targetRule);
        if (!sourceText.equals(targetText)) {
            item.addDifference(property, sourceRule == null ? null : sourceRule.getName(), targetRule == null ? null : targetRule.getName());
        }
    }

    @NotNull
    private static String ruleId(@Nullable DBSForeignKeyModifyRule rule) {
        if (rule == null || "UNKNOWN".equals(rule.getId()) || "RESTRICT".equals(rule.getId())) { //$NON-NLS-1$ //$NON-NLS-2$
            return "NO_ACTION"; //$NON-NLS-1$
        }
        return rule.getId();
    }

    /**
     * The table's indexes without those of its primary key and unique constraints (they are created with the key).
     */
    @NotNull
    static List<DBSTableIndex> getIndexes(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity table, @NotNull DBSTable rdbTable) throws DBException {
        Set<String> constraintNames = new HashSet<>();
        for (DBSEntityConstraint constraint : CommonUtils.safeCollection(table.getConstraints(monitor))) {
            if (!(constraint instanceof DBSTableIndex)) {
                constraintNames.add(constraint.getName().toLowerCase(Locale.ROOT));
            }
        }
        List<DBSTableIndex> indexes = new ArrayList<>();
        Collection<? extends DBSTableIndex> all = rdbTable.getIndexes(monitor);
        for (DBSTableIndex index : CommonUtils.safeCollection(all)) {
            if (!index.isPrimary() && !DBUtils.isHiddenObject(index) && !constraintNames.contains(index.getName().toLowerCase(Locale.ROOT))) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    /**
     * Index columns in order, a descending one with " DESC".
     */
    @NotNull
    static List<String> getIndexColumns(@NotNull DBRProgressMonitor monitor, @NotNull DBSTableIndex index) throws DBException {
        List<String> columns = new ArrayList<>();
        for (DBSTableIndexColumn column : CommonUtils.safeCollection(index.getAttributeReferences(monitor))) {
            DBSEntityAttribute attribute = column.getAttribute();
            String name = attribute != null ? attribute.getName() : column.getName();
            columns.add(column.isAscending() ? name : name + " DESC"); //$NON-NLS-1$
        }
        return columns;
    }

    @NotNull
    private String getIndexSignature(@NotNull DBSTableIndex index) {
        try {
            return ((index.isUnique() ? "unique:" : "") + String.join(",", getIndexColumns(monitor, index))).toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } catch (DBException e) {
            log.debug("Can't read index " + index.getName(), e); //$NON-NLS-1$
            return index.getName();
        }
    }

    private void compareIndexes(
        @NotNull SchemaCompareItem item,
        @NotNull DBSEntity sourceTable,
        @NotNull DBSEntity targetTable,
        @NotNull DBSTable sourceRdb,
        @NotNull DBSTable targetRdb
    ) throws DBException {
        List<Pair<DBSTableIndex>> pairs = match(getIndexes(monitor, sourceTable, sourceRdb), getIndexes(monitor, targetTable, targetRdb),
            List.of(DBSObject::getName, i -> i.getName().toLowerCase(Locale.ROOT), this::getIndexSignature));
        for (Pair<DBSTableIndex> pair : pairs) {
            DBSTableIndex sourceIndex = pair.source();
            DBSTableIndex targetIndex = pair.target();
            if (targetIndex == null) {
                item.addChild(new SchemaCompareItem(SchemaCompareItem.Kind.INDEX, Objects.requireNonNull(sourceIndex).getName(),
                    SchemaCompareItem.Status.ONLY_IN_SOURCE, sourceIndex, null));
                continue;
            }
            if (sourceIndex == null) {
                item.addChild(new SchemaCompareItem(SchemaCompareItem.Kind.INDEX, targetIndex.getName(),
                    SchemaCompareItem.Status.ONLY_IN_TARGET, null, targetIndex));
                continue;
            }
            SchemaCompareItem child = new SchemaCompareItem(SchemaCompareItem.Kind.INDEX, targetIndex.getName(),
                SchemaCompareItem.Status.DIFFERENT, sourceIndex, targetIndex);
            if (sourceIndex.isUnique() != targetIndex.isUnique()) {
                child.addDifference(SchemaCompareItem.Property.UNIQUE, String.valueOf(sourceIndex.isUnique()), String.valueOf(targetIndex.isUnique()));
            }
            String sourceColumns = String.join(", ", getIndexColumns(monitor, sourceIndex)); //$NON-NLS-1$
            String targetColumns = String.join(", ", getIndexColumns(monitor, targetIndex)); //$NON-NLS-1$
            if (!sourceColumns.equalsIgnoreCase(targetColumns)) {
                child.addDifference(SchemaCompareItem.Property.COLUMNS, sourceColumns, targetColumns);
            }
            if (!child.getDifferences().isEmpty()) {
                item.addChild(child);
            }
        }
    }

    private void compareTriggers(@NotNull SchemaCompareItem item, @NotNull DBSTable sourceTable, @NotNull DBSTable targetTable) throws DBException {
        List<DBSTrigger> sourceTriggers = getTriggers(monitor, sourceTable);
        List<DBSTrigger> targetTriggers = getTriggers(monitor, targetTable);
        List<Pair<DBSTrigger>> pairs = match(sourceTriggers, targetTriggers, List.of(DBSObject::getName, t -> t.getName().toLowerCase(Locale.ROOT)));
        for (Pair<DBSTrigger> pair : pairs) {
            DBSTrigger sourceTrigger = pair.source();
            DBSTrigger targetTrigger = pair.target();
            if (targetTrigger == null) {
                item.addChild(new SchemaCompareItem(SchemaCompareItem.Kind.TRIGGER, Objects.requireNonNull(sourceTrigger).getName(),
                    SchemaCompareItem.Status.ONLY_IN_SOURCE, sourceTrigger, null));
            } else if (sourceTrigger == null) {
                item.addChild(new SchemaCompareItem(SchemaCompareItem.Kind.TRIGGER, targetTrigger.getName(),
                    SchemaCompareItem.Status.ONLY_IN_TARGET, null, targetTrigger));
            } else {
                SchemaCompareItem child = new SchemaCompareItem(SchemaCompareItem.Kind.TRIGGER, targetTrigger.getName(),
                    SchemaCompareItem.Status.DIFFERENT, sourceTrigger, targetTrigger);
                compareDefinition(child, sourceTrigger, targetTrigger);
                if (!child.getDifferences().isEmpty()) {
                    item.addChild(child);
                }
            }
        }
    }

    @NotNull
    static List<DBSTrigger> getTriggers(@NotNull DBRProgressMonitor monitor, @NotNull DBSTable table) throws DBException {
        List<DBSTrigger> triggers = new ArrayList<>();
        for (DBSTrigger trigger : CommonUtils.safeCollection(table.getTriggers(monitor))) {
            if (!isHidden(trigger)) {
                triggers.add(trigger);
            }
        }
        return triggers;
    }
}
