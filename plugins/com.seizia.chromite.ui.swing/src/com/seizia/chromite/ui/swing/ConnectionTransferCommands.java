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

import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceFolder;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.registry.ConnectionTransfer;
import com.seizia.chromite.registry.ConnectionTransferException;
import com.seizia.chromite.registry.ConnectionTransferFile;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.ConnectionTransferMessages;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 연결 내보내기·가져오기 명령(File 메뉴, Registered Servers 메뉴).
 *
 * 설계:
 * <ul>
 *     <li>모델은 {@link ConnectionTransfer}(registry 번들)가 맡고, 여기는 대화상자와 파일 고르기, 진행 대화상자, 결과 알림만 한다.</li>
 *     <li>암호에서 키를 만드는 PBKDF2가 1초 안팎 걸리므로 내보내기와 파일 읽기는 {@link ProgressDialog}의 백그라운드 작업에서 한다.
 *     가져오기 자체(레지스트리에 연결 더하기)는 Registered Servers의 다른 명령처럼 EDT에서 한다(메모리와 설정 파일만 바꾼다).</li>
 *     <li>잘못된 암호나 바뀐 파일(GCM 인증 실패)은 아무것도 가져오지 않았다고 알리고 암호를 다시 묻는다.</li>
 *     <li>암호({@code char[]})는 작업이 끝나면 지운다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/connection-transfer.md)
 */
final class ConnectionTransferCommands {

    private ConnectionTransferCommands() {
    }

    /**
     * Export Connections: the dialog, the target file, then the export.
     *
     * @param preselect a server or group to check alone; null checks every connection
     */
    static void exportConnections(@NotNull Component parent, @Nullable Object preselect) {
        DBPDataSourceRegistry registry = getRegistry(parent, ConnectionTransferMessages.transfer_export_title);
        if (registry == null) {
            return;
        }
        ConnectionExportDialog dialog = ConnectionExportDialog.open(parent, registry, preselect);
        if (dialog == null) {
            return;
        }
        List<DBPDataSourceContainer> connections = dialog.getConnections();
        List<DBPDataSourceFolder> folders = dialog.getFolders();
        char[] passphrase = dialog.getPassphrase();
        dialog.clear();
        try {
            Path file = chooseSaveFile(parent);
            if (file == null) {
                return;
            }
            int count = ProgressDialog.runTask(ConnectionTransferMessages.transfer_export_progress,
                monitor -> ConnectionTransfer.exportConnections(monitor, registry, connections, folders, passphrase, file));
            JOptionPane.showMessageDialog(getWindow(parent), NLS.bind(ConnectionTransferMessages.transfer_export_done, count, file),
                ConnectionTransferMessages.transfer_export_title, JOptionPane.INFORMATION_MESSAGE);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ConnectionTransferMessages.transfer_export_title, ConnectionTransferMessages.transfer_export_failed, e);
        } finally {
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
        }
    }

    /**
     * Import Connections: the file, its passphrase, the preview, then the import.
     */
    static void importConnections(@NotNull Component parent) {
        DBPDataSourceRegistry registry = getRegistry(parent, ConnectionTransferMessages.transfer_import_title);
        if (registry == null) {
            return;
        }
        Path file = chooseOpenFile(parent);
        if (file == null) {
            return;
        }
        ConnectionTransfer.ImportFile importFile = readFile(parent, registry, file);
        if (importFile == null) {
            return;
        }
        if (importFile.getEntries().isEmpty()) {
            JOptionPane.showMessageDialog(getWindow(parent), ConnectionTransferMessages.transfer_import_empty,
                ConnectionTransferMessages.transfer_import_title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Map<String, ConnectionTransfer.ConflictAction> actions = ConnectionImportDialog.open(parent, file.getFileName().toString(), importFile);
        if (actions == null) {
            return;
        }
        ConnectionTransfer.ImportResult result;
        try {
            result = ConnectionTransfer.importConnections(new VoidProgressMonitor(), registry, importFile, actions);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ConnectionTransferMessages.transfer_import_title, ConnectionTransferMessages.transfer_import_failed, e);
            return;
        } finally {
            // New folders appear in the navigator and Registered Servers
            RegisteredServerCommands.updateConfig(registry);
        }
        StringBuilder message = new StringBuilder(NLS.bind(ConnectionTransferMessages.transfer_import_done,
            new Object[]{result.getAdded(), result.getReplaced(), result.getCopied(), result.getSkipped()}));
        appendList(message, ConnectionTransferMessages.transfer_import_done_skipped_connected, result.getSkippedConnected());
        appendList(message, ConnectionTransferMessages.transfer_import_done_missing_drivers, result.getMissingDrivers());
        appendList(message, ConnectionTransferMessages.transfer_import_done_kept_profiles, result.getKeptProfiles());
        JOptionPane.showMessageDialog(getWindow(parent), message.toString(), ConnectionTransferMessages.transfer_import_title,
            result.getSkippedConnected().isEmpty() && result.getMissingDrivers().isEmpty()
                ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Reads (and decrypts) the file; asks the passphrase again after a wrong one.
     */
    @Nullable
    private static ConnectionTransfer.ImportFile readFile(@NotNull Component parent, @NotNull DBPDataSourceRegistry registry, @NotNull Path file) {
        boolean encrypted;
        try {
            encrypted = ConnectionTransfer.isEncrypted(file);
        } catch (DBException e) {
            showReadProblem(parent, e);
            return null;
        }
        while (true) {
            char[] passphrase = null;
            if (encrypted) {
                passphrase = askPassphrase(parent);
                if (passphrase == null) {
                    return null;
                }
            }
            char[] filePassphrase = passphrase;
            try {
                return ProgressDialog.runTask(ConnectionTransferMessages.transfer_import_progress,
                    monitor -> ConnectionTransfer.readFile(file, filePassphrase, registry));
            } catch (DBException e) {
                showReadProblem(parent, e);
                if (!(e instanceof ConnectionTransferException transferError)
                    || transferError.getReason() != ConnectionTransferException.Reason.WRONG_PASSPHRASE_OR_CHANGED) {
                    return null;
                }
            } finally {
                if (passphrase != null) {
                    Arrays.fill(passphrase, '\0');
                }
            }
        }
    }

    private static void showReadProblem(@NotNull Component parent, @NotNull DBException error) {
        if (error instanceof ConnectionTransferException transferError) {
            String message = switch (transferError.getReason()) {
                case NOT_A_TRANSFER_FILE -> ConnectionTransferMessages.transfer_import_not_a_file;
                case UNSUPPORTED_FORMAT -> ConnectionTransferMessages.transfer_import_unsupported;
                case WRONG_PASSPHRASE_OR_CHANGED -> ConnectionTransferMessages.transfer_import_wrong_passphrase;
                case INVALID_CONTENT -> ConnectionTransferMessages.transfer_import_invalid;
            };
            JOptionPane.showMessageDialog(getWindow(parent), message, ConnectionTransferMessages.transfer_import_title, JOptionPane.ERROR_MESSAGE);
        } else {
            DBWorkbench.getPlatformUI().showError(ConnectionTransferMessages.transfer_import_title, ConnectionTransferMessages.transfer_import_failed, error);
        }
    }

    /**
     * The passphrase of an encrypted file (the caller clears it), or null when canceled.
     */
    @Nullable
    private static char[] askPassphrase(@NotNull Component parent) {
        JPasswordField field = new JPasswordField(24);
        // The option pane focuses its OK button; the passphrase is typed right away
        field.addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
                SwingUtilities.invokeLater(field::requestFocusInWindow);
            }

            @Override
            public void ancestorRemoved(AncestorEvent event) {
            }

            @Override
            public void ancestorMoved(AncestorEvent event) {
            }
        });
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel(ConnectionTransferMessages.transfer_import_passphrase_prompt), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        int answer = JOptionPane.showConfirmDialog(getWindow(parent), panel, ConnectionTransferMessages.transfer_import_title,
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] passphrase = answer == JOptionPane.OK_OPTION ? field.getPassword() : null;
        field.setText(""); //$NON-NLS-1$
        return passphrase;
    }

    @Nullable
    private static Path chooseSaveFile(@NotNull Component parent) {
        JFileChooser chooser = createChooser(ConnectionTransferMessages.transfer_export_save_title);
        while (chooser.showSaveDialog(getWindow(parent)) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            String extension = "." + ConnectionTransferFile.FILE_EXTENSION; //$NON-NLS-1$
            if (!selected.getName().toLowerCase().endsWith(extension)) {
                selected = new File(selected.getParentFile(), selected.getName() + extension);
            }
            Path file = selected.toPath();
            if (!Files.exists(file) || JOptionPane.showConfirmDialog(getWindow(parent),
                NLS.bind(ConnectionTransferMessages.transfer_export_overwrite, file.getFileName()),
                ConnectionTransferMessages.transfer_export_title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
                return file;
            }
        }
        return null;
    }

    @Nullable
    private static Path chooseOpenFile(@NotNull Component parent) {
        JFileChooser chooser = createChooser(ConnectionTransferMessages.transfer_import_open_title);
        return chooser.showOpenDialog(getWindow(parent)) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
    }

    @NotNull
    private static JFileChooser createChooser(@NotNull String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        FileNameExtensionFilter filter = new FileNameExtensionFilter(ConnectionTransferMessages.transfer_file_filter, ConnectionTransferFile.FILE_EXTENSION);
        chooser.addChoosableFileFilter(filter);
        chooser.setFileFilter(filter);
        return chooser;
    }

    private static void appendList(@NotNull StringBuilder message, @NotNull String pattern, @NotNull List<String> names) {
        if (!names.isEmpty()) {
            message.append("\n\n").append(NLS.bind(pattern, String.join(", ", names))); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Nullable
    private static DBPDataSourceRegistry getRegistry(@NotNull Component parent, @NotNull String title) {
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        if (project == null) {
            JOptionPane.showMessageDialog(getWindow(parent), CoreMessages.common_no_active_project,
                title, JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return project.getDataSourceRegistry();
    }

    @Nullable
    private static Window getWindow(@NotNull Component component) {
        return component instanceof Window window ? window : SwingUtilities.getWindowAncestor(component);
    }
}
