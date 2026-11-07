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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Regression tests of the Query Library archive ({@link QueryLibraryArchive}): zip safety on import and the choices for
 * queries whose ID is in the library already (docs/chromite/swing-query-library.md "공유").
 *
 * 설계: 가져오기는 쓰기 전에 묶음 전체를 읽고 검사하므로 위험한 항목은 {@code read}에서 실패해야 하고, 쓰기는 고른 폴더 밖으로 나가지 않아야
 * 한다. 같은 ID 처리(덮어쓰기, 사본, 건너뛰기, 묶음 안의 중복은 사본)는 {@code write}가 정해진 선택대로 한다. 라이브러리 객체는 앱 설정을
 * 읽어 만들어지므로 mock으로 대신한다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "Query Library")
 */
public class QueryLibraryArchiveTest {

    @TempDir
    Path temp;

    @Test
    public void entryPathsAreCheckedBeforeAnythingIsWritten() throws Exception {
        Assertions.assertEquals("a/b.sql", QueryLibraryArchive.checkPath("a/b.sql"));
        Assertions.assertEquals("a/b.sql", QueryLibraryArchive.checkPath("a\\b.sql"));
        Assertions.assertEquals("a/b.sql", QueryLibraryArchive.checkPath("./a//b.sql"));
        Assertions.assertEquals("Q.SQL", QueryLibraryArchive.checkPath("Q.SQL"));
        // Skipped: hidden entries and other files
        Assertions.assertNull(QueryLibraryArchive.checkPath(".git/config.sql"));
        Assertions.assertNull(QueryLibraryArchive.checkPath("a/.draft.sql"));
        Assertions.assertNull(QueryLibraryArchive.checkPath("a/readme.txt"));
        Assertions.assertNull(QueryLibraryArchive.checkPath("a/"));
        // Refused: anything that could point outside the target folder
        for (String name : List.of("../evil.sql", "a/../../evil.sql", "..\\evil.sql", "/abs.sql", "\\abs.sql", "C:/evil.sql", "C:evil.sql",
            "a/b:stream.sql")) {
            Assertions.assertThrows(IOException.class, () -> QueryLibraryArchive.checkPath(name), name);
        }
    }

    @Test
    public void readFindsExistingIdsAndSkipsOtherEntries() throws Exception {
        Path existing = Files.writeString(temp.resolve("existing.sql"), query("id-1", "Sessions"));
        QueryLibrary library = library(Map.of("id-1", existing), false);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("sessions.sql", bytes(query("id-1", "Sessions")));
        entries.put("admin/locks.sql", bytes(query("id-2", "Locks")));
        entries.put("notes.txt", bytes("not a query"));
        entries.put(".git/HEAD.sql", bytes("hidden"));
        Path archive = zip("library" + QueryLibraryArchive.EXTENSION, entries);

        List<QueryLibraryArchive.Entry> read = QueryLibraryArchive.read(archive, library);

        Assertions.assertEquals(List.of("sessions.sql", "admin/locks.sql"), read.stream().map(QueryLibraryArchive.Entry::path).toList());
        Assertions.assertEquals(existing, read.get(0).existing());
        Assertions.assertNull(read.get(1).existing());
        Assertions.assertEquals("id-2", read.get(1).item().getId());
    }

    @Test
    public void unsafeOrOversizedArchivesAreRefused() throws Exception {
        QueryLibrary library = library(Map.of(), false);
        Path slip = zip("slip.zip", Map.of("../../evil.sql", bytes("SELECT 1")));
        Assertions.assertThrows(IOException.class, () -> QueryLibraryArchive.read(slip, library));

        // The size counts the bytes actually read, not the size in the entry header (a compressed bomb)
        byte[] big = new byte[(int) QueryLibraryArchive.MAX_FILE_BYTES + 1];
        Arrays.fill(big, (byte) ' ');
        Path tooBig = zip("big.zip", Map.of("big.sql", big));
        Assertions.assertThrows(IOException.class, () -> QueryLibraryArchive.read(tooBig, library));

        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i <= QueryLibraryArchive.MAX_ENTRIES; i++) {
            many.put("n" + i + ".txt", new byte[0]);
        }
        Path tooMany = zip("many.zip", many);
        Assertions.assertThrows(IOException.class, () -> QueryLibraryArchive.read(tooMany, library));
    }

    @Test
    public void choicesForExistingIdsAreFollowed() throws Exception {
        Path target = Files.createDirectories(temp.resolve("target"));
        Path overwriteFile = Files.writeString(temp.resolve("overwrite.sql"), query("id-o", "Old text"));
        Path skipFile = Files.writeString(temp.resolve("skip.sql"), query("id-s", "Kept"));
        Path copySource = Files.writeString(temp.resolve("copy.sql"), query("id-c", "Report"));
        QueryLibrary library = library(Map.of(), false);

        List<QueryLibraryArchive.Entry> entries = List.of(
            entry("new.sql", query("id-n", "New"), null),
            entry("overwrite.sql", query("id-o", "New text"), overwriteFile),
            entry("skip.sql", query("id-s", "Changed"), skipFile),
            entry("report.sql", query("id-c", "Report"), copySource),
            // The same ID again in one archive: imported as a copy
            entry("again/new.sql", query("id-n", "New"), null));
        List<QueryLibraryArchive.Choice> choices = Arrays.asList(null, QueryLibraryArchive.Choice.OVERWRITE,
            QueryLibraryArchive.Choice.SKIP, QueryLibraryArchive.Choice.COPY, null);

        QueryLibraryArchive.Summary summary = QueryLibraryArchive.write(library, target, entries, choices);

        Assertions.assertEquals(List.of(), summary.errors());
        Assertions.assertEquals(1, summary.added());
        Assertions.assertEquals(1, summary.overwritten());
        Assertions.assertEquals(1, summary.skipped());
        Assertions.assertEquals(2, summary.copied());
        Assertions.assertTrue(Files.readString(overwriteFile).contains("New text"));
        Assertions.assertTrue(Files.readString(skipFile).contains("Kept"));
        Assertions.assertTrue(Files.exists(target.resolve("new.sql")));
        // Copies get a new ID, so the library never holds one ID twice
        List<String> ids = new ArrayList<>();
        try (var files = Files.walk(target)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                ids.add(QueryLibraryFile.read(file).getId());
            }
        }
        Assertions.assertEquals(ids.size(), ids.stream().distinct().count(), ids.toString());
        Assertions.assertFalse(ids.contains("id-c"));
    }

    @Test
    public void overwriteInAReadOnlyRootIsAnError() throws Exception {
        Path readOnlyFile = Files.writeString(temp.resolve("shared.sql"), query("id-r", "Shared"));
        QueryLibrary library = library(Map.of(), true);
        QueryLibraryArchive.Summary summary = QueryLibraryArchive.write(library, temp.resolve("target"),
            List.of(entry("shared.sql", query("id-r", "Changed"), readOnlyFile)), List.of(QueryLibraryArchive.Choice.OVERWRITE));
        Assertions.assertEquals(1, summary.errors().size());
        Assertions.assertEquals(0, summary.overwritten());
        Assertions.assertTrue(Files.readString(readOnlyFile).contains("Shared"));
    }

    @NotNull
    private static String query(@NotNull String id, @NotNull String name) {
        return "-- @chromite-query: 1\n-- @id: " + id + "\n-- @name: " + name + "\n\nSELECT 1 -- " + name + "\n";
    }

    @NotNull
    private static QueryLibraryArchive.Entry entry(@NotNull String path, @NotNull String text, @Nullable Path existing) {
        QueryLibraryItem item = QueryLibraryFile.parse(text, Path.of(path.substring(path.lastIndexOf('/') + 1)));
        item.setFile(null);
        return new QueryLibraryArchive.Entry(path, bytes(text), item, existing);
    }

    @NotNull
    private static QueryLibrary library(@NotNull Map<String, Path> files, boolean readOnly) {
        // QueryLibrary is final, and its constructor reads the settings of the running application
        QueryLibrary library = Mockito.mock(QueryLibrary.class, Mockito.withSettings().mockMaker("mock-maker-inline"));
        Mockito.when(library.findFile(Mockito.anyString())).thenAnswer(invocation -> files.get((String) invocation.getArgument(0)));
        Mockito.when(library.isReadOnly(Mockito.any())).thenReturn(readOnly);
        return library;
    }

    @NotNull
    private static byte[] bytes(@NotNull String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @NotNull
    private Path zip(@NotNull String name, @NotNull Map<String, byte[]> entries) throws IOException {
        Path file = temp.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }
}
