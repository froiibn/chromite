/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
import com.seizia.chromite.model.DBPDataSourceConfigurationStorage;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;

import java.nio.file.Path;

/**
 * DataSourceStorage
 *
 * 변경 이력:
 * - 2026-11-07 옛 XML 연결 설정({@code .chromite-data-sources*.xml}) 읽기를 지움. 내부 이름 변경(#59)으로 찾는 이름이
 *   어떤 빌드도 쓰지 않는 이름이 됐다(docs/chromite/internal-rename.md "사용자 데이터 이전")
 */
class DataSourceFileStorage implements DBPDataSourceConfigurationStorage
{
    private Path sourceFile;
    private boolean isDefault;
    private String configSuffix;

    DataSourceFileStorage(Path sourceFile, boolean isDefault) {
        this.sourceFile = sourceFile;
        this.isDefault = isDefault;

        if (isDefault) {
            configSuffix = "";
        } else {
            String configFileName = sourceFile.getFileName().toString();
            configSuffix = configFileName.substring(
                DBPDataSourceRegistry.MODERN_CONFIG_FILE_PREFIX.length(),
                configFileName.length() - DBPDataSourceRegistry.MODERN_CONFIG_FILE_EXT.length());
        }

    }

    @NotNull
    @Override
    public String getStorageId() {
        return "file://" + sourceFile.toAbsolutePath();
    }

    @NotNull
    @Override
    public String getStorageName() {
        return sourceFile.getFileName().toString();
    }

    @Override
    public boolean isValid() {
        return true;
    }

    public String getName() {
        return sourceFile.getFileName().toString();
    }

    @Nullable
    public String getStorageSubId() {
        return configSuffix;
    }

    @Override
    public boolean isDefault() {
        return isDefault;
    }

    @Override
    public boolean isVirtual() {
        return false;
    }

    public Path getSourceFile() {
        return sourceFile;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DataSourceFileStorage &&
            sourceFile.equals(((DataSourceFileStorage) obj).sourceFile);
    }

    @Override
    public int hashCode() {
        return sourceFile.hashCode();
    }

    @Override
    public String toString() {
        return sourceFile.toAbsolutePath().toString();
    }
}
