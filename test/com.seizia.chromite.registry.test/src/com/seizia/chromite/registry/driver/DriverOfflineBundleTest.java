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

import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Regression tests of the offline driver bundle checks ({@link DriverOfflineBundle}, {@link DriverOfflineBundleInstaller#read}).
 *
 * 설계: 묶음 검사는 아무것도 쓰기 전에 끝나야 하므로, 잘못된 묶음은 {@code read}가 드라이버 폴더를 보기 전에 실패하는지 본다.
 * 올바른 묶음의 확인은 드라이버 폴더(사용자 데이터 폴더)를 건드리지 않도록 매니페스트 검사와 해시 확인만 직접 부른다.
 * 링크 항목과 중복 이름은 {@link ZipOutputStream}이 만들 수 없어 중앙 디렉터리 바이트를 고친다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "드라이버 오프라인 묶음")
 */
public class DriverOfflineBundleTest {

    private static final byte[] JAR_BYTES = "not really a jar".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temp;

    @Test
    public void safeRelativePathsOnly() {
        for (String path : List.of("maven/maven-central/org.postgresql/postgresql-42.7.4.jar", "a/b.jar", "x")) {
            Assertions.assertTrue(DriverOfflineBundle.isSafeRelativePath(path), path);
        }
        for (String path : new String[] {null, "", "/etc/passwd", "../x.jar", "a/../../x.jar", "a/./b.jar", "a//b.jar",
            "C:/Windows/x.dll", "C:x.jar", "a\\b.jar", "..\\x.jar", "a/b.jar.", "a/b.jar ", "a/con:stream", "a/b\u0000.jar",
            "a/b?.jar", "a/b*.jar", "a/\"b\".jar", "a/<b>.jar", "a|b.jar", "a/" + "x".repeat(600)}) {
            Assertions.assertFalse(DriverOfflineBundle.isSafeRelativePath(path), String.valueOf(path));
        }
    }

    @Test
    public void resolveTargetStaysInsideTheDriversFolder() throws Exception {
        Path home = temp.resolve("drivers").toAbsolutePath().normalize();
        Assertions.assertEquals(home.resolve("a").resolve("b.jar"), DriverOfflineBundle.resolveTarget(home, "a/b.jar"));
        for (String path : List.of("../outside.jar", "a/../../outside.jar", "/abs.jar", "C:/abs.jar", ".")) {
            Assertions.assertThrows(DBException.class, () -> DriverOfflineBundle.resolveTarget(home, path), path);
        }
    }

    @Test
    public void checkTargetPathRejectsLinksAndFoldersInTheWay() throws Exception {
        Path home = Files.createDirectories(temp.resolve("drivers")).toAbsolutePath().normalize();
        // A folder where the file should go
        Files.createDirectories(home.resolve("a").resolve("b.jar"));
        Assertions.assertThrows(DBException.class,
            () -> DriverOfflineBundle.checkTargetPath(home, home.resolve("a").resolve("b.jar")));
        // A file where a folder should be
        Files.writeString(home.resolve("file"), "x");
        Assertions.assertThrows(DBException.class,
            () -> DriverOfflineBundle.checkTargetPath(home, home.resolve("file").resolve("c.jar")));
        // A plain new path is fine
        DriverOfflineBundle.checkTargetPath(home, home.resolve("new").resolve("c.jar"));

        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path link = home.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("Symbolic links can't be created here: " + e.getMessage());
        }
        Assertions.assertThrows(DBException.class, () -> DriverOfflineBundle.checkTargetPath(home, link.resolve("c.jar")));
    }

    @Test
    public void readEntryNamesRejectsUnsafeNamesLinksDuplicatesAndTooManyEntries() throws Exception {
        Path good = zip("good.zip", Map.of(DriverOfflineBundle.MANIFEST_ENTRY, "{}".getBytes(), "files/a/b.jar", JAR_BYTES));
        Assertions.assertEquals(List.of(DriverOfflineBundle.MANIFEST_ENTRY, "files/a/b.jar"), DriverOfflineBundle.readEntryNames(good));

        for (String name : List.of("files/../evil.jar", "../evil.jar", "/files/a.jar", "files/C:/a.jar", "files/a\\b.jar", "other.txt",
            "files/")) {
            Path bad = zip("bad.zip", Map.of(name, JAR_BYTES));
            Assertions.assertThrows(DBException.class, () -> DriverOfflineBundle.readEntryNames(bad), name);
        }

        // A Unix symbolic link entry (mode 0120777 in the external attributes)
        Path link = zip("link.zip", Map.of("files/a.jar", JAR_BYTES));
        patchCentralDirectory(link, 0, (directory, pos) -> {
            directory.put(pos + 5, (byte) 3);
            directory.putInt(pos + 38, 0120777 << 16);
        });
        DBException linkError = Assertions.assertThrows(DBException.class, () -> DriverOfflineBundle.readEntryNames(link));
        Assertions.assertTrue(linkError.getMessage().contains("files/a.jar"), linkError.getMessage());

        // The same name twice: rename the second entry of the directory to the first one
        Path duplicate = zip("duplicate.zip", Map.of("files/a.jar", JAR_BYTES, "files/b.jar", JAR_BYTES));
        patchCentralDirectory(duplicate, 1, (directory, pos) -> directory.put(pos + 46 + "files/".length(), (byte) 'a'));
        Assertions.assertThrows(DBException.class, () -> DriverOfflineBundle.readEntryNames(duplicate));

        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i <= DriverOfflineBundle.MAX_ENTRIES; i++) {
            many.put("files/f" + i + ".jar", new byte[0]);
        }
        Path tooMany = zip("many.zip", many);
        Assertions.assertThrows(DBException.class, () -> DriverOfflineBundle.readEntryNames(tooMany));

        Path notZip = temp.resolve("not.zip");
        Files.writeString(notZip, "plain text, no central directory");
        Assertions.assertThrows(DBException.class, () -> DriverOfflineBundle.readEntryNames(notZip));
    }

    @Test
    public void readRejectsChecksumAndSizeMismatches() throws Exception {
        String rightHash = DriverOfflineBundle.toHex(DriverOfflineBundle.createDigest().digest(JAR_BYTES));
        String wrongHash = "0".repeat(64);

        Path changed = bundle("changed.zip", "a/b.jar", JAR_BYTES.length, wrongHash, Map.of("files/a/b.jar", JAR_BYTES));
        DBException hashError = Assertions.assertThrows(DBException.class,
            () -> DriverOfflineBundleInstaller.read(monitor(), changed));
        Assertions.assertTrue(hashError.getMessage().contains("a/b.jar"), hashError.getMessage());

        Path longer = bundle("longer.zip", "a/b.jar", JAR_BYTES.length - 1, rightHash, Map.of("files/a/b.jar", JAR_BYTES));
        Assertions.assertThrows(DBException.class, () -> DriverOfflineBundleInstaller.read(monitor(), longer));

        // An entry the manifest does not list, and a listed file that is not in the archive
        Path extra = bundle("extra.zip", "a/b.jar", JAR_BYTES.length, rightHash,
            Map.of("files/a/b.jar", JAR_BYTES, "files/a/c.jar", JAR_BYTES));
        Assertions.assertThrows(DBException.class, () -> DriverOfflineBundleInstaller.read(monitor(), extra));
        Path missing = bundle("missing.zip", "a/b.jar", JAR_BYTES.length, rightHash, Map.of());
        Assertions.assertThrows(DBException.class, () -> DriverOfflineBundleInstaller.read(monitor(), missing));

        // Declared sizes over the total limit are refused before any file is read
        Path huge = bundle("huge.zip", "a/b.jar", DriverOfflineBundle.MAX_TOTAL_SIZE + 1, rightHash, Map.of("files/a/b.jar", JAR_BYTES));
        Assertions.assertThrows(DBException.class, () -> DriverOfflineBundleInstaller.read(monitor(), huge));

        // A manifest path that is unsafe is refused even when the entry name would be
        Path unsafe = bundle("unsafe.zip", "../b.jar", JAR_BYTES.length, rightHash, Map.of("files/a/b.jar", JAR_BYTES));
        Assertions.assertThrows(DBException.class, () -> DriverOfflineBundleInstaller.read(monitor(), unsafe));
    }

    @Test
    public void matchingBundlePassesManifestAndChecksumChecks() throws Exception {
        String rightHash = DriverOfflineBundle.toHex(DriverOfflineBundle.createDigest().digest(JAR_BYTES));
        Path bundle = bundle("ok.zip", "a/b.jar", JAR_BYTES.length, rightHash, Map.of("files/a/b.jar", JAR_BYTES));
        List<String> names = DriverOfflineBundle.readEntryNames(bundle);
        DriverOfflineBundle.Manifest manifest = manifest("a/b.jar", JAR_BYTES.length, rightHash);
        try (ZipFile zip = new ZipFile(bundle.toFile(), StandardCharsets.UTF_8)) {
            Method validate = DriverOfflineBundleInstaller.class.getDeclaredMethod("validateManifest", DriverOfflineBundle.Manifest.class, List.class);
            validate.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, DriverOfflineBundle.FileEntry> files = (Map<String, DriverOfflineBundle.FileEntry>) invoke(validate, manifest, names);
            Assertions.assertEquals(List.of("a/b.jar"), new ArrayList<>(files.keySet()));
            Method verify = DriverOfflineBundleInstaller.class.getDeclaredMethod("verifyFiles",
                DBRProgressMonitor.class, ZipFile.class, java.util.Collection.class);
            verify.setAccessible(true);
            invoke(verify, monitor(), zip, files.values());
        }
    }

    //////////////////////////////////////////////////////////
    // Helpers

    // The progress monitors of the model ask the workbench for cancellation, which a plain unit test does not have
    @NotNull
    private static DBRProgressMonitor monitor() {
        return Mockito.mock(DBRProgressMonitor.class);
    }

    private interface DirectoryPatch {
        void patch(@NotNull ByteBuffer directory, int recordPosition);
    }

    private static Object invoke(@NotNull Method method, Object... args) throws Exception {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    @NotNull
    private Path zip(@NotNull String name, @NotNull Map<String, byte[]> entries) throws IOException {
        Path file = temp.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : (entries instanceof LinkedHashMap ? entries : new java.util.TreeMap<>(entries)).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    /**
     * Patches the n-th record of the central directory in place.
     */
    private static void patchCentralDirectory(@NotNull Path file, int index, @NotNull DirectoryPatch patch) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        int found = -1;
        for (int pos = 0; pos + 4 <= bytes.limit(); pos++) {
            if (bytes.getInt(pos) == 0x02014b50 && ++found == index) {
                patch.patch(bytes, pos);
                Files.write(file, bytes.array());
                return;
            }
        }
        throw new AssertionError("Central directory record " + index + " not found");
    }

    @NotNull
    private static DriverOfflineBundle.Manifest manifest(@NotNull String path, long size, @NotNull String sha256) {
        DriverOfflineBundle.LibraryFile file = new DriverOfflineBundle.LibraryFile("lib", "1.0", "jar", path, null, null, null);
        DriverOfflineBundle.LibraryEntry library = new DriverOfflineBundle.LibraryEntry("lib", path, "jar", List.of(file));
        DriverOfflineBundle.DriverEntry driver = new DriverOfflineBundle.DriverEntry("no-such-provider", "no-such-driver", "Test", List.of(library));
        return new DriverOfflineBundle.Manifest("0.1.1", "2026-11-08T00:00:00Z", "win32", "x86_64", List.of(driver),
            List.of(new DriverOfflineBundle.FileEntry(path, size, sha256)));
    }

    @NotNull
    private Path bundle(
        @NotNull String name,
        @NotNull String manifestPath,
        long size,
        @NotNull String sha256,
        @NotNull Map<String, byte[]> files
    ) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(DriverOfflineBundle.MANIFEST_ENTRY,
            DriverOfflineBundle.GSON.toJson(manifest(manifestPath, size, sha256)).getBytes(StandardCharsets.UTF_8));
        entries.putAll(files);
        return zip(name, entries);
    }
}
