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
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.DBValueFormatting;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSDataManipulator;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityType;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Import Data 마법사의 첫 단계(데이터베이스 노드에서 열었을 때): 행을 받을 테이블 고르기. 스키마와 기존 테이블, 또는 새 테이블 이름.
 *
 * 설계: 테이블 노드에서 연 가져오기는 대상이 정해져 있지만, 데이터베이스 노드(SSMS 데이터베이스 ▸ Tasks ▸ Import Data)에서는 대상을 골라야 한다.
 * 스키마 목록과 테이블 찾기는 테이블 복사 대상 단계와 같은 {@link DatabaseCopyMappings} 호출(숨김·시스템 스키마 제외, 주 자식 형식으로
 * 스키마 단계가 있는지 판단)을 쓴다. 새 테이블은 여기서 이름만 정하고, 실제 매핑(create)은 파일의 열을 읽은 뒤 마법사가
 * {@link DatabaseCopyMappings#setTableTarget}으로 만든다. 그 이름의 테이블이 이미 있으면 그 테이블에 넣는 매핑(existing)이 된다.
 * 메타데이터는 EDT에서 읽지 않는다: 스키마 목록과 처음 고른 스키마의 테이블은 마법사를 열기 전에({@link #readTargets}), 다른 스키마의 테이블은
 * 그 스키마를 고를 때 진행 대화상자에서 읽는다. 목록에는 테이블만 둔다(뷰에 넣기는 테이블 노드에서처럼 그 뷰를 직접 고를 때만).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 데이터베이스 노드에서 Import Data… (docs/chromite/swing-data-transfer.md "데이터베이스 노드")
 */
final class DataImportTargetPage implements DataTransferWizard.Page {

    /**
     * Where the chosen rows go: a container, and an existing table of it or the name of a new table (empty: the file name).
     */
    interface TargetListener {
        void setTarget(@NotNull DBSObjectContainer container, @Nullable DBSDataManipulator table, @NotNull String newTableName);
    }

    /**
     * The database, its schemas (or the database itself when it holds the tables), the preselected one and the tables
     * read so far.
     */
    record Targets(
        @NotNull DBSObjectContainer database,
        boolean schemaLevel,
        @NotNull List<DBSObjectContainer> containers,
        @Nullable DBSObjectContainer preferred,
        @NotNull Map<DBSObjectContainer, List<DBSDataManipulator>> tables
    ) {
    }

    private final DataTransferWizard wizard;
    private final Targets targets;
    private final TargetListener listener;

    private final JPanel panel = new JPanel(new BorderLayout());
    private final JComboBox<DBSObjectContainer> schemaCombo = new JComboBox<>();
    private final JRadioButton existingRadio = new JRadioButton(DbCopyMessages.dbnode_import_target_existing);
    private final JRadioButton newRadio = new JRadioButton(DbCopyMessages.dbnode_import_target_new);
    private final JComboBox<DBSDataManipulator> tableCombo = new JComboBox<>();
    private final JTextField nameField = new JTextField(30);
    private final JLabel note = DataTransferObjects.createNote(" ");
    private boolean updating;

    DataImportTargetPage(@NotNull DataTransferWizard wizard, @NotNull Targets targets, @NotNull TargetListener listener) {
        this.wizard = wizard;
        this.targets = targets;
        this.listener = listener;

        DefaultListCellRenderer objectRenderer = new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof DBSObject object) {
                    setText(object.getName());
                    setIcon(NodeIcons.getIcon(DBValueFormatting.getObjectImage(object)));
                } else {
                    setText(" ");
                    setIcon(null);
                }
                return this;
            }
        };
        schemaCombo.setRenderer(objectRenderer);
        tableCombo.setRenderer(objectRenderer);
        for (DBSObjectContainer container : targets.containers()) {
            schemaCombo.addItem(container);
        }
        ButtonGroup group = new ButtonGroup();
        group.add(existingRadio);
        group.add(newRadio);
        nameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, DbCopyMessages.dbnode_import_target_new_placeholder);

        JLabel databaseLabel = new JLabel(DBUtils.getObjectFullName(targets.database(), DBPEvaluationContext.UI));
        databaseLabel.setIcon(NodeIcons.getIcon(DBValueFormatting.getObjectImage(targets.database())));
        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        row = DataTransferObjects.addRow(form, row, DbCopyMessages.dbnode_import_target_database, databaseLabel, null);
        if (targets.schemaLevel()) {
            row = DataTransferObjects.addRow(form, row, DbCopyMessages.dbnode_import_target_schema, schemaCombo, null);
        }
        row = addChoiceRow(form, row, existingRadio, tableCombo);
        addChoiceRow(form, row, newRadio, nameField);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.add(form, BorderLayout.NORTH);
        content.add(note, BorderLayout.CENTER);
        panel.add(content, BorderLayout.NORTH);

        schemaCombo.addActionListener(e -> {
            if (!updating) {
                showTables();
            }
        });
        existingRadio.addActionListener(e -> updateState());
        newRadio.addActionListener(e -> updateState());
        tableCombo.addActionListener(e -> updateState());
        updating = true;
        try {
            schemaCombo.setSelectedItem(targets.preferred());
        } finally {
            updating = false;
        }
        fillTables(targets.tables().getOrDefault(targets.preferred(), List.of()));
    }

    /**
     * A choice (radio button) in the label column with its control, like {@link DataTransferObjects#addRow}.
     */
    private static int addChoiceRow(@NotNull JPanel form, int row, @NotNull JRadioButton radio, @NotNull JComponent control) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 6, 6);
        c.gridx = 0;
        form.add(radio, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.gridwidth = 2;
        form.add(control, c);
        return row + 1;
    }

    /**
     * Reads the schemas of a database (or takes the database when it holds the tables), preselects the default schema
     * and reads its tables. Runs in a job before the wizard opens.
     */
    @NotNull
    static Targets readTargets(@NotNull DBRProgressMonitor monitor, @NotNull DBSObjectContainer database) throws DBException {
        boolean tables = DatabaseCopyMappings.isTableContainer(monitor, database);
        List<DBSObjectContainer> containers = tables ? List.of(database) : DatabaseCopyMappings.readChildContainers(monitor, database);
        DBSObjectContainer preferred = tables ? database : null;
        if (!tables) {
            // The connection's default schema, or a schema of that name in another database (dbo, public), else the first one
            DBCExecutionContext context = DBUtils.getDefaultContext(database, true);
            DBCExecutionContextDefaults<?, ?> defaults = context == null ? null : context.getContextDefaults();
            DBSObject defaultSchema = defaults == null ? null : defaults.getDefaultSchema();
            for (DBSObjectContainer container : containers) {
                if (container == defaultSchema || preferred == null && defaultSchema != null && container.getName().equals(defaultSchema.getName())) {
                    preferred = container;
                }
            }
            if (preferred == null && !containers.isEmpty()) {
                preferred = containers.getFirst();
            }
        }
        Map<DBSObjectContainer, List<DBSDataManipulator>> read = new HashMap<>();
        if (preferred != null) {
            read.put(preferred, readTables(monitor, preferred));
        }
        return new Targets(database, !tables, containers, preferred, read);
    }

    /**
     * Tables of a container that can receive rows, by name.
     */
    @NotNull
    private static List<DBSDataManipulator> readTables(@NotNull DBRProgressMonitor monitor, @NotNull DBSObjectContainer container) throws DBException {
        List<DBSDataManipulator> result = new ArrayList<>();
        for (DBSObject child : CommonUtils.safeCollection(container.getChildren(monitor))) {
            if (child instanceof DBSDataManipulator table && child instanceof DBSEntity entity && entity.getEntityType() == DBSEntityType.TABLE
                && !DBUtils.isHiddenObject(child)) {
                result.add(table);
            }
        }
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    /**
     * Lists the tables of the chosen schema, reading them in a job the first time.
     */
    private void showTables() {
        DBSObjectContainer container = (DBSObjectContainer) schemaCombo.getSelectedItem();
        List<DBSDataManipulator> tables = container == null ? List.of() : targets.tables().get(container);
        if (container != null && tables == null) {
            try {
                tables = ProgressDialog.runTask(NLS.bind(DbCopyMessages.progress_read, container.getName()),
                    monitor -> readTables(monitor, container));
                targets.tables().put(container, tables);
            } catch (DBInterruptedException e) {
                tables = List.of();
            } catch (DBException e) {
                DBWorkbench.getPlatformUI().showError(wizard.getTitle(), NLS.bind(DbCopyMessages.error_read, container.getName()), e);
                tables = List.of();
            }
        }
        fillTables(tables);
    }

    private void fillTables(@NotNull List<DBSDataManipulator> tables) {
        updating = true;
        try {
            tableCombo.removeAllItems();
            for (DBSDataManipulator table : tables) {
                tableCombo.addItem(table);
            }
            tableCombo.setSelectedItem(tables.isEmpty() ? null : tables.getFirst());
        } finally {
            updating = false;
        }
        // A schema without tables can only receive a new table
        existingRadio.setEnabled(!tables.isEmpty());
        if (tables.isEmpty()) {
            newRadio.setSelected(true);
        } else if (!newRadio.isSelected()) {
            existingRadio.setSelected(true);
        }
        updateState();
    }

    private void updateState() {
        tableCombo.setEnabled(existingRadio.isSelected() && existingRadio.isEnabled());
        nameField.setEnabled(newRadio.isSelected());
        DBSObjectContainer container = (DBSObjectContainer) schemaCombo.getSelectedItem();
        String text;
        if (container == null) {
            text = DbCopyMessages.dbnode_import_target_error_schema;
        } else if (newRadio.isSelected()) {
            text = NLS.bind(DbCopyMessages.dbnode_import_target_note_new, DBUtils.getObjectFullName(container, DBPEvaluationContext.UI));
        } else if (tableCombo.getSelectedItem() instanceof DBSDataManipulator table) {
            text = NLS.bind(DbCopyMessages.dbnode_import_target_note_existing, DBUtils.getObjectFullName(table, DBPEvaluationContext.UI));
        } else {
            text = DbCopyMessages.dbnode_import_target_error_table;
        }
        note.setText("<html>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</html>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    @NotNull
    @Override
    public String getTitle() {
        return DbCopyMessages.dbnode_import_target_title;
    }

    @NotNull
    @Override
    public String getDescription() {
        return NLS.bind(DbCopyMessages.dbnode_import_target_description, targets.database().getName());
    }

    @NotNull
    @Override
    public JComponent getComponent() {
        return panel;
    }

    @Nullable
    @Override
    public String leave() {
        if (!(schemaCombo.getSelectedItem() instanceof DBSObjectContainer container)) {
            return DbCopyMessages.dbnode_import_target_error_schema;
        }
        if (newRadio.isSelected()) {
            listener.setTarget(container, null, nameField.getText().trim());
            return null;
        }
        if (!(tableCombo.getSelectedItem() instanceof DBSDataManipulator table)) {
            return DbCopyMessages.dbnode_import_target_error_table;
        }
        listener.setTarget(container, table, ""); //$NON-NLS-1$
        return null;
    }
}
