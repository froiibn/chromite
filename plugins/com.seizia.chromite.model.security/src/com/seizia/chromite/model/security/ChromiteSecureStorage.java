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
package com.seizia.chromite.model.security;

import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.internal.security.storage.SecurePreferencesMapper;
import org.eclipse.equinox.internal.security.storage.SecurePreferencesRoot;
import org.eclipse.equinox.internal.security.storage.SecurePreferencesWrapper;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.equinox.security.storage.provider.IProviderHints;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.impl.app.BaseApplicationImpl;
import com.seizia.chromite.runtime.DBSecurityException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * The OS-backed secure storage of saved passwords: connection credentials of the projects and the secrets of the
 * workspace (global network profiles, proxy, Maven repositories).
 *
 * 설계:
 * <ul>
 *     <li>Eclipse secure storage에 OS 제공자({@link DBSecurityUtils#findNativeOSProvider()})를 필수 모듈로 지정해 연다.
 *     Windows는 DPAPI가 마스터 비밀번호를 보호하고, macOS는 키체인이 보호한다. 제공자가 없거나 헤드리스 앱이면 쓰지도 읽지도 않고
 *     {@link DBSecurityException}을 던진다. 평문이나 소스에 있는 고정 키로 대신 저장하지 않는다.</li>
 *     <li>저장 파일은 Eclipse 기본 위치({@code ~/.eclipse/org.eclipse.equinox.security/secure_storage})가 아니라 Chromite 데이터 폴더의
 *     {@code secure/secure_storage}다. Equinox는 파일을 처음 열 때 메모리로 읽고 flush 때 파일 전체를 메모리 내용으로 덮어쓴다
 *     (다른 프로세스가 바꿨으면 UI 콜백에 묻는데 Chromite에는 그 콜백이 없어 그냥 덮어쓴다). 기본 파일을 쓰면 같은 사용자의 Eclipse IDE나
 *     다른 Eclipse 기반 앱이 자기 사본으로 덮어써 연결 비밀번호가 사라질 수 있다. {@code -eclipse.keyring}이나 {@code ECLIPSE_KEYRING}을
 *     주면 Equinox처럼 그 파일을 쓴다(시험에서 실제 사용자 저장소를 건드리지 않기 위해).</li>
 *     <li>Chromite 창 여러 개(다른 작업 공간)는 같은 파일을 쓴다. 그래서 읽고 쓰는 동안 옆 파일 {@code secure_storage.lock}을 잠그고,
 *     파일이 마지막으로 읽은 뒤 바뀌었으면 다시 읽은 뒤 바꾼다. 쓴 뒤에는 flush하고 파일을 다시 읽어 값을 비교한다(되읽기 확인).</li>
 *     <li>범위(scope): 프로젝트 메타데이터 폴더와 작업 공간 {@code .metadata} 폴더마다 경로에서 만든 키 아래에 둔다. OS 저장소는 OS
 *     사용자 단위라 작업 공간을 복사한 두 사본이 같은 ID를 가져도 서로 덮어쓰지 않게 하기 위해서다. 폴더에는 그 키를 적은 상태 파일
 *     ({@value #STATE_FILE})을 두고, 폴더가 옮겨지거나 복사돼 키가 달라지면 이전 키의 값을 새 키로 복사한다(원래 것은 그대로 둔다:
 *     복사인지 이동인지 알 수 없다). 상태 파일에는 비밀이 없다.</li>
 *     <li>Equinox의 내부 API({@code SecurePreferencesMapper.close})로 캐시된 파일 내용을 버린다. 공개 API에는 파일을 다시 읽는 방법이
 *     없다. 이 파일은 이 클래스만 쓰므로 다른 코드가 버려진 내용을 붙잡고 있지 않다.</li>
 * </ul>
 * 자세한 내용은 docs/chromite/credential-storage.md에 있다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 연결 비밀번호와 작업 공간 비밀을 OS 보안 저장소로 옮김
 */
public final class ChromiteSecureStorage {

    private static final Log log = Log.getLog(ChromiteSecureStorage.class);

    /**
     * Scope state file (no secrets): the scope key the folder used last, and the finished legacy migrations.
     */
    public static final String STATE_FILE = "credentials-storage.properties"; //$NON-NLS-1$
    private static final String STATE_SCOPE = "scope"; //$NON-NLS-1$

    private static final String ROOT_NODE = "chromite"; //$NON-NLS-1$
    private static final String STORAGE_FILE = "secure_storage"; //$NON-NLS-1$
    private static final String LOCK_FILE_SUFFIX = ".lock"; //$NON-NLS-1$
    // The same overrides as SecurePreferencesMapper
    private static final String KEYRING_ARGUMENT = "-eclipse.keyring"; //$NON-NLS-1$
    private static final String KEYRING_ENVIRONMENT = "ECLIPSE_KEYRING"; //$NON-NLS-1$
    private static final String PATH_SEPARATOR = "/"; //$NON-NLS-1$
    private static final int SCOPE_KEY_LENGTH = 32;

    // Modification time of the storage file when this JVM last read or wrote it
    private static long loadedModified = Long.MIN_VALUE;

    private ChromiteSecureStorage() {
    }

    /**
     * Whether this application can use the OS secure storage (an OS provider exists and the application is not headless).
     */
    public static boolean isAvailable() {
        try {
            getProviderId();
            return true;
        } catch (DBException e) {
            return false;
        }
    }

    /**
     * The storage file: {@code -eclipse.keyring} or {@code ECLIPSE_KEYRING} when given, else {@code secure/secure_storage}
     * in the global data folder.
     */
    @NotNull
    public static Path getStorageFile() {
        String keyring = null;
        String[] args = Platform.getCommandLineArgs();
        if (args != null) {
            for (int i = 0; i < args.length - 1; i++) {
                if (KEYRING_ARGUMENT.equalsIgnoreCase(args[i])) {
                    keyring = args[i + 1];
                }
            }
        }
        if (CommonUtils.isEmpty(keyring)) {
            keyring = System.getenv(KEYRING_ENVIRONMENT);
        }
        if (!CommonUtils.isEmpty(keyring)) {
            return Path.of(keyring).toAbsolutePath();
        }
        return DBWorkbench.getPlatform().getApplication().getGlobalDataPath()
            .resolve(BaseApplicationImpl.SECURE_DATA_FOLDER)
            .resolve(STORAGE_FILE);
    }

    /**
     * Reads a value.
     *
     * @param path node names and the key, separated by {@code /}
     * @throws DBException if the secure storage is not available or cannot decrypt the value
     */
    @Nullable
    public static synchronized String get(@NotNull String path) throws DBException {
        try (FileLock ignored = lockStorage()) {
            ISecurePreferences root = openRoot(false);
            String[] names = splitPath(path);
            ISecurePreferences node = findNode(root, names);
            return node == null ? null : node.get(names[names.length - 1], null);
        } catch (StorageException e) {
            throw storageFailure(path, e);
        } catch (IOException e) {
            throw new DBSecurityException("Cannot open the secure storage " + getStorageFile() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw storageFailure(path, e);
        }
    }

    /**
     * Writes (or with {@code null} removes) a value, then reads the storage file again and compares.
     *
     * @throws DBException if the secure storage is not available, did not encrypt the value or did not keep it;
     *                     the value is then not in the storage
     */
    public static synchronized void put(@NotNull String path, @Nullable String value) throws DBException {
        String[] names = splitPath(path);
        String key = names[names.length - 1];
        try (FileLock ignored = lockStorage()) {
            ISecurePreferences root = openRoot(false);
            if (value == null) {
                ISecurePreferences node = findNode(root, names);
                if (node == null || node.get(key, null) == null) {
                    return;
                }
                node.remove(key);
            } else {
                ISecurePreferences node = getNode(root, names);
                node.put(key, value, true);
                if (!node.isEncrypted(key)) {
                    // Equinox keeps the value unencrypted when the provider cannot supply a password
                    node.remove(key);
                    flush(root);
                    throw new DBSecurityException("The secure storage of the operating system did not encrypt the value");
                }
            }
            flush(root);
            // Read back what is on disk, not the cached copy
            ISecurePreferences reread = openRoot(true);
            ISecurePreferences node = findNode(reread, names);
            String stored = node == null ? null : node.get(key, null);
            if (!Objects.equals(stored, value)) {
                throw new DBSecurityException("The secure storage " + getStorageFile() + " did not keep the value of '" + path + "'");
            }
        } catch (StorageException e) {
            throw storageFailure(path, e);
        } catch (IOException e) {
            throw new DBSecurityException("Cannot write the secure storage " + getStorageFile() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw storageFailure(path, e);
        }
    }

    /**
     * Removes a node with all its values (nothing when it does not exist).
     */
    public static synchronized void removeNode(@NotNull String path) throws DBException {
        String[] names = splitPath(path);
        try (FileLock ignored = lockStorage()) {
            ISecurePreferences root = openRoot(false);
            ISecurePreferences parent = findNode(root, names);
            if (parent == null || !parent.nodeExists(names[names.length - 1])) {
                return;
            }
            parent.node(names[names.length - 1]).removeNode();
            flush(root);
        } catch (IOException e) {
            throw new DBSecurityException("Cannot write the secure storage " + getStorageFile() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw storageFailure(path, e);
        }
    }

    /**
     * The scope node of a folder without side effects (for a folder that is about to be deleted).
     */
    @NotNull
    public static String getScope(@NotNull Path folder, @NotNull String area) {
        return area + PATH_SEPARATOR + getScopeKey(folder);
    }

    /**
     * The scope node of a folder ({@code <area>/<key>}). When the folder was moved or copied (its state file names another
     * key) and the new key has no values yet, the values of the previous key are copied first. The previous values stay:
     * the original folder may still exist.
     */
    @NotNull
    public static synchronized String resolveScope(@NotNull Path folder, @NotNull String area) throws DBException {
        String key = getScopeKey(folder);
        Properties state = readState(folder);
        String previous = state.getProperty(STATE_SCOPE);
        if (previous != null && !previous.equals(key)) {
            copyScope(area, previous, key, folder);
        }
        if (!key.equals(previous) && Files.isDirectory(folder)) {
            state.setProperty(STATE_SCOPE, key);
            writeState(folder, state);
        }
        return area + PATH_SEPARATOR + key;
    }

    /**
     * Reads a property of the state file of a folder.
     */
    @Nullable
    public static synchronized String getState(@NotNull Path folder, @NotNull String name) {
        return readState(folder).getProperty(name);
    }

    /**
     * Writes a property of the state file of a folder (the folder must exist).
     */
    public static synchronized void setState(@NotNull Path folder, @NotNull String name, @NotNull String value) {
        Properties state = readState(folder);
        state.setProperty(name, value);
        writeState(folder, state);
    }

    @NotNull
    private static String getProviderId() throws DBException {
        if (!DBWorkbench.isPlatformStarted() || DBWorkbench.getPlatform().getApplication().isHeadlessMode()) {
            throw new DBSecurityException("The secure storage of the operating system is not available in a headless application. "
                + "Saved passwords are never kept in a file instead.");
        }
        String providerId = DBSecurityUtils.findNativeOSProvider();
        if (providerId == null) {
            throw new DBSecurityException("The secure storage of the operating system is not available. "
                + "Saved passwords are never kept in a file instead.");
        }
        return providerId;
    }

    /**
     * Opens the storage. A copy that this JVM read before is dropped when the file changed since then (another Chromite
     * window wrote it), or always with {@code reload}.
     */
    @NotNull
    private static ISecurePreferences openRoot(boolean reload) throws DBException, IOException {
        Map<Object, Object> options = Map.of(IProviderHints.REQUIRED_MODULE_ID, getProviderId());
        Path file = getStorageFile();
        URL location = file.toUri().toURL();
        ISecurePreferences root = SecurePreferencesFactory.open(location, options);
        long modified = getModified(file);
        if (root instanceof SecurePreferencesWrapper wrapper) {
            SecurePreferencesRoot data = wrapper.getContainer().getRootData();
            if ((reload || modified != loadedModified) && !data.isModified()) {
                SecurePreferencesMapper.close(data);
                root = SecurePreferencesFactory.open(location, options);
            }
        }
        if (root == null) {
            throw new IOException("Secure storage " + file + " cannot be opened");
        }
        loadedModified = modified;
        return root.node(ROOT_NODE);
    }

    private static void flush(@NotNull ISecurePreferences node) throws IOException {
        node.flush();
        loadedModified = getModified(getStorageFile());
    }

    /**
     * Locks the storage against other Chromite processes. The JVM's own threads are serialized by the class lock.
     */
    @NotNull
    private static FileLock lockStorage() throws IOException {
        Path file = getStorageFile();
        Files.createDirectories(file.getParent());
        Path lockFile = file.resolveSibling(file.getFileName() + LOCK_FILE_SUFFIX);
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock();
            return new ChannelLock(channel, lock);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static long getModified(@NotNull Path file) {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void copyScope(@NotNull String area, @NotNull String from, @NotNull String to, @NotNull Path folder) throws DBException {
        try (FileLock ignored = lockStorage()) {
            ISecurePreferences root = openRoot(false);
            String fromPath = area + PATH_SEPARATOR + from;
            String toPath = area + PATH_SEPARATOR + to;
            if (!root.nodeExists(fromPath) || root.nodeExists(toPath)) {
                return;
            }
            copyNode(root.node(fromPath), root.node(toPath));
            flush(root);
            log.info("Saved passwords of " + folder + " were copied from its previous location in the secure storage");
        } catch (StorageException e) {
            throw storageFailure(area, e);
        } catch (IOException e) {
            throw new DBSecurityException("Cannot write the secure storage " + getStorageFile() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw storageFailure(area, e);
        }
    }

    private static void copyNode(@NotNull ISecurePreferences source, @NotNull ISecurePreferences target) throws StorageException {
        for (String key : source.keys()) {
            String value = source.get(key, null);
            if (value != null) {
                target.put(key, value, true);
            }
        }
        for (String child : source.childrenNames()) {
            copyNode(source.node(child), target.node(child));
        }
    }

    @NotNull
    private static String getScopeKey(@NotNull Path folder) {
        Path path;
        try {
            path = folder.toRealPath();
        } catch (IOException e) {
            path = folder.toAbsolutePath().normalize();
        }
        String name = path.toString();
        if (RuntimeUtils.isWindows()) {
            // Windows paths are case-insensitive
            name = name.toLowerCase(Locale.ROOT);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            return HexFormat.of().formatHex(digest).substring(0, SCOPE_KEY_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @NotNull
    private static Properties readState(@NotNull Path folder) {
        Properties state = new Properties();
        Path file = folder.resolve(STATE_FILE);
        if (Files.isRegularFile(file)) {
            try (InputStream is = Files.newInputStream(file)) {
                state.load(is);
            } catch (IOException e) {
                log.error("Error reading " + file, e);
            }
        }
        return state;
    }

    private static void writeState(@NotNull Path folder, @NotNull Properties state) {
        Path file = folder.resolve(STATE_FILE);
        try (OutputStream os = Files.newOutputStream(file)) {
            state.store(os, "Chromite saved passwords: scope in the secure storage (no secrets)"); //$NON-NLS-1$
        } catch (IOException e) {
            log.error("Error writing " + file, e);
        }
    }

    @NotNull
    private static String[] splitPath(@NotNull String path) {
        List<String> names = new ArrayList<>();
        for (String name : path.split(PATH_SEPARATOR)) {
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("Empty secure storage path");
        }
        return names.toArray(String[]::new);
    }

    /**
     * The parent node of the key at the end of {@code names}, or {@code null} when it does not exist.
     */
    @Nullable
    private static ISecurePreferences findNode(@NotNull ISecurePreferences root, @NotNull String[] names) {
        ISecurePreferences node = root;
        for (int i = 0; i < names.length - 1; i++) {
            if (!node.nodeExists(names[i])) {
                return null;
            }
            node = node.node(names[i]);
        }
        return node;
    }

    @NotNull
    private static ISecurePreferences getNode(@NotNull ISecurePreferences root, @NotNull String[] names) {
        ISecurePreferences node = root;
        for (int i = 0; i < names.length - 1; i++) {
            node = node.node(names[i]);
        }
        return node;
    }

    /**
     * The OS provider can fail with a checked {@link StorageException} or an unchecked exception (the Windows provider
     * throws JNA's {@code Win32Exception} when DPAPI can't decrypt the master password, for example a storage file copied
     * from another computer or account).
     */
    @NotNull
    private static DBSecurityException storageFailure(@NotNull String path, @NotNull Exception e) {
        if (e instanceof StorageException se && se.getErrorCode() == StorageException.NO_PASSWORD) {
            return new DBSecurityException("The secure storage " + getStorageFile() + " is locked: its password was not provided", e);
        }
        return new DBSecurityException("The secure storage " + getStorageFile() + " failed for '" + path + "': " + e.getMessage(), e);
    }

    /**
     * Releases the file lock and closes its channel.
     */
    private static final class ChannelLock extends FileLock {
        private final FileLock lock;

        private ChannelLock(@NotNull FileChannel channel, @NotNull FileLock lock) {
            super(channel, lock.position(), lock.size(), lock.isShared());
            this.lock = lock;
        }

        @Override
        public boolean isValid() {
            return lock.isValid();
        }

        @Override
        public void release() throws IOException {
            try {
                lock.release();
            } finally {
                channel().close();
            }
        }
    }
}
