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
package com.seizia.chromite.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBConfigurationController;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.registry.driver.DriverDescriptor;
import com.seizia.chromite.registry.driver.DriverDescriptorSerializerLegacy;
import com.seizia.chromite.runtime.DBWorkbench;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.xml.SAXReader;
import org.jkiss.utils.xml.XMLException;
import org.jkiss.utils.xml.XMLUtils;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Exports settings (preferences, option files, driver settings, and the files and connections the UI adds) to one file
 * and imports them back with a preview of each change.
 *
 * 설계:
 * <ul>
 *     <li>파일({@code .chromite-settings})은 평범한 zip이다: {@code manifest.json}(형식·버전·고른 묶음), 설정 값({@code preferences.json}),
 *     옵션 설정 파일({@code config/<이름>}), 드라이버 설정({@code drivers.xml}), 그리고 화면 쪽이 넣는 Query Library·스크립트 파일과
 *     #108의 연결 파일({@code connections.chromite-connections}, 비밀번호를 넣으면 그 파일 형식대로 암호로 암호화). 읽을 때 항목 수·크기를
 *     실제로 읽은 바이트로 세고(압축 폭탄), 절대 경로·드라이브 문자·{@code ..}가 든 항목 이름을 거부한다(zip-slip).</li>
 *     <li>비밀은 평문으로 쓰지 않는다. 설정 값과 드라이버 속성 중 이름이 비밀처럼 보이는 것({@link #SECRET_NAME})은 참·거짓·숫자가 아니면
 *     넣지 않고 그 수를 알린다. 연결 비밀번호는 #108의 암호화 파일로만 간다. 가져오기도 같은 규칙으로 걸러 그런 값을 쓰지 않는다.</li>
 *     <li>설정 값은 Chromite 번들의 Eclipse 설정 노드({@code com.seizia.chromite*}, 인스턴스 범위)에서 사용자가 정한 값만 읽는다.
 *     이 PC의 상태(창 위치, 최근 파일, 경로 등)는 화면 쪽이 {@link PreferenceFilter}로 뺀다. 가져오기는 합친다: 파일에 없는 키는 그대로 둔다.
 *     노드에 바로 쓰므로 번들 설정 저장소가 값을 읽을 때 바로 보이지만, 시작할 때만 읽는 값(테마, 글꼴 등)은 다시 시작한 뒤 적용된다.</li>
 *     <li>드라이버는 레지스트리의 직렬화기({@link DriverDescriptorSerializerLegacy})가 쓰는 {@code drivers.xml} 형식으로, 사용자 드라이버와
 *     바꾼 기본 드라이버만 담는다. 이 PC에 내려받은 jar 위치({@code <file>})는 빼서 가져온 PC가 다시 찾게 한다. 가져오기는 같은 직렬화기의
 *     파서로 실행 중인 레지스트리에 드라이버마다 합친다(바꾸기는 기본값으로 되돌리거나 사용자 드라이버를 비운 뒤 읽음). 그래서 연결이 쥔 드라이버
 *     객체가 그대로이고, 나중에 레지스트리가 저장해도 가져온 값이 남는다.</li>
 *     <li>모든 바꿈은 먼저 {@link SettingsBackup}에 이전 값을 적고 저장한 뒤 한다(되돌리기).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/settings-transfer.md)
 */
public final class SettingsTransfer {

    private static final Log log = Log.getLog(SettingsTransfer.class);

    public static final String FILE_EXTENSION = "chromite-settings"; //$NON-NLS-1$
    private static final String FORMAT = "chromite-settings"; //$NON-NLS-1$
    private static final int VERSION = 1;

    private static final String ENTRY_MANIFEST = "manifest.json"; //$NON-NLS-1$
    private static final String ENTRY_PREFERENCES = "preferences.json"; //$NON-NLS-1$
    private static final String ENTRY_DRIVERS = "drivers.xml"; //$NON-NLS-1$
    private static final String CONFIG_FOLDER = "config/"; //$NON-NLS-1$
    public static final String QUERY_LIBRARY_FOLDER = "query-library/"; //$NON-NLS-1$
    public static final String SCRIPTS_FOLDER = "scripts/"; //$NON-NLS-1$
    public static final String ENTRY_SCRIPT_LINKS = "script-links.json"; //$NON-NLS-1$
    public static final String ENTRY_CONNECTIONS = "connections." + ConnectionTransferFile.FILE_EXTENSION; //$NON-NLS-1$

    private static final String MANIFEST_FORMAT = "format"; //$NON-NLS-1$
    private static final String MANIFEST_VERSION = "version"; //$NON-NLS-1$
    private static final String MANIFEST_CREATED = "created"; //$NON-NLS-1$
    private static final String MANIFEST_SECTIONS = "sections"; //$NON-NLS-1$

    static final int MAX_ENTRIES = 20_000;
    static final long MAX_ENTRY_BYTES = 20L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;

    /**
     * Preference nodes of the Chromite bundles (instance scope): the bundle symbolic names start with it.
     */
    private static final String PREFERENCE_NODE_PREFIX = "com.seizia.chromite"; //$NON-NLS-1$
    /**
     * Names that look like a secret; their values are never written into a settings file (a true/false or number value is a switch, not a secret).
     */
    static final Pattern SECRET_NAME = Pattern.compile(
        "(?i)(password|passwd|passphrase|pwd|secret|token|api[-_.]?key|credential|private[-_.]?key)"); //$NON-NLS-1$
    private static final Pattern NOT_SECRET_VALUE = Pattern.compile("(?i)true|false|-?\\d+(\\.\\d+)?"); //$NON-NLS-1$

    /**
     * Parts of a settings file, chosen when it is exported.
     */
    public enum Section {
        PREFERENCES,
        DRIVERS,
        QUERY_LIBRARY,
        SCRIPTS,
        CONNECTIONS
    }

    /**
     * How an item of the file relates to this computer.
     */
    public enum Kind {
        /**
         * Not here, or here at its default: importing adds it.
         */
        NEW,
        /**
         * Here with something else: importing replaces it (or keeps both, where an item can exist twice).
         */
        CONFLICT
    }

    /**
     * What to do with an item.
     */
    public enum Action {
        IMPORT,
        SKIP,
        REPLACE,
        KEEP_BOTH
    }

    /**
     * Why an item cannot be replaced (or imported at all).
     */
    public enum Blocker {
        NONE,
        /**
         * The file is open in a query window: replacing it would leave the window with other text.
         */
        OPEN,
        /**
         * The existing item is in a read-only place.
         */
        READ_ONLY,
        /**
         * The item needs something that is not installed here (a driver of a provider this product does not have).
         */
        UNAVAILABLE
    }

    /**
     * A settings file of the configuration controller, imported as one item (option pages that keep their own file).
     *
     * @param product  in the product configuration (otherwise the workspace configuration)
     * @param onChange reloads what the application keeps in memory from the file; called after it is written or restored
     */
    public record ConfigFile(@NotNull String name, boolean product, @Nullable Runnable onChange) {
        @NotNull
        DBConfigurationController getController() {
            return product
                ? DBWorkbench.getPlatform().getProductConfigurationController()
                : DBWorkbench.getPlatform().getConfigurationController();
        }

        void changed() {
            if (onChange != null) {
                onChange.run();
            }
        }
    }

    /**
     * Decides which preference keys are settings to move (and not the state of this computer).
     */
    public interface PreferenceFilter {
        boolean isExported(@NotNull String node, @NotNull String key);
    }

    /**
     * One item of an import: a preference, an option file, a driver, a file, with what importing it would do.
     */
    public static final class Change {
        @NotNull
        private final Section section;
        @NotNull
        private final String name;
        @NotNull
        private final Kind kind;
        private final boolean existsHere;
        @Nullable
        private final String current;
        @Nullable
        private final String imported;
        private final boolean keepBothAllowed;
        @NotNull
        private final Blocker blocker;
        @Nullable
        private final Object target;
        @NotNull
        private Action action;

        /**
         * @param existsHere      the item is here (for {@link Kind#NEW}: at its default)
         * @param current         value here, shown in the preview (preferences), or null
         * @param imported        value of the file, shown in the preview (preferences), or null
         * @param keepBothAllowed the item can exist twice (files): "keep both" imports it under another name
         * @param target          what the section needs to apply the change
         */
        public Change(
            @NotNull Section section,
            @NotNull String name,
            @NotNull Kind kind,
            boolean existsHere,
            @Nullable String current,
            @Nullable String imported,
            boolean keepBothAllowed,
            @NotNull Blocker blocker,
            @Nullable Object target
        ) {
            this.section = section;
            this.name = name;
            this.kind = kind;
            this.existsHere = existsHere;
            this.current = current;
            this.imported = imported;
            this.keepBothAllowed = keepBothAllowed;
            this.blocker = blocker;
            this.target = target;
            // A conflict is left as it is unless the user decides otherwise
            this.action = blocker == Blocker.UNAVAILABLE ? Action.SKIP : kind == Kind.NEW ? Action.IMPORT : Action.SKIP;
        }

        @NotNull
        public Section getSection() {
            return section;
        }

        @NotNull
        public String getName() {
            return name;
        }

        @NotNull
        public Kind getKind() {
            return kind;
        }

        public boolean isExistsHere() {
            return existsHere;
        }

        @Nullable
        public String getCurrent() {
            return current;
        }

        @Nullable
        public String getImported() {
            return imported;
        }

        @NotNull
        public Blocker getBlocker() {
            return blocker;
        }

        @Nullable
        public Object getTarget() {
            return target;
        }

        @NotNull
        public Action getAction() {
            return action;
        }

        /**
         * Sets the action if it is one of {@link #getChoices()}.
         */
        public void setAction(@NotNull Action action) {
            if (getChoices().contains(action)) {
                this.action = action;
            }
        }

        /**
         * The actions the user can pick.
         */
        @NotNull
        public List<Action> getChoices() {
            if (blocker == Blocker.UNAVAILABLE) {
                return List.of(Action.SKIP);
            }
            if (kind == Kind.NEW) {
                return List.of(Action.IMPORT, Action.SKIP);
            }
            List<Action> choices = new ArrayList<>();
            choices.add(Action.SKIP);
            if (blocker == Blocker.NONE) {
                choices.add(Action.REPLACE);
            }
            if (keepBothAllowed) {
                choices.add(Action.KEEP_BOTH);
            }
            return choices;
        }

        /**
         * The change writes something.
         */
        public boolean isApplied() {
            return action != Action.SKIP;
        }
    }

    /**
     * The entries of a settings file.
     */
    public static final class Archive {
        private final Map<String, byte[]> entries = new LinkedHashMap<>();
        @NotNull
        private final Set<Section> sections;
        @Nullable
        private String created;

        public Archive(@NotNull Set<Section> sections) {
            this.sections = sections.isEmpty() ? EnumSet.noneOf(Section.class) : EnumSet.copyOf(sections);
        }

        @NotNull
        public Set<Section> getSections() {
            return Collections.unmodifiableSet(sections);
        }

        /**
         * When the file was written (ISO-8601), or null for a new archive.
         */
        @Nullable
        public String getCreated() {
            return created;
        }

        @Nullable
        public byte[] get(@NotNull String name) {
            return entries.get(name);
        }

        public void put(@NotNull String name, @NotNull byte[] data) {
            entries.put(name, data);
        }

        /**
         * The entries in a folder, by their path in it ({@code /} separators), sorted.
         */
        @NotNull
        public SortedMap<String, byte[]> getFolder(@NotNull String folder) {
            SortedMap<String, byte[]> result = new TreeMap<>();
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (entry.getKey().startsWith(folder) && entry.getKey().length() > folder.length()) {
                    result.put(entry.getKey().substring(folder.length()), entry.getValue());
                }
            }
            return result;
        }

        /**
         * Writes the file (a temporary file first, so a failed export leaves an older file as it was).
         */
        public void write(@NotNull Path file) throws DBException {
            JsonObject manifest = new JsonObject();
            manifest.addProperty(MANIFEST_FORMAT, FORMAT);
            manifest.addProperty(MANIFEST_VERSION, VERSION);
            manifest.addProperty(MANIFEST_CREATED, Instant.now().toString());
            JsonArray sectionsJson = new JsonArray();
            for (Section section : sections) {
                sectionsJson.add(section.name());
            }
            manifest.add(MANIFEST_SECTIONS, sectionsJson);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp"); //$NON-NLS-1$
            try {
                try (OutputStream out = Files.newOutputStream(temporary); ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                    putEntry(zip, ENTRY_MANIFEST, manifest.toString().getBytes(StandardCharsets.UTF_8));
                    for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                        putEntry(zip, entry.getKey(), entry.getValue());
                    }
                }
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ex) {
                    log.debug("Can't delete " + temporary, ex);
                }
                throw new DBException("Cannot write " + file, e);
            }
        }

        /**
         * Reads and checks a settings file.
         */
        @NotNull
        public static Archive read(@NotNull Path file) throws DBException {
            Map<String, byte[]> read = new LinkedHashMap<>();
            try (InputStream in = Files.newInputStream(file); ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
                readEntries(zip, read);
            } catch (ZipException | IllegalArgumentException e) {
                throw notSettingsFile(e);
            } catch (IOException e) {
                throw new DBException("Cannot read " + file, e);
            }
            byte[] manifestBytes = read.remove(ENTRY_MANIFEST);
            if (manifestBytes == null) {
                throw notSettingsFile(null);
            }
            JsonObject manifest;
            try {
                JsonElement element = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8));
                manifest = element.isJsonObject() ? element.getAsJsonObject() : null;
            } catch (JsonParseException e) {
                throw notSettingsFile(e);
            }
            if (manifest == null || !FORMAT.equals(getString(manifest, MANIFEST_FORMAT))) {
                throw notSettingsFile(null);
            }
            int version = CommonUtils.toInt(getString(manifest, MANIFEST_VERSION), -1);
            if (version < 1) {
                throw notSettingsFile(null);
            }
            if (version > VERSION) {
                throw new SettingsTransferException(SettingsTransferException.Reason.UNSUPPORTED_FORMAT,
                    "The settings file has version " + version + "; this version reads up to " + VERSION);
            }
            EnumSet<Section> sections = EnumSet.noneOf(Section.class);
            JsonElement sectionsJson = manifest.get(MANIFEST_SECTIONS);
            if (sectionsJson != null && sectionsJson.isJsonArray()) {
                for (JsonElement sectionJson : sectionsJson.getAsJsonArray()) {
                    // A section of a newer version is ignored
                    Section section = sectionJson.isJsonPrimitive() ? CommonUtils.valueOf(Section.class, sectionJson.getAsString(), null) : null;
                    if (section != null) {
                        sections.add(section);
                    }
                }
            }
            Archive archive = new Archive(sections);
            archive.created = getString(manifest, MANIFEST_CREATED);
            archive.entries.putAll(read);
            return archive;
        }

        private static void readEntries(@NotNull ZipInputStream zip, @NotNull Map<String, byte[]> result) throws IOException, DBException {
            long total = 0;
            int count = 0;
            for (ZipEntry zipEntry; (zipEntry = zip.getNextEntry()) != null; ) {
                if (++count > MAX_ENTRIES) {
                    throw invalid("More than " + MAX_ENTRIES + " entries");
                }
                if (zipEntry.isDirectory()) {
                    continue;
                }
                String name = checkEntryName(zipEntry.getName());
                // Sizes are counted from the bytes read, not from the entry headers
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                long size = 0;
                for (int read; (read = zip.read(buffer)) > 0; ) {
                    size += read;
                    total += read;
                    if (size > MAX_ENTRY_BYTES) {
                        throw invalid("Entry " + name + " is larger than " + MAX_ENTRY_BYTES + " bytes");
                    }
                    if (total > MAX_TOTAL_BYTES) {
                        throw invalid("The content is larger than " + MAX_TOTAL_BYTES + " bytes");
                    }
                    out.write(buffer, 0, read);
                }
                result.put(name, out.toByteArray());
            }
        }

        private static void putEntry(@NotNull ZipOutputStream zip, @NotNull String name, @NotNull byte[] data) throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(data);
            zip.closeEntry();
        }
    }

    private SettingsTransfer() {
    }

    //////////////////////////////////////////////////////////
    // Paths of entries and files

    /**
     * The entry name as a relative path with {@code /}; refuses names that point outside the folder they are written to.
     */
    @NotNull
    static String checkEntryName(@NotNull String name) throws DBException {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":")) { //$NON-NLS-1$ //$NON-NLS-2$
            throw invalid("Bad entry path " + name);
        }
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) { //$NON-NLS-1$
            if (part.isEmpty() || part.equals(".")) { //$NON-NLS-1$
                continue;
            }
            if (part.equals("..")) { //$NON-NLS-1$
                throw invalid("Bad entry path " + name);
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            throw invalid("Bad entry path " + name);
        }
        return String.join("/", parts); //$NON-NLS-1$
    }

    /**
     * The file of a relative path in a folder, checked again to stay inside the folder.
     */
    @NotNull
    public static Path resolveInside(@NotNull Path folder, @NotNull String relativePath) throws DBException {
        Path base = folder.toAbsolutePath().normalize();
        try {
            Path target = base.resolve(checkEntryName(relativePath)).normalize();
            if (!target.startsWith(base) || target.equals(base)) {
                throw invalid("Bad entry path " + relativePath);
            }
            return target;
        } catch (java.nio.file.InvalidPathException e) {
            throw new SettingsTransferException(SettingsTransferException.Reason.INVALID_CONTENT, "Bad entry path " + relativePath, e);
        }
    }

    /**
     * True if a name/value pair looks like a secret and must not be written into a settings file.
     */
    public static boolean isSecret(@NotNull String name, @Nullable String value) {
        return SECRET_NAME.matcher(name).find() && !CommonUtils.isEmpty(value) && !NOT_SECRET_VALUE.matcher(value.trim()).matches();
    }

    //////////////////////////////////////////////////////////
    // Preferences

    /**
     * A preference to set.
     */
    private record PreferenceTarget(@NotNull String node, @NotNull String key, @NotNull String value) {
    }

    /**
     * Puts the preferences set on this computer (not their defaults) into the archive.
     *
     * @param skippedSecrets gets the number of values left out because they look like secrets (first element)
     * @return the number of preferences
     */
    public static int exportPreferences(@NotNull Archive archive, @NotNull PreferenceFilter filter, @NotNull int[] skippedSecrets) throws DBException {
        JsonObject nodesJson = new JsonObject();
        int count = 0;
        try {
            for (Preferences node : getChromiteNodes()) {
                String nodePath = getNodePath(node);
                JsonObject valuesJson = new JsonObject();
                String[] keys = node.keys();
                Arrays.sort(keys);
                for (String key : keys) {
                    String value = node.get(key, null);
                    if (value == null || !filter.isExported(nodePath, key)) {
                        continue;
                    }
                    if (isSecret(key, value)) {
                        skippedSecrets[0]++;
                        continue;
                    }
                    valuesJson.addProperty(key, value);
                    count++;
                }
                if (!valuesJson.isEmpty()) {
                    nodesJson.add(nodePath, valuesJson);
                }
            }
        } catch (BackingStoreException e) {
            throw new DBException("Cannot read the preferences", e);
        }
        archive.put(ENTRY_PREFERENCES, nodesJson.toString().getBytes(StandardCharsets.UTF_8));
        return count;
    }

    /**
     * The preferences of the archive that differ from this computer.
     */
    @NotNull
    public static List<Change> previewPreferences(@NotNull Archive archive, @NotNull PreferenceFilter filter) throws DBException {
        byte[] data = archive.get(ENTRY_PREFERENCES);
        if (data == null) {
            return List.of();
        }
        JsonObject nodesJson = parseObject(data, ENTRY_PREFERENCES);
        List<Change> changes = new ArrayList<>();
        Preferences instanceRoot = getInstanceRoot();
        Preferences defaultRoot = Platform.getPreferencesService().getRootNode().node(DefaultScope.SCOPE);
        for (String nodePath : new TreeSet<>(nodesJson.keySet())) {
            JsonObject valuesJson = getObject(nodesJson, nodePath);
            if (valuesJson == null || !isChromiteNode(nodePath)) {
                continue;
            }
            Preferences node = instanceRoot.node(nodePath);
            Preferences defaults = defaultRoot.node(nodePath);
            for (String key : new TreeSet<>(valuesJson.keySet())) {
                String value = getString(valuesJson, key);
                // A file from elsewhere may carry what this product leaves out; it is never written
                if (value == null || !filter.isExported(nodePath, key) || isSecret(key, value)) {
                    continue;
                }
                String current = node.get(key, null);
                String name = getNodeLabel(nodePath) + ": " + key; //$NON-NLS-1$
                PreferenceTarget target = new PreferenceTarget(nodePath, key, value);
                if (current != null) {
                    if (!current.equals(value)) {
                        changes.add(new Change(Section.PREFERENCES, name, Kind.CONFLICT, true, current, value, false, Blocker.NONE, target));
                    }
                } else {
                    String defaultValue = defaults.get(key, null);
                    if (!value.equals(defaultValue)) {
                        changes.add(new Change(Section.PREFERENCES, name, Kind.NEW, true, defaultValue, value, false, Blocker.NONE, target));
                    }
                }
            }
        }
        return changes;
    }

    /**
     * Records the values that the chosen preference changes replace.
     */
    public static void backupPreferences(@NotNull List<Change> changes, @NotNull SettingsBackup backup) {
        Preferences instanceRoot = getInstanceRoot();
        for (Change change : changes) {
            if (change.isApplied() && change.target instanceof PreferenceTarget target) {
                backup.recordPreference(target.node(), target.key(), instanceRoot.node(target.node()).get(target.key(), null), target.value());
            }
        }
    }

    /**
     * Sets the chosen preferences.
     *
     * @return the number of preferences set
     */
    public static int applyPreferences(@NotNull List<Change> changes) throws DBException {
        Preferences instanceRoot = getInstanceRoot();
        Set<Preferences> changed = new LinkedHashSet<>();
        int count = 0;
        for (Change change : changes) {
            if (change.isApplied() && change.target instanceof PreferenceTarget target) {
                Preferences node = instanceRoot.node(target.node());
                node.put(target.key(), target.value());
                changed.add(node);
                count++;
            }
        }
        flush(changed);
        return count;
    }

    static void flush(@NotNull Collection<Preferences> nodes) throws DBException {
        for (Preferences node : nodes) {
            try {
                node.flush();
            } catch (BackingStoreException e) {
                throw new DBException("Cannot save the preferences " + node.absolutePath(), e);
            }
        }
    }

    @NotNull
    static Preferences getInstanceRoot() {
        return Platform.getPreferencesService().getRootNode().node(InstanceScope.SCOPE);
    }

    /**
     * The instance nodes of the Chromite bundles and their child nodes.
     */
    @NotNull
    private static List<Preferences> getChromiteNodes() throws BackingStoreException {
        List<Preferences> result = new ArrayList<>();
        Preferences root = getInstanceRoot();
        String[] names = root.childrenNames();
        Arrays.sort(names);
        for (String name : names) {
            if (isChromiteNode(name)) {
                addNodes(root.node(name), result);
            }
        }
        return result;
    }

    private static void addNodes(@NotNull Preferences node, @NotNull List<Preferences> result) throws BackingStoreException {
        result.add(node);
        String[] names = node.childrenNames();
        Arrays.sort(names);
        for (String name : names) {
            addNodes(node.node(name), result);
        }
    }

    private static boolean isChromiteNode(@NotNull String nodePath) {
        return (nodePath.equals(PREFERENCE_NODE_PREFIX) || nodePath.startsWith(PREFERENCE_NODE_PREFIX + ".")) //$NON-NLS-1$
            && !nodePath.contains(".."); //$NON-NLS-1$
    }

    /**
     * The node path below the instance scope ("com.seizia.chromite.ui.swing", "com.seizia.chromite.ui.swing/child").
     */
    @NotNull
    private static String getNodePath(@NotNull Preferences node) {
        String rootPath = getInstanceRoot().absolutePath() + "/"; //$NON-NLS-1$
        String path = node.absolutePath();
        return path.startsWith(rootPath) ? path.substring(rootPath.length()) : path;
    }

    /**
     * A short label of a node for the preview: the bundle name without the common prefix.
     */
    @NotNull
    private static String getNodeLabel(@NotNull String nodePath) {
        return nodePath.startsWith(PREFERENCE_NODE_PREFIX + ".") ? nodePath.substring(PREFERENCE_NODE_PREFIX.length() + 1) : nodePath; //$NON-NLS-1$
    }

    //////////////////////////////////////////////////////////
    // Option files

    /**
     * A file to write.
     */
    private record ConfigTarget(@NotNull ConfigFile file, @NotNull String content) {
    }

    /**
     * Puts the option files that exist here into the archive.
     *
     * @return the number of files
     */
    public static int exportConfigFiles(@NotNull Archive archive, @NotNull List<ConfigFile> files) throws DBException {
        int count = 0;
        for (ConfigFile file : files) {
            String content = file.getController().loadConfigurationFile(file.name());
            if (!CommonUtils.isEmptyTrimmed(content)) {
                archive.put(CONFIG_FOLDER + file.name(), content.getBytes(StandardCharsets.UTF_8));
                count++;
            }
        }
        return count;
    }

    /**
     * The option files of the archive that differ from this computer. Each file is one item: replaced as a whole.
     */
    @NotNull
    public static List<Change> previewConfigFiles(@NotNull Archive archive, @NotNull List<ConfigFile> files) throws DBException {
        List<Change> changes = new ArrayList<>();
        for (ConfigFile file : files) {
            byte[] data = archive.get(CONFIG_FOLDER + file.name());
            if (data == null) {
                continue;
            }
            String imported = new String(data, StandardCharsets.UTF_8);
            String current = file.getController().loadConfigurationFile(file.name());
            if (imported.equals(current)) {
                continue;
            }
            boolean exists = !CommonUtils.isEmptyTrimmed(current);
            changes.add(new Change(Section.PREFERENCES, file.name(), exists ? Kind.CONFLICT : Kind.NEW, exists, null, null, false,
                Blocker.NONE, new ConfigTarget(file, imported)));
        }
        return changes;
    }

    public static void backupConfigFiles(@NotNull List<Change> changes, @NotNull SettingsBackup backup) throws DBException {
        for (Change change : changes) {
            if (change.isApplied() && change.target instanceof ConfigTarget target) {
                backup.recordConfig(target.file(), target.file().getController().loadConfigurationFile(target.file().name()), target.content());
            }
        }
    }

    /**
     * Writes the chosen option files and reloads what uses them.
     *
     * @return the number of files written
     */
    public static int applyConfigFiles(@NotNull List<Change> changes) throws DBException {
        int count = 0;
        for (Change change : changes) {
            if (change.isApplied() && change.target instanceof ConfigTarget target) {
                target.file().getController().saveConfigurationFile(target.file().name(), target.content());
                target.file().changed();
                count++;
            }
        }
        return count;
    }

    //////////////////////////////////////////////////////////
    // Drivers

    /**
     * How a driver of the file is here before the import.
     */
    enum DriverState {
        /**
         * No such driver (a user driver of the file).
         */
        ABSENT,
        /**
         * A built-in driver with its default settings.
         */
        DEFAULT,
        /**
         * A user driver, or a built-in driver whose settings were changed.
         */
        MODIFIED
    }

    /**
     * A driver to merge: the {@code <driver>} element of the file and its state here.
     */
    private record DriverTarget(@NotNull String providerId, @NotNull String driverId, @NotNull String xml, @NotNull DriverState state,
                                @Nullable String currentXml) {
    }

    /**
     * Puts the user drivers and the changed built-in drivers into the archive.
     *
     * @param skippedSecrets gets the number of driver properties left out because they look like secrets (first element)
     * @return the number of drivers
     */
    public static int exportDrivers(@NotNull Archive archive, @NotNull int[] skippedSecrets) throws DBException {
        Document document = serializeDrivers();
        int count = 0;
        for (Element providerElement : XMLUtils.getChildElementList(document.getDocumentElement(), RegistryConstants.TAG_PROVIDER)) {
            for (Element driverElement : XMLUtils.getChildElementList(providerElement, RegistryConstants.TAG_DRIVER)) {
                skippedSecrets[0] += stripDriver(driverElement);
                count++;
            }
        }
        if (count > 0) {
            removeWhitespace(document.getDocumentElement());
            archive.put(ENTRY_DRIVERS, toXml(document.getDocumentElement(), true).getBytes(StandardCharsets.UTF_8));
        }
        return count;
    }

    /**
     * The drivers of the archive that differ from this computer.
     */
    @NotNull
    public static List<Change> previewDrivers(@NotNull Archive archive) throws DBException {
        byte[] data = archive.get(ENTRY_DRIVERS);
        if (data == null) {
            return List.of();
        }
        Document fileDocument = parseXml(data);
        // The drivers changed here, in the same form as the file
        Map<String, String> localDrivers = new HashMap<>();
        Document localDocument = serializeDrivers();
        for (Element providerElement : XMLUtils.getChildElementList(localDocument.getDocumentElement(), RegistryConstants.TAG_PROVIDER)) {
            for (Element driverElement : XMLUtils.getChildElementList(providerElement, RegistryConstants.TAG_DRIVER)) {
                stripDriver(driverElement);
                localDrivers.put(providerElement.getAttribute(RegistryConstants.ATTR_ID) + "/" + driverElement.getAttribute(RegistryConstants.ATTR_ID), //$NON-NLS-1$
                    toXml(driverElement, false));
            }
        }
        DataSourceProviderRegistry registry = DataSourceProviderRegistry.getInstance();
        List<Change> changes = new ArrayList<>();
        for (Element providerElement : XMLUtils.getChildElementList(fileDocument.getDocumentElement(), RegistryConstants.TAG_PROVIDER)) {
            String providerId = providerElement.getAttribute(RegistryConstants.ATTR_ID);
            DataSourceProviderDescriptor provider = registry.getDataSourceProvider(providerId);
            for (Element driverElement : XMLUtils.getChildElementList(providerElement, RegistryConstants.TAG_DRIVER)) {
                String driverId = driverElement.getAttribute(RegistryConstants.ATTR_ID);
                if (CommonUtils.isEmpty(driverId)) {
                    continue;
                }
                // A file from elsewhere is cleaned like an export: no secret, no local jar location
                stripDriver(driverElement);
                String xml = toXml(driverElement, false);
                DBPDriver driver = provider == null ? null : provider.getDriver(driverId);
                String name = (provider == null ? providerId : provider.getName()) + " / " //$NON-NLS-1$
                    + (driver != null ? driver.getName() : CommonUtils.toString(driverElement.getAttribute(RegistryConstants.ATTR_NAME), driverId));
                if (provider == null || driver != null && !(driver instanceof DriverDescriptor)) {
                    changes.add(new Change(Section.DRIVERS, name, Kind.NEW, false, null, null, false, Blocker.UNAVAILABLE, null));
                    continue;
                }
                String currentXml = localDrivers.get(providerId + "/" + driverId); //$NON-NLS-1$
                if (driver == null) {
                    changes.add(new Change(Section.DRIVERS, name, Kind.NEW, false, null, null, false, Blocker.NONE,
                        new DriverTarget(providerId, driverId, xml, DriverState.ABSENT, null)));
                } else if (currentXml == null) {
                    // A driver whose library files were only downloaded on the other computer has its default settings
                    if (!xml.equals(serializeDefaultDriver((DriverDescriptor) driver))) {
                        changes.add(new Change(Section.DRIVERS, name, Kind.NEW, true, null, null, false, Blocker.NONE,
                            new DriverTarget(providerId, driverId, xml, DriverState.DEFAULT, null)));
                    }
                } else if (!currentXml.equals(xml)) {
                    changes.add(new Change(Section.DRIVERS, name, Kind.CONFLICT, true, null, null, false, Blocker.NONE,
                        new DriverTarget(providerId, driverId, xml, DriverState.MODIFIED, currentXml)));
                }
            }
        }
        return changes;
    }

    public static void backupDrivers(@NotNull List<Change> changes, @NotNull SettingsBackup backup) {
        for (Change change : changes) {
            if (change.isApplied() && change.target instanceof DriverTarget target) {
                backup.recordDriver(target.providerId(), target.driverId(), target.state(), target.currentXml());
            }
        }
    }

    /**
     * Merges the chosen drivers into the running registry and saves the drivers configuration.
     *
     * @return the number of drivers changed
     */
    public static int applyDrivers(@NotNull List<Change> changes) throws DBException {
        int count = 0;
        for (Change change : changes) {
            if (change.isApplied() && change.target instanceof DriverTarget target) {
                loadDriver(target.providerId(), target.driverId(), target.xml(), true);
                count++;
            }
        }
        if (count > 0) {
            DataSourceProviderRegistry.getInstance().saveDrivers();
        }
        return count;
    }

    /**
     * Gives an existing driver the settings of a {@code <driver>} element (a missing user driver is created by the parser).
     *
     * @param reset start from the defaults of a built-in driver (from nothing for a user driver), so the element replaces the settings
     */
    static void loadDriver(@NotNull String providerId, @NotNull String driverId, @NotNull String xml, boolean reset) throws DBException {
        DataSourceProviderDescriptor provider = DataSourceProviderRegistry.getInstance().getDataSourceProvider(providerId);
        if (provider == null) {
            throw new DBException("Driver provider " + providerId + " is not installed");
        }
        if (reset && provider.getDriver(driverId) instanceof DriverDescriptor driver) {
            resetDriver(driver);
        }
        String document = "<" + RegistryConstants.TAG_DRIVERS + "><" + RegistryConstants.TAG_PROVIDER + " " + RegistryConstants.ATTR_ID //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "=\"" + XMLUtils.escapeXml(providerId) + "\">" + xml + "</" + RegistryConstants.TAG_PROVIDER + "></" + RegistryConstants.TAG_DRIVERS + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        try (StringReader reader = new StringReader(document)) {
            new SAXReader(reader).parse(new DriverDescriptorSerializerLegacy.DriversParser(false));
        } catch (XMLException | IOException e) {
            throw new DBException("Cannot read the settings of driver " + driverId, e);
        }
        if (provider.getDriver(driverId) instanceof DriverDescriptor driver) {
            driver.resetDriverInstance();
        }
    }

    /**
     * A built-in driver goes back to its defaults; a user driver loses its libraries, parameters, properties and client homes.
     */
    static void resetDriver(@NotNull DriverDescriptor driver) {
        if (!driver.isCustom()) {
            driver.resetToDefaults();
            return;
        }
        driver.getDriverLibraries().clear();
        driver.setDriverParameters(Map.of());
        driver.setConnectionProperties(Map.of());
        driver.setNativeClientLocations(List.of());
        driver.resetDriverInstance();
    }

    /**
     * The drivers configuration as the registry saves it.
     */
    @NotNull
    private static Document serializeDrivers() throws DBException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            new DriverDescriptorSerializerLegacy().serializeDrivers(out, DataSourceProviderRegistry.getInstance().getDataSourceProviders());
        } catch (IOException e) {
            throw new DBException("Cannot serialize the drivers", e);
        }
        return parseXml(out.toByteArray());
    }

    /**
     * The element the serializer writes for a built-in driver at its default settings (it writes only changed drivers, so the
     * driver is marked changed while it is serialized), cleaned like an export; null if it writes none.
     */
    @Nullable
    private static String serializeDefaultDriver(@NotNull DriverDescriptor driver) throws DBException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        driver.setModified(true);
        try {
            new DriverDescriptorSerializerLegacy().serializeDrivers(out, List.of(driver.getProviderDescriptor()));
        } catch (IOException e) {
            throw new DBException("Cannot serialize driver " + driver.getId(), e);
        } finally {
            driver.setModified(false);
        }
        Document document = parseXml(out.toByteArray());
        for (Element providerElement : XMLUtils.getChildElementList(document.getDocumentElement(), RegistryConstants.TAG_PROVIDER)) {
            for (Element driverElement : XMLUtils.getChildElementList(providerElement, RegistryConstants.TAG_DRIVER)) {
                if (driver.getId().equals(driverElement.getAttribute(RegistryConstants.ATTR_ID))) {
                    stripDriver(driverElement);
                    return toXml(driverElement, false);
                }
            }
        }
        return null;
    }

    /**
     * Removes what must not leave this computer: resolved local jar files, and properties and parameters that look like secrets.
     *
     * @return the number of secret-looking values removed
     */
    private static int stripDriver(@NotNull Element driverElement) {
        int secrets = 0;
        for (Element library : XMLUtils.getChildElementList(driverElement, RegistryConstants.TAG_LIBRARY)) {
            for (Element file : XMLUtils.getChildElementList(library, RegistryConstants.TAG_FILE)) {
                library.removeChild(file);
            }
        }
        for (String tag : new String[]{RegistryConstants.TAG_PROPERTY, RegistryConstants.TAG_PARAMETER}) {
            for (Element element : XMLUtils.getChildElementList(driverElement, tag)) {
                if (isSecret(element.getAttribute(RegistryConstants.ATTR_NAME), element.getAttribute(RegistryConstants.ATTR_VALUE))) {
                    driverElement.removeChild(element);
                    secrets++;
                }
            }
        }
        removeWhitespace(driverElement);
        return secrets;
    }

    /**
     * Drops the indentation text nodes, so that the same settings give the same text.
     */
    private static void removeWhitespace(@NotNull Node node) {
        for (Node child = node.getFirstChild(); child != null; ) {
            Node next = child.getNextSibling();
            if (child.getNodeType() == Node.TEXT_NODE && child.getNodeValue().isBlank()) {
                node.removeChild(child);
            } else {
                removeWhitespace(child);
            }
            child = next;
        }
    }

    @NotNull
    static Document parseXml(@NotNull byte[] data) throws DBException {
        try {
            return XMLUtils.parseDocument(new ByteArrayInputStream(data));
        } catch (XMLException e) {
            throw new SettingsTransferException(SettingsTransferException.Reason.INVALID_CONTENT, "The drivers configuration cannot be read", e);
        }
    }

    @NotNull
    static String toXml(@NotNull Element element, boolean indent) throws DBException {
        try {
            Transformer transformer = XMLUtils.newSecureTransformerFactory().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, indent ? "no" : "yes"); //$NON-NLS-1$ //$NON-NLS-2$
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            transformer.setOutputProperty(OutputKeys.INDENT, indent ? "yes" : "no"); //$NON-NLS-1$ //$NON-NLS-2$
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(element), new StreamResult(writer));
            return writer.toString();
        } catch (XMLException | TransformerException e) {
            throw new DBException("Cannot write XML", e);
        }
    }

    //////////////////////////////////////////////////////////
    // Helpers

    @NotNull
    static JsonObject parseObject(@NotNull byte[] data, @NotNull String entry) throws DBException {
        try {
            JsonElement element = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (JsonParseException e) {
            throw invalid("Entry " + entry + " is not valid JSON", e);
        }
        throw invalid("Entry " + entry + " is not a JSON object");
    }

    @Nullable
    static JsonObject getObject(@NotNull JsonObject object, @NotNull String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    @Nullable
    static String getString(@NotNull JsonObject object, @NotNull String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    @NotNull
    private static SettingsTransferException notSettingsFile(@Nullable Throwable cause) {
        return new SettingsTransferException(SettingsTransferException.Reason.NOT_A_SETTINGS_FILE, "Not a Chromite settings file", cause);
    }

    @NotNull
    private static SettingsTransferException invalid(@NotNull String message) {
        return invalid(message, null);
    }

    @NotNull
    private static SettingsTransferException invalid(@NotNull String message, @Nullable Throwable cause) {
        return new SettingsTransferException(SettingsTransferException.Reason.INVALID_CONTENT, message, cause);
    }
}
