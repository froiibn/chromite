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

import com.google.gson.JsonParseException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.connection.DBPDriverLibrary;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.registry.DataSourceProviderDescriptor;
import com.seizia.chromite.registry.DataSourceProviderRegistry;
import com.seizia.chromite.registry.internal.RegistryMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks and installs an offline driver install bundle ({@link DriverOfflineBundle}) on a PC without internet access.
 *
 * 설계:
 * <ul>
 *     <li>{@link #read}는 아무것도 쓰지 않는다. ZIP 중앙 디렉터리 검사, 매니페스트 검사(형식, 경로, 파일 목록과 실제 항목이 정확히 같은지),
 *     모든 파일의 크기와 SHA-256 확인을 마친 뒤에만 미리 보기를 만든다. 하나라도 틀리면 예외로 끝나 설치할 수 없다.</li>
 *     <li>{@link #install}은 필요한 파일을 드라이버 폴더 안의 임시 폴더로 풀면서 다시 SHA-256을 확인하고(미리 보기와 설치 사이에 파일이
 *     바뀌었을 수 있음), 모두 맞을 때만 제자리로 옮긴다. 그 뒤 드라이버 로더에 등록하고 {@code drivers.xml}에 저장한다.</li>
 *     <li>같은 파일(크기와 SHA-256이 같음)이 이미 있으면 복사하지 않는다. 내용이 다른 파일은 미리 보기에 "바뀜"으로 보이고, 사용자가 그것을
 *     보고 설치를 누르면 바꾼다.</li>
 *     <li>네트워크는 쓰지 않는다. Maven 해석도 하지 않고, 묶음이 기록한 해석 결과를 그대로 등록한다(오프라인 모드와 관계없이 같음).</li>
 *     <li>이 Chromite에 없는 드라이버(사용자 정의, 더 새 Chromite가 만든 묶음)는 미리 보기에 보이고 건너뛴다. 드라이버 정의에 없는 라이브러리도
 *     건너뛰고, 드라이버에 필요한데 묶음에 없는 라이브러리는 "일부만 있음"으로 알린다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/driver-offline-bundle.md)
 */
public final class DriverOfflineBundleInstaller {

    private static final Log log = Log.getLog(DriverOfflineBundleInstaller.class);

    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}"); //$NON-NLS-1$
    private static final String STAGING_FOLDER_PREFIX = ".bundle-install-"; //$NON-NLS-1$

    private DriverOfflineBundleInstaller() {
    }

    public enum DriverStatus {
        /** Some files are new, different or not registered yet. */
        INSTALL,
        /** Same files present and registered: nothing to do. */
        INSTALLED,
        /** Installable, but this Chromite's driver needs libraries the bundle does not have. */
        INCOMPLETE,
        /** No such driver in this Chromite: skipped. */
        NOT_FOUND
    }

    public enum FileStatus {
        NEW,
        PRESENT,
        DIFFERENT
    }

    public static final class DriverPreview {
        private final DriverOfflineBundle.DriverEntry entry;
        private final DriverDescriptor driver;
        private final DriverStatus status;
        private final Map<DBPDriverLibrary, DriverOfflineBundle.LibraryEntry> libraries;
        private final List<String> missingLibraries;

        DriverPreview(
            @NotNull DriverOfflineBundle.DriverEntry entry,
            @Nullable DriverDescriptor driver,
            @NotNull DriverStatus status,
            @NotNull Map<DBPDriverLibrary, DriverOfflineBundle.LibraryEntry> libraries,
            @NotNull List<String> missingLibraries
        ) {
            this.entry = entry;
            this.driver = driver;
            this.status = status;
            this.libraries = libraries;
            this.missingLibraries = missingLibraries;
        }

        @NotNull
        public DriverOfflineBundle.DriverEntry getEntry() {
            return entry;
        }

        @Nullable
        public DriverDescriptor getDriver() {
            return driver;
        }

        @NotNull
        public DriverStatus getStatus() {
            return status;
        }

        /**
         * Display names of the libraries this Chromite's driver needs but the bundle does not have.
         */
        @NotNull
        public List<String> getMissingLibraries() {
            return missingLibraries;
        }
    }

    public static final class FilePreview {
        private final DriverOfflineBundle.FileEntry entry;
        private final Path target;
        private final FileStatus status;

        FilePreview(@NotNull DriverOfflineBundle.FileEntry entry, @NotNull Path target, @NotNull FileStatus status) {
            this.entry = entry;
            this.target = target;
            this.status = status;
        }

        @NotNull
        public DriverOfflineBundle.FileEntry getEntry() {
            return entry;
        }

        @NotNull
        public Path getTarget() {
            return target;
        }

        @NotNull
        public FileStatus getStatus() {
            return status;
        }
    }

    /**
     * A checked bundle: every file matched its checksum when it was read. Files lists only the files of drivers found here.
     */
    public static final class Preview {
        private final Path bundle;
        private final DriverOfflineBundle.Manifest manifest;
        private final List<DriverPreview> drivers;
        private final Map<String, FilePreview> files;

        Preview(
            @NotNull Path bundle,
            @NotNull DriverOfflineBundle.Manifest manifest,
            @NotNull List<DriverPreview> drivers,
            @NotNull Map<String, FilePreview> files
        ) {
            this.bundle = bundle;
            this.manifest = manifest;
            this.drivers = drivers;
            this.files = files;
        }

        @NotNull
        public Path getBundle() {
            return bundle;
        }

        @NotNull
        public DriverOfflineBundle.Manifest getManifest() {
            return manifest;
        }

        @NotNull
        public List<DriverPreview> getDrivers() {
            return drivers;
        }

        @NotNull
        public Collection<FilePreview> getFiles() {
            return files.values();
        }

        public boolean hasChanges() {
            return drivers.stream().anyMatch(d -> d.status == DriverStatus.INSTALL || d.status == DriverStatus.INCOMPLETE);
        }
    }

    public record Result(int driverCount, int copiedCount, int presentCount, int replacedCount) {
    }

    //////////////////////////////////////////////////////////
    // Read and verify

    /**
     * Reads and fully verifies the bundle, then compares it with the installed drivers and files. Writes nothing.
     */
    @NotNull
    public static Preview read(@NotNull DBRProgressMonitor monitor, @NotNull Path bundle) throws DBException, InterruptedException {
        List<String> entryNames = DriverOfflineBundle.readEntryNames(bundle);
        DriverOfflineBundle.Manifest manifest;
        Map<String, DriverOfflineBundle.FileEntry> bundleFiles;
        try (ZipFile zip = new ZipFile(bundle.toFile(), StandardCharsets.UTF_8)) {
            if (zip.size() != entryNames.size()) {
                throw DriverOfflineBundle.invalid(RegistryMessages.driver_bundle_invalid_zip);
            }
            manifest = readManifest(zip);
            bundleFiles = validateManifest(manifest, entryNames);
            verifyFiles(monitor, zip, bundleFiles.values());
        } catch (IOException e) {
            throw new DBException(NLS.bind(RegistryMessages.driver_bundle_invalid, RegistryMessages.driver_bundle_invalid_zip), e);
        }
        return createPreview(monitor, bundle, manifest, bundleFiles);
    }

    @NotNull
    private static DriverOfflineBundle.Manifest readManifest(@NotNull ZipFile zip) throws IOException, DBException {
        ZipEntry manifestEntry = zip.getEntry(DriverOfflineBundle.MANIFEST_ENTRY);
        if (manifestEntry == null) {
            throw DriverOfflineBundle.invalid(RegistryMessages.driver_bundle_invalid_manifest);
        }
        byte[] data;
        try (InputStream input = zip.getInputStream(manifestEntry)) {
            data = input.readNBytes((int) DriverOfflineBundle.MAX_MANIFEST_SIZE + 1);
        }
        if (data.length > DriverOfflineBundle.MAX_MANIFEST_SIZE) {
            throw DriverOfflineBundle.tooLarge();
        }
        DriverOfflineBundle.Manifest manifest;
        try {
            manifest = DriverOfflineBundle.GSON.fromJson(new String(data, StandardCharsets.UTF_8), DriverOfflineBundle.Manifest.class);
        } catch (JsonParseException e) {
            log.debug("Malformed driver bundle manifest", e);
            throw DriverOfflineBundle.invalid(RegistryMessages.driver_bundle_invalid_manifest);
        }
        if (manifest == null || !DriverOfflineBundle.FORMAT_NAME.equals(manifest.getFormat())) {
            throw DriverOfflineBundle.invalid(RegistryMessages.driver_bundle_invalid_manifest);
        }
        if (manifest.getFormatVersion() != DriverOfflineBundle.FORMAT_VERSION) {
            throw DriverOfflineBundle.invalid(NLS.bind(RegistryMessages.driver_bundle_invalid_version,
                manifest.getFormatVersion(), DriverOfflineBundle.FORMAT_VERSION));
        }
        return manifest;
    }

    /**
     * The manifest must describe the archive exactly: every file entry listed once with a safe path, a size and a SHA-256,
     * no other entries, and every library file one of the listed files.
     */
    @NotNull
    private static Map<String, DriverOfflineBundle.FileEntry> validateManifest(
        @NotNull DriverOfflineBundle.Manifest manifest,
        @NotNull List<String> entryNames
    ) throws DBException {
        if (CommonUtils.isEmpty(manifest.getDrivers()) || manifest.getFiles() == null) {
            throw DriverOfflineBundle.invalid(RegistryMessages.driver_bundle_invalid_manifest);
        }
        Map<String, DriverOfflineBundle.FileEntry> files = new LinkedHashMap<>();
        long totalSize = 0;
        for (DriverOfflineBundle.FileEntry file : manifest.getFiles()) {
            if (file == null || !DriverOfflineBundle.isSafeRelativePath(file.getPath())) {
                throw DriverOfflineBundle.invalid(NLS.bind(RegistryMessages.driver_bundle_unsafe_entry, file == null ? null : file.getPath()));
            }
            if (file.getSize() < 0 || file.getSha256() == null || !SHA256_PATTERN.matcher(file.getSha256()).matches()
                || files.put(file.getPath(), file) != null) {
                throw DriverOfflineBundle.invalid(RegistryMessages.driver_bundle_invalid_manifest);
            }
            totalSize += file.getSize();
            if (totalSize > DriverOfflineBundle.MAX_TOTAL_SIZE) {
                throw DriverOfflineBundle.tooLarge();
            }
        }
        Set<String> expectedNames = new HashSet<>();
        expectedNames.add(DriverOfflineBundle.MANIFEST_ENTRY);
        files.keySet().forEach(path -> expectedNames.add(DriverOfflineBundle.FILES_PREFIX + path));
        for (String name : entryNames) {
            if (!expectedNames.remove(name)) {
                throw DriverOfflineBundle.invalid(NLS.bind(RegistryMessages.driver_bundle_invalid_files, name));
            }
        }
        if (!expectedNames.isEmpty()) {
            throw DriverOfflineBundle.invalid(NLS.bind(RegistryMessages.driver_bundle_invalid_files, expectedNames.iterator().next()));
        }
        for (DriverOfflineBundle.DriverEntry driver : manifest.getDrivers()) {
            if (driver == null || CommonUtils.isEmpty(driver.getProvider()) || CommonUtils.isEmpty(driver.getId())
                || CommonUtils.isEmpty(driver.getLibraries())) {
                throw DriverOfflineBundle.invalid(RegistryMessages.driver_bundle_invalid_manifest);
            }
            for (DriverOfflineBundle.LibraryEntry library : driver.getLibraries()) {
                if (library == null || CommonUtils.isEmpty(library.getId()) || CommonUtils.isEmpty(library.getFiles())) {
                    throw DriverOfflineBundle.invalid(RegistryMessages.driver_bundle_invalid_manifest);
                }
                for (DriverOfflineBundle.LibraryFile file : library.getFiles()) {
                    if (file == null || CommonUtils.isEmpty(file.getId()) || !files.containsKey(file.getPath())) {
                        throw DriverOfflineBundle.invalid(RegistryMessages.driver_bundle_invalid_manifest);
                    }
                    try {
                        file.getFileType();
                    } catch (RuntimeException e) {
                        throw DriverOfflineBundle.invalid(RegistryMessages.driver_bundle_invalid_manifest);
                    }
                }
            }
        }
        return files;
    }

    private static void verifyFiles(
        @NotNull DBRProgressMonitor monitor,
        @NotNull ZipFile zip,
        @NotNull Collection<DriverOfflineBundle.FileEntry> files
    ) throws IOException, DBException, InterruptedException {
        monitor.beginTask(NLS.bind(RegistryMessages.driver_bundle_task_verify, zip.getName()), files.size());
        try {
            byte[] buffer = new byte[1 << 16];
            for (DriverOfflineBundle.FileEntry file : files) {
                checkCanceled(monitor);
                monitor.subTask(NLS.bind(RegistryMessages.driver_bundle_task_verify, file.getPath()));
                copyVerified(zip, file, null, buffer);
                monitor.worked(1);
            }
        } finally {
            monitor.done();
        }
    }

    /**
     * Streams one file entry, optionally into {@code output}, and fails unless its size and SHA-256 are the declared ones.
     * Reading stops past the declared size, so a forged entry cannot expand without limit.
     */
    private static void copyVerified(
        @NotNull ZipFile zip,
        @NotNull DriverOfflineBundle.FileEntry file,
        @Nullable OutputStream output,
        @NotNull byte[] buffer
    ) throws IOException, DBException {
        ZipEntry entry = zip.getEntry(DriverOfflineBundle.FILES_PREFIX + file.getPath());
        if (entry == null) {
            throw DriverOfflineBundle.invalid(NLS.bind(RegistryMessages.driver_bundle_invalid_files, file.getPath()));
        }
        MessageDigest digest = DriverOfflineBundle.createDigest();
        long size = 0;
        try (InputStream input = zip.getInputStream(entry)) {
            for (int read; (read = input.read(buffer)) > 0; ) {
                size += read;
                if (size > file.getSize()) {
                    break;
                }
                digest.update(buffer, 0, read);
                if (output != null) {
                    output.write(buffer, 0, read);
                }
            }
        }
        if (size != file.getSize() || !DriverOfflineBundle.toHex(digest.digest()).equals(file.getSha256())) {
            throw DriverOfflineBundle.invalid(NLS.bind(RegistryMessages.driver_bundle_checksum_mismatch, file.getPath()));
        }
    }

    @NotNull
    private static Preview createPreview(
        @NotNull DBRProgressMonitor monitor,
        @NotNull Path bundle,
        @NotNull DriverOfflineBundle.Manifest manifest,
        @NotNull Map<String, DriverOfflineBundle.FileEntry> bundleFiles
    ) throws DBException, InterruptedException {
        Path driversHome = DriverOfflineBundle.getDriversHome();
        Map<String, FilePreview> files = new LinkedHashMap<>();
        List<DriverPreview> drivers = new ArrayList<>();
        monitor.beginTask(RegistryMessages.driver_bundle_task_compare, manifest.getDrivers().size());
        try {
            for (DriverOfflineBundle.DriverEntry entry : manifest.getDrivers()) {
                checkCanceled(monitor);
                DriverDescriptor driver = findDriver(entry);
                if (driver == null) {
                    drivers.add(new DriverPreview(entry, null, DriverStatus.NOT_FOUND, Map.of(), List.of()));
                    continue;
                }
                Map<String, DBPDriverLibrary> driverLibraries = new LinkedHashMap<>();
                for (DBPDriverLibrary library : driver.getDriverLibraries()) {
                    driverLibraries.putIfAbsent(library.getId(), library);
                }
                // Libraries the driver definition does not have any more are skipped
                Map<DBPDriverLibrary, DriverOfflineBundle.LibraryEntry> matched = new LinkedHashMap<>();
                for (DriverOfflineBundle.LibraryEntry library : entry.getLibraries()) {
                    DBPDriverLibrary driverLibrary = driverLibraries.get(library.getId());
                    if (driverLibrary != null && !driverLibrary.isDisabled()) {
                        matched.put(driverLibrary, library);
                    }
                }
                if (matched.isEmpty()) {
                    drivers.add(new DriverPreview(entry, null, DriverStatus.NOT_FOUND, Map.of(), List.of()));
                    continue;
                }
                List<String> missing = new ArrayList<>();
                for (DBPDriverLibrary library : driverLibraries.values()) {
                    if (!library.isDisabled() && library.isDownloadable() && library.matchesCurrentPlatform() && !library.isOptional()
                        && library.getType() != DBPDriverLibrary.FileType.license && !matched.containsKey(library)) {
                        missing.add(library.getDisplayName());
                    }
                }
                boolean installed = true;
                for (Map.Entry<DBPDriverLibrary, DriverOfflineBundle.LibraryEntry> library : matched.entrySet()) {
                    Set<Path> registered = new HashSet<>();
                    Collection<DriverFileInfo> registeredFiles = driver.getDefaultDriverLoader().getLibraryFiles(library.getKey());
                    if (registeredFiles != null) {
                        registeredFiles.forEach(info -> registered.add(info.getFile() == null ? null : info.getFile().toAbsolutePath().normalize()));
                    }
                    Set<Path> bundled = new HashSet<>();
                    for (DriverOfflineBundle.LibraryFile file : library.getValue().getFiles()) {
                        FilePreview filePreview = files.get(file.getPath());
                        if (filePreview == null) {
                            filePreview = compareFile(driversHome, bundleFiles.get(file.getPath()));
                            files.put(file.getPath(), filePreview);
                        }
                        bundled.add(filePreview.getTarget());
                        installed &= filePreview.getStatus() == FileStatus.PRESENT;
                    }
                    installed &= registered.equals(bundled);
                }
                DriverStatus status = !missing.isEmpty() ? DriverStatus.INCOMPLETE : installed ? DriverStatus.INSTALLED : DriverStatus.INSTALL;
                drivers.add(new DriverPreview(entry, driver, status, matched, missing));
                monitor.worked(1);
            }
        } finally {
            monitor.done();
        }
        return new Preview(bundle, manifest, drivers, files);
    }

    @Nullable
    private static DriverDescriptor findDriver(@NotNull DriverOfflineBundle.DriverEntry entry) {
        DataSourceProviderDescriptor provider = DataSourceProviderRegistry.getInstance().getDataSourceProvider(entry.getProvider());
        DBPDriver driver = provider == null ? null : provider.getDriver(entry.getId());
        return driver instanceof DriverDescriptor descriptor && !descriptor.isDisabled() ? descriptor : null;
    }

    @NotNull
    private static FilePreview compareFile(@NotNull Path driversHome, @NotNull DriverOfflineBundle.FileEntry file) throws DBException {
        Path target = DriverOfflineBundle.resolveTarget(driversHome, file.getPath());
        DriverOfflineBundle.checkTargetPath(driversHome, target);
        if (!Files.exists(target)) {
            return new FilePreview(file, target, FileStatus.NEW);
        }
        try {
            boolean same = Files.size(target) == file.getSize() && DriverOfflineBundle.sha256(target).equals(file.getSha256());
            return new FilePreview(file, target, same ? FileStatus.PRESENT : FileStatus.DIFFERENT);
        } catch (IOException e) {
            log.debug("Error reading '" + target + "'", e);
            return new FilePreview(file, target, FileStatus.DIFFERENT);
        }
    }

    //////////////////////////////////////////////////////////
    // Install

    /**
     * Installs the files of the found drivers and registers them. Nothing is moved into place unless every needed file
     * matched its checksum again while being unpacked.
     */
    @NotNull
    public static Result install(@NotNull DBRProgressMonitor monitor, @NotNull Preview preview) throws DBException, InterruptedException {
        Path driversHome = DriverOfflineBundle.getDriversHome();
        List<FilePreview> toCopy = new ArrayList<>();
        int present = 0;
        int replaced = 0;
        for (FilePreview file : preview.getFiles()) {
            // A file seen as present may have gone since the preview
            if (file.getStatus() == FileStatus.PRESENT && Files.isRegularFile(file.getTarget())) {
                present++;
            } else {
                toCopy.add(file);
                if (file.getStatus() == FileStatus.DIFFERENT) {
                    replaced++;
                }
            }
        }

        Path staging = driversHome.resolve(STAGING_FOLDER_PREFIX + UUID.randomUUID());
        try {
            Map<FilePreview, Path> staged = new LinkedHashMap<>();
            if (!toCopy.isEmpty()) {
                Files.createDirectories(staging);
                monitor.beginTask(NLS.bind(RegistryMessages.driver_bundle_task_verify, preview.getBundle().getFileName()), toCopy.size());
                try (ZipFile zip = new ZipFile(preview.getBundle().toFile(), StandardCharsets.UTF_8)) {
                    byte[] buffer = new byte[1 << 16];
                    for (FilePreview file : toCopy) {
                        checkCanceled(monitor);
                        monitor.subTask(NLS.bind(RegistryMessages.driver_bundle_task_verify, file.getEntry().getPath()));
                        Path stagedFile = staging.resolve(staged.size() + ".part"); //$NON-NLS-1$
                        try (OutputStream output = Files.newOutputStream(stagedFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                            copyVerified(zip, file.getEntry(), output, buffer);
                        }
                        staged.put(file, stagedFile);
                        monitor.worked(1);
                    }
                } finally {
                    monitor.done();
                }
            }
            // Every file is verified: move them into place (no cancel from here on)
            monitor.beginTask(NLS.bind(RegistryMessages.driver_bundle_task_install, preview.getBundle().getFileName()), staged.size());
            try {
                for (Map.Entry<FilePreview, Path> file : staged.entrySet()) {
                    Path target = file.getKey().getTarget();
                    monitor.subTask(NLS.bind(RegistryMessages.driver_bundle_task_install, file.getKey().getEntry().getPath()));
                    DriverOfflineBundle.checkTargetPath(driversHome, target);
                    Files.createDirectories(target.getParent());
                    try {
                        Files.move(file.getValue(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(file.getValue(), target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    monitor.worked(1);
                }
            } finally {
                monitor.done();
            }
        } catch (IOException e) {
            throw new DBException(NLS.bind(RegistryMessages.driver_bundle_install_failed, e.getMessage()), e);
        } finally {
            deleteStaging(staging);
        }

        int driverCount = 0;
        for (DriverPreview driver : preview.getDrivers()) {
            if (driver.driver == null || driver.status == DriverStatus.NOT_FOUND) {
                continue;
            }
            Map<DBPDriverLibrary, List<DriverFileInfo>> registrations = new LinkedHashMap<>();
            for (Map.Entry<DBPDriverLibrary, DriverOfflineBundle.LibraryEntry> library : driver.libraries.entrySet()) {
                List<DriverFileInfo> fileInfos = new ArrayList<>();
                for (DriverOfflineBundle.LibraryFile file : library.getValue().getFiles()) {
                    Path target = preview.files.get(file.getPath()).getTarget();
                    fileInfos.add(new DriverFileInfo(file.getId(), file.getVersion(), file.getFileType(), target, target.toString()));
                }
                registrations.put(library.getKey(), fileInfos);
            }
            DriverOfflineBundle.registerLibraryFiles(driver.driver, registrations);
            driverCount++;
        }
        DataSourceProviderRegistry.getInstance().saveDrivers();
        return new Result(driverCount, toCopy.size() - replaced, present, replaced);
    }

    private static void deleteStaging(@NotNull Path staging) {
        if (!Files.exists(staging)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(staging)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("Error deleting temporary folder '" + staging + "'", e);
        }
    }

    private static void checkCanceled(@NotNull DBRProgressMonitor monitor) throws InterruptedException {
        if (monitor.isCanceled()) {
            throw new InterruptedException();
        }
    }
}
