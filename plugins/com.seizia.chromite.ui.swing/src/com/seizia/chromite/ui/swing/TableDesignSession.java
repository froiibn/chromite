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
package com.seizia.chromite.ui.swing;

import com.seizia.chromite.ui.swing.design.TableDesignerDriver;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPDataSourcePermission;
import org.jkiss.dbeaver.model.DBPNamedObject2;
import org.jkiss.dbeaver.model.DBPObject;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.model.DBPStatefulObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.edit.DBECommand;
import org.jkiss.dbeaver.model.edit.DBECommandContext;
import org.jkiss.dbeaver.model.edit.DBEObjectMaker;
import org.jkiss.dbeaver.model.edit.DBEObjectManager;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.edit.DBEStructEditor;
import org.jkiss.dbeaver.model.edit.prop.DBECommandDeleteObject;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBExecUtils;
import org.jkiss.dbeaver.model.impl.DBObjectNameCaseTransformer;
import org.jkiss.dbeaver.model.impl.edit.AbstractCommandContext;
import org.jkiss.dbeaver.model.impl.sql.edit.SQLObjectEditor;
import org.jkiss.dbeaver.model.impl.sql.edit.struct.SQLForeignKeyManager;
import org.jkiss.dbeaver.model.impl.struct.AbstractTableConstraint;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNEvent;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSEntityAttributeRef;
import org.jkiss.dbeaver.model.struct.DBSEntityConstrainable;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraint;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraintInfo;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraintType;
import org.jkiss.dbeaver.model.struct.DBSEntityReferrer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.cache.DBSObjectCache;
import org.jkiss.dbeaver.model.struct.rdb.DBSForeignKeyModifyRule;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableColumn;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableForeignKey;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndex;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.properties.DataSourcePropertyFilter;
import org.jkiss.dbeaver.runtime.properties.ObjectAttributeDescriptor;
import org.jkiss.dbeaver.runtime.properties.ObjectPropertyDescriptor;
import org.jkiss.dbeaver.runtime.properties.PropertySourceEditable;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.BeanUtils;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 테이블 디자이너의 편집 세션(UI 없음): 한 테이블의 열·키·외래 키·인덱스를 바꾸는 명령을 모아 두었다가 스크립트로 보여 주고 저장한다.
 *
 * 설계: SWT 객체 편집기({@code EntityEditor})가 쓰는 모델 계층의 명령 체계를 그대로 쓴다.
 * <ul>
 *     <li>명령 묶음은 {@link DBECommandContext}다. SWT는 편집기 입력({@code DatabaseEditorInput})이 연결의 기본 실행 문맥
 *     ({@code DBUtils.getDefaultContext(object, false)})으로 {@code SimpleCommandContext(context, false)}를 만든다.
 *     그 클래스는 SWT 명령 서비스를 새로 고치는 것만 더한 것이라, 여기서는 {@link AbstractCommandContext}를 바로 쓴다.</li>
 *     <li>속성 편집은 SWT 목록 편집({@code ItemListControl.CellEditingSupport} → {@code NodeListPropertySource})처럼
 *     모델 계층의 {@link PropertySourceEditable#setPropertyValue}로 한다. 이 메서드가 객체 관리자의 속성 처리기로 명령을 만들고
 *     같은 객체·속성의 연속 편집은 한 명령으로 합친다. 그래서 드라이버마다 다른 속성(형식, ID, 기본값 등)을 공통 코드로 편집한다.</li>
 *     <li>새 객체·삭제는 SWT의 {@code NavigatorHandlerObjectCreateBase}·{@code NavigatorHandlerObjectDelete}처럼 객체 관리자
 *     ({@link DBEObjectMaker#createNewObject}, {@link DBEObjectMaker#deleteObject})로 한다. 설정 대화상자({@code DBEObjectConfigurator})는
 *     SWT 플러그인에 있으므로 {@link SQLObjectEditor#OPTION_SKIP_CONFIGURATION}으로 건너뛰고, 대화상자 결과는 이 클래스가 넣는다:
 *     PK·UK·열의 키 표시는 SWT {@code EditAttributePage.createConstrainForColumn}과 같은 공통 API
 *     ({@link AbstractTableConstraint#addAttributeReference}), 인덱스·외래 키·CHECK는 드라이버 구현({@link TableDesignerDriver}).</li>
 *     <li>스크립트 미리 보기와 저장은 {@code EntityEditor.showChanges}·{@code saveCommandContext}와 같다(명령 검사 → 실행 동작 →
 *     {@link SQLUtils#generateScript}, 저장은 {@link DBExecUtils#tryExecuteRecover} 안의 {@link DBECommandContext#saveChanges}).
 *     저장 뒤 탐색기 노드를 새 객체면 {@link DBNEvent#FORCE_REFRESH}, 아니면 {@link DBNEvent#UPDATE_ON_SAVE}로 새로 읽는다.
 *     SWT는 이것을 따로 Job으로 돌리지만, 여기서는 디자이너가 새로 읽힌 객체를 다시 보여 주어야 하므로 저장 작업 안에서 이어서 한다.</li>
 *     <li>목록은 탐색기 노드가 아니라 테이블 객체의 API({@link DBSEntity#getAttributes}, {@link DBSEntity#getConstraints},
 *     {@link DBSEntity#getAssociations}, {@link DBSTable#getIndexes})로 읽는다. 새로 만든 객체도 관리자가 모델 캐시에 넣으므로
 *     저장 전 새 테이블에서도 같은 경로로 보인다.</li>
 * </ul>
 * 모든 메서드는 작업 스레드에서 부른다(모델을 읽거나 DB에 접속할 수 있다).
 *
 * 변경 이력:
 * - 2026-11-06 테이블 디자이너: 최초 작성 (docs/chromite/swing-table-designer.md)
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 */
final class TableDesignSession {

    private static final Log log = Log.getLog(TableDesignSession.class);

    /**
     * Property of {@code DBSTypedObjectExt2#setRequired} ("Not Null"), set when a column joins the primary key.
     */
    private static final String PROP_REQUIRED = "required"; //$NON-NLS-1$

    /**
     * Grids of the designer, in tab order.
     */
    enum Section {
        COLUMNS(ToolsMessages.designer_section_columns, DBSEntityAttribute.class),
        KEYS(ToolsMessages.designer_section_keys, DBSEntityConstraint.class),
        FOREIGN_KEYS(ToolsMessages.designer_section_foreign_keys, DBSTableForeignKey.class),
        INDEXES(ToolsMessages.designer_section_indexes, DBSTableIndex.class);

        final String title;
        final Class<?> type;

        Section(@NotNull String title, @NotNull Class<?> type) {
            this.title = title;
            this.type = type;
        }
    }

    /**
     * A key the user chose in the key dialog.
     *
     * @param columns    key columns (PK, UK)
     * @param expression CHECK expression
     */
    record KeySpec(@NotNull String name, @NotNull DBSEntityConstraintType type, @NotNull List<DBSEntityAttribute> columns,
                   @Nullable String expression) {
    }

    private final DBCExecutionContext executionContext;
    private final DBECommandContext commandContext;
    /**
     * One editable property source for all rows, like the SWT list ({@code NodeListPropertySource}): it merges consecutive
     * edits of the same property into one command.
     */
    private final PropertySourceEditable propertySource;
    private final DataSourcePropertyFilter propertyFilter;
    @Nullable
    private final TableDesignerDriver driver;
    private final Class<? extends DBSObject>[] childTypes;
    private DBSEntity table;
    /**
     * The table was created by this session and has not been saved yet.
     */
    private boolean newTable;

    private TableDesignSession(@NotNull DBCExecutionContext executionContext, @NotNull DBSEntity table, boolean newTable,
                               @NotNull DBECommandContext commandContext) throws DBException {
        this.executionContext = executionContext;
        this.commandContext = commandContext;
        this.table = table;
        this.newTable = newTable;
        this.propertySource = new PropertySourceEditable(commandContext, table, table);
        this.propertyFilter = new DataSourcePropertyFilter(table.getDataSource());
        this.driver = GeneralUtils.adapt(table, TableDesignerDriver.class);
        DBEStructEditor<?> tableManager = DBWorkbench.getPlatform().getEditorsRegistry().getObjectManager(table.getClass(), DBEStructEditor.class);
        if (tableManager == null) {
            throw new DBException(NLS.bind(ToolsMessages.designer_error_edit_not_supported, getDriverName(table)));
        }
        this.childTypes = tableManager.getChildTypes();
    }

    /**
     * Opens an existing table.
     */
    @NotNull
    static TableDesignSession open(@NotNull DBSEntity table) throws DBException {
        DBCExecutionContext executionContext = getExecutionContext(table);
        return new TableDesignSession(executionContext, table, false, createCommandContext(executionContext));
    }

    /**
     * Creates a new (unsaved) table in a "Tables" folder, as the SWT "Create New" command: the folder's children are
     * read first so that the navigator gets a node for the new table.
     */
    @NotNull
    static TableDesignSession createTable(@NotNull DBRProgressMonitor monitor, @NotNull DBNDatabaseFolder folder) throws DBException {
        Class<? extends DBSObject> tableClass = folder.getChildrenClass();
        if (!(folder.getValueObject() instanceof DBSObject container) || tableClass == null) {
            throw new DBException(NLS.bind(ToolsMessages.designer_error_table_type, folder.getNodeDisplayName()));
        }
        DBEObjectMaker<DBSObject, Object> tableMaker = getObjectMaker(tableClass);
        if (tableMaker == null || !tableMaker.canCreateObject(container)) {
            throw new DBException(NLS.bind(ToolsMessages.designer_error_create_not_supported, getDriverName(container)));
        }
        if (folder.hasChildren(false)) {
            folder.getChildren(monitor);
        }
        DBCExecutionContext executionContext = getExecutionContext(container);
        DBECommandContext commandContext = createCommandContext(executionContext);
        Map<String, Object> options = new HashMap<>();
        options.put(DBEObjectManager.OPTION_CONTAINER, folder);
        options.put(DBEObjectManager.OPTION_OBJECT_TYPE, tableClass);
        DBSObject newObject = tableMaker.createNewObject(monitor, commandContext, container, null, options);
        if (!(newObject instanceof DBSEntity newTable)) {
            throw new DBException("Table was not created");
        }
        return new TableDesignSession(executionContext, newTable, true, commandContext);
    }

    /**
     * True if the designer can open the object: a table (not a view) whose driver has a table manager.
     */
    static boolean canDesign(@Nullable DBSObject object) {
        return object instanceof DBSTable table && !table.isView()
            && DBWorkbench.getPlatform().getEditorsRegistry().getObjectManager(table.getClass(), DBEStructEditor.class) != null;
    }

    /**
     * True if "New Table" applies to the folder: its children are tables (not views) that the driver can create.
     */
    static boolean canCreateTable(@Nullable DBNDatabaseFolder folder) {
        if (folder == null || !(folder.getValueObject() instanceof DBSObject container)) {
            return false;
        }
        Class<? extends DBSObject> childClass = folder.getChildrenClass();
        if (childClass == null || !DBSTable.class.isAssignableFrom(childClass)
            || DBWorkbench.getPlatform().getEditorsRegistry().getObjectManager(childClass, DBEStructEditor.class) == null) {
            return false;
        }
        DBEObjectMaker<DBSObject, Object> maker = getObjectMaker(childClass);
        return maker != null && maker.canCreateObject(container);
    }

    @NotNull
    private static DBCExecutionContext getExecutionContext(@NotNull DBSObject object) throws DBException {
        DBCExecutionContext executionContext = DBUtils.getDefaultContext(object, false);
        if (executionContext == null) {
            throw new DBException(NLS.bind(ToolsMessages.designer_error_not_connected, object.getName()));
        }
        return executionContext;
    }

    @NotNull
    private static DBECommandContext createCommandContext(@NotNull DBCExecutionContext executionContext) {
        return new AbstractCommandContext(executionContext, false) {
        };
    }

    @NotNull
    private static String getDriverName(@NotNull DBSObject object) {
        DBPDataSourceContainer container = object.getDataSource() == null ? null : object.getDataSource().getContainer();
        return container == null ? ToolsMessages.designer_this_connection : container.getDriver().getName();
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static DBEObjectMaker<DBSObject, Object> getObjectMaker(@NotNull Class<?> objectClass) {
        return DBWorkbench.getPlatform().getEditorsRegistry().getObjectManager(objectClass, DBEObjectMaker.class);
    }

    //////////////////////////////////////////////////////////
    // State

    @NotNull
    DBSEntity getTable() {
        return table;
    }

    boolean isNewTable() {
        return newTable;
    }

    boolean isDirty() {
        return commandContext.isDirty();
    }

    boolean isReadOnly() {
        return DBUtils.isReadOnly(table);
    }

    /**
     * Objects with pending changes (marked in the grids).
     */
    @NotNull
    Set<DBPObject> getEditedObjects() {
        return new LinkedHashSet<>(commandContext.getEditedObjects());
    }

    //////////////////////////////////////////////////////////
    // Lists and properties

    /**
     * The objects of a grid, as the model has them now: the table's lists, plus the new objects of pending create
     * commands and without the objects of pending delete commands. The command context is consulted because some
     * managers keep new objects in another cache than the one the table reads (PostgreSQL foreign keys go to the
     * table's foreign key cache, {@code PostgreTable.getForeignKeys} reads the schema's constraint cache); the SWT editor
     * sees them through the navigator nodes that the add/remove events create.
     */
    @NotNull
    List<DBSObject> readObjects(@NotNull DBRProgressMonitor monitor, @NotNull Section section) throws DBException {
        Collection<? extends DBSObject> objects = switch (section) {
            case COLUMNS -> table.getAttributes(monitor);
            case KEYS -> {
                List<DBSEntityConstraint> keys = new ArrayList<>(CommonUtils.safeCollection(table.getConstraints(monitor)));
                if (driver != null) {
                    keys.addAll(driver.getExtraConstraints(monitor, table));
                }
                yield keys;
            }
            case FOREIGN_KEYS -> table.getAssociations(monitor);
            case INDEXES -> table instanceof DBSTable dbsTable ? dbsTable.getIndexes(monitor) : null;
        };
        Set<DBPObject> deleted = new HashSet<>();
        List<DBSObject> created = new ArrayList<>();
        for (DBECommand<?> command : commandContext.getFinalCommands()) {
            if (command instanceof DBECommandDeleteObject<?>) {
                deleted.add(command.getObject());
            } else if (command.getObject() instanceof DBSObject object && !object.isPersisted() && object.getParentObject() == table) {
                created.add(object);
            }
        }
        List<DBSObject> candidates = new ArrayList<>(CommonUtils.safeCollection(objects));
        for (DBSObject object : created) {
            if (!candidates.contains(object)) {
                candidates.add(object);
            }
        }
        List<DBSObject> result = new ArrayList<>();
        for (DBSObject object : candidates) {
            if (DBUtils.isHiddenObject(object) || !section.type.isInstance(object) || deleted.contains(object)) {
                continue;
            }
            if (section == Section.KEYS && (object instanceof DBSTableForeignKey || object instanceof DBSTableIndex)) {
                // Indexes and foreign keys are constraints too; they have their own grids
                continue;
            }
            result.add(object);
        }
        return result;
    }

    /**
     * Grid columns: the viewable properties of the objects' classes, as the SWT object lists show them
     * ({@code ObjectListControl}): non-hidden, visible for at least one object, ordered by the property order.
     */
    @NotNull
    List<ObjectPropertyDescriptor> getGridProperties(@NotNull Section section, @NotNull List<DBSObject> objects) {
        List<Class<?>> classes = new ArrayList<>();
        Class<?> childType = getChildType(section);
        if (childType != null) {
            classes.add(childType);
        }
        for (DBSObject object : objects) {
            if (!classes.contains(object.getClass())) {
                classes.add(object.getClass());
            }
        }
        // Keep the most specific classes only (a subclass has the properties of its superclasses)
        classes.removeIf(c -> classes.stream().anyMatch(other -> other != c && c.isAssignableFrom(other)));
        List<ObjectPropertyDescriptor> properties = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (ObjectPropertyDescriptor property : ObjectAttributeDescriptor.extractAnnotations(propertySource, classes, propertyFilter)) {
            if (!property.isViewable() || property.isHidden() || !ids.add(property.getId())) {
                continue;
            }
            if (!objects.isEmpty() && objects.stream().noneMatch(o -> property.getDeclaringClass().isInstance(o) && property.isPropertyVisible(o, o))) {
                continue;
            }
            properties.add(property);
        }
        return properties;
    }

    /**
     * All properties of one object for the property sheet below a grid (including the non-viewable ones).
     */
    @NotNull
    List<ObjectPropertyDescriptor> getObjectProperties(@NotNull DBSObject object) {
        List<ObjectPropertyDescriptor> properties = new ArrayList<>();
        for (ObjectPropertyDescriptor property : ObjectAttributeDescriptor.extractAnnotations(propertySource, List.of(object.getClass()), propertyFilter)) {
            if (!property.isHidden() && property.isPropertyVisible(object, object)) {
                properties.add(property);
            }
        }
        return properties;
    }

    /**
     * The value of a property (lazy values are read with the monitor), formatted as the property renders it.
     */
    @Nullable
    Object readValue(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object, @NotNull ObjectPropertyDescriptor property) {
        if (!property.getDeclaringClass().isInstance(object)) {
            return null;
        }
        return propertySource.getPropertyValue(monitor, object, property, true);
    }

    /**
     * Whether a property cell can be edited: the object manager allows it and the property is editable for new
     * objects or updatable for saved ones ({@link ObjectPropertyDescriptor#isEditable}).
     */
    boolean isEditable(@NotNull DBSObject object, @NotNull ObjectPropertyDescriptor property) {
        return !isReadOnly() && property.getDeclaringClass().isInstance(object) && property.isEditable(object);
    }

    /**
     * Changes a property: a command in the command context (SWT {@code ItemListControl.CellEditingSupport.setValue}).
     * Texts are trimmed like the SWT editors do ({@code UIUtils.normalizePropertyValue}).
     */
    void setValue(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object, @NotNull ObjectPropertyDescriptor property, @Nullable Object value) {
        Object normalized = value instanceof String text ? text.trim() : value;
        propertySource.setPropertyValue(monitor, object, property, normalized);
    }

    //////////////////////////////////////////////////////////
    // Create and delete

    @Nullable
    @SuppressWarnings("unchecked")
    private Class<? extends DBSObject> getChildType(@NotNull Section section) {
        return section == Section.KEYS ? null : (Class<? extends DBSObject>) BeanUtils.findAssignableType(childTypes, (Class<Object>) section.type);
    }

    /**
     * Whether a grid can add objects: the table manager has a child type for it with an object manager.
     * Indexes and foreign keys also need the driver part ({@link TableDesignerDriver}).
     */
    boolean canCreate(@NotNull Section section) {
        if (isReadOnly()) {
            return false;
        }
        return switch (section) {
            case COLUMNS -> getChildType(section) != null && getObjectMaker(getChildType(section)) != null;
            case KEYS -> !getKeyTypes().isEmpty();
            case FOREIGN_KEYS, INDEXES -> driver != null && getChildType(section) != null && getObjectMaker(getChildType(section)) != null;
        };
    }

    boolean canDelete(@NotNull DBSObject object) {
        DBEObjectMaker<DBSObject, Object> maker = getObjectMaker(object.getClass());
        return !isReadOnly() && maker != null && maker.canDeleteObject(object);
    }

    /**
     * Adds a column with the manager's defaults (name, type), like a new row in the SSMS designer.
     */
    @NotNull
    DBSObject createColumn(@NotNull DBRProgressMonitor monitor) throws DBException {
        return createChild(monitor, getRequiredChildType(Section.COLUMNS));
    }

    void delete(@NotNull DBSObject object) throws DBException {
        DBEObjectMaker<DBSObject, Object> maker = getObjectMaker(object.getClass());
        if (maker == null) {
            throw new DBException("Can't delete " + object.getName() + ": no object manager");
        }
        maker.deleteObject(commandContext, object, new HashMap<>());
    }

    @NotNull
    private Class<? extends DBSObject> getRequiredChildType(@NotNull Section section) throws DBException {
        Class<? extends DBSObject> childType = getChildType(section);
        if (childType == null) {
            throw new DBException(NLS.bind(ToolsMessages.designer_error_section_not_supported, section.title, getDriverName(table)));
        }
        return childType;
    }

    @NotNull
    private DBSObject createChild(@NotNull DBRProgressMonitor monitor, @NotNull Class<?> childClass) throws DBException {
        DBEObjectMaker<DBSObject, Object> maker = getObjectMaker(childClass);
        if (maker == null) {
            throw new DBException("Object manager not found for type '" + childClass.getName() + "'");
        }
        Map<String, Object> options = new HashMap<>();
        // The SWT configuration dialogs are not available; the designer fills the object itself
        options.put(SQLObjectEditor.OPTION_SKIP_CONFIGURATION, true);
        options.put(DBEObjectManager.OPTION_OBJECT_TYPE, childClass);
        DBSObject newObject = maker.createNewObject(monitor, commandContext, table, null, options);
        if (newObject == null) {
            throw new DBException("Object of type '" + childClass.getSimpleName() + "' was not created");
        }
        return newObject;
    }

    /**
     * Key types offered by the key dialog: the unique keys the table supports with the generic column API
     * ({@code EditAttributePage.createKeysGroup}), and CHECK if the driver part supports it.
     */
    @NotNull
    List<DBSEntityConstraintType> getKeyTypes() {
        List<DBSEntityConstraintType> types = new ArrayList<>();
        if (isReadOnly()) {
            return types;
        }
        for (DBSEntityConstraintInfo info : getUniqueKeyInfos()) {
            if (!types.contains(info.getType())) {
                types.add(info.getType());
            }
        }
        if (driver != null && driver.getCheckConstraintClass(table) != null) {
            types.add(DBSEntityConstraintType.CHECK);
        }
        return types;
    }

    @NotNull
    private List<DBSEntityConstraintInfo> getUniqueKeyInfos() {
        List<DBSEntityConstraintInfo> infos = new ArrayList<>();
        if (table instanceof DBSEntityConstrainable constrainable) {
            for (DBSEntityConstraintInfo info : constrainable.getSupportedConstraints()) {
                if (info.getType().isUnique() && AbstractTableConstraint.class.isAssignableFrom(info.getImplClass())
                    && getObjectMaker(info.getImplClass()) != null) {
                    infos.add(info);
                }
            }
        }
        return infos;
    }

    /**
     * Default name of a new key ({@code ConstraintNameGenerator}: table name with a type suffix), unique in the table.
     */
    @NotNull
    String suggestKeyName(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntityConstraintType type) throws DBException {
        String suffix = type == DBSEntityConstraintType.PRIMARY_KEY ? "_pk" //$NON-NLS-1$
            : type == DBSEntityConstraintType.CHECK ? "_check" : "_unique"; //$NON-NLS-1$ //$NON-NLS-2$
        return makeUniqueName(monitor, Section.KEYS, table.getName() + suffix);
    }

    @NotNull
    String suggestIndexName(@NotNull DBRProgressMonitor monitor, @Nullable DBSEntityAttribute firstColumn) throws DBException {
        // As the SWT index configurators: table, first column, "_idx"
        return makeUniqueName(monitor, Section.INDEXES,
            table.getName() + (firstColumn == null ? "" : "_" + firstColumn.getName()) + "_idx"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    private String makeUniqueName(@NotNull DBRProgressMonitor monitor, @NotNull Section section, @NotNull String baseName) throws DBException {
        String base = DBObjectNameCaseTransformer.transformName(table.getDataSource(), baseName);
        Set<String> names = new HashSet<>();
        for (DBSObject object : readObjects(monitor, section)) {
            names.add(object.getName().toLowerCase(Locale.ENGLISH));
        }
        String name = base;
        for (int i = 1; names.contains(name.toLowerCase(Locale.ENGLISH)); i++) {
            name = base + "_" + i; //$NON-NLS-1$
        }
        return name;
    }

    /**
     * Adds a PK, UK or CHECK constraint.
     */
    @NotNull
    DBSEntityConstraint createKey(@NotNull DBRProgressMonitor monitor, @NotNull KeySpec spec) throws DBException {
        Class<?> keyClass;
        if (spec.type() == DBSEntityConstraintType.CHECK) {
            keyClass = driver == null ? null : driver.getCheckConstraintClass(table);
        } else {
            keyClass = getUniqueKeyInfos().stream().filter(i -> i.getType() == spec.type()).map(DBSEntityConstraintInfo::getImplClass)
                .findFirst().orElse(null);
        }
        if (keyClass == null) {
            throw new DBException(NLS.bind(ToolsMessages.designer_error_key_not_supported, spec.type().getLocalizedName(), getDriverName(table)));
        }
        DBSObject newObject = createChild(monitor, keyClass);
        if (!(newObject instanceof DBSEntityConstraint constraint)) {
            throw new DBException("Unexpected key object " + newObject.getClass().getName());
        }
        if (constraint instanceof DBPNamedObject2 named && !CommonUtils.isEmpty(spec.name())) {
            named.setName(DBObjectNameCaseTransformer.transformName(table.getDataSource(), spec.name()));
        }
        if (spec.type() == DBSEntityConstraintType.CHECK) {
            if (driver == null || CommonUtils.isEmpty(spec.expression())) {
                throw new DBException(ToolsMessages.designer_error_check_empty);
            }
            driver.setCheckExpression(constraint, spec.expression());
        } else if (constraint instanceof AbstractTableConstraint<?, ?> tableConstraint) {
            // EditAttributePage.createConstrainForColumn
            tableConstraint.setConstraintType(spec.type());
            for (DBSEntityAttribute column : spec.columns()) {
                tableConstraint.addAttributeReference((DBSTableColumn) column);
            }
        }
        recache(constraint);
        return constraint;
    }

    /**
     * Adds an index (configured by the driver part, as the SWT index configurators do after their dialog).
     */
    @NotNull
    DBSTableIndex createIndex(@NotNull DBRProgressMonitor monitor, @NotNull String name, boolean unique,
                              @NotNull List<TableDesignerDriver.IndexColumn> columns) throws DBException {
        if (driver == null) {
            throw new DBException(NLS.bind(ToolsMessages.designer_error_index_not_supported, getDriverName(table)));
        }
        DBSObject newObject = createChild(monitor, getRequiredChildType(Section.INDEXES));
        if (!(newObject instanceof DBSTableIndex index)) {
            throw new DBException("Unexpected index object " + newObject.getClass().getName());
        }
        driver.configureIndex(index, unique, columns);
        if (index instanceof DBPNamedObject2 named) {
            named.setName(DBObjectNameCaseTransformer.transformObjectName(index, name));
        }
        recache(index);
        return index;
    }

    /**
     * Adds a foreign key. An empty name gets the manager's generated name ({@link SQLForeignKeyManager#updateForeignKeyName}),
     * as the SWT foreign key configurators do.
     */
    @NotNull
    DBSTableForeignKey createForeignKey(
        @NotNull DBRProgressMonitor monitor,
        @Nullable String name,
        @NotNull DBSEntityConstraint referencedKey,
        @NotNull DBSForeignKeyModifyRule deleteRule,
        @NotNull DBSForeignKeyModifyRule updateRule,
        @NotNull List<TableDesignerDriver.ForeignKeyColumn> columns
    ) throws DBException {
        if (driver == null) {
            throw new DBException(NLS.bind(ToolsMessages.designer_error_foreign_key_not_supported, getDriverName(table)));
        }
        DBSObject newObject = createChild(monitor, getRequiredChildType(Section.FOREIGN_KEYS));
        if (!(newObject instanceof DBSTableForeignKey foreignKey)) {
            throw new DBException("Unexpected foreign key object " + newObject.getClass().getName());
        }
        driver.configureForeignKey(foreignKey, referencedKey, deleteRule, updateRule, columns);
        if (!CommonUtils.isEmpty(name) && foreignKey instanceof DBPNamedObject2 named) {
            named.setName(DBObjectNameCaseTransformer.transformObjectName(foreignKey, name));
        } else if (foreignKey instanceof AbstractTableConstraint<?, ?> tableConstraint) {
            SQLForeignKeyManager.updateForeignKeyName(monitor, tableConstraint);
        }
        recache(foreignKey);
        return foreignKey;
    }

    /**
     * Object caches are keyed by name; the name was set after the manager cached the new object
     * (as {@code PropertySourceEditable} does after a rename).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void recache(@NotNull DBSObject object) {
        DBEObjectMaker maker = getObjectMaker(object.getClass());
        DBSObjectCache cache = maker == null ? null : maker.getObjectsCache(object);
        if (cache != null && cache.isFullyCached()) {
            cache.setCache(CommonUtils.copyList(cache.getCachedObjects()));
        }
    }

    /**
     * Tables a new foreign key can reference: the tables of the edited table's container (schema or catalog).
     */
    @NotNull
    List<DBSEntity> readReferenceTables(@NotNull DBRProgressMonitor monitor) throws DBException {
        List<DBSEntity> tables = new ArrayList<>();
        if (table.getParentObject() instanceof DBSObjectContainer container) {
            for (DBSObject child : CommonUtils.safeCollection(container.getChildren(monitor))) {
                if (child instanceof DBSTable childTable && !childTable.isView() && child.isPersisted()) {
                    tables.add(childTable);
                }
            }
        }
        if (!tables.contains(table)) {
            tables.add(table);
        }
        return tables;
    }

    /**
     * Unique keys (PK, UK) of a table, which a foreign key can reference.
     */
    @NotNull
    static List<DBSEntityConstraint> readUniqueKeys(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity entity) throws DBException {
        List<DBSEntityConstraint> keys = new ArrayList<>();
        for (DBSEntityConstraint constraint : CommonUtils.safeCollection(entity.getConstraints(monitor))) {
            if (constraint.getConstraintType().isUnique() && !(constraint instanceof DBSTableIndex)) {
                keys.add(constraint);
            }
        }
        return keys;
    }

    /**
     * Columns of a key, in key order.
     */
    @NotNull
    static List<DBSEntityAttribute> readKeyColumns(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntityConstraint key) throws DBException {
        List<DBSEntityAttribute> columns = new ArrayList<>();
        if (key instanceof DBSEntityReferrer referrer) {
            for (DBSEntityAttributeRef ref : CommonUtils.safeCollection(referrer.getAttributeReferences(monitor))) {
                if (ref.getAttribute() != null) {
                    columns.add(ref.getAttribute());
                }
            }
        }
        return columns;
    }

    //////////////////////////////////////////////////////////
    // Primary key column flag (SSMS "Set Primary Key")

    boolean canEditPrimaryKey() {
        return getUniqueKeyInfos().stream().anyMatch(i -> i.getType() == DBSEntityConstraintType.PRIMARY_KEY);
    }

    @Nullable
    DBSEntityConstraint findPrimaryKey(@NotNull DBRProgressMonitor monitor) throws DBException {
        for (DBSObject object : readObjects(monitor, Section.KEYS)) {
            if (object instanceof DBSEntityConstraint constraint && constraint.getConstraintType() == DBSEntityConstraintType.PRIMARY_KEY) {
                return constraint;
            }
        }
        return null;
    }

    /**
     * Adds a column to the primary key or removes it, as the SSMS designer's key toggle. A new (unsaved) key is changed
     * in place; a saved key is dropped and created again with the new column list (SSMS does the same). A column that
     * joins the key becomes NOT NULL.
     */
    void setPrimaryKeyColumn(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntityAttribute column, boolean inKey) throws DBException {
        DBSEntityConstraint primaryKey = findPrimaryKey(monitor);
        List<DBSEntityAttribute> keyColumns = primaryKey == null ? new ArrayList<>() : readKeyColumns(monitor, primaryKey);
        if (keyColumns.contains(column) == inKey) {
            return;
        }
        if (inKey) {
            keyColumns.add(column);
            setRequired(monitor, column);
        } else {
            keyColumns.remove(column);
        }
        if (primaryKey == null) {
            createKey(monitor, new KeySpec(suggestKeyName(monitor, DBSEntityConstraintType.PRIMARY_KEY),
                DBSEntityConstraintType.PRIMARY_KEY, keyColumns, null));
            return;
        }
        if (!primaryKey.isPersisted() && primaryKey instanceof AbstractTableConstraint<?, ?> tableConstraint) {
            if (keyColumns.isEmpty()) {
                delete(primaryKey);
            } else if (inKey) {
                tableConstraint.addAttributeReference((DBSTableColumn) column);
            } else {
                removeKeyColumn(monitor, tableConstraint, column);
            }
            return;
        }
        String keyName = primaryKey.getName();
        delete(primaryKey);
        if (!keyColumns.isEmpty()) {
            createKey(monitor, new KeySpec(keyName, DBSEntityConstraintType.PRIMARY_KEY, keyColumns, null));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void removeKeyColumn(@NotNull DBRProgressMonitor monitor, @NotNull AbstractTableConstraint constraint,
                                        @NotNull DBSEntityAttribute column) throws DBException {
        List refs = new ArrayList(CommonUtils.safeCollection(constraint.getAttributeReferences(monitor)));
        refs.removeIf(ref -> ref instanceof DBSEntityAttributeRef attributeRef && attributeRef.getAttribute() == column);
        constraint.setAttributeReferences(refs);
    }

    private void setRequired(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntityAttribute column) {
        for (ObjectPropertyDescriptor property : getObjectProperties(column)) {
            if (PROP_REQUIRED.equals(property.getId()) && property.isEditable(column)
                && !Boolean.TRUE.equals(readValue(monitor, column, property))) {
                setValue(monitor, column, property, Boolean.TRUE);
                return;
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Script and save

    /**
     * The script of the pending changes ({@code EntityEditor.showChanges}); empty when nothing changed.
     */
    @NotNull
    String generateScript(@NotNull DBRProgressMonitor monitor) throws DBException {
        Collection<? extends DBECommand<?>> commands = commandContext.getFinalCommands();
        StringBuilder script = new StringBuilder();
        monitor.beginTask(ToolsMessages.designer_progress_generate_script, commands.size());
        Map<String, Object> validateOptions = new HashMap<>();
        for (DBECommand<?> command : commands) {
            monitor.subTask(command.getTitle());
            command.validateCommand(monitor, validateOptions);
            Map<String, Object> options = new HashMap<>();
            options.put(DBPScriptObject.OPTION_OBJECT_SAVE, true);
            DBEPersistAction[] actions = command.getPersistActions(monitor, executionContext, options);
            script.append(SQLUtils.generateScript(table.getDataSource(), actions, false));
            monitor.worked(1);
        }
        monitor.done();
        return script.toString();
    }

    /**
     * Executes the pending changes ({@code EntityEditor.saveCommandContext}) and reads the table again from the database.
     */
    void save(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (!table.getDataSource().getContainer().hasModifyPermission(DBPDataSourcePermission.PERMISSION_EDIT_METADATA)) {
            throw new DBException(NLS.bind(ToolsMessages.designer_error_metadata_restricted,
                table.getDataSource().getContainer().getName()));
        }
        boolean isNewObject = newTable || !table.isPersisted();
        if (!isNewObject) {
            for (DBECommand<?> command : commandContext.getFinalCommands()) {
                if (command.getObject() instanceof DBSObject object && !object.isPersisted()) {
                    isNewObject = true;
                    break;
                }
            }
        }
        DBExecUtils.tryExecuteRecover(monitor, executionContext.getDataSource(), param ->
            commandContext.saveChanges(monitor, new HashMap<>()));
        newTable = false;
        if (table instanceof DBPStatefulObject statefulObject) {
            try {
                statefulObject.refreshObjectState(monitor);
            } catch (DBCException e) {
                log.error(e);
            }
        }
        // Read the saved object and its children from the database (EntityEditor refreshes the editor's node)
        DBNDatabaseNode node = DBWorkbench.getPlatform().getNavigatorModel().getNodeByObject(monitor, table, false);
        if (node != null) {
            node.refreshNode(monitor, isNewObject ? DBNEvent.FORCE_REFRESH : DBNEvent.UPDATE_ON_SAVE);
            if (node.getObject() instanceof DBSEntity refreshed) {
                table = refreshed;
            }
        }
    }

    /**
     * Discards the pending changes (a new table disappears).
     */
    void revert() {
        commandContext.resetChanges(true);
    }
}
