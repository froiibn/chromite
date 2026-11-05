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
package com.seizia.chromite.ui.swing;

import org.eclipse.core.runtime.Plugin;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.DBPExternalFileManager;
import org.jkiss.dbeaver.model.app.DBACertificateStorage;
import org.jkiss.dbeaver.model.app.DBPApplicationDesktop;
import org.jkiss.dbeaver.model.app.DBPGlobalEventManager;
import org.jkiss.dbeaver.model.app.DBPPlatformDesktop;
import org.jkiss.dbeaver.model.app.DBPWorkspaceDesktop;
import org.jkiss.dbeaver.model.impl.app.BaseApplicationImpl;
import org.jkiss.dbeaver.model.impl.app.DefaultCertificateStorage;
import org.jkiss.dbeaver.model.navigator.DBNModel;
import org.jkiss.dbeaver.model.navigator.DesktopNavigatorModel;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.qm.QMUtils;
import org.jkiss.dbeaver.model.runtime.LoggingProgressMonitor;
import org.jkiss.dbeaver.registry.BasePlatformImpl;
import org.jkiss.dbeaver.registry.DataSourceProviderRegistry;
import org.jkiss.dbeaver.registry.GlobalEventManagerImpl;
import org.jkiss.dbeaver.utils.RuntimeUtils;

/**
 * Swing 프런트엔드용 DBeaver 플랫폼 구현.
 *
 * 설계: SWT 버전의 {@code DesktopPlatform}(org.jkiss.dbeaver.core)은 Eclipse UI({@code PlatformUI})에 의존해서
 * 쓸 수 없다. 그래서 같은 초기화 순서(인증서 저장소 → 작업 공간 → 프로젝트 → Query Manager → 기반 플랫폼)를
 * UI 의존 없이 다시 구현한다. 작업 공간은 {@code DesktopApplicationImpl#createWorkspace}가 만드는
 * {@code DesktopWorkspaceImpl}을 그대로 써서 SWT 버전과 같은 작업 공간 형식(연결 목록 포함)을 공유한다.
 * 탐색기 모델도 SWT 버전과 같은 {@link DesktopNavigatorModel}을 써서 B단계 Object Explorer가 그대로 쓸 수 있게 한다.
 *
 * 변경 이력:
 * - 2026-11-06 A단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — A단계")
 */
public class ChromiteSwingPlatform extends BasePlatformImpl implements DBPPlatformDesktop {

    private static final Log log = Log.getLog(ChromiteSwingPlatform.class);

    private DBPWorkspaceDesktop workspace;
    private DBACertificateStorage certificateStorage;
    private volatile boolean closing;
    private volatile boolean workbenchStarted;

    // Instantiated reflectively by AbstractApplication.initializeApplicationServices()
    ChromiteSwingPlatform() {
    }

    @Override
    protected void initialize() throws DBException {
        long startTime = System.currentTimeMillis();
        log.debug("Initialize Swing platform...");

        this.certificateStorage = new DefaultCertificateStorage(
            this,
            RuntimeUtils.getPluginStateLocation(ChromiteSwingActivator.getInstance())
                .resolve(DBConstants.CERTIFICATE_STORAGE_FOLDER));

        getApplication().beforeWorkspaceInitialization();
        this.workspace = getApplication().createWorkspace(this);
        this.workspace.initializeProjects();

        QMUtils.initPlatform(true);

        super.initialize();

        log.debug("Swing platform initialized (" + (System.currentTimeMillis() - startTime) + "ms)");
    }

    @Override
    protected DBNModel createNavigatorModel() {
        return new DesktopNavigatorModel(this, null);
    }

    public synchronized void dispose() {
        long startTime = System.currentTimeMillis();
        log.debug("Shutdown Swing platform...");

        closing = true;

        super.dispose();

        if (workspace != null) {
            // In the SWT product the IDE workbench advisor saves the Eclipse workspace on shutdown;
            // without it core.resources reports "The workspace will exit with unsaved changes".
            try {
                workspace.save(new LoggingProgressMonitor(log));
            } catch (DBException e) {
                log.error("Can not save workspace", e);
            }
            workspace.dispose();
        }

        QMUtils.disposePlatform();
        DataSourceProviderRegistry.dispose();

        log.debug("Swing platform shutdown completed (" + (System.currentTimeMillis() - startTime) + "ms)");
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
        return workbenchStarted;
    }

    /**
     * Set by the application while the main window is open, like {@code DesktopPlatform#setWorkbenchStarted}.
     */
    void setWorkbenchStarted(boolean started) {
        this.workbenchStarted = started;
    }

    @Override
    public boolean isShuttingDown() {
        return closing;
    }

    @Override
    protected Plugin getProductPlugin() {
        return ChromiteSwingActivator.getInstance();
    }
}
