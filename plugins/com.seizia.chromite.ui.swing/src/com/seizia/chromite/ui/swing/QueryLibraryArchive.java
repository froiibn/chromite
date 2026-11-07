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

import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Query Library 묶음 파일({@code .chromite-queries}): 폴더 하나의 쿼리를 zip 하나로 내보내고, 고른 폴더로 가져온다.
 *
 * 설계:
 * <ul>
 *     <li>형식은 평범한 zip이다. 항목은 내보낸 폴더 기준 상대 경로의 쿼리 파일({@code .sql})이고 바이트를 그대로 담는다(문자 집합·BOM·줄바꿈과
 *     모르는 머리말 줄이 그대로 간다). 내보낸 폴더 이름은 넣지 않는다: 가져오는 사람이 넣을 폴더를 고른다. {@code .}으로 시작하는 폴더·파일은 넣지 않는다.</li>
 *     <li>가져오기는 먼저 묶음 전체를 메모리로 읽고 검사한 뒤({@link #read}) 쓴다. 검사: 항목 수({@value #MAX_ENTRIES}),
 *     파일 하나의 크기({@value #MAX_FILE_BYTES}바이트), 전체 크기({@value #MAX_TOTAL_BYTES}바이트)를 머리글의 크기가 아니라 실제로 읽은 바이트로 센다
 *     (압축 폭탄). 경로는 절대 경로, 드라이브 문자, {@code ..}를 거부하고, 대상 폴더 밖으로 나가는 경로를 다시 한 번 막는다(zip-slip).
 *     {@code .sql}이 아닌 항목과 {@code .}으로 시작하는 항목은 건너뛴다.</li>
 *     <li>같은 ID의 쿼리가 라이브러리에 있으면 묻는다({@link Choice}): 덮어쓰기(그 파일 자리에 가져온 내용을 씀), 사본으로(새 ID, 이름에 "copy"),
 *     건너뛰기. 묻는 것은 화면 쪽({@link QueryLibraryPanel})이고 이 클래스는 정해진 선택대로 쓴다({@link #write}).
 *     묶음 안에서 같은 ID가 다시 나오면 뒤의 것은 사본으로 가져온다(한 라이브러리에 같은 ID가 둘이면 이 PC의 상태가 섞인다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 Query Library ④ 공유: 최초 작성 (docs/chromite/swing-query-library.md "공유")
 */
final class QueryLibraryArchive {

    static final String EXTENSION = ".chromite-queries"; //$NON-NLS-1$
    static final int MAX_ENTRIES = 10_000;
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;

    /**
     * What to do with an imported query whose id is in the library already.
     */
    enum Choice {
        OVERWRITE,
        COPY,
        SKIP
    }

    /**
     * A query file of an archive.
     *
     * @param path     relative path with {@code /}
     * @param bytes    the file as it was exported
     * @param item     the parsed query
     * @param existing the library file with the same id, or null
     */
    record Entry(@NotNull String path, @NotNull byte[] bytes, @NotNull QueryLibraryItem item, @Nullable Path existing) {
    }

    /**
     * Counts of an import.
     */
    record Summary(int added, int overwritten, int copied, int skipped, @NotNull List<String> errors) {
    }

    private QueryLibraryArchive() {
    }

    //////////////////////////////////////////////////////////
    // Export

    /**
     * Writes the queries of a folder and its subfolders into an archive.
     *
     * @return the number of queries
     */
    static int export(@NotNull Path folder, @NotNull Path archive) throws IOException {
        List<Path> files = new ArrayList<>();
        collect(folder, files);
        if (files.isEmpty()) {
            return 0;
        }
        files.sort(Comparator.comparing(Path::toString));
        Path temporary = archive.resolveSibling(archive.getFileName() + ".tmp"); //$NON-NLS-1$
        try (OutputStream out = Files.newOutputStream(temporary); ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Path file : files) {
                StringBuilder name = new StringBuilder();
                for (Path part : folder.relativize(file)) {
                    name.append(name.isEmpty() ? "" : "/").append(part); //$NON-NLS-1$ //$NON-NLS-2$
                }
                zip.putNextEntry(new ZipEntry(name.toString()));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        // A failed export leaves an old archive as it was
        Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
        return files.size();
    }

    private static void collect(@NotNull Path folder, @NotNull List<Path> result) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path child : stream) {
                String name = String.valueOf(child.getFileName());
                if (name.startsWith(".")) { //$NON-NLS-1$
                    continue;
                }
                if (Files.isDirectory(child)) {
                    collect(child, result);
                } else if (name.toLowerCase(Locale.ROOT).endsWith(QueryLibraryFile.EXTENSION)) {
                    result.add(child);
                }
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Import

    /**
     * Reads and checks an archive, and finds the queries whose id is in the library already.
     */
    @NotNull
    static List<Entry> read(@NotNull Path archive, @NotNull QueryLibrary library) throws IOException {
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        int count = 0;
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> zipEntries = zip.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry zipEntry = zipEntries.nextElement();
                if (++count > MAX_ENTRIES) {
                    throw new IOException(NLS.bind(ToolsMessages.library_import_error_too_many, MAX_ENTRIES));
                }
                if (zipEntry.isDirectory()) {
                    continue;
                }
                String path = checkPath(zipEntry.getName());
                if (path == null) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(zipEntry)) {
                    bytes = readLimited(in, zipEntry.getName());
                }
                total += bytes.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new IOException(NLS.bind(ToolsMessages.library_import_error_too_large, MAX_TOTAL_BYTES / (1024 * 1024)));
                }
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                QueryLibraryItem item = QueryLibraryFile.parse(SqlFileContent.parse(bytes).text(), Path.of(fileName));
                item.setFile(null);
                entries.add(new Entry(path, bytes, item, item.getId() != null ? library.findFile(item.getId()) : null));
            }
        }
        return entries;
    }

    /**
     * The entry name as a safe relative path with {@code /}, null for an entry that is skipped (not a query, hidden).
     *
     * @throws IOException for a name that points outside the target folder
     */
    @Nullable
    static String checkPath(@NotNull String name) throws IOException {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":")) { //$NON-NLS-1$ //$NON-NLS-2$
            throw new IOException(NLS.bind(ToolsMessages.library_import_error_bad_path, name));
        }
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) { //$NON-NLS-1$
            if (part.isEmpty() || part.equals(".")) { //$NON-NLS-1$
                continue;
            }
            if (part.equals("..")) { //$NON-NLS-1$
                throw new IOException(NLS.bind(ToolsMessages.library_import_error_bad_path, name));
            }
            if (part.startsWith(".")) { //$NON-NLS-1$
                return null;
            }
            parts.add(part);
        }
        if (parts.isEmpty() || !parts.getLast().toLowerCase(Locale.ROOT).endsWith(QueryLibraryFile.EXTENSION)) {
            return null;
        }
        return String.join("/", parts); //$NON-NLS-1$
    }

    @NotNull
    private static byte[] readLimited(@NotNull InputStream in, @NotNull String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long size = 0;
        for (int read; (read = in.read(buffer)) > 0; ) {
            size += read;
            if (size > MAX_FILE_BYTES) {
                throw new IOException(NLS.bind(ToolsMessages.library_import_error_file_too_large, name, MAX_FILE_BYTES / (1024 * 1024)));
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Writes the entries into a folder. Entries without a choice are new (no id clash).
     *
     * @param choices the answer for each entry with an existing query (same order as {@code entries}, null for the others)
     */
    @NotNull
    static Summary write(
        @NotNull QueryLibrary library,
        @NotNull Path folder,
        @NotNull List<Entry> entries,
        @NotNull List<Choice> choices
    ) {
        int added = 0;
        int overwritten = 0;
        int copied = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        Set<String> imported = new HashSet<>();
        Path base = folder.toAbsolutePath().normalize();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            Choice choice = entry.existing() != null ? choices.get(i) : null;
            String id = entry.item().getId();
            // The same id twice in one archive: the later one becomes a copy
            if (id != null && !imported.add(id) && choice == null) {
                choice = Choice.COPY;
            }
            try {
                if (choice == Choice.SKIP) {
                    skipped++;
                } else if (choice == Choice.OVERWRITE && entry.existing() != null) {
                    if (library.isReadOnly(entry.existing())) {
                        throw new IOException(NLS.bind(ToolsMessages.library_error_read_only_root, entry.existing()));
                    }
                    Files.write(entry.existing(), entry.bytes());
                    overwritten++;
                } else {
                    Path target = resolve(base, entry.path());
                    Files.createDirectories(target.getParent());
                    if (choice == Choice.COPY) {
                        QueryLibraryItem copy = entry.item().copy();
                        copy.setId(null);
                        copy.ensureId();
                        copy.setName(NLS.bind(ToolsMessages.library_copy_name, copy.getName()));
                        OffsetDateTime now = OffsetDateTime.now().withNano(0);
                        copy.setCreated(now);
                        copy.setUpdated(now);
                        target = QueryLibrary.uniquePath(target.getParent(), QueryLibraryFile.toFileName(copy.getName()), QueryLibraryFile.EXTENSION);
                        // The exported bytes first, so that the copy keeps their charset and line separator
                        Files.write(target, entry.bytes());
                        QueryLibraryFile.write(copy, target);
                        copied++;
                    } else {
                        String fileName = String.valueOf(target.getFileName());
                        target = QueryLibrary.uniquePath(target.getParent(),
                            fileName.substring(0, fileName.length() - QueryLibraryFile.EXTENSION.length()), QueryLibraryFile.EXTENSION);
                        Files.write(target, entry.bytes());
                        added++;
                    }
                }
            } catch (IOException | RuntimeException e) {
                errors.add(entry.path() + ": " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return new Summary(added, overwritten, copied, skipped, errors);
    }

    /**
     * The target file of an entry, checked again to be inside the folder.
     */
    @NotNull
    private static Path resolve(@NotNull Path base, @NotNull String path) throws IOException {
        try {
            Path target = base.resolve(path).normalize();
            if (!target.startsWith(base) || target.equals(base)) {
                throw new IOException(NLS.bind(ToolsMessages.library_import_error_bad_path, path));
            }
            return target;
        } catch (InvalidPathException e) {
            throw new IOException(NLS.bind(ToolsMessages.library_import_error_bad_path, path), e);
        }
    }
}
