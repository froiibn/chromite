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
package com.seizia.chromite.registry.driver;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.connection.DBPDriverDependencies;
import com.seizia.chromite.model.connection.DBPDriverLibrary;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.registry.DataSourceProviderRegistry;
import com.seizia.chromite.registry.internal.RegistryMessages;
import com.seizia.chromite.registry.maven.MavenArtifactLicense;
import com.seizia.chromite.registry.maven.MavenArtifactVersion;
import com.seizia.chromite.utils.GeneralUtils;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Creates an offline driver install bundle ({@link DriverOfflineBundle}) on a PC with internet access.
 *
 * 설계: 드라이버 파일은 평소 다운로드와 같은 경로로 해석하고 받는다. 라이브러리 의존성은 {@link DriverDependencies}(Maven 전이 의존성 포함),
 * 받기는 라이브러리의 {@code downloadLibraryFile}(Maven·URL·{@code repo:} 모두, 이미 받은 파일은 다시 받지 않음)이다.
 * 다운로드 확인 대화상자({@code UIServiceDrivers})는 띄우지 않는다. 사용자가 이미 드라이버를 골라 묶음을 만들기로 했기 때문이다.
 * 평소 다운로드처럼 받은 파일은 이 PC의 드라이버에도 등록된다({@link DriverOfflineBundle#registerLibraryFiles}).
 * 필수 파일을 받지 못하면 묶음을 만들지 않는다. 선택 라이브러리와 라이선스 파일만 경고로 남기고 뺀다(평소 다운로드도 이 둘 없이 연결된다).
 * 라이브러리는 이 PC의 OS·아키텍처에 맞는 것만 넣는다(해석기가 돌려주는 그대로, DuckDB처럼 플랫폼별 jar가 있는 드라이버 포함).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/driver-offline-bundle.md)
 */
public final class DriverOfflineBundleWriter {

    private static final Log log = Log.getLog(DriverOfflineBundleWriter.class);

    private DriverOfflineBundleWriter() {
    }

    /**
     * What was written; warnings name the optional or licence files left out.
     */
    public record Result(int driverCount, int fileCount, long totalSize, @NotNull List<String> warnings) {
    }

    /**
     * Drivers that have files to download on this PC. Bundled drivers (product bundles) and drivers with local files only have none.
     */
    public static boolean hasDownloadableLibraries(@NotNull DriverDescriptor driver) {
        return !getDownloadableLibraries(driver).isEmpty();
    }

    /**
     * Downloads every file of the drivers (with Maven dependencies) and writes them to {@code target} with a manifest.
     * The target is written to a temporary file next to it first, so a failed or canceled run leaves no partial bundle.
     */
    @NotNull
    public static Result create(
        @NotNull DBRProgressMonitor monitor,
        @NotNull Collection<DriverDescriptor> drivers,
        @NotNull Path target
    ) throws DBException, InterruptedException {
        Path driversHome = DriverOfflineBundle.getDriversHome();
        Map<String, Path> files = new LinkedHashMap<>();
        List<DriverOfflineBundle.DriverEntry> driverEntries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        monitor.beginTask(RegistryMessages.driver_bundle_task_create, drivers.size());
        try {
            for (DriverDescriptor driver : drivers) {
                checkCanceled(monitor);
                DriverOfflineBundle.DriverEntry entry = collectDriver(monitor, driver, driversHome, files, warnings);
                if (entry != null) {
                    driverEntries.add(entry);
                }
                monitor.worked(1);
            }
        } finally {
            monitor.done();
        }
        // Registered like a normal download (drivers.xml), also when writing the bundle fails below
        try {
            DataSourceProviderRegistry.getInstance().saveDrivers();
        } catch (DBException e) {
            log.error("Error saving drivers config", e);
        }

        long totalSize = 0;
        for (Path file : files.values()) {
            try {
                totalSize += Files.size(file);
            } catch (IOException e) {
                throw new DBException(NLS.bind(RegistryMessages.driver_bundle_write_failed, target, e.getMessage()), e);
            }
        }
        if (files.size() + 1 > DriverOfflineBundle.MAX_ENTRIES || totalSize > DriverOfflineBundle.MAX_TOTAL_SIZE) {
            throw DriverOfflineBundle.tooLarge();
        }
        writeArchive(monitor, target, files, driverEntries);
        return new Result(driverEntries.size(), files.size(), totalSize, warnings);
    }

    @NotNull
    private static List<DBPDriverLibrary> getDownloadableLibraries(@NotNull DriverDescriptor driver) {
        List<DBPDriverLibrary> libraries = new ArrayList<>();
        for (DBPDriverLibrary library : driver.getDriverLibraries()) {
            if (!library.isDisabled() && library.isDownloadable() && library.matchesCurrentPlatform()) {
                libraries.add(library);
            }
        }
        return libraries;
    }

    /**
     * Optional libraries and licence texts may be missing: a normal download goes on without them as well.
     */
    private static boolean canSkip(@NotNull DBPDriverLibrary library) {
        return library.isOptional() || library.getType() == DBPDriverLibrary.FileType.license;
    }

    @Nullable
    private static DriverOfflineBundle.DriverEntry collectDriver(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DriverDescriptor driver,
        @NotNull Path driversHome,
        @NotNull Map<String, Path> files,
        @NotNull List<String> warnings
    ) throws DBException, InterruptedException {
        List<DBPDriverLibrary> roots = getDownloadableLibraries(driver);
        if (roots.isEmpty()) {
            return null;
        }
        monitor.subTask(NLS.bind(RegistryMessages.driver_bundle_task_resolve, driver.getFullName()));
        DriverDependencies dependencies = new DriverDependencies(roots);
        try {
            dependencies.resolveDependencies(monitor);
        } catch (DBException e) {
            // Resolution goes on after a failed library: only the failed roots are missing from the result
            List<DBPDriverLibrary> resolved = dependencies.getLibraryMap().stream().map(node -> node.library).toList();
            for (DBPDriverLibrary root : roots) {
                if (resolved.contains(root)) {
                    continue;
                }
                if (!canSkip(root)) {
                    throw new DBException(NLS.bind(RegistryMessages.driver_bundle_resolve_failed, driver.getFullName(), getMessage(e)), e);
                }
                warnings.add(NLS.bind(RegistryMessages.driver_bundle_skipped_file, driver.getFullName(), root.getDisplayName()));
            }
        }

        for (DBPDriverDependencies.DependencyNode node : dependencies.getLibraryList()) {
            checkCanceled(monitor);
            DBPDriverLibrary library = node.library;
            String taskName = NLS.bind(RegistryMessages.driver_bundle_task_download, library.getDisplayName());
            monitor.subTask(taskName);
            try {
                library.downloadLibraryFile(monitor, false, taskName);
            } catch (IOException e) {
                if (!canSkip(library)) {
                    throw new DBException(NLS.bind(RegistryMessages.driver_bundle_download_failed,
                        new Object[]{library.getDisplayName(), driver.getFullName(), e.getMessage()}), e);
                }
                log.debug("Optional driver file '" + library.getDisplayName() + "' not downloaded: " + e.getMessage());
            }
        }

        List<DriverOfflineBundle.LibraryEntry> libraryEntries = new ArrayList<>();
        Map<DBPDriverLibrary, List<DriverFileInfo>> registrations = new LinkedHashMap<>();
        for (DBPDriverDependencies.DependencyNode root : dependencies.getLibraryMap()) {
            List<DriverOfflineBundle.LibraryFile> libraryFiles = new ArrayList<>();
            List<DriverFileInfo> fileInfos = new ArrayList<>();
            if (!collectFiles(monitor, driver, root, driversHome, files, libraryFiles, fileInfos)) {
                warnings.add(NLS.bind(RegistryMessages.driver_bundle_skipped_file, driver.getFullName(), root.library.getDisplayName()));
                continue;
            }
            libraryEntries.add(new DriverOfflineBundle.LibraryEntry(
                root.library.getId(), root.library.getPath(), root.library.getType().name(), libraryFiles));
            registrations.put(root.library, fileInfos);
        }
        if (libraryEntries.isEmpty()) {
            return null;
        }
        DriverOfflineBundle.registerLibraryFiles(driver, registrations);
        return new DriverOfflineBundle.DriverEntry(
            driver.getProviderDescriptor().getId(), driver.getId(), driver.getName(), libraryEntries);
    }

    /**
     * Collects the files of a dependency tree like the loader does after a download ({@code collectLibraryFiles}: the node, then its
     * non-duplicate dependencies). Returns false when a skippable library's own file is missing.
     */
    private static boolean collectFiles(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DriverDescriptor driver,
        @NotNull DBPDriverDependencies.DependencyNode node,
        @NotNull Path driversHome,
        @NotNull Map<String, Path> files,
        @NotNull List<DriverOfflineBundle.LibraryFile> libraryFiles,
        @NotNull List<DriverFileInfo> fileInfos
    ) throws DBException {
        if (node.duplicate) {
            return true;
        }
        DBPDriverLibrary library = node.library;
        Path localFile = library.getLocalFile();
        if (localFile == null || !Files.isRegularFile(localFile)) {
            if (canSkip(library)) {
                return false;
            }
            throw new DBException(NLS.bind(RegistryMessages.driver_bundle_download_failed,
                new Object[]{library.getDisplayName(), driver.getFullName(), String.valueOf(localFile)}));
        }
        Path file = localFile.toAbsolutePath().normalize();
        String relativePath = file.startsWith(driversHome)
            ? driversHome.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/") //$NON-NLS-1$
            : null;
        if (relativePath == null || !DriverOfflineBundle.isSafeRelativePath(relativePath)) {
            // E.g. the install folder's maven-local repository: not a download, so there is no place for it on the other PC
            throw new DBException(NLS.bind(RegistryMessages.driver_bundle_outside_folder, file, driversHome));
        }
        files.putIfAbsent(relativePath, file);

        String coordinates = null;
        String url;
        List<String> licenses = null;
        if (library instanceof DriverLibraryMavenArtifact mavenLibrary && mavenLibrary.getArtifactVersion(monitor) != null) {
            MavenArtifactVersion version = mavenLibrary.getArtifactVersion(monitor);
            coordinates = version.getGroupId() + ":" + version.getArtifactId() //$NON-NLS-1$
                + (CommonUtils.isEmpty(version.getClassifier()) ? "" : ":" + version.getClassifier()) //$NON-NLS-1$ //$NON-NLS-2$
                + ":" + version.getVersion(); //$NON-NLS-1$
            url = version.getExternalURL();
            licenses = getLicenses(version);
        } else {
            url = library.getExternalURL(monitor);
        }
        libraryFiles.add(new DriverOfflineBundle.LibraryFile(
            library.getId(), library.getVersion(), library.getType().name(), relativePath, coordinates, url, licenses));
        fileInfos.add(new DriverFileInfo(library.getId(), library.getVersion(), library.getType(), file, file.toString()));

        for (DBPDriverDependencies.DependencyNode dependency : node.dependencies) {
            if (!collectFiles(monitor, driver, dependency, driversHome, files, libraryFiles, fileInfos)) {
                // Only skippable libraries return false and dependencies are never skippable
                return false;
            }
        }
        return true;
    }

    /**
     * Licence names from the POM, else from the nearest parent POM that declares them (many artifacts inherit them).
     */
    @Nullable
    private static List<String> getLicenses(@NotNull MavenArtifactVersion version) {
        for (MavenArtifactVersion current = version; current != null; current = current.getParent()) {
            if (!CommonUtils.isEmpty(current.getLicenses())) {
                List<String> names = new ArrayList<>();
                for (MavenArtifactLicense license : current.getLicenses()) {
                    names.add(CommonUtils.isEmpty(license.getUrl()) ? license.getName() : license.getName() + " (" + license.getUrl() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return names;
            }
        }
        return null;
    }

    private static void writeArchive(
        @NotNull DBRProgressMonitor monitor,
        @NotNull Path target,
        @NotNull Map<String, Path> files,
        @NotNull List<DriverOfflineBundle.DriverEntry> driverEntries
    ) throws DBException, InterruptedException {
        Path tempFile = target.resolveSibling(target.getFileName() + ".part"); //$NON-NLS-1$
        List<DriverOfflineBundle.FileEntry> fileEntries = new ArrayList<>();
        monitor.beginTask(NLS.bind(RegistryMessages.driver_bundle_task_write, target.getFileName()), files.size());
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tempFile)))) {
            // Driver jars are compressed already: deflating them again gains little and costs time
            zip.setLevel(Deflater.BEST_SPEED);
            byte[] buffer = new byte[1 << 16];
            for (Map.Entry<String, Path> file : files.entrySet()) {
                checkCanceled(monitor);
                monitor.subTask(NLS.bind(RegistryMessages.driver_bundle_task_write, file.getKey()));
                MessageDigest digest = DriverOfflineBundle.createDigest();
                long size = 0;
                zip.putNextEntry(new ZipEntry(DriverOfflineBundle.FILES_PREFIX + file.getKey()));
                try (InputStream input = Files.newInputStream(file.getValue())) {
                    for (int read; (read = input.read(buffer)) > 0; ) {
                        digest.update(buffer, 0, read);
                        zip.write(buffer, 0, read);
                        size += read;
                    }
                }
                zip.closeEntry();
                fileEntries.add(new DriverOfflineBundle.FileEntry(file.getKey(), size, DriverOfflineBundle.toHex(digest.digest())));
                monitor.worked(1);
            }
            DriverOfflineBundle.Manifest manifest = new DriverOfflineBundle.Manifest(
                GeneralUtils.getProductVersion().toString(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                Platform.getOS(),
                Platform.getOSArch(),
                driverEntries,
                fileEntries);
            // The manifest goes last: it carries the checksums computed while writing the files
            zip.putNextEntry(new ZipEntry(DriverOfflineBundle.MANIFEST_ENTRY));
            zip.write(DriverOfflineBundle.GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            deleteQuietly(tempFile);
            throw new DBException(NLS.bind(RegistryMessages.driver_bundle_write_failed, target, e.getMessage()), e);
        } catch (InterruptedException | RuntimeException e) {
            deleteQuietly(tempFile);
            throw e;
        } finally {
            monitor.done();
        }
        try {
            try {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(tempFile);
            throw new DBException(NLS.bind(RegistryMessages.driver_bundle_write_failed, target, e.getMessage()), e);
        }
    }

    private static void checkCanceled(@NotNull DBRProgressMonitor monitor) throws InterruptedException {
        if (monitor.isCanceled()) {
            throw new InterruptedException();
        }
    }

    @NotNull
    private static String getMessage(@NotNull Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        return CommonUtils.notEmpty(cause.getMessage());
    }

    private static void deleteQuietly(@NotNull Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Error deleting '" + file + "'", e);
        }
    }
}
