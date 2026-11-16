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
package com.seizia.chromite.model.rcp;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.IPath;
import org.jkiss.code.NotNull;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.app.DBPWorkspace;
import com.seizia.chromite.model.security.ChromiteSecureStorage;
import com.seizia.chromite.model.security.WorkspaceSecretController;
import com.seizia.chromite.registry.ProjectCredentialsStorage;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Moves the saved passwords that earlier versions kept in files to the OS secure storage, once at startup.
 *
 * 설계: 연결 레지스트리와 작업 공간 세션도 읽을 때 같은 일을 하지만 그것은 연 프로젝트와 읽은 비밀만이다. 열지 않은 프로젝트의
 * {@code credentials-config*.json}과 읽히지 않는 작업 공간 비밀(지금 쓰지 않는 프록시 등)도 파일에 남지 않게 시작할 때 모두 옮긴다.
 * 옮길 것이 남았는지는 파일과 설정 키가 있는지로 판단하므로, 중간에 끝나도 다음 시작이 이어서 한다.
 * {@link LegacyNameMigration} 다음에 불러, 이름 변경 전 폴더에서 복사된 파일도 같은 시작에서 옮긴다.
 * 자세한 내용은 docs/chromite/credential-storage.md "이전"에 있다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
final class CredentialStorageMigration {

    private static final Log log = Log.getLog(CredentialStorageMigration.class);

    private CredentialStorageMigration() {
    }

    /**
     * Never throws: a failure is logged and the files stay for the next start.
     */
    static void migrate(@NotNull Path workspaceFolder, @NotNull IWorkspace eclipseWorkspace) {
        if (!ChromiteSecureStorage.isAvailable()) {
            log.warn("The secure storage of the operating system is not available: saved passwords stay where they are");
            return;
        }
        try {
            new WorkspaceSecretController(workspaceFolder.resolve(DBPWorkspace.METADATA_FOLDER)).migrateLegacySecrets();
        } catch (Throwable e) {
            log.error("Saved secrets of the workspace settings were not moved to the secure storage", e);
        }
        Set<Path> projectFolders = new LinkedHashSet<>();
        try {
            for (IProject project : eclipseWorkspace.getRoot().getProjects()) {
                IPath location = project.getLocation();
                if (location != null) {
                    projectFolders.add(location.toPath());
                }
            }
        } catch (Throwable e) {
            log.error("Error listing projects for moving saved passwords to the secure storage", e);
        }
        for (Path projectFolder : projectFolders) {
            ProjectCredentialsStorage.migrateLegacyFiles(projectFolder.resolve(DBPProject.METADATA_FOLDER));
        }
    }
}
