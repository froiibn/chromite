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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.seizia.chromite.model.connection.DBPDriverLibrary;
import com.seizia.chromite.registry.internal.RegistryMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.ByteNumberFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline driver install bundle: one ZIP file with the downloaded files of the chosen drivers, made on a PC with internet
 * access and installed on a PC in a closed network (docs/chromite/driver-offline-bundle.md).
 *
 * 설계:
 * <ul>
 *     <li>파일은 온라인 다운로드가 쓰는 드라이버 폴더({@link DriverDescriptor#getCustomDriversHome()}, 사용자별 데이터 폴더의 {@code drivers})
 *     기준 상대 경로로 담는다. 설치는 같은 상대 경로에 파일을 놓고 드라이버 로더에 "내려받은 파일"로 등록하므로({@link #registerLibraryFiles})
 *     로더는 새 조회 경로 없이 평소처럼 파일을 찾고, 드라이버 관리자는 "다운로드됨"으로 보인다. 설치 폴더는 쓰지 않는다
 *     (관리자 설치면 쓸 수 없고 업데이트가 통째로 바꾼다).</li>
 *     <li>형식은 평범한 ZIP이다. {@value #MANIFEST_ENTRY}(매니페스트: 형식 이름·버전, Chromite 버전, 만든 시각과 플랫폼, 드라이버별
 *     라이브러리와 해석된 파일, 파일 목록과 SHA-256)와 {@value #FILES_PREFIX}&lt;상대 경로&gt; 파일들만 들어간다. 다른 항목이 있으면 거부한다.</li>
 *     <li>ZIP 안전 검사(zip-slip)는 {@link java.util.zip.ZipFile}이 알려 주지 않는 링크 항목까지 막으려고 중앙 디렉터리를 직접 읽는다
 *     ({@link #readEntryNames}). 항목 수와 전체 크기 상한을 두고, ZIP64(4 GiB·65,535개 초과)는 상한을 넘으므로 지원하지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/driver-offline-bundle.md)
 */
public final class DriverOfflineBundle {

    /** File name extension of a bundle, without the dot. */
    public static final String FILE_EXTENSION = "chromite-drivers"; //$NON-NLS-1$
    public static final String FORMAT_NAME = "chromite-driver-bundle"; //$NON-NLS-1$
    public static final int FORMAT_VERSION = 1;

    /** Archive limits. "All drivers" is about 1.7 GB; a bundle over the limits would also need ZIP64, which is not read. */
    public static final int MAX_ENTRIES = 10_000;
    public static final long MAX_TOTAL_SIZE = 3L << 30;

    static final String MANIFEST_ENTRY = "chromite-driver-bundle.json"; //$NON-NLS-1$
    static final String FILES_PREFIX = "files/"; //$NON-NLS-1$
    static final long MAX_MANIFEST_SIZE = 16L << 20;
    private static final int MAX_PATH_LENGTH = 512;
    // Central directory record: 46 bytes plus name, extra field and comment; generous for MAX_ENTRIES short names
    private static final long MAX_DIRECTORY_SIZE = 32L << 20;
    // Characters Windows does not allow in file names (':' also rules out drive letters and alternate data streams)
    private static final String FORBIDDEN_NAME_CHARS = "<>:\"|?*\\"; //$NON-NLS-1$

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private DriverOfflineBundle() {
    }

    //////////////////////////////////////////////////////////
    // Manifest (serialized with Gson, field names are the JSON keys)

    public static final class Manifest {
        private String format;
        private int formatVersion;
        private String chromiteVersion;
        private String created;
        private String os;
        private String arch;
        private List<DriverEntry> drivers;
        private List<FileEntry> files;

        Manifest(
            @NotNull String chromiteVersion,
            @NotNull String created,
            @NotNull String os,
            @NotNull String arch,
            @NotNull List<DriverEntry> drivers,
            @NotNull List<FileEntry> files
        ) {
            this.format = FORMAT_NAME;
            this.formatVersion = FORMAT_VERSION;
            this.chromiteVersion = chromiteVersion;
            this.created = created;
            this.os = os;
            this.arch = arch;
            this.drivers = drivers;
            this.files = files;
        }

        @Nullable
        String getFormat() {
            return format;
        }

        int getFormatVersion() {
            return formatVersion;
        }

        @Nullable
        public String getChromiteVersion() {
            return chromiteVersion;
        }

        @Nullable
        public String getCreated() {
            return created;
        }

        @Nullable
        public String getOs() {
            return os;
        }

        @Nullable
        public String getArch() {
            return arch;
        }

        @NotNull
        public List<DriverEntry> getDrivers() {
            return drivers;
        }

        @NotNull
        public List<FileEntry> getFiles() {
            return files;
        }
    }

    public static final class DriverEntry {
        private String provider;
        private String id;
        private String name;
        private List<LibraryEntry> libraries;

        DriverEntry(@NotNull String provider, @NotNull String id, @NotNull String name, @NotNull List<LibraryEntry> libraries) {
            this.provider = provider;
            this.id = id;
            this.name = name;
            this.libraries = libraries;
        }

        @NotNull
        public String getProvider() {
            return provider;
        }

        @NotNull
        public String getId() {
            return id;
        }

        @NotNull
        public String getName() {
            return name == null ? id : name;
        }

        @NotNull
        public List<LibraryEntry> getLibraries() {
            return libraries;
        }
    }

    /**
     * A library of the driver definition (its ID matches {@link DBPDriverLibrary#getId()}) and the files the resolver gave
     * for it: the library file first, then its Maven dependencies.
     */
    public static final class LibraryEntry {
        private String id;
        private String path;
        private String type;
        private List<LibraryFile> files;

        LibraryEntry(@NotNull String id, @NotNull String path, @NotNull String type, @NotNull List<LibraryFile> files) {
            this.id = id;
            this.path = path;
            this.type = type;
            this.files = files;
        }

        @NotNull
        public String getId() {
            return id;
        }

        @Nullable
        public String getPath() {
            return path;
        }

        @NotNull
        public List<LibraryFile> getFiles() {
            return files;
        }

        /**
         * The resolved version of the library itself (its first file), e.g. "2.4.240".
         */
        @Nullable
        public String getResolvedVersion() {
            return files.isEmpty() ? null : files.getFirst().version;
        }
    }

    /**
     * One resolved file. {@code coordinates} (Maven) or {@code url} tell where it came from so that its licence can be looked up;
     * {@code licenses} are the licence names declared in the Maven POM.
     */
    public static final class LibraryFile {
        private String id;
        private String version;
        private String type;
        private String path;
        private String coordinates;
        private String url;
        private List<String> licenses;

        LibraryFile(
            @NotNull String id,
            @Nullable String version,
            @NotNull String type,
            @NotNull String path,
            @Nullable String coordinates,
            @Nullable String url,
            @Nullable List<String> licenses
        ) {
            this.id = id;
            this.version = version;
            this.type = type;
            this.path = path;
            this.coordinates = coordinates;
            this.url = url;
            this.licenses = licenses;
        }

        @NotNull
        public String getId() {
            return id;
        }

        @Nullable
        public String getVersion() {
            return version;
        }

        @NotNull
        DBPDriverLibrary.FileType getFileType() {
            return DBPDriverLibrary.FileType.valueOf(type);
        }

        @NotNull
        public String getPath() {
            return path;
        }
    }

    public static final class FileEntry {
        private String path;
        private long size;
        private String sha256;

        FileEntry(@NotNull String path, long size, @NotNull String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }

        @NotNull
        public String getPath() {
            return path;
        }

        public long getSize() {
            return size;
        }

        @NotNull
        public String getSha256() {
            return sha256;
        }
    }

    //////////////////////////////////////////////////////////
    // Shared helpers

    /**
     * The folder online downloads write to; bundle paths are relative to it on both PCs.
     */
    @NotNull
    static Path getDriversHome() {
        return DriverDescriptor.getCustomDriversHome().toAbsolutePath().normalize();
    }

    /**
     * Accepts only plain relative paths with '/' separators: no empty, "." or ".." segment, no leading '/', no drive letter,
     * no backslash, no control or Windows-reserved character, and no segment ending with '.' or ' ' (Windows drops those,
     * so two different names could land on one file).
     */
    static boolean isSafeRelativePath(@Nullable String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_PATH_LENGTH || path.startsWith("/")) { //$NON-NLS-1$
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7f || FORBIDDEN_NAME_CHARS.indexOf(c) >= 0) {
                return false;
            }
        }
        for (String segment : path.split("/", -1)) { //$NON-NLS-1$
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") //$NON-NLS-1$ //$NON-NLS-2$
                || segment.endsWith(".") || segment.endsWith(" ")) { //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves a bundle path under the drivers folder and rejects any result outside it.
     */
    @NotNull
    static Path resolveTarget(@NotNull Path driversHome, @NotNull String relativePath) throws DBException {
        Path target = isSafeRelativePath(relativePath) ? driversHome.resolve(relativePath).normalize() : null;
        if (target == null || !target.startsWith(driversHome) || target.equals(driversHome)) {
            throw invalid(NLS.bind(RegistryMessages.driver_bundle_unsafe_entry, relativePath));
        }
        return target;
    }

    /**
     * Fails when the target or a folder between it and the drivers folder is a link, or when the target is not a regular file.
     * Files are only ever written as regular files, never through a link.
     */
    static void checkTargetPath(@NotNull Path driversHome, @NotNull Path target) throws DBException {
        Path current = driversHome;
        for (Path part : driversHome.relativize(target)) {
            current = current.resolve(part);
            boolean last = current.equals(target);
            if (Files.isSymbolicLink(current)
                || (last && Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS))
                || (!last && Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))) {
                throw new DBException(NLS.bind(RegistryMessages.driver_bundle_target_unsafe, target, current));
            }
        }
    }

    @NotNull
    static MessageDigest createDigest() {
        try {
            return MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256
            throw new IllegalStateException(e);
        }
    }

    @NotNull
    static String toHex(@NotNull byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    @NotNull
    static String sha256(@NotNull Path file) throws IOException {
        MessageDigest digest = createDigest();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = input.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    @NotNull
    static String formatSize(long bytes) {
        return new ByteNumberFormat(ByteNumberFormat.BinaryPrefix.ISO).format(bytes);
    }

    @NotNull
    static DBException invalid(@NotNull String reason) {
        return new DBException(NLS.bind(RegistryMessages.driver_bundle_invalid, reason));
    }

    @NotNull
    static DBException tooLarge() {
        return invalid(NLS.bind(RegistryMessages.driver_bundle_too_large, MAX_ENTRIES, formatSize(MAX_TOTAL_SIZE)));
    }

    /**
     * Loader registration of downloaded files, the same record a normal download keeps in {@code drivers.xml}
     * ({@link DriverLoaderDescriptor#downloadDriverLibraries}): with it the loader takes the files as they are and resolves nothing.
     * The caller saves the drivers.
     */
    static void registerLibraryFiles(@NotNull DriverDescriptor driver, @NotNull Map<DBPDriverLibrary, List<DriverFileInfo>> files) {
        DriverLoaderDescriptor loader = driver.getDefaultDriverLoader();
        if (loader.isLoaded()) {
            // A loaded driver keeps its class loader: drop it so the next connection uses the registered files,
            // but keep the registrations of the other libraries (resetDriverInstance clears them all)
            Map<DBPDriverLibrary, List<DriverFileInfo>> others = new HashMap<>(loader.getResolvedFiles());
            loader.resetDriverInstance();
            loader.getResolvedFiles().putAll(others);
        }
        for (Map.Entry<DBPDriverLibrary, List<DriverFileInfo>> entry : files.entrySet()) {
            loader.removeLibraryFiles(entry.getKey());
            for (DriverFileInfo fileInfo : entry.getValue()) {
                loader.addLibraryFile(entry.getKey(), fileInfo);
            }
        }
        driver.setModified(true);
    }

    //////////////////////////////////////////////////////////
    // ZIP central directory check

    /**
     * Reads the entry names from the ZIP central directory and rejects the archive before any entry is opened when it has too many
     * entries, ZIP64 or multi-disk records, duplicate names, unsafe names or links (Unix mode {@code 0120000} in the external
     * attributes, which {@link java.util.zip.ZipFile} does not expose).
     */
    @NotNull
    static List<String> readEntryNames(@NotNull Path file) throws DBException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            // End of central directory record: 22 bytes plus a comment of up to 65535 bytes at the end of the file
            int tailSize = (int) Math.min(fileSize, 22 + 0xFFFF);
            if (tailSize < 22) {
                throw invalid(RegistryMessages.driver_bundle_invalid_zip);
            }
            ByteBuffer tail = readFully(channel, fileSize - tailSize, tailSize);
            int eocd = -1;
            for (int pos = tailSize - 22; pos >= 0; pos--) {
                if (tail.getInt(pos) == 0x06054b50) {
                    eocd = pos;
                    break;
                }
            }
            if (eocd < 0) {
                throw invalid(RegistryMessages.driver_bundle_invalid_zip);
            }
            int diskNumber = Short.toUnsignedInt(tail.getShort(eocd + 4));
            int directoryDisk = Short.toUnsignedInt(tail.getShort(eocd + 6));
            int diskEntries = Short.toUnsignedInt(tail.getShort(eocd + 8));
            int entryCount = Short.toUnsignedInt(tail.getShort(eocd + 10));
            long directorySize = Integer.toUnsignedLong(tail.getInt(eocd + 12));
            long directoryOffset = Integer.toUnsignedLong(tail.getInt(eocd + 16));
            if (entryCount == 0xFFFF || directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL || entryCount > MAX_ENTRIES
                || directorySize > MAX_DIRECTORY_SIZE) {
                // ZIP64 markers: only needed beyond the limits
                throw tooLarge();
            }
            if (diskNumber != 0 || directoryDisk != 0 || diskEntries != entryCount
                || directoryOffset + directorySize > fileSize - tailSize + eocd) {
                throw invalid(RegistryMessages.driver_bundle_invalid_zip);
            }
            ByteBuffer directory = readFully(channel, directoryOffset, (int) directorySize);
            List<String> names = new ArrayList<>(entryCount);
            Set<String> unique = new HashSet<>();
            int pos = 0;
            for (int i = 0; i < entryCount; i++) {
                if (pos + 46 > directory.limit() || directory.getInt(pos) != 0x02014b50) {
                    throw invalid(RegistryMessages.driver_bundle_invalid_zip);
                }
                int madeBy = Short.toUnsignedInt(directory.getShort(pos + 4));
                int nameLength = Short.toUnsignedInt(directory.getShort(pos + 28));
                int extraLength = Short.toUnsignedInt(directory.getShort(pos + 30));
                int commentLength = Short.toUnsignedInt(directory.getShort(pos + 32));
                long externalAttributes = Integer.toUnsignedLong(directory.getInt(pos + 38));
                if (pos + 46 + nameLength > directory.limit()) {
                    throw invalid(RegistryMessages.driver_bundle_invalid_zip);
                }
                byte[] nameBytes = new byte[nameLength];
                directory.get(pos + 46, nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8);
                boolean unixHost = (madeBy >>> 8) == 3;
                boolean link = unixHost && ((externalAttributes >>> 16) & 0xF000) == 0xA000;
                boolean safeName = name.equals(MANIFEST_ENTRY)
                    || (name.startsWith(FILES_PREFIX) && isSafeRelativePath(name.substring(FILES_PREFIX.length())));
                if (link || !safeName) {
                    throw invalid(NLS.bind(RegistryMessages.driver_bundle_unsafe_entry, name));
                }
                if (!unique.add(name)) {
                    throw invalid(NLS.bind(RegistryMessages.driver_bundle_invalid_files, name));
                }
                names.add(name);
                pos += 46 + nameLength + extraLength + commentLength;
            }
            return names;
        } catch (IOException e) {
            throw new DBException(NLS.bind(RegistryMessages.driver_bundle_invalid, RegistryMessages.driver_bundle_invalid_zip), e);
        }
    }

    @NotNull
    private static ByteBuffer readFully(@NotNull FileChannel channel, long position, int size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                throw new IOException("Unexpected end of file"); //$NON-NLS-1$
            }
        }
        buffer.flip();
        return buffer;
    }
}
