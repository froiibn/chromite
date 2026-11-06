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
import com.seizia.chromite.model.app.DBPWorkspace;
import com.seizia.chromite.model.rcp.DesktopApplicationImpl;
import com.seizia.chromite.utils.RuntimeUtils;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.service.datalocation.Location;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * 작업 공간 위치를 고치기 전 빌드가 {@code <설치 폴더>/workspace}나 {@code <현재 폴더>/workspace}에 만든 작업 공간을
 * 기본 작업 공간({@code ChromiteData/workspace6})으로 한 번 복사한다.
 *
 * 설계:
 * <ul>
 *     <li><b>왜.</b> 고치기 전 Swing 제품은 {@code -data}가 없으면 Equinox 기본값 {@code <현재 폴더>/workspace}를 썼다
 *     ({@link ChromiteSwingActivator#initPreferences} 참고). 시작 메뉴 바로 가기는 설치 폴더에서 시작하므로 대개
 *     {@code <설치 폴더>\workspace}다. 기본 작업 공간으로 바꾸면 그 사용자의 연결이 보이지 않으므로 처음 한 번 가져온다.</li>
 *     <li><b>한 곳에서만.</b> 설치 폴더, 현재 폴더 순서로 찾아 처음 찾은 작업 공간 하나만 복사한다. 바로 가기가 쓰던 쪽이 앞이고,
 *     두 작업 공간을 섞지 않는다.</li>
 *     <li><b>Chromite 작업 공간만.</b> {@code .metadata}에 Chromite만 쓰는 파일(작업 공간 정보 {@value #WORKSPACE_PROPS_FILE}
 *     또는 이 번들의 설정 파일)이 있어야 한다. 이름 변경 전 빌드는 작업 공간 정보 파일 이름이 DBeaver와 같지만 이 번들의 설정 파일은
 *     그때도 같은 이름이었다. 같은 폴더 이름을 쓰는 다른 Eclipse 기반 프로그램의 작업 공간은 가져오지 않는다.
 *     설치된 DBeaver의 데이터 폴더 아래 {@code workspace6}은 찾는 곳이 아니다.</li>
 *     <li><b>새 위치가 비었을 때만.</b> 잠금 파일과 로그 외에 아무것도 없을 때만 복사한다. 이미 쓰던 기본 작업 공간은 덮지 않는다.</li>
 *     <li><b>{@code LegacyNameMigration}과 같은 규칙.</b> 복사만 하고 옛 폴더는 읽기만 한다(고치기 전 빌드로 돌아가도 그대로다).
 *     있는 파일은 덮어쓰지 않는다. 파일은 임시 이름으로 쓴 뒤 옮긴다. 표시 파일({@value #MARKER_FILE})로 한 번만 한다.
 *     표시는 복사 전에 {@value #STATE_COPYING}으로 쓰고 실패 없이 끝나면 {@value #STATE_DONE}으로 바꾼다. 중간에 끊기거나 실패한
 *     파일이 있으면 다음 시작 때 같은 원본에서 없는 파일만 다시 복사한다. 판단은 폴더·파일·표시의 존재로만 한다.</li>
 *     <li><b>언제.</b> 앱이 기본 작업 공간을 잠근 직후, 설정을 처음 읽기 전이다. 인스턴스 설정 파일은 처음 읽을 때 메모리에 올라가고
 *     저장할 때 다시 쓰이므로 그 뒤에 복사하면 무시되거나 덮인다. 잠근 뒤라 다른 인스턴스와 동시에 복사하지 않는다.
 *     이름 변경 전 작업 공간이면 이 복사 뒤에 플랫폼 초기화의 {@code LegacyNameMigration}이 새 위치에서 이름을 바꾼다
 *     (위치 이전 → 이름 이전 순서). {@code -data}로 작업 공간을 고른 실행에서는 부르지 않는다.</li>
 *     <li><b>그대로 복사.</b> 파일 수정 시각을 유지한다(Eclipse 리소스 트리 스냅숏이 시각으로 동기화 여부를 본다). 옛 위치의 잠금 파일과
 *     {@code .metadata} 바로 아래 로그는 복사하지 않는다(진단용, {@code LegacyNameMigration}과 같다). 저장된 절대 경로(예: 사용자가
 *     바꾼 Query Library 루트)는 바꾸지 않는다. 옛 폴더를 지우지 않으므로 그 경로는 계속 열린다.</li>
 * </ul>
 * 범위와 이유는 docs/chromite/workspace-location.md에 있다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
final class WorkspaceLocationMigration {

    private static final Log log = Log.getLog(WorkspaceLocationMigration.class);

    /** One-time marker in the metadata folder of the default workspace. */
    static final String MARKER_FILE = "workspace-location-migration.properties"; //$NON-NLS-1$
    static final String STATE_COPYING = "copying"; //$NON-NLS-1$
    static final String STATE_DONE = "done"; //$NON-NLS-1$

    private static final String PROP_STATE = "state"; //$NON-NLS-1$
    private static final String PROP_SOURCE = "source"; //$NON-NLS-1$

    // Equinox's default instance area (<user.dir>/workspace), used by builds before the fix when -data was not given
    private static final String OLD_WORKSPACE_FOLDER = "workspace"; //$NON-NLS-1$
    private static final String USER_DIR_PROPERTY = "user.dir"; //$NON-NLS-1$
    // Equinox instance location lock file (.metadata/.lock)
    private static final String LOCK_FILE = ".lock"; //$NON-NLS-1$
    private static final String LOG_EXT = ".log"; //$NON-NLS-1$
    private static final String WORKSPACE_PROPS_FILE = DBConstants.WORKSPACE_PROPS_FILE;
    // Instance preferences of this bundle: .metadata/.plugins/org.eclipse.core.runtime/.settings/<bundle id>.prefs
    private static final String SWING_PREFS_FILE = DesktopApplicationImpl.WORKSPACE_PLUGINS_FOLDER + "/" //$NON-NLS-1$
        + DesktopApplicationImpl.CORE_RUNTIME_PLUGIN_ID + "/.settings/" + ChromiteSwingActivator.PLUGIN_ID + ".prefs"; //$NON-NLS-1$ //$NON-NLS-2$

    private WorkspaceLocationMigration() {
    }

    /**
     * Copies a workspace of builds before the fix into the default workspace, which the application has just locked.
     * Never throws: a failure is logged and startup continues with what was copied.
     *
     * @return a summary to log once the debug log is open, or {@code null} if nothing was copied
     */
    @Nullable
    static String migrate(@NotNull Path workspaceFolder) {
        try {
            return migrateWorkspace(workspaceFolder);
        } catch (Throwable e) {
            log.error("Error copying the workspace of builds before the workspace location fix to " + workspaceFolder, e);
            return null;
        }
    }

    @Nullable
    private static String migrateWorkspace(@NotNull Path workspaceFolder) throws IOException {
        Path metadataFolder = workspaceFolder.resolve(DBPWorkspace.METADATA_FOLDER);
        Path marker = metadataFolder.resolve(MARKER_FILE);
        Path source;
        if (Files.exists(marker)) {
            Properties props = readMarker(marker);
            if (!STATE_COPYING.equals(props.getProperty(PROP_STATE))) {
                return null;
            }
            // An earlier start was interrupted or failed to copy some files: copy what is still missing
            source = Path.of(props.getProperty(PROP_SOURCE, "")); //$NON-NLS-1$
            if (!Files.isDirectory(source)) {
                log.warn("Workspace " + source + " recorded in " + marker + " no longer exists; nothing to copy");
                return null;
            }
        } else {
            if (!isEmptyWorkspace(workspaceFolder)) {
                return null;
            }
            source = findOldWorkspace(workspaceFolder);
            if (source == null) {
                return null;
            }
        }

        writeMarker(marker, source, STATE_COPYING, null);
        Counter counter = new Counter();
        copyTree(source, workspaceFolder, counter);
        String summary = "Workspace " + source + " copied to " + workspaceFolder + ": " + counter;
        if (counter.failed == 0) {
            writeMarker(marker, source, STATE_DONE, counter);
        }
        return summary;
    }

    /**
     * Whether the workspace holds nothing but what locking it and logging may have created.
     */
    private static boolean isEmptyWorkspace(@NotNull Path workspaceFolder) throws IOException {
        if (!Files.isDirectory(workspaceFolder)) {
            return true;
        }
        try (Stream<Path> entries = Files.list(workspaceFolder)) {
            for (Path entry : entries.toList()) {
                if (!entry.getFileName().toString().equals(DBPWorkspace.METADATA_FOLDER) || !Files.isDirectory(entry)) {
                    return false;
                }
                try (Stream<Path> metadata = Files.list(entry)) {
                    if (metadata.anyMatch(file -> !isSkippedMetadataFile(file))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * The workspace folder of builds before the fix: in the installation folder (where the start menu shortcut starts
     * the application), else in the current folder.
     */
    @Nullable
    private static Path findOldWorkspace(@NotNull Path workspaceFolder) throws IOException {
        List<Path> candidates = new ArrayList<>();
        Location installLocation = Platform.getInstallLocation();
        if (installLocation != null && installLocation.getURL() != null) {
            candidates.add(RuntimeUtils.getLocalPathFromURL(installLocation.getURL()).resolve(OLD_WORKSPACE_FOLDER));
        }
        String userDir = System.getProperty(USER_DIR_PROPERTY);
        if (userDir != null) {
            candidates.add(Path.of(userDir).resolve(OLD_WORKSPACE_FOLDER));
        }
        Path target = workspaceFolder.toRealPath();
        for (Path candidate : candidates) {
            if (!isChromiteWorkspace(candidate)) {
                continue;
            }
            Path source = candidate.toRealPath();
            if (source.startsWith(target) || target.startsWith(source)) {
                // The default workspace itself (e.g. the workspace environment variable points there)
                continue;
            }
            return source;
        }
        return null;
    }

    private static boolean isChromiteWorkspace(@NotNull Path folder) {
        Path metadataFolder = folder.resolve(DBPWorkspace.METADATA_FOLDER);
        return Files.isRegularFile(metadataFolder.resolve(WORKSPACE_PROPS_FILE))
            || Files.isRegularFile(metadataFolder.resolve(SWING_PREFS_FILE));
    }

    // The lock file and the logs directly in .metadata belong to the instance that wrote them
    private static boolean isSkippedMetadataFile(@NotNull Path file) {
        String name = file.getFileName().toString();
        return name.equals(LOCK_FILE) || name.endsWith(LOG_EXT) || name.equals(MARKER_FILE);
    }

    private static void copyTree(@NotNull Path sourceFolder, @NotNull Path targetFolder, @NotNull Counter counter) throws IOException {
        Path sourceMetadata = sourceFolder.resolve(DBPWorkspace.METADATA_FOLDER);
        List<Path> entries;
        try (Stream<Path> walk = Files.walk(sourceFolder)) {
            entries = walk.toList();
        }
        for (Path entry : entries) {
            if (entry.equals(sourceFolder)
                || (sourceMetadata.equals(entry.getParent()) && Files.isRegularFile(entry) && isSkippedMetadataFile(entry))) {
                continue;
            }
            Path target = targetFolder.resolve(sourceFolder.relativize(entry).toString());
            if (Files.isDirectory(entry)) {
                // Empty folders too (e.g. a script folder without scripts)
                Files.createDirectories(target);
            } else if (Files.isRegularFile(entry)) {
                copyFile(entry, target, counter);
            }
        }
    }

    /**
     * Copies one file unless the target exists. Written under a temporary name first, so an interrupted copy never
     * leaves a partial file that the next start would keep as existing.
     */
    private static void copyFile(@NotNull Path source, @NotNull Path target, @NotNull Counter counter) {
        if (Files.exists(target)) {
            counter.kept++;
            log.debug("Kept existing " + target + "; copy of " + source + " not used");
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".migration-", ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$
            try {
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
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

    @NotNull
    private static Properties readMarker(@NotNull Path marker) throws IOException {
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(marker)) {
            props.load(is);
        }
        return props;
    }

    private static void writeMarker(
        @NotNull Path marker,
        @NotNull Path source,
        @NotNull String state,
        @Nullable Counter counter
    ) throws IOException {
        Properties props = new Properties();
        props.setProperty(PROP_STATE, state);
        props.setProperty(PROP_SOURCE, source.toString());
        props.setProperty("time", OffsetDateTime.now().toString()); //$NON-NLS-1$
        if (counter != null) {
            props.setProperty("result", counter.toString()); //$NON-NLS-1$
        }
        Files.createDirectories(marker.getParent());
        try (OutputStream os = Files.newOutputStream(marker)) {
            props.store(os, "Workspace of builds before the workspace location fix was copied here once; " //$NON-NLS-1$
                + "delete this file and empty the workspace to copy again"); //$NON-NLS-1$
        }
    }

    private static final class Counter {
        int files;
        int kept;
        int failed;

        @Override
        public String toString() {
            return "copied " + files + " file(s); kept " + kept + " existing; " + failed + " failed";
        }
    }
}
