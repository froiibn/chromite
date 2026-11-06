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

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.app.DBPWorkspace;
import com.seizia.chromite.utils.SystemVariablesResolver;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.jkiss.code.NotNull;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 내부 이름 변경(froiibn/chromite#59, {@code org.jkiss.dbeaver} → {@code com.seizia.chromite}) 전 빌드가 남긴
 * 사용자 데이터를 새 이름 위치로 한 번 복사한다.
 *
 * 설계:
 * <ul>
 *     <li><b>복사만 한다.</b> 옛 폴더·파일·설정 노드는 읽기만 하고 고치거나 지우지 않는다. 사용자가 이름 변경 전 빌드로
 *     돌아가도 그 빌드의 데이터가 그대로 있게 하기 위해서다. 같은 이유로 두 빌드가 같은 경로를 쓰는 파일
 *     ({@code .metadata/.config}, {@code .project}, Swing 번들 설정 등)은 고치지 않는다. 같은 설정 노드 안에서
 *     키 이름만 바뀐 경우는 새 키를 옆에 추가한다(옛 키는 남는다).</li>
 *     <li><b>있는 것은 덮어쓰지 않는다.</b> 새 위치에 같은 파일·키가 있으면 사용자가 새 빌드에서 만든 값이므로 건너뛰고
 *     로그에 남긴다.</li>
 *     <li><b>한 번만 한다.</b> 옮긴 뒤 새 위치에 표시 파일({@value #MARKER_FILE})을 둔다. 작업 공간은 {@code .metadata},
 *     프로젝트는 새 메타데이터 폴더({@link DBPProject#METADATA_FOLDER})에 둔다. 표시가 있으면 다시 복사하지 않는다.
 *     "있는 것은 건너뛴다"만으로는 새 빌드에서 지운 연결 파일이나 기본값으로 되돌린 설정이 다음 시작 때 옛 값으로
 *     되살아나기 때문이다. 옛 데이터가 없으면 표시도 두지 않는다. 판단은 폴더·파일·표시의 존재로만 하고 제품·번들
 *     버전은 보지 않는다(버전 체계가 바뀌어도 이전이 건너뛰어지거나 반복되지 않게).</li>
 *     <li><b>저장된 이름을 새 이름으로 바꾼다.</b> 복사한 텍스트 파일과 설정 키·값에 이름 변경 규칙
 *     ({@code tools/rename/rules.py})의 해당 부분을 적용한다: 클래스·번들 이름 {@code org.jkiss.dbeaver.*}
 *     (R1, 마지막 마디의 {@code DBeaver<Name>}은 R2), R3의 {@code com.dbeaver.*} ID, 드라이버·연결 내부 속성 접두어
 *     {@code @dbeaver-}, 경로 변수 {@code ${dbeaver_home}}. 예: 연결 필터 {@code type}, SQL Server 인증·Oracle 연결
 *     방식 같은 {@code provider-properties}. 단어 {@code DBeaver} 전체를 바꾸지는 않는다(드라이버 경로의
 *     {@code DBeaverData} 같은 사용자 값이 깨진다).</li>
 *     <li><b>{@code credentials-config*.json}은 바이트 그대로 복사한다.</b> 고정 키({@code BaseProjectImpl}
 *     {@code LOCAL_KEY_CACHE}, 이름 변경 전후 같다)로 암호화되어 있고, 내용(데이터 소스 ID별 사용자·비밀번호·인증 속성)에
 *     바뀐 이름이 없다. 그래서 복사만으로 저장된 비밀번호가 그대로 읽힌다.</li>
 *     <li>프로젝트 목록은 Eclipse 작업 공간에서 얻는다(작업 공간 밖에 연결한 프로젝트 포함). 연결 레지스트리를 읽기 전,
 *     {@link DesktopApplicationImpl#beforeWorkspaceInitialization()}에서 부른다.</li>
 *     <li>옛 이름 문자열은 이 클래스에만 둔다. 이름 변경 게이트({@code tools/rename/gate.py})와 도구가 이 파일을
 *     건너뛰도록 {@code rules.py}의 {@code EXCLUDE_FILES}에 등록했다.</li>
 * </ul>
 * 범위와 이유는 docs/chromite/internal-rename.md "사용자 데이터 이전"에 있다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
final class LegacyNameMigration {

    private static final Log log = Log.getLog(LegacyNameMigration.class);

    /** Project metadata folder written by builds before the rename (now {@link DBPProject#METADATA_FOLDER}). */
    static final String LEGACY_PROJECT_FOLDER = ".dbeaver"; //$NON-NLS-1$
    /** One-time marker, in the workspace metadata folder and in each migrated project metadata folder. */
    static final String MARKER_FILE = "legacy-migration.properties"; //$NON-NLS-1$

    // Workspace .metadata files whose names changed (old name -> current name)
    private static final Map<String, String> WORKSPACE_FILES = Map.of(
        "dbeaver-workspace.properties", DBConstants.WORKSPACE_PROPS_FILE, //$NON-NLS-1$
        "dbeaver-external-files.json", DesktopWorkspaceImpl.EXT_FILES_PROPS_STORE, //$NON-NLS-1$
        "dbeaver-external-files.data", DesktopWorkspaceImpl.EXT_FILES_PROPS_STORE_LEGACY //$NON-NLS-1$
    );

    private static final String PLUGINS_STATE_FOLDER = ".plugins"; //$NON-NLS-1$
    private static final String PROJECT_SETTINGS_FOLDER = ".settings"; //$NON-NLS-1$
    private static final String PREFS_EXT = ".prefs"; //$NON-NLS-1$
    private static final String BACKUP_EXT = ".bak"; //$NON-NLS-1$
    private static final Set<String> TEXT_EXTENSIONS = Set.of(".json", ".xml", ".properties", ".prefs", ".txt"); //$NON-NLS-1$

    // R1 (+R2 in the name's segments): org.jkiss.dbeaver.x.y.DBeaverZ -> com.seizia.chromite.x.y.ChromiteZ
    private static final Pattern OLD_QUALIFIED_NAME = Pattern.compile(
        "(?<![A-Za-z0-9_$.])org\\.jkiss\\.dbeaver(?![A-Za-z0-9_$])((?:\\.[A-Za-z0-9_$]+)*)"); //$NON-NLS-1$
    // The one class that kept the old package (it lives in deps/)
    private static final String KEPT_CLASS_TAIL = ".DBException"; //$NON-NLS-1$
    private static final String NEW_PACKAGE = "com.seizia.chromite"; //$NON-NLS-1$
    private static final Pattern OLD_TYPE_PREFIX = Pattern.compile("^DBeaver(?=[A-Z])"); //$NON-NLS-1$
    // R3: ids that upstream declared under com.dbeaver (not defined by deps/)
    private static final Pattern OLD_COM_DBEAVER_ID = Pattern.compile(
        "(?<![A-Za-z0-9_])com\\.dbeaver\\.(ai|secretController|preferences|ssh\\.tunnels|SecureStorage)(?![A-Za-z0-9_])"); //$NON-NLS-1$
    // R4: internal driver/connection property prefix (DBConstants.INTERNAL_PROP_PREFIX) and the home path variable
    private static final String OLD_INTERNAL_PROP_PREFIX = "@dbeaver-"; //$NON-NLS-1$
    private static final String OLD_HOME_VARIABLE = "${dbeaver_home}"; //$NON-NLS-1$
    private static final String NEW_HOME_VARIABLE = "${" + SystemVariablesResolver.VAR_CHROMITE_HOME + "}"; //$NON-NLS-1$ //$NON-NLS-2$

    private LegacyNameMigration() {
    }

    /**
     * Migrates the workspace (instance preferences, renamed .metadata files and plugin state folders) and every
     * project of the Eclipse workspace. Never throws: a failure is logged and startup continues with what was copied.
     */
    static void migrate(@NotNull Path workspaceFolder, @NotNull IWorkspace eclipseWorkspace) {
        try {
            migrateWorkspace(workspaceFolder);
        } catch (Throwable e) {
            log.error("Error migrating workspace data written before the internal rename", e);
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
            log.error("Error listing projects for the legacy data migration", e);
        }
        for (Path projectFolder : projectFolders) {
            try {
                migrateProject(projectFolder);
            } catch (Throwable e) {
                log.error("Error migrating project data written before the internal rename in " + projectFolder, e);
            }
        }
    }

    static void migrateWorkspace(@NotNull Path workspaceFolder) throws IOException, BackingStoreException {
        Path metadataFolder = workspaceFolder.resolve(DBPWorkspace.METADATA_FOLDER);
        Path marker = metadataFolder.resolve(MARKER_FILE);
        if (Files.exists(marker)) {
            log.debug("Workspace data written before the internal rename was already migrated (" + marker + ")");
            return;
        }
        Counter counter = new Counter();
        boolean found = false;

        for (Map.Entry<String, String> file : WORKSPACE_FILES.entrySet()) {
            Path source = metadataFolder.resolve(file.getKey());
            if (Files.isRegularFile(source)) {
                found = true;
                copyFile(source, metadataFolder.resolve(file.getValue()), counter);
            }
        }

        // Plugin state folders (.metadata/.plugins/<bundle id>) are named after the bundle symbolic names
        Path pluginsFolder = metadataFolder.resolve(PLUGINS_STATE_FOLDER);
        if (Files.isDirectory(pluginsFolder)) {
            try (Stream<Path> list = Files.list(pluginsFolder)) {
                for (Path source : list.filter(Files::isDirectory).toList()) {
                    String name = source.getFileName().toString();
                    String newName = mapNames(name);
                    if (!newName.equals(name)) {
                        found = true;
                        copyTree(source, pluginsFolder.resolve(newName), counter);
                    }
                }
            }
        }

        // Instance preferences (.metadata/.plugins/org.eclipse.core.runtime/.settings/<bundle id>.prefs).
        // Copied through the preference service: some nodes are already loaded at this point, writing their files
        // directly would be lost when the loaded node is flushed.
        IEclipsePreferences instanceRoot =
            (IEclipsePreferences) Platform.getPreferencesService().getRootNode().node(InstanceScope.SCOPE);
        for (String nodeName : instanceRoot.childrenNames()) {
            String newNodeName = mapNames(nodeName);
            Preferences source = instanceRoot.node(nodeName);
            Preferences target = newNodeName.equals(nodeName) ? source : instanceRoot.node(newNodeName);
            if (copyPreferences(source, target, nodeName, newNodeName, counter)) {
                found = true;
            }
        }

        if (!found) {
            return;
        }
        log.info("Workspace data written before the internal rename: " + counter);
        if (counter.failed == 0) {
            writeMarker(marker, metadataFolder, counter);
        }
    }

    static void migrateProject(@NotNull Path projectFolder) throws IOException {
        Path source = projectFolder.resolve(LEGACY_PROJECT_FOLDER);
        if (!Files.isDirectory(source)) {
            return;
        }
        Path target = projectFolder.resolve(DBPProject.METADATA_FOLDER);
        Path marker = target.resolve(MARKER_FILE);
        if (Files.exists(marker)) {
            log.debug("Project data written before the internal rename was already migrated (" + marker + ")");
            return;
        }
        Counter counter = new Counter();
        copyTree(source, target, counter);

        // Project-scope preferences (<project>/.settings/<qualifier>.prefs), e.g. resource folders of the project
        Path settingsFolder = projectFolder.resolve(PROJECT_SETTINGS_FOLDER);
        if (Files.isDirectory(settingsFolder)) {
            try (Stream<Path> list = Files.list(settingsFolder)) {
                for (Path prefsFile : list.filter(Files::isRegularFile).toList()) {
                    String name = prefsFile.getFileName().toString();
                    String newName = mapNames(name);
                    if (name.endsWith(PREFS_EXT) && !newName.equals(name)) {
                        copyFile(prefsFile, settingsFolder.resolve(newName), counter);
                    }
                }
            }
        }

        log.info("Project data written before the internal rename: " + source + " -> " + target + ": " + counter);
        if (counter.failed == 0) {
            writeMarker(marker, source, counter);
        }
    }

    /**
     * Copies the keys of a preference node (and its child nodes) whose node or key name changed. A key that exists
     * in the target node is kept. Values of keys whose node and name did not change are left alone: that node is
     * shared with builds before the rename.
     *
     * @return whether the node held anything written under an old name
     */
    private static boolean copyPreferences(
        @NotNull Preferences source,
        @NotNull Preferences target,
        @NotNull String sourcePath,
        @NotNull String targetPath,
        @NotNull Counter counter
    ) throws BackingStoreException {
        boolean sameNode = source == target;
        boolean found = !sameNode;
        int copied = 0;
        for (String key : source.keys()) {
            String newKey = mapNames(key);
            if (sameNode && newKey.equals(key)) {
                continue;
            }
            found = true;
            if (target.get(newKey, null) != null) {
                counter.kept++;
                log.info("Preference '" + targetPath + "/" + newKey + "' already exists; legacy value not copied");
                continue;
            }
            target.put(newKey, mapNames(source.get(key, ""))); //$NON-NLS-1$
            copied++;
        }
        if (copied > 0) {
            target.flush();
            counter.preferences += copied;
            log.info("Copied " + copied + " preference(s) from '" + sourcePath + "' to '" + targetPath + "'");
        }
        for (String child : source.childrenNames()) {
            String newChild = mapNames(child);
            Preferences sourceChild = source.node(child);
            Preferences targetChild = sameNode && newChild.equals(child) ? sourceChild : target.node(newChild);
            if (copyPreferences(sourceChild, targetChild, sourcePath + "/" + child, targetPath + "/" + newChild, counter)) {
                found = true;
            }
        }
        return found;
    }

    private static void copyTree(@NotNull Path sourceFolder, @NotNull Path targetFolder, @NotNull Counter counter) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(sourceFolder)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            Path relative = sourceFolder.relativize(file);
            copyFile(file, targetFolder.resolve(relative.toString()), counter);
        }
    }

    /**
     * Copies one file unless the target exists. Text files get the name mapping; the file is written under a temporary
     * name first, so an interrupted copy never leaves a partial file that the next start would keep as "existing".
     */
    private static void copyFile(@NotNull Path source, @NotNull Path target, @NotNull Counter counter) {
        if (Files.exists(target)) {
            counter.kept++;
            log.info("Kept existing " + target + "; legacy copy " + source + " not used");
            return;
        }
        try {
            byte[] data = Files.readAllBytes(source);
            if (isMappedText(source.getFileName().toString())) {
                // Latin-1 keeps every byte as is (UTF-8 included); all mapped names are ASCII
                String text = new String(data, StandardCharsets.ISO_8859_1);
                String mapped = mapNames(text);
                if (!mapped.equals(text)) {
                    data = mapped.getBytes(StandardCharsets.ISO_8859_1);
                    counter.rewritten++;
                }
            }
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".migration-", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
            try {
                Files.write(temp, data);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            counter.files++;
        } catch (IOException e) {
            counter.failed++;
            log.error("Error copying " + source + " to " + target, e);
        }
    }

    /**
     * Text files whose stored names are mapped. Connection credentials are encrypted and hold no renamed names,
     * so they are copied byte for byte. Backups ({@code .name.bak}) are treated like the file they back up.
     */
    private static boolean isMappedText(@NotNull String fileName) {
        String name = fileName.endsWith(BACKUP_EXT) ? fileName.substring(0, fileName.length() - BACKUP_EXT.length()) : fileName;
        if (name.contains(DBPDataSourceRegistry.CREDENTIALS_CONFIG_FILE_PREFIX)) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && TEXT_EXTENSIONS.contains(name.substring(dot));
    }

    /**
     * Maps names that builds before the rename stored (class names, bundle ids, property prefixes) to the current ones.
     * Same rules as tools/rename/rules.py R1-R4, limited to the forms that occur in user data.
     */
    @NotNull
    static String mapNames(@NotNull String text) {
        if (text.indexOf("dbeaver") < 0) { //$NON-NLS-1$
            return text;
        }
        Matcher m = OLD_QUALIFIED_NAME.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String tail = m.group(1);
            String replacement = KEPT_CLASS_TAIL.equals(tail) ? m.group() : NEW_PACKAGE + mapTypeSegments(tail);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        String result = OLD_COM_DBEAVER_ID.matcher(sb.toString()).replaceAll(Matcher.quoteReplacement(NEW_PACKAGE) + ".$1");
        return result
            .replace(OLD_INTERNAL_PROP_PREFIX, DBConstants.INTERNAL_PROP_PREFIX)
            .replace(OLD_HOME_VARIABLE, NEW_HOME_VARIABLE);
    }

    // R2: a type named DBeaver<Name> became Chromite<Name> (DBeaverIcons became SwtIcons)
    @NotNull
    private static String mapTypeSegments(@NotNull String tail) {
        if (tail.indexOf("DBeaver") < 0) { //$NON-NLS-1$
            return tail;
        }
        List<String> segments = new ArrayList<>();
        for (String segment : tail.substring(1).split("\\.")) { //$NON-NLS-1$
            if (segment.equals("DBeaverIcons")) { //$NON-NLS-1$
                segments.add("SwtIcons"); //$NON-NLS-1$
            } else {
                segments.add(OLD_TYPE_PREFIX.matcher(segment).replaceFirst("Chromite")); //$NON-NLS-1$
            }
        }
        return "." + String.join(".", segments); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void writeMarker(@NotNull Path marker, @NotNull Path source, @NotNull Counter counter) throws IOException {
        Properties props = new Properties();
        props.setProperty("migrated", OffsetDateTime.now().toString()); //$NON-NLS-1$
        props.setProperty("source", source.toString()); //$NON-NLS-1$
        props.setProperty("result", counter.toString()); //$NON-NLS-1$
        Files.createDirectories(marker.getParent());
        try (OutputStream os = Files.newOutputStream(marker)) {
            props.store(os, "Data written before the internal rename was copied here once; delete this file to copy again"); //$NON-NLS-1$
        }
    }

    private static final class Counter {
        int files;
        int rewritten;
        int preferences;
        int kept;
        int failed;

        @Override
        public String toString() {
            return "copied " + files + " file(s) (" + rewritten + " with renamed names), " + preferences
                + " preference(s); kept " + kept + " existing; " + failed + " failed";
        }
    }
}
