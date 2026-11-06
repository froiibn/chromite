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

import com.seizia.chromite.ui.swing.internal.DbCopyMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataTypeProvider;
import org.jkiss.dbeaver.model.DBPSystemObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.DBObjectNameCaseTransformer;
import org.jkiss.dbeaver.model.impl.struct.RelationalObjectType;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.MonitorRunnableContext;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSDataManipulator;
import org.jkiss.dbeaver.model.struct.DBSDataType;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSNamespace;
import org.jkiss.dbeaver.model.struct.DBSNamespaceContainer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.tools.transfer.DataTransferPipe;
import org.jkiss.dbeaver.tools.transfer.DataTransferSettings;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseConsumerSettings;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseMappingAttribute;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseMappingContainer;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseMappingType;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseTransferUtils;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 테이블 복사(Database 대상)의 매핑 처리: 대상 컨테이너 찾기, 원본 테이블마다 대상 테이블 정하기, 열 대상·형식 바꾸기, 대상 DDL 만들기.
 *
 * 설계: SWT {@code DatabaseConsumerPageMapping}이 모델({@link DatabaseMappingContainer}, {@link DatabaseMappingAttribute},
 * {@link DatabaseConsumerSettings}, {@link DatabaseTransferUtils})을 부르는 부분을 화면과 떼어 그대로 옮겼다. 화면({@link DatabaseCopyPages})은
 * 이 클래스의 정적 메서드만 부르므로 SWT와 같은 매핑 결과(이름 대소문자 변환, 기존 테이블 찾기, 새 테이블의 형식 매핑)가 나온다.
 * SWT는 오류 문구를 페이지 필드에 두지만, 여기서는 반환값으로 돌려준다. 대상 DDL 미리 보기는 SWT {@code ConfigureMetadataStructureDialog}의
 * {@code generateTableActions}와 같이 {@link DatabaseTransferUtils#generateTargetTableDDL}을 부른다. 단, 기존 테이블에 열을 더하는 경우는 부르지 않는다:
 * 모델이 새 열 객체를 실제 테이블의 열 캐시에 넣기 때문에({@code generateStructTableDDL}의 {@code cacheObject}), 미리 보기만 해도 없는 열이 있는 것처럼 된다.
 *
 * 변경 이력:
 * - 2026-11-06 테이블 복사: 최초 작성 (docs/chromite/swing-data-transfer.md)
 */
final class DatabaseCopyMappings {

    private DatabaseCopyMappings() {
    }

    //////////////////////////////////////////////////////////
    // Target containers

    /**
     * Whether tables can be created in or copied into a container: its main children are tables (MySQL database, schema).
     */
    static boolean isTableContainer(@NotNull DBRProgressMonitor monitor, @NotNull DBSObjectContainer container) throws DBException {
        Class<? extends DBSObject> childType = container.getPrimaryChildType(monitor);
        return childType != null && DBSEntity.class.isAssignableFrom(childType);
    }

    /**
     * Child containers of a connection or database that can hold tables or schemas (databases of a server, schemas of a
     * database), without hidden and system ones (pg_catalog, sys, …), by name.
     */
    @NotNull
    static List<DBSObjectContainer> readChildContainers(@NotNull DBRProgressMonitor monitor, @NotNull DBSObjectContainer parent) throws DBException {
        List<DBSObjectContainer> result = new ArrayList<>();
        for (DBSObject child : CommonUtils.safeCollection(parent.getChildren(monitor))) {
            if (child instanceof DBSObjectContainer container && !(child instanceof DBSEntity) && !DBUtils.isHiddenObject(child)
                && !(child instanceof DBPSystemObject system && system.isSystem())) {
                result.add(container);
            }
        }
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    /**
     * Names of the writable tables of the target container, as a target name list (SWT {@code createTargetEditor}).
     */
    @NotNull
    static List<String> readTargetTableNames(@NotNull DBRProgressMonitor monitor, @NotNull DatabaseConsumerSettings settings) throws DBException {
        List<String> names = new ArrayList<>();
        DBSObjectContainer container = settings.getContainer();
        if (container != null) {
            for (DBSObject child : CommonUtils.safeCollection(container.getChildren(monitor))) {
                if (child instanceof DBSDataManipulator) {
                    names.add(DBUtils.getQuotedIdentifier(child));
                }
            }
        }
        return names;
    }

    //////////////////////////////////////////////////////////
    // Table mappings

    /**
     * Creates a mapping for each source of the pipes and maps it to a table of the target container, as the SWT page
     * does when its container is set ({@code loadAndUpdateColumnsModel}, then {@code autoAssignMappings}).
     *
     * @param updateAttributesNames whether column names follow the target's identifier case (SWT: true when the
     *                              container is changed, false for the first assignment)
     * @return why some tables could not be mapped (shown to the user), empty when all were mapped
     */
    @NotNull
    static List<String> createMappings(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DataTransferSettings settings,
        @NotNull DatabaseConsumerSettings consumerSettings,
        boolean updateAttributesNames
    ) throws DBException {
        List<DataTransferPipe> pipes = settings.getDataPipes();
        if (pipes.size() > 1) {
            // Referenced tables first (SWT autoAssignMappings)
            settings.sortDataPipes(monitor);
        }
        Set<DBSObjectContainer> sourceContainers = new LinkedHashSet<>();
        for (DataTransferPipe pipe : pipes) {
            if (pipe.getProducer() != null && pipe.getProducer().getDatabaseObject() instanceof DBSDataContainer source
                && DBUtils.getAdapter(DBSObjectContainer.class, source.getParentObject()) instanceof DBSObjectContainer container) {
                sourceContainers.add(container);
            }
        }
        for (DBSObjectContainer container : sourceContainers) {
            container.cacheStructure(monitor, DBSObjectContainer.STRUCT_ATTRIBUTES);
        }
        List<String> errors = new ArrayList<>();
        for (DataTransferPipe pipe : pipes) {
            if (pipe.getProducer() == null || !(pipe.getProducer().getDatabaseObject() instanceof DBSDataContainer source)) {
                continue;
            }
            DatabaseMappingContainer mapping = consumerSettings.getDataMapping(source);
            if (mapping == null) {
                mapping = new DatabaseMappingContainer(consumerSettings, source);
                mapping.getAttributeMappings(monitor);
                consumerSettings.addDataMappings(new MonitorRunnableContext(monitor), source, mapping);
            }
            String error = setTableTarget(monitor, consumerSettings, mapping, mapping.getTargetName(), true, updateAttributesNames);
            if (error != null) {
                errors.add(error);
            }
        }
        return errors;
    }

    /**
     * The mappings of the pipes, in the pipes' order.
     */
    @NotNull
    static List<DatabaseMappingContainer> getMappings(@NotNull DataTransferSettings settings, @NotNull DatabaseConsumerSettings consumerSettings) {
        List<DatabaseMappingContainer> mappings = new ArrayList<>();
        for (DataTransferPipe pipe : settings.getDataPipes()) {
            if (pipe.getProducer() != null && pipe.getProducer().getDatabaseObject() instanceof DBSDataContainer source) {
                DatabaseMappingContainer mapping = consumerSettings.getDataMapping(source);
                if (mapping != null) {
                    mappings.add(mapping);
                }
            }
        }
        return mappings;
    }

    /**
     * Sets the target table of a source table by name: an existing table of the target container (existing), a new
     * table (create) or {@link DatabaseMappingAttribute#TARGET_NAME_SKIP} (SWT {@code setMappingTarget} and
     * {@code setMappingTargetContainer}).
     *
     * @return why the name can't be used (another kind of object has it), or null
     */
    @Nullable
    static String setTableTarget(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DatabaseConsumerSettings settings,
        @NotNull DatabaseMappingContainer mapping,
        @NotNull String name,
        boolean forceRefresh,
        boolean updateAttributesNames
    ) throws DBException {
        if (name.equals(DatabaseMappingAttribute.TARGET_NAME_SKIP)) {
            mapping.refreshMappingType(monitor, DatabaseMappingType.skip, false, false);
            return null;
        }
        name = transformTargetName(settings, name, mapping.getMappingType());
        DBSObjectContainer container = settings.getContainer();
        if (container != null && container.getDataSource() != null) {
            String unquotedName = DBUtils.getUnQuotedIdentifier(container.getDataSource(), name);
            DBSDataManipulator existingTable = null;
            // A view or sequence of that name can't receive rows (SWT: name conflict in the namespace)
            DBSNamespaceContainer namespaceContainer = DBUtils.getAdapter(DBSNamespaceContainer.class, container);
            DBSNamespace namespace = namespaceContainer == null ? null : namespaceContainer.getNamespaceForObjectType(RelationalObjectType.TYPE_TABLE);
            if (namespace != null) {
                DBSObject existingObject = namespace.getObjectByName(monitor, unquotedName);
                if (existingObject instanceof DBSDataManipulator manipulator) {
                    existingTable = manipulator;
                } else if (existingObject != null) {
                    mapping.setTargetName(name);
                    mapping.refreshMappingType(monitor, DatabaseMappingType.unspecified, false, false);
                    return NLS.bind(DbCopyMessages.mapping_error_name_used, unquotedName, DBUtils.getObjectTypeName(existingObject));
                }
            }
            if (existingTable == null) {
                for (DBSObject child : CommonUtils.safeCollection(container.getChildren(monitor))) {
                    if (child instanceof DBSDataManipulator manipulator && unquotedName.equalsIgnoreCase(child.getName())) {
                        existingTable = manipulator;
                        break;
                    }
                }
            }
            if (existingTable != null) {
                mapping.setTarget(existingTable);
                if (forceRefresh && mapping.getMappingType() == DatabaseMappingType.recreate) {
                    // Keep "recreate", refresh only the columns
                    mapping.refreshAttributesMappingTypes(monitor, false, false);
                } else {
                    mapping.refreshMappingType(monitor, DatabaseMappingType.existing, false, false);
                }
                return null;
            }
        }
        if (forceRefresh && mapping.getMappingType() == DatabaseMappingType.recreate) {
            mapping.refreshAttributesMappingTypes(monitor, false, false);
        } else {
            mapping.setTarget(null);
            mapping.setTargetName(name);
            mapping.refreshMappingType(monitor, DatabaseMappingType.create, forceRefresh, updateAttributesNames);
        }
        return null;
    }

    /**
     * Changes how a table is copied: existing ↔ recreate (drop and create the existing table), skip, or copied again
     * after a skip (SWT {@code applyMappingType}, {@code applyMappingSkip}, {@code applyMappingTransfer}).
     */
    static void setTableMappingType(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DatabaseMappingContainer mapping,
        @NotNull DatabaseMappingType type
    ) throws DBException {
        if (type == DatabaseMappingType.skip) {
            mapping.refreshMappingType(monitor, DatabaseMappingType.skip, false, false);
            return;
        }
        if (mapping.getMappingType() == DatabaseMappingType.skip) {
            for (DatabaseMappingAttribute attribute : mapping.getAttributeMappings(monitor)) {
                if (attribute.getMappingType() == DatabaseMappingType.skip) {
                    attribute.setMappingType(DatabaseMappingType.unspecified);
                }
            }
            mapping.setExtraMappingState(null);
        }
        if (type == DatabaseMappingType.recreate && mapping.getTarget() != null) {
            mapping.refreshMappingType(monitor, DatabaseMappingType.recreate, false, false);
        } else {
            mapping.refreshMappingType(monitor, mapping.getTarget() != null ? DatabaseMappingType.existing : DatabaseMappingType.create, false, false);
        }
    }

    /**
     * The mapping types a table can be switched to (SWT {@code createMappingTypeEditor}; "recreate" only for a table
     * that exists, since this wizard is not saved as a task).
     */
    @NotNull
    static List<DatabaseMappingType> getTableMappingTypes(@NotNull DatabaseMappingContainer mapping) {
        List<DatabaseMappingType> types = new ArrayList<>();
        if (mapping.getTarget() != null) {
            types.add(DatabaseMappingType.existing);
            types.add(DatabaseMappingType.recreate);
        } else {
            types.add(DatabaseMappingType.create);
        }
        types.add(DatabaseMappingType.skip);
        return types;
    }

    /**
     * Target name case as the SWT page writes a typed or found name ({@code transformTargetName}).
     */
    @NotNull
    private static String transformTargetName(@NotNull DatabaseConsumerSettings settings, @NotNull String name, @NotNull DatabaseMappingType type) {
        DBSObjectContainer container = settings.getContainer();
        DBPDataSource dataSource = container == null ? null : container.getDataSource();
        if (dataSource == null) {
            return name;
        }
        if (type == DatabaseMappingType.create) {
            return DatabaseTransferUtils.getTransformedName(dataSource, name, false);
        }
        return DBObjectNameCaseTransformer.transformName(dataSource, name);
    }

    /**
     * A typed target name in the target's identifier case unless it is quoted (SWT target cell {@code setValue}).
     */
    @NotNull
    static String normalizeTypedName(@NotNull DatabaseConsumerSettings settings, @NotNull DatabaseMappingAttribute attribute, @NotNull String name) {
        DBPDataSource dataSource = settings.getTargetDataSource(attribute);
        if (!name.equals(DatabaseMappingAttribute.TARGET_NAME_SKIP) && dataSource != null && !DBUtils.isQuotedIdentifier(dataSource, name)) {
            return DBObjectNameCaseTransformer.transformName(dataSource, name);
        }
        return name;
    }

    //////////////////////////////////////////////////////////
    // Column mappings

    /**
     * Sets the target column of a source column: a column of the existing target table (existing), a new column
     * (create) or {@link DatabaseMappingAttribute#TARGET_NAME_SKIP} (SWT {@code setMappingTarget} for attributes).
     */
    static void setColumnTarget(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DatabaseConsumerSettings settings,
        @NotNull DatabaseMappingAttribute attribute,
        @NotNull String name
    ) throws DBException {
        if (name.equals(DatabaseMappingAttribute.TARGET_NAME_SKIP)) {
            attribute.setMappingType(DatabaseMappingType.skip);
            return;
        }
        name = transformTargetName(settings, name, attribute.getMappingType());
        DBPDataSource targetDataSource = settings.getTargetDataSource(attribute);
        if (attribute.getParent().getTarget() instanceof DBSEntity parentEntity) {
            Collection<? extends DBSEntityAttribute> columns = parentEntity.getAttributes(monitor);
            if (columns != null) {
                DBSEntityAttribute match = CommonUtils.findBestCaseAwareMatch(
                    columns,
                    targetDataSource != null ? DBUtils.getUnQuotedIdentifier(targetDataSource, name) : name,
                    DBSEntityAttribute::getName);
                if (match != null) {
                    attribute.setMappingType(DatabaseMappingType.existing);
                    attribute.setTarget(match);
                    attribute.setTargetName(name);
                    return;
                }
            }
        }
        attribute.setMappingType(DatabaseMappingType.create);
        attribute.setTargetName(name);
    }

    /**
     * Columns of the existing target table, without hidden and pseudo columns.
     */
    @NotNull
    static List<DBSEntityAttribute> readTargetColumns(@NotNull DBRProgressMonitor monitor, @NotNull DatabaseMappingContainer mapping) throws DBException {
        List<DBSEntityAttribute> columns = new ArrayList<>();
        if (mapping.getTarget() instanceof DBSEntity entity) {
            for (DBSEntityAttribute attribute : CommonUtils.safeCollection(entity.getAttributes(monitor))) {
                if (!DBUtils.isHiddenObject(attribute) && !DBUtils.isPseudoAttribute(attribute)) {
                    columns.add(attribute);
                }
            }
        }
        return columns;
    }

    /**
     * Data types of the target database for a new column: the dialect's names and the database's own types, and the
     * mapped type (SWT {@code ColumnsMappingDialog} target type editor).
     */
    @NotNull
    static List<String> getTargetTypes(@NotNull DatabaseConsumerSettings settings, @NotNull DatabaseMappingAttribute attribute) {
        Map<String, String> types = new TreeMap<>();
        DBSObjectContainer container = settings.getContainer();
        DBPDataSource dataSource = container == null ? null : container.getDataSource();
        if (dataSource != null) {
            for (String type : CommonUtils.safeCollection(dataSource.getSQLDialect().getDataTypes(dataSource))) {
                types.put(type.toUpperCase(Locale.ROOT), type);
            }
        }
        DBPDataTypeProvider typeProvider = DBUtils.getParentOfType(DBPDataTypeProvider.class, container);
        if (typeProvider != null) {
            for (DBSDataType type : CommonUtils.safeCollection(typeProvider.getLocalDataTypes())) {
                types.put(type.getName().toUpperCase(Locale.ROOT), type.getName());
            }
        }
        String mapped = attribute.getTargetType(settings.getTargetDataSource(attribute), true);
        types.put(mapped.toUpperCase(Locale.ROOT), mapped);
        return new ArrayList<>(types.values());
    }

    //////////////////////////////////////////////////////////
    // DDL

    /**
     * The statements that create the target table of a new or recreated mapping (SWT {@code ConfigureMetadataStructureDialog}
     * "Target DDL"), or null when the table structure doesn't change or only columns are added to an existing table
     * (see the class comment).
     */
    @Nullable
    static String generateTableDdl(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DatabaseConsumerSettings settings,
        @NotNull DatabaseMappingContainer mapping
    ) throws DBException {
        DBSObjectContainer container = settings.getContainer();
        DBPDataSource dataSource = container == null ? null : container.getDataSource();
        if (container == null || dataSource == null || !mapping.hasNewTargetObject()) {
            return null;
        }
        DBCExecutionContext context = DBUtils.getDefaultContext(dataSource, true);
        if (context == null) {
            return null;
        }
        DBEPersistAction[] actions = DatabaseTransferUtils.generateTargetTableDDL(
            monitor, context, container, mapping, mapping.getChangedPropertiesMap(), settings);
        return actions.length == 0 ? null : SQLUtils.generateScript(dataSource, actions, false);
    }

    /**
     * Source columns that become new columns of an existing target table (ALTER TABLE … ADD).
     */
    @NotNull
    static List<DatabaseMappingAttribute> getAddedColumns(@NotNull DBRProgressMonitor monitor, @NotNull DatabaseMappingContainer mapping) {
        List<DatabaseMappingAttribute> added = new ArrayList<>();
        if (mapping.getMappingType() == DatabaseMappingType.existing) {
            for (DatabaseMappingAttribute attribute : mapping.getAttributeMappings(monitor)) {
                if (attribute.getMappingType() == DatabaseMappingType.create) {
                    added.add(attribute);
                }
            }
        }
        return added;
    }
}
