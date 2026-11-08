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
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.EventObject;
import java.util.Locale;

/**
 * 레코드 보기: 결과의 한 행을 "열 이름 | 자료형 | 값" 줄로 세워 보여 준다(SWT 결과 뷰어의 레코드 모드, {@code ResultSetViewer.toggleMode}).
 *
 * 설계: 행은 결과 표의 선택 행이다. 이전·다음 단추(Alt+←/→)는 표의 선택을 옮기므로 표로 돌아가도 같은 행에 있다.
 * 값은 표가 가진 표시 문자열이고, 편집할 수 있는 결과에서는 값 칸을 두 번 누르거나 F2로 고친다. 고친 글자는 표의 셀 편집기와 같이
 * 열의 값 처리기가 값으로 만든다({@link ResultSetGrid#setCellText}). 틀린 값이면 빨간 테두리로 편집을 계속한다. Ctrl+S는 표의 변경을 저장한다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-result-viewer.md "레코드 보기")
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 그리드처럼 고정폭 글꼴 대신 UI 글꼴 (docs/chromite/design-spec.md "적용 현황 — 그리드 글꼴 Pretendard")
 * - 2026-11-09 단축키를 플랫폼 명령 키로({@link UiKeys}: macOS Command, 앱 변경 #24) (docs/chromite/shortcuts.md)
 */
final class ResultSetRecordView extends JPanel {

    /**
     * The viewer side: the grid's rows and selection.
     */
    interface Source {
        int getRowCount();

        int getCurrentRow();

        void setCurrentRow(int row);

        @NotNull
        String[] getColumnNames();

        @NotNull
        DBDAttributeBinding[] getBindings();

        @Nullable
        Object getDisplayValue(int row, int column);

        boolean isEditable(int row, int column);

        @NotNull
        String getEditText(int row, int column);

        /**
         * @return null if set, otherwise why not
         */
        @Nullable
        String setText(int row, int column, @NotNull String text);

        boolean isChanged(int row, int column);

        void save();

        void selectionChanged();
    }

    private static final int COLUMN_NAME = 0;
    private static final int COLUMN_TYPE = 1;
    private static final int COLUMN_VALUE = 2;

    private final Source source;
    private final RecordModel model = new RecordModel();
    private final JTable table;
    private final JLabel positionLabel = new JLabel();
    private final JLabel messageLabel = new JLabel();
    private final Action previousAction;
    private final Action nextAction;
    private int row = -1;

    ResultSetRecordView(@NotNull Source source) {
        super(new BorderLayout());
        this.source = source;
        table = new JTable(model);
        table.putClientProperty("JTable.autoStartsEdit", Boolean.FALSE); //$NON-NLS-1$
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowVerticalLines(true);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new RecordRenderer());
        table.setDefaultEditor(Object.class, new RecordEditor());
        table.getColumnModel().getColumn(COLUMN_NAME).setPreferredWidth(180);
        table.getColumnModel().getColumn(COLUMN_TYPE).setPreferredWidth(110);
        table.getColumnModel().getColumn(COLUMN_VALUE).setPreferredWidth(480);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                source.selectionChanged();
            }
        });

        previousAction = ResultSetViewerPanel.createAction(EditorMessages.editor_record_previous,
            KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK), () -> move(-1));
        nextAction = ResultSetViewerPanel.createAction(EditorMessages.editor_record_next,
            KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK), () -> move(1));
        previousAction.putValue(Action.SHORT_DESCRIPTION, EditorMessages.editor_record_previous_tip);
        nextAction.putValue(Action.SHORT_DESCRIPTION, EditorMessages.editor_record_next_tip);
        Action saveAction = ResultSetViewerPanel.createAction(EditorMessages.editor_common_save, UiKeys.command(KeyEvent.VK_S), source::save);
        for (Action action : new Action[]{previousAction, nextAction, saveAction}) {
            KeyStroke key = (KeyStroke) action.getValue(Action.ACCELERATOR_KEY);
            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, action.getValue(Action.NAME));
            getActionMap().put(action.getValue(Action.NAME), action);
        }

        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        navigation.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        navigation.add(createNavButton(previousAction, "◀")); //$NON-NLS-1$
        navigation.add(positionLabel);
        navigation.add(createNavButton(nextAction, "▶")); //$NON-NLS-1$
        messageLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.error"); //$NON-NLS-1$
        navigation.add(messageLabel);

        JScrollPane scrollPane = new JScrollPane(table);
        add(navigation, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    @NotNull
    private static JButton createNavButton(@NotNull Action action, @NotNull String text) {
        JButton button = new JButton(action);
        button.setText(text);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setFocusable(false);
        return button;
    }

    /**
     * Shows the grid's current row again (selection, data or columns changed).
     */
    void refresh() {
        int current = source.getCurrentRow();
        int count = source.getRowCount();
        if (current < 0 && count > 0) {
            current = 0;
        }
        boolean columnsChanged = model.getRowCount() != source.getColumnNames().length;
        if (current != row || columnsChanged || !table.isEditing()) {
            int selected = table.getSelectedRow();
            if (table.isEditing() && current != row) {
                table.getCellEditor().cancelCellEditing();
            }
            row = current;
            model.fireTableDataChanged();
            if (selected >= 0 && selected < model.getRowCount()) {
                table.setRowSelectionInterval(selected, selected);
            }
        }
        positionLabel.setText(count == 0 ? EditorMessages.editor_record_no_rows : NLS.bind(EditorMessages.editor_record_position, row + 1, count));
        previousAction.setEnabled(row > 0);
        nextAction.setEnabled(row >= 0 && row < count - 1);
    }

    /**
     * @param line the line to select (a grid column), or -1 to keep the selection
     */
    void focusTable(int line) {
        table.requestFocusInWindow();
        if (line >= 0 && line < model.getRowCount()) {
            table.setRowSelectionInterval(line, line);
        } else if (table.getSelectedRow() < 0 && model.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    /**
     * The grid column of the selected record line, or -1.
     */
    int getSelectedColumn() {
        return isShowing() ? table.getSelectedRow() : -1;
    }

    private void move(int delta) {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) {
            return;
        }
        int target = row + delta;
        if (target >= 0 && target < source.getRowCount()) {
            source.setCurrentRow(target);
            refresh();
        }
    }

    private final class RecordModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return row < 0 ? 0 : source.getColumnNames().length;
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case COLUMN_NAME -> EditorMessages.editor_common_column;
                case COLUMN_TYPE -> EditorMessages.editor_record_type;
                default -> EditorMessages.editor_common_value;
            };
        }

        @Override
        public Object getValueAt(int line, int column) {
            DBDAttributeBinding binding = source.getBindings()[line];
            return switch (column) {
                case COLUMN_NAME -> source.getColumnNames()[line];
                case COLUMN_TYPE -> CommonUtils.notEmpty(binding.getTypeName()).toLowerCase(Locale.ROOT);
                default -> source.getDisplayValue(row, line);
            };
        }

        @Override
        public boolean isCellEditable(int line, int column) {
            return column == COLUMN_VALUE && row >= 0 && source.isEditable(row, line);
        }

        @Override
        public void setValueAt(Object value, int line, int column) {
            // Set by the editor through the source
        }
    }

    private final class RecordRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable recordTable, Object value, boolean isSelected, boolean hasFocus, int line, int column) {
            boolean isNull = value == ResultSetGrid.NULL_VALUE;
            String text = value == null ? "" : value.toString().replace('\r', ' ').replace('\n', ' ');
            super.getTableCellRendererComponent(recordTable, text, isSelected, hasFocus, line, column);
            setHorizontalAlignment(column == COLUMN_VALUE && source.getBindings()[line].getDataKind() == DBPDataKind.NUMERIC
                ? SwingConstants.RIGHT : SwingConstants.LEFT);
            if (!isSelected) {
                boolean secondary = isNull || column == COLUMN_TYPE;
                setForeground(secondary ? UIManager.getColor("Chromite.secondaryForeground") : recordTable.getForeground()); //$NON-NLS-1$
                Color changed = column == COLUMN_VALUE && row >= 0 && source.isChanged(row, line)
                    ? UIManager.getColor("Chromite.Table.changedBackground") : null; //$NON-NLS-1$
                setBackground(changed != null ? changed : recordTable.getBackground());
            }
            return this;
        }
    }

    /**
     * Text editor of a value line (as the grid's cell editor: the value handler makes the value).
     */
    private final class RecordEditor extends AbstractCellEditor implements TableCellEditor {
        private final JTextField field = new JTextField();
        private final Border normalBorder;
        private String initialText = "";
        private int line;

        RecordEditor() {
            normalBorder = field.getBorder();
            field.addActionListener(e -> stopCellEditing());
        }

        @Override
        public boolean isCellEditable(EventObject event) {
            return !(event instanceof MouseEvent mouseEvent) || mouseEvent.getClickCount() >= 2;
        }

        @Override
        public Component getTableCellEditorComponent(JTable recordTable, Object value, boolean isSelected, int editLine, int column) {
            line = editLine;
            initialText = source.getEditText(row, line);
            field.setText(initialText);
            field.setBorder(normalBorder);
            field.setFont(recordTable.getFont());
            messageLabel.setText("");
            SwingUtilities.invokeLater(field::selectAll);
            return field;
        }

        @Override
        public Object getCellEditorValue() {
            return null;
        }

        @Override
        public boolean stopCellEditing() {
            String text = field.getText();
            if (!text.equals(initialText)) {
                String error = source.setText(row, line, text);
                if (error != null) {
                    Color errorColor = UIManager.getColor("Component.error.focusedBorderColor"); //$NON-NLS-1$
                    field.setBorder(BorderFactory.createLineBorder(errorColor != null ? errorColor : Color.RED, 2));
                    messageLabel.setText(error);
                    UIManager.getLookAndFeel().provideErrorFeedback(field);
                    return false;
                }
            }
            messageLabel.setText("");
            return super.stopCellEditing();
        }
    }
}
