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
import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableWithReturn;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.properties.ObjectPropertyDescriptor;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 테이블 디자이너 문서 탭(SSMS의 테이블 "Design"): 열·키·외래 키·인덱스 표와 선택한 행의 속성 시트, 테이블 속성.
 *
 * 설계:
 * <ul>
 *     <li>모델 작업은 {@link TableDesignSession}(SWT {@code EntityEditor}와 같은 명령 체계)에 맡기고, 이 패널은 화면만 다룬다.
 *     바뀐 내용은 바로 DB에 적용하지 않는다. Save는 SSMS처럼 변경 스크립트를 먼저 보여 주고({@link ScriptPreviewDialog}),
 *     Execute를 고르면 저장한다. Generate Script는 스크립트만 보여 준다(쿼리 창으로 열기, 복사).</li>
 *     <li>탭 배치는 SSMS 디자이너를 따른다: 위쪽 표에서 행(열 하나)을 고르면 아래쪽 속성 시트(SSMS "Column Properties")가
 *     그 객체의 모든 속성을 보여 준다. 표의 열은 그 객체 종류의 표시 속성이다(SWT 객체 목록과 같음, {@link TableDesignSession#getGridProperties}).
 *     Columns 표 맨 앞의 PK 확인란은 SSMS "Set Primary Key"에 해당한다({@link TableDesignSession#setPrimaryKeyColumn}).</li>
 *     <li>읽기·편집·저장은 모두 진행 대화상자의 작업 스레드에서 한다({@link ProgressDialog}, 짧은 작업은 창이 뜨지 않음).
 *     편집 한 번마다 명령을 만든 뒤 모든 표의 값을 다시 읽는다: 한 편집이 다른 칸을 바꿀 수 있고(PK → NOT NULL),
 *     값은 모델 캐시에서 읽으므로 DB를 다시 읽지 않는다. 표의 선택은 객체로 기억해 다시 고른다.</li>
 *     <li>저장 뒤 세션이 탐색기 노드를 새로 읽고(모델 이벤트), Object Explorer가 그 이벤트로 트리를 갱신한다.</li>
 *     <li>탭을 닫을 때 저장하지 않은 변경이 있으면 Save / Don't Save / Cancel을 묻고, Don't Save면 명령을 되돌린다
 *     (저장하지 않은 새 테이블은 모델에서 사라진다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 테이블 디자이너: 최초 작성 (docs/chromite/swing-table-designer.md)
 * - 2026-11-06 탭 제목에 연결 이름(쿼리 창과 같은 "이름 - 연결" 형식): 여러 연결의 같은 이름 테이블이 구별되지 않던 문제
 *   (docs/chromite/swing-table-designer.md)
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 */
final class TableDesignerPanel extends JPanel {

    /**
     * The main window side.
     */
    interface Host {
        /**
         * Title or dirty state changed.
         */
        void designerChanged(@NotNull TableDesignerPanel designer);

        /**
         * Opens a script in a new query window without running it.
         */
        void openScript(@NotNull DBPDataSourceContainer dataSource, @NotNull String script, long maxRows, boolean execute);
    }

    private final TableDesignSession session;
    private final Host host;
    private final JTabbedPane sectionTabs = new JTabbedPane();
    private final Map<TableDesignSession.Section, SectionView> views = new EnumMap<>(TableDesignSession.Section.class);
    private final DesignerTable.SheetModel tableSheetModel;
    private final JTextField nameField = new JTextField(24);
    private final JLabel stateLabel = new JLabel(" ");
    private final Action saveAction;
    private final Action scriptAction;
    private final Action revertAction;
    @Nullable
    private Snapshot snapshot;
    private boolean disposed;

    /**
     * Everything the grids show, read in a background task.
     */
    private record Snapshot(
        @NotNull Map<TableDesignSession.Section, SectionData> sections,
        @NotNull SheetData tableSheet,
        @NotNull Map<DBSObject, SheetData> sheets,
        @NotNull Set<DBPObject> editedObjects,
        @Nullable ObjectPropertyDescriptor nameProperty,
        boolean dirty
    ) {
    }

    private record SectionData(@NotNull List<DBSObject> objects, @NotNull List<ObjectPropertyDescriptor> properties,
                               @NotNull Map<DBSObject, Object[]> values, @NotNull Set<DBSObject> keyObjects) {
    }

    private record SheetData(@NotNull List<ObjectPropertyDescriptor> properties, @NotNull Object[] values) {
    }

    /**
     * A model change done in the background task before the grids are read again.
     */
    private interface DesignTask {
        void run(@NotNull DBRProgressMonitor monitor) throws DBException;
    }

    private TableDesignerPanel(@NotNull TableDesignSession session, @NotNull Host host, @NotNull Snapshot snapshot) {
        super(new BorderLayout());
        this.session = session;
        this.host = host;
        DesignerTable.EditHandler editHandler = this::edit;
        tableSheetModel = new DesignerTable.SheetModel(session, editHandler);

        saveAction = createAction(ToolsMessages.designer_save, ToolsMessages.designer_save_tip, this::save);
        scriptAction = createAction(ToolsMessages.designer_generate_script, ToolsMessages.designer_generate_script_tip, this::showScript);
        revertAction = createAction(ToolsMessages.designer_revert, ToolsMessages.designer_revert_tip, this::revert);

        for (TableDesignSession.Section section : TableDesignSession.Section.values()) {
            SectionView view = new SectionView(section, editHandler);
            views.put(section, view);
            sectionTabs.addTab(section.title, UiIcons.get(getSectionIcon(section)), view);
        }
        DesignerTable tableSheet = createSheetTable(tableSheetModel);
        JScrollPane tableSheetScroll = new JScrollPane(tableSheet);
        tableSheetScroll.setBorder(BorderFactory.createEmptyBorder());
        sectionTabs.addTab(ToolsMessages.designer_section_table, UiIcons.get("table"), tableSheetScroll); //$NON-NLS-1$
        sectionTabs.putClientProperty(FlatClientProperties.STYLE, "tabHeight: 32; tabSelectionHeight: 3"); //$NON-NLS-1$

        nameField.addActionListener(e -> renameTable());
        nameField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                renameTable();
            }
        });

        add(createHeader(), BorderLayout.NORTH);
        add(sectionTabs, BorderLayout.CENTER);
        apply(snapshot);
    }

    /**
     * Opens the designer of an existing table, reading it in a progress dialog. Null if it failed (reported) or was canceled.
     */
    @Nullable
    static TableDesignerPanel open(@NotNull Host host, @NotNull DBSEntity table) {
        return create(host, NLS.bind(ToolsMessages.designer_progress_open, table.getName()), monitor -> TableDesignSession.open(table));
    }

    /**
     * Opens the designer of a new table in a "Tables" folder.
     */
    @Nullable
    static TableDesignerPanel createTable(@NotNull Host host, @NotNull DBNDatabaseFolder folder) {
        return create(host, ToolsMessages.designer_new_table, monitor -> TableDesignSession.createTable(monitor, folder));
    }

    @Nullable
    private static TableDesignerPanel create(@NotNull Host host, @NotNull String title,
                                             @NotNull DBRRunnableWithReturn<TableDesignSession> opener) {
        try {
            Object[] result = ProgressDialog.runTask(title, monitor -> {
                TableDesignSession session = opener.runTask(monitor);
                return new Object[]{session, readSnapshot(monitor, session)};
            });
            return new TableDesignerPanel((TableDesignSession) result[0], host, (Snapshot) result[1]);
        } catch (DBInterruptedException e) {
            return null;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, ToolsMessages.designer_error_open, e);
            return null;
        }
    }

    @NotNull
    private static String getSectionIcon(@NotNull TableDesignSession.Section section) {
        return switch (section) {
            case COLUMNS -> "column"; //$NON-NLS-1$
            case KEYS -> "primary-key"; //$NON-NLS-1$
            case FOREIGN_KEYS -> "foreign-key"; //$NON-NLS-1$
            case INDEXES -> "index"; //$NON-NLS-1$
        };
    }

    @NotNull
    private static Action createAction(@NotNull String name, @NotNull String description, @NotNull Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, description);
        return action;
    }

    /**
     * Tool bar (Save, Generate Script, Revert) and the table name.
     */
    @NotNull
    private JComponent createHeader() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.add(UiIcons.createToolButton(saveAction, "save")); //$NON-NLS-1$
        toolBar.add(UiIcons.createToolButton(scriptAction, "file-sql")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(revertAction, "rollback")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(new JLabel(ToolsMessages.designer_label_name));
        toolBar.add(Box.createHorizontalStrut(UIScale.scale(6)));
        nameField.setMaximumSize(new Dimension(UIScale.scale(260), nameField.getPreferredSize().height));
        toolBar.add(nameField);
        toolBar.add(Box.createHorizontalStrut(UIScale.scale(12)));
        stateLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        toolBar.add(stateLabel);
        toolBar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(8), UIScale.scale(4), UIScale.scale(8)));
        return toolBar;
    }

    @NotNull
    private static DesignerTable createSheetTable(@NotNull DesignerTable.SheetModel model) {
        DesignerTable table = new DesignerTable(model);
        table.getColumnModel().getColumn(0).setPreferredWidth(180);
        table.getColumnModel().getColumn(1).setPreferredWidth(320);
        return table;
    }

    //////////////////////////////////////////////////////////
    // State

    @NotNull
    TableDesignSession getSession() {
        return session;
    }

    boolean isDirty() {
        return snapshot != null && snapshot.dirty();
    }

    /**
     * Tab title: the table name, "[Design]" and the connection (as query windows name theirs: tables of the same name on
     * several connections are told apart), "*" while there are pending changes.
     */
    @NotNull
    String getTitle() {
        DBSEntity table = session.getTable();
        return NLS.bind(ToolsMessages.designer_tab_title, table.getName(), table.getDataSource().getContainer().getName())
            + (isDirty() ? "*" : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    String getToolTip() {
        DBSEntity table = session.getTable();
        String name = DBUtils.getObjectFullName(table, DBPEvaluationContext.UI);
        String connection = table.getDataSource().getContainer().getName();
        return session.isNewTable() ? NLS.bind(ToolsMessages.designer_tab_tooltip_new, name, connection) : name + " - " + connection; //$NON-NLS-1$
    }

    //////////////////////////////////////////////////////////
    // Reading

    @NotNull
    private static Snapshot readSnapshot(@NotNull DBRProgressMonitor monitor, @NotNull TableDesignSession session) throws DBException {
        Map<TableDesignSession.Section, SectionData> sections = new EnumMap<>(TableDesignSession.Section.class);
        Map<DBSObject, SheetData> sheets = new HashMap<>();
        Set<DBSObject> keyColumns = new HashSet<>();
        DBSEntityConstraint primaryKey = session.findPrimaryKey(monitor);
        if (primaryKey != null) {
            keyColumns.addAll(TableDesignSession.readKeyColumns(monitor, primaryKey));
        }
        for (TableDesignSession.Section section : TableDesignSession.Section.values()) {
            monitor.subTask(NLS.bind(ToolsMessages.designer_progress_read, section.title.toLowerCase()));
            List<DBSObject> objects = session.readObjects(monitor, section);
            List<ObjectPropertyDescriptor> properties = new ArrayList<>(session.getGridProperties(section, objects));
            Map<DBSObject, Object[]> values = new LinkedHashMap<>();
            for (DBSObject object : objects) {
                Object[] row = new Object[properties.size()];
                for (int i = 0; i < properties.size(); i++) {
                    row[i] = session.readValue(monitor, object, properties.get(i));
                }
                values.put(object, row);
                sheets.put(object, readSheet(monitor, session, object));
            }
            dropOwnerColumns(session.getTable(), properties, values);
            sections.put(section, new SectionData(objects, properties, values,
                section == TableDesignSession.Section.COLUMNS ? keyColumns : Set.of()));
        }
        SheetData tableSheet = readSheet(monitor, session, session.getTable());
        ObjectPropertyDescriptor nameProperty = tableSheet.properties().stream().filter(ObjectPropertyDescriptor::isNameProperty).findFirst().orElse(null);
        return new Snapshot(sections, tableSheet, sheets, session.getEditedObjects(), nameProperty, session.isDirty());
    }

    /**
     * Leaves out the grid columns whose value is the edited table in every row (the owner: "Table" of indexes and keys),
     * as they repeat the tab's table. The sheet below still shows them.
     */
    private static void dropOwnerColumns(@NotNull DBSEntity table, @NotNull List<ObjectPropertyDescriptor> properties,
                                         @NotNull Map<DBSObject, Object[]> values) {
        if (values.isEmpty()) {
            return;
        }
        for (int i = properties.size() - 1; i >= 0; i--) {
            int column = i;
            if (values.values().stream().allMatch(row -> row[column] == table)) {
                properties.remove(column);
                for (Map.Entry<DBSObject, Object[]> entry : values.entrySet()) {
                    Object[] row = entry.getValue();
                    Object[] shorter = new Object[row.length - 1];
                    System.arraycopy(row, 0, shorter, 0, column);
                    System.arraycopy(row, column + 1, shorter, column, row.length - column - 1);
                    entry.setValue(shorter);
                }
            }
        }
    }

    @NotNull
    private static SheetData readSheet(@NotNull DBRProgressMonitor monitor, @NotNull TableDesignSession session, @NotNull DBSObject object) {
        List<ObjectPropertyDescriptor> properties = session.getObjectProperties(object);
        Object[] values = new Object[properties.size()];
        for (int i = 0; i < properties.size(); i++) {
            values[i] = session.readValue(monitor, object, properties.get(i));
        }
        return new SheetData(properties, values);
    }

    private void apply(@NotNull Snapshot newSnapshot) {
        snapshot = newSnapshot;
        for (Map.Entry<TableDesignSession.Section, SectionView> entry : views.entrySet()) {
            SectionData data = newSnapshot.sections().get(entry.getKey());
            entry.getValue().setContent(data, newSnapshot.editedObjects());
        }
        tableSheetModel.setContent(session.getTable(), newSnapshot.tableSheet().properties(), newSnapshot.tableSheet().values());
        if (!nameField.isFocusOwner()) {
            nameField.setText(session.getTable().getName());
        }
        nameField.setEditable(newSnapshot.nameProperty() != null && session.isEditable(session.getTable(), newSnapshot.nameProperty()));
        updateState();
    }

    private void updateState() {
        boolean dirty = isDirty();
        saveAction.setEnabled(dirty && !session.isReadOnly());
        scriptAction.setEnabled(dirty);
        revertAction.setEnabled(dirty && !session.isNewTable());
        stateLabel.setText(session.isReadOnly() ? ToolsMessages.designer_state_read_only
            : session.isNewTable() ? ToolsMessages.designer_state_new
            : dirty ? ToolsMessages.designer_state_dirty : " ");
        host.designerChanged(this);
    }

    /**
     * Runs a model change in a background task, then reads the grids again (also after a failure: the model may have
     * changed partly).
     */
    private void run(@NotNull String title, @NotNull DesignTask task) {
        DBException error = null;
        try {
            Snapshot newSnapshot = ProgressDialog.runTask(title, monitor -> {
                try {
                    task.run(monitor);
                } catch (IllegalArgumentException e) {
                    // Property values are checked by the property writer (ObjectPropertyDescriptor.writeValue)
                    throw new DBException(CommonUtils.notEmpty(e.getMessage()), e);
                }
                return readSnapshot(monitor, session);
            });
            apply(newSnapshot);
            return;
        } catch (DBInterruptedException e) {
            // Canceled
        } catch (DBException e) {
            error = e;
        }
        if (error != null) {
            DBWorkbench.getPlatformUI().showError(title, null, error);
        }
        reload();
    }

    /**
     * Reads the grids again.
     */
    void reload() {
        try {
            apply(ProgressDialog.runTask(NLS.bind(ToolsMessages.designer_progress_read, session.getTable().getName()),
                monitor -> readSnapshot(monitor, session)));
        } catch (DBInterruptedException e) {
            // Canceled: keep what is shown
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.designer_title, NLS.bind(ToolsMessages.designer_error_read, session.getTable().getName()), e);
        }
    }

    //////////////////////////////////////////////////////////
    // Editing

    /**
     * A cell edit: a property change, or (no property) the primary key flag of a column. It runs after the table has
     * finished editing (the grids are rebuilt by the reload).
     */
    private void edit(@NotNull DBSObject object, @Nullable ObjectPropertyDescriptor property, @Nullable Object value) {
        SwingUtilities.invokeLater(() -> applyEdit(object, property, value));
    }

    private void applyEdit(@NotNull DBSObject object, @Nullable ObjectPropertyDescriptor property, @Nullable Object value) {
        if (property == null) {
            if (object instanceof DBSEntityAttribute column) {
                run(ToolsMessages.designer_progress_set_primary_key, monitor -> session.setPrimaryKeyColumn(monitor, column, Boolean.TRUE.equals(value)));
            }
            return;
        }
        run(NLS.bind(ToolsMessages.designer_progress_edit, property.getDisplayName()), monitor -> session.setValue(monitor, object, property, value));
    }

    private void renameTable() {
        Snapshot current = snapshot;
        if (current == null || current.nameProperty() == null || !nameField.isEditable()) {
            return;
        }
        String newName = nameField.getText().trim();
        if (newName.isEmpty() || newName.equals(session.getTable().getName())) {
            nameField.setText(session.getTable().getName());
            return;
        }
        ObjectPropertyDescriptor nameProperty = current.nameProperty();
        run(ToolsMessages.designer_progress_rename, monitor -> session.setValue(monitor, session.getTable(), nameProperty, newName));
    }

    private void addObject(@NotNull TableDesignSession.Section section) {
        switch (section) {
            case COLUMNS -> run(ToolsMessages.designer_new_column, monitor -> views.get(section).select(session.createColumn(monitor)));
            case KEYS -> addKey();
            case FOREIGN_KEYS -> addForeignKey();
            case INDEXES -> addIndex();
        }
    }

    @NotNull
    private List<DBSEntityAttribute> getColumns() {
        List<DBSEntityAttribute> columns = new ArrayList<>();
        if (snapshot != null) {
            for (DBSObject object : snapshot.sections().get(TableDesignSession.Section.COLUMNS).objects()) {
                if (object instanceof DBSEntityAttribute attribute) {
                    columns.add(attribute);
                }
            }
        }
        return columns;
    }

    private void addKey() {
        List<DBSEntityConstraintType> types = session.getKeyTypes();
        Map<DBSEntityConstraintType, String> names;
        try {
            names = ProgressDialog.runTask(ToolsMessages.designer_add_key, monitor -> {
                Map<DBSEntityConstraintType, String> result = new HashMap<>();
                for (DBSEntityConstraintType type : types) {
                    result.put(type, session.suggestKeyName(monitor, type));
                }
                return result;
            });
        } catch (DBException e) {
            if (!(e instanceof DBInterruptedException)) {
                DBWorkbench.getPlatformUI().showError(ToolsMessages.designer_add_key, null, e);
            }
            return;
        }
        TableDesignSession.KeySpec spec = DesignerDialogs.editKey(this, getColumns(), types, names);
        if (spec != null) {
            run(ToolsMessages.designer_add_key, monitor -> views.get(TableDesignSession.Section.KEYS).select(session.createKey(monitor, spec)));
        }
    }

    private void addIndex() {
        List<DBSEntityAttribute> columns = getColumns();
        Map<DBSEntityAttribute, String> names;
        try {
            names = ProgressDialog.runTask(ToolsMessages.designer_add_index, monitor -> {
                Map<DBSEntityAttribute, String> result = new HashMap<>();
                result.put(null, session.suggestIndexName(monitor, null));
                for (DBSEntityAttribute column : columns) {
                    result.put(column, session.suggestIndexName(monitor, column));
                }
                return result;
            });
        } catch (DBException e) {
            if (!(e instanceof DBInterruptedException)) {
                DBWorkbench.getPlatformUI().showError(ToolsMessages.designer_add_index, null, e);
            }
            return;
        }
        DesignerDialogs.IndexSpec spec = DesignerDialogs.editIndex(this, columns, names);
        if (spec != null) {
            run(ToolsMessages.designer_add_index, monitor -> views.get(TableDesignSession.Section.INDEXES)
                .select(session.createIndex(monitor, spec.name(), spec.unique(), spec.columns())));
        }
    }

    private void addForeignKey() {
        List<DBSEntity> referenceTables;
        try {
            referenceTables = ProgressDialog.runTask(ToolsMessages.designer_add_foreign_key, session::readReferenceTables);
        } catch (DBException e) {
            if (!(e instanceof DBInterruptedException)) {
                DBWorkbench.getPlatformUI().showError(ToolsMessages.designer_add_foreign_key, null, e);
            }
            return;
        }
        DesignerDialogs.ForeignKeySpec spec = DesignerDialogs.editForeignKey(this, session.getTable(), getColumns(), referenceTables);
        if (spec != null) {
            run(ToolsMessages.designer_add_foreign_key, monitor -> views.get(TableDesignSession.Section.FOREIGN_KEYS).select(session.createForeignKey(
                monitor, spec.name(), spec.referencedKey(), spec.deleteRule(), spec.updateRule(), spec.columns())));
        }
    }

    private void deleteObject(@NotNull TableDesignSession.Section section) {
        DBSObject object = views.get(section).getSelectedObject();
        if (object == null || !session.canDelete(object)) {
            return;
        }
        run(NLS.bind(ToolsMessages.designer_progress_delete, object.getName()), monitor -> session.delete(object));
    }

    //////////////////////////////////////////////////////////
    // Script, save, revert

    @Nullable
    private String generateScript(@NotNull String title) {
        try {
            return ProgressDialog.runTask(title, session::generateScript);
        } catch (DBInterruptedException e) {
            return null;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, ToolsMessages.designer_error_script, e);
            return null;
        }
    }

    private void showScript() {
        String script = generateScript(ToolsMessages.designer_generate_script);
        if (script == null) {
            return;
        }
        if (script.isBlank()) {
            JOptionPane.showMessageDialog(this, ToolsMessages.designer_script_none, ToolsMessages.designer_generate_script, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (ScriptPreviewDialog.show(this, NLS.bind(ToolsMessages.designer_change_script_title, session.getTable().getName()), script, false)
            == ScriptPreviewDialog.Result.OPEN_IN_EDITOR) {
            host.openScript(session.getTable().getDataSource().getContainer(), script, 0, false);
        }
    }

    /**
     * Shows the change script and applies it if the user executes it.
     *
     * @return true if there is nothing left to save
     */
    boolean save() {
        if (!isDirty()) {
            return true;
        }
        if (nameField.isFocusOwner()) {
            renameTable();
        }
        String script = generateScript(ToolsMessages.designer_save);
        if (script == null) {
            return false;
        }
        if (!script.isBlank()) {
            ScriptPreviewDialog.Result result = ScriptPreviewDialog.show(this,
                NLS.bind(ToolsMessages.designer_save_changes_title, session.getTable().getName()), script, true);
            if (result == ScriptPreviewDialog.Result.OPEN_IN_EDITOR) {
                host.openScript(session.getTable().getDataSource().getContainer(), script, 0, false);
                return false;
            }
            if (result != ScriptPreviewDialog.Result.EXECUTE) {
                return false;
            }
        }
        try {
            apply(ProgressDialog.runTask(NLS.bind(ToolsMessages.designer_progress_save, session.getTable().getName()), monitor -> {
                session.save(monitor);
                return readSnapshot(monitor, session);
            }));
            return true;
        } catch (DBInterruptedException e) {
            reload();
            return false;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.designer_save, NLS.bind(ToolsMessages.designer_error_save, session.getTable().getName()), e);
            reload();
            return false;
        }
    }

    private void revert() {
        if (!isDirty() || JOptionPane.showConfirmDialog(this, NLS.bind(ToolsMessages.designer_confirm_revert, session.getTable().getName()),
            ToolsMessages.designer_revert, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        run(ToolsMessages.designer_revert, monitor -> session.revert());
    }

    /**
     * Before the tab closes: asks to save pending changes (Save / Don't Save / Cancel).
     *
     * @return true if the tab may close
     */
    boolean confirmClose() {
        if (!isDirty()) {
            return true;
        }
        String[] options = {ToolsMessages.designer_save, ToolsMessages.designer_dont_save, ToolsMessages.button_cancel};
        int answer = JOptionPane.showOptionDialog(this, NLS.bind(ToolsMessages.designer_confirm_close, session.getTable().getName()),
            ToolsMessages.designer_title, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        return switch (answer) {
            case 0 -> save();
            case 1 -> true;
            default -> false;
        };
    }

    /**
     * The tab closed: pending changes are discarded so that the model does not keep them (a new table disappears).
     */
    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (session.isDirty()) {
            try {
                ProgressDialog.runTask(ToolsMessages.designer_progress_discard, monitor -> {
                    session.revert();
                    return null;
                });
            } catch (DBException e) {
                DBWorkbench.getPlatformUI().showError(ToolsMessages.designer_title, ToolsMessages.designer_error_discard, e);
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Section view

    /**
     * One tab: tool bar (add, delete), the grid, and the property sheet of the selected row.
     */
    private final class SectionView extends JPanel {
        private final TableDesignSession.Section section;
        private final DesignerTable.GridModel gridModel;
        private final DesignerTable grid;
        private final DesignerTable.SheetModel sheetModel;
        private final JLabel sheetTitle = new JLabel();
        private final Action addAction;
        private final Action deleteAction;
        @Nullable
        private DBSObject pendingSelection;

        SectionView(@NotNull TableDesignSession.Section section, @NotNull DesignerTable.EditHandler editHandler) {
            super(new BorderLayout());
            this.section = section;
            gridModel = new DesignerTable.GridModel(session, editHandler,
                section == TableDesignSession.Section.COLUMNS && session.canEditPrimaryKey());
            grid = new DesignerTable(gridModel);
            grid.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            sheetModel = new DesignerTable.SheetModel(session, editHandler);

            addAction = createAction(switch (section) {
                case COLUMNS -> ToolsMessages.designer_new_column;
                case KEYS -> ToolsMessages.designer_action_add_key;
                case FOREIGN_KEYS -> ToolsMessages.designer_action_add_foreign_key;
                case INDEXES -> ToolsMessages.designer_action_add_index;
            }, ToolsMessages.designer_action_add_tip, () -> addObject(section));
            deleteAction = createAction(ToolsMessages.designer_action_delete, ToolsMessages.designer_action_delete_tip,
                () -> deleteObject(section));
            if (!session.canCreate(section)) {
                addAction.setEnabled(false);
                addAction.putValue(Action.SHORT_DESCRIPTION, session.isReadOnly()
                    ? ToolsMessages.designer_action_read_only : ToolsMessages.designer_action_not_supported);
            }

            JToolBar toolBar = new JToolBar();
            toolBar.setFloatable(false);
            toolBar.add(UiIcons.createToolButton(addAction, getSectionIcon(section)));
            toolBar.add(UiIcons.createToolButton(deleteAction, "close")); //$NON-NLS-1$
            toolBar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(2), UIScale.scale(6), UIScale.scale(2), UIScale.scale(6)));

            grid.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    showSelected();
                }
            });
            grid.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "chromite.designer.delete"); //$NON-NLS-1$
            grid.getActionMap().put("chromite.designer.delete", deleteAction); //$NON-NLS-1$
            JScrollPane gridScroll = new JScrollPane(grid);
            gridScroll.setBorder(BorderFactory.createEmptyBorder());

            JPanel top = new JPanel(new BorderLayout());
            top.add(toolBar, BorderLayout.NORTH);
            top.add(gridScroll, BorderLayout.CENTER);

            DesignerTable sheet = createSheetTable(sheetModel);
            JScrollPane sheetScroll = new JScrollPane(sheet);
            sheetScroll.setBorder(BorderFactory.createEmptyBorder());
            sheetTitle.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
            sheetTitle.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(8), UIScale.scale(6), UIScale.scale(8)));
            JPanel bottom = new JPanel(new BorderLayout());
            bottom.add(sheetTitle, BorderLayout.NORTH);
            bottom.add(sheetScroll, BorderLayout.CENTER);

            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
            split.setResizeWeight(0.6);
            split.setBorder(BorderFactory.createEmptyBorder());
            split.setContinuousLayout(true);
            add(split, BorderLayout.CENTER);
        }

        void setContent(@NotNull SectionData data, @NotNull Set<DBPObject> editedObjects) {
            DBSObject selected = pendingSelection != null ? pendingSelection : getSelectedObject();
            pendingSelection = null;
            int columnsBefore = gridModel.getColumnCount();
            gridModel.setContent(data.objects(), data.properties(), data.values(), data.keyObjects(), editedObjects);
            if (columnsBefore != gridModel.getColumnCount() || columnsBefore == 0) {
                sizeColumns();
            }
            int row = selected == null ? -1 : data.objects().indexOf(selected);
            if (row >= 0) {
                grid.setRowSelectionInterval(row, row);
                grid.scrollRectToVisible(grid.getCellRect(row, 0, true));
            } else if (!data.objects().isEmpty() && grid.getSelectedRow() < 0) {
                grid.setRowSelectionInterval(0, 0);
            }
            showSelected();
        }

        /**
         * Initial widths: the key flag narrow, the first property (the name) wide, the others by their header.
         */
        private void sizeColumns() {
            for (int i = 0; i < grid.getColumnCount(); i++) {
                ObjectPropertyDescriptor property = gridModel.getProperty(0, i);
                int width = property == null ? 40
                    : property.isNameProperty() ? 180 : Math.max(90, gridModel.getColumnName(i).length() * 8 + 30);
                grid.getColumnModel().getColumn(i).setPreferredWidth(UIScale.scale(width));
            }
        }

        /**
         * Selects an object after the next reload (a new row).
         */
        void select(@NotNull DBSObject object) {
            pendingSelection = object;
        }

        @Nullable
        DBSObject getSelectedObject() {
            int row = grid.getSelectedRow();
            return row < 0 ? null : gridModel.getObjects().get(grid.convertRowIndexToModel(row));
        }

        private void showSelected() {
            DBSObject object = getSelectedObject();
            SheetData data = object == null || snapshot == null ? null : snapshot.sheets().get(object);
            if (data == null) {
                sheetModel.setContent(null, List.of(), new Object[0]);
                sheetTitle.setText(ToolsMessages.designer_sheet_properties);
            } else {
                sheetModel.setContent(object, data.properties(), data.values());
                sheetTitle.setText(getSheetTitle() + " - " + object.getName());
            }
            deleteAction.setEnabled(object != null && session.canDelete(object));
        }

        @NotNull
        private String getSheetTitle() {
            return switch (section) {
                case COLUMNS -> ToolsMessages.designer_sheet_column;
                case KEYS -> ToolsMessages.designer_sheet_key;
                case FOREIGN_KEYS -> ToolsMessages.designer_sheet_foreign_key;
                case INDEXES -> ToolsMessages.designer_sheet_index;
            };
        }
    }
}
