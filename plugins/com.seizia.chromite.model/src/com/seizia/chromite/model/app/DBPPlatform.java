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
import com.seizia.chromite.model.DBConfigurationController;
import com.seizia.chromite.model.DBFileController;
import com.seizia.chromite.model.connection.DBPDataSourceProviderRegistry;
import com.seizia.chromite.model.data.DBDRegistry;
import com.seizia.chromite.model.edit.DBERegistry;
import com.seizia.chromite.model.fs.DBFRegistry;
import com.seizia.chromite.model.navigator.DBNModel;
import com.seizia.chromite.model.net.DBWHandlerRegistry;
import com.seizia.chromite.model.net.DBWNetworkProfileManager;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.OSDescriptor;
import com.seizia.chromite.model.sql.SQLDialectMetadataRegistry;
import com.seizia.chromite.model.task.DBTTaskController;

import java.io.IOException;
import java.nio.file.Path;

/**
 * DBPPlatform
 */
public interface DBPPlatform {

    @NotNull
    DBPApplication getApplication();

    @NotNull
    DBPWorkspace getWorkspace();

    @NotNull
    String getDeploymentId();

    // Deprecated? use navigator model from DBPProject when possible
    // But we still use it in desktop for global tree.
    @NotNull
    DBNModel getNavigatorModel();

    @NotNull
    DBPDataSourceProviderRegistry getDataSourceProviderRegistry();

    @NotNull
    OSDescriptor getLocalSystem();

    @NotNull
    DBDRegistry getValueHandlerRegistry();

    @NotNull
    DBERegistry getEditorsRegistry();

    @NotNull
    DBFRegistry getFileSystemRegistry();

    @NotNull
    SQLDialectMetadataRegistry getSQLDialectRegistry();

    @NotNull
    DBWHandlerRegistry getNetworkHandlerRegistry();
    @NotNull
    DBWNetworkProfileManager getNetworkProfiles();

    @NotNull
    DBPPreferenceStore getPreferenceStore();

    @NotNull
    DBACertificateStorage getCertificateStorage();

    @NotNull
    Path getTempFolder(@NotNull DBRProgressMonitor monitor, @NotNull String name) throws IOException;

    /**
     * Returns platform configuration controller,
     * which keeps configuration which can be shared with other users.
     */
    @NotNull
    DBConfigurationController getConfigurationController();
    
    /**
     * Returns configuration controller,
     * which keeps product configuration which can be shared with other users.
     */
    @NotNull
    DBConfigurationController getProductConfigurationController();
    
    /**
     * Returns configuration controller,
     * which keeps plugin configuration which can be shared with other users.
     */
    @NotNull
    DBConfigurationController getPluginConfigurationController(@Nullable String pluginId);

    /**
     * Local config files are used to store some configuration specific to local machine and active workspace.
     */
    @NotNull
    Path getLocalConfigurationFile(@NotNull String fileName);

    /**
     * Global config files are used to store some configuration which can
     * be shared between different workspaces, but still specific to local machine.
     *
     * @param fileName name of the file
     * @return path to the file in global configuration folder
     */
    @NotNull
    Path getGlobalConfigurationFile(@NotNull String fileName);

    /**
     * File controller allows to read/write binary files (e.g. custom driver libraries)
     */
    @NotNull
    DBFileController getFileController();

    /**
     * Task controller can read and change tasks configuration file
     */
    @NotNull
    DBTTaskController getTaskController();

    @Deprecated
    @NotNull
    Path getApplicationConfiguration();


    @NotNull
    DBPDataFormatterRegistry getDataFormatterRegistry();

    boolean isShuttingDown();

    default boolean isUnitTestMode() {
        return false;
    }
}
