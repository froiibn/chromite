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

import com.seizia.chromite.registry.driver.DriverOfflineBundle;
import com.seizia.chromite.registry.driver.DriverOfflineBundleInstaller;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.DriverManagerMessages;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.ByteNumberFormat;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Driver Manager &gt; Install Offline Bundle…: choose a bundle file, see what it installs, then install it
 * ({@link DriverOfflineBundleInstaller}).
 *
 * 설계: 파일을 고르면 먼저 진행 대화상자에서 묶음 전체를 검사한다(ZIP 안전 검사, 매니페스트, 모든 파일의 SHA-256). 검사에 실패하면 미리 보기 없이
 * 오류만 보이고 아무것도 쓰지 않는다. 미리 보기는 드라이버별 버전과 상태(설치함, 이미 설치됨, 일부만 있음, 이 Chromite에 없음), 파일 수·크기·
 * 이미 있는 파일 수, 내용이 달라 바뀔 파일 목록, 다른 플랫폼에서 만든 묶음이면 그 안내를 보인다. 설치를 눌러야만 파일을 쓴다.
 * 네트워크는 쓰지 않으므로 오프라인 모드에서도 같다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/driver-offline-bundle.md)
 */
final class DriverBundleInstallDialog extends JDialog {

    private final DriverOfflineBundleInstaller.Preview preview;
    private boolean installed;

    private DriverBundleInstallDialog(@Nullable Window owner, @NotNull DriverOfflineBundleInstaller.Preview preview) {
        super(owner, DriverManagerMessages.driverbundle_install_title, ModalityType.APPLICATION_MODAL);
        this.preview = preview;
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        DriverOfflineBundle.Manifest manifest = preview.getManifest();
        JPanel north = new JPanel(new BorderLayout(0, 6));
        north.add(DriverBundleCreateDialog.createHint(NLS.bind(DriverManagerMessages.driverbundle_install_source, new Object[]{
            preview.getBundle(), CommonUtils.notEmpty(manifest.getChromiteVersion()), CommonUtils.notEmpty(manifest.getCreated()),
            CommonUtils.notEmpty(manifest.getOs()), CommonUtils.notEmpty(manifest.getArch())})), BorderLayout.NORTH);
        if (!Objects.equals(manifest.getOs(), Platform.getOS()) || !Objects.equals(manifest.getArch(), Platform.getOSArch())) {
            north.add(DriverBundleCreateDialog.createHint(NLS.bind(DriverManagerMessages.driverbundle_platform_hint, new Object[]{
                manifest.getOs(), manifest.getArch(), Platform.getOS(), Platform.getOSArch()})), BorderLayout.SOUTH);
        }

        JTable table = new JTable(new DriverModel(preview.getDrivers()));
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(200);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable jTable, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(jTable, value, isSelected, hasFocus, row, column);
                setToolTipText(value == null ? null : value.toString());
                return this;
            }
        });
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(640, 200));

        int present = 0;
        int different = 0;
        long size = 0;
        StringBuilder differentFiles = new StringBuilder();
        for (DriverOfflineBundleInstaller.FilePreview file : preview.getFiles()) {
            size += file.getEntry().getSize();
            switch (file.getStatus()) {
                case PRESENT -> present++;
                case DIFFERENT -> {
                    different++;
                    differentFiles.append(differentFiles.isEmpty() ? "" : "\n").append(file.getTarget()); //$NON-NLS-1$ //$NON-NLS-2$
                }
                default -> {
                }
            }
        }
        int total = preview.getFiles().size();
        JPanel south = new JPanel(new BorderLayout(0, 6));
        if (total > 0) {
            south.add(new JLabel(NLS.bind(DriverManagerMessages.driverbundle_files_summary, new Object[]{
                total, new ByteNumberFormat(ByteNumberFormat.BinaryPrefix.ISO).format(size), total - present - different, present, different})),
                BorderLayout.NORTH);
        }
        if (different > 0) {
            // Never overwrite silently: the files that will be replaced are listed before Install
            JPanel differentPanel = new JPanel(new BorderLayout(0, 4));
            differentPanel.add(new JLabel(DriverManagerMessages.driverbundle_different_hint, UIManager.getIcon("OptionPane.warningIcon"), //$NON-NLS-1$
                SwingConstants.LEADING), BorderLayout.NORTH);
            JTextArea list = new JTextArea(differentFiles.toString(), Math.min(5, different), 60);
            list.setEditable(false);
            differentPanel.add(new JScrollPane(list), BorderLayout.CENTER);
            south.add(differentPanel, BorderLayout.CENTER);
        }
        if (!preview.hasChanges()) {
            south.add(new JLabel(DriverManagerMessages.driverbundle_nothing), BorderLayout.SOUTH);
        }

        JButton installButton = new JButton(DriverManagerMessages.driverbundle_button_install);
        installButton.setEnabled(preview.hasChanges());
        installButton.addActionListener(e -> install());
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        buttons.add(installButton);
        buttons.add(cancelButton);
        buttons.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.add(north, BorderLayout.NORTH);
        center.add(scrollPane, BorderLayout.CENTER);
        center.add(south, BorderLayout.SOUTH);
        JPanel content = new JPanel(new BorderLayout());
        // Dialog insets of docs/chromite/design-spec.md "11. 대화상자" (10, 12)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(center, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(installButton.isEnabled() ? installButton : cancelButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Driver Manager &gt; Install Offline Bundle…. Returns true when drivers were installed.
     */
    static boolean open(@Nullable Window owner) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(DriverManagerMessages.driverbundle_open_title);
        chooser.setFileFilter(DriverBundleCreateDialog.createFileFilter());
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return false;
        }
        Path bundle = chooser.getSelectedFile().toPath();
        DriverOfflineBundleInstaller.Preview preview;
        try {
            preview = ProgressDialog.runTask(DriverManagerMessages.driverbundle_task_read, monitor -> {
                try {
                    return DriverOfflineBundleInstaller.read(monitor, bundle);
                } catch (InterruptedException e) {
                    return null;
                }
            });
        } catch (DBException e) {
            JOptionPane.showMessageDialog(owner, e.getMessage(), DriverManagerMessages.driverbundle_install_title, JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (preview == null) {
            // Canceled
            return false;
        }
        DriverBundleInstallDialog dialog = new DriverBundleInstallDialog(owner, preview);
        dialog.setVisible(true);
        return dialog.installed;
    }

    private void install() {
        DriverOfflineBundleInstaller.Result result;
        try {
            result = ProgressDialog.runTask(DriverManagerMessages.driverbundle_task_install, monitor -> {
                try {
                    return DriverOfflineBundleInstaller.install(monitor, preview);
                } catch (InterruptedException e) {
                    return null;
                }
            });
        } catch (DBException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), DriverManagerMessages.driverbundle_install_title, JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (result == null) {
            // Canceled before anything was moved into place
            return;
        }
        installed = true;
        JOptionPane.showMessageDialog(this, NLS.bind(DriverManagerMessages.driverbundle_installed, new Object[]{
                result.driverCount(), result.copiedCount(), result.presentCount(), result.replacedCount()}),
            DriverManagerMessages.driverbundle_install_title, JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    @NotNull
    private static String getStatusText(@NotNull DriverOfflineBundleInstaller.DriverPreview driver) {
        return switch (driver.getStatus()) {
            case INSTALL -> DriverManagerMessages.driverbundle_status_install;
            case INSTALLED -> DriverManagerMessages.driverbundle_status_installed;
            case INCOMPLETE -> NLS.bind(DriverManagerMessages.driverbundle_status_incomplete, String.join(", ", driver.getMissingLibraries())); //$NON-NLS-1$
            case NOT_FOUND -> DriverManagerMessages.driverbundle_status_not_found;
        };
    }

    /**
     * The resolved version of each library: the artifact or file name and its version.
     */
    @NotNull
    private static String getVersionsText(@NotNull DriverOfflineBundle.DriverEntry driver) {
        return driver.getLibraries().stream()
            .filter(library -> !library.getFiles().isEmpty())
            .map(library -> {
                String path = library.getFiles().getFirst().getPath();
                String name = path.substring(path.lastIndexOf('/') + 1);
                return CommonUtils.isEmpty(library.getResolvedVersion()) || name.contains(library.getResolvedVersion())
                    ? name : name + " " + library.getResolvedVersion(); //$NON-NLS-1$
            })
            .collect(Collectors.joining(", ")); //$NON-NLS-1$
    }

    private static final class DriverModel extends AbstractTableModel {
        private final List<DriverOfflineBundleInstaller.DriverPreview> drivers;

        private DriverModel(@NotNull List<DriverOfflineBundleInstaller.DriverPreview> drivers) {
            this.drivers = drivers;
        }

        @Override
        public int getRowCount() {
            return drivers.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> DriverManagerMessages.driverbundle_column_driver;
                case 1 -> DriverManagerMessages.driverbundle_column_versions;
                default -> DriverManagerMessages.driverbundle_column_status;
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            DriverOfflineBundleInstaller.DriverPreview driver = drivers.get(row);
            return switch (column) {
                case 0 -> driver.getDriver() != null ? driver.getDriver().getName() : driver.getEntry().getName();
                case 1 -> getVersionsText(driver.getEntry());
                default -> getStatusText(driver);
            };
        }
    }
}
