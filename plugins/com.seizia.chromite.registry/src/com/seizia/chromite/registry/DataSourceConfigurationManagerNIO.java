/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceConfigurationStorage;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.utils.ContentUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * Configuration files manager on FS
 *
 * 변경 이력:
 * - 2026-11-07 프로젝트 폴더의 옛 XML 연결 설정({@code .chromite-data-sources*.xml}) 읽기를 지움. 내부 이름 변경(#59)으로
 *   찾는 이름이 어떤 빌드도 쓰지 않는 이름이 됐다(docs/chromite/internal-rename.md "사용자 데이터 이전")
 */
public class DataSourceConfigurationManagerNIO implements DataSourceConfigurationManager {

    private static final Log log = Log.getLog(DataSourceConfigurationManagerNIO.class);

    @NotNull
    private final DBPProject project;

    public DataSourceConfigurationManagerNIO(@NotNull DBPProject project) {
        this.project = project;
    }

    private Path getConfigurationPath(boolean create) {
        return project.getMetadataFolder(create);
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public List<DBPDataSourceConfigurationStorage> getConfigurationStorages() {
        List<DBPDataSourceConfigurationStorage> storages = new ArrayList<>();
        Path metadataFolder = project.getMetadataFolder(false);
        if (Files.exists(metadataFolder)) {
            try (Stream<Path> list = Files.list(metadataFolder)) {
                List<Path> mdFiles = list
                    .filter(path -> !Files.isDirectory(path) && Files.exists(path))
                    .toList();
                for (Path res : mdFiles) {
                    String fileName = res.getFileName().toString();
                    if (fileName.startsWith(DataSourceRegistry.MODERN_CONFIG_FILE_PREFIX) &&
                        fileName.endsWith(DataSourceRegistry.MODERN_CONFIG_FILE_EXT))
                    {
                        storages.add(new DataSourceFileStorage(res, fileName.equals(DataSourceRegistry.MODERN_CONFIG_FILE_NAME)));
                    }
                }
            } catch (IOException e) {
                log.error("Error during project files read", e);
            }
        }
        if (storages.isEmpty()) {
            storages.add(
                new DataSourceFileStorage(
                    metadataFolder.resolve(DataSourceRegistry.MODERN_CONFIG_FILE_NAME), true));
        }
        return storages;
    }

    @Override
    public InputStream readConfiguration(@NotNull String name, Collection<String> dataSourceIds) throws IOException {
        Path path = getConfigurationPath(false).resolve(name);
        if (Files.notExists(path)) {
            return null;
        }
        return Files.newInputStream(path);
    }

    @Override
    public void writeConfiguration(@NotNull String name, @Nullable byte[] data) throws IOException {
        Path configFile = getConfigurationPath(true).resolve(name);
        ContentUtils.makeFileBackup(configFile);

        if (data == null || data.length == 0) {
            if (Files.exists(configFile)) {
                try {
                    Files.delete(configFile);
                } catch (IOException e) {
                    log.debug("Error deleting file " + configFile.toAbsolutePath(), e);
                }
            }
        } else {
            Files.write(configFile, data);
        }
    }
}
