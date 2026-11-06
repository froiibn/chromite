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
package com.seizia.chromite.model.rcp;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.app.DBPApplicationDesktop;
import com.seizia.chromite.model.app.DBPLockManagerProvider;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.app.DBPWorkspaceDesktop;
import com.seizia.chromite.model.fs.lock.LockManager;
import com.seizia.chromite.model.fs.lock.local.LocalFileLockManager;
import com.seizia.chromite.model.impl.app.BaseApplicationImpl;
import com.seizia.chromite.utils.GeneralUtils;

import java.nio.file.Path;

/**
 * DesktopApplicationImpl
 *
 * 변경 이력:
 * - 2026-11-07 작업 공간을 읽기 전에 이름 변경 전 빌드의 사용자 데이터를 복사함({@link LegacyNameMigration},
 *   docs/chromite/internal-rename.md "사용자 데이터 이전")
 */
public abstract class DesktopApplicationImpl extends BaseApplicationImpl implements DBPApplicationDesktop, DBPLockManagerProvider {

    public static final String WORKSPACE_PLUGINS_FOLDER = ".plugins";
    public static final String CORE_RUNTIME_PLUGIN_ID = "org.eclipse.core.runtime";
    public static final String CORE_RESOURCES_PLUGIN_ID = "org.eclipse.core.resources";
    public static final String CORE_FILESYSTEM_PLUGIN_ID = "org.eclipse.core.filesystem";

    private boolean isForcedRestart = false;

    public DesktopApplicationImpl() {
    }

    public DesktopApplicationImpl(@NotNull String defaultWorkspaceLocation, @NotNull String defaultAppWorkspaceName) {
        super(defaultWorkspaceLocation, defaultAppWorkspaceName);
    }

    /**
     * Runs before the platform creates the workspace, so projects, their connection registries and the preferences
     * of renamed bundles are read only after the data of builds before the internal rename was copied.
     * The application has locked the workspace at this point, so no other instance copies at the same time.
     */
    @Override
    public void beforeWorkspaceInitialization() {
        LegacyNameMigration.migrate(getWorkspacePath(), ResourcesPlugin.getWorkspace());
    }

    @NotNull
    @Override
    public DBPWorkspaceDesktop createWorkspace(@NotNull DBPPlatform platform) {
        return new DesktopWorkspaceImpl(platform, loadEclipseWorkspace());
    }

    @NotNull
    protected IWorkspace loadEclipseWorkspace() {
        return ResourcesPlugin.getWorkspace();
    }

    @Override
    public boolean isEnvironmentVariablesAccessible() {
        return true;
    }

    @NotNull
    @Override
    public LockManager createLockManager(@NotNull String applicationId, @NotNull Path metadataFolder) throws DBException {
        return new LocalFileLockManager(metadataFolder);
    }

    @NotNull
    @Override
    public LockManager createLockManager() throws DBException {
        return new LocalFileLockManager(GeneralUtils.getMetadataFolder());
    }

    // Dirty fix of pro#6833
    // We should keep this flag somewhere in basic UI plugin
    public boolean isForcedRestart() {
        return isForcedRestart;
    }

    public void setIsForcedRestart(boolean isForcedRestart) {
        this.isForcedRestart = isForcedRestart;
    }


}
