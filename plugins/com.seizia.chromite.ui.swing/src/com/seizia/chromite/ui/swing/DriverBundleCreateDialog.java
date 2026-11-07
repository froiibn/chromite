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
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.registry.driver.DriverDescriptor;
import com.seizia.chromite.registry.driver.DriverOfflineBundle;
import com.seizia.chromite.registry.driver.DriverOfflineBundleWriter;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.DriverManagerMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.ByteNumberFormat;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Driver Manager &gt; Create Offline Bundle…: the user ticks drivers, then their files are downloaded and saved in one bundle file
 * ({@link DriverOfflineBundleWriter}) to install on a PC without internet access ({@link DriverBundleInstallDialog}).
 *
 * 설계: 관리자 트리는 한 드라이버만 고르는 편집용이라(Edit·Copy·Delete가 고른 하나에 동작) 그대로 두고, 이 창에 체크 목록을 따로 둔다.
 * 목록은 연결할 수 있는 드라이버({@link DriverSelectionPanel#getConnectableDrivers()}) 중 이 PC에서 내려받을 파일이 있는 것뿐이다.
 * Chromite에 포함된 드라이버는 내려받을 파일이 없으므로 나오지 않는다. 처음에는 관리자에서 고른 드라이버만 체크되어 있다
 * ("모두 선택"은 약 1.7 GB라 기본이 아니다). 만들기는 {@link ProgressDialog}에서 돌고 Cancel로 멈출 수 있다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/driver-offline-bundle.md)
 * - 2026-11-08 묶음 파일 덮어쓰기 확인은 바꾸기 / 취소 단추(취소가 기본) (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 */
final class DriverBundleCreateDialog extends JDialog {

    private static final int COLUMN_CHECK = 0;
    private static final int COLUMN_DRIVER = 1;
    private static final int COLUMN_CATEGORY = 2;
    private static final int COLUMN_FILES = 3;

    private final List<Row> rows = new ArrayList<>();
    private final RowModel model = new RowModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<RowModel> sorter;
    private final JTextField searchField = new JTextField();
    private final JLabel countLabel = new JLabel(" ");
    private final JButton createButton = new JButton(DriverManagerMessages.driverbundle_button_create);

    private DriverBundleCreateDialog(@Nullable Window owner, @Nullable DriverDescriptor preselected) {
        super(owner, DriverManagerMessages.driverbundle_create_title, ModalityType.APPLICATION_MODAL);
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        for (DBPDriver driver : DriverSelectionPanel.getConnectableDrivers()) {
            if (driver instanceof DriverDescriptor descriptor && DriverOfflineBundleWriter.hasDownloadableLibraries(descriptor)) {
                rows.add(new Row(descriptor, descriptor == preselected));
            }
        }
        rows.sort(Comparator.comparing(row -> row.driver.getName(), String.CASE_INSENSITIVE_ORDER));

        // Created after the rows: the sorter keeps the model's row count from its construction
        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(COLUMN_CHECK).setMaxWidth(36);
        table.getColumnModel().getColumn(COLUMN_DRIVER).setPreferredWidth(260);
        table.getColumnModel().getColumn(COLUMN_CATEGORY).setPreferredWidth(120);
        table.getColumnModel().getColumn(COLUMN_FILES).setPreferredWidth(120);
        table.getColumnModel().getColumn(COLUMN_DRIVER).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable jTable, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(jTable, value, isSelected, hasFocus, row, column);
                DriverDescriptor driver = rows.get(jTable.convertRowIndexToModel(row)).driver;
                setText(driver.getName());
                setIcon(NodeIcons.getIcon(driver.getIcon()));
                setToolTipText(CommonUtils.isEmpty(driver.getDescription()) ? driver.getFullName() : driver.getDescription());
                return this;
            }
        });
        // Space toggles the selected row like a check list
        table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "chromite.toggleRow"); //$NON-NLS-1$
        table.getActionMap().put("chromite.toggleRow", new AbstractAction() { //$NON-NLS-1$
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int viewRow = table.getSelectedRow();
                if (viewRow >= 0) {
                    Row row = rows.get(table.convertRowIndexToModel(viewRow));
                    model.setValueAt(!row.checked, table.convertRowIndexToModel(viewRow), COLUMN_CHECK);
                }
            }
        });

        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, DriverManagerMessages.drivermgr_search);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });

        JButton allButton = new JButton(DriverManagerMessages.driverbundle_select_all);
        allButton.addActionListener(e -> setAllChecked(true));
        JButton noneButton = new JButton(DriverManagerMessages.driverbundle_select_none);
        noneButton.addActionListener(e -> setAllChecked(false));
        JPanel selectionButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        selectionButtons.add(allButton);
        selectionButtons.add(noneButton);
        selectionButtons.add(countLabel);

        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.add(createHint(DriverManagerMessages.driverbundle_create_hint), BorderLayout.NORTH);
        north.add(searchField, BorderLayout.SOUTH);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(580, 360));
        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.add(north, BorderLayout.NORTH);
        center.add(scrollPane, BorderLayout.CENTER);
        center.add(selectionButtons, BorderLayout.SOUTH);

        createButton.addActionListener(e -> createBundle());
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        buttons.add(createButton);
        buttons.add(cancelButton);
        buttons.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

        JPanel content = new JPanel(new BorderLayout());
        // Dialog insets of docs/chromite/design-spec.md "11. 대화상자" (10, 12)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(center, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(createButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        updateCount();
        pack();
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(searchField::requestFocusInWindow);
    }

    /**
     * Driver Manager &gt; Create Offline Bundle…. {@code preselected} (the driver chosen in the manager) starts checked.
     */
    static void open(@Nullable Window owner, @Nullable DriverDescriptor preselected) {
        new DriverBundleCreateDialog(owner, preselected).setVisible(true);
    }

    /**
     * A wrapping, read-only text that looks like a secondary label.
     */
    @NotNull
    static JTextArea createHint(@NotNull String text) {
        JTextArea hint = new JTextArea(text);
        hint.setEditable(false);
        hint.setFocusable(false);
        hint.setOpaque(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setBorder(BorderFactory.createEmptyBorder());
        hint.setFont(UIManager.getFont("Label.font")); //$NON-NLS-1$
        hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        hint.setColumns(48);
        return hint;
    }

    private void applyFilter() {
        String pattern = normalize(searchField.getText());
        sorter.setRowFilter(pattern.isEmpty() ? null : new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends RowModel, ? extends Integer> entry) {
                DriverDescriptor driver = rows.get(entry.getIdentifier()).driver;
                @SuppressWarnings("deprecation")
                String category = driver.getCategory();
                return normalize(driver.getName()).contains(pattern) || normalize(category).contains(pattern)
                    || normalize(driver.getId()).contains(pattern) || normalize(driver.getDescription()).contains(pattern);
            }
        });
    }

    @NotNull
    private static String normalize(@Nullable String text) {
        return CommonUtils.notEmpty(text).toLowerCase(Locale.ENGLISH).replaceAll("\\s+", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Select All / Clear act on the rows the search shows.
     */
    private void setAllChecked(boolean checked) {
        for (int viewRow = 0; viewRow < table.getRowCount(); viewRow++) {
            rows.get(table.convertRowIndexToModel(viewRow)).checked = checked;
        }
        model.fireTableDataChanged();
        updateCount();
    }

    private void updateCount() {
        long count = rows.stream().filter(row -> row.checked).count();
        countLabel.setText(NLS.bind(DriverManagerMessages.driverbundle_selected_count, count));
        createButton.setEnabled(count > 0);
    }

    private void createBundle() {
        List<DriverDescriptor> drivers = rows.stream().filter(row -> row.checked).map(row -> row.driver).toList();
        if (drivers.isEmpty()) {
            return;
        }
        Path target = chooseTarget();
        if (target == null) {
            return;
        }
        DriverOfflineBundleWriter.Result result;
        try {
            result = ProgressDialog.runTask(DriverManagerMessages.driverbundle_task_create, monitor -> {
                try {
                    return DriverOfflineBundleWriter.create(monitor, drivers, target);
                } catch (InterruptedException e) {
                    return null;
                }
            });
        } catch (DBException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), DriverManagerMessages.driverbundle_create_title, JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (result == null) {
            // Canceled
            return;
        }
        StringBuilder message = new StringBuilder(NLS.bind(DriverManagerMessages.driverbundle_created, new Object[]{
            target, result.driverCount(), result.fileCount(), new ByteNumberFormat(ByteNumberFormat.BinaryPrefix.ISO).format(result.totalSize())}));
        if (!result.warnings().isEmpty()) {
            message.append("\n\n").append(DriverManagerMessages.driverbundle_created_warnings); //$NON-NLS-1$
            for (String warning : result.warnings()) {
                message.append("\n - ").append(warning); //$NON-NLS-1$
            }
        }
        JOptionPane.showMessageDialog(this, message.toString(), DriverManagerMessages.driverbundle_create_title,
            result.warnings().isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        dispose();
    }

    @Nullable
    private Path chooseTarget() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(DriverManagerMessages.driverbundle_save_title);
        chooser.setFileFilter(createFileFilter());
        chooser.setSelectedFile(new File("chromite-drivers-" //$NON-NLS-1$
            + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "." + DriverOfflineBundle.FILE_EXTENSION)); //$NON-NLS-1$
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return null;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase(Locale.ENGLISH).endsWith("." + DriverOfflineBundle.FILE_EXTENSION)) { //$NON-NLS-1$
            file = new File(file.getParentFile(), file.getName() + "." + DriverOfflineBundle.FILE_EXTENSION); //$NON-NLS-1$
        }
        if (file.exists() && !SwingPlatformUI.confirmChange(this, NLS.bind(DriverManagerMessages.driverbundle_overwrite, file),
            DriverManagerMessages.driverbundle_save_title, CoreMessages.common_replace, JOptionPane.WARNING_MESSAGE)) {
            return null;
        }
        return file.toPath();
    }

    @NotNull
    static FileNameExtensionFilter createFileFilter() {
        return new FileNameExtensionFilter(NLS.bind(DriverManagerMessages.driverbundle_file_filter, DriverOfflineBundle.FILE_EXTENSION),
            DriverOfflineBundle.FILE_EXTENSION);
    }

    private static final class Row {
        private final DriverDescriptor driver;
        private final boolean downloaded;
        private boolean checked;

        private Row(@NotNull DriverDescriptor driver, boolean checked) {
            this.driver = driver;
            this.checked = checked;
            // Registered files that exist (no Maven resolution, no network)
            this.downloaded = driver.getDefaultDriverLoader().isDriverInstalled();
        }
    }

    private final class RowModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case COLUMN_DRIVER -> DriverManagerMessages.driverbundle_column_driver;
                case COLUMN_CATEGORY -> DriverManagerMessages.driverbundle_column_category;
                case COLUMN_FILES -> DriverManagerMessages.driverbundle_column_files;
                default -> ""; //$NON-NLS-1$
            };
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == COLUMN_CHECK ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == COLUMN_CHECK;
        }

        @Override
        @SuppressWarnings("deprecation")
        public Object getValueAt(int rowIndex, int column) {
            Row row = rows.get(rowIndex);
            return switch (column) {
                case COLUMN_CHECK -> row.checked;
                case COLUMN_DRIVER -> row.driver.getName();
                case COLUMN_CATEGORY -> CommonUtils.notEmpty(row.driver.getCategory());
                default -> row.downloaded ? DriverManagerMessages.driverbundle_files_downloaded : DriverManagerMessages.driverbundle_files_not_downloaded;
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int column) {
            if (column == COLUMN_CHECK) {
                rows.get(rowIndex).checked = Boolean.TRUE.equals(value);
                fireTableCellUpdated(rowIndex, column);
                updateCount();
            }
        }
    }
}
