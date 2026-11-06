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

package com.seizia.chromite.core;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.ui.PlatformUI;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ChromitePreferences;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPExternalFileManager;
import com.seizia.chromite.model.app.*;
import com.seizia.chromite.model.impl.app.BaseApplicationImpl;
import com.seizia.chromite.model.impl.app.DefaultCertificateStorage;
import com.seizia.chromite.model.navigator.DBNModel;
import com.seizia.chromite.model.navigator.DesktopNavigatorModel;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.runtime.LoggingProgressMonitor;
import com.seizia.chromite.registry.BasePlatformImpl;
import com.seizia.chromite.registry.DataSourceProviderRegistry;
import com.seizia.chromite.registry.GlobalEventManagerImpl;
import com.seizia.chromite.runtime.SecurityProviderUtils;
import com.seizia.chromite.utils.RuntimeUtils;

/**
 * DesktopPlatform
 *
 * 설계: Chromite는 사용 통계를 수집하지 않으므로 플랫폼 초기화 때 기능 추적을 시작하지 않는다.
 *
 * 변경 이력:
 * - 2026-11-06 사용 통계 기능 제거: initialize()의 DBRFeatureRegistry.startTracking() 호출 삭제
 *   (docs/chromite/telemetry.md)
 */
public class DesktopPlatform extends BasePlatformImpl implements DBPPlatformDesktop {

    // The plug-in ID
    public static final String PLUGIN_ID = "com.seizia.chromite.core"; //$NON-NLS-1$

    private static final String CHROMITE_CONFIG_FOLDER = "settings";

    private static final Log log = Log.getLog(DesktopPlatform.class);

    static DesktopPlatform instance;

    private static volatile boolean isClosing = false;

    private DBPWorkspaceDesktop workspace;
    private DBACertificateStorage certificateStorage;
    private volatile boolean workbenchStarted;

    public static boolean isStandalone() {
        return BaseApplicationImpl.getInstance().isStandalone();
    }

    public static boolean isClosing() {
        if (isClosing) {
            return true;
        }
        if (!PlatformUI.isWorkbenchRunning()) {
            return false;
        }
        return false;
    }

    static void setClosing(boolean closing) {
        isClosing = closing;
    }

    public DesktopPlatform() {
        instance = this;
    }

    protected void initialize() throws DBException {
        long startTime = System.currentTimeMillis();
        log.debug("Initialize desktop platform...");

        if (getPreferenceStore().getBoolean(ChromitePreferences.SECURITY_USE_BOUNCY_CASTLE)) {
            // Register BC security provider
            SecurityProviderUtils.registerSecurityProvider();
        }

        this.certificateStorage = new DefaultCertificateStorage(
            this,
            RuntimeUtils.getPluginStateLocation(ChromiteActivator.getInstance())
                .resolve(DBConstants.CERTIFICATE_STORAGE_FOLDER));

        // Create workspace
        getApplication().beforeWorkspaceInitialization();

        this.workspace = getApplication().createWorkspace(this);

        // Init workspace in UI because it may need some UI interactions to initialize
        this.workspace.initializeProjects();

        QMUtils.initPlatform(true);

        super.initialize();

        log.debug("Platform initialized (" + (System.currentTimeMillis() - startTime) + "ms)");
    }

    @Override
    protected DBNModel createNavigatorModel() {
        return new DesktopNavigatorModel(this, null);
    }

    public synchronized void dispose() {
        long startTime = System.currentTimeMillis();
        log.debug("Shutdown desktop platform...");

        DesktopPlatform.setClosing(true);
        DBPApplication application = getApplication();
        if (application instanceof DBPApplicationController ac) {
            // Shutdown in headless mode
            ac.setHeadlessMode(true);
        }

        super.dispose();

        if (workspace != null) {
            workspace.dispose();
            workspace = null;
        }

        QMUtils.disposePlatform();
        DataSourceProviderRegistry.dispose();

        if (isStandalone() && workspace != null && !application.isExclusiveMode()) {
            try {
                workspace.save(new LoggingProgressMonitor(log));
            } catch (DBException ex) {
                log.error("Can not save workspace", ex); //$NON-NLS-1$
            }
        }

        DesktopPlatform.instance = null;
        System.gc();
        log.debug("Platform shutdown completed (" + (System.currentTimeMillis() - startTime) + "ms)");
        // Just in case do System.eis after pause
        new Thread(() -> {
            RuntimeUtils.pause(10000);
            System.out.println("App shutdown was halted. Force system shutdown!");
            System.exit(-2);
        }).start();
    }

    @Override
    protected Plugin getProductPlugin() {
        return ChromiteActivator.getInstance();
    }

    @NotNull
    @Override
    public DBPWorkspaceDesktop getWorkspace() {
        return workspace;
    }

    @NotNull
    @Override
    public DBPApplicationDesktop getApplication() {
        return (DBPApplicationDesktop) BaseApplicationImpl.getInstance();
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
        return certificateStorage;
    }

    @NotNull
    @Override
    public DBPExternalFileManager getExternalFileManager() {
        return workspace;
    }

    @Override
    public boolean isWorkbenchStarted() {
        // In plugin mode it is always true
        // We don't have any specific security providers which are activated during startup so it is safe
        return workbenchStarted || !getApplication().isStandalone();
    }

    public void setWorkbenchStarted(boolean started) {
        this.workbenchStarted = started;
    }

    @Override
    public boolean isShuttingDown() {
        return isClosing();
    }

}
