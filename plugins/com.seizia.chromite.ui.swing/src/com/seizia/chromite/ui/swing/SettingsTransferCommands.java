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

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceFolder;
import com.seizia.chromite.model.WorkspaceConfigEventManager;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.qm.QMConstants;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.registry.ConnectionTransfer;
import com.seizia.chromite.registry.RegistryConstants;
import com.seizia.chromite.registry.SettingsBackup;
import com.seizia.chromite.registry.SettingsTransfer;
import com.seizia.chromite.registry.SettingsTransfer.Action;
import com.seizia.chromite.registry.SettingsTransfer.Archive;
import com.seizia.chromite.registry.SettingsTransfer.Blocker;
import com.seizia.chromite.registry.SettingsTransfer.Change;
import com.seizia.chromite.registry.SettingsTransfer.Kind;
import com.seizia.chromite.registry.SettingsTransfer.Section;
import com.seizia.chromite.registry.SettingsTransferException;
import com.seizia.chromite.registry.formatter.DataFormatterRegistry;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.tools.transfer.DTConstants;
import com.seizia.chromite.ui.swing.internal.ConnectionTransferMessages;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.SettingsTransferMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.List;

/**
 * File &gt; Export Settings, Import Settings and Undo Settings Import.
 *
 * 설계:
 * <ul>
 *     <li>설정 값·옵션 설정 파일·드라이버는 모델({@link SettingsTransfer}, registry 번들)이 맡고, 여기는 화면 쪽이 아는 것을 더한다:
 *     이 PC의 상태라서 옮기지 않는 설정 키({@link #isExported}), Swing의 옵션 설정 파일(SQL 템플릿)과 다시 읽기, Query Library의
 *     개인 루트(My Queries, {@link QueryLibraryArchive}의 모으기·쓰기와 같은 ID 규칙), 프로젝트의 스크립트 폴더({@link ScriptFiles})와
 *     연결 묶기, #108 연결 내보내기·가져오기 대화상자.</li>
 *     <li>가져오기 순서: 파일 읽기(진행 대화상자) → 이 PC와 비교(진행 대화상자) → 미리 보기({@link SettingsImportDialog}) → 바꿀 항목의
 *     이전 값을 백업({@link SettingsBackup})하고 저장 → 바꾸기(EDT, 파일과 메모리만) → 연결(#108의 암호·미리 보기, 바꿀 연결도 백업) →
 *     스크립트의 연결 묶기(가져온 연결이 있어야 묶이므로 연결 뒤) → 결과. 한 부분이 실패해도 나머지는 가져오고 실패를 한 번에 알린다.</li>
 *     <li>공유 루트(네트워크 폴더, Git)의 쿼리는 넣지 않는다: 그 폴더 자체가 공유 경로다. 루트 목록은 이 PC의 경로라 설정 키에서도 뺀다.</li>
 *     <li>트랜잭션: 설정 가져오기는 세션을 건드리지 않는다. 연결 중인 연결은 바꾸지 않고(#108과 같음), 되돌리기도 연결 중인 연결은 그대로 둔다.
 *     연결 유형(자동 커밋 기본값 등)을 가져와도 열린 세션의 커밋 방식은 바뀌지 않는다(연결이 쥔 유형 객체는 다시 시작할 때까지 그대로).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/settings-transfer.md)
 */
final class SettingsTransferCommands {

    private static final Log log = Log.getLog(SettingsTransferCommands.class);

    /**
     * Preference keys that hold the state or the folders of this computer, not settings to move. The owners keep them as
     * private constants: MainFrame (window bounds), RegisteredServersPanel and ObjectExplorerServers (expanded and shown
     * servers), ConnectionHistory (recent drivers), RecentFiles, ResultSetLobFiles and DataExportWizard (last folders),
     * QueryLibrary (root folders); the model's folder settings (driver files, query log, data transfer fallback folder).
     */
    private static final Set<String> STATE_KEYS = Set.of(
        "mainWindow.bounds", //$NON-NLS-1$
        "mainWindow.maximized", //$NON-NLS-1$
        "registeredServers.expanded", //$NON-NLS-1$
        "objectExplorer.servers", //$NON-NLS-1$
        "connectDialog.recentDrivers", //$NON-NLS-1$
        "sqlEditor.recentFiles", //$NON-NLS-1$
        ResultSetLobFiles.PREF_FOLDER,
        "dataTransfer.export.folder", //$NON-NLS-1$
        "queryLibrary.roots", //$NON-NLS-1$
        ModelPreferences.UI_DRIVERS_HOME,
        QMConstants.PROP_LOG_DIRECTORY,
        DTConstants.PREF_FALLBACK_OUTPUT_DIRECTORY
    );
    /**
     * Key prefixes of the same kind: ConnectionHistory (server names typed per driver) and QueryLibraryState (last parameter
     * values, "active on this computer" of scheduled queries, alert state), which are kept per computer by design.
     */
    private static final List<String> STATE_PREFIXES = List.of(
        "connectDialog.serverHistory.", //$NON-NLS-1$
        "queryLibrary.lastValues.", //$NON-NLS-1$
        "queryLibrary.schedule.active.", //$NON-NLS-1$
        "queryLibrary.alert." //$NON-NLS-1$
    );
    /**
     * Size ({@code ToolWindow}) and place ({@code ToolWindowDocking}) of a tool window: "&lt;tool window id&gt;.size",
     * "&lt;tool window id&gt;.restorePlace". Tool window ids have no dot, so "sqlEditor.font.size" is a setting.
     */
    private static final String TOOL_WINDOW_SIZE_SUFFIX = ".size"; //$NON-NLS-1$
    private static final String TOOL_WINDOW_PLACE_SUFFIX = ".restorePlace"; //$NON-NLS-1$

    private SettingsTransferCommands() {
    }

    /**
     * True if a preference is a setting to move, not the state of this computer.
     */
    static boolean isExported(@NotNull String node, @NotNull String key) {
        if (STATE_KEYS.contains(key) || key.endsWith(TOOL_WINDOW_PLACE_SUFFIX)) {
            return false;
        }
        if (key.endsWith(TOOL_WINDOW_SIZE_SUFFIX) && key.indexOf('.') == key.length() - TOOL_WINDOW_SIZE_SUFFIX.length()) {
            return false;
        }
        for (String prefix : STATE_PREFIXES) {
            if (key.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Option pages that keep their own file: SQL templates, connection types, data formats.
     */
    @NotNull
    static List<SettingsTransfer.ConfigFile> getConfigFiles() {
        return List.of(
            new SettingsTransfer.ConfigFile(SqlTemplates.CONFIG_FILE, true, SqlTemplates::reload),
            new SettingsTransfer.ConfigFile(RegistryConstants.CONNECTION_TYPES_FILE_NAME, false,
                () -> WorkspaceConfigEventManager.fireConfigChangedEvent(RegistryConstants.CONNECTION_TYPES_FILE_NAME)),
            new SettingsTransfer.ConfigFile(DataFormatterRegistry.CONFIG_FILE_NAME, true,
                () -> WorkspaceConfigEventManager.fireConfigChangedEvent(DataFormatterRegistry.CONFIG_FILE_NAME))
        );
    }

    //////////////////////////////////////////////////////////
    // Export

    /**
     * What an export wrote.
     */
    private static final class ExportCounts {
        int preferences;
        int configFiles;
        int drivers;
        int queries;
        int scripts;
        int connections = -1;
        boolean encrypted;
        int secrets;
    }

    /**
     * Export Settings: the parts, the connections (when chosen), the target file, then the export.
     */
    static void exportSettings(@NotNull MainFrame frame) {
        Set<Section> sections = SettingsExportDialog.open(frame);
        if (sections == null) {
            return;
        }
        DBPDataSourceRegistry registry = null;
        List<DBPDataSourceContainer> connections = List.of();
        List<DBPDataSourceFolder> folders = List.of();
        char[] passphrase = null;
        if (sections.contains(Section.CONNECTIONS)) {
            DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
            if (project == null) {
                JOptionPane.showMessageDialog(frame, CoreMessages.common_no_active_project,
                    SettingsTransferMessages.settings_export_title, JOptionPane.WARNING_MESSAGE);
                return;
            }
            registry = project.getDataSourceRegistry();
            ConnectionExportDialog dialog = ConnectionExportDialog.open(frame, registry, null);
            if (dialog == null) {
                return;
            }
            connections = dialog.getConnections();
            folders = dialog.getFolders();
            passphrase = dialog.getPassphrase();
            dialog.clear();
        }
        try {
            Path file = chooseSaveFile(frame);
            if (file == null) {
                return;
            }
            DBPDataSourceRegistry exportRegistry = registry;
            List<DBPDataSourceContainer> exportConnections = connections;
            List<DBPDataSourceFolder> exportFolders = folders;
            char[] exportPassphrase = passphrase;
            ExportCounts counts = ProgressDialog.runTask(SettingsTransferMessages.settings_export_progress, monitor -> {
                Archive archive = new Archive(sections);
                ExportCounts result = new ExportCounts();
                int[] secrets = new int[1];
                if (sections.contains(Section.PREFERENCES)) {
                    result.preferences = SettingsTransfer.exportPreferences(archive, SettingsTransferCommands::isExported, secrets);
                    result.configFiles = SettingsTransfer.exportConfigFiles(archive, getConfigFiles());
                }
                if (sections.contains(Section.DRIVERS)) {
                    result.drivers = SettingsTransfer.exportDrivers(archive, secrets);
                }
                if (sections.contains(Section.QUERY_LIBRARY)) {
                    result.queries = exportLibrary(archive);
                }
                if (sections.contains(Section.SCRIPTS)) {
                    result.scripts = exportScripts(archive);
                }
                if (exportRegistry != null) {
                    int[] count = new int[1];
                    archive.put(SettingsTransfer.ENTRY_CONNECTIONS, ConnectionTransfer.exportConnectionsData(
                        monitor, exportRegistry, exportConnections, exportFolders, exportPassphrase, count));
                    result.connections = count[0];
                    result.encrypted = exportPassphrase != null;
                }
                result.secrets = secrets[0];
                archive.write(file);
                return result;
            });
            showExportResult(frame, file, sections, counts);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(SettingsTransferMessages.settings_export_title, SettingsTransferMessages.settings_export_failed, e);
        } finally {
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
        }
    }

    /**
     * The queries of the personal root (My Queries), with their relative paths.
     */
    private static int exportLibrary(@NotNull Archive archive) throws DBException {
        Path root = QueryLibrary.getDefaultRoot().path();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try {
            List<Path> files = new ArrayList<>();
            QueryLibraryArchive.collect(root, files);
            for (Path file : files) {
                archive.put(SettingsTransfer.QUERY_LIBRARY_FOLDER + toEntryPath(root, file), Files.readAllBytes(file));
            }
            return files.size();
        } catch (IOException e) {
            throw new DBException("Cannot read the Query Library " + root, e);
        }
    }

    /**
     * The scripts of the active project's script folder, and the connections they are linked to (by connection ID).
     */
    private static int exportScripts(@NotNull Archive archive) throws DBException {
        ScriptFiles.Root root = ScriptFiles.getActiveRoot();
        if (root == null || !Files.isDirectory(root.folder())) {
            return 0;
        }
        try {
            List<Path> files = new ArrayList<>();
            collectScripts(root.folder(), files);
            JsonObject links = new JsonObject();
            for (Path file : files) {
                String path = toEntryPath(root.folder(), file);
                archive.put(SettingsTransfer.SCRIPTS_FOLDER + path, Files.readAllBytes(file));
                String dataSourceId = ScriptFiles.getLink(file).dataSourceId();
                if (dataSourceId != null) {
                    links.addProperty(path, dataSourceId);
                }
            }
            archive.put(SettingsTransfer.ENTRY_SCRIPT_LINKS, links.toString().getBytes(StandardCharsets.UTF_8));
            return files.size();
        } catch (IOException e) {
            throw new DBException("Cannot read the scripts " + root.folder(), e);
        }
    }

    /**
     * Scripts like the Scripts window shows them: script files, without names that start with a dot.
     */
    private static void collectScripts(@NotNull Path folder, @NotNull List<Path> result) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path child : stream) {
                if (String.valueOf(child.getFileName()).startsWith(".")) { //$NON-NLS-1$
                    continue;
                }
                if (Files.isDirectory(child)) {
                    collectScripts(child, result);
                } else if (ScriptFiles.isScript(child)) {
                    result.add(child);
                }
            }
        }
    }

    @NotNull
    private static String toEntryPath(@NotNull Path root, @NotNull Path file) {
        StringBuilder name = new StringBuilder();
        for (Path part : root.relativize(file)) {
            name.append(name.isEmpty() ? "" : "/").append(part); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return name.toString();
    }

    private static void showExportResult(@NotNull Component parent, @NotNull Path file, @NotNull Set<Section> sections, @NotNull ExportCounts counts) {
        StringBuilder message = new StringBuilder(NLS.bind(SettingsTransferMessages.settings_export_done, file));
        if (sections.contains(Section.PREFERENCES)) {
            message.append("\n").append(NLS.bind(SettingsTransferMessages.settings_export_count_preferences, counts.preferences, counts.configFiles)); //$NON-NLS-1$
        }
        if (sections.contains(Section.DRIVERS)) {
            message.append("\n").append(NLS.bind(SettingsTransferMessages.settings_export_count_drivers, counts.drivers)); //$NON-NLS-1$
        }
        if (sections.contains(Section.QUERY_LIBRARY)) {
            message.append("\n").append(NLS.bind(SettingsTransferMessages.settings_export_count_query_library, counts.queries)); //$NON-NLS-1$
        }
        if (sections.contains(Section.SCRIPTS)) {
            message.append("\n").append(NLS.bind(SettingsTransferMessages.settings_export_count_scripts, counts.scripts)); //$NON-NLS-1$
        }
        if (counts.connections >= 0) {
            message.append("\n").append(NLS.bind(counts.encrypted //$NON-NLS-1$
                ? SettingsTransferMessages.settings_export_count_connections_encrypted
                : SettingsTransferMessages.settings_export_count_connections_plain, counts.connections));
        }
        if (counts.secrets > 0) {
            message.append("\n\n").append(NLS.bind(SettingsTransferMessages.settings_export_secrets_skipped, counts.secrets)); //$NON-NLS-1$
        }
        JOptionPane.showMessageDialog(getWindow(parent), message.toString(), SettingsTransferMessages.settings_export_title,
            JOptionPane.INFORMATION_MESSAGE);
    }

    //////////////////////////////////////////////////////////
    // Import

    /**
     * A script of the file and where it would be written.
     */
    private record ScriptTarget(@NotNull String path, @NotNull byte[] bytes, @NotNull Path file) {
    }

    /**
     * A script to write: the file it goes to (another name for "keep both").
     */
    private record ScriptWrite(@NotNull ScriptTarget target, @NotNull Path file) {
    }

    /**
     * The changes of a file and what the import needs besides them.
     */
    private static final class Preview {
        final List<Change> changes = new ArrayList<>();
        boolean scriptsSkipped;
        final List<ScriptWrite> scriptWrites = new ArrayList<>();
    }

    /**
     * Import Settings: the file, the preview, the backup, the import, the connections, then what it did.
     *
     * @param scriptsChanged reloads the Scripts window
     */
    static void importSettings(@NotNull MainFrame frame, @NotNull Runnable scriptsChanged) {
        Path file = chooseOpenFile(frame);
        if (file == null) {
            return;
        }
        String fileName = String.valueOf(file.getFileName());
        Archive archive;
        try {
            archive = ProgressDialog.runTask(SettingsTransferMessages.settings_import_progress, monitor -> Archive.read(file));
        } catch (DBException e) {
            showReadProblem(frame, e);
            return;
        }
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        // The query windows are read on the EDT; the comparison runs in the background
        Set<Path> openFiles = new HashSet<>();
        for (SqlEditorPanel editor : frame.getQueryWindows()) {
            if (editor.getFile() != null) {
                openFiles.add(editor.getFile().toAbsolutePath().normalize());
            }
        }
        Preview preview;
        try {
            preview = ProgressDialog.runTask(SettingsTransferMessages.settings_import_preview_progress,
                monitor -> buildPreview(archive, openFiles));
        } catch (DBException e) {
            showReadProblem(frame, e);
            return;
        }
        byte[] connectionsData = archive.get(SettingsTransfer.ENTRY_CONNECTIONS);
        boolean hasConnections = connectionsData != null && project != null;
        if (preview.changes.isEmpty() && !hasConnections) {
            JOptionPane.showMessageDialog(frame, NLS.bind(SettingsTransferMessages.settings_import_nothing, fileName),
                SettingsTransferMessages.settings_import_title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        SettingsImportDialog.Result choice = SettingsImportDialog.open(frame, fileName, formatTime(archive.getCreated()),
            preview.changes, hasConnections, preview.scriptsSkipped);
        if (choice == null) {
            return;
        }

        // The current values first: nothing is changed unless the backup is saved
        SettingsBackup backup = SettingsBackup.create(fileName);
        try {
            SettingsTransfer.backupPreferences(preview.changes, backup);
            SettingsTransfer.backupConfigFiles(preview.changes, backup);
            SettingsTransfer.backupDrivers(preview.changes, backup);
            backupLibrary(preview, backup);
            planScripts(preview, backup);
            backup.save();
        } catch (DBException | IOException e) {
            DBWorkbench.getPlatformUI().showError(SettingsTransferMessages.settings_import_title, SettingsTransferMessages.settings_import_backup_failed, e);
            return;
        }

        List<String> errors = new ArrayList<>();
        int preferences = 0;
        int drivers = 0;
        int queries = 0;
        int scripts = 0;
        try {
            preferences = SettingsTransfer.applyPreferences(preview.changes);
            preferences += SettingsTransfer.applyConfigFiles(preview.changes);
        } catch (DBException e) {
            log.error("Settings import: options", e);
            errors.add(SettingsTransferMessages.settings_short_preferences + ": " + e.getMessage()); //$NON-NLS-1$
        }
        try {
            drivers = SettingsTransfer.applyDrivers(preview.changes);
        } catch (DBException e) {
            log.error("Settings import: drivers", e);
            errors.add(SettingsTransferMessages.settings_short_drivers + ": " + e.getMessage()); //$NON-NLS-1$
        }
        queries = applyLibrary(preview, backup, errors);
        scripts = applyScripts(preview, backup, errors);
        saveBackup(backup);

        if (choice.importConnections() && connectionsData != null && project != null) {
            importConnections(frame, project.getDataSourceRegistry(), connectionsData, fileName, backup);
        }
        // After the connections: a script is linked only to a connection that is here
        applyScriptLinks(archive, preview, backup);

        QueryLibrary.getInstance().fireChanged();
        scriptsChanged.run();

        StringBuilder message = new StringBuilder(NLS.bind(SettingsTransferMessages.settings_import_done,
            new Object[]{preferences, drivers, queries, scripts}));
        message.append("\n\n").append(SettingsTransferMessages.settings_import_done_restart); //$NON-NLS-1$
        message.append("\n").append(SettingsTransferMessages.settings_import_done_undo); //$NON-NLS-1$
        if (!errors.isEmpty()) {
            message.append("\n\n").append(NLS.bind(SettingsTransferMessages.settings_import_errors, String.join("\n", errors))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        JOptionPane.showMessageDialog(frame, message.toString(), SettingsTransferMessages.settings_import_title,
            errors.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }

    @NotNull
    private static Preview buildPreview(@NotNull Archive archive, @NotNull Set<Path> openFiles) throws DBException {
        Preview preview = new Preview();
        preview.changes.addAll(SettingsTransfer.previewPreferences(archive, SettingsTransferCommands::isExported));
        preview.changes.addAll(SettingsTransfer.previewConfigFiles(archive, getConfigFiles()));
        preview.changes.addAll(SettingsTransfer.previewDrivers(archive));
        try {
            preview.changes.addAll(previewLibrary(archive));
            SortedMap<String, byte[]> scripts = archive.getFolder(SettingsTransfer.SCRIPTS_FOLDER);
            if (!scripts.isEmpty()) {
                ScriptFiles.Root root = ScriptFiles.getActiveRoot();
                if (root == null) {
                    preview.scriptsSkipped = true;
                } else {
                    preview.changes.addAll(previewScripts(scripts, root, openFiles));
                }
            }
        } catch (IOException e) {
            throw new DBException("Cannot compare the files of the settings file", e);
        }
        return preview;
    }

    /**
     * The queries of the file that differ from the library. A query whose ID is in the library already (in any root), or a
     * file at the same place in My Queries, is a conflict; "keep both" imports it as a copy with a new ID
     * ({@link QueryLibraryArchive#write}), so that one library never has the same ID twice.
     */
    @NotNull
    private static List<Change> previewLibrary(@NotNull Archive archive) throws IOException, DBException {
        SortedMap<String, byte[]> files = archive.getFolder(SettingsTransfer.QUERY_LIBRARY_FOLDER);
        if (files.isEmpty()) {
            return List.of();
        }
        QueryLibrary library = QueryLibrary.getInstance();
        // Loading the roots builds the ID index that finds an existing query
        for (QueryLibrary.Root root : library.getRoots()) {
            try {
                library.load(root);
            } catch (IOException e) {
                log.warn("Can't read the Query Library root " + root.path(), e);
            }
        }
        Path target = QueryLibrary.getDefaultRoot().path();
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            String path = QueryLibraryArchive.checkPath(file.getKey());
            if (path == null) {
                continue;
            }
            byte[] bytes = file.getValue();
            QueryLibraryItem item = QueryLibraryFile.parse(SqlFileContent.parse(bytes).text(), Path.of(path.substring(path.lastIndexOf('/') + 1)));
            item.setFile(null);
            Path samePlace = SettingsTransfer.resolveInside(target, path);
            Path existing = item.getId() != null ? library.findFile(item.getId()) : null;
            if (existing == null && Files.isRegularFile(samePlace)) {
                existing = samePlace;
            }
            if (existing != null && Arrays.equals(Files.readAllBytes(existing), bytes)) {
                continue;
            }
            Blocker blocker = existing != null && library.isReadOnly(existing) ? Blocker.READ_ONLY : Blocker.NONE;
            String current = existing != null && !existing.equals(samePlace) ? existing.toString() : null;
            changes.add(new Change(Section.QUERY_LIBRARY, path, existing == null ? Kind.NEW : Kind.CONFLICT, existing != null,
                current, null, true, blocker, new QueryLibraryArchive.Entry(path, bytes, item, existing)));
        }
        return changes;
    }

    private static void backupLibrary(@NotNull Preview preview, @NotNull SettingsBackup backup) throws IOException {
        for (Change change : preview.changes) {
            if (change.getAction() == Action.REPLACE && change.getTarget() instanceof QueryLibraryArchive.Entry entry && entry.existing() != null) {
                backup.recordFile(entry.existing(), Files.readAllBytes(entry.existing()));
            }
        }
    }

    /**
     * Writes the chosen queries into My Queries with the Query Library import rules; records what was written for the undo.
     */
    private static int applyLibrary(@NotNull Preview preview, @NotNull SettingsBackup backup, @NotNull List<String> errors) {
        List<QueryLibraryArchive.Entry> entries = new ArrayList<>();
        List<QueryLibraryArchive.Choice> choices = new ArrayList<>();
        for (Change change : preview.changes) {
            if (change.isApplied() && change.getTarget() instanceof QueryLibraryArchive.Entry entry) {
                entries.add(entry);
                choices.add(entry.existing() == null ? null : switch (change.getAction()) {
                    case REPLACE -> QueryLibraryArchive.Choice.OVERWRITE;
                    case KEEP_BOTH -> QueryLibraryArchive.Choice.COPY;
                    default -> QueryLibraryArchive.Choice.SKIP;
                });
            }
        }
        if (entries.isEmpty()) {
            return 0;
        }
        QueryLibrary library = QueryLibrary.getInstance();
        Path folder = QueryLibrary.getDefaultRoot().path();
        try {
            Files.createDirectories(folder);
            Set<Path> before = listLibraryFiles(folder);
            QueryLibraryArchive.Summary summary = QueryLibraryArchive.write(library, folder, entries, choices);
            errors.addAll(summary.errors().stream().map(error -> SettingsTransferMessages.settings_short_query_library + ": " + error).toList()); //$NON-NLS-1$
            // New queries and copies are the files that were not there before; replaced ones are recorded with what was written
            for (Path created : listLibraryFiles(folder)) {
                if (!before.contains(created)) {
                    backup.recordWrittenFile(created, Files.readAllBytes(created));
                }
            }
            for (int i = 0; i < entries.size(); i++) {
                QueryLibraryArchive.Entry entry = entries.get(i);
                if (choices.get(i) == QueryLibraryArchive.Choice.OVERWRITE && entry.existing() != null
                    && Arrays.equals(Files.readAllBytes(entry.existing()), entry.bytes())) {
                    backup.recordWrittenFile(entry.existing(), entry.bytes());
                }
            }
            return summary.added() + summary.overwritten() + summary.copied();
        } catch (IOException e) {
            log.error("Settings import: Query Library", e);
            errors.add(SettingsTransferMessages.settings_short_query_library + ": " + e.getMessage()); //$NON-NLS-1$
            return 0;
        }
    }

    @NotNull
    private static Set<Path> listLibraryFiles(@NotNull Path folder) throws IOException {
        List<Path> files = new ArrayList<>();
        QueryLibraryArchive.collect(folder, files);
        Set<Path> result = new HashSet<>();
        for (Path file : files) {
            result.add(file.toAbsolutePath().normalize());
        }
        return result;
    }

    /**
     * The scripts of the file that differ from the script folder. A script open in a query window cannot be replaced
     * (the window would keep other text and save it over the import).
     */
    @NotNull
    private static List<Change> previewScripts(
        @NotNull SortedMap<String, byte[]> scripts,
        @NotNull ScriptFiles.Root root,
        @NotNull Set<Path> openFiles
    ) throws IOException, DBException {
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, byte[]> script : scripts.entrySet()) {
            Path file = SettingsTransfer.resolveInside(root.folder(), script.getKey());
            if (!ScriptFiles.isScript(file) || script.getKey().startsWith(".") || script.getKey().contains("/.")) { //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            byte[] bytes = script.getValue();
            boolean exists = Files.isRegularFile(file);
            if (exists && Arrays.equals(Files.readAllBytes(file), bytes)) {
                continue;
            }
            Blocker blocker = exists && openFiles.contains(file) ? Blocker.OPEN : Blocker.NONE;
            changes.add(new Change(Section.SCRIPTS, script.getKey(), exists ? Kind.CONFLICT : Kind.NEW, exists, null, null, true,
                blocker, new ScriptTarget(script.getKey(), bytes, file)));
        }
        return changes;
    }

    /**
     * Decides where each chosen script goes ("keep both": a free name like "a (2).sql") and records what it replaces.
     */
    private static void planScripts(@NotNull Preview preview, @NotNull SettingsBackup backup) throws IOException {
        Set<Path> reserved = new HashSet<>();
        for (Change change : preview.changes) {
            if (!change.isApplied() || !(change.getTarget() instanceof ScriptTarget target)) {
                continue;
            }
            Path file = target.file();
            byte[] old = null;
            if (change.getAction() == Action.KEEP_BOTH) {
                file = getFreeName(file, reserved);
            } else if (Files.isRegularFile(file)) {
                old = Files.readAllBytes(file);
            }
            reserved.add(file);
            backup.recordFile(file, old);
            preview.scriptWrites.add(new ScriptWrite(target, file));
        }
    }

    @NotNull
    private static Path getFreeName(@NotNull Path file, @NotNull Set<Path> reserved) {
        String name = String.valueOf(file.getFileName());
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : ""; //$NON-NLS-1$
        for (int i = 2; ; i++) {
            Path candidate = file.resolveSibling(stem + " (" + i + ")" + extension); //$NON-NLS-1$ //$NON-NLS-2$
            if (!Files.exists(candidate) && !reserved.contains(candidate)) {
                return candidate;
            }
        }
    }

    private static int applyScripts(@NotNull Preview preview, @NotNull SettingsBackup backup, @NotNull List<String> errors) {
        int count = 0;
        for (ScriptWrite write : preview.scriptWrites) {
            try {
                Files.createDirectories(write.file().getParent());
                Files.write(write.file(), write.target().bytes());
                backup.recordWrittenFile(write.file(), write.target().bytes());
                count++;
            } catch (IOException e) {
                log.error("Settings import: script " + write.file(), e);
                errors.add(SettingsTransferMessages.settings_short_scripts + ": " + write.target().path() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return count;
    }

    /**
     * Links the imported scripts to their connections, when that connection is here and the script is not linked to it already.
     */
    private static void applyScriptLinks(@NotNull Archive archive, @NotNull Preview preview, @NotNull SettingsBackup backup) {
        byte[] data = archive.get(SettingsTransfer.ENTRY_SCRIPT_LINKS);
        if (data == null || preview.scriptWrites.isEmpty()) {
            return;
        }
        JsonObject links;
        try {
            links = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            log.warn("Settings import: the script links cannot be read", e);
            return;
        }
        record Link(Path file, DBPProject project, String resourcePath, DBPDataSourceContainer connection) {
        }
        List<Link> todo = new ArrayList<>();
        for (ScriptWrite write : preview.scriptWrites) {
            String dataSourceId = links.has(write.target().path()) && links.get(write.target().path()).isJsonPrimitive()
                ? links.get(write.target().path()).getAsString() : null;
            ScriptFiles.Root root = ScriptFiles.findRoot(write.file());
            if (dataSourceId == null || root == null || !Files.isRegularFile(write.file())
                || dataSourceId.equals(ScriptFiles.getLink(write.file()).dataSourceId())) {
                continue;
            }
            DBPDataSourceContainer connection = root.project().getDataSourceRegistry().getDataSource(dataSourceId);
            if (connection != null) {
                todo.add(new Link(write.file(), root.project(), ScriptFiles.getResourcePath(root.project(), write.file()), connection));
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        for (Link link : todo) {
            backup.recordResourceProperties(link.project(), link.resourcePath(), link.project().getResourceProperties(link.resourcePath()));
        }
        if (!saveBackup(backup)) {
            return;
        }
        for (Link link : todo) {
            ScriptFiles.setLink(link.file(), link.connection());
        }
    }

    /**
     * The connections of the file through the connection import of #108 (passphrase, preview, conflicts); the connections it
     * will replace are backed up first.
     */
    private static void importConnections(
        @NotNull MainFrame frame,
        @NotNull DBPDataSourceRegistry registry,
        @NotNull byte[] data,
        @NotNull String fileName,
        @NotNull SettingsBackup backup
    ) {
        ConnectionTransfer.ImportFile importFile = ConnectionTransferCommands.readData(frame, registry, data);
        if (importFile == null) {
            return;
        }
        if (importFile.getEntries().isEmpty()) {
            JOptionPane.showMessageDialog(frame, ConnectionTransferMessages.transfer_import_empty,
                ConnectionTransferMessages.transfer_import_title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Map<String, ConnectionTransfer.ConflictAction> actions = ConnectionImportDialog.open(frame, fileName, importFile);
        if (actions == null) {
            return;
        }
        List<DBPDataSourceContainer> replaced = new ArrayList<>();
        for (ConnectionTransfer.ImportEntry entry : importFile.getEntries()) {
            if (entry.getConflict() != ConnectionTransfer.ConflictKind.NONE && entry.getExisting() != null
                && actions.get(entry.getId()) == ConnectionTransfer.ConflictAction.REPLACE && !entry.isExistingConnected()) {
                replaced.add(entry.getExisting());
            }
        }
        try {
            ProgressDialog.runTask(SettingsTransferMessages.settings_import_backup_progress, monitor -> {
                backup.recordConnections(monitor, registry, replaced);
                return null;
            });
            backup.save();
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ConnectionTransferMessages.transfer_import_title, SettingsTransferMessages.settings_import_backup_failed, e);
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
        backup.recordImportedConnections(result);
        saveBackup(backup);
        ConnectionTransferCommands.showImportResult(frame, registry, result);
    }

    private static boolean saveBackup(@NotNull SettingsBackup backup) {
        try {
            backup.save();
            return true;
        } catch (DBException e) {
            log.error("Can't save the settings backup", e);
            return false;
        }
    }

    //////////////////////////////////////////////////////////
    // Undo

    /**
     * Undo Settings Import: the newest import not undone yet, a confirmation (Cancel is the default), then the undo.
     *
     * @param scriptsChanged reloads the Scripts window
     */
    static void undoImport(@NotNull MainFrame frame, @NotNull Runnable scriptsChanged) {
        SettingsBackup backup;
        try {
            backup = SettingsBackup.findLatest();
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(SettingsTransferMessages.settings_undo_title, SettingsTransferMessages.settings_undo_failed, e);
            return;
        }
        if (backup == null) {
            JOptionPane.showMessageDialog(frame, SettingsTransferMessages.settings_undo_none,
                SettingsTransferMessages.settings_undo_title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!SwingPlatformUI.confirmChange(frame,
            NLS.bind(SettingsTransferMessages.settings_undo_confirm, backup.getSource(), formatTime(backup.getCreated())),
            SettingsTransferMessages.settings_undo_title, SettingsTransferMessages.settings_undo_button, JOptionPane.WARNING_MESSAGE)) {
            return;
        }
        SettingsBackup.UndoResult result;
        try {
            // Decrypting the replaced connections takes about a second; the undo itself changes the registry on the EDT
            ConnectionTransfer.ImportFile connections = ProgressDialog.runTask(SettingsTransferMessages.settings_undo_progress,
                monitor -> backup.readReplacedConnections());
            result = backup.undo(new VoidProgressMonitor(), getConfigFiles(), connections);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(SettingsTransferMessages.settings_undo_title, SettingsTransferMessages.settings_undo_failed, e);
            return;
        } finally {
            DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
            if (project != null) {
                RegisteredServerCommands.updateConfig(project.getDataSourceRegistry());
            }
            QueryLibrary.getInstance().fireChanged();
            scriptsChanged.run();
        }
        StringBuilder message = new StringBuilder(NLS.bind(SettingsTransferMessages.settings_undo_done, result.getRestored()));
        appendList(message, SettingsTransferMessages.settings_undo_kept_changed, result.getKeptChanged());
        appendList(message, SettingsTransferMessages.settings_undo_kept_connected, result.getKeptConnected());
        appendList(message, SettingsTransferMessages.settings_undo_kept_drivers, result.getKeptDriversInUse());
        message.append("\n\n").append(SettingsTransferMessages.settings_import_done_restart); //$NON-NLS-1$
        JOptionPane.showMessageDialog(frame, message.toString(), SettingsTransferMessages.settings_undo_title,
            result.getKeptChanged().isEmpty() && result.getKeptConnected().isEmpty() && result.getKeptDriversInUse().isEmpty()
                ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }

    //////////////////////////////////////////////////////////
    // Helpers

    private static void showReadProblem(@NotNull Component parent, @NotNull DBException error) {
        if (error instanceof SettingsTransferException transferError) {
            String message = switch (transferError.getReason()) {
                case NOT_A_SETTINGS_FILE -> SettingsTransferMessages.settings_import_not_a_file;
                case UNSUPPORTED_FORMAT -> SettingsTransferMessages.settings_import_unsupported;
                case INVALID_CONTENT -> SettingsTransferMessages.settings_import_invalid;
            };
            log.warn("Settings file not read: " + error.getMessage());
            JOptionPane.showMessageDialog(getWindow(parent), message, SettingsTransferMessages.settings_import_title, JOptionPane.ERROR_MESSAGE);
        } else {
            DBWorkbench.getPlatformUI().showError(SettingsTransferMessages.settings_import_title, SettingsTransferMessages.settings_import_failed, error);
        }
    }

    /**
     * An ISO-8601 time in the local time zone and format, or the text as it is.
     */
    @NotNull
    private static String formatTime(@Nullable String instant) {
        if (CommonUtils.isEmpty(instant)) {
            return ""; //$NON-NLS-1$
        }
        try {
            return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.parse(instant));
        } catch (DateTimeParseException e) {
            return instant;
        }
    }

    private static void appendList(@NotNull StringBuilder message, @NotNull String pattern, @NotNull List<String> names) {
        if (!names.isEmpty()) {
            message.append("\n\n").append(NLS.bind(pattern, String.join(", ", names))); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Nullable
    private static Path chooseSaveFile(@NotNull Component parent) {
        JFileChooser chooser = createChooser(SettingsTransferMessages.settings_export_save_title);
        while (chooser.showSaveDialog(getWindow(parent)) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            String extension = "." + SettingsTransfer.FILE_EXTENSION; //$NON-NLS-1$
            if (!selected.getName().toLowerCase(Locale.ROOT).endsWith(extension)) {
                selected = new File(selected.getParentFile(), selected.getName() + extension);
            }
            Path file = selected.toPath();
            if (!Files.exists(file) || SwingPlatformUI.confirmChange(getWindow(parent),
                NLS.bind(SettingsTransferMessages.settings_export_overwrite, file.getFileName()),
                SettingsTransferMessages.settings_export_title, CoreMessages.common_replace, JOptionPane.WARNING_MESSAGE)) {
                return file;
            }
        }
        return null;
    }

    @Nullable
    private static Path chooseOpenFile(@NotNull Component parent) {
        JFileChooser chooser = createChooser(SettingsTransferMessages.settings_import_open_title);
        return chooser.showOpenDialog(getWindow(parent)) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
    }

    @NotNull
    private static JFileChooser createChooser(@NotNull String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        FileNameExtensionFilter filter = new FileNameExtensionFilter(SettingsTransferMessages.settings_file_filter, SettingsTransfer.FILE_EXTENSION);
        chooser.addChoosableFileFilter(filter);
        chooser.setFileFilter(filter);
        return chooser;
    }

    @Nullable
    private static Window getWindow(@NotNull Component component) {
        return component instanceof Window window ? window : SwingUtilities.getWindowAncestor(component);
    }
}
