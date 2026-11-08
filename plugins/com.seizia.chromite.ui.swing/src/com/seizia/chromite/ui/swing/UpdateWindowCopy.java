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
import com.seizia.chromite.model.update.UpdateRun;
import com.seizia.chromite.ui.swing.update.UpdateWindowApp;
import com.seizia.chromite.utils.RuntimeUtils;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Makes the copy of this app that shows the update window while the installer runs: {@code <updates>/window-app/}.
 *
 * 설계: Turbrix #747의 앱 사본({@code electron/updateRun.cjs} {@code copyList}·{@code copyRuntime})을 옮겼다
 * (docs/chromite/auto-update.md "업데이트 진행 창"). Turbrix는 Electron 실행 파일 일부를 복사하고, Chromite는 진행 창 하나를 띄우는 데
 * 필요한 것만 복사한다.
 * <ul>
 *     <li>{@code jre/}: 실행 중인 Java 런타임({@code java.home}, 설치본에서는 설치 폴더의 {@code jre}) 전체. 설치 폴더 밖이어야 한다:
 *     설치 프로그램은 설치 폴더의 {@code jre\bin\server\jvm.dll}이 풀리기를 기다리고 옛 파일을 지운다. 하드 링크는 쓰지 않는다
 *     (같은 파일이라 잠긴다).</li>
 *     <li>{@code lib/}: 진입점({@link UpdateWindowApp})이 든 이 번들의 jar, 그 안에 든 FlatLaf jar, 업데이트 모델 번들, gson,
 *     {@code NLS}가 든 OSGi 프레임워크 jar. 진입점은 이 jar들만 클래스 경로로 쓰는 평범한 Java 프로그램이다.</li>
 * </ul>
 * 번들 파일은 OSGi에게 물어 찾는다({@link FileLocator#getBundleFileLocation}). 번들이 폴더면(개발 실행) 폴더째 복사한다.
 * 크기와 걸린 시간은 디버그 로그와 업데이트 도우미 기록에 남긴다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 업데이트 진행 창 (docs/chromite/auto-update.md "업데이트 진행 창")
 */
final class UpdateWindowCopy {

    private static final Log log = Log.getLog(UpdateWindowCopy.class);

    private static final String JRE_FOLDER = "jre"; //$NON-NLS-1$
    private static final String LIB_FOLDER = "lib"; //$NON-NLS-1$
    /**
     * FlatLaf is embedded in this bundle (Bundle-ClassPath).
     */
    private static final String FLATLAF_ENTRY = "lib/flatlaf.jar"; //$NON-NLS-1$
    private static final String GSON_BUNDLE = "com.google.gson"; //$NON-NLS-1$
    private static final String PROP_FRAMEWORK = "osgi.framework"; //$NON-NLS-1$

    private UpdateWindowCopy() {
    }

    /**
     * Makes the copy (an earlier one is removed first) and returns the command that starts it.
     */
    @NotNull
    static List<String> make(@NotNull UpdateRun run) throws IOException {
        long started = System.currentTimeMillis();
        Path dest = run.getCopyFolder();
        run.removeCopy();
        Path jre = dest.resolve(JRE_FOLDER);
        copyTree(Path.of(System.getProperty("java.home")), jre); //$NON-NLS-1$
        Path lib = dest.resolve(LIB_FOLDER);
        Files.createDirectories(lib);
        Bundle ui = FrameworkUtil.getBundle(UpdateWindowApp.class);
        List<Path> classPath = new ArrayList<>();
        classPath.add(copyBundle(ui, lib));
        classPath.add(copyEntry(ui, FLATLAF_ENTRY, lib));
        classPath.add(copyBundle(FrameworkUtil.getBundle(UpdateRun.class), lib));
        classPath.add(copyBundle(Platform.getBundle(GSON_BUNDLE), lib));
        classPath.add(copyFile(frameworkJar(), lib));
        Path java = jre.resolve("bin").resolve(RuntimeUtils.isWindows() ? "javaw.exe" : "java"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!Files.isRegularFile(java)) {
            throw new IOException("no Java program in the copy: " + java);
        }
        long size = sizeOf(dest);
        long ms = System.currentTimeMillis() - started;
        log.debug("Update window copy: " + size + " bytes in " + ms + " ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run.log("update window copy made: " + size + " bytes in " + ms + " ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return List.of(
            java.toString(),
            "-Xmx128m", //$NON-NLS-1$
            // The window has no frame of FlatLaf's: its native library is not needed (nothing is unpacked to the temp folder)
            "-Dflatlaf.useNativeLibrary=false", //$NON-NLS-1$
            "-cp", //$NON-NLS-1$
            classPath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)),
            UpdateWindowApp.class.getName(),
            run.getFolder().toString());
    }

    @NotNull
    private static Path copyBundle(@Nullable Bundle bundle, @NotNull Path lib) throws IOException {
        if (bundle == null) {
            throw new IOException("a bundle of the update window is missing"); //$NON-NLS-1$
        }
        File location = FileLocator.getBundleFileLocation(bundle)
            .orElseThrow(() -> new IOException("no file of bundle " + bundle.getSymbolicName()));
        Path source = location.toPath();
        Path target = lib.resolve(source.getFileName().toString());
        if (Files.isDirectory(source)) {
            copyTree(source, target);
        } else {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    @NotNull
    private static Path copyEntry(@NotNull Bundle bundle, @NotNull String entry, @NotNull Path lib) throws IOException {
        URL url = bundle.getEntry(entry);
        if (url == null) {
            throw new IOException("no " + entry + " in bundle " + bundle.getSymbolicName());
        }
        Path target = lib.resolve(Path.of(entry).getFileName().toString());
        try (InputStream in = url.openStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    @NotNull
    private static Path copyFile(@NotNull Path source, @NotNull Path lib) throws IOException {
        Path target = lib.resolve(source.getFileName().toString());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /**
     * The OSGi framework's jar (it holds {@link NLS}): where its classes come from, else the launcher's
     * {@value #PROP_FRAMEWORK} property.
     */
    @NotNull
    private static Path frameworkJar() throws IOException {
        try {
            CodeSource source = NLS.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path path = Path.of(source.getLocation().toURI());
                if (Files.isRegularFile(path)) {
                    return path;
                }
            }
            String framework = System.getProperty(PROP_FRAMEWORK);
            if (framework != null) {
                Path path = RuntimeUtils.getLocalPathFromURL(URI.create(framework).toURL());
                if (Files.isRegularFile(path)) {
                    return path;
                }
            }
        } catch (URISyntaxException | RuntimeException e) {
            throw new IOException("the framework's jar was not found: " + e.getMessage(), e);
        }
        throw new IOException("the framework's jar was not found"); //$NON-NLS-1$
    }

    private static void copyTree(@NotNull Path source, @NotNull Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static long sizeOf(@NotNull Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }
}
