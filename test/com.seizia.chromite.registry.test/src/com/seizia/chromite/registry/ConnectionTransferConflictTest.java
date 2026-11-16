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

import com.google.gson.JsonObject;
import com.seizia.chromite.junit.ChromiteUnitTest;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Regression tests of the conflict rules of the connection import ({@link ConnectionTransfer#readFile},
 * {@link ConnectionTransfer#importConnections}).
 *
 * 설계: 시험 작업 공간의 실제 레지스트리에 연결 하나를 더하고, 같은 ID·같은 폴더의 같은 이름·다른 폴더의 같은 이름·새 연결이 든
 * 평문 파일을 읽어 미리 보기의 충돌 판단을 본다. 가져오기는 충돌하는 연결만 든 파일로 해서, 건너뛰기와 "연결 중이면 바꾸지 않음"이
 * 레지스트리를 바꾸지 않는지 본다. 사본 가져오기만 실제로 연결을 더하고 끝에 지운다. 연결 중인 연결은 실제 연결 대신 spy로 만든다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "연결 내보내기 파일")
 */
public class ConnectionTransferConflictTest extends ChromiteUnitTest {

    private static final String EXISTING_ID = "transfer-test-existing";
    private static final String EXISTING_NAME = "Transfer Test Shop";

    @TempDir
    Path temp;

    private DBPDataSourceRegistry registry;
    private DBPDriver driver;
    private final List<DBPDataSourceContainer> added = new ArrayList<>();

    @BeforeEach
    public void findRegistryAndDriver() {
        registry = DBWorkbench.getPlatform().getWorkspace().getActiveProject().getDataSourceRegistry();
        driver = DBWorkbench.getPlatform().getDataSourceProviderRegistry().findDriver("sqlite_jdbc");
        Assertions.assertNotNull(driver);
    }

    @AfterEach
    public void removeAddedConnections() throws Exception {
        for (DBPDataSourceContainer container : added) {
            registry.removeDataSource(container);
        }
        added.clear();
    }

    @Test
    public void previewFindsSameIdAndSameNameInTheSameFolderOnly() throws Exception {
        add(existing(false));
        Path file = writeFile(Map.of(
            EXISTING_ID, "Renamed in the file",
            "transfer-test-same-name", EXISTING_NAME,
            "transfer-test-new", "Transfer Test Fresh"), Map.of("transfer-test-other-folder", EXISTING_NAME));

        ConnectionTransfer.ImportFile importFile = ConnectionTransfer.readFile(file, null, registry);
        Map<String, ConnectionTransfer.ConflictKind> conflicts = importFile.getEntries().stream()
            .collect(Collectors.toMap(ConnectionTransfer.ImportEntry::getId, ConnectionTransfer.ImportEntry::getConflict));

        Assertions.assertFalse(importFile.hasSecrets());
        Assertions.assertEquals(ConnectionTransfer.ConflictKind.SAME_ID, conflicts.get(EXISTING_ID));
        Assertions.assertEquals(ConnectionTransfer.ConflictKind.SAME_NAME, conflicts.get("transfer-test-same-name"));
        // The same name in another folder is not a conflict
        Assertions.assertEquals(ConnectionTransfer.ConflictKind.NONE, conflicts.get("transfer-test-other-folder"));
        Assertions.assertEquals(ConnectionTransfer.ConflictKind.NONE, conflicts.get("transfer-test-new"));
    }

    @Test
    public void conflictsWithoutAnActionAreSkippedAndAConnectedConnectionIsNeverReplaced() throws Exception {
        add(existing(true));
        Path file = writeFile(Map.of(EXISTING_ID, "Renamed in the file", "transfer-test-same-name", EXISTING_NAME), Map.of());
        ConnectionTransfer.ImportFile importFile = ConnectionTransfer.readFile(file, null, registry);
        int before = registry.getDataSources().size();

        ConnectionTransfer.ImportResult skipped = ConnectionTransfer.importConnections(monitor, registry, importFile, Map.of());
        Assertions.assertEquals(2, skipped.getSkipped());
        Assertions.assertEquals(0, skipped.getAdded() + skipped.getReplaced() + skipped.getCopied());

        ConnectionTransfer.ImportResult connected = ConnectionTransfer.importConnections(monitor, registry, importFile,
            Map.of(EXISTING_ID, ConnectionTransfer.ConflictAction.REPLACE, "transfer-test-same-name", ConnectionTransfer.ConflictAction.SKIP));
        Assertions.assertEquals(List.of("Renamed in the file"), connected.getSkippedConnected());
        Assertions.assertEquals(1, connected.getSkipped());
        Assertions.assertEquals(0, connected.getReplaced());

        Assertions.assertEquals(before, registry.getDataSources().size());
        Assertions.assertEquals(EXISTING_NAME, registry.getDataSource(EXISTING_ID).getName());
    }

    @Test
    public void copyGetsANewIdAndTheNextFreeName() throws Exception {
        add(existing(false));
        Path file = writeFile(Map.of("transfer-test-same-name", EXISTING_NAME), Map.of());
        ConnectionTransfer.ImportFile importFile = ConnectionTransfer.readFile(file, null, registry);

        ConnectionTransfer.ImportResult result = ConnectionTransfer.importConnections(monitor, registry, importFile,
            Map.of("transfer-test-same-name", ConnectionTransfer.ConflictAction.COPY));
        DBPDataSourceContainer copy = registry.getDataSources().stream()
            .filter(c -> (EXISTING_NAME + " (2)").equals(c.getName())).findFirst().orElse(null);
        if (copy != null) {
            added.add(copy);
        }

        Assertions.assertEquals(1, result.getCopied());
        Assertions.assertNotNull(copy, "copy named \"" + EXISTING_NAME + " (2)\"");
        Assertions.assertNotEquals("transfer-test-same-name", copy.getId());
        Assertions.assertNull(registry.getDataSource("transfer-test-same-name"));
    }

    @NotNull
    private DataSourceDescriptor existing(boolean connected) {
        DataSourceDescriptor descriptor = new DataSourceDescriptor(registry, EXISTING_ID, driver, new DBPConnectionConfiguration());
        descriptor.setName(EXISTING_NAME);
        if (!connected) {
            return descriptor;
        }
        DataSourceDescriptor spy = Mockito.spy(descriptor);
        Mockito.doReturn(true).when(spy).isConnected();
        return spy;
    }

    private void add(@NotNull DBPDataSourceContainer container) throws Exception {
        registry.addDataSource(container);
        added.add(container);
    }

    /**
     * A plain (unencrypted) transfer file with connections in no folder and in folder "other".
     */
    @NotNull
    private Path writeFile(@NotNull Map<String, String> rootConnections, @NotNull Map<String, String> otherFolderConnections) throws Exception {
        JsonObject connections = new JsonObject();
        rootConnections.forEach((id, name) -> connections.add(id, connection(name, null)));
        otherFolderConnections.forEach((id, name) -> connections.add(id, connection(name, "other")));
        JsonObject configuration = new JsonObject();
        JsonObject folders = new JsonObject();
        folders.add("other", new JsonObject());
        configuration.add("folders", folders);
        configuration.add("connections", connections);
        JsonObject payload = new JsonObject();
        payload.addProperty("created", "2026-11-08T00:00:00Z");
        payload.addProperty("secrets", false);
        payload.add("configuration", configuration);
        Path file = temp.resolve("connections." + ConnectionTransferFile.FILE_EXTENSION);
        Files.write(file, ConnectionTransferFile.write(payload.toString().getBytes(StandardCharsets.UTF_8), null));
        return file;
    }

    @NotNull
    private JsonObject connection(@NotNull String name, @Nullable String folder) {
        JsonObject connection = new JsonObject();
        connection.addProperty("provider", driver.getProviderId());
        connection.addProperty("driver", driver.getId());
        connection.addProperty("name", name);
        if (folder != null) {
            connection.addProperty("folder", folder);
        }
        JsonObject configuration = new JsonObject();
        configuration.addProperty("database", "transfer-test.db");
        configuration.addProperty("type", "dev");
        connection.add("configuration", configuration);
        return connection;
    }
}
