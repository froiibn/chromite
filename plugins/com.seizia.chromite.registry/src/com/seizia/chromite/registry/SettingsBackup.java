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
package com.seizia.chromite.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.impl.app.BaseProjectImpl;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.secret.DBSSecretController;
import com.seizia.chromite.registry.driver.DriverDescriptor;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;
import org.osgi.service.prefs.Preferences;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * What a settings import changed, saved before the change so that the import can be undone (File &gt; Undo Settings Import).
 *
 * 설계:
 * <ul>
 *     <li>가져오기마다 작업 공간 메타데이터 폴더의 {@code chromite-settings-backups/<시각>.chromite-settings-backup}(zip) 하나다.
 *     {@code backup.json}에 바꾼 항목마다 이전 값(없었으면 없음)과 가져와 쓴 값(또는 그 SHA-256)을 적고, 큰 값(파일 내용, 드라이버
 *     요소, 연결)은 따로 항목으로 둔다. 바꾸기 전에 저장하고, 가져오기가 새로 만든 것(파일, 연결)을 안 뒤 다시 저장한다.</li>
 *     <li>되돌리기는 가져온 뒤 사용자가 다시 바꾼 것을 덮어쓰지 않는다: 설정 값·설정 파일·파일은 지금 값이 가져와 쓴 값과 같을 때만 되돌리고,
 *     아니면 그대로 두고 알린다. 연결 중인 연결은 지우거나 바꾸지 않는다(세션과 트랜잭션을 건드리지 않음). 연결이 쓰는 드라이버는 지우지 않는다.</li>
 *     <li>가져오기가 바꾼 연결은 #108 연결 파일 형식으로 비밀번호까지 담아 두되, 임의로 만든 암호로 암호화하고 그 암호는 OS 보안 저장소
 *     ({@link DBSSecretController#getGlobalSecretController()})에만 둔다. 그래서 백업 파일에 평문 비밀이 없고, 되돌리면 비밀번호도 돌아온다.
 *     되돌리거나 오래된 백업을 지우면 그 암호도 지운다.</li>
 *     <li>되돌린 백업에는 표시를 남긴다. 다음 되돌리기는 아직 되돌리지 않은 것 중 가장 새 것이다(가져오기 순서의 반대). 백업은 최근
 *     {@value #KEEP}개만 둔다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/settings-transfer.md)
 */
public final class SettingsBackup {

    private static final Log log = Log.getLog(SettingsBackup.class);

    public static final String FILE_EXTENSION = "chromite-settings-backup"; //$NON-NLS-1$
    private static final String FOLDER = "chromite-settings-backups"; //$NON-NLS-1$
    private static final String FORMAT = "chromite-settings-backup"; //$NON-NLS-1$
    private static final int VERSION = 1;
    private static final int KEEP = 10;
    private static final String ENTRY_MANIFEST = "backup.json"; //$NON-NLS-1$
    private static final String BLOB_FOLDER = "data/"; //$NON-NLS-1$
    private static final String SECRET_PREFIX = "chromite/settings-backup/"; //$NON-NLS-1$
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"); //$NON-NLS-1$

    private static final String KEY_FORMAT = "format"; //$NON-NLS-1$
    private static final String KEY_VERSION = "version"; //$NON-NLS-1$
    private static final String KEY_CREATED = "created"; //$NON-NLS-1$
    private static final String KEY_SOURCE = "source"; //$NON-NLS-1$
    private static final String KEY_UNDONE = "undone"; //$NON-NLS-1$
    private static final String KEY_PREFERENCES = "preferences"; //$NON-NLS-1$
    private static final String KEY_CONFIGS = "configs"; //$NON-NLS-1$
    private static final String KEY_DRIVERS = "drivers"; //$NON-NLS-1$
    private static final String KEY_FILES = "files"; //$NON-NLS-1$
    private static final String KEY_RESOURCES = "resources"; //$NON-NLS-1$
    private static final String KEY_CONNECTIONS = "connections"; //$NON-NLS-1$
    private static final String KEY_NODE = "node"; //$NON-NLS-1$
    private static final String KEY_KEY = "key"; //$NON-NLS-1$
    private static final String KEY_OLD = "old"; //$NON-NLS-1$
    private static final String KEY_NEW = "new"; //$NON-NLS-1$
    private static final String KEY_NAME = "name"; //$NON-NLS-1$
    private static final String KEY_PRODUCT = "product"; //$NON-NLS-1$
    private static final String KEY_PROVIDER = "provider"; //$NON-NLS-1$
    private static final String KEY_DRIVER = "driver"; //$NON-NLS-1$
    private static final String KEY_STATE = "state"; //$NON-NLS-1$
    private static final String KEY_PATH = "path"; //$NON-NLS-1$
    private static final String KEY_WRITTEN = "written"; //$NON-NLS-1$
    private static final String KEY_PROJECT = "project"; //$NON-NLS-1$
    private static final String KEY_PROPERTIES = "properties"; //$NON-NLS-1$
    private static final String KEY_SECRET = "secret"; //$NON-NLS-1$
    private static final String KEY_IMPORTED = "imported"; //$NON-NLS-1$
    private static final String KEY_REPLACED = "replaced"; //$NON-NLS-1$

    /**
     * What an undo did, and what it left as it was.
     */
    public static final class UndoResult {
        private int restored;
        private final List<String> keptChanged = new ArrayList<>();
        private final List<String> keptConnected = new ArrayList<>();
        private final List<String> keptDriversInUse = new ArrayList<>();

        /**
         * Number of items put back (preferences, files, drivers, connections).
         */
        public int getRestored() {
            return restored;
        }

        /**
         * Items changed again after the import; they were left as they are.
         */
        @NotNull
        public List<String> getKeptChanged() {
            return keptChanged;
        }

        /**
         * Connections that are connected; they were neither removed nor replaced.
         */
        @NotNull
        public List<String> getKeptConnected() {
            return keptConnected;
        }

        /**
         * Drivers the import added that connections use; they were not removed.
         */
        @NotNull
        public List<String> getKeptDriversInUse() {
            return keptDriversInUse;
        }
    }

    @NotNull
    private final Path file;
    @NotNull
    private final JsonObject manifest;
    private final Map<String, byte[]> blobs = new LinkedHashMap<>();
    /**
     * File records by path, to complete them after the write.
     */
    private final Map<Path, JsonObject> fileRecords = new HashMap<>();

    private SettingsBackup(@NotNull Path file, @NotNull JsonObject manifest) {
        this.file = file;
        this.manifest = manifest;
    }

    /**
     * Starts the backup of an import (nothing is written until {@link #save()}); old backups beyond the last {@value #KEEP} are removed.
     *
     * @param source the name of the imported file
     */
    @NotNull
    public static SettingsBackup create(@NotNull String source) {
        Path folder = getFolder();
        pruneOldBackups(folder);
        JsonObject manifest = new JsonObject();
        manifest.addProperty(KEY_FORMAT, FORMAT);
        manifest.addProperty(KEY_VERSION, VERSION);
        manifest.addProperty(KEY_CREATED, Instant.now().toString());
        manifest.addProperty(KEY_SOURCE, source);
        manifest.addProperty(KEY_UNDONE, false);
        for (String key : new String[]{KEY_PREFERENCES, KEY_CONFIGS, KEY_DRIVERS, KEY_FILES, KEY_RESOURCES}) {
            manifest.add(key, new JsonArray());
        }
        return new SettingsBackup(folder.resolve(LocalDateTime.now().format(FILE_TIME) + "." + FILE_EXTENSION), manifest); //$NON-NLS-1$
    }

    /**
     * The newest backup that was not undone yet, or null.
     */
    @Nullable
    public static SettingsBackup findLatest() throws DBException {
        for (Path backupFile : listBackups(getFolder())) {
            SettingsBackup backup = read(backupFile);
            if (backup != null && !isTrue(backup.manifest, KEY_UNDONE)) {
                return backup;
            }
        }
        return null;
    }

    /**
     * When the import was done (ISO-8601).
     */
    @NotNull
    public String getCreated() {
        return CommonUtils.notEmpty(SettingsTransfer.getString(manifest, KEY_CREATED));
    }

    /**
     * The name of the imported file.
     */
    @NotNull
    public String getSource() {
        return CommonUtils.notEmpty(SettingsTransfer.getString(manifest, KEY_SOURCE));
    }

    @NotNull
    public Path getFile() {
        return file;
    }

    //////////////////////////////////////////////////////////
    // Recording (before the change)

    void recordPreference(@NotNull String node, @NotNull String key, @Nullable String oldValue, @NotNull String newValue) {
        JsonObject record = new JsonObject();
        record.addProperty(KEY_NODE, node);
        record.addProperty(KEY_KEY, key);
        record.addProperty(KEY_OLD, oldValue);
        record.addProperty(KEY_NEW, newValue);
        manifest.getAsJsonArray(KEY_PREFERENCES).add(record);
    }

    void recordConfig(@NotNull SettingsTransfer.ConfigFile config, @Nullable String oldContent, @NotNull String newContent) {
        JsonObject record = new JsonObject();
        record.addProperty(KEY_NAME, config.name());
        record.addProperty(KEY_PRODUCT, config.product());
        record.addProperty(KEY_OLD, oldContent == null ? null : putBlob(oldContent.getBytes(StandardCharsets.UTF_8)));
        record.addProperty(KEY_WRITTEN, hash(newContent.getBytes(StandardCharsets.UTF_8)));
        manifest.getAsJsonArray(KEY_CONFIGS).add(record);
    }

    void recordDriver(@NotNull String providerId, @NotNull String driverId, @NotNull SettingsTransfer.DriverState state, @Nullable String oldXml) {
        JsonObject record = new JsonObject();
        record.addProperty(KEY_PROVIDER, providerId);
        record.addProperty(KEY_DRIVER, driverId);
        record.addProperty(KEY_STATE, state.name());
        record.addProperty(KEY_OLD, oldXml == null ? null : putBlob(oldXml.getBytes(StandardCharsets.UTF_8)));
        manifest.getAsJsonArray(KEY_DRIVERS).add(record);
    }

    /**
     * Before a file is written: its content (null when it does not exist yet).
     */
    public void recordFile(@NotNull Path path, @Nullable byte[] oldContent) {
        Path normalized = path.toAbsolutePath().normalize();
        JsonObject record = new JsonObject();
        record.addProperty(KEY_PATH, normalized.toString());
        record.addProperty(KEY_OLD, oldContent == null ? null : putBlob(oldContent));
        manifest.getAsJsonArray(KEY_FILES).add(record);
        fileRecords.put(normalized, record);
    }

    /**
     * After a file is written: what was written (a file not recorded before was created by the import).
     */
    public void recordWrittenFile(@NotNull Path path, @NotNull byte[] content) {
        Path normalized = path.toAbsolutePath().normalize();
        JsonObject record = fileRecords.get(normalized);
        if (record == null) {
            recordFile(normalized, null);
            record = fileRecords.get(normalized);
        }
        record.addProperty(KEY_WRITTEN, hash(content));
    }

    /**
     * Before the resource properties of a project path change (a script's connection link): the old ones, null for none.
     */
    public void recordResourceProperties(@NotNull DBPProject project, @NotNull String resourcePath, @Nullable Map<String, String> oldProperties) {
        JsonObject record = new JsonObject();
        record.addProperty(KEY_PROJECT, project.getId());
        record.addProperty(KEY_PATH, resourcePath);
        if (oldProperties != null) {
            JsonObject properties = new JsonObject();
            oldProperties.forEach(properties::addProperty);
            record.add(KEY_PROPERTIES, properties);
        }
        manifest.getAsJsonArray(KEY_RESOURCES).add(record);
    }

    /**
     * Before a connection import: the existing connections it will replace, encrypted with a passphrase kept in the secure storage.
     */
    public void recordConnections(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceRegistry registry,
        @NotNull Collection<? extends DBPDataSourceContainer> replaced
    ) throws DBException {
        JsonObject record = new JsonObject();
        record.addProperty(KEY_PROJECT, registry.getProject().getId());
        if (!replaced.isEmpty()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            char[] passphrase = Base64.getEncoder().encodeToString(random).toCharArray();
            Arrays.fill(random, (byte) 0);
            String secretId = SECRET_PREFIX + file.getFileName();
            try {
                DBSSecretController secrets = DBSSecretController.getGlobalSecretController();
                secrets.setPrivateSecretValue(secretId, new String(passphrase));
                secrets.flushChanges();
                byte[] data = ConnectionTransfer.exportConnectionsData(monitor, registry, replaced, List.of(), passphrase, null);
                record.addProperty(KEY_OLD, putBlob(data));
                record.addProperty(KEY_SECRET, secretId);
            } finally {
                Arrays.fill(passphrase, '\0');
            }
        }
        record.add(KEY_IMPORTED, new JsonArray());
        record.add(KEY_REPLACED, new JsonArray());
        manifest.add(KEY_CONNECTIONS, record);
    }

    /**
     * After the connection import: the connections it added and replaced.
     */
    public void recordImportedConnections(@NotNull ConnectionTransfer.ImportResult result) {
        JsonObject record = SettingsTransfer.getObject(manifest, KEY_CONNECTIONS);
        if (record == null) {
            return;
        }
        result.getImportedIds().forEach(record.getAsJsonArray(KEY_IMPORTED)::add);
        result.getReplacedIds().forEach(record.getAsJsonArray(KEY_REPLACED)::add);
    }

    /**
     * Writes the backup (again, after more was recorded).
     */
    public void save() throws DBException {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp"); //$NON-NLS-1$
            try (OutputStream out = Files.newOutputStream(temporary); ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry(ENTRY_MANIFEST));
                zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                for (Map.Entry<String, byte[]> blob : blobs.entrySet()) {
                    zip.putNextEntry(new ZipEntry(blob.getKey()));
                    zip.write(blob.getValue());
                    zip.closeEntry();
                }
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DBException("Cannot write the settings backup " + file, e);
        }
    }

    //////////////////////////////////////////////////////////
    // Undo

    /**
     * Reads the connections that the import replaced (decrypting them takes about a second: call it off the UI thread), or null.
     */
    @Nullable
    public ConnectionTransfer.ImportFile readReplacedConnections() throws DBException {
        JsonObject record = SettingsTransfer.getObject(manifest, KEY_CONNECTIONS);
        String blob = record == null ? null : SettingsTransfer.getString(record, KEY_OLD);
        String secretId = record == null ? null : SettingsTransfer.getString(record, KEY_SECRET);
        DBPDataSourceRegistry registry = record == null ? null : getRegistry(SettingsTransfer.getString(record, KEY_PROJECT));
        if (blob == null || secretId == null || registry == null) {
            return null;
        }
        String passphrase = DBSSecretController.getGlobalSecretController().getPrivateSecretValue(secretId);
        if (passphrase == null) {
            throw new DBException("The key of the connection backup is not in the secure storage");
        }
        char[] chars = passphrase.toCharArray();
        try {
            return ConnectionTransfer.readData(blobs.get(blob), chars, registry);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * Puts back what the import changed, unless it was changed again since; then marks the backup as undone.
     *
     * @param configFiles         the option files the import knows (to reload what uses them)
     * @param replacedConnections from {@link #readReplacedConnections()}
     */
    @NotNull
    public UndoResult undo(
        @NotNull DBRProgressMonitor monitor,
        @NotNull List<SettingsTransfer.ConfigFile> configFiles,
        @Nullable ConnectionTransfer.ImportFile replacedConnections
    ) throws DBException {
        UndoResult result = new UndoResult();
        undoConnections(monitor, replacedConnections, result);
        undoPreferences(result);
        undoConfigs(configFiles, result);
        undoDrivers(result);
        undoFiles(result);
        undoResources();
        manifest.addProperty(KEY_UNDONE, true);
        save();
        deleteSecret(manifest);
        return result;
    }

    private void undoPreferences(@NotNull UndoResult result) throws DBException {
        Preferences root = SettingsTransfer.getInstanceRoot();
        Set<Preferences> changed = new LinkedHashSet<>();
        for (JsonElement element : getArray(KEY_PREFERENCES)) {
            JsonObject record = element.getAsJsonObject();
            String nodePath = SettingsTransfer.getString(record, KEY_NODE);
            String key = SettingsTransfer.getString(record, KEY_KEY);
            if (nodePath == null || key == null) {
                continue;
            }
            Preferences node = root.node(nodePath);
            if (!Objects.equals(node.get(key, null), SettingsTransfer.getString(record, KEY_NEW))) {
                result.keptChanged.add(key);
                continue;
            }
            String oldValue = SettingsTransfer.getString(record, KEY_OLD);
            if (oldValue == null) {
                node.remove(key);
            } else {
                node.put(key, oldValue);
            }
            changed.add(node);
            result.restored++;
        }
        SettingsTransfer.flush(changed);
    }

    private void undoConfigs(@NotNull List<SettingsTransfer.ConfigFile> configFiles, @NotNull UndoResult result) throws DBException {
        for (JsonElement element : getArray(KEY_CONFIGS)) {
            JsonObject record = element.getAsJsonObject();
            String name = SettingsTransfer.getString(record, KEY_NAME);
            SettingsTransfer.ConfigFile config = configFiles.stream()
                .filter(f -> f.name().equals(name) && f.product() == isTrue(record, KEY_PRODUCT))
                .findFirst().orElse(null);
            if (config == null) {
                continue;
            }
            String current = config.getController().loadConfigurationFile(config.name());
            if (current == null || !hash(current.getBytes(StandardCharsets.UTF_8)).equals(SettingsTransfer.getString(record, KEY_WRITTEN))) {
                result.keptChanged.add(name);
                continue;
            }
            byte[] old = getBlob(SettingsTransfer.getString(record, KEY_OLD));
            // Every option file reads an empty content as "nothing customized", like a missing file
            config.getController().saveConfigurationFile(config.name(), old == null ? "" : new String(old, StandardCharsets.UTF_8));
            config.changed();
            result.restored++;
        }
    }

    private void undoDrivers(@NotNull UndoResult result) throws DBException {
        DataSourceProviderRegistry registry = DataSourceProviderRegistry.getInstance();
        boolean changed = false;
        for (JsonElement element : getArray(KEY_DRIVERS)) {
            JsonObject record = element.getAsJsonObject();
            String providerId = CommonUtils.notEmpty(SettingsTransfer.getString(record, KEY_PROVIDER));
            String driverId = CommonUtils.notEmpty(SettingsTransfer.getString(record, KEY_DRIVER));
            DataSourceProviderDescriptor provider = registry.getDataSourceProvider(providerId);
            if (provider == null || !(provider.getDriver(driverId) instanceof DriverDescriptor driver)) {
                continue;
            }
            SettingsTransfer.DriverState state = CommonUtils.valueOf(SettingsTransfer.DriverState.class,
                SettingsTransfer.getString(record, KEY_STATE), SettingsTransfer.DriverState.MODIFIED);
            switch (state) {
                case ABSENT -> {
                    if (isDriverUsed(driver)) {
                        result.keptDriversInUse.add(driver.getName());
                        continue;
                    }
                    provider.removeDriver(driver);
                }
                case DEFAULT -> SettingsTransfer.resetDriver(driver);
                case MODIFIED -> {
                    byte[] old = getBlob(SettingsTransfer.getString(record, KEY_OLD));
                    if (old == null) {
                        continue;
                    }
                    SettingsTransfer.loadDriver(providerId, driverId, new String(old, StandardCharsets.UTF_8), true);
                }
            }
            changed = true;
            result.restored++;
        }
        if (changed) {
            registry.saveDrivers();
        }
    }

    private static boolean isDriverUsed(@NotNull DBPDriver driver) {
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            if (!project.isRegistryLoaded()) {
                continue;
            }
            for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                if (container.getDriver() == driver) {
                    return true;
                }
            }
        }
        return false;
    }

    private void undoFiles(@NotNull UndoResult result) {
        // Backwards: a file written twice goes back to its first content
        List<JsonElement> records = new ArrayList<>();
        getArray(KEY_FILES).forEach(records::add);
        Collections.reverse(records);
        for (JsonElement element : records) {
            JsonObject record = element.getAsJsonObject();
            String written = SettingsTransfer.getString(record, KEY_WRITTEN);
            String pathName = SettingsTransfer.getString(record, KEY_PATH);
            if (written == null || pathName == null) {
                // The write did not happen
                continue;
            }
            Path path = Path.of(pathName);
            try {
                if (!Files.isRegularFile(path) || !hash(Files.readAllBytes(path)).equals(written)) {
                    result.keptChanged.add(String.valueOf(path.getFileName()));
                    continue;
                }
                byte[] old = getBlob(SettingsTransfer.getString(record, KEY_OLD));
                if (old == null) {
                    Files.delete(path);
                } else {
                    Files.write(path, old);
                }
                result.restored++;
            } catch (IOException e) {
                log.warn("Can't restore " + path, e);
                result.keptChanged.add(String.valueOf(path.getFileName()));
            }
        }
    }

    private void undoResources() {
        for (JsonElement element : getArray(KEY_RESOURCES)) {
            JsonObject record = element.getAsJsonObject();
            DBPProject project = DBWorkbench.getPlatform().getWorkspace().getProjectById(
                CommonUtils.notEmpty(SettingsTransfer.getString(record, KEY_PROJECT)));
            String resourcePath = SettingsTransfer.getString(record, KEY_PATH);
            if (project == null || resourcePath == null) {
                continue;
            }
            JsonObject properties = SettingsTransfer.getObject(record, KEY_PROPERTIES);
            if (properties != null) {
                Map<String, String> old = new LinkedHashMap<>();
                for (String key : properties.keySet()) {
                    old.put(key, SettingsTransfer.getString(properties, key));
                }
                project.setResourceProperties(resourcePath, old);
            } else if (project instanceof BaseProjectImpl baseProject) {
                baseProject.resetResourceProperties(resourcePath);
            }
        }
    }

    private void undoConnections(
        @NotNull DBRProgressMonitor monitor,
        @Nullable ConnectionTransfer.ImportFile replacedConnections,
        @NotNull UndoResult result
    ) throws DBException {
        JsonObject record = SettingsTransfer.getObject(manifest, KEY_CONNECTIONS);
        DBPDataSourceRegistry registry = record == null ? null : getRegistry(SettingsTransfer.getString(record, KEY_PROJECT));
        if (registry == null) {
            return;
        }
        boolean changed = false;
        JsonArray imported = record.getAsJsonArray(KEY_IMPORTED);
        if (imported != null) {
            for (JsonElement id : imported) {
                DBPDataSourceContainer container = registry.getDataSource(id.getAsString());
                if (container == null) {
                    continue;
                }
                // A connected connection is not removed: its sessions are not touched
                if (container.isConnected() || container.isConnecting()) {
                    result.keptConnected.add(container.getName());
                    continue;
                }
                registry.removeDataSource(container);
                changed = true;
                result.restored++;
            }
        }
        if (replacedConnections != null) {
            Map<String, ConnectionTransfer.ConflictAction> actions = new HashMap<>();
            for (ConnectionTransfer.ImportEntry entry : replacedConnections.getEntries()) {
                actions.put(entry.getId(), ConnectionTransfer.ConflictAction.REPLACE);
            }
            ConnectionTransfer.ImportResult restored = ConnectionTransfer.importConnections(monitor, registry, replacedConnections, actions);
            result.keptConnected.addAll(restored.getSkippedConnected());
            result.restored += restored.getReplaced() + restored.getAdded();
        }
        if (changed) {
            registry.flushConfig();
        }
    }

    //////////////////////////////////////////////////////////
    // Storage

    @NotNull
    private static Path getFolder() {
        return DBWorkbench.getPlatform().getWorkspace().getMetadataFolder().resolve(FOLDER);
    }

    /**
     * Backup files, newest first (the names sort by time).
     */
    @NotNull
    private static List<Path> listBackups(@NotNull Path folder) {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*." + FILE_EXTENSION)) { //$NON-NLS-1$
            stream.forEach(result::add);
        } catch (IOException e) {
            log.warn("Can't list the settings backups in " + folder, e);
        }
        result.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return result;
    }

    private static void pruneOldBackups(@NotNull Path folder) {
        List<Path> backups = listBackups(folder);
        // The new backup will be one more
        for (int i = KEEP - 1; i < backups.size(); i++) {
            Path old = backups.get(i);
            try {
                SettingsBackup backup = read(old);
                if (backup != null) {
                    deleteSecret(backup.manifest);
                }
                Files.delete(old);
            } catch (DBException | IOException e) {
                log.warn("Can't remove the old settings backup " + old, e);
            }
        }
    }

    @Nullable
    private static SettingsBackup read(@NotNull Path backupFile) throws DBException {
        JsonObject manifest = null;
        Map<String, byte[]> blobs = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(backupFile); ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                byte[] data = zip.readAllBytes();
                if (ENTRY_MANIFEST.equals(entry.getName())) {
                    manifest = SettingsTransfer.parseObject(data, ENTRY_MANIFEST);
                } else {
                    blobs.put(entry.getName(), data);
                }
            }
        } catch (IOException e) {
            throw new DBException("Cannot read the settings backup " + backupFile, e);
        }
        if (manifest == null || !FORMAT.equals(SettingsTransfer.getString(manifest, KEY_FORMAT))
            || CommonUtils.toInt(SettingsTransfer.getString(manifest, KEY_VERSION)) > VERSION) {
            log.warn("Not a settings backup this version can read: " + backupFile);
            return null;
        }
        SettingsBackup backup = new SettingsBackup(backupFile, manifest);
        backup.blobs.putAll(blobs);
        return backup;
    }

    private static void deleteSecret(@NotNull JsonObject manifest) {
        JsonObject record = SettingsTransfer.getObject(manifest, KEY_CONNECTIONS);
        String secretId = record == null ? null : SettingsTransfer.getString(record, KEY_SECRET);
        if (secretId == null) {
            return;
        }
        try {
            DBSSecretController secrets = DBSSecretController.getGlobalSecretController();
            secrets.setPrivateSecretValue(secretId, null);
            secrets.flushChanges();
        } catch (DBException e) {
            log.warn("Can't remove the key of a settings backup", e);
        }
    }

    @Nullable
    private static DBPDataSourceRegistry getRegistry(@Nullable String projectId) {
        DBPProject project = projectId == null ? null : DBWorkbench.getPlatform().getWorkspace().getProjectById(projectId);
        return project == null ? null : project.getDataSourceRegistry();
    }

    @NotNull
    private String putBlob(@NotNull byte[] data) {
        String name = BLOB_FOLDER + blobs.size();
        blobs.put(name, data);
        return name;
    }

    @Nullable
    private byte[] getBlob(@Nullable String name) {
        return name == null ? null : blobs.get(name);
    }

    @NotNull
    private JsonArray getArray(@NotNull String key) {
        JsonElement element = manifest.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static boolean isTrue(@NotNull JsonObject object, @NotNull String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    @NotNull
    private static String hash(@NotNull byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); //$NON-NLS-1$
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
