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
import com.seizia.chromite.model.DBPAdaptable;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.access.DBAPermissionRealm;
import com.seizia.chromite.model.auth.SMAuthSpace;
import com.seizia.chromite.model.auth.SMSession;
import com.seizia.chromite.model.auth.SMSessionContext;
import com.seizia.chromite.model.fs.DBFFileSystemContainer;
import com.seizia.chromite.model.rm.RMConstants;

import java.nio.file.Path;
import java.util.List;

/**
 * User workspace.
 * *
 * Operates with projects, resources and user session.
 */
public interface DBPWorkspace extends DBFFileSystemContainer, SMAuthSpace, DBAPermissionRealm {
    String METADATA_FOLDER = ".metadata";

    @NotNull
    DBPPlatform getPlatform();

    @NotNull
    String getWorkspaceId();

    boolean isActive();

    @NotNull
    Path getAbsolutePath();

    @NotNull
    Path getMetadataFolder();

    @NotNull
    List<? extends DBPProject> getProjects();
    @Nullable
    DBPProject getActiveProject();
    @Nullable
    DBPProject getProject(@NotNull String projectName);

    /**
     * Finds project by ID
     */
    @Nullable
    DBPProject getProjectById(@NotNull String projectId);

    /**
     * Workspace auth context
     */
    @NotNull
    SMSessionContext getAuthContext();

    /**
     * Initializes workspace state.
     * Called once during workspace instantiation. Mustn't be called directly by user.
     */
    void initializeProjects() throws DBException;

    /**
     * Disposes workspace caches.
     * Mustn't be called directly by user.
     */
    void dispose();

    @Nullable
    default SMSession getWorkspaceSession() {
        return getAuthContext().findSpaceSession(this);
    }

    default boolean canManageProjects() {
        return hasRealmPermission(RMConstants.PERMISSION_PROJECT_ADMIN);
    }

    default boolean isEnabledSecretProviders() {
        return true;
    }

    @Nullable
    DBPImage getResourceIcon(DBPAdaptable resourceAdapter);

    @NotNull
    DBPProject createProject(@NotNull String name, @Nullable String description) throws DBException;

    void deleteProject(@NotNull DBPProject project) throws DBException;

    void renameProject(@NotNull DBPProject project, @NotNull String newName) throws DBException;

}
