/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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

package com.seizia.chromite.headless;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Plugin;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPExternalFileManager;
import com.seizia.chromite.model.app.*;
import com.seizia.chromite.model.impl.app.BaseApplicationImpl;
import com.seizia.chromite.model.impl.app.DefaultCertificateStorage;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.registry.BasePlatformImpl;
import com.seizia.chromite.registry.DataSourceProviderRegistry;
import com.seizia.chromite.registry.GlobalEventManagerImpl;
import com.seizia.chromite.registry.language.PlatformLanguageRegistry;
import java.util.Locale;

/**
 * ChromiteTestPlatform
 */
public class ChromiteTestPlatform extends BasePlatformImpl implements DBPPlatformDesktop {

    public static final String PLUGIN_ID = "com.seizia.chromite.headless"; //$NON-NLS-1$
    private static final Log log = Log.getLog(ChromiteTestPlatform.class);

    static ChromiteTestPlatform instance;

    private static volatile boolean isClosing = false;

    private ChromiteTestWorkspace workspace;

    private static boolean disposed = false;
    private DefaultCertificateStorage defaultCertificateStorage;

    public static String getCorePluginID() {
        return PLUGIN_ID;
    }

    public static boolean isStandalone() {
        return BaseApplicationImpl.getInstance().isStandalone();
    }

    public static boolean isClosing() {
        return isClosing;
    }

    private static void setClosing(boolean closing) {
        isClosing = closing;
    }

    ChromiteTestPlatform() {
    }

    protected void initialize() throws DBException {
        long startTime = System.currentTimeMillis();
        log.debug("Initialize Test Platform...");

        this.defaultCertificateStorage = new DefaultCertificateStorage(
            this,
            ChromiteTestActivator.getConfigurationFile(DBConstants.CERTIFICATE_STORAGE_FOLDER).toPath());

        // Register properties adapter
        this.workspace = new ChromiteTestWorkspace(this, ResourcesPlugin.getWorkspace());
        this.workspace.initializeProjects();

        QMUtils.initPlatform(false);

        super.initialize();

        log.debug("Test Platform initialized (" + (System.currentTimeMillis() - startTime) + "ms)");
    }

    public synchronized void dispose() {
        long startTime = System.currentTimeMillis();
        log.debug("Shutdown Core...");

        ChromiteTestPlatform.setClosing(true);

        super.dispose();

        workspace.dispose();

        QMUtils.disposePlatform();
        DataSourceProviderRegistry.dispose();

        ChromiteTestPlatform.instance = null;
        ChromiteTestPlatform.disposed = true;
        System.gc();
        log.debug("Test platform shutdown completed in " + (System.currentTimeMillis() - startTime) + "ms");
    }

    @NotNull
    @Override
    public DBPWorkspaceDesktop getWorkspace() {
        return workspace;
    }

    @NotNull
    @Override
    public DBPPlatformLanguage getPlatformLanguage() {
        return PlatformLanguageRegistry.getInstance().getLanguage(Locale.ENGLISH);
    }

    @NotNull
    @Override
    public ChromiteHeadlessApplication getApplication() {
        return (ChromiteHeadlessApplication) BaseApplicationImpl.getInstance();
    }

    @NotNull
    @Override
    public DBPGlobalEventManager getGlobalEventManager() {
        return GlobalEventManagerImpl.getInstance();
    }

    @NotNull
    @Override
    public DBPPreferenceStore getPreferenceStore() {
        return getApplication().getPreferenceStore();
    }

    @NotNull
    @Override
    public DBACertificateStorage getCertificateStorage() {
        return defaultCertificateStorage;
    }

    @NotNull
    @Override
    public DBPExternalFileManager getExternalFileManager() {
        return workspace;
    }

    @Override
    public boolean isWorkbenchStarted() {
        return true;
    }

    @Override
    protected Plugin getProductPlugin() {
        return ChromiteTestActivator.getInstance();
    }

    @Override
    public boolean isShuttingDown() {
        return isClosing();
    }

    @Override
    public boolean isUnitTestMode() {
        return true;
    }
}
