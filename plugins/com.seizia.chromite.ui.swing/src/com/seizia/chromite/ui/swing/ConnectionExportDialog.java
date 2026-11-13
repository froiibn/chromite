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
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceFolder;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.registry.ConnectionTransferFile;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 연결 내보내기 대화상자: 내보낼 그룹·서버를 고르고, 비밀번호를 넣을지와 파일 암호를 정한다.
 *
 * 설계:
 * <ul>
 *     <li>목록은 Registered Servers와 같은 순서(그룹 다음 서버, 이름의 자연 정렬, {@link RegisteredServerCommands})로 그룹과 서버를
 *     들여 써서 보인다. 그룹을 체크하면 그 아래 모든 그룹과 서버가 함께 체크된다. 체크한 그룹은 비어 있어도 파일에 남는다.</li>
 *     <li>"비밀번호 포함"은 기본으로 켠다(새 PC로 옮기는 것이 이 기능의 목적). 켜면 암호를 두 번 넣어야 하고
 *     {@link ConnectionTransferFile#MIN_PASSPHRASE_LENGTH}자 이상이어야 한다. 끄면 암호 칸을 끄고, 파일이 암호화되지 않는다는 것을 알린다.</li>
 *     <li>암호는 {@link JPasswordField#getPassword()}의 {@code char[]}로만 다루고, 쓰고 나면 지운다(문자열로 만들지 않는다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/connection-transfer.md)
 * - 2026-11-13 암호 안내를 HTML 라벨 대신 {@link WrappingNote}로: Swing 줄 바꿈이 한국어 낱말 가운데서 끊었다(앱 변경 #90,
 *   docs/chromite/swing-options.md "HTML 라벨 안내")
 */
final class ConnectionExportDialog extends JDialog {

    private static final int COLUMN_CHECK = 0;
    private static final int COLUMN_NAME = 1;
    private static final int COLUMN_HOST = 2;

    /**
     * A group or server row of the list.
     */
    private static final class Row {
        @NotNull
        final Object item;
        final int depth;
        boolean checked;

        Row(@NotNull Object item, int depth) {
            this.item = item;
            this.depth = depth;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final RowsModel rowsModel = new RowsModel();
    private final JCheckBox includePasswordsCheck = new JCheckBox(ConnectionTransferMessages.transfer_export_include_passwords, true);
    private final JPasswordField passphraseField = new JPasswordField(24);
    private final JPasswordField confirmField = new JPasswordField(24);
    private final JLabel passphraseLabel = new JLabel(ConnectionTransferMessages.transfer_export_passphrase);
    private final JLabel confirmLabel = new JLabel(ConnectionTransferMessages.transfer_export_passphrase_confirm);
    // Wraps at spaces only (an HTML label broke Korean words), at most as wide as the HTML label was (420 CSS px, which
    // Swing's HTML draws 1.3 times as wide)
    private final JTextArea hintNote = new WrappingNote("", UIScale.scale(546)); //$NON-NLS-1$
    private boolean accepted;

    private ConnectionExportDialog(@Nullable Window owner, @NotNull DBPDataSourceRegistry registry, @Nullable Object preselect) {
        super(owner, ConnectionTransferMessages.transfer_export_title, ModalityType.APPLICATION_MODAL);
        addRows(registry, null, 0);
        preselect(preselect);

        JTable table = new JTable(rowsModel);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setRowHeight(Math.max(table.getRowHeight(), UIScale.scale(22)));
        table.getColumnModel().getColumn(COLUMN_CHECK).setMaxWidth(UIScale.scale(32));
        table.getColumnModel().getColumn(COLUMN_NAME).setPreferredWidth(UIScale.scale(300));
        table.getColumnModel().getColumn(COLUMN_HOST).setPreferredWidth(UIScale.scale(200));
        table.getColumnModel().getColumn(COLUMN_NAME).setCellRenderer(new NameRenderer());
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(UIScale.scale(560), UIScale.scale(280)));

        JButton selectAllButton = new JButton(ConnectionTransferMessages.transfer_export_select_all);
        JButton selectNoneButton = new JButton(ConnectionTransferMessages.transfer_export_select_none);
        selectAllButton.addActionListener(e -> setAllChecked(true));
        selectNoneButton.addActionListener(e -> setAllChecked(false));
        JPanel selectButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        selectButtons.add(selectAllButton);
        selectButtons.add(selectNoneButton);

        JPanel listPanel = new JPanel(new BorderLayout(0, 6));
        listPanel.add(new JLabel(ConnectionTransferMessages.transfer_export_choose), BorderLayout.NORTH);
        listPanel.add(scrollPane, BorderLayout.CENTER);
        listPanel.add(selectButtons, BorderLayout.SOUTH);

        JPanel passwordPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(4, 0, 0, 8);
        gc.gridx = 0;
        gc.gridy = 0;
        gc.gridwidth = 2;
        passwordPanel.add(includePasswordsCheck, gc);
        gc.gridwidth = 1;
        gc.gridy = 1;
        passwordPanel.add(passphraseLabel, gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        passwordPanel.add(passphraseField, gc);
        gc.gridx = 0;
        gc.gridy = 2;
        gc.fill = GridBagConstraints.NONE;
        gc.weightx = 0;
        passwordPanel.add(confirmLabel, gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        passwordPanel.add(confirmField, gc);
        gc.gridx = 0;
        gc.gridy = 3;
        gc.gridwidth = 2;
        passwordPanel.add(hintNote, gc);
        includePasswordsCheck.addActionListener(e -> updatePasswordFields());
        updatePasswordFields();

        JButton exportButton = new JButton(ConnectionTransferMessages.transfer_export_button);
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        exportButton.addActionListener(e -> {
            if (validateInput()) {
                accepted = true;
                setVisible(false);
            }
        });
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(exportButton);
        buttons.add(cancelButton);
        buttons.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));

        JPanel south = new JPanel(new BorderLayout());
        south.add(passwordPanel, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        // Dialog insets of docs/chromite/design-spec.md "11. 대화상자" (10, 12)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(listPanel, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(exportButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the dialog; null when canceled. The caller reads the choice, then calls {@link #clear()}.
     *
     * @param preselect a server or group to check alone; null checks everything
     */
    @Nullable
    static ConnectionExportDialog open(@NotNull Component parent, @NotNull DBPDataSourceRegistry registry, @Nullable Object preselect) {
        Window owner = parent instanceof Window window ? window : SwingUtilities.getWindowAncestor(parent);
        ConnectionExportDialog dialog = new ConnectionExportDialog(owner, registry, preselect);
        dialog.setVisible(true);
        if (!dialog.accepted) {
            dialog.clear();
            return null;
        }
        return dialog;
    }

    @NotNull
    List<DBPDataSourceContainer> getConnections() {
        List<DBPDataSourceContainer> result = new ArrayList<>();
        for (Row row : rows) {
            if (row.checked && row.item instanceof DBPDataSourceContainer container) {
                result.add(container);
            }
        }
        return result;
    }

    @NotNull
    List<DBPDataSourceFolder> getFolders() {
        List<DBPDataSourceFolder> result = new ArrayList<>();
        for (Row row : rows) {
            if (row.checked && row.item instanceof DBPDataSourceFolder folder) {
                result.add(folder);
            }
        }
        return result;
    }

    /**
     * The passphrase (a copy the caller clears), or null when passwords are not included.
     */
    @Nullable
    char[] getPassphrase() {
        return includePasswordsCheck.isSelected() ? passphraseField.getPassword() : null;
    }

    /**
     * Clears the passphrase fields and disposes the dialog.
     */
    void clear() {
        passphraseField.setText(""); //$NON-NLS-1$
        confirmField.setText(""); //$NON-NLS-1$
        dispose();
    }

    private void addRows(@NotNull DBPDataSourceRegistry registry, @Nullable DBPDataSourceFolder parent, int depth) {
        for (DBPDataSourceFolder group : RegisteredServerCommands.getGroups(registry, parent)) {
            rows.add(new Row(group, depth));
            addRows(registry, group, depth + 1);
        }
        for (DBPDataSourceContainer server : RegisteredServerCommands.getServers(registry, parent)) {
            rows.add(new Row(server, depth));
        }
    }

    private void preselect(@Nullable Object preselect) {
        if (preselect == null) {
            setAllChecked(true);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).item == preselect) {
                setChecked(i, true);
            }
        }
    }

    private void setAllChecked(boolean checked) {
        for (Row row : rows) {
            row.checked = checked;
        }
        rowsModel.fireTableDataChanged();
    }

    /**
     * Checks a row; a group row checks (or unchecks) everything below it.
     */
    private void setChecked(int index, boolean checked) {
        Row row = rows.get(index);
        row.checked = checked;
        if (row.item instanceof DBPDataSourceFolder) {
            for (int i = index + 1; i < rows.size() && rows.get(i).depth > row.depth; i++) {
                rows.get(i).checked = checked;
            }
        }
        rowsModel.fireTableDataChanged();
    }

    private void updatePasswordFields() {
        boolean include = includePasswordsCheck.isSelected();
        passphraseLabel.setEnabled(include);
        passphraseField.setEnabled(include);
        confirmLabel.setEnabled(include);
        confirmField.setEnabled(include);
        hintNote.setText(include
            ? NLS.bind(ConnectionTransferMessages.transfer_export_passphrase_hint, ConnectionTransferFile.MIN_PASSPHRASE_LENGTH)
            : ConnectionTransferMessages.transfer_export_plain_hint);
    }

    private boolean validateInput() {
        if (getConnections().isEmpty()) {
            showProblem(ConnectionTransferMessages.transfer_export_nothing_selected);
            return false;
        }
        if (!includePasswordsCheck.isSelected()) {
            return true;
        }
        char[] passphrase = passphraseField.getPassword();
        char[] confirm = confirmField.getPassword();
        try {
            if (passphrase.length < ConnectionTransferFile.MIN_PASSPHRASE_LENGTH) {
                showProblem(NLS.bind(ConnectionTransferMessages.transfer_export_passphrase_short, ConnectionTransferFile.MIN_PASSPHRASE_LENGTH));
                passphraseField.requestFocusInWindow();
                return false;
            }
            if (!Arrays.equals(passphrase, confirm)) {
                showProblem(ConnectionTransferMessages.transfer_export_passphrase_mismatch);
                confirmField.requestFocusInWindow();
                return false;
            }
            return true;
        } finally {
            Arrays.fill(passphrase, '\0');
            Arrays.fill(confirm, '\0');
        }
    }

    private void showProblem(@NotNull String message) {
        JOptionPane.showMessageDialog(this, message, ConnectionTransferMessages.transfer_export_title, JOptionPane.WARNING_MESSAGE);
    }

    private final class RowsModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case COLUMN_NAME -> ConnectionTransferMessages.transfer_export_column_name;
                case COLUMN_HOST -> ConnectionTransferMessages.transfer_export_column_host;
                default -> ""; //$NON-NLS-1$
            };
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == COLUMN_CHECK ? Boolean.class : Object.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == COLUMN_CHECK;
        }

        @Override
        public Object getValueAt(int rowIndex, int column) {
            Row row = rows.get(rowIndex);
            return switch (column) {
                case COLUMN_CHECK -> row.checked;
                case COLUMN_NAME -> row;
                default -> row.item instanceof DBPDataSourceContainer container ? getHost(container) : ""; //$NON-NLS-1$
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int column) {
            if (column == COLUMN_CHECK) {
                setChecked(rowIndex, Boolean.TRUE.equals(value));
            }
        }
    }

    @NotNull
    private static String getHost(@NotNull DBPDataSourceContainer container) {
        DBPConnectionConfiguration configuration = container.getConnectionConfiguration();
        String host = configuration.getHostName();
        if (CommonUtils.isEmpty(host)) {
            return CommonUtils.notEmpty(configuration.getUrl());
        }
        return CommonUtils.isEmpty(configuration.getHostPort()) ? host : host + ":" + configuration.getHostPort(); //$NON-NLS-1$
    }

    /**
     * Name with the group or server icon, indented by depth.
     */
    private static final class NameRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column); //$NON-NLS-1$
            if (value instanceof Row item) {
                if (item.item instanceof DBPDataSourceFolder folder) {
                    setText(folder.getName());
                    setIcon(UiIcons.get("folder")); //$NON-NLS-1$
                } else if (item.item instanceof DBPDataSourceContainer container) {
                    setText(container.getName());
                    setIcon(NodeIcons.getDataSourceIcon(container));
                }
                setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(4 + 16 * item.depth), 0, 0));
            }
            return this;
        }
    }
}
