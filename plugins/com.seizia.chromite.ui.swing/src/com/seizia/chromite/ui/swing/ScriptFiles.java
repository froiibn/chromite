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

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.app.DBPResourceTypeDescriptor;
import com.seizia.chromite.model.impl.app.BaseProjectImpl;
import com.seizia.chromite.registry.ResourceTypeRegistry;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 프로젝트의 스크립트 폴더와 스크립트-연결 묶기(Scripts 도구 창 {@link ScriptsPanel}, 메인 창의 파일 열기·저장).
 *
 * 설계:
 * <ul>
 *     <li>스크립트 폴더는 SWT와 같은 곳이다: 모델의 자원 형식 {@code sql-script}({@code model.sql}의 {@code plugin.xml})가 정하는
 *     프로젝트 아래 폴더({@code Scripts}, 프로젝트 설정으로 바꿀 수 있음, {@link DBPResourceTypeDescriptor#getDefaultRoot}).
 *     그래서 SWT에서 만든 작업 공간의 스크립트가 그대로 보인다. 보이는 파일도 그 형식의 확장자({@code getFileExtensions})다.</li>
 *     <li>파일은 Eclipse 자원({@code IResource})이 아니라 {@code java.nio}로 다룬다. Swing 화면은 Eclipse 자원 트리를 쓰지 않아,
 *     자원 트리를 거치면 새로 고침 동기화만 늘기 때문이다.</li>
 *     <li>스크립트-연결 묶기는 SWT({@code EditorUtils.setFileDataSource})와 같은 프로젝트 자원 속성
 *     ({@link DBConstants#PROP_RESOURCE_DEFAULT_DATASOURCE}, {@link DBConstants#PROP_RESOURCE_DEFAULT_PROJECT_ID})에 저장한다.
 *     경로는 프로젝트 기준 상대 경로({@code Scripts/a.sql}, SWT {@code getProjectRelativePath}와 같은 모양)다. 그래서 SWT에서 묶은 스크립트도
 *     같은 연결로 열린다. 저장은 모델이 프로젝트의 {@code .chromite/project-metadata.json}에 한다.</li>
 *     <li>이름 바꾸기·옮기기·삭제를 하면 그 경로(폴더면 그 아래 모든 경로)의 속성을 함께 옮기거나 지운다. 그러지 않으면 같은 이름으로
 *     새로 만든 스크립트가 지운 스크립트의 연결을 물려받는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-scripts-explorer.md)
 */
final class ScriptFiles {

    private static final Log log = Log.getLog(ScriptFiles.class);

    /**
     * Resource type of SQL scripts ({@code model.sql} {@code plugin.xml}); its root folder is the project's script folder.
     */
    static final String SCRIPT_RESOURCE_TYPE = "sql-script"; //$NON-NLS-1$
    private static final String DEFAULT_EXTENSION = "sql"; //$NON-NLS-1$

    /**
     * The script folder of a project.
     */
    record Root(@NotNull DBPProject project, @NotNull Path folder) {
    }

    /**
     * The connection link of a script.
     *
     * @param dataSourceId id of the linked connection, null when the script has none
     * @param connection   the linked connection, null when there is none or it no longer exists
     */
    record Link(@Nullable String dataSourceId, @Nullable DBPDataSourceContainer connection) {
        static final Link NONE = new Link(null, null);

        boolean isMissing() {
            return dataSourceId != null && connection == null;
        }
    }

    private ScriptFiles() {
    }

    @Nullable
    private static DBPResourceTypeDescriptor getResourceType() {
        return ResourceTypeRegistry.getInstance().getResourceType(SCRIPT_RESOURCE_TYPE);
    }

    /**
     * The script folder of the active project (it may not exist yet), or null without an active project.
     */
    @Nullable
    static Root getActiveRoot() {
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        return project == null ? null : getRoot(project);
    }

    @Nullable
    private static Root getRoot(@NotNull DBPProject project) {
        DBPResourceTypeDescriptor type = getResourceType();
        String folder = type != null ? type.getDefaultRoot(project) : null;
        Path projectPath = project.getAbsolutePath();
        if (projectPath == null || CommonUtils.isEmptyTrimmed(folder)) {
            return null;
        }
        return new Root(project, projectPath.resolve(folder).toAbsolutePath().normalize());
    }

    /**
     * The script folder of any project that contains the file or folder, or null.
     */
    @Nullable
    static Root findRoot(@NotNull Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            Root root = getRoot(project);
            if (root != null && normalized.startsWith(root.folder())) {
                return root;
            }
        }
        return null;
    }

    /**
     * Whether a file is a script by its extension (the extensions of the script resource type).
     */
    static boolean isScript(@NotNull Path file) {
        String name = String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT);
        DBPResourceTypeDescriptor type = getResourceType();
        String[] extensions = type != null && type.getFileExtensions().length > 0 ? type.getFileExtensions() : new String[]{DEFAULT_EXTENSION};
        for (String extension : extensions) {
            if (name.endsWith("." + extension.toLowerCase(Locale.ROOT))) { //$NON-NLS-1$
                return true;
            }
        }
        return false;
    }

    /**
     * Project-relative path with {@code /} separators, as SWT stored resource properties.
     */
    @NotNull
    private static String getResourcePath(@NotNull DBPProject project, @NotNull Path path) {
        return CommonUtils.normalizeResourcePath(project.getAbsolutePath().toAbsolutePath().normalize()
            .relativize(path.toAbsolutePath().normalize()).toString());
    }

    /**
     * The connection link of a script; {@link Link#NONE} outside the script folders. Reads cached project metadata only.
     */
    @NotNull
    static Link getLink(@NotNull Path file) {
        Root root = findRoot(file);
        if (root == null) {
            return Link.NONE;
        }
        DBPProject project = root.project();
        String resourcePath = getResourcePath(project, file);
        String dataSourceId = project.getResourceProperty(resourcePath, DBConstants.PROP_RESOURCE_DEFAULT_DATASOURCE);
        if (CommonUtils.isEmpty(dataSourceId)) {
            return Link.NONE;
        }
        String projectId = project.getResourceProperty(resourcePath, DBConstants.PROP_RESOURCE_DEFAULT_PROJECT_ID);
        DBPProject dataSourceProject = CommonUtils.isEmpty(projectId) ? project
            : DBWorkbench.getPlatform().getWorkspace().getProjectById(projectId);
        DBPDataSourceContainer connection = dataSourceProject == null ? null
            : dataSourceProject.getDataSourceRegistry().getDataSource(dataSourceId);
        return new Link(dataSourceId, connection);
    }

    /**
     * Links a script to a connection, or removes the link (null). Other properties of the script stay.
     */
    static void setLink(@NotNull Path file, @Nullable DBPDataSourceContainer connection) {
        Root root = findRoot(file);
        if (root == null) {
            return;
        }
        DBPProject project = root.project();
        String resourcePath = getResourcePath(project, file);
        Map<String, String> oldProperties = project.getResourceProperties(resourcePath);
        Map<String, String> newProperties = oldProperties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(oldProperties);
        if (connection == null) {
            newProperties.remove(DBConstants.PROP_RESOURCE_DEFAULT_PROJECT_ID);
            newProperties.remove(DBConstants.PROP_RESOURCE_DEFAULT_DATASOURCE);
        } else {
            newProperties.put(DBConstants.PROP_RESOURCE_DEFAULT_PROJECT_ID, connection.getProject().getId());
            newProperties.put(DBConstants.PROP_RESOURCE_DEFAULT_DATASOURCE, connection.getId());
        }
        if (newProperties.equals(oldProperties == null ? Map.of() : oldProperties)) {
            return;
        }
        if (newProperties.isEmpty()) {
            clearProperties(project, resourcePath);
        } else {
            project.setResourceProperties(resourcePath, newProperties);
        }
    }

    /**
     * A script or folder was renamed: its properties (for a folder, those of everything in it) follow it.
     */
    static void moved(@NotNull Path from, @NotNull Path to) {
        Root root = findRoot(from);
        if (root == null) {
            return;
        }
        DBPProject project = root.project();
        String fromPath = getResourcePath(project, from);
        String toPath = getResourcePath(project, to);
        for (String resourcePath : findResourcePaths(project, fromPath)) {
            project.moveResourceProperties(resourcePath, toPath + resourcePath.substring(fromPath.length()));
        }
    }

    /**
     * A script or folder was deleted: its properties (for a folder, those of everything in it) go too.
     */
    static void deleted(@NotNull Path path) {
        Root root = findRoot(path);
        if (root == null) {
            return;
        }
        DBPProject project = root.project();
        for (String resourcePath : findResourcePaths(project, getResourcePath(project, path))) {
            clearProperties(project, resourcePath);
        }
    }

    /**
     * The resource paths with properties at the path or below it.
     */
    @NotNull
    private static String[] findResourcePaths(@NotNull DBPProject project, @NotNull String path) {
        try {
            // No required properties: every resource that has properties
            String[] all = project.findResources(Map.of());
            return Arrays.stream(all)
                .filter(resourcePath -> resourcePath.equals(path) || resourcePath.startsWith(path + "/")) //$NON-NLS-1$
                .toArray(String[]::new);
        } catch (DBException e) {
            log.warn("Can't read the resource properties of project " + project.getName(), e);
            return new String[0];
        }
    }

    private static void clearProperties(@NotNull DBPProject project, @NotNull String resourcePath) {
        if (project instanceof BaseProjectImpl baseProject) {
            // Removes the entry; clearing the properties one by one would leave an empty entry in the metadata file
            baseProject.resetResourceProperties(resourcePath);
            return;
        }
        Map<String, String> properties = project.getResourceProperties(resourcePath);
        if (properties != null) {
            for (String name : Map.copyOf(properties).keySet()) {
                project.setResourceProperty(resourcePath, name, null);
            }
        }
    }
}
