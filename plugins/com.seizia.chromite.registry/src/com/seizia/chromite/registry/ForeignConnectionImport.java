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
import com.google.gson.JsonParser;
import com.seizia.chromite.model.DatabaseURL;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.connection.DBPDriverConfigurationType;
import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.registry.driver.DriverDescriptor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Turns the connections read from another tool's file into an import file of {@link ConnectionTransfer}, so that they go
 * through the same preview, conflict handling and import as a Chromite connection file.
 *
 * 설계:
 * <ul>
 *     <li>드라이버는 단서 순서대로 고른다: 원본이 정한 드라이버 → 종류 이름 → 드라이버 클래스 → JDBC URL의 하위 프로토콜.
 *     종류 이름과 하위 프로토콜은 같은 규칙으로 찾는다(별칭 표, 드라이버 ID·"공급자:ID"·이름, 공급자 ID의 첫 사용 가능 드라이버).
 *     별칭 표는 흔한 다른 이름(postgres, mssql, mariadb …)과, 공급자의 첫 드라이버가 기본이 아닌 경우(MySQL은 첫 드라이버가
 *     5.x용)만 담는다.</li>
 *     <li>어느 것으로도 못 찾으면 Generic 공급자의 사용자 드라이버("imported_" + 종류)로 만든다. 미리 보기에서는 아직 등록하지 않고
 *     ({@link ConnectionTransfer.ImportFile} 안의 대기 드라이버), 실제로 가져올 때 그 연결을 가져오는 경우에만 등록한다. 같은 종류를
 *     다시 가져오면 같은 드라이버를 쓰고(이름으로 찾힘) 그때도 알 수 없는 종류로 표시한다. 미리 보기와 결과는 이 연결을 "알 수 없는 종류"로 알리고, 드라이버 관리자에서 클래스와
 *     라이브러리를 넣어야 연결된다.</li>
 *     <li>연결 설정은 같은 프로젝트의 메모리 레지스트리에 {@link DataSourceDescriptor}로 만들고, 레지스트리 직렬화기로
 *     {@code data-sources.json} 형식을 만든다. 그래서 네트워크 처리기, 자격 증명, 그룹이 Chromite 파일과 똑같은 형식이 되고
 *     {@link ConnectionTransfer#importConnections}가 그대로 가져온다(새 형식 코드가 없다).</li>
 *     <li>호스트 없이 URL만 있으면 드라이버의 URL 견본으로 호스트·포트·DB를 뽑아 보고(SWT {@code ConfigImportWizard.populateBySampleParams}),
 *     뽑히면 호스트 방식, 아니면 URL 방식 연결로 만든다.</li>
 *     <li>비밀번호가 있는 연결만 "비밀번호 저장"을 켠다. 저장은 가져올 때 레지스트리의 보통 경로(OS 보안 저장소)로 된다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/connection-transfer.md "다른 도구에서 가져오기")
 */
public final class ForeignConnectionImport {

    /**
     * Provider of the drivers created for unrecognized types.
     */
    public static final String GENERIC_PROVIDER_ID = "generic"; //$NON-NLS-1$
    /**
     * ID prefix of the drivers created for unrecognized types.
     */
    public static final String IMPORTED_DRIVER_PREFIX = "imported_"; //$NON-NLS-1$

    private static final String JDBC_PREFIX = "jdbc:"; //$NON-NLS-1$
    private static final String DEFAULT_TYPE_KEY = "jdbc"; //$NON-NLS-1$

    /**
     * Common other names of a database type (normalized: lower case, no space, '-', '_' or '.') → "provider:driver".
     * Only names that the provider and driver IDs do not already find, and providers whose first driver is not the usual one.
     */
    private static final Map<String, String> TYPE_ALIASES = Map.ofEntries(
        Map.entry("oracle", "oracle:oracle_thin"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("postgresql", "postgresql:postgres-jdbc"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("postgres", "postgresql:postgres-jdbc"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("pgsql", "postgresql:postgres-jdbc"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("pg", "postgresql:postgres-jdbc"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("mysql", "mysql:mysql8"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("mariadb", "mysql:mariaDB"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("sqlserver", "sqlserver:microsoft"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("mssql", "sqlserver:microsoft"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("mssqlserver", "sqlserver:microsoft"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("microsoftsqlserver", "sqlserver:microsoft"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("sqlite", "sqlite:sqlite_jdbc"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("db2", "db2:db2") //$NON-NLS-1$ //$NON-NLS-2$
    );

    private ForeignConnectionImport() {
    }

    /**
     * Maps the connections to drivers and prepares them for the import preview. Nothing is changed: drivers for
     * unrecognized types are created only when such a connection is imported.
     */
    @NotNull
    public static ConnectionTransfer.ImportFile prepare(
        @NotNull DBRProgressMonitor monitor,
        @NotNull List<ForeignConnection> connections,
        @NotNull DBPDataSourceRegistry target
    ) throws DBException {
        if (!(target instanceof DataSourceRegistry<?> targetRegistry)) {
            throw new DBException("Unsupported connection registry: " + target.getClass().getName());
        }
        ConnectionTransfer.TransferConfigurationManager manager = new ConnectionTransfer.TransferConfigurationManager(null);
        DataSourceRegistry<DataSourceDescriptor> scratch = new DataSourceRegistry<>(
            targetRegistry.getProject(), manager, targetRegistry.getPreferenceStore());
        Map<String, DriverDescriptor> pendingDrivers = new HashMap<>();
        Map<String, String> unrecognizedTypes = new HashMap<>();
        List<DataSourceDescriptor> descriptors = new ArrayList<>();
        boolean secrets = false;
        JsonObject configuration;
        try {
            for (ForeignConnection connection : connections) {
                DBPDriver driver = findDriver(connection);
                String unrecognizedType = null;
                if (driver == null) {
                    unrecognizedType = getTypeKey(connection);
                    driver = getImportedDriver(connection, unrecognizedType, pendingDrivers);
                } else if (isImportedDriver(driver)) {
                    // A driver an earlier import created for this type (found by its name): it still may lack its library
                    unrecognizedType = getTypeKey(connection);
                }
                DataSourceDescriptor descriptor = scratch.createDataSource(
                    DataSourceDescriptor.generateNewId(driver), driver, createConfiguration(connection, driver));
                descriptor.setName(connection.getName());
                boolean hasPassword = !CommonUtils.isEmpty(connection.getPassword());
                descriptor.setSavePassword(hasPassword);
                secrets |= hasPassword;
                for (DBWHandlerConfiguration handler : connection.getHandlers()) {
                    secrets |= handler.isSavePassword() && !CommonUtils.isEmpty(handler.getPassword());
                }
                String folderPath = CommonUtils.nullIfEmpty(connection.getFolderPath());
                if (folderPath != null) {
                    descriptor.setFolder(scratch.getFolder(folderPath));
                }
                scratch.addDataSourceToList(descriptor);
                descriptors.add(descriptor);
                if (unrecognizedType != null) {
                    unrecognizedTypes.put(descriptor.getId(), CommonUtils.notEmpty(connection.getType()).isEmpty()
                        ? unrecognizedType : connection.getType());
                }
            }

            // The registry serializer writes the connections in the Chromite file format (credentials in plain JSON:
            // the manager is a secure in-memory buffer)
            scratch.saveConfigurationToManager(monitor, manager, null);
            scratch.checkForErrors();
            byte[] data = manager.getData();
            if (data == null) {
                throw new DBException("Connection configuration was not serialized");
            }
            try {
                JsonElement element = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
                configuration = element.getAsJsonObject();
            } finally {
                Arrays.fill(data, (byte) 0);
                manager.setData(null);
            }
        } finally {
            for (DataSourceDescriptor descriptor : descriptors) {
                descriptor.dispose();
            }
        }
        // Only the connections and their groups: the rest (project network and auth profiles, filters, connection
        // types, driver definitions) belongs to the project, not to the imported connections
        for (String section : new ArrayList<>(configuration.keySet())) {
            if (!ConnectionTransfer.CFG_FOLDERS.equals(section) && !ConnectionTransfer.CFG_CONNECTIONS.equals(section)) {
                configuration.remove(section);
            }
        }
        return ConnectionTransfer.readConfiguration(configuration, secrets, pendingDrivers, unrecognizedTypes, targetRegistry);
    }

    //////////////////////////////////////////////////////////
    // Drivers

    /**
     * The installed driver for the connection, or null when no clue finds one.
     */
    @Nullable
    static DBPDriver findDriver(@NotNull ForeignConnection connection) {
        DataSourceProviderRegistry providers = DataSourceProviderRegistry.getInstance();
        if (!CommonUtils.isEmpty(connection.getDriverId())) {
            DBPDriver driver = usable(providers.findDriver(connection.getDriverId()));
            if (driver != null) {
                return driver;
            }
        }
        String urlType = getUrlType(connection.getUrl());
        if (!CommonUtils.isEmpty(connection.getType())) {
            DBPDriver driver = findDriverByName(connection.getType());
            if (driver != null) {
                return driver;
            }
        }
        if (!CommonUtils.isEmpty(connection.getDriverClass())) {
            DBPDriver driver = findDriverByClass(connection.getDriverClass(), urlType);
            if (driver != null) {
                return driver;
            }
        }
        if (urlType != null) {
            return findDriverByName(urlType);
        }
        return null;
    }

    /**
     * By alias, driver ID / "provider:driver" / driver name, or the first enabled driver of the provider with that ID.
     */
    @Nullable
    private static DBPDriver findDriverByName(@NotNull String name) {
        DataSourceProviderRegistry providers = DataSourceProviderRegistry.getInstance();
        String alias = TYPE_ALIASES.get(normalize(name));
        if (alias != null) {
            DBPDriver driver = usable(providers.findDriver(alias));
            if (driver != null) {
                return driver;
            }
        }
        DBPDriver driver = usable(providers.findDriver(name.trim()));
        if (driver != null) {
            return driver;
        }
        DataSourceProviderDescriptor provider = providers.getDataSourceProvider(name.trim().toLowerCase(Locale.ENGLISH));
        if (provider != null) {
            for (DBPDriver providerDriver : provider.getEnabledDrivers()) {
                if (usable(providerDriver) != null) {
                    return providerDriver;
                }
            }
        }
        return null;
    }

    /**
     * An enabled driver with that class; among several, the one whose provider matches the URL type.
     */
    @Nullable
    private static DBPDriver findDriverByClass(@NotNull String driverClass, @Nullable String urlType) {
        DBPDriver first = null;
        for (DataSourceProviderDescriptor provider : DataSourceProviderRegistry.getInstance().getDataSourceProviders()) {
            for (DBPDriver driver : provider.getEnabledDrivers()) {
                if (usable(driver) == null || !driverClass.equals(driver.getDriverClassName())) {
                    continue;
                }
                if (urlType != null && provider.getId().equalsIgnoreCase(urlType)) {
                    return driver;
                }
                if (first == null) {
                    first = driver;
                }
            }
        }
        return first;
    }

    @Nullable
    private static DBPDriver usable(@Nullable DBPDriver driver) {
        return driver == null || driver.isDisabled() || driver.isTemporary() ? null : driver;
    }

    /**
     * The sub-protocol of a JDBC URL ("postgresql" for "jdbc:postgresql://…"), or null.
     */
    @Nullable
    static String getUrlType(@Nullable String url) {
        if (url == null || !url.regionMatches(true, 0, JDBC_PREFIX, 0, JDBC_PREFIX.length())) {
            return null;
        }
        int end = url.indexOf(':', JDBC_PREFIX.length());
        return end <= JDBC_PREFIX.length() ? null : url.substring(JDBC_PREFIX.length(), end);
    }

    @NotNull
    private static String getTypeKey(@NotNull ForeignConnection connection) {
        String key = CommonUtils.nullIfEmpty(normalize(CommonUtils.notEmpty(connection.getType())));
        if (key == null) {
            String urlType = getUrlType(connection.getUrl());
            key = urlType == null ? null : CommonUtils.nullIfEmpty(normalize(urlType));
        }
        return key == null ? DEFAULT_TYPE_KEY : key;
    }

    /**
     * The Generic driver of an unrecognized type: the one created by an earlier import, or a new one that is registered
     * only when a connection using it is imported.
     */
    @NotNull
    private static DBPDriver getImportedDriver(
        @NotNull ForeignConnection connection,
        @NotNull String typeKey,
        @NotNull Map<String, DriverDescriptor> pendingDrivers
    ) throws DBException {
        DataSourceProviderDescriptor provider = DataSourceProviderRegistry.getInstance().getDataSourceProvider(GENERIC_PROVIDER_ID);
        if (provider == null) {
            throw new DBException("The Generic JDBC provider is not installed");
        }
        String driverId = IMPORTED_DRIVER_PREFIX + typeKey;
        DBPDriver existing = provider.getOriginalDriver(driverId);
        if (existing != null) {
            return existing;
        }
        DriverDescriptor pending = pendingDrivers.get(driverId);
        if (pending == null) {
            pending = provider.createDriver(driverId);
            String type = CommonUtils.notEmpty(connection.getType()).trim();
            pending.setName(type.isEmpty() ? typeKey : type);
            pending.setDescription("Created by Import Connections for a database type that no installed driver matched. "
                + "Set the driver class and library before connecting.");
            if (!CommonUtils.isEmpty(connection.getDriverClass())) {
                pending.setDriverClassName(connection.getDriverClass(), true);
            }
            pending.setModified(true);
            pendingDrivers.put(driverId, pending);
        } else if (CommonUtils.isEmpty(pending.getDriverClassName()) && !CommonUtils.isEmpty(connection.getDriverClass())) {
            pending.setDriverClassName(connection.getDriverClass(), true);
        }
        return pending;
    }

    private static boolean isImportedDriver(@NotNull DBPDriver driver) {
        return GENERIC_PROVIDER_ID.equals(driver.getProviderId()) && driver.getId().startsWith(IMPORTED_DRIVER_PREFIX);
    }

    @NotNull
    private static String normalize(@NotNull String name) {
        StringBuilder key = new StringBuilder(name.length());
        for (char c : name.toLowerCase(Locale.ENGLISH).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                key.append(c);
            }
        }
        return key.toString();
    }

    //////////////////////////////////////////////////////////
    // Connection settings

    @NotNull
    private static DBPConnectionConfiguration createConfiguration(@NotNull ForeignConnection connection, @NotNull DBPDriver driver) {
        DBPConnectionConfiguration configuration = new DBPConnectionConfiguration();
        configuration.setHostName(connection.getHost());
        configuration.setHostPort(connection.getPort());
        configuration.setDatabaseName(connection.getDatabase());
        configuration.setConfigurationType(DBPDriverConfigurationType.MANUAL);
        String url = connection.getUrl();
        if (CommonUtils.isEmpty(connection.getHost()) && !CommonUtils.isEmpty(url)) {
            DBPConnectionConfiguration fromUrl = CommonUtils.isEmpty(driver.getSampleURL())
                ? null : DatabaseURL.extractConfigurationFromUrl(driver.getSampleURL(), url);
            if (fromUrl != null && !CommonUtils.isEmpty(fromUrl.getHostName())) {
                configuration.setHostName(fromUrl.getHostName());
                if (!CommonUtils.isEmpty(fromUrl.getHostPort())) {
                    configuration.setHostPort(fromUrl.getHostPort());
                }
                if (!CommonUtils.isEmpty(fromUrl.getDatabaseName())) {
                    configuration.setDatabaseName(fromUrl.getDatabaseName());
                }
            } else {
                configuration.setUrl(url);
                configuration.setConfigurationType(DBPDriverConfigurationType.URL);
            }
        }
        configuration.setUserName(connection.getUser());
        configuration.setUserPassword(connection.getPassword());
        if (!connection.getProviderProperties().isEmpty()) {
            configuration.setProviderProperties(connection.getProviderProperties());
        }
        for (DBWHandlerConfiguration handler : connection.getHandlers()) {
            configuration.updateHandler(handler);
        }
        return configuration;
    }
}
