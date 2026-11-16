/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.registry.driver;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceProvider;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

/**
 * MissingDataSourceProvider
 *
 * 설계: 저장된 연결이 가리키는 데이터 소스 공급자를 찾지 못했을 때 임시 공급자에 붙는 구현이다.
 * 연결을 열면 원인을 알리는 오류만 낸다.
 *
 * 변경 이력:
 * - 2026-11-06 오류 문구에서 "DBeaver" 판 이름을 빼고 "a different edition of the product"로 일반화(Chromite).
 */
public class MissingDataSourceProvider implements DBPDataSourceProvider<DBPDataSource> {

    public MissingDataSourceProvider() {
    }

    @Override
    public void init(@NotNull DBPPlatform platform) {

    }

    @Override
    public long getFeatures() {
        return 0;
    }

    @NotNull
    @Override
    public DBPPropertyDescriptor[] getConnectionProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDriver driver,
        @Nullable DBPDataSourceContainer dataSourceContainer,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) throws DBException {
        return new DBPPropertyDescriptor[0];
    }

    @NotNull
    @Override
    public Class<DBPDataSource> getDataSourceClass() {
        return DBPDataSource.class;
    }

    @NotNull
    @Override
    public DBPDataSource openDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container
    ) throws DBException {
        throw new DBException("Error: invalid datasource configuration:\n"
                + "The specified driver '" + container.getDriver().getFullName() + "' could not be found.\n"
                + "This error may occur if the configuration was created in a different edition of the product.");
    }

    @NotNull
    @Override
    public String getConnectionURL(@NotNull DBPDriver driver, @NotNull DBPConnectionConfiguration connectionInfo) {
        return connectionInfo.getUrl();
    }

}
