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

import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.registry.SettingsTransfer.Action;
import com.seizia.chromite.registry.SettingsTransfer.Change;
import com.seizia.chromite.registry.SettingsTransfer.Kind;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.SettingsTransferMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * File &gt; Import Settings: the preview of what changes, with the action of each item.
 *
 * 설계:
 * <ul>
 *     <li>파일과 이 컴퓨터가 다른 항목만 보인다(같은 것은 빼고). 여기에 없거나 기본값인 항목은 "가져오기", 여기와 다른 항목(충돌)은
 *     "건너뛰기"가 처음 값이다(지금 설정을 모르게 바꾸지 않기 위해). 충돌은 건너뛰기·바꾸기·둘 다 두기(파일처럼 두 개가 될 수 있는 항목만)
 *     중에서 고르고, "모든 충돌에"로 한 번에 정할 수 있다. 쿼리 창에 열린 스크립트, 읽기 전용 폴더의 쿼리는 바꾸기를 고를 수 없다.</li>
 *     <li>설정 값은 여기 값과 파일의 값을 보인다(비밀은 파일에 없다). 연결은 이 표에 넣지 않고, 파일에 있으면 "이어서 연결도 가져오기"를
 *     보인다: #108의 암호 묻기와 연결 미리 보기가 이어진다.</li>
 *     <li>가져오기는 지금 설정을 바꿀 수 있으므로 취소가 기본 단추이고 처음 포커스를 갖는다(docs/chromite/swing-frontend.md "실행 확인의 기본 단추").</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/settings-transfer.md)
 * - 2026-11-13 스크립트를 가져오지 않는다는 안내를 HTML 라벨 대신 경고 아이콘과 {@link WrappingNote}로: Swing 줄 바꿈이 한국어 낱말
 *   가운데서 끊을 수 있었다(앱 변경 #90, docs/chromite/swing-options.md "HTML 라벨 안내")
 * - 2026-11-13 아이콘과 안내를 {@link WrappingNote#withIcon}으로 세로 가운데에 맞춤: 한 줄 안내가 아이콘 가운데보다 위에 있었다(#90 검토)
 */
final class SettingsImportDialog extends JDialog {

    private static final int COLUMN_SECTION = 0;
    private static final int COLUMN_ITEM = 1;
    private static final int COLUMN_STATUS = 2;
    private static final int COLUMN_HERE = 3;
    private static final int COLUMN_FILE = 4;
    private static final int COLUMN_ACTION = 5;

    private static final Action[] CONFLICT_ACTIONS = {Action.SKIP, Action.REPLACE, Action.KEEP_BOTH};

    private final List<Change> changes;
    private final ChangesModel changesModel = new ChangesModel();
    @Nullable
    private final JCheckBox connectionsBox;
    private boolean accepted;

    private SettingsImportDialog(
        @Nullable Window owner,
        @NotNull String fileName,
        @NotNull String created,
        @NotNull List<Change> changes,
        boolean hasConnections,
        boolean scriptsSkipped
    ) {
        super(owner, SettingsTransferMessages.settings_import_title, ModalityType.APPLICATION_MODAL);
        this.changes = changes;

        JPanel header = new JPanel(new GridLayout(0, 1, 0, 2));
        header.add(new JLabel(NLS.bind(SettingsTransferMessages.settings_import_file, fileName, created)));
        header.add(new JLabel(SettingsTransferMessages.settings_import_hint));

        JTable table = new JTable(changesModel);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(Math.max(table.getRowHeight(), UIScale.scale(22)));
        table.getColumnModel().getColumn(COLUMN_SECTION).setPreferredWidth(UIScale.scale(100));
        table.getColumnModel().getColumn(COLUMN_ITEM).setPreferredWidth(UIScale.scale(260));
        table.getColumnModel().getColumn(COLUMN_STATUS).setPreferredWidth(UIScale.scale(240));
        table.getColumnModel().getColumn(COLUMN_HERE).setPreferredWidth(UIScale.scale(130));
        table.getColumnModel().getColumn(COLUMN_FILE).setPreferredWidth(UIScale.scale(130));
        table.getColumnModel().getColumn(COLUMN_ACTION).setPreferredWidth(UIScale.scale(110));
        table.getColumnModel().getColumn(COLUMN_ACTION).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                return super.getTableCellRendererComponent(table, getActionText((Action) value), isSelected, hasFocus, row, column);
            }
        });
        table.getColumnModel().getColumn(COLUMN_ACTION).setCellEditor(new ActionEditor());
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(UIScale.scale(990), UIScale.scale(340)));

        JPanel south = new JPanel(new BorderLayout(0, 6));
        JPanel options = new JPanel(new GridLayout(0, 1, 0, 6));
        if (changes.stream().anyMatch(change -> change.getKind() == Kind.CONFLICT)) {
            JComboBox<Action> allCombo = createActionCombo(CONFLICT_ACTIONS);
            JButton applyButton = new JButton(SettingsTransferMessages.settings_import_apply);
            applyButton.addActionListener(e -> {
                if (table.isEditing()) {
                    table.getCellEditor().stopCellEditing();
                }
                applyToConflicts((Action) allCombo.getSelectedItem());
            });
            JPanel allPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            allPanel.add(new JLabel(SettingsTransferMessages.settings_import_all_conflicts));
            allPanel.add(allCombo);
            allPanel.add(applyButton);
            options.add(allPanel);
        }
        if (hasConnections) {
            connectionsBox = new JCheckBox(SettingsTransferMessages.settings_import_connections, true);
            options.add(connectionsBox);
        } else {
            connectionsBox = null;
        }
        if (scriptsSkipped) {
            options.add(createNote(SettingsTransferMessages.settings_import_no_scripts_folder));
        }
        options.add(new JLabel(SettingsTransferMessages.settings_import_backup_note));
        south.add(options, BorderLayout.CENTER);

        JButton importButton = new JButton(SettingsTransferMessages.settings_import_button);
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        importButton.addActionListener(e -> {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
            accepted = true;
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(importButton);
        buttons.add(cancelButton);
        buttons.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        south.add(buttons, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        // Dialog insets of docs/chromite/design-spec.md "11. 대화상자" (10, 12)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(header, BorderLayout.NORTH);
        content.add(scrollPane, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        // Cancel is the default button: Import can replace settings (conflicts set to Replace)
        getRootPane().setDefaultButton(cancelButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(cancelButton::requestFocusInWindow);
    }

    /**
     * The choices of an accepted preview.
     *
     * @param importConnections the connections of the file are imported next
     */
    record Result(boolean importConnections) {
    }

    /**
     * Shows the preview; the actions are set on the changes. Returns null when canceled.
     */
    @Nullable
    static Result open(
        @NotNull Component parent,
        @NotNull String fileName,
        @NotNull String created,
        @NotNull List<Change> changes,
        boolean hasConnections,
        boolean scriptsSkipped
    ) {
        Window owner = parent instanceof Window window ? window : SwingUtilities.getWindowAncestor(parent);
        SettingsImportDialog dialog = new SettingsImportDialog(owner, fileName, created, changes, hasConnections, scriptsSkipped);
        dialog.setVisible(true);
        if (!dialog.accepted) {
            return null;
        }
        return new Result(dialog.connectionsBox != null && dialog.connectionsBox.isSelected());
    }

    /**
     * The warning icon beside a note that wraps at spaces only (an HTML label could break Korean words), at most as wide
     * as the HTML label was (760 CSS px, which Swing's HTML draws 1.3 times as wide).
     */
    @NotNull
    private static JComponent createNote(@NotNull String text) {
        return WrappingNote.withIcon(UIManager.getIcon("OptionPane.warningIcon"), text, UIScale.scale(988)); //$NON-NLS-1$
    }

    private void applyToConflicts(@Nullable Action action) {
        if (action == null) {
            return;
        }
        for (Change change : changes) {
            // An item that cannot take the action (Replace of an open script, Keep both of a setting) keeps its own
            if (change.getKind() == Kind.CONFLICT) {
                change.setAction(action);
            }
        }
        changesModel.fireTableDataChanged();
    }

    @NotNull
    private static JComboBox<Action> createActionCombo(@NotNull Action[] choices) {
        JComboBox<Action> combo = new JComboBox<>(choices);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, getActionText((Action) value), index, isSelected, cellHasFocus);
            }
        });
        return combo;
    }

    @NotNull
    static String getActionText(@Nullable Action action) {
        if (action == null) {
            return ""; //$NON-NLS-1$
        }
        return switch (action) {
            case IMPORT -> SettingsTransferMessages.settings_action_import;
            case SKIP -> SettingsTransferMessages.settings_action_skip;
            case REPLACE -> SettingsTransferMessages.settings_action_replace;
            case KEEP_BOTH -> SettingsTransferMessages.settings_action_keep_both;
        };
    }

    @NotNull
    static String getSectionText(@NotNull Change change) {
        return switch (change.getSection()) {
            case PREFERENCES -> SettingsTransferMessages.settings_short_preferences;
            case DRIVERS -> SettingsTransferMessages.settings_short_drivers;
            case QUERY_LIBRARY -> SettingsTransferMessages.settings_short_query_library;
            case SCRIPTS -> SettingsTransferMessages.settings_short_scripts;
            // Connections have their own preview (ConnectionImportDialog)
            case CONNECTIONS -> SettingsTransferMessages.settings_section_connections;
        };
    }

    @NotNull
    private static String getStatusText(@NotNull Change change) {
        return switch (change.getBlocker()) {
            case UNAVAILABLE -> SettingsTransferMessages.settings_status_unavailable;
            case OPEN -> SettingsTransferMessages.settings_status_open;
            case READ_ONLY -> SettingsTransferMessages.settings_status_read_only;
            case NONE -> change.getKind() == Kind.CONFLICT
                ? SettingsTransferMessages.settings_status_conflict
                : change.isExistsHere() ? SettingsTransferMessages.settings_status_default : SettingsTransferMessages.settings_status_new;
        };
    }

    private final class ChangesModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return changes.size();
        }

        @Override
        public int getColumnCount() {
            return 6;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case COLUMN_SECTION -> SettingsTransferMessages.settings_import_column_section;
                case COLUMN_ITEM -> SettingsTransferMessages.settings_import_column_item;
                case COLUMN_STATUS -> SettingsTransferMessages.settings_import_column_status;
                case COLUMN_HERE -> SettingsTransferMessages.settings_import_column_here;
                case COLUMN_FILE -> SettingsTransferMessages.settings_import_column_file;
                default -> SettingsTransferMessages.settings_import_column_action;
            };
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == COLUMN_ACTION && changes.get(row).getChoices().size() > 1;
        }

        @Override
        public Object getValueAt(int row, int column) {
            Change change = changes.get(row);
            return switch (column) {
                case COLUMN_SECTION -> getSectionText(change);
                case COLUMN_ITEM -> change.getName();
                case COLUMN_STATUS -> getStatusText(change);
                case COLUMN_HERE -> CommonUtils.notEmpty(change.getCurrent());
                case COLUMN_FILE -> CommonUtils.notEmpty(change.getImported());
                default -> change.getAction();
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == COLUMN_ACTION && value instanceof Action action) {
                changes.get(row).setAction(action);
                fireTableCellUpdated(row, column);
            }
        }
    }

    /**
     * Combo editor of the action column with the choices of the row.
     */
    private final class ActionEditor extends DefaultCellEditor {
        ActionEditor() {
            super(createActionCombo(CONFLICT_ACTIONS));
        }

        @Override
        @SuppressWarnings("unchecked")
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            JComboBox<Action> combo = (JComboBox<Action>) getComponent();
            Change change = changes.get(table.convertRowIndexToModel(row));
            combo.setModel(new DefaultComboBoxModel<>(change.getChoices().toArray(new Action[0])));
            combo.setSelectedItem(value);
            return combo;
        }
    }
}
