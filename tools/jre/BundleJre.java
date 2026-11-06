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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Links the bundled Java runtime (JetBrains Runtime 25, reduced with jlink) and puts it into the
 * built Chromite products as {@code jre/}.
 *
 * <pre>
 * java tools/jre/BundleJre.java &lt;platform&gt;...   (platform: windows-x64, osx-aarch64)
 * </pre>
 *
 * Run it from the repository root after the Maven product build, with the JDK 25 that ran the build.
 * For each platform it
 * <ol>
 *   <li>downloads the pinned JBR SDK archive into the download cache (environment variable
 *       {@code CHROMITE_JBR_CACHE}, default {@code ~/.cache/chromite-jbr}) unless it is already there,
 *       and checks its SHA-256 ({@code tools/jre/jbr.properties}),</li>
 *   <li>extracts the archive's {@code jmods/*.jmod} into {@code target/jre/<platform>/jmods},</li>
 *   <li>links the runtime with the jlink of the running JDK into {@code target/jre/<platform>/image},</li>
 *   <li>copies it into every built product of that platform: {@code <root>/jre} on Windows,
 *       {@code <App>.app/Contents/Eclipse/jre} on macOS.</li>
 * </ol>
 *
 * 설계:
 * <ul>
 *   <li>A step after Maven instead of a Tycho root-file feature: p2 keeps a second, zipped copy of every
 *       feature root-file artifact in {@code p2/org.eclipse.equinox.p2.core/cache/binary}, which would add
 *       the runtime twice to each product. Tycho's own {@code includeJRE} only installs Eclipse JustJ
 *       (Temurin) runtimes, not JBR.</li>
 *   <li>Java single-file program: the JDK 25 the build needs anyway runs it on Windows, Linux and macOS
 *       alike, so the CI jobs and local builds need no other tool. jlink runs in process through
 *       {@link ToolProvider}.</li>
 *   <li>Cross-linking: jlink links a runtime for another platform from that platform's jmods (the Linux
 *       CI job links the macOS runtime). jlink only accepts jmods of its own feature release (25), so the
 *       running JDK is checked first.</li>
 *   <li>No default CDS archive ({@code --generate-cds-archive}): it adds 30 MB to the Windows runtime, made
 *       no measurable difference to the Swing product's start (runtime.md "시작 시간") and can only be
 *       generated for the platform jlink runs on, so the macOS runtime could not have it anyway. Without it
 *       the runtime is the same whatever OS links it.</li>
 *   <li>On a POSIX host the executable bits of the macOS runtime ({@code bin/*}, {@code lib/jspawnhelper})
 *       are set explicitly; a Windows host cannot set them, as for the Eclipse launcher of a macOS product
 *       built on Windows (docs/chromite/ci.md).</li>
 *   <li>Messages go to standard output: this is a build tool, not product code.</li>
 * </ul>
 *
 * 변경 이력:
 * <ul>
 *   <li>2026-11-07 Created: bundled JBR 25 runtime for both products (docs/chromite/runtime.md).</li>
 * </ul>
 */
public class BundleJre {

    private static final Path PROPERTIES = Path.of("tools", "jre", "jbr.properties");
    private static final Path WORK_DIR = Path.of("target", "jre");
    private static final String CACHE_ENV = "CHROMITE_JBR_CACHE";
    private static final int REQUIRED_FEATURE = 25;

    /**
     * Built products: product module, product id, Windows root folder, macOS app folder
     * ({@code <product><id>} and {@code <rootFolders>} of the module's pom.xml).
     */
    private static final String[][] PRODUCTS = {
        {"product/community", "org.jkiss.dbeaver.core.product", "chromite", "Chromite.app"},
        {"product/swing", "com.seizia.chromite.swing.product", "chromite-swing", "ChromiteSwing.app"},
    };

    private record Platform(String name, String productEnv, boolean macos) {
    }

    private static final List<Platform> PLATFORMS = List.of(
        new Platform("windows-x64", "win32/win32/x86_64", false),
        new Platform("osx-aarch64", "macosx/cocoa/aarch64", true)
    );

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            fail("usage: java tools/jre/BundleJre.java <platform>... (" + platformNames() + ")");
        }
        if (!Files.isRegularFile(PROPERTIES)) {
            fail("run from the repository root: " + PROPERTIES + " not found");
        }
        Runtime.Version jdk = Runtime.version();
        if (jdk.feature() != REQUIRED_FEATURE) {
            fail("jlink of JDK " + REQUIRED_FEATURE + " is required (it links only jmods of its own release), running " + jdk);
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(PROPERTIES)) {
            props.load(in);
        }
        String cacheEnv = System.getenv(CACHE_ENV);
        Path cache = cacheEnv != null && !cacheEnv.isBlank()
            ? Path.of(cacheEnv)
            : Path.of(System.getProperty("user.home"), ".cache", "chromite-jbr");

        for (String arg : args) {
            Platform platform = PLATFORMS.stream().filter(p -> p.name().equals(arg)).findFirst().orElse(null);
            if (platform == null) {
                fail("unknown platform " + arg + " (" + platformNames() + ")");
            }
            bundle(platform, props, cache);
        }
    }

    private static void bundle(Platform platform, Properties props, Path cache) throws IOException, InterruptedException {
        String version = required(props, "jbr.version");
        String build = required(props, "jbr.build");
        String archiveName = "jbrsdk-" + version + "-" + platform.name() + "-" + build + ".tar.gz";
        String sha256 = required(props, platform.name() + ".sha256").toLowerCase(Locale.ROOT);

        Path archive = cache.resolve(archiveName);
        download(URI.create(required(props, "jbr.url") + archiveName), archive, sha256);

        Path work = WORK_DIR.resolve(platform.name());
        Path jmods = work.resolve("jmods");
        extractJmods(archive, archiveName, jmods);

        Path image = work.resolve("image");
        link(platform, props, jmods, image);

        int installed = 0;
        for (String[] product : PRODUCTS) {
            Path root = Path.of(product[0], "target", "products", product[1]).resolve(platform.productEnv());
            Path productDir = platform.macos()
                ? root.resolve(product[3]).resolve("Contents").resolve("Eclipse")
                : root.resolve(product[2]);
            if (!Files.isDirectory(productDir)) {
                System.out.println("[jre] " + productDir + " not built, skipped");
                continue;
            }
            Path jre = productDir.resolve("jre");
            deleteTree(jre);
            copyTree(image, jre);
            System.out.println("[jre] installed " + jre);
            installed++;
        }
        if (installed == 0) {
            fail("no " + platform.name() + " product found under product/*/target/products: run the Maven product build first");
        }
    }

    /** Downloads into the cache unless a file with the pinned SHA-256 is there already. */
    private static void download(URI uri, Path archive, String sha256) throws IOException, InterruptedException {
        if (Files.isRegularFile(archive)) {
            String actual = sha256(archive);
            if (actual.equals(sha256)) {
                System.out.println("[jre] cached " + archive);
                return;
            }
            System.out.println("[jre] cached " + archive + " has SHA-256 " + actual + ", downloading again");
        }
        Files.createDirectories(archive.getParent());
        Path part = archive.resolveSibling(archive.getFileName() + ".part");
        System.out.println("[jre] downloading " + uri);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<Path> response = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofFile(part));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(part);
            fail("download failed: HTTP " + response.statusCode() + " " + uri);
        }
        String actual = sha256(part);
        if (!actual.equals(sha256)) {
            Files.deleteIfExists(part);
            fail("SHA-256 mismatch for " + uri + ": expected " + sha256 + ", got " + actual);
        }
        // Rename only after the check, so the cache never holds an unverified archive under the final name.
        Files.move(part, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Extracts {@code <top>/.../jmods/*.jmod} of the tar.gz into a flat folder. The JDK has no tar API,
     * so this reads the ustar format directly: GNU long names ('L') and pax headers ('x') are
     * honoured for the entry name, everything else is skipped. Only the file name after
     * {@code jmods/} is used, so no entry can write outside the folder.
     */
    private static void extractJmods(Path archive, String archiveName, Path jmods) throws IOException {
        Path marker = jmods.resolve(".from");
        if (Files.isRegularFile(marker) && Files.readString(marker).equals(archiveName)) {
            System.out.println("[jre] jmods already extracted in " + jmods);
            return;
        }
        deleteTree(jmods);
        Files.createDirectories(jmods);
        int count = 0;
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(archive), 1 << 16), 1 << 16)) {
            byte[] header = new byte[512];
            String longName = null;
            while (in.readNBytes(header, 0, 512) == 512 && !allZero(header)) {
                long size = parseOctal(header, 124, 12);
                long padded = (size + 511) & ~511L;
                char type = (char) header[156];
                String name = cString(header, 0, 100);
                if ("ustar".equals(cString(header, 257, 5))) {
                    String prefix = cString(header, 345, 155);
                    if (!prefix.isEmpty()) {
                        name = prefix + "/" + name;
                    }
                }
                if (longName != null) {
                    name = longName;
                    longName = null;
                }
                if (type == 'L' || type == 'x') {
                    byte[] data = in.readNBytes((int) size);
                    in.skipNBytes(padded - size);
                    longName = type == 'L' ? cString(data, 0, data.length) : paxPath(data);
                    continue;
                }
                int slash = name.lastIndexOf('/');
                String fileName = name.substring(slash + 1);
                boolean jmod = (type == '0' || type == 0) && name.substring(0, Math.max(slash, 0)).endsWith("jmods")
                    && fileName.endsWith(".jmod") && !fileName.startsWith(".");
                if (jmod) {
                    try (OutputStream out = Files.newOutputStream(jmods.resolve(fileName))) {
                        copyBytes(in, out, size);
                    }
                    in.skipNBytes(padded - size);
                    count++;
                } else {
                    in.skipNBytes(padded);
                }
            }
        }
        if (count == 0 || !Files.isRegularFile(jmods.resolve("java.base.jmod"))) {
            fail(archive + " has no jmods/java.base.jmod: a JBR SDK (jbrsdk) archive is needed");
        }
        Files.writeString(marker, archiveName);
        System.out.println("[jre] extracted " + count + " jmods into " + jmods);
    }

    private static void link(Platform platform, Properties props, Path jmods, Path image) throws IOException {
        Set<String> modules = new LinkedHashSet<>(splitList(required(props, "modules")));
        modules.addAll(splitList(props.getProperty(platform.name() + ".modules", "")));
        List<String> args = new ArrayList<>(List.of(
            "--module-path", jmods.toString(),
            "--add-modules", String.join(",", modules),
            "--output", image.toString()));
        args.addAll(Arrays.asList(required(props, "jlink.options").trim().split("\\s+")));
        deleteTree(image);
        Files.createDirectories(image.getParent());
        System.out.println("[jre] jlink " + String.join(" ", args));
        ToolProvider jlink = ToolProvider.findFirst("jlink")
            .orElseThrow(() -> new IllegalStateException("jlink not found: run with a JDK (jdk.jlink module), not a JRE"));
        int code = jlink.run(new PrintWriter(System.out, true), new PrintWriter(System.err, true), args.toArray(String[]::new));
        if (code != 0) {
            fail("jlink failed with exit code " + code);
        }
        if (platform.macos()) {
            setMacExecutableBits(image);
        }
        System.out.println("[jre] " + platform.name() + " runtime: " + treeSize(image) / (1024 * 1024) + " MB in " + image);
    }

    private static void setMacExecutableBits(Path image) throws IOException {
        if (!image.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            System.out.println("[jre] no POSIX permissions on this host: the macOS runtime has no executable bits (not for distribution)");
            return;
        }
        List<Path> executables = new ArrayList<>();
        try (Stream<Path> bin = Files.list(image.resolve("bin"))) {
            bin.filter(Files::isRegularFile).forEach(executables::add);
        }
        Path spawnHelper = image.resolve("lib").resolve("jspawnhelper");
        if (Files.isRegularFile(spawnHelper)) {
            // Process launching (ProcessBuilder) on macOS starts child processes through jspawnhelper.
            executables.add(spawnHelper);
        }
        for (Path file : executables) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    private static String paxPath(byte[] data) {
        // Records: "<length> <key>=<value>\n"
        String text = new String(data, StandardCharsets.UTF_8);
        int pos = 0;
        while (pos < text.length()) {
            int space = text.indexOf(' ', pos);
            if (space < 0) {
                break;
            }
            int length = Integer.parseInt(text.substring(pos, space));
            String record = text.substring(space + 1, pos + length - 1);
            if (record.startsWith("path=")) {
                return record.substring(5);
            }
            pos += length;
        }
        return null;
    }

    private static boolean allZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String cString(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] data, int offset, int length) {
        String text = cString(data, offset, length).trim();
        return text.isEmpty() ? 0 : Long.parseLong(text, 8);
    }

    private static void copyBytes(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[1 << 16];
        long left = size;
        while (left > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) {
                throw new IOException("unexpected end of archive");
            }
            out.write(buffer, 0, read);
            left -= read;
        }
    }

    private static String sha256(Path file) throws IOException {
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // COPY_ATTRIBUTES keeps the POSIX permissions; NOFOLLOW_LINKS copies symbolic links as links.
                Files.copy(file, target.resolve(source.relativize(file).toString()),
                    StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static long treeSize(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
    }

    private static List<String> splitList(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String required(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            fail(PROPERTIES + ": " + key + " is not set");
        }
        return value.trim();
    }

    private static String platformNames() {
        return String.join(", ", PLATFORMS.stream().map(Platform::name).toList());
    }

    private static void fail(String message) {
        System.err.println("[jre] ERROR: " + message);
        System.exit(1);
    }
}
