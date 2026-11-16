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

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.auth.SMSessionContext;
import com.seizia.chromite.model.fs.DBFResourceAdapter;
import com.seizia.chromite.model.fs.DBFVirtualFileSystemRoot;
import com.seizia.chromite.model.impl.app.BaseProjectImpl;
import com.seizia.chromite.model.impl.app.BaseWorkspaceImpl;
import com.seizia.chromite.model.navigator.DBNModel;
import com.seizia.chromite.model.task.DBTTaskManager;
import com.seizia.chromite.registry.DataSourceRegistry;
import com.seizia.chromite.registry.task.TaskConstants;
import com.seizia.chromite.registry.task.TaskManagerImpl;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.utils.ResourceUtils;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Desktop (Eclipse resources) project.
 *
 * 변경 이력:
 * - 2026-11-07 옛 XML 연결 설정({@code .chromite-data-sources*.xml}) 읽기를 지움. 내부 이름 변경(#59)으로 찾는 이름이
 *   어떤 빌드도 쓰지 않는 이름이 됐다(docs/chromite/internal-rename.md "사용자 데이터 이전")
 */
public class DesktopProjectImpl extends BaseProjectImpl implements RCPProject, DBFResourceAdapter {

    private static final Log log = Log.getLog(DesktopProjectImpl.class);

    private static final String SETTINGS_FOLDER = ".settings";

    @NotNull
    private final IProject project;
    protected volatile DBTTaskManager taskManager;

    private volatile boolean projectInvalidated;

    public DesktopProjectImpl(@NotNull BaseWorkspaceImpl workspace, @NotNull IProject project, @Nullable SMSessionContext sessionContext) {
        super(workspace, sessionContext);
        this.project = project;
    }

    @Override
    public boolean isVirtual() {
        return false;
    }

    @NotNull
    @Override
    public String getName() {
        return project.getName();
    }

    @NotNull
    @Override
    public Path getAbsolutePath() {
        if (project.getLocation() == null) {
            throw new IllegalStateException("Can't determine the workspace path for project " + project.getName());
        }
        return project.getLocation().toFile().toPath();
    }

    @Nullable
    @Override
    public IProject getEclipseProject() {
        return project;
    }

    @Nullable
    @Override
    public IContainer getRootResource() {
        return getEclipseProject();
    }

    @Override
    @NotNull
    public String getResourcePath(@NotNull IResource resource) {
        return resource.getProjectRelativePath().toString();
    }

    @Override
    public boolean isOpen() {
        return project.isOpen();
    }

    @Override
    public void ensureOpen() throws IllegalStateException {
        if (!project.isOpen()) {
            NullProgressMonitor monitor = new NullProgressMonitor();
            try {
                project.open(monitor);
                if (!DBWorkbench.isDistributed()) {
                    project.refreshLocal(IFile.DEPTH_ONE, monitor);
                }
            } catch (CoreException e) {
                if (getWorkspace().getPlatform().getApplication().isStandalone() &&
                    e.getMessage().contains(IProjectDescription.DESCRIPTION_FILE_NAME)) {
                    try {
                        recoverProjectDescription();
                        project.open(monitor);
                        hideConfigurationFiles();
                        project.refreshLocal(IFile.DEPTH_ONE, monitor);
                    } catch (Exception e2) {
                        log.error("Error opening project", e2);
                        return;
                    }
                }
            }
        }
        if (!projectInvalidated) {
            try {
                setFormat(ProjectFormat.MODERN);
            } finally {
                projectInvalidated = true;
            }
        }
    }

    @Override
    public boolean isUseSecretStorage() {
        return false;
    }

    @NotNull
    protected DBPDataSourceRegistry createDataSourceRegistry() {
        return new DataSourceRegistry<>(this);
    }

    @Nullable
    @Override
    public <T> T adaptResource(DBFVirtualFileSystemRoot fsRoot, Path path, Class<T> adapter) {
        if (adapter == IResource.class) {
            return adapter.cast(ResourceUtils.createResourceFromPath(fsRoot, getEclipseProject(), path));
        }
        return null;
    }

    @NotNull
    @Override
    public DBNModel getNavigatorModel() {
        return getWorkspace().getPlatform().getNavigatorModel();
    }

    @NotNull
    @Override
    public DBTTaskManager getTaskManager() {
        ensureOpen();
        if (taskManager == null) {
            synchronized (metadataSync) {
                if (taskManager == null) {
                    taskManager = createTaskManager();
                }
            }
        }
        return taskManager;
    }

    @Nullable
    @Override
    public DBTTaskManager getTaskManager(boolean create) {
        if (taskManager != null) {
            return taskManager;
        }
        return create ? getTaskManager() : null;
    }

    @NotNull
    protected DBTTaskManager createTaskManager() {
        return new TaskManagerImpl(
            this,
            getWorkspace().getMetadataFolder().resolve(TaskConstants.TASK_STATS_FOLDER)
        );
    }

    public void hideConfigurationFiles() {
        if (project.isOpen() && DBWorkbench.getPlatform().getApplication().isStandalone()) {
            // To avoid accidental corruption of the workspace configuration by search/replace commands,
            // we need to mark metadata folder as hidden (see dbeaver/dbeaver#20759)
            IFolder metadataFolder = project.getFolder(DBPProject.METADATA_FOLDER);
            hideResource(metadataFolder);
            IFolder settingsFolder = project.getFolder(SETTINGS_FOLDER);
            hideResource(settingsFolder);
            IFile file = project.getFile(PROJECT_FILE);
            hideResource(file);
        }
    }

    private void hideResource(IResource file) {
        if (file.exists() && !file.isHidden()) {
            try {
                file.setHidden(true);
            } catch (CoreException e) {
                log.error("Error hiding metadata folder", e);
            }
        }
    }

    public void recoverProjectDescription() throws IOException {
        // .project file missing. Let's try to create an empty project config
        log.debug("Recovering project '" + project.getName() + "' metadata " + getAbsolutePath().toAbsolutePath());
        BaseProjectImpl.updateProjectFile(getAbsolutePath(), project.getName());
    }

    @NotNull
    public Map<String, Map<String, String>> getAllResourceProperties() {
        this.loadMetadata();
        synchronized (resourcesSync) {
            return new TreeMap<>(this.resourceProperties);
        }
    }

    void removeResourceFromCache(IPath path) {
        boolean cacheChanged = false;
        synchronized (resourcesSync) {
            if (resourceProperties != null) {
                String resPath = CommonUtils.normalizeResourcePath(path.toString());
                cacheChanged = (resourceProperties.remove(resPath) != null);
            }
        }
        if (cacheChanged) {
            flushMetadata();
        }
    }

    void moveResourceCache(IPath oldPath, IPath newPath) {
        boolean cacheChanged = false;
        synchronized (resourcesSync) {
            if (resourceProperties != null) {
                String oldResPath = CommonUtils.normalizeResourcePath(oldPath.toString());
                Map<String, String> props = resourceProperties.remove(oldResPath);
                if (props != null) {
                    String newResPath = CommonUtils.normalizeResourcePath(newPath.toString());
                    resourceProperties.put(newResPath, props);
                    cacheChanged = true;
                }
            }
        }
        if (cacheChanged) {
            flushMetadata();
        }
    }

    @Override
    public void updateProjectNature() {
        if (!isRegistryLoaded()) {
            return;
        }
        try {
            IProject eclipseProject = this.getEclipseProject();
            if (eclipseProject != null) {
                final IProjectDescription description = eclipseProject.getDescription();
                if (description != null) {
                    String[] natureIds = description.getNatureIds();
                    if (getDataSourceRegistry() instanceof DataSourceRegistry<?> dsr && dsr.getDataSourceCount() > 0) {
                        // Add nature
                        if (!ArrayUtils.contains(natureIds, ChromiteNature.NATURE_ID)) {
                            description.setNatureIds(ArrayUtils.add(String.class, natureIds, ChromiteNature.NATURE_ID));
                            try {
                                eclipseProject.setDescription(description, new NullProgressMonitor());
                            } catch (CoreException e) {
                                log.debug("Can't set project nature", e);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug(e);
        }
    }
}
