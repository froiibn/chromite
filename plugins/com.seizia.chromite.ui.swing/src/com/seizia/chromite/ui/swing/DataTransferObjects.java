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

import com.formdev.flatlaf.FlatClientProperties;
import com.seizia.chromite.ui.swing.internal.DbCopyMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPSystemObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.DBValueFormatting;
import com.seizia.chromite.model.impl.PropertyDescriptor;
import com.seizia.chromite.model.navigator.DBNDataSource;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.LocalCacheProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSFolder;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.tools.transfer.registry.DataTransferProcessorDescriptor;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 데이터 내보내기·가져오기 마법사가 함께 쓰는 것: 원본 객체 찾기, 양식 줄, 안내 문구, 객체 칸 렌더러.
 *
 * 설계: 원본 목록은 SWT {@code DataExportHandler}·{@code DataImportHandler}처럼 모델 객체의 형식으로 판단한다
 * (테이블·뷰 = {@link DBSDataContainer}이면서 {@link DBSEntity}, 그 부모 = 주 자식 형식이 {@link DBSDataContainer}인
 * {@link DBSObjectContainer}, 탐색기 폴더 = 자식 형식이 그런 {@link DBSFolder}). 양식 줄 모양은 {@link OptionsDialog}와 같다
 * (이름표에 쌍점 없음, 간격 6, 보조 설명은 text.secondary).
 * 데이터베이스 노드: SQL Server·PostgreSQL 데이터베이스는 테이블이 아니라 스키마를 담으므로({@code getPrimaryChildType}이 {@link DBSSchema})
 * 위 "테이블의 부모" 판단에 걸리지 않는다. 그래서 스키마를 담는 컨테이너({@link #isSchemaContainer})를 따로 알아보고, 원본은 스키마마다 읽는다
 * ({@link #readSchemaTables}). 연결 노드는 이미 읽은 탐색기 노드에서 데이터베이스가 하나뿐일 때만 그 데이터베이스로 본다
 * ({@link #findConnectionDatabase}). 메뉴를 켤지는 EDT에서 정하므로 메타데이터를 읽지 않기 위해서다.
 *
 * 변경 이력:
 * - 2026-11-06 데이터 전송: 최초 작성 (docs/chromite/swing-data-transfer.md)
 * - 2026-11-06 데이터베이스 노드에서 가져오기·내보내기·테이블 복사: 스키마를 담는 컨테이너, 연결의 유일한 데이터베이스, 스키마별 원본 읽기
 *   (docs/chromite/swing-data-transfer.md "데이터베이스 노드")
 * - 2026-11-08 저장된 작업의 형식 속성 바꾸기({@link #convertProcessorProperties}) (docs/chromite/swing-database-tasks.md "데이터 전송 작업")
 * - 2026-11-13 안내 문구({@link #createNote})를 HTML 라벨 대신 {@link WrappingNote}로: Swing 줄 바꿈이 한국어 낱말 가운데서 끊었다
 *   (앱 변경 #90, docs/chromite/swing-options.md "HTML 라벨 안내")
 */
final class DataTransferObjects {

    private static final Log log = Log.getLog(DataTransferObjects.class);

    private DataTransferObjects() {
    }

    /**
     * A schema, catalog or Object Explorer folder whose children are tables or views. Called on the EDT: it only
     * looks at types (as the SWT {@code DataImportHandler.isObjectContainerSupportsImport}).
     */
    static boolean isDataContainerParent(@Nullable DBSObject object) {
        try {
            if (object instanceof DBNDatabaseFolder folder) {
                Class<? extends DBSObject> childrenClass = folder.getChildrenClass();
                return childrenClass != null && DBSDataContainer.class.isAssignableFrom(childrenClass);
            }
            if (object instanceof DBSObjectContainer container) {
                Class<? extends DBSObject> childType = container.getPrimaryChildType(null);
                return childType != null && DBSDataContainer.class.isAssignableFrom(childType);
            }
        } catch (DBException e) {
            log.debug("Can't get the child type of " + object.getName(), e);
        }
        return false;
    }

    /**
     * A database whose children are schemas, not tables (SQL Server and PostgreSQL databases, the connection of a driver
     * without databases such as Oracle): its primary child type is a {@link DBSSchema}. Called on the EDT: types only.
     */
    static boolean isSchemaContainer(@Nullable DBSObject object) {
        if (object instanceof DBSObjectContainer container && !(object instanceof DBSEntity)) {
            try {
                Class<? extends DBSObject> childType = container.getPrimaryChildType(null);
                return childType != null && DBSSchema.class.isAssignableFrom(childType);
            } catch (DBException e) {
                log.debug("Can't get the child type of " + object.getName(), e);
            }
        }
        return false;
    }

    /**
     * A database node: a catalog that holds tables (MySQL, MariaDB) or a container of schemas ({@link #isSchemaContainer}).
     * A schema is not a database, so a schema node keeps its own behaviour. Called on the EDT: types only.
     */
    static boolean isDatabase(@Nullable DBSObject object) {
        return isSchemaContainer(object) || object instanceof DBSCatalog && isDataContainerParent(object);
    }

    /**
     * The database a connection node stands for: the connection itself when it holds schemas (no database level), or its
     * only database among the nodes Object Explorer has already read (connection, "Databases" folder, database). Null when
     * it is not connected, has several databases, or they are not read yet: metadata is never read here (called on the EDT).
     */
    @Nullable
    static DBSObject findConnectionDatabase(@NotNull DBNDataSource node) {
        DBPDataSourceContainer container = node.getDataSourceContainer();
        if (!container.isConnected()) {
            return null;
        }
        DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, container.getDataSource());
        if (isSchemaContainer(root)) {
            return root;
        }
        List<DBSObject> databases = new ArrayList<>();
        collectLoadedDatabases(node, 2, databases);
        return databases.size() == 1 ? databases.getFirst() : null;
    }

    private static void collectLoadedDatabases(@NotNull DBNDatabaseNode node, int depth, @NotNull List<DBSObject> result) {
        DBNDatabaseNode[] children;
        try {
            // The cache-only monitor returns the children already read and never loads them
            children = node.needsInitialization() ? null : node.getChildren(new LocalCacheProgressMonitor(new VoidProgressMonitor()));
        } catch (DBException e) {
            return;
        }
        if (children == null) {
            return;
        }
        for (DBNDatabaseNode child : children) {
            if (child instanceof DBNDatabaseFolder folder) {
                if (depth > 1) {
                    collectLoadedDatabases(folder, depth - 1, result);
                }
                continue;
            }
            DBSObject object = child.getObject();
            if (object instanceof DBSCatalog && isDatabase(object) && !DBUtils.isHiddenObject(object)
                && !(object instanceof DBPSystemObject system && system.isSystem())) {
                result.add(object);
            }
        }
    }

    /**
     * Tool tips of Tasks ▸ Import Data…, Export Data… and Copy Tables… for the object they act on: what they do with a
     * database or a table, or which nodes they work on when they are off.
     */
    static void updateTaskTooltips(@Nullable DBSObject object, @NotNull Action importAction, @NotNull Action exportAction, @NotNull Action copyAction) {
        boolean database = isDatabase(object);
        importAction.putValue(Action.SHORT_DESCRIPTION, !importAction.isEnabled() ? DbCopyMessages.dbnode_tip_import_disabled
            : database ? DbCopyMessages.dbnode_tip_import_database : DbCopyMessages.dbnode_tip_import_table);
        exportAction.putValue(Action.SHORT_DESCRIPTION, !exportAction.isEnabled() ? DbCopyMessages.dbnode_tip_transfer_disabled
            : database ? DbCopyMessages.dbnode_tip_export_database : DbCopyMessages.dbnode_tip_export);
        copyAction.putValue(Action.SHORT_DESCRIPTION, !copyAction.isEnabled() ? DbCopyMessages.dbnode_tip_transfer_disabled
            : database ? DbCopyMessages.dbnode_tip_copy_database : DbCopyMessages.dbnode_tip_copy);
    }

    /**
     * A schema of a database with its tables and views; {@code tables} is null until they are read (system schemas are
     * read only when they are shown).
     */
    record SchemaTables(@NotNull DBSObjectContainer schema, boolean system, @Nullable List<DBSDataContainer> tables) {
    }

    /**
     * The schemas of a database by name, without hidden ones and those the connection's navigator filters leave out
     * (the model's schema list applies them), with the tables and views of the schemas that are not system schemas.
     */
    @NotNull
    static List<SchemaTables> readSchemaTables(@NotNull DBRProgressMonitor monitor, @NotNull DBSObjectContainer database) throws DBException {
        List<DBSObjectContainer> schemas = new ArrayList<>();
        for (DBSObject child : CommonUtils.safeCollection(database.getChildren(monitor))) {
            if (child instanceof DBSObjectContainer schema && !(child instanceof DBSEntity) && !DBUtils.isHiddenObject(child)) {
                schemas.add(schema);
            }
        }
        schemas.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        List<SchemaTables> result = new ArrayList<>();
        for (DBSObjectContainer schema : schemas) {
            boolean system = schema instanceof DBPSystemObject systemObject && systemObject.isSystem();
            result.add(new SchemaTables(schema, system, system ? null : readSchema(monitor, schema)));
        }
        return result;
    }

    /**
     * The tables and views of one schema, with the schema name as the progress text; a cancel stops the reading.
     */
    @NotNull
    static List<DBSDataContainer> readSchema(@NotNull DBRProgressMonitor monitor, @NotNull DBSObjectContainer schema) throws DBException {
        if (monitor.isCanceled()) {
            throw new DBInterruptedException("Canceled"); //$NON-NLS-1$
        }
        monitor.subTask(schema.getName());
        return readDataContainers(monitor, schema);
    }

    /**
     * Tables and views of a parent (a container's children or a folder's), in its order (reads metadata).
     *
     * @param parent a {@link DBSObjectContainer} or a {@link DBSFolder}
     */
    @NotNull
    static List<DBSDataContainer> readDataContainers(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject parent) throws DBException {
        Collection<? extends DBSObject> children = null;
        if (parent instanceof DBSFolder folder) {
            children = folder.getChildrenObjects(monitor);
        } else if (parent instanceof DBSObjectContainer container) {
            children = container.getChildren(monitor);
        }
        List<DBSDataContainer> result = new ArrayList<>();
        if (children != null) {
            for (DBSObject child : children) {
                if (child instanceof DBSDataContainer dataContainer && child instanceof DBSEntity && !DBUtils.isHiddenObject(child)) {
                    result.add(dataContainer);
                }
            }
        }
        return result;
    }

    /**
     * Label, control and optional hint in one form row (as {@link OptionsDialog}).
     */
    static int addRow(@NotNull JPanel form, int row, @Nullable String label, @NotNull JComponent control, @Nullable String hint) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 6, 6);
        if (label != null) {
            c.gridx = 0;
            form.add(new JLabel(label), c);
        }
        c.gridx = 1;
        // Fields and combos take the width; check boxes and spinners keep their size, and their hint takes the rest
        boolean fixedSize = control instanceof JSpinner || control instanceof AbstractButton;
        if (control instanceof JSpinner spinner) {
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setColumns(8);
        }
        c.fill = fixedSize ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
        c.weightx = fixedSize ? 0 : 1;
        if (hint == null) {
            c.gridwidth = 2;
        }
        form.add(control, c);
        if (hint != null) {
            JLabel hintLabel = new JLabel(hint);
            hintLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            c.gridx = 2;
            c.fill = GridBagConstraints.NONE;
            c.weightx = fixedSize ? 1 : 0;
            form.add(hintLabel, c);
        }
        return row + 1;
    }

    /**
     * The format properties of a saved task (saved as text), converted to the property types as the wizards edit them;
     * properties the format does not have are left out.
     */
    @NotNull
    static Map<String, Object> convertProcessorProperties(
        @NotNull DataTransferProcessorDescriptor processor,
        @Nullable Map<String, Object> saved
    ) {
        Map<String, Object> properties = new HashMap<>();
        if (saved != null) {
            for (Map.Entry<String, Object> entry : saved.entrySet()) {
                DBPPropertyDescriptor descriptor = processor.getProperty(entry.getKey());
                if (descriptor != null && entry.getValue() != null) {
                    properties.put(entry.getKey(), PropertyDescriptor.convertString(CommonUtils.toString(entry.getValue()), descriptor.getDataType()));
                }
            }
        }
        return properties;
    }

    /**
     * Explanatory text in the secondary color, wrapped.
     */
    @NotNull
    static JTextArea createNote(@NotNull String text) {
        // Wraps at spaces only (an HTML label broke Korean words); like the label, it asks for its one-line width
        JTextArea note = new WrappingNote(text);
        note.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        return note;
    }

    /**
     * Database objects with their icon and full name.
     */
    static final class ObjectRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value instanceof DBSObject object) {
                setText(DBUtils.getObjectFullName(object, DBPEvaluationContext.UI));
                setIcon(NodeIcons.getIcon(DBValueFormatting.getObjectImage(object)));
            } else {
                setIcon(null);
            }
            return this;
        }
    }
}
