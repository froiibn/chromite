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

package com.seizia.chromite.model.app;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.*;
import com.seizia.chromite.model.access.DBAAuthProfile;
import com.seizia.chromite.model.access.DBACredentialsProvider;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.net.DBWNetworkProfile;
import com.seizia.chromite.model.net.DBWNetworkProfileManager;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.secret.DBPSecretHolder;
import com.seizia.chromite.model.struct.DBSObjectFilter;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Datasource registry.
 * Keeps all database connection information in a project
 *
 * 변경 이력:
 * - 2026-11-07 옛 XML 연결 설정({@code .chromite-data-sources*.xml}) 읽기를 지움. 내부 이름 변경(#59)으로 찾는 이름이
 *   어떤 빌드도 쓰지 않는 이름이 됐다(docs/chromite/internal-rename.md "사용자 데이터 이전")
 */
public interface DBPDataSourceRegistry extends DBPObject, DBPSecretHolder {

    String MODERN_CONFIG_FILE_PREFIX = "data-sources"; //$NON-NLS-1$
    String MODERN_CONFIG_FILE_EXT = ".json"; //$NON-NLS-1$
    String MODERN_CONFIG_FILE_NAME = MODERN_CONFIG_FILE_PREFIX + MODERN_CONFIG_FILE_EXT;
    String CREDENTIALS_CONFIG_FILE_PREFIX = "credentials-config"; //$NON-NLS-1$
    String CREDENTIALS_CONFIG_FILE_EXT = ".json"; //$NON-NLS-1$
    String CREDENTIALS_CONFIG_FILE_NAME = CREDENTIALS_CONFIG_FILE_PREFIX + CREDENTIALS_CONFIG_FILE_EXT;

    String LEGACY2_CONFIG_FILE_NAME = "data-sources.xml"; //$NON-NLS-1$

    /**
     * Owner project.
     */
    @NotNull
    DBPProject getProject();

    @Nullable
    DBPDataSourceContainer getDataSource(@NotNull String id);

    @Nullable
    DBPDataSourceContainer getDataSource(@NotNull DBPDataSource dataSource);

    @Nullable
    DBPDataSourceContainer findDataSourceByName(String name);

    @NotNull
    List<? extends DBPDataSourceContainer> getDataSourcesByProfile(@NotNull DBWNetworkProfile profile);

    @NotNull
    List<? extends DBPDataSourceContainer> getDataSources();

    @NotNull
    <T extends DBPDataSourceContainer> T createDataSource(
        @NotNull DBPDriver driver,
        @NotNull DBPConnectionConfiguration connConfig
    );

    <T extends DBPDataSourceContainer> T createDataSource(
        @NotNull String id,
        @NotNull DBPDriver driver,
        @NotNull DBPConnectionConfiguration connConfig
    );

    <T extends DBPDataSourceContainer> T createDataSource(
        @NotNull DBPDataSourceConfigurationStorage dataSourceStorage,
        @NotNull DBPDataSourceOrigin origin,
        @NotNull String id,
        @NotNull DBPDriver driver,
        @NotNull DBPConnectionConfiguration configuration
    );

    @NotNull
    <T extends DBPDataSourceContainer> T createDataSource(@NotNull DBPDataSourceContainer source);

    void addDataSourceListener(@NotNull DBPEventListener listener);

    boolean removeDataSourceListener(@NotNull DBPEventListener listener);

    void addDataSource(@NotNull DBPDataSourceContainer dataSource) throws DBException;

    void removeDataSource(@NotNull DBPDataSourceContainer dataSource);

    void updateDataSource(@NotNull DBPDataSourceContainer dataSource) throws DBException;

    default void updateDataSources(@NotNull List<? extends DBPDataSourceContainer> dataSources) throws DBException {
        for (DBPDataSourceContainer dataSource : dataSources) {
            updateDataSource(dataSource);
        }
    }

    default void updateDataSource(@NotNull DBPDataSourceContainer dataSource, boolean forcePersistSecrets) throws DBException {
        updateDataSource(dataSource);
    }

    @NotNull
    List<? extends DBPDataSourceFolder> getAllFolders();

    @NotNull
    List<? extends DBPDataSourceFolder> getRootFolders();

    @NotNull
    DBPDataSourceFolder getFolder(@NotNull String path);

    @NotNull
    DBPDataSourceFolder addFolder(@Nullable DBPDataSourceFolder parent, @NotNull String name);

    void removeFolder(@NotNull DBPDataSourceFolder folder, boolean dropContents);

    /**
     * Moves connection folder
     */
    void moveFolder(@NotNull String oldPath, @NotNull String newPath) throws DBException;

    @Nullable
    DBSObjectFilter getSavedFilter(String name);
    @NotNull
    List<DBSObjectFilter> getSavedFilters();
    void updateSavedFilter(@NotNull DBSObjectFilter filter);
    void removeSavedFilter(@NotNull String filterName);

    // Network profiles
    @NotNull
    DBWNetworkProfileManager getNetworkProfiles();

    // Auth profiles
    @Nullable
    DBAAuthProfile getAuthProfile(@NotNull String id);
    @NotNull
    List<DBAAuthProfile> getAllAuthProfiles();
    @NotNull
    List<DBAAuthProfile> getApplicableAuthProfiles(@Nullable DBPDriver driver);

    void updateAuthProfile(@NotNull DBAAuthProfile profile);
    void removeAuthProfile(@NotNull DBAAuthProfile profile);

    /**
     * Set collection of profiles.
     *
     * @param profiles - profile collection
     */
    void setAuthProfiles(@NotNull Collection<DBAAuthProfile> profiles);

    void flushConfig();
    void refreshConfig();

    /**
     * Refreshes configuration of specified data sources
     */
    void refreshConfig(@Nullable Collection<String> dataSourceIds);

    /**
     * Returns and nullifies last registry save/load error.
     */
    @Nullable
    Throwable getLastError();

    boolean hasError();

    /**
     * Throws last occurred load/save error
     */
    void checkForErrors() throws DBException;

    void notifyDataSourceListeners(@NotNull DBPEvent event);

    // Registry auth provider. Null by default.
    @Nullable
    DBACredentialsProvider getAuthCredentialsProvider();

    /**
     * Sets auth credentials provider to the registry.
     */
    void setAuthCredentialsProvider(@Nullable DBACredentialsProvider authCredentialsProvider);

    /**
     * Returns all folders having temporary connections.
     */
    @NotNull
    Set<DBPDataSourceFolder> getTemporaryFolders();

    @NotNull
    DBPPreferenceStore getPreferenceStore();

    // Can be called only once
    // FIXME: move it in another abstraction
    void initializeDataSources();

    void dispose();
}
