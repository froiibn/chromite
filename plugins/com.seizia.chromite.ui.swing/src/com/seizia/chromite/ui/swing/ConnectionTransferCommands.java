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
import com.seizia.chromite.ext.oracle.importer.SqlDeveloperConnectionReader;
import com.seizia.chromite.ext.oracle.importer.ToadConnectionReader;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceFolder;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.registry.ConnectionTransfer;
import com.seizia.chromite.registry.ConnectionTransferException;
import com.seizia.chromite.registry.ConnectionTransferFile;
import com.seizia.chromite.registry.CsvConnectionReader;
import com.seizia.chromite.registry.ForeignConnectionImport;
import com.seizia.chromite.registry.ForeignConnectionReader;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.ConnectionTransferMessages;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.utils.RuntimeUtils;
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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 연결 내보내기·가져오기 명령(File 메뉴, Registered Servers 메뉴)과 다른 도구에서 가져오기(File > Import Connections > From Another Tool).
 *
 * 설계:
 * <ul>
 *     <li>모델은 {@link ConnectionTransfer}(registry 번들)가 맡고, 여기는 대화상자와 파일 고르기, 진행 대화상자, 결과 알림만 한다.</li>
 *     <li>암호에서 키를 만드는 PBKDF2가 1초 안팎 걸리므로 내보내기와 파일 읽기는 {@link ProgressDialog}의 백그라운드 작업에서 한다.
 *     가져오기 자체(레지스트리에 연결 더하기)는 Registered Servers의 다른 명령처럼 EDT에서 한다(메모리와 설정 파일만 바꾼다).</li>
 *     <li>잘못된 암호나 바뀐 파일(GCM 인증 실패)은 아무것도 가져오지 않았다고 알리고 암호를 다시 묻는다.</li>
 *     <li>암호({@code char[]})는 작업이 끝나면 지운다.</li>
 *     <li>다른 도구에서 가져오기는 원본 도구와 파일을 고른 뒤 모델({@link ForeignConnectionImport})이 만든 가져오기 파일을 Chromite 파일과
 *     같은 미리 보기·가져오기({@link #previewAndImport})로 넘긴다. 도구 목록에만 그 도구 이름을 쓴다(파일 형식을 고르려면 필요하다).
 *     SQL Developer는 이 PC의 설정 폴더가 있으면 파일 고르기를 그 연결 폴더에서 시작한다(SWT 가져오기가 찾던 위치).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/connection-transfer.md)
 * - 2026-11-07 가져온 연결의 저장 실패(OS 보안 저장소가 비밀번호를 저장하지 못함)를 결과 뒤에 알림 (docs/chromite/credential-storage.md)
 * - 2026-11-08 내보내기 파일 덮어쓰기 확인은 바꾸기 / 취소 단추(취소가 기본) (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 * - 2026-11-08 다른 도구에서 가져오기(SQL Developer, Toad, CSV), 미리 보기 뒤 단계를 {@link #previewAndImport}로 나눔,
 *   결과에 알 수 없는 DB 종류의 연결 (docs/chromite/connection-transfer.md "다른 도구에서 가져오기")
 * - 2026-11-08 설정 가져오기가 같은 암호 묻기·결과 알림을 쓰도록 {@link #readData}(다른 파일 안의 연결 파일)와
 *   {@link #showImportResult}를 나눔 (docs/chromite/settings-transfer.md)
 * - 2026-11-13 다른 도구 고르기의 안내를 HTML 라벨 대신 {@link WrappingNote}로: Swing 줄 바꿈이 한국어 낱말 가운데서 끊었다
 *   (앱 변경 #90, docs/chromite/swing-options.md "HTML 라벨 안내")
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
        previewAndImport(parent, registry, file, importFile);
    }

    /**
     * Import Connections > From Another Tool: the source tool, its file, then the same preview and import as a Chromite file.
     */
    static void importFromOtherTool(@NotNull Component parent) {
        DBPDataSourceRegistry registry = getRegistry(parent, ConnectionTransferMessages.transfer_other_title);
        if (registry == null) {
            return;
        }
        ForeignSource source = chooseForeignSource(parent);
        if (source == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(ConnectionTransferMessages.transfer_other_open_title);
        FileNameExtensionFilter filter = new FileNameExtensionFilter(source.getFilterDescription(), source.extensions);
        chooser.addChoosableFileFilter(filter);
        chooser.setFileFilter(filter);
        File startFolder = source.getStartFolder();
        if (startFolder != null) {
            chooser.setCurrentDirectory(startFolder);
        }
        if (chooser.showOpenDialog(getWindow(parent)) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        ConnectionTransfer.ImportFile importFile;
        try {
            importFile = ProgressDialog.runTask(ConnectionTransferMessages.transfer_other_progress,
                monitor -> ForeignConnectionImport.prepare(monitor, source.reader.read(file), registry));
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ConnectionTransferMessages.transfer_other_title, ConnectionTransferMessages.transfer_other_failed, e);
            return;
        }
        if (importFile.getEntries().isEmpty()) {
            JOptionPane.showMessageDialog(getWindow(parent), ConnectionTransferMessages.transfer_other_empty,
                ConnectionTransferMessages.transfer_other_title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        previewAndImport(parent, registry, file, importFile);
    }

    /**
     * The preview (conflicts and their actions), the import, then what it did.
     */
    private static void previewAndImport(
        @NotNull Component parent,
        @NotNull DBPDataSourceRegistry registry,
        @NotNull Path file,
        @NotNull ConnectionTransfer.ImportFile importFile
    ) {
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
        showImportResult(parent, registry, result);
    }

    /**
     * What an import did, then a failure to save the imported connections if there was one.
     */
    static void showImportResult(
        @NotNull Component parent,
        @NotNull DBPDataSourceRegistry registry,
        @NotNull ConnectionTransfer.ImportResult result
    ) {
        StringBuilder message = new StringBuilder(NLS.bind(ConnectionTransferMessages.transfer_import_done,
            new Object[]{result.getAdded(), result.getReplaced(), result.getCopied(), result.getSkipped()}));
        appendList(message, ConnectionTransferMessages.transfer_import_done_skipped_connected, result.getSkippedConnected());
        appendList(message, ConnectionTransferMessages.transfer_import_done_missing_drivers, result.getMissingDrivers());
        appendList(message, ConnectionTransferMessages.transfer_import_done_unrecognized, result.getUnrecognizedDrivers());
        appendList(message, ConnectionTransferMessages.transfer_import_done_kept_profiles, result.getKeptProfiles());
        JOptionPane.showMessageDialog(getWindow(parent), message.toString(), ConnectionTransferMessages.transfer_import_title,
            result.getSkippedConnected().isEmpty() && result.getMissingDrivers().isEmpty() && result.getUnrecognizedDrivers().isEmpty()
                ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        // Saving the imported connections keeps a failure (the secure storage could not keep the passwords) as the last error
        Throwable saveError = registry.getLastError();
        if (saveError != null) {
            DBWorkbench.getPlatformUI().showError(ConnectionTransferMessages.transfer_import_title,
                ConnectionTransferMessages.transfer_import_save_failed, saveError);
        }
    }

    /**
     * The tool to import from, or null when canceled.
     */
    @Nullable
    private static ForeignSource chooseForeignSource(@NotNull Component parent) {
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
        panel.add(new JLabel(ConnectionTransferMessages.transfer_other_choose));
        ButtonGroup group = new ButtonGroup();
        Map<ForeignSource, JRadioButton> buttons = new EnumMap<>(ForeignSource.class);
        for (ForeignSource source : ForeignSource.values()) {
            JRadioButton button = new JRadioButton(source.getLabel());
            group.add(button);
            panel.add(button);
            buttons.put(source, button);
        }
        buttons.get(ForeignSource.SQL_DEVELOPER).setSelected(true);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.add(panel, BorderLayout.NORTH);
        // Wraps at spaces only (an HTML label broke Korean words), at most as wide as the HTML label was (420 CSS px, which
        // Swing's HTML draws 1.3 times as wide)
        content.add(new WrappingNote(ConnectionTransferMessages.transfer_other_note, UIScale.scale(546)), BorderLayout.CENTER);
        int answer = JOptionPane.showConfirmDialog(getWindow(parent), content, ConnectionTransferMessages.transfer_other_title,
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return null;
        }
        for (Map.Entry<ForeignSource, JRadioButton> entry : buttons.entrySet()) {
            if (entry.getValue().isSelected()) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Reads (and decrypts) the file; asks the passphrase again after a wrong one.
     */
    @Nullable
    private static ConnectionTransfer.ImportFile readFile(@NotNull Component parent, @NotNull DBPDataSourceRegistry registry, @NotNull Path file) {
        return read(parent, () -> ConnectionTransfer.isEncrypted(file), passphrase -> ConnectionTransfer.readFile(file, passphrase, registry));
    }

    /**
     * Like {@link #readFile}, for the content of a connection file kept inside another file (Import Settings).
     */
    @Nullable
    static ConnectionTransfer.ImportFile readData(@NotNull Component parent, @NotNull DBPDataSourceRegistry registry, @NotNull byte[] data) {
        return read(parent, () -> ConnectionTransfer.isEncrypted(data), passphrase -> ConnectionTransfer.readData(data, passphrase, registry));
    }

    private interface EncryptionCheck {
        boolean isEncrypted() throws DBException;
    }

    private interface ContentReader {
        @NotNull
        ConnectionTransfer.ImportFile read(@Nullable char[] passphrase) throws DBException;
    }

    @Nullable
    private static ConnectionTransfer.ImportFile read(@NotNull Component parent, @NotNull EncryptionCheck check, @NotNull ContentReader reader) {
        boolean encrypted;
        try {
            encrypted = check.isEncrypted();
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
                    monitor -> reader.read(filePassphrase));
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
            if (!Files.exists(file) || SwingPlatformUI.confirmChange(getWindow(parent),
                NLS.bind(ConnectionTransferMessages.transfer_export_overwrite, file.getFileName()),
                ConnectionTransferMessages.transfer_export_title, CoreMessages.common_replace, JOptionPane.WARNING_MESSAGE)) {
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

    /**
     * Another tool whose connections can be imported, with the reader of its file format.
     */
    private enum ForeignSource {
        SQL_DEVELOPER(new SqlDeveloperConnectionReader(), "json", "xml"), //$NON-NLS-1$ //$NON-NLS-2$
        TOAD(new ToadConnectionReader(), "xml"), //$NON-NLS-1$
        CSV(new CsvConnectionReader(), "csv", "txt"); //$NON-NLS-1$ //$NON-NLS-2$

        /**
         * SQL Developer keeps its settings here (Windows, older Windows, Linux and macOS), like the SWT importer looked them up.
         */
        private static final String[] SQL_DEVELOPER_HOMES = {
            "AppData/Roaming/SQL Developer", "Application Data/SQL Developer", ".sqldeveloper" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        };
        private static final String SQL_DEVELOPER_SYSTEM_PREFIX = "system"; //$NON-NLS-1$
        private static final String SQL_DEVELOPER_CONNECTIONS_FOLDER = "o.jdeveloper.db.connection"; //$NON-NLS-1$

        private final ForeignConnectionReader reader;
        private final String[] extensions;

        ForeignSource(@NotNull ForeignConnectionReader reader, @NotNull String... extensions) {
            this.reader = reader;
            this.extensions = extensions;
        }

        @NotNull
        String getLabel() {
            return switch (this) {
                case SQL_DEVELOPER -> ConnectionTransferMessages.transfer_other_sqldeveloper;
                case TOAD -> ConnectionTransferMessages.transfer_other_toad;
                case CSV -> ConnectionTransferMessages.transfer_other_csv;
            };
        }

        @NotNull
        String getFilterDescription() {
            return switch (this) {
                case SQL_DEVELOPER -> ConnectionTransferMessages.transfer_other_filter_sqldeveloper;
                case TOAD -> ConnectionTransferMessages.transfer_other_filter_toad;
                case CSV -> ConnectionTransferMessages.transfer_other_filter_csv;
            };
        }

        /**
         * Where the file chooser starts: the connection folder of the newest SQL Developer settings on this computer, or null.
         */
        @Nullable
        File getStartFolder() {
            if (this != SQL_DEVELOPER) {
                return null;
            }
            Path userHome = RuntimeUtils.getUserHomePath();
            for (String homeName : SQL_DEVELOPER_HOMES) {
                Path home = userHome.resolve(homeName);
                if (!Files.isDirectory(home)) {
                    continue;
                }
                File[] systemFolders = home.toFile().listFiles(f -> f.isDirectory() && f.getName().startsWith(SQL_DEVELOPER_SYSTEM_PREFIX));
                if (systemFolders != null) {
                    // "system23.1.1.345.2114" and the like: the newest version sorts last
                    Arrays.sort(systemFolders, Comparator.comparing(File::getName).reversed());
                    for (File systemFolder : systemFolders) {
                        File[] connectionFolders = systemFolder.listFiles(
                            f -> f.isDirectory() && f.getName().startsWith(SQL_DEVELOPER_CONNECTIONS_FOLDER));
                        if (connectionFolders != null && connectionFolders.length > 0) {
                            return connectionFolders[0];
                        }
                    }
                }
                return home.toFile();
            }
            return null;
        }
    }
}
