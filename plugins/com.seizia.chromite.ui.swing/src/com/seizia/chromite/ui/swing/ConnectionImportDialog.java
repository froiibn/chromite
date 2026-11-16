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
import com.seizia.chromite.registry.ConnectionTransfer;
import com.seizia.chromite.registry.ConnectionTransfer.ConflictAction;
import com.seizia.chromite.registry.ConnectionTransfer.ImportEntry;
import com.seizia.chromite.ui.swing.internal.ConnectionTransferMessages;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 연결 가져오기 미리 보기: 파일의 연결(이름, 호스트, 드라이버, 그룹, 비밀번호 유무)과 기존 연결과의 충돌, 충돌마다 할 일을 고른다.
 *
 * 설계:
 * <ul>
 *     <li>충돌(같은 ID, 또는 같은 그룹의 같은 이름)마다 건너뛰기·바꾸기·사본으로 가져오기를 고른다. 기본은 건너뛰기다(기존 연결을
 *     모르게 바꾸지 않기 위해). "모든 충돌에" 한 번에 정할 수도 있다. 충돌이 없는 연결은 그대로 가져온다.</li>
 *     <li>기존 연결이 연결 중이면 "바꾸기"를 고를 수 없다(세션과 트랜잭션을 건드리지 않음). 모델도 가져오는 순간 다시 확인한다.</li>
 *     <li>이 PC에 없는 드라이버는 드라이버 열에 "설치 안 됨"으로 표시하고 표 아래에 알린다.</li>
 *     <li>다른 도구에서 가져온 연결 중 DB 종류를 알아보지 못한 것은 드라이버 열에 원본 종류와 "Generic JDBC"를 보이고 표 아래에
 *     드라이버 관리자에서 설정해야 한다고 알린다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/connection-transfer.md)
 * - 2026-11-08 취소를 기본 단추로 하고 처음 포커스를 둠: Enter로 기존 연결이 바뀌지 않게 함 (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 * - 2026-11-08 다른 도구에서 가져올 때 알아보지 못한 DB 종류 표시 (docs/chromite/connection-transfer.md "다른 도구에서 가져오기")
 * - 2026-11-13 표 아래 안내를 HTML 라벨 대신 경고 아이콘과 {@link WrappingNote}로: Swing 줄 바꿈이 한국어 낱말 가운데서 끊었다
 *   (앱 변경 #90, docs/chromite/swing-options.md "HTML 라벨 안내")
 * - 2026-11-13 아이콘과 안내를 {@link WrappingNote#withIcon}으로 세로 가운데에 맞춤: 한 줄 안내가 아이콘 가운데보다 위에 있었다(#90 검토)
 */
final class ConnectionImportDialog extends JDialog {

    private static final int COLUMN_NAME = 0;
    private static final int COLUMN_HOST = 1;
    private static final int COLUMN_DRIVER = 2;
    private static final int COLUMN_GROUP = 3;
    private static final int COLUMN_PASSWORD = 4;
    private static final int COLUMN_CONFLICT = 5;
    private static final int COLUMN_ACTION = 6;

    private static final ConflictAction[] ALL_ACTIONS = {ConflictAction.SKIP, ConflictAction.REPLACE, ConflictAction.COPY};
    private static final ConflictAction[] CONNECTED_ACTIONS = {ConflictAction.SKIP, ConflictAction.COPY};

    private final List<ImportEntry> entries;
    private final Map<String, ConflictAction> actions = new HashMap<>();
    private final EntriesModel entriesModel = new EntriesModel();
    private boolean accepted;

    private ConnectionImportDialog(@Nullable Window owner, @NotNull String fileName, @NotNull ConnectionTransfer.ImportFile importFile) {
        super(owner, ConnectionTransferMessages.transfer_import_title, ModalityType.APPLICATION_MODAL);
        this.entries = importFile.getEntries();
        boolean anyConflict = false;
        boolean anyMissingDriver = false;
        boolean anyUnrecognizedType = false;
        for (ImportEntry entry : entries) {
            if (entry.getConflict() != ConnectionTransfer.ConflictKind.NONE) {
                actions.put(entry.getId(), ConflictAction.SKIP);
                anyConflict = true;
            }
            anyMissingDriver |= entry.isDriverMissing();
            anyUnrecognizedType |= entry.getUnrecognizedType() != null;
        }

        JPanel header = new JPanel(new GridLayout(0, 1, 0, 2));
        header.add(new JLabel(NLS.bind(ConnectionTransferMessages.transfer_import_file, fileName)));
        header.add(new JLabel(importFile.hasSecrets()
            ? ConnectionTransferMessages.transfer_import_with_passwords
            : ConnectionTransferMessages.transfer_import_without_passwords));

        JTable table = new JTable(entriesModel);
        table.setFillsViewportHeight(true);
        table.setRowHeight(Math.max(table.getRowHeight(), UIScale.scale(22)));
        table.getColumnModel().getColumn(COLUMN_NAME).setPreferredWidth(UIScale.scale(180));
        table.getColumnModel().getColumn(COLUMN_HOST).setPreferredWidth(UIScale.scale(160));
        table.getColumnModel().getColumn(COLUMN_DRIVER).setPreferredWidth(UIScale.scale(120));
        table.getColumnModel().getColumn(COLUMN_GROUP).setPreferredWidth(UIScale.scale(110));
        table.getColumnModel().getColumn(COLUMN_PASSWORD).setPreferredWidth(UIScale.scale(70));
        table.getColumnModel().getColumn(COLUMN_CONFLICT).setPreferredWidth(UIScale.scale(170));
        table.getColumnModel().getColumn(COLUMN_ACTION).setPreferredWidth(UIScale.scale(130));
        table.getColumnModel().getColumn(COLUMN_ACTION).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                return super.getTableCellRendererComponent(table, getActionText((ConflictAction) value), isSelected, hasFocus, row, column);
            }
        });
        table.getColumnModel().getColumn(COLUMN_ACTION).setCellEditor(new ActionEditor());
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(UIScale.scale(900), UIScale.scale(300)));

        JPanel south = new JPanel(new BorderLayout(0, 6));
        JPanel notes = new JPanel(new GridLayout(0, 1, 0, 6));
        if (anyMissingDriver) {
            notes.add(createNote(ConnectionTransferMessages.transfer_import_drivers_missing_note));
        }
        if (anyUnrecognizedType) {
            notes.add(createNote(ConnectionTransferMessages.transfer_import_drivers_unrecognized_note));
        }
        if (notes.getComponentCount() > 0) {
            south.add(notes, BorderLayout.NORTH);
        }
        if (anyConflict) {
            JComboBox<ConflictAction> allCombo = createActionCombo(ALL_ACTIONS);
            JButton applyButton = new JButton(ConnectionTransferMessages.transfer_import_apply);
            applyButton.addActionListener(e -> {
                if (table.isEditing()) {
                    table.getCellEditor().stopCellEditing();
                }
                applyToAll((ConflictAction) allCombo.getSelectedItem());
            });
            JPanel allPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            allPanel.add(new JLabel(ConnectionTransferMessages.transfer_import_all_conflicts));
            allPanel.add(allCombo);
            allPanel.add(applyButton);
            south.add(allPanel, BorderLayout.CENTER);
        }

        JButton importButton = new JButton(ConnectionTransferMessages.transfer_import_button);
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
        // Cancel is the default button: Import can replace existing connections (conflicts set to Replace)
        getRootPane().setDefaultButton(cancelButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(cancelButton::requestFocusInWindow);
    }

    /**
     * Shows the preview; returns the action of each conflict by entry ID, or null when canceled.
     */
    @Nullable
    static Map<String, ConflictAction> open(@NotNull Component parent, @NotNull String fileName, @NotNull ConnectionTransfer.ImportFile importFile) {
        Window owner = parent instanceof Window window ? window : SwingUtilities.getWindowAncestor(parent);
        ConnectionImportDialog dialog = new ConnectionImportDialog(owner, fileName, importFile);
        dialog.setVisible(true);
        return dialog.accepted ? dialog.actions : null;
    }

    /**
     * The warning icon beside a note that wraps at spaces only (an HTML label broke Korean words), at most as wide as the
     * HTML label was (760 CSS px, which Swing's HTML draws 1.3 times as wide).
     */
    @NotNull
    private static JComponent createNote(@NotNull String text) {
        return WrappingNote.withIcon(UIManager.getIcon("OptionPane.warningIcon"), text, UIScale.scale(988)); //$NON-NLS-1$
    }

    private void applyToAll(@Nullable ConflictAction action) {
        if (action == null) {
            return;
        }
        for (ImportEntry entry : entries) {
            if (actions.containsKey(entry.getId())) {
                // Connected connections cannot be replaced: they stay skipped
                actions.put(entry.getId(), action == ConflictAction.REPLACE && entry.isExistingConnected() ? ConflictAction.SKIP : action);
            }
        }
        entriesModel.fireTableDataChanged();
    }

    @NotNull
    private static JComboBox<ConflictAction> createActionCombo(@NotNull ConflictAction[] choices) {
        JComboBox<ConflictAction> combo = new JComboBox<>(choices);
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, getActionText((ConflictAction) value), index, isSelected, cellHasFocus);
            }
        });
        return combo;
    }

    @NotNull
    private static String getActionText(@Nullable ConflictAction action) {
        if (action == null) {
            return ConnectionTransferMessages.transfer_import_action_new;
        }
        return switch (action) {
            case SKIP -> ConnectionTransferMessages.transfer_import_action_skip;
            case REPLACE -> ConnectionTransferMessages.transfer_import_action_replace;
            case COPY -> ConnectionTransferMessages.transfer_import_action_copy;
        };
    }

    @NotNull
    private static String getConflictText(@NotNull ImportEntry entry) {
        return switch (entry.getConflict()) {
            case NONE -> ConnectionTransferMessages.transfer_import_conflict_none;
            case SAME_ID -> entry.isExistingConnected()
                ? ConnectionTransferMessages.transfer_import_conflict_connected
                : ConnectionTransferMessages.transfer_import_conflict_same_id;
            case SAME_NAME -> entry.isExistingConnected()
                ? ConnectionTransferMessages.transfer_import_conflict_connected
                : ConnectionTransferMessages.transfer_import_conflict_same_name;
        };
    }

    private final class EntriesModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return 7;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case COLUMN_NAME -> ConnectionTransferMessages.transfer_import_column_name;
                case COLUMN_HOST -> ConnectionTransferMessages.transfer_import_column_host;
                case COLUMN_DRIVER -> ConnectionTransferMessages.transfer_import_column_driver;
                case COLUMN_GROUP -> ConnectionTransferMessages.transfer_import_column_group;
                case COLUMN_PASSWORD -> ConnectionTransferMessages.transfer_import_column_passwords;
                case COLUMN_CONFLICT -> ConnectionTransferMessages.transfer_import_column_conflict;
                default -> ConnectionTransferMessages.transfer_import_column_action;
            };
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == COLUMN_ACTION && actions.containsKey(entries.get(row).getId());
        }

        @Override
        public Object getValueAt(int row, int column) {
            ImportEntry entry = entries.get(row);
            return switch (column) {
                case COLUMN_NAME -> entry.getName();
                case COLUMN_HOST -> entry.getAddress();
                case COLUMN_DRIVER -> entry.getUnrecognizedType() != null
                    ? NLS.bind(ConnectionTransferMessages.transfer_import_driver_unrecognized, entry.getUnrecognizedType())
                    : entry.isDriverMissing()
                    ? NLS.bind(ConnectionTransferMessages.transfer_import_driver_missing, entry.getDriverName())
                    : entry.getDriverName();
                case COLUMN_GROUP -> entry.getFolderPath() == null ? "" : entry.getFolderPath().replace("/", " / "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                case COLUMN_PASSWORD -> entry.hasPassword() ? ConnectionTransferMessages.transfer_import_yes : ConnectionTransferMessages.transfer_import_no;
                case COLUMN_CONFLICT -> getConflictText(entry);
                default -> actions.get(entry.getId());
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == COLUMN_ACTION && value instanceof ConflictAction action) {
                actions.put(entries.get(row).getId(), action);
                fireTableCellUpdated(row, column);
            }
        }
    }

    /**
     * Combo editor of the action column; a connected existing connection offers no "Replace".
     */
    private final class ActionEditor extends DefaultCellEditor {
        ActionEditor() {
            super(createActionCombo(ALL_ACTIONS));
        }

        @Override
        @SuppressWarnings("unchecked")
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            JComboBox<ConflictAction> combo = (JComboBox<ConflictAction>) getComponent();
            ImportEntry entry = entries.get(row);
            combo.setModel(new DefaultComboBoxModel<>(entry.isExistingConnected() ? CONNECTED_ACTIONS : ALL_ACTIONS));
            combo.setSelectedItem(CommonUtils.notNull((ConflictAction) value, ConflictAction.SKIP));
            return combo;
        }
    }
}
