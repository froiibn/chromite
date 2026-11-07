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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceConfigurationStorage;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceFolder;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPConnectionType;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import com.seizia.chromite.model.net.DBWNetworkProfile;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.secret.DBSSecretController;
import com.seizia.chromite.registry.driver.DriverDescriptor;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/**
 * Exports connections (with their credentials when asked) to a passphrase-encrypted file and imports them back.
 *
 * 설계:
 * <ul>
 *     <li>새 PC로 옮기는 경로다. 저장된 비밀번호를 OS 보안 저장소로 옮길 계획(사용자 결정 2026-11-07 "A로 한다")에서는 작업 공간을
 *     복사해도 비밀번호가 따라가지 않으므로, 사용자가 정한 암호로 암호화한 파일에 비밀번호를 담아 옮긴다.</li>
 *     <li>연결 설정은 레지스트리의 직렬화기를 그대로 쓴다({@link DataSourceRegistry#saveConfigurationToManager}에 메모리 버퍼
 *     {@link DataSourceConfigurationManagerBuffer}를 넘김). 버퍼는 "secure" 관리자라서 직렬화기가 자격 증명을 파일 대신 JSON 안에 평문으로
 *     쓴다(CloudBeaver의 {@code DataSourceRegistryRM}과 같은 길). 그래서 연결 설정의 모든 항목이 저장 파일과 같은 형식으로 담긴다.</li>
 *     <li>자격 증명은 {@code credentials-config.json}을 읽지 않고 연결 객체에서 다시 채운다({@link SecureCredentials}: 사용자, 저장한
 *     비밀번호, 인증 모델 속성; 네트워크 처리기(SSH, 프록시, SSL)는 사용자, 저장한 비밀번호, 보안 속성). 프로젝트가 비밀 저장소를 쓰면
 *     먼저 비밀 컨트롤러에서 읽는다. 저장 위치가 OS 보안 저장소로 바뀌어도 연결 객체가 같은 값을 주므로 이 기능은 그대로 동작한다.</li>
 *     <li>가져오기는 파일 내용을 따로 만든 임시 레지스트리에 같은 직렬화기로 읽고({@link DataSourceSerializerModern#parseDataSources}),
 *     고른 연결만 복사해 실제 레지스트리의 보통 경로({@link DataSourceRegistry#addDataSource})로 더한다. 그래서 자격 증명은 그때
 *     쓰는 저장소에 저장된다. 실제 레지스트리에 바로 읽어 들이지 않는 것은 그 경로가 파일에 없는 네트워크·인증 프로필을 지우고
 *     같은 ID의 연결을 그 자리에서 바꾸기 때문이다.</li>
 *     <li>드라이버는 ID로만 참조한다(드라이버 정의와 jar는 담지 않는다). 없는 드라이버는 미리 보기에서 알리고, 연결은 저장 파일을
 *     읽을 때와 같이 "없는 드라이버"로 들어온다.</li>
 *     <li>연결된 연결은 바꾸지 않는다(세션과 트랜잭션을 건드리지 않기 위해). "바꾸기"를 고른 연결이 연결 중이면 건너뛰고 결과에 적는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/connection-transfer.md)
 * - 2026-11-08 다른 도구의 연결({@link ForeignConnectionImport})도 같은 미리 보기·충돌·가져오기를 쓰게 함: 파일을 읽은 뒤의 단계를
 *   {@link #readConfiguration}으로 나눔, 알 수 없는 종류의 연결과 그 대기 드라이버(가져올 때만 등록) (docs/chromite/connection-transfer.md
 *   "다른 도구에서 가져오기")
 */
public final class ConnectionTransfer {

    private static final String PAYLOAD_CREATED = "created"; //$NON-NLS-1$
    private static final String PAYLOAD_SECRETS = "secrets"; //$NON-NLS-1$
    private static final String PAYLOAD_CONFIGURATION = "configuration"; //$NON-NLS-1$

    // Sections of the data source configuration (DataSourceSerializerModern)
    static final String CFG_FOLDERS = "folders"; //$NON-NLS-1$
    static final String CFG_CONNECTIONS = "connections"; //$NON-NLS-1$
    private static final String CFG_CONFIGURATION = "configuration"; //$NON-NLS-1$
    private static final String CFG_CREDENTIALS = "credentials"; //$NON-NLS-1$
    private static final String CFG_NETWORK_PROFILES = "network-profiles"; //$NON-NLS-1$
    private static final String CFG_CONNECTION_TYPES = "connection-types"; //$NON-NLS-1$
    private static final String CFG_CONFIG_PROFILE = "config-profile"; //$NON-NLS-1$
    private static final String CFG_CONFIG_PROFILE_SOURCE = "config-profile-source"; //$NON-NLS-1$
    /**
     * Sections that are not part of the chosen connections: project-wide auth profiles and saved filters, and custom driver
     * definitions (drivers travel by ID only; their library paths are local).
     */
    private static final List<String> CFG_DROPPED_SECTIONS = List.of("auth-profiles", "saved-filters", "drivers"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /**
     * What to do with an imported connection that matches an existing one.
     */
    public enum ConflictAction {
        SKIP,
        REPLACE,
        COPY
    }

    /**
     * How an imported connection matches an existing one.
     */
    public enum ConflictKind {
        NONE,
        /**
         * An existing connection has the same ID.
         */
        SAME_ID,
        /**
         * An existing connection in the same folder has the same name.
         */
        SAME_NAME
    }

    /**
     * One connection of an import file, for the preview.
     */
    public static final class ImportEntry {
        @NotNull
        private final String id;
        @NotNull
        private final String name;
        @NotNull
        private final String address;
        @NotNull
        private final String driverName;
        private final boolean driverMissing;
        @Nullable
        private final String folderPath;
        private final boolean hasPassword;
        @Nullable
        private final String unrecognizedType;
        @NotNull
        private ConflictKind conflict = ConflictKind.NONE;
        @Nullable
        private DBPDataSourceContainer existing;

        private ImportEntry(
            @NotNull String id,
            @NotNull String name,
            @NotNull String address,
            @NotNull String driverName,
            boolean driverMissing,
            @Nullable String folderPath,
            boolean hasPassword,
            @Nullable String unrecognizedType
        ) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.driverName = driverName;
            this.driverMissing = driverMissing;
            this.folderPath = folderPath;
            this.hasPassword = hasPassword;
            this.unrecognizedType = unrecognizedType;
        }

        @NotNull
        public String getId() {
            return id;
        }

        @NotNull
        public String getName() {
            return name;
        }

        /**
         * "host:port", or the URL when the connection is configured by URL.
         */
        @NotNull
        public String getAddress() {
            return address;
        }

        @NotNull
        public String getDriverName() {
            return driverName;
        }

        /**
         * The driver is not installed here (the connection is imported with a missing driver).
         */
        public boolean isDriverMissing() {
            return driverMissing;
        }

        /**
         * Folder path ("a/b"), or null at the top level.
         */
        @Nullable
        public String getFolderPath() {
            return folderPath;
        }

        /**
         * The file carries a saved password for this connection or one of its network handlers.
         */
        public boolean hasPassword() {
            return hasPassword;
        }

        /**
         * The database type of another tool's connection that no installed driver matched (the connection gets a Generic
         * driver that must be configured), or null.
         */
        @Nullable
        public String getUnrecognizedType() {
            return unrecognizedType;
        }

        @NotNull
        public ConflictKind getConflict() {
            return conflict;
        }

        /**
         * The existing connection this one conflicts with.
         */
        @Nullable
        public DBPDataSourceContainer getExisting() {
            return existing;
        }

        /**
         * The existing connection is connected (or connecting): replacing it is not possible.
         */
        public boolean isExistingConnected() {
            return existing != null && (existing.isConnected() || existing.isConnecting());
        }
    }

    /**
     * A decrypted import file.
     */
    public static final class ImportFile {
        private final boolean secrets;
        @NotNull
        private final JsonObject configuration;
        @NotNull
        private final List<ImportEntry> entries;
        /**
         * Drivers for unrecognized types (by driver ID), registered only when a connection using them is imported.
         */
        @NotNull
        private final Map<String, DriverDescriptor> pendingDrivers;

        private ImportFile(
            boolean secrets,
            @NotNull JsonObject configuration,
            @NotNull List<ImportEntry> entries,
            @NotNull Map<String, DriverDescriptor> pendingDrivers
        ) {
            this.secrets = secrets;
            this.configuration = configuration;
            this.entries = entries;
            this.pendingDrivers = pendingDrivers;
        }

        /**
         * The file was exported with passwords.
         */
        public boolean hasSecrets() {
            return secrets;
        }

        @NotNull
        public List<ImportEntry> getEntries() {
            return entries;
        }
    }

    /**
     * What an import did.
     */
    public static final class ImportResult {
        private int added;
        private int replaced;
        private int copied;
        private int skipped;
        private final List<String> skippedConnected = new ArrayList<>();
        private final List<String> missingDrivers = new ArrayList<>();
        private final List<String> unrecognizedDrivers = new ArrayList<>();
        private final List<String> keptProfiles = new ArrayList<>();

        public int getAdded() {
            return added;
        }

        public int getReplaced() {
            return replaced;
        }

        public int getCopied() {
            return copied;
        }

        /**
         * Conflicts skipped by choice (connected ones are in {@link #getSkippedConnected()}).
         */
        public int getSkipped() {
            return skipped;
        }

        /**
         * Names of connections that were to be replaced but are connected, so they were left as they are.
         */
        @NotNull
        public List<String> getSkippedConnected() {
            return skippedConnected;
        }

        /**
         * Names of imported connections whose driver is not installed here.
         */
        @NotNull
        public List<String> getMissingDrivers() {
            return missingDrivers;
        }

        /**
         * Names of imported connections of an unrecognized type: their Generic driver must be configured before connecting.
         */
        @NotNull
        public List<String> getUnrecognizedDrivers() {
            return unrecognizedDrivers;
        }

        /**
         * Names of network profiles that already existed here; the existing ones were kept.
         */
        @NotNull
        public List<String> getKeptProfiles() {
            return keptProfiles;
        }
    }

    private ConnectionTransfer() {
    }

    //////////////////////////////////////////////////////////
    // Export

    /**
     * Writes the connections to a file.
     *
     * @param folders    chosen folders (kept in the file even when empty); the folders of the connections are added
     * @param passphrase encrypts the file and includes the credentials; null writes a plain file without any secret
     * @return the number of exported connections
     */
    public static int exportConnections(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceRegistry registry,
        @NotNull Collection<? extends DBPDataSourceContainer> connections,
        @NotNull Collection<? extends DBPDataSourceFolder> folders,
        @Nullable char[] passphrase,
        @NotNull Path file
    ) throws DBException {
        DataSourceRegistry<?> sourceRegistry = getRegistry(registry);
        boolean secrets = passphrase != null;
        List<DataSourceDescriptor> descriptors = new ArrayList<>();
        for (DBPDataSourceContainer container : connections) {
            DataSourceDescriptor descriptor = sourceRegistry.getDataSource(container.getId());
            if (descriptor != null && !descriptor.isDetached()) {
                descriptors.add(descriptor);
            }
        }
        if (secrets) {
            // Secrets kept outside the configuration (secret storage) are read before they are copied
            for (DataSourceDescriptor descriptor : descriptors) {
                descriptor.resolveSecretsIfNeeded();
            }
        }
        Set<String> ids = new HashSet<>();
        for (DataSourceDescriptor descriptor : descriptors) {
            ids.add(descriptor.getId());
        }

        DataSourceConfigurationManagerBuffer buffer = new DataSourceConfigurationManagerBuffer();
        sourceRegistry.saveConfigurationToManager(monitor, buffer, container -> ids.contains(container.getId()));
        sourceRegistry.checkForErrors();
        byte[] data = buffer.getData();
        if (data == null) {
            throw new DBException("Connection configuration was not serialized");
        }
        JsonObject configuration;
        try {
            configuration = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        } finally {
            Arrays.fill(data, (byte) 0);
            buffer.setData(null);
        }

        prepareConfiguration(sourceRegistry, configuration, descriptors, folders, secrets);

        JsonObject payload = new JsonObject();
        payload.addProperty(PAYLOAD_CREATED, Instant.now().toString());
        payload.addProperty(PAYLOAD_SECRETS, secrets);
        payload.add(PAYLOAD_CONFIGURATION, configuration);
        byte[] payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try {
            byte[] fileBytes = ConnectionTransferFile.write(payloadBytes, passphrase);
            Files.write(file, fileBytes);
        } catch (IOException e) {
            throw new DBException("Cannot write " + file, e);
        } finally {
            Arrays.fill(payloadBytes, (byte) 0);
        }
        return descriptors.size();
    }

    /**
     * Keeps only what belongs to the chosen connections and puts in (or takes out) their credentials.
     */
    private static void prepareConfiguration(
        @NotNull DataSourceRegistry<?> registry,
        @NotNull JsonObject configuration,
        @NotNull List<DataSourceDescriptor> descriptors,
        @NotNull Collection<? extends DBPDataSourceFolder> folders,
        boolean secrets
    ) throws DBException {
        for (String section : CFG_DROPPED_SECTIONS) {
            configuration.remove(section);
        }

        // Folders: the chosen ones and the folders of the connections, with their parents
        Set<String> folderPaths = new HashSet<>();
        for (DBPDataSourceFolder folder : folders) {
            addFolderPaths(folder, folderPaths);
        }
        Set<String> profileNames = new HashSet<>();
        Set<String> customTypes = new HashSet<>();
        for (DataSourceDescriptor descriptor : descriptors) {
            addFolderPaths(descriptor.getFolder(), folderPaths);
            DBPConnectionConfiguration connectionInfo = descriptor.getConnectionConfiguration();
            if (CommonUtils.isEmpty(connectionInfo.getConfigProfileSource()) && !CommonUtils.isEmpty(connectionInfo.getConfigProfileName())) {
                profileNames.add(connectionInfo.getConfigProfileName());
            }
            DBPConnectionType connectionType = connectionInfo.getConnectionType();
            if (connectionType != null && !connectionType.isPredefined()) {
                customTypes.add(connectionType.getId());
            }
        }
        retainKeys(getObject(configuration, CFG_FOLDERS), folderPaths::contains);
        retainKeys(getObject(configuration, CFG_CONNECTION_TYPES), customTypes::contains);

        // Network profiles referenced by the connections (profiles from external sources are not in the file)
        JsonObject profilesJson = getObject(configuration, CFG_NETWORK_PROFILES);
        if (profilesJson != null) {
            for (String key : new ArrayList<>(profilesJson.keySet())) {
                JsonObject profileJson = getObject(profilesJson, key);
                String name = profileJson == null ? null : getString(profileJson, RegistryConstants.ATTR_NAME);
                if (!profileNames.contains(key) && (name == null || !profileNames.contains(name))) {
                    profilesJson.remove(key);
                    continue;
                }
                DBWNetworkProfile profile = registry.getNetworkProfiles().getProfile(null, name != null ? name : key);
                if (secrets && profile != null) {
                    if (registry.getProject().isUseSecretStorage()) {
                        profile.resolveSecrets(DBSSecretController.getProjectSecretController(registry.getProject()));
                    }
                    putHandlerCredentials(getObject(profileJson, RegistryConstants.TAG_HANDLERS), profile.getConfigurations());
                } else {
                    stripHandlerSecrets(getObject(profileJson, RegistryConstants.TAG_HANDLERS));
                }
            }
        }

        // Connection credentials
        JsonObject connectionsJson = getObject(configuration, CFG_CONNECTIONS);
        if (connectionsJson == null) {
            throw new DBException("Connection configuration has no connections");
        }
        for (DataSourceDescriptor descriptor : descriptors) {
            JsonObject connectionJson = getObject(connectionsJson, descriptor.getId());
            JsonObject cfgJson = connectionJson == null ? null : getObject(connectionJson, CFG_CONFIGURATION);
            if (cfgJson == null) {
                continue;
            }
            if (secrets) {
                putCredentials(cfgJson, new SecureCredentials(descriptor));
                putHandlerCredentials(getObject(cfgJson, RegistryConstants.TAG_HANDLERS), descriptor.getConnectionConfiguration().getHandlers());
            } else {
                // No password is saved, so connecting asks for it
                connectionJson.remove(RegistryConstants.ATTR_SAVE_PASSWORD);
                // The lock password hash is derived from a secret
                connectionJson.remove(RegistryConstants.ATTR_LOCK_PASSWORD);
                cfgJson.remove(RegistryConstants.TAG_AUTH_PROPERTIES);
                stripCredentials(cfgJson);
                stripHandlerSecrets(getObject(cfgJson, RegistryConstants.TAG_HANDLERS));
            }
        }
    }

    private static void addFolderPaths(@Nullable DBPDataSourceFolder folder, @NotNull Set<String> paths) {
        for (DBPDataSourceFolder f = folder; f != null; f = f.getParent()) {
            paths.add(f.getFolderPath());
        }
    }

    private static void putHandlerCredentials(@Nullable JsonObject handlersJson, @NotNull Collection<DBWHandlerConfiguration> handlers) {
        if (handlersJson == null) {
            return;
        }
        for (DBWHandlerConfiguration handler : handlers) {
            JsonObject handlerJson = getObject(handlersJson, CommonUtils.notEmpty(handler.getId()));
            if (handlerJson != null) {
                SecureCredentials credentials = new SecureCredentials(handler);
                credentials.setProperties(handler.getSecureProperties());
                putCredentials(handlerJson, credentials);
            }
        }
    }

    /**
     * Sets the "credentials" object as the serializer writes it for a secure configuration manager.
     */
    private static void putCredentials(@NotNull JsonObject target, @NotNull SecureCredentials credentials) {
        Map<String, String> values = new LinkedHashMap<>();
        DataSourceParser.saveCredentialsToMap(values, credentials);
        if (values.isEmpty()) {
            target.remove(CFG_CREDENTIALS);
            return;
        }
        JsonObject credentialsJson = new JsonObject();
        for (Map.Entry<String, String> value : values.entrySet()) {
            credentialsJson.addProperty(value.getKey(), value.getValue());
        }
        target.add(CFG_CREDENTIALS, credentialsJson);
    }

    private static void stripHandlerSecrets(@Nullable JsonObject handlersJson) {
        if (handlersJson == null) {
            return;
        }
        for (String key : handlersJson.keySet()) {
            JsonObject handlerJson = getObject(handlersJson, key);
            if (handlerJson != null) {
                handlerJson.remove(RegistryConstants.ATTR_SAVE_PASSWORD);
                stripCredentials(handlerJson);
            }
        }
    }

    /**
     * Keeps only the user name of a "credentials" object (no password, no secure property).
     */
    private static void stripCredentials(@NotNull JsonObject target) {
        JsonObject credentialsJson = getObject(target, CFG_CREDENTIALS);
        if (credentialsJson == null) {
            return;
        }
        String user = getString(credentialsJson, RegistryConstants.ATTR_USER);
        target.remove(CFG_CREDENTIALS);
        if (!CommonUtils.isEmpty(user)) {
            JsonObject userOnly = new JsonObject();
            userOnly.addProperty(RegistryConstants.ATTR_USER, user);
            target.add(CFG_CREDENTIALS, userOnly);
        }
    }

    //////////////////////////////////////////////////////////
    // Import

    /**
     * True if the file needs a passphrase.
     */
    public static boolean isEncrypted(@NotNull Path file) throws DBException {
        return ConnectionTransferFile.isEncrypted(readBytes(file));
    }

    /**
     * Decrypts and reads a file; conflicts are checked against the target registry. Nothing is changed.
     */
    @NotNull
    public static ImportFile readFile(
        @NotNull Path file,
        @Nullable char[] passphrase,
        @NotNull DBPDataSourceRegistry target
    ) throws DBException {
        byte[] payloadBytes = ConnectionTransferFile.read(readBytes(file), passphrase);
        JsonObject payload;
        try {
            JsonElement element = JsonParser.parseString(new String(payloadBytes, StandardCharsets.UTF_8));
            payload = element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (JsonParseException e) {
            throw invalidContent(e);
        } finally {
            Arrays.fill(payloadBytes, (byte) 0);
        }
        JsonObject configuration = payload == null ? null : getObject(payload, PAYLOAD_CONFIGURATION);
        if (configuration == null) {
            throw invalidContent(null);
        }
        boolean secrets = payload.has(PAYLOAD_SECRETS) && payload.get(PAYLOAD_SECRETS).isJsonPrimitive()
            && payload.get(PAYLOAD_SECRETS).getAsBoolean();
        return readConfiguration(configuration, secrets, Map.of(), Map.of(), getRegistry(target));
    }

    /**
     * Builds the preview of a configuration in the registry's file format; conflicts are checked against the target registry.
     *
     * @param pendingDrivers    drivers not registered yet (by driver ID); their connections are not shown as "driver missing"
     * @param unrecognizedTypes source database type of the connections (by ID) that got a driver for an unrecognized type
     */
    @NotNull
    static ImportFile readConfiguration(
        @NotNull JsonObject configuration,
        boolean secrets,
        @NotNull Map<String, DriverDescriptor> pendingDrivers,
        @NotNull Map<String, String> unrecognizedTypes,
        @NotNull DataSourceRegistry<?> target
    ) throws DBException {
        JsonObject connectionsJson = getObject(configuration, CFG_CONNECTIONS);
        if (connectionsJson == null) {
            throw invalidContent(null);
        }
        DataSourceProviderRegistry providers = DataSourceProviderRegistry.getInstance();
        List<ImportEntry> entries = new ArrayList<>();
        for (String id : connectionsJson.keySet()) {
            JsonObject connectionJson = getObject(connectionsJson, id);
            if (connectionJson == null) {
                continue;
            }
            JsonObject cfgJson = getObject(connectionJson, CFG_CONFIGURATION);
            String providerId = CommonUtils.notEmpty(getString(connectionJson, RegistryConstants.ATTR_PROVIDER));
            String driverId = CommonUtils.notEmpty(getString(connectionJson, RegistryConstants.ATTR_DRIVER));
            DataSourceProviderDescriptor provider = providerId.isEmpty() ? null : providers.getDataSourceProvider(providerId);
            DBPDriver driver = provider == null || driverId.isEmpty() ? null : provider.getOriginalDriver(driverId);
            DriverDescriptor pending = pendingDrivers.get(driverId);
            if (driver == null && pending != null && pending.getProviderDescriptor() == provider) {
                driver = pending;
            }
            boolean driverMissing = driver == null || driver.isTemporary();
            ImportEntry entry = new ImportEntry(
                id,
                CommonUtils.notEmpty(getString(connectionJson, RegistryConstants.ATTR_NAME)),
                cfgJson == null ? "" : getAddress(cfgJson),
                driverMissing ? driverId : driver.getName(),
                driverMissing,
                CommonUtils.nullIfEmpty(getString(connectionJson, RegistryConstants.ATTR_FOLDER)),
                cfgJson != null && hasPassword(cfgJson),
                unrecognizedTypes.get(id));
            entries.add(entry);
        }
        ImportFile importFile = new ImportFile(secrets, configuration, entries, pendingDrivers);
        updateConflicts(target, importFile);
        return importFile;
    }

    /**
     * Imports the connections of a file. Connections without a conflict are always imported; for a conflict the action
     * comes from {@code actions} (by entry ID; missing means skip). Conflicts are checked again here, so a connection
     * added or connected since the preview is handled safely.
     */
    @NotNull
    public static ImportResult importConnections(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceRegistry target,
        @NotNull ImportFile importFile,
        @NotNull Map<String, ConflictAction> actions
    ) throws DBException {
        DataSourceRegistry<?> registry = getRegistry(target);
        updateConflicts(registry, importFile);
        ImportResult result = new ImportResult();

        Map<String, ConflictAction> plan = new LinkedHashMap<>();
        for (ImportEntry entry : importFile.entries) {
            if (entry.conflict == ConflictKind.NONE) {
                plan.put(entry.id, null);
                continue;
            }
            ConflictAction action = actions.getOrDefault(entry.id, ConflictAction.SKIP);
            if (action == ConflictAction.SKIP) {
                result.skipped++;
            } else if (action == ConflictAction.REPLACE && entry.isExistingConnected()) {
                // Replacing would change a connection in use; its sessions are not touched
                result.skippedConnected.add(entry.name);
            } else {
                plan.put(entry.id, action);
            }
        }
        if (plan.isEmpty()) {
            return result;
        }

        monitor.beginTask("Import connections", plan.size());
        JsonObject configuration = importFile.configuration.deepCopy();
        JsonObject connectionsJson = Objects.requireNonNull(getObject(configuration, CFG_CONNECTIONS));
        retainKeys(connectionsJson, plan::containsKey);
        Set<String> usedTypes = new HashSet<>();
        for (String id : connectionsJson.keySet()) {
            JsonObject cfgJson = getObject(Objects.requireNonNull(getObject(connectionsJson, id)), CFG_CONFIGURATION);
            String type = cfgJson == null ? null : getString(cfgJson, RegistryConstants.ATTR_TYPE);
            if (type != null) {
                usedTypes.add(type);
            }
        }
        retainKeys(getObject(configuration, CFG_CONNECTION_TYPES), usedTypes::contains);
        registerPendingDrivers(importFile, connectionsJson);
        boolean newConnectionTypes = false;
        JsonObject typesJson = getObject(configuration, CFG_CONNECTION_TYPES);
        if (typesJson != null) {
            for (String typeId : typesJson.keySet()) {
                if (DataSourceProviderRegistry.getInstance().getConnectionType(typeId, null) == null) {
                    newConnectionTypes = true;
                }
            }
        }

        // Parse into a separate registry of the same project: the serializer creates the descriptors, folders,
        // profiles and connection types exactly as when it reads the project's own configuration
        byte[] data = configuration.toString().getBytes(StandardCharsets.UTF_8);
        TransferConfigurationManager manager = new TransferConfigurationManager(data);
        DataSourceRegistry<DataSourceDescriptor> scratch = new DataSourceRegistry<>(
            registry.getProject(), manager, registry.getPreferenceStore());
        DataSourceParseResults parseResults = new DataSourceParseResults();
        try {
            new DataSourceSerializerModern<>(scratch).parseDataSources(manager.storage, manager, parseResults, null);
        } catch (IOException e) {
            throw invalidContent(e);
        } finally {
            Arrays.fill(data, (byte) 0);
            manager.setData(null);
        }

        try {
            // Network profiles first, so the connections that use them are saved together with them
            for (DBWNetworkProfile profile : scratch.getNetworkProfiles().getProfiles()) {
                if (registry.getNetworkProfiles().getProfile(null, profile.getProfileName()) != null) {
                    result.keptProfiles.add(profile.getProfileName());
                } else {
                    registry.getNetworkProfiles().addOrUpdateProfile(profile);
                }
            }
            // Folders of the file (chosen at export, possibly empty)
            JsonObject foldersJson = getObject(configuration, CFG_FOLDERS);
            if (foldersJson != null) {
                for (String folderPath : foldersJson.keySet()) {
                    registry.getFolder(folderPath);
                }
            }

            for (DBPDataSourceContainer parsed : parseResults.addedDataSources) {
                if (!(parsed instanceof DataSourceDescriptor source) || !plan.containsKey(source.getId())) {
                    continue;
                }
                ConflictAction action = plan.get(source.getId());
                ImportEntry entry = findEntry(importFile, source.getId());
                monitor.subTask(source.getName());
                // Cross-registry copy: the default storage and the folder of the target registry
                DataSourceDescriptor copy = new DataSourceDescriptor(source, registry, true);
                if (action == ConflictAction.REPLACE && entry != null && entry.existing != null) {
                    // Keep the existing ID so that scripts bound to the connection still find it
                    String existingId = entry.existing.getId();
                    registry.removeDataSource(entry.existing);
                    copy.setId(existingId);
                    registry.addDataSource(copy);
                    result.replaced++;
                } else if (action == ConflictAction.COPY) {
                    copy.setId(DataSourceDescriptor.generateNewId(copy.getDriver()));
                    copy.setName(getUniqueName(registry, copy.getName(), entry == null ? null : entry.folderPath));
                    registry.addDataSource(copy);
                    result.copied++;
                } else {
                    registry.addDataSource(copy);
                    result.added++;
                }
                if (copy.getDriver().isTemporary()) {
                    result.missingDrivers.add(copy.getName());
                }
                if (entry != null && entry.unrecognizedType != null) {
                    result.unrecognizedDrivers.add(copy.getName());
                }
                monitor.worked(1);
            }
            if (newConnectionTypes) {
                DataSourceProviderRegistry.getInstance().saveConnectionTypes();
            }
            registry.flushConfig();
        } finally {
            for (DBPDataSourceContainer parsed : parseResults.addedDataSources) {
                parsed.dispose();
            }
            monitor.done();
        }
        return result;
    }

    /**
     * Registers (and saves) the pending drivers that the connections to import use; drivers of skipped connections are not created.
     */
    private static void registerPendingDrivers(@NotNull ImportFile importFile, @NotNull JsonObject connectionsJson) throws DBException {
        if (importFile.pendingDrivers.isEmpty()) {
            return;
        }
        boolean added = false;
        for (String id : connectionsJson.keySet()) {
            JsonObject connectionJson = Objects.requireNonNull(getObject(connectionsJson, id));
            DriverDescriptor pending = importFile.pendingDrivers.get(CommonUtils.notEmpty(getString(connectionJson, RegistryConstants.ATTR_DRIVER)));
            if (pending == null
                || !pending.getProviderDescriptor().getId().equals(getString(connectionJson, RegistryConstants.ATTR_PROVIDER))
                || pending.getProviderDescriptor().getOriginalDriver(pending.getId()) != null) {
                continue;
            }
            pending.getProviderDescriptor().addDriver(pending);
            added = true;
        }
        if (added) {
            DataSourceProviderRegistry.getInstance().saveDrivers();
        }
    }

    private static void updateConflicts(@NotNull DataSourceRegistry<?> registry, @NotNull ImportFile importFile) {
        for (ImportEntry entry : importFile.entries) {
            DBPDataSourceContainer byId = registry.getDataSource(entry.id);
            if (byId != null) {
                entry.conflict = ConflictKind.SAME_ID;
                entry.existing = byId;
                continue;
            }
            DBPDataSourceContainer byName = findByName(registry, entry.name, entry.folderPath);
            entry.conflict = byName == null ? ConflictKind.NONE : ConflictKind.SAME_NAME;
            entry.existing = byName;
        }
    }

    @Nullable
    private static DBPDataSourceContainer findByName(
        @NotNull DataSourceRegistry<?> registry,
        @NotNull String name,
        @Nullable String folderPath
    ) {
        for (DBPDataSourceContainer container : registry.getDataSources()) {
            DBPDataSourceFolder folder = container.getFolder();
            if (name.equals(container.getName()) && Objects.equals(folderPath, folder == null ? null : folder.getFolderPath())) {
                return container;
            }
        }
        return null;
    }

    /**
     * "Name (2)", "Name (3)", … : the first one not used in the folder.
     */
    @NotNull
    private static String getUniqueName(@NotNull DataSourceRegistry<?> registry, @NotNull String name, @Nullable String folderPath) {
        for (int i = 2; ; i++) {
            String candidate = name + " (" + i + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            if (findByName(registry, candidate, folderPath) == null) {
                return candidate;
            }
        }
    }

    @Nullable
    private static ImportEntry findEntry(@NotNull ImportFile importFile, @NotNull String id) {
        for (ImportEntry entry : importFile.entries) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    @NotNull
    private static String getAddress(@NotNull JsonObject cfgJson) {
        String host = getString(cfgJson, RegistryConstants.ATTR_HOST);
        if (CommonUtils.isEmpty(host)) {
            return CommonUtils.notEmpty(getString(cfgJson, RegistryConstants.ATTR_URL));
        }
        String port = getString(cfgJson, RegistryConstants.ATTR_PORT);
        return CommonUtils.isEmpty(port) ? host : host + ":" + port; //$NON-NLS-1$
    }

    private static boolean hasPassword(@NotNull JsonObject cfgJson) {
        if (hasCredentialPassword(cfgJson)) {
            return true;
        }
        JsonObject handlersJson = getObject(cfgJson, RegistryConstants.TAG_HANDLERS);
        if (handlersJson != null) {
            for (String key : handlersJson.keySet()) {
                JsonObject handlerJson = getObject(handlersJson, key);
                if (handlerJson != null && hasCredentialPassword(handlerJson)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasCredentialPassword(@NotNull JsonObject target) {
        JsonObject credentialsJson = getObject(target, CFG_CREDENTIALS);
        return credentialsJson != null && !CommonUtils.isEmpty(getString(credentialsJson, RegistryConstants.ATTR_PASSWORD));
    }

    //////////////////////////////////////////////////////////
    // Helpers

    @NotNull
    private static DataSourceRegistry<?> getRegistry(@NotNull DBPDataSourceRegistry registry) throws DBException {
        if (registry instanceof DataSourceRegistry<?> dataSourceRegistry) {
            return dataSourceRegistry;
        }
        throw new DBException("Unsupported connection registry: " + registry.getClass().getName());
    }

    @NotNull
    private static byte[] readBytes(@NotNull Path file) throws DBException {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new DBException("Cannot read " + file, e);
        }
    }

    @NotNull
    private static ConnectionTransferException invalidContent(@Nullable Throwable cause) {
        return new ConnectionTransferException(ConnectionTransferException.Reason.INVALID_CONTENT,
            "The connection file has no valid connection list", cause);
    }

    private static void retainKeys(@Nullable JsonObject object, @NotNull Predicate<String> keep) {
        if (object == null) {
            return;
        }
        for (String key : new ArrayList<>(object.keySet())) {
            if (!keep.test(key)) {
                object.remove(key);
            }
        }
    }

    @Nullable
    private static JsonObject getObject(@NotNull JsonObject object, @NotNull String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    @Nullable
    private static String getString(@NotNull JsonObject object, @NotNull String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /**
     * Configuration manager of the import's separate registry: the file's configuration as its one default storage.
     * A secure manager, so the serializer reads the credentials from the configuration itself. {@link ForeignConnectionImport}
     * also serializes other tools' connections into it (secure: the credentials are written into the configuration).
     */
    static final class TransferConfigurationManager extends DataSourceConfigurationManagerBuffer {
        private final DBPDataSourceConfigurationStorage storage = new DBPDataSourceConfigurationStorage() {
            @NotNull
            @Override
            public String getStorageId() {
                return "connection-transfer"; //$NON-NLS-1$
            }

            @Override
            public boolean isValid() {
                return true;
            }

            @Override
            public boolean isDefault() {
                return true;
            }

            @Override
            public boolean isVirtual() {
                return false;
            }

            @Nullable
            @Override
            public String getStorageSubId() {
                return null;
            }
        };

        TransferConfigurationManager(@Nullable byte[] data) {
            setData(data);
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public List<DBPDataSourceConfigurationStorage> getConfigurationStorages() {
            return List.of(storage);
        }
    }
}
