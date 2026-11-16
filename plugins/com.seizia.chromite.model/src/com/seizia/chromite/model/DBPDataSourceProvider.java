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
package com.seizia.chromite.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.connection.DBPAuthModelDescriptor;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

/**
 * Data source provider
 *
 * 설계: 연결이 가리키는 서버 글({@link #getConnectionAddress})은 URL을 만드는 공급자가 정한다. 기본은 저장된 호스트[:포트]이고, 주소를 다른 값으로
 * 만드는 공급자(Oracle TNS: 네트워크 별칭)만 바꾼다. 레지스트리·화면·에이전트가 모두 이것을 읽어 같은 글을 보인다.
 *
 * 변경 이력:
 * - 2026-11-16 {@link #getConnectionAddress}(앱 변경 #135, docs/chromite/swing-conn-advanced.md "Oracle TNS 연결")
 */
public interface DBPDataSourceProvider<DATASOURCE extends DBPDataSource> extends DBPDataSourceURLProvider, DBPObject {
    long FEATURE_NONE        = 0;
    long FEATURE_CATALOGS    = 1;
    long FEATURE_SCHEMAS     = 2;
    long FEATURE_CATALOGS_ONLY = 4;

    /**
     * Initializes data source provider
     * @param platform application
     */
    void init(@NotNull DBPPlatform platform);

    /**
     * Supported features
     * @return features
     */
    long getFeatures();
    
    /**
     * Supported connection properties.
     *
     * @param monitor progress monitor
     * @param driver driver
     * @param connectionInfo connection information   @return property group which contains all supported properties
     * @throws DBException on any error
     */
    @NotNull
    DBPPropertyDescriptor[] getConnectionProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDriver driver,
        @Nullable DBPDataSourceContainer dataSourceContainer,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) throws DBException;

    /**
     * Class of datasource.
     * It is used for static examining of database structure
     */
    @NotNull
    Class<? extends DATASOURCE> getDataSourceClass();

    /**
     * Opens new data source
     * @param monitor progress monitor
     * @param container data source container
     * @return new data source object
     * @throws DBException on any error
     */
    @NotNull
    DATASOURCE openDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container)
        throws DBException;

    @NotNull
    default DBPAuthModelDescriptor detectConnectionAuthModel(
        @NotNull DBPDriver driver,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) {
        DBPAuthModelDescriptor am = connectionInfo.getAuthModelDescriptor();
        DBPAuthModelDescriptor ram = am.getReplacedBy(driver);
        if (ram != null) {
            return ram;
        }
        return am;
    }

    default boolean providesDriverClasses(@NotNull DBPDriver driver) {
        return true;
    }

    /**
     * The server a saved connection points at, as every place that shows a server shows it: {@code host[:port]} of the
     * configuration, or {@code null} when it has no host (a file database, a URL the driver's template can't read).
     * A provider whose URL is not made from the host and port returns what it is made from instead.
     */
    @Nullable
    default String getConnectionAddress(@NotNull DBPConnectionConfiguration connectionInfo) {
        String host = connectionInfo.getHostName();
        if (CommonUtils.isEmpty(host)) {
            return null;
        }
        String port = connectionInfo.getHostPort();
        return CommonUtils.isEmpty(port) ? host : host + ":" + port; //$NON-NLS-1$
    }

}
