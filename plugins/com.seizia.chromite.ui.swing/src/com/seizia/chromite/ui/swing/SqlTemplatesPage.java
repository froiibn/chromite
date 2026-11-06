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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Tools &gt; Options &gt; Templates: the SQL templates ({@link SqlTemplates}) to view, change, add and remove.
 *
 * 설계: SWT 설정 페이지({@code PrefPageSQLTemplates})처럼 목록과 선택한 템플릿의 이름·설명·내용 편집이다. 고친 내용은 이 페이지 안의 복사본에만 두고
 * 대화상자의 OK에서 {@link #apply()}가 한 번에 저장한다. 기본 템플릿은 지우면 숨겨지고(저장 파일의 {@code deleted}), Restore Defaults가 되살린다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 */
final class SqlTemplatesPage extends JPanel {

    private final List<SqlTemplates.Template> templates = new ArrayList<>(SqlTemplates.getTemplates());
    private final List<SqlTemplates.Template> original = List.copyOf(templates);
    private final TemplatesModel model = new TemplatesModel();
    private final JTable table = new JTable(model);
    private final JTextField nameField = new JTextField(16);
    private final JTextField descriptionField = new JTextField(30);
    private final JCheckBox enabledBox = new JCheckBox(EditorMessages.editor_templates_enabled);
    private final SqlEditorPanel.SqlTextArea patternArea = new SqlEditorPanel.SqlTextArea();
    private final JButton removeButton = new JButton(EditorMessages.editor_templates_remove);
    private boolean updating;

    SqlTemplatesPage() {
        super(new BorderLayout(12, 0));
        setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(260, 240));

        JButton addButton = new JButton(EditorMessages.editor_templates_new);
        addButton.addActionListener(e -> addTemplate());
        removeButton.addActionListener(e -> removeTemplate());
        JButton restoreButton = new JButton(EditorMessages.editor_templates_restore);
        restoreButton.setToolTipText(EditorMessages.editor_templates_restore_tip);
        restoreButton.addActionListener(e -> restoreDefaults());
        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        listButtons.add(addButton);
        listButtons.add(removeButton);
        listButtons.add(restoreButton);
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.add(tableScroll, BorderLayout.CENTER);
        left.add(listButtons, BorderLayout.SOUTH);

        DocumentListener editListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                storeSelected();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                storeSelected();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        };
        nameField.getDocument().addDocumentListener(editListener);
        descriptionField.getDocument().addDocumentListener(editListener);
        patternArea.getDocument().addDocumentListener(editListener);
        enabledBox.addActionListener(e -> storeSelected());

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 6, 6);
        c.gridy = 0;
        fields.add(new JLabel(EditorMessages.editor_common_name), c);
        c.gridx = 1;
        fields.add(nameField, c);
        c.gridx = 2;
        fields.add(enabledBox, c);
        c.gridy = 1;
        c.gridx = 0;
        fields.add(new JLabel(EditorMessages.editor_common_description), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        fields.add(descriptionField, c);
        JLabel hint = new JLabel(EditorMessages.editor_templates_hint);
        hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JScrollPane patternScroll = new JScrollPane(patternArea);
        patternScroll.setPreferredSize(new Dimension(380, 180));
        patternArea.applyTheme();
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.add(fields, BorderLayout.NORTH);
        right.add(patternScroll, BorderLayout.CENTER);
        right.add(hint, BorderLayout.SOUTH);

        add(left, BorderLayout.WEST);
        add(right, BorderLayout.CENTER);
        if (!templates.isEmpty()) {
            table.getSelectionModel().setSelectionInterval(0, 0);
        }
        showSelected();
    }

    /**
     * Saves the templates if they changed (Options dialog OK).
     */
    void apply() {
        if (templates.equals(original)) {
            return;
        }
        try {
            SqlTemplates.save(templates);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(EditorMessages.editor_templates_title, EditorMessages.editor_templates_cant_save, e);
        }
    }

    @Nullable
    private SqlTemplates.Template getSelected() {
        int row = table.getSelectedRow();
        return row >= 0 && row < templates.size() ? templates.get(row) : null;
    }

    private void showSelected() {
        SqlTemplates.Template template = getSelected();
        updating = true;
        try {
            nameField.setText(template == null ? "" : template.name());
            descriptionField.setText(template == null ? "" : template.description());
            enabledBox.setSelected(template != null && template.enabled());
            patternArea.setText(template == null ? "" : template.pattern());
            patternArea.setCaretPosition(0);
            boolean editable = template != null;
            nameField.setEnabled(editable);
            descriptionField.setEnabled(editable);
            enabledBox.setEnabled(editable);
            patternArea.setEditable(editable);
            removeButton.setEnabled(editable);
        } finally {
            updating = false;
        }
    }

    private void storeSelected() {
        int row = table.getSelectedRow();
        if (updating || row < 0 || row >= templates.size()) {
            return;
        }
        SqlTemplates.Template old = templates.get(row);
        templates.set(row, new SqlTemplates.Template(old.id(), nameField.getText().trim(), descriptionField.getText(),
            patternArea.getText(), enabledBox.isSelected()));
        model.fireTableRowsUpdated(row, row);
    }

    private void addTemplate() {
        templates.add(new SqlTemplates.Template(null, "new", "", "select * from ${table};", true)); //$NON-NLS-1$ //$NON-NLS-2$
        model.fireTableDataChanged();
        int row = templates.size() - 1;
        table.getSelectionModel().setSelectionInterval(row, row);
        table.scrollRectToVisible(table.getCellRect(row, 0, true));
        nameField.requestFocusInWindow();
        nameField.selectAll();
    }

    private void removeTemplate() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        templates.remove(row);
        model.fireTableDataChanged();
        if (!templates.isEmpty()) {
            int next = Math.min(row, templates.size() - 1);
            table.getSelectionModel().setSelectionInterval(next, next);
        }
        showSelected();
    }

    private void restoreDefaults() {
        List<SqlTemplates.Template> custom = templates.stream().filter(t -> t.id() == null).toList();
        templates.clear();
        templates.addAll(SqlTemplates.DEFAULTS);
        templates.addAll(custom);
        model.fireTableDataChanged();
        table.getSelectionModel().setSelectionInterval(0, 0);
        showSelected();
    }

    private final class TemplatesModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return templates.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? EditorMessages.editor_common_name : EditorMessages.editor_common_description;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SqlTemplates.Template template = templates.get(rowIndex);
            return columnIndex == 0 ? template.name() : template.description();
        }
    }
}
