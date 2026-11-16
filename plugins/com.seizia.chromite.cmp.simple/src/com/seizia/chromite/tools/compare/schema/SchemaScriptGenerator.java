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
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommand;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectMaker;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.edit.DBERegistry;
import com.seizia.chromite.model.edit.DBEStructEditor;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.AbstractCommandContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSDataType;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAssociation;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.DBStructUtils;
import com.seizia.chromite.model.struct.DBSTypedObjectExt2;
import com.seizia.chromite.model.struct.DBSTypedObjectExt3;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import com.seizia.chromite.model.struct.rdb.DBSForeignKeyModifyRule;
import com.seizia.chromite.model.struct.rdb.DBSSequence;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.model.struct.rdb.DBSTableForeignKey;
import com.seizia.chromite.model.struct.rdb.DBSTableIndex;
import com.seizia.chromite.model.struct.rdb.DBSTableIndexColumn;
import com.seizia.chromite.model.struct.rdb.DBSTrigger;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.properties.ObjectPropertyDescriptor;
import com.seizia.chromite.runtime.properties.PropertySourceEditable;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.BeanUtils;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the script that turns the target schema into the source for the selected items of a compare (it does not run it).
 *
 * 설계:
 * <ul>
 *     <li>문장은 가능한 한 대상 드라이버의 객체 관리자({@link SQLObjectEditor})가 만든다. 객체 탐색기의 Delete·테이블 디자이너와 같은 경로로,
 *     버려지는 명령 묶음({@link AbstractCommandContext})에 명령을 넣고 실행 동작을 글로 읽은 뒤 {@link DBECommandContext#resetChanges}로
 *     모델을 되돌린다. 그래서 DB마다 다른 문법(SQL Server 기본값 제약 조건 다시 만들기, PostgreSQL {@code ALTER COLUMN … TYPE},
 *     MySQL {@code MODIFY}, 인자가 붙는 PostgreSQL {@code DROP FUNCTION})을 드라이버가 정한다.
 *     <ul>
 *         <li>지우기(모든 종류): 관리자의 {@code deleteObject}.</li>
 *         <li>새 테이블, 열 더하기: 관리자의 {@code createNewObject}로 대상에 만들고 원본 열의 이름·형식·NOT NULL·기본값을 넣는다
 *         (데이터 옮기기의 새 테이블과 같은 방법, {@code DatabaseTransferUtils.generateStructTableDDL}).</li>
 *         <li>열 바꾸기: 대상 열의 속성(형식, {@code required}, {@code defaultValue})을 {@link PropertySourceEditable}로 바꿔 관리자의 변경 명령을 만든다
 *         (테이블 디자이너와 같음). 형식 속성은 드라이버마다 이름이 달라 {@code fullTypeName}, {@code dataType}+길이·정밀도·소수 자릿수,
 *         {@code typeName} 순서로 편집할 수 있는 것을 쓴다.</li>
 *     </ul></li>
 *     <li>관리자가 없는 것은 표준 SQL로 만든다: 기본 키·외래 키 더하기({@code ALTER TABLE … ADD [CONSTRAINT] PRIMARY KEY/FOREIGN KEY}),
 *     인덱스 만들기({@code CREATE [UNIQUE] INDEX … ON … (…)}, 같은 종류 DB에서 원본 정의가 있으면 그 정의), 시퀀스 바꾸기
 *     ({@code ALTER SEQUENCE … INCREMENT BY/MINVALUE/MAXVALUE}). 뷰·루틴·트리거·시퀀스 만들기는 같은 종류 DB에서만 원본 정의를
 *     대상 스키마 이름으로 바꿔 쓴다({@link SchemaNameMapper}). 바꾸기는 루틴·트리거의 정의가 {@code CREATE OR REPLACE/ALTER}면 그것만,
 *     아니면 대상 객체를 지우고 다시 만든다. 뷰는 늘 지우고 다시 만든다(PostgreSQL은 열이 바뀐 뷰를 REPLACE하지 못한다).
 *     다른 종류 DB로는 옮길 수 없다는 주석을 남긴다.</li>
 *     <li>순서: 지우기(외래 키 → 뷰·루틴·트리거 → 인덱스 → 기본 키 → 열 → 테이블 → 시퀀스) 다음 만들기(시퀀스 → 테이블 → 열 → 기본 키 →
 *     인덱스 → 외래 키 → 뷰·루틴·트리거). 의존하는 것을 먼저 지우고, 가리킬 것을 먼저 만든다.</li>
 *     <li>다른 종류 DB의 기본값은 리터럴(숫자, 문자열)만 옮긴다. 함수식({@code now()} 등)은 대상 DB에 없을 수 있어 주석을 남긴다.</li>
 * </ul>
 * 스크립트는 실행하지 않는다. 화면은 쿼리 창에 열어 사용자가 검토하고 실행한다. 작업 스레드에서 부른다(DB를 읽는다).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/schema-compare.md)
 */
public final class SchemaScriptGenerator {

    private static final Log log = Log.getLog(SchemaScriptGenerator.class);

    private static final String PROP_FULL_TYPE_NAME = "fullTypeName"; //$NON-NLS-1$
    private static final String PROP_DATA_TYPE = "dataType"; //$NON-NLS-1$
    private static final String PROP_TYPE_NAME = "typeName"; //$NON-NLS-1$
    private static final String PROP_MAX_LENGTH = "maxLength"; //$NON-NLS-1$
    private static final String PROP_PRECISION = "precision"; //$NON-NLS-1$
    private static final String PROP_SCALE = "scale"; //$NON-NLS-1$
    private static final String PROP_REQUIRED = "required"; //$NON-NLS-1$
    private static final String PROP_DEFAULT_VALUE = "defaultValue"; //$NON-NLS-1$

    private static final Pattern REPLACING_CREATE = Pattern.compile("^(\\s*--[^\\n]*\\n)*\\s*CREATE\\s+OR\\s+(REPLACE|ALTER)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE_MODIFIERS = Pattern.compile("\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)"); //$NON-NLS-1$

    private final DBRProgressMonitor monitor;
    private final SchemaCompareResult result;
    private final DBPDataSource targetDataSource;
    private final DBCExecutionContext executionContext;
    private final SchemaNameMapper names;
    private final DBERegistry registry;
    /**
     * Command contexts whose model changes are undone at the end.
     */
    private final List<DBECommandContext> contexts = new ArrayList<>();

    private final List<DBEPersistAction> dropForeignKeys = new ArrayList<>();
    private final List<DBEPersistAction> dropCode = new ArrayList<>();
    private final List<DBEPersistAction> dropIndexes = new ArrayList<>();
    private final List<DBEPersistAction> dropKeys = new ArrayList<>();
    private final List<DBEPersistAction> dropColumns = new ArrayList<>();
    private final List<DBEPersistAction> dropTables = new ArrayList<>();
    private final List<DBEPersistAction> dropSequences = new ArrayList<>();
    private final List<DBEPersistAction> sequences = new ArrayList<>();
    private final List<DBEPersistAction> createTables = new ArrayList<>();
    private final List<DBEPersistAction> columns = new ArrayList<>();
    private final List<DBEPersistAction> addKeys = new ArrayList<>();
    private final List<DBEPersistAction> addIndexes = new ArrayList<>();
    private final List<DBEPersistAction> addForeignKeys = new ArrayList<>();
    private final List<DBEPersistAction> createCode = new ArrayList<>();

    private SchemaScriptGenerator(@NotNull DBRProgressMonitor monitor, @NotNull SchemaCompareResult result) throws DBException {
        this.monitor = monitor;
        this.result = result;
        DBSObjectContainer target = result.getTarget();
        this.targetDataSource = target.getDataSource();
        this.executionContext = DBUtils.getDefaultContext(target, false);
        if (targetDataSource == null || executionContext == null) {
            throw new DBException("Target '" + target.getName() + "' is not connected");
        }
        this.names = result.getNameMapper();
        this.registry = DBWorkbench.getPlatform().getEditorsRegistry();
    }

    /**
     * The script actions for the selected items: statements and comments (manual steps), in execution order. Empty when
     * nothing is selected.
     */
    @NotNull
    public static List<DBEPersistAction> generate(@NotNull DBRProgressMonitor monitor, @NotNull SchemaCompareResult result) throws DBException {
        SchemaScriptGenerator generator = new SchemaScriptGenerator(monitor, result);
        try {
            for (SchemaCompareItem item : result.getItems()) {
                if (monitor.isCanceled()) {
                    break;
                }
                if (item.isSelected()) {
                    generator.addItem(item);
                }
            }
            return generator.collect();
        } finally {
            generator.discard();
        }
    }

    @NotNull
    private List<DBEPersistAction> collect() {
        List<DBEPersistAction> actions = new ArrayList<>();
        for (List<DBEPersistAction> phase : List.of(dropForeignKeys, dropCode, dropIndexes, dropKeys, dropColumns, dropTables, dropSequences,
            sequences, createTables, columns, addKeys, addIndexes, addForeignKeys, createCode)) {
            actions.addAll(phase);
        }
        return actions;
    }

    private void discard() {
        for (int i = contexts.size() - 1; i >= 0; i--) {
            try {
                contexts.get(i).resetChanges(true);
            } catch (RuntimeException e) {
                log.warn("Can't undo the model changes of the schema compare script", e); //$NON-NLS-1$
            }
        }
        contexts.clear();
    }

    //////////////////////////////////////////////////////////
    // Items

    private void addItem(@NotNull SchemaCompareItem item) throws DBException {
        switch (item.getStatus()) {
            case ONLY_IN_TARGET -> drop(item.getKind(), requireTarget(item));
            case ONLY_IN_SOURCE -> create(item);
            case DIFFERENT -> change(item);
        }
    }

    @NotNull
    private static DBSObject requireTarget(@NotNull SchemaCompareItem item) {
        DBSObject target = item.getTarget();
        if (target == null) {
            throw new IllegalStateException("No target object for " + item);
        }
        return target;
    }

    @NotNull
    private static DBSObject requireSource(@NotNull SchemaCompareItem item) {
        DBSObject source = item.getSource();
        if (source == null) {
            throw new IllegalStateException("No source object for " + item);
        }
        return source;
    }

    private void create(@NotNull SchemaCompareItem item) throws DBException {
        DBSObject source = requireSource(item);
        switch (item.getKind()) {
            case TABLE -> createTable((DBSEntity) source);
            case VIEW, ROUTINE -> createFromDefinition(source, createCode);
            case SEQUENCE -> createFromDefinition(source, sequences);
            case TRIGGER -> createFromDefinition(source, createCode);
            case COLUMN -> addColumn(getTargetTable(item), (DBSEntityAttribute) source);
            case PRIMARY_KEY -> addKeys.add(addPrimaryKey(getTargetTableName(item), getSourceTable(item), (DBSEntityConstraint) source));
            case FOREIGN_KEY -> addForeignKeys.add(addForeignKey(getTargetTableName(item), getSourceTable(item), (DBSEntityAssociation) source));
            case INDEX -> addIndexes.add(createIndex(getTargetTableName(item), getSourceTable(item), (DBSTableIndex) source));
        }
    }

    private void change(@NotNull SchemaCompareItem item) throws DBException {
        if (item.getKind() == SchemaCompareItem.Kind.TABLE) {
            for (SchemaCompareItem child : item.getChildren()) {
                if (child.isSelected()) {
                    addItem(child);
                }
            }
            return;
        }
        DBSObject source = requireSource(item);
        DBSObject target = requireTarget(item);
        switch (item.getKind()) {
            case VIEW, ROUTINE, TRIGGER -> replaceDefinition(item.getKind(), source, target);
            case SEQUENCE -> sequences.add(alterSequence((DBSSequence) source, item));
            case COLUMN -> modifyColumn((DBSEntityAttribute) source, (DBSEntityAttribute) target, item);
            case PRIMARY_KEY -> {
                drop(item.getKind(), target);
                addKeys.add(addPrimaryKey(getTargetTableName(item), getSourceTable(item), (DBSEntityConstraint) source));
            }
            case FOREIGN_KEY -> {
                drop(item.getKind(), target);
                addForeignKeys.add(addForeignKey(getTargetTableName(item), getSourceTable(item), (DBSEntityAssociation) source));
            }
            case INDEX -> {
                drop(item.getKind(), target);
                addIndexes.add(createIndex(getTargetTableName(item), getSourceTable(item), (DBSTableIndex) source));
            }
            default -> throw new IllegalStateException("Unexpected item " + item);
        }
    }

    @NotNull
    private static DBSEntity getSourceTable(@NotNull SchemaCompareItem item) {
        SchemaCompareItem parent = item.getParent();
        if (parent == null || !(parent.getSource() instanceof DBSEntity table)) {
            throw new IllegalStateException("No source table for " + item);
        }
        return table;
    }

    @NotNull
    private static DBSEntity getTargetTable(@NotNull SchemaCompareItem item) {
        SchemaCompareItem parent = item.getParent();
        if (parent == null || !(parent.getTarget() instanceof DBSEntity table)) {
            throw new IllegalStateException("No target table for " + item);
        }
        return table;
    }

    @NotNull
    private static String getTargetTableName(@NotNull SchemaCompareItem item) {
        return DBUtils.getObjectFullName(getTargetTable(item), DBPEvaluationContext.DDL);
    }

    //////////////////////////////////////////////////////////
    // Object managers

    @NotNull
    private DBECommandContext newContext() {
        DBECommandContext context = new AbstractCommandContext(executionContext, false) {
        };
        contexts.add(context);
        return context;
    }

    @NotNull
    private static Map<String, Object> createOptions() {
        Map<String, Object> options = new HashMap<>();
        // The configuration dialogs of new objects are UI; the values are set here
        options.put(SQLObjectEditor.OPTION_SKIP_CONFIGURATION, true);
        return options;
    }

    private void render(@NotNull DBECommandContext context, @NotNull Map<String, Object> options, @NotNull List<DBEPersistAction> phase) throws DBException {
        for (DBECommand<?> command : context.getFinalCommands()) {
            command.validateCommand(monitor, options);
            DBEPersistAction[] actions = command.getPersistActions(monitor, executionContext, options);
            if (actions != null) {
                phase.addAll(List.of(actions));
            }
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private <T> T getManager(@NotNull Class<?> objectClass, @NotNull Class<T> managerType) {
        return registry.getObjectManager(objectClass, managerType);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void drop(@NotNull SchemaCompareItem.Kind kind, @NotNull DBSObject object) throws DBException {
        List<DBEPersistAction> phase = switch (kind) {
            case TABLE -> dropTables;
            case VIEW, ROUTINE, TRIGGER -> dropCode;
            case SEQUENCE -> dropSequences;
            case COLUMN -> dropColumns;
            case PRIMARY_KEY -> dropKeys;
            case FOREIGN_KEY -> dropForeignKeys;
            case INDEX -> dropIndexes;
        };
        DBEObjectMaker maker = getManager(object.getClass(), DBEObjectMaker.class);
        if (maker == null || !maker.canDeleteObject(object)) {
            phase.add(comment(NLS.bind(SchemaCompareMessages.script_cannot_drop, getFullName(object))));
            return;
        }
        DBECommandContext context = newContext();
        Map<String, Object> options = new HashMap<>();
        maker.deleteObject(context, object, options);
        render(context, options, phase);
    }

    @NotNull
    private static String getFullName(@NotNull DBSObject object) {
        return DBUtils.getObjectFullName(object, DBPEvaluationContext.UI);
    }

    /**
     * The column manager of a table class (the attribute type among the table manager's child types), or null.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private SQLObjectEditor<DBSEntityAttribute, DBSEntity> getColumnManager(@NotNull Class<?> tableClass) {
        DBEStructEditor<?> tableManager = getManager(tableClass, DBEStructEditor.class);
        if (tableManager == null) {
            return null;
        }
        Class<? extends DBSEntityAttribute> columnClass = BeanUtils.findAssignableType(tableManager.getChildTypes(), DBSEntityAttribute.class);
        return columnClass == null ? null : getManager(columnClass, SQLObjectEditor.class);
    }

    @SuppressWarnings("unchecked")
    private void createTable(@NotNull DBSEntity sourceTable) throws DBException {
        DBSObjectContainer target = result.getTarget();
        String tableName = names.getTargetName(sourceTable.getName());
        Class<? extends DBSObject> tableClass = target.getPrimaryChildType(monitor);
        SQLObjectEditor<DBSEntity, DBSObject> tableManager = tableClass == null ? null : getManager(tableClass, SQLObjectEditor.class);
        SQLObjectEditor<DBSEntityAttribute, DBSEntity> columnManager = tableClass == null ? null : getColumnManager(tableClass);
        if (tableManager == null || columnManager == null || !tableManager.canCreateObject(target)) {
            createTables.add(comment(NLS.bind(SchemaCompareMessages.script_cannot_create, getFullName(sourceTable))));
            return;
        }
        DBECommandContext context = newContext();
        Map<String, Object> options = createOptions();
        DBSEntity table = tableManager.createNewObject(monitor, context, target, null, options);
        if (!(table instanceof DBPNamedObject2 namedTable)) {
            createTables.add(comment(NLS.bind(SchemaCompareMessages.script_cannot_create, getFullName(sourceTable))));
            return;
        }
        namedTable.setName(tableName);
        List<DBEPersistAction> notes = new ArrayList<>();
        for (DBSEntityAttribute sourceColumn : SchemaComparer.getColumns(monitor, sourceTable)) {
            DBSEntityAttribute column = columnManager.createNewObject(monitor, context, table, null, options);
            if (column == null) {
                continue;
            }
            defineColumn(context, column, sourceTable, sourceColumn, notes);
            // The name changed after the manager cached the column under its default name
            DBSObjectCache<? extends DBSObject, DBSEntityAttribute> cache = columnManager.getObjectsCache(column);
            if (cache != null) {
                cache.cacheObject(column);
            }
        }
        render(context, options, createTables);
        createTables.addAll(notes);

        String qualifiedName = names.qualify(tableName);
        DBSEntityConstraint primaryKey = SchemaComparer.getPrimaryKey(monitor, sourceTable);
        if (primaryKey != null) {
            addKeys.add(addPrimaryKey(qualifiedName, sourceTable, primaryKey));
        }
        if (sourceTable instanceof DBSTable sourceRdb) {
            for (DBSTableIndex index : SchemaComparer.getIndexes(monitor, sourceTable, sourceRdb)) {
                addIndexes.add(createIndex(qualifiedName, sourceTable, index));
            }
            for (DBSTrigger trigger : SchemaComparer.getTriggers(monitor, sourceRdb)) {
                createFromDefinition(trigger, createCode);
            }
        }
        for (DBSEntityAssociation foreignKey : SchemaComparer.getForeignKeys(monitor, sourceTable)) {
            addForeignKeys.add(addForeignKey(qualifiedName, sourceTable, foreignKey));
        }
    }

    private void addColumn(@NotNull DBSEntity targetTable, @NotNull DBSEntityAttribute sourceColumn) throws DBException {
        SQLObjectEditor<DBSEntityAttribute, DBSEntity> columnManager = getColumnManager(targetTable.getClass());
        if (columnManager == null || !columnManager.canCreateObject(targetTable)) {
            columns.add(comment(NLS.bind(SchemaCompareMessages.script_cannot_create, getFullName(sourceColumn))));
            return;
        }
        DBECommandContext context = newContext();
        Map<String, Object> options = createOptions();
        DBSEntityAttribute column = columnManager.createNewObject(monitor, context, targetTable, null, options);
        if (column == null) {
            return;
        }
        List<DBEPersistAction> notes = new ArrayList<>();
        defineColumn(context, column, targetTable, sourceColumn, notes);
        render(context, options, columns);
        columns.addAll(notes);
    }

    /**
     * Sets a new column like the source column: name, type, NOT NULL and default.
     */
    private void defineColumn(
        @NotNull DBECommandContext context,
        @NotNull DBSEntityAttribute column,
        @NotNull DBSEntity table,
        @NotNull DBSEntityAttribute sourceColumn,
        @NotNull List<DBEPersistAction> notes
    ) throws DBException {
        if (!(column instanceof DBPNamedObject2 namedColumn)) {
            throw new DBException("Column name can't be set for " + column.getClass().getName());
        }
        namedColumn.setName(names.getTargetName(sourceColumn.getName()));
        String fullType = getTargetType(sourceColumn);
        boolean modifiersSet = false;
        if (column instanceof DBSTypedObjectExt3 typed3) {
            typed3.setFullTypeName(fullType);
            modifiersSet = fullType.contains("("); //$NON-NLS-1$
        } else if (column instanceof DBSTypedObjectExt2 typed2) {
            typed2.setTypeName(SQLUtils.stripColumnTypeModifiers(fullType));
        }
        if (column instanceof DBSTypedObjectExt2 typed2) {
            if (!modifiersSet) {
                typed2.setMaxLength(sourceColumn.getMaxLength());
                typed2.setPrecision(sourceColumn.getPrecision());
                typed2.setScale(sourceColumn.getScale());
            }
            typed2.setRequired(sourceColumn.isRequired());
        }
        String defaultValue = getTargetDefault(table, sourceColumn, notes);
        if (defaultValue != null) {
            setProperty(new PropertySourceEditable(context, column, column), column, PROP_DEFAULT_VALUE, defaultValue);
        }
    }

    private void modifyColumn(@NotNull DBSEntityAttribute sourceColumn, @NotNull DBSEntityAttribute targetColumn, @NotNull SchemaCompareItem item) throws DBException {
        DBECommandContext context = newContext();
        PropertySourceEditable properties = new PropertySourceEditable(context, targetColumn, targetColumn);
        properties.collectProperties();
        List<DBEPersistAction> notes = new ArrayList<>();
        for (SchemaCompareItem.Difference difference : item.getDifferences()) {
            switch (difference.property()) {
                case TYPE -> {
                    if (!setColumnType(properties, sourceColumn, targetColumn)) {
                        notes.add(comment(NLS.bind(SchemaCompareMessages.script_cannot_change_type, getFullName(targetColumn))));
                    }
                }
                case NULLABLE -> setProperty(properties, targetColumn, PROP_REQUIRED, sourceColumn.isRequired());
                case DEFAULT -> {
                    String defaultValue = getTargetDefault(targetColumn.getParentObject(), sourceColumn, notes);
                    // An expression that can't be copied (a note says so) leaves the target's default alone
                    if (defaultValue != null || CommonUtils.isEmptyTrimmed(sourceColumn.getDefaultValue())) {
                        setProperty(properties, targetColumn, PROP_DEFAULT_VALUE, defaultValue);
                    }
                }
                default -> {
                }
            }
        }
        render(context, new HashMap<>(), columns);
        columns.addAll(notes);
    }

    /**
     * Changes the type of an existing column with the first editable type property of its driver.
     */
    private boolean setColumnType(
        @NotNull PropertySourceEditable properties,
        @NotNull DBSEntityAttribute sourceColumn,
        @NotNull DBSEntityAttribute targetColumn
    ) throws DBException {
        String fullType = getTargetType(sourceColumn);
        if (setProperty(properties, targetColumn, PROP_FULL_TYPE_NAME, fullType)) {
            return true;
        }
        String typeName = SQLUtils.stripColumnTypeModifiers(fullType);
        boolean typeSet = false;
        ObjectPropertyDescriptor dataTypeProperty = getEditableProperty(properties, targetColumn, PROP_DATA_TYPE);
        if (dataTypeProperty != null) {
            DBSDataType dataType = DBUtils.resolveDataType(monitor, targetDataSource, typeName);
            if (dataType != null && dataTypeProperty.getDataType().isInstance(dataType)) {
                properties.setPropertyValue(monitor, targetColumn, dataTypeProperty, dataType);
                typeSet = true;
            }
        }
        if (!typeSet) {
            typeSet = setProperty(properties, targetColumn, PROP_TYPE_NAME, typeName);
        }
        if (!typeSet) {
            return false;
        }
        Long maxLength = sourceColumn.getMaxLength();
        Integer precision = sourceColumn.getPrecision();
        Integer scale = sourceColumn.getScale();
        Matcher modifiers = TYPE_MODIFIERS.matcher(fullType);
        if (!result.isSameDialect() && modifiers.find()) {
            // The mapped type says the size in the target's terms (e.g. varchar(max) has none)
            int first = Integer.parseInt(modifiers.group(1));
            DBPDataKind kind = sourceColumn.getDataKind();
            if (kind == DBPDataKind.STRING || kind == DBPDataKind.BINARY) {
                maxLength = (long) first;
            } else {
                precision = first;
                scale = modifiers.group(2) == null ? null : Integer.parseInt(modifiers.group(2));
            }
        }
        setProperty(properties, targetColumn, PROP_MAX_LENGTH, maxLength);
        setProperty(properties, targetColumn, PROP_PRECISION, precision);
        setProperty(properties, targetColumn, PROP_SCALE, scale);
        return true;
    }

    @Nullable
    private ObjectPropertyDescriptor getEditableProperty(@NotNull PropertySourceEditable properties, @NotNull Object object, @NotNull String id) {
        if (properties.getProperties().length == 0) {
            properties.collectProperties();
        }
        return properties.getProperty(id) instanceof ObjectPropertyDescriptor property && property.isEditable(object) ? property : null;
    }

    /**
     * Sets a property through the object's manager (a change command for a saved object); false if the driver has no
     * such editable property.
     */
    private boolean setProperty(@NotNull PropertySourceEditable properties, @NotNull Object object, @NotNull String id, @Nullable Object value) {
        ObjectPropertyDescriptor property = getEditableProperty(properties, object, id);
        if (property == null) {
            return false;
        }
        properties.setPropertyValue(monitor, object, property, value);
        return true;
    }

    @NotNull
    private String getTargetType(@NotNull DBSEntityAttribute sourceColumn) {
        return result.isSameDialect()
            ? sourceColumn.getFullTypeName()
            : DBStructUtils.mapTargetDataType(result.getTarget(), sourceColumn, true);
    }

    /**
     * The default a target column gets: the source default (schema names mapped) in the same database type; between
     * types only a literal (a number or a string), an expression gets a note instead.
     */
    @Nullable
    private String getTargetDefault(@NotNull DBSEntity table, @NotNull DBSEntityAttribute sourceColumn, @NotNull List<DBEPersistAction> notes) {
        String value = sourceColumn.getDefaultValue();
        if (CommonUtils.isEmptyTrimmed(value)) {
            return null;
        }
        if (result.isSameDialect()) {
            return names.remap(value);
        }
        if (sourceColumn.isAutoGenerated()) {
            return null;
        }
        String literal = SchemaComparer.toLiteral(value);
        if (literal != null) {
            return literal;
        }
        notes.add(comment(NLS.bind(SchemaCompareMessages.script_default_not_copied,
            table.getName() + "." + sourceColumn.getName(), value))); //$NON-NLS-1$
        return null;
    }

    //////////////////////////////////////////////////////////
    // Keys and indexes (standard SQL)

    /**
     * The quoted target names of source columns: the matched target column's name, else the name a new column gets.
     */
    @NotNull
    private String getTargetColumnList(@NotNull DBSEntity sourceTable, @NotNull List<String> sourceColumnNames) throws DBException {
        DBSEntity targetTable = result.getTargetTable(sourceTable);
        List<DBSEntityAttribute> targetColumns = targetTable == null ? List.of() : SchemaComparer.getColumns(monitor, targetTable);
        List<String> quoted = new ArrayList<>();
        for (String sourceName : sourceColumnNames) {
            String name = null;
            for (DBSEntityAttribute column : targetColumns) {
                if (column.getName().equals(sourceName)) {
                    name = column.getName();
                    break;
                }
            }
            if (name == null) {
                for (DBSEntityAttribute column : targetColumns) {
                    if (column.getName().equalsIgnoreCase(sourceName)) {
                        name = column.getName();
                        break;
                    }
                }
            }
            quoted.add(DBUtils.getQuotedIdentifier(targetDataSource, name != null ? name : names.getTargetName(sourceName)));
        }
        return String.join(", ", quoted); //$NON-NLS-1$
    }

    /**
     * The target name of a table a source foreign key refers to: its matched target table, a table of the compared
     * schema by its target name, or a table of another schema as it is.
     */
    @NotNull
    private String getTargetTableName(@NotNull DBSEntity sourceTable) {
        DBSEntity targetTable = result.getTargetTable(sourceTable);
        if (targetTable != null) {
            return DBUtils.getObjectFullName(targetTable, DBPEvaluationContext.DDL);
        }
        if (sourceTable.getParentObject() == result.getSource()) {
            return names.qualify(names.getTargetName(sourceTable.getName()));
        }
        return DBUtils.getObjectFullName(sourceTable, DBPEvaluationContext.DDL);
    }

    @NotNull
    private DBEPersistAction addPrimaryKey(@NotNull String tableName, @NotNull DBSEntity sourceTable, @NotNull DBSEntityConstraint key) throws DBException {
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(tableName).append(" ADD "); //$NON-NLS-1$ //$NON-NLS-2$
        appendConstraintName(sql, key);
        sql.append("PRIMARY KEY (").append(getTargetColumnList(sourceTable, SchemaComparer.getColumnNames(monitor, key))).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
        return new SQLDatabasePersistAction("Add primary key", sql.toString()); //$NON-NLS-1$
    }

    /**
     * {@code CONSTRAINT <name> } in the same database type; between types the target names it (names generated by one
     * database, such as SQL Server {@code PK__…}, mean nothing in another).
     */
    private void appendConstraintName(@NotNull StringBuilder sql, @NotNull DBSEntityConstraint key) {
        if (result.isSameDialect() && !CommonUtils.isEmpty(key.getName())) {
            sql.append("CONSTRAINT ").append(DBUtils.getQuotedIdentifier(targetDataSource, key.getName())).append(' '); //$NON-NLS-1$
        }
    }

    @NotNull
    private DBEPersistAction addForeignKey(@NotNull String tableName, @NotNull DBSEntity sourceTable, @NotNull DBSEntityAssociation key) throws DBException {
        DBSEntity referenced = key.getAssociatedEntity();
        if (referenced == null) {
            return comment(NLS.bind(SchemaCompareMessages.script_cannot_create, getFullName(key)));
        }
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(tableName).append(" ADD "); //$NON-NLS-1$ //$NON-NLS-2$
        appendConstraintName(sql, key);
        sql.append("FOREIGN KEY (").append(getTargetColumnList(sourceTable, SchemaComparer.getColumnNames(monitor, key))) //$NON-NLS-1$
            .append(") REFERENCES ").append(getTargetTableName(referenced)) //$NON-NLS-1$
            .append(" (").append(getTargetColumnList(referenced, SchemaComparer.getColumnNames(monitor, key.getReferencedConstraint()))) //$NON-NLS-1$
            .append(")"); //$NON-NLS-1$
        if (key instanceof DBSTableForeignKey foreignKey) {
            appendRule(sql, " ON DELETE ", foreignKey.getDeleteRule()); //$NON-NLS-1$
            appendRule(sql, " ON UPDATE ", foreignKey.getUpdateRule()); //$NON-NLS-1$
        }
        return new SQLDatabasePersistAction("Add foreign key", sql.toString()); //$NON-NLS-1$
    }

    private static void appendRule(@NotNull StringBuilder sql, @NotNull String prefix, @Nullable DBSForeignKeyModifyRule rule) {
        // NO ACTION (no clause) is the default everywhere
        if (rule != null && !CommonUtils.isEmpty(rule.getClause())) {
            sql.append(prefix).append(rule.getClause());
        }
    }

    @NotNull
    private DBEPersistAction createIndex(@NotNull String tableName, @NotNull DBSEntity sourceTable, @NotNull DBSTableIndex index) throws DBException {
        if (result.isSameDialect()) {
            String definition = SchemaComparer.readDefinition(monitor, index);
            if (definition != null && definition.regionMatches(true, 0, "CREATE", 0, 6)) { //$NON-NLS-1$
                return new SQLDatabasePersistAction("Create index", names.remap(definition)); //$NON-NLS-1$
            }
        }
        List<String> parts = new ArrayList<>();
        for (DBSTableIndexColumn column : CommonUtils.safeCollection(index.getAttributeReferences(monitor))) {
            if (column.getAttribute() == null) {
                // An expression: only its own database's definition can say it
                return comment(NLS.bind(SchemaCompareMessages.script_cannot_create, getFullName(index)));
            }
            String part = getTargetColumnList(sourceTable, List.of(column.getAttribute().getName()));
            parts.add(column.isAscending() ? part : part + " DESC"); //$NON-NLS-1$
        }
        String sql = "CREATE " + (index.isUnique() ? "UNIQUE " : "") + "INDEX " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + DBUtils.getQuotedIdentifier(targetDataSource, names.getTargetName(index.getName()))
            + " ON " + tableName + " (" + String.join(", ", parts) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return new SQLDatabasePersistAction("Create index", sql); //$NON-NLS-1$
    }

    //////////////////////////////////////////////////////////
    // Definitions

    /**
     * A view, routine, trigger or sequence from its source definition (same database type only; a note otherwise).
     */
    private void createFromDefinition(@NotNull DBSObject source, @NotNull List<DBEPersistAction> phase) throws DBException {
        if (!result.isSameDialect()) {
            phase.add(comment(NLS.bind(SchemaCompareMessages.script_other_database, getFullName(source))));
            return;
        }
        String definition = SchemaComparer.readDefinition(monitor, source);
        if (definition == null) {
            phase.add(comment(NLS.bind(SchemaCompareMessages.script_no_definition, getFullName(source))));
            return;
        }
        // Complex: a body with its own delimiters (the query window keeps it in one statement)
        phase.add(new SQLDatabasePersistAction("Create " + source.getName(), names.remap(definition), !(source instanceof DBSSequence))); //$NON-NLS-1$
    }

    private void replaceDefinition(@NotNull SchemaCompareItem.Kind kind, @NotNull DBSObject source, @NotNull DBSObject target) throws DBException {
        String definition = SchemaComparer.readDefinition(monitor, source);
        if (definition == null) {
            createCode.add(comment(NLS.bind(SchemaCompareMessages.script_no_definition, getFullName(source))));
            return;
        }
        String remapped = names.remap(definition);
        // A view is dropped first even with CREATE OR REPLACE: PostgreSQL can't replace a view whose columns change
        // ("cannot drop columns from view"), and dropping a view loses no data
        if (kind == SchemaCompareItem.Kind.VIEW || !REPLACING_CREATE.matcher(remapped).find()) {
            drop(kind, target);
        }
        createCode.add(new SQLDatabasePersistAction("Replace " + target.getName(), remapped, true)); //$NON-NLS-1$
    }

    @NotNull
    private DBEPersistAction alterSequence(@NotNull DBSSequence source, @NotNull SchemaCompareItem item) {
        StringBuilder sql = new StringBuilder("ALTER SEQUENCE ").append(DBUtils.getObjectFullName(requireTarget(item), DBPEvaluationContext.DDL)); //$NON-NLS-1$
        for (SchemaCompareItem.Difference difference : item.getDifferences()) {
            String keyword = switch (difference.property()) {
                case INCREMENT -> " INCREMENT BY "; //$NON-NLS-1$
                case MIN_VALUE -> " MINVALUE "; //$NON-NLS-1$
                case MAX_VALUE -> " MAXVALUE "; //$NON-NLS-1$
                default -> null;
            };
            if (keyword != null && difference.sourceValue() != null) {
                sql.append(keyword).append(difference.sourceValue());
            }
        }
        return new SQLDatabasePersistAction("Alter sequence " + source.getName(), sql.toString()); //$NON-NLS-1$
    }

    @NotNull
    private DBEPersistAction comment(@NotNull String text) {
        return new SQLDatabasePersistAction("Note", SQLUtils.generateCommentLine(targetDataSource, text).strip(), //$NON-NLS-1$
            DBEPersistAction.ActionType.COMMENT);
    }

    /**
     * Comment lines that start the script: what it does, and that nothing was run.
     */
    @NotNull
    public static List<DBEPersistAction> createHeader(@NotNull SchemaCompareResult result) {
        DBPDataSource dataSource = result.getTarget().getDataSource();
        List<DBEPersistAction> header = new ArrayList<>();
        for (String line : List.of(
            NLS.bind(SchemaCompareMessages.script_header_source, describe(result.getSource())),
            NLS.bind(SchemaCompareMessages.script_header_target, describe(result.getTarget())),
            SchemaCompareMessages.script_header_review)) {
            header.add(new SQLDatabasePersistAction("Note", SQLUtils.generateCommentLine(dataSource, line).strip(), //$NON-NLS-1$
                DBEPersistAction.ActionType.COMMENT));
        }
        return header;
    }

    @NotNull
    private static String describe(@NotNull DBSObjectContainer container) {
        DBPDataSource dataSource = container.getDataSource();
        String connection = dataSource == null ? "?" : dataSource.getContainer().getName(); //$NON-NLS-1$
        return connection + " / " + DBUtils.getObjectFullName(container, DBPEvaluationContext.UI); //$NON-NLS-1$
    }

}

