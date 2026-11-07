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

import com.google.gson.reflect.TypeToken;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.impl.app.BaseProjectImpl;
import com.seizia.chromite.model.security.ChromiteSecureStorage;
import com.seizia.chromite.runtime.DBSecurityException;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Connection credentials of a desktop project in the OS secure storage, in place of the {@code credentials-config*.json}
 * files that earlier versions encrypted with the key fixed in the source.
 *
 * 설계:
 * <ul>
 *     <li>저장 형식은 예전 파일 내용과 같은 JSON(데이터 소스 ID → 노드({@code #connection}, {@code network/...}) → 속성)이다. 파일 하나가
 *     값 하나가 된다: {@code projects/<프로젝트 메타데이터 폴더의 범위>/credentials-config<부 ID>.json}. 직렬화기와 연결 객체의 동작
 *     (비밀번호 저장 여부, 공유 자격 증명, 네트워크 처리기·인증 프로필·프로젝트 네트워크 프로필의 비밀)은 바뀌지 않는다. CloudBeaver용
 *     비밀 저장소 경로({@code isUseSecretStorage})를 켜지 않은 이유는 docs/chromite/credential-storage.md "설계 결정"에 있다.</li>
 *     <li>예전 파일이 있으면 보안 저장소 값과 합치고(같은 데이터 소스·노드는 보안 저장소 값이 이김), 합친 값을 쓰고 되읽어 확인한 뒤에야
 *     파일과 그 백업({@code .credentials-config.json.bak})을 지운다. 앱이 중간에 죽으면 파일이 남아 있어 다음 시작이 같은 일을 다시
 *     한다(같은 결과). 시작할 때 모든 프로젝트에 한 번({@link #migrateLegacyFiles(Path)}), 레지스트리가 읽을 때도 한다.</li>
 *     <li>보안 저장소를 읽지 못하면(저장소는 있는데 복호화 실패 등) 그 파일을 "읽지 못함"으로 표시하고 저장을 거부한다. 메모리에
 *     비밀번호가 없는 상태로 저장하면 저장소의 비밀번호를 지우기 때문이다. 예전 파일이 남아 있으면 그 파일로 읽기만 한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public final class ProjectCredentialsStorage {

    private static final Log log = Log.getLog(ProjectCredentialsStorage.class);

    private static final String AREA = "projects"; //$NON-NLS-1$
    private static final String STATE_MIGRATED_PREFIX = "legacy.migrated."; //$NON-NLS-1$
    private static final String BACKUP_PREFIX = "."; //$NON-NLS-1$
    // ContentUtils.makeFileBackup naming
    private static final String BACKUP_SUFFIX = ".bak"; //$NON-NLS-1$
    private static final Type CREDENTIALS_TYPE = new TypeToken<Map<String, Map<String, Map<String, String>>>>() {
    }.getType();

    // Credential files whose stored value could not be read in this session (folder + file name)
    private static final Set<String> unreadable = ConcurrentHashMap.newKeySet();

    private ProjectCredentialsStorage() {
    }

    /**
     * Reads the credentials of one configuration storage and moves an old credentials file into the secure storage.
     *
     * @param metadataFolder project metadata folder
     * @param fileName       {@code credentials-config<sub id>.json}
     * @return the credentials, or {@code null} when there are none
     * @throws DBException if the secure storage cannot be read and there is no old file to read instead
     */
    @Nullable
    public static synchronized Map<String, Map<String, Map<String, String>>> load(
        @NotNull Path metadataFolder,
        @NotNull String fileName
    ) throws DBException {
        Path legacyFile = metadataFolder.resolve(fileName);
        Map<String, Map<String, Map<String, String>>> stored;
        try {
            stored = parse(ChromiteSecureStorage.get(getPath(metadataFolder, fileName)));
            unreadable.remove(getId(metadataFolder, fileName));
        } catch (DBException e) {
            if (ChromiteSecureStorage.isAvailable()) {
                unreadable.add(getId(metadataFolder, fileName));
            }
            Map<String, Map<String, Map<String, String>>> legacy = Files.exists(legacyFile) ? readLegacyFile(legacyFile) : null;
            if (legacy != null) {
                log.error("Saved passwords of " + metadataFolder + " were read from the old file " + fileName
                    + " because the secure storage failed; they are not saved until it works", e);
                return legacy;
            }
            throw new DBException("Saved passwords of " + metadataFolder + " can't be read: " + e.getMessage(), e);
        }
        if (!Files.exists(legacyFile)) {
            return stored;
        }
        Map<String, Map<String, Map<String, String>>> legacy = readLegacyFile(legacyFile);
        if (legacy == null) {
            // Damaged file: keep it, the stored values are used
            return stored;
        }
        Map<String, Map<String, Map<String, String>>> merged = merge(stored, legacy);
        try {
            write(metadataFolder, fileName, merged);
            deleteLegacyFiles(metadataFolder, fileName);
            ChromiteSecureStorage.setState(metadataFolder, STATE_MIGRATED_PREFIX + fileName, OffsetDateTime.now().toString());
            log.info("Saved passwords of " + metadataFolder + " (" + fileName + ") were moved to the secure storage");
        } catch (DBException e) {
            log.error("Saved passwords of " + metadataFolder + " were not moved to the secure storage; the old file is kept", e);
        }
        return merged;
    }

    /**
     * Saves the credentials of one configuration storage (no credentials remove the stored value) and deletes an old
     * credentials file, whose content {@link #load} merged into the saved credentials.
     *
     * @throws DBException if the secure storage is not available, failed, or could not be read in this session
     */
    public static synchronized void save(
        @NotNull Path metadataFolder,
        @NotNull String fileName,
        @NotNull Map<String, Map<String, Map<String, String>>> credentials
    ) throws DBException {
        if (unreadable.contains(getId(metadataFolder, fileName))) {
            throw new DBSecurityException("Saved passwords were not saved: they could not be read from the secure storage "
                + ChromiteSecureStorage.getStorageFile() + " when this project was opened, and saving now would overwrite them. "
                + "Restart Chromite; if the storage file came from another computer or account, close Chromite and delete it.");
        }
        if (credentials.isEmpty() && !ChromiteSecureStorage.isAvailable()) {
            // Nothing to keep (headless application without passwords)
            return;
        }
        try {
            write(metadataFolder, fileName, credentials);
        } catch (DBException e) {
            throw new DBSecurityException("Saved passwords were not saved: " + e.getMessage()
                + " Passwords are never saved in a file instead.", e);
        }
        deleteLegacyFiles(metadataFolder, fileName);
    }

    /**
     * Moves every old credentials file of a project folder to the secure storage. Never throws.
     */
    public static void migrateLegacyFiles(@NotNull Path metadataFolder) {
        if (!Files.isDirectory(metadataFolder)) {
            return;
        }
        List<String> names;
        try (Stream<Path> list = Files.list(metadataFolder)) {
            names = list.map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith(DBPDataSourceRegistry.CREDENTIALS_CONFIG_FILE_PREFIX)
                    && name.endsWith(DBPDataSourceRegistry.CREDENTIALS_CONFIG_FILE_EXT))
                .toList();
        } catch (IOException e) {
            log.error("Error listing " + metadataFolder, e);
            return;
        }
        for (String name : names) {
            try {
                load(metadataFolder, name);
            } catch (Throwable e) {
                log.error("Saved passwords of " + metadataFolder + " (" + name + ") were not moved to the secure storage", e);
            }
        }
    }

    /**
     * Removes all stored credentials of a project folder (the project was deleted with its contents).
     *
     * @param scope the value of {@link #getScope(Path)} taken while the folder still existed
     */
    public static void removeScope(@NotNull String scope) throws DBException {
        ChromiteSecureStorage.removeNode(scope);
    }

    /**
     * The secure storage scope of a project folder, for {@link #removeScope(String)}.
     */
    @NotNull
    public static String getScope(@NotNull Path metadataFolder) {
        return ChromiteSecureStorage.getScope(metadataFolder, AREA);
    }

    private static void write(
        @NotNull Path metadataFolder,
        @NotNull String fileName,
        @NotNull Map<String, Map<String, Map<String, String>>> credentials
    ) throws DBException {
        ChromiteSecureStorage.put(
            getPath(metadataFolder, fileName),
            credentials.isEmpty() ? null : DataSourceSerializerModern.CONFIG_GSON.toJson(credentials, CREDENTIALS_TYPE));
    }

    @NotNull
    private static String getPath(@NotNull Path metadataFolder, @NotNull String fileName) throws DBException {
        return ChromiteSecureStorage.resolveScope(metadataFolder, AREA) + "/" + fileName;
    }

    @NotNull
    private static String getId(@NotNull Path metadataFolder, @NotNull String fileName) {
        return metadataFolder.toAbsolutePath().normalize().resolve(fileName).toString();
    }

    @Nullable
    private static Map<String, Map<String, Map<String, String>>> parse(@Nullable String json) {
        return json == null ? null : DataSourceSerializerModern.CONFIG_GSON.fromJson(json, CREDENTIALS_TYPE);
    }

    /**
     * Reads a file written with the key fixed in the source, or {@code null} when it cannot be read.
     */
    @Nullable
    private static Map<String, Map<String, Map<String, String>>> readLegacyFile(@NotNull Path file) {
        try {
            byte[] data = Files.readAllBytes(file);
            if (data.length == 0) {
                return Map.of();
            }
            byte[] decrypted = BaseProjectImpl.getLegacyCredentialsEncryptor().decryptValue(data);
            Map<String, Map<String, Map<String, String>>> credentials = parse(new String(decrypted, StandardCharsets.UTF_8));
            return credentials == null ? Map.of() : credentials;
        } catch (Exception e) {
            log.error("Old saved passwords file " + file + " can't be read; it is kept", e);
            return null;
        }
    }

    /**
     * Credentials of both, the stored ones win for the same data source and node: an old file that appears again (an
     * earlier version wrote it, or the rename migration copied it) must not undo changes made after the move.
     */
    @NotNull
    private static Map<String, Map<String, Map<String, String>>> merge(
        @Nullable Map<String, Map<String, Map<String, String>>> stored,
        @NotNull Map<String, Map<String, Map<String, String>>> legacy
    ) {
        Map<String, Map<String, Map<String, String>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Map<String, String>>> entry : legacy.entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
            }
        }
        if (stored != null) {
            for (Map.Entry<String, Map<String, Map<String, String>>> entry : stored.entrySet()) {
                if (entry.getValue() != null) {
                    result.computeIfAbsent(entry.getKey(), k -> new LinkedHashMap<>()).putAll(entry.getValue());
                }
            }
        }
        return result;
    }

    private static void deleteLegacyFiles(@NotNull Path metadataFolder, @NotNull String fileName) {
        for (Path file : List.of(
            metadataFolder.resolve(fileName),
            metadataFolder.resolve(BACKUP_PREFIX + fileName + BACKUP_SUFFIX)
        )) {
            try {
                if (Files.deleteIfExists(file)) {
                    log.debug("Deleted old saved passwords file " + file);
                }
            } catch (IOException e) {
                log.error("Error deleting old saved passwords file " + file, e);
            }
        }
    }
}
