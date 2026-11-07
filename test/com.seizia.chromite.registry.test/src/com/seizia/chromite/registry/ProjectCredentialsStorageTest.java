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

import com.seizia.chromite.junit.ChromiteUnitTest;
import com.seizia.chromite.model.impl.app.BaseProjectImpl;
import com.seizia.chromite.model.security.ChromiteSecureStorage;
import com.seizia.chromite.runtime.DBSecurityException;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Regression tests of the move of saved passwords from the old credentials files to the OS secure storage
 * ({@link ProjectCredentialsStorage}).
 *
 * 설계: 시험 앱은 헤드리스라 OS 보안 저장소를 쓰지 않는다({@link ChromiteSecureStorage#isAvailable()}가 false). 그래서 실제 저장소에
 * 쓰고 되읽는 경로 대신, 저장소를 쓸 수 없을 때 예전 파일을 지우지 않는지(되읽기 확인 전에는 지우지 않음)와 합치기 규칙(저장소 값이 이김)을
 * 확인한다. 합치기는 저장소를 거치지 않는 private 메서드라 리플렉션으로 부른다. 저장소 잠금 파일은 {@code CHROMITE_DATA} 아래에 생기므로
 * 시험은 그 변수를 시험용 폴더로 두고 실행한다(docs/chromite/regression-tests.md "실행").
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "저장한 비밀번호")
 */
public class ProjectCredentialsStorageTest extends ChromiteUnitTest {

    private static final String FILE_NAME = "credentials-config.json";

    @TempDir
    Path projectFolder;

    @Test
    public void storedValuesWinOverTheOldFileForTheSameConnectionAndNode() throws Exception {
        Map<String, Map<String, Map<String, String>>> stored = Map.of(
            "postgres-1", Map.of("#connection", Map.of("user", "new-user", "password", "new-secret")));
        Map<String, Map<String, Map<String, String>>> legacy = new LinkedHashMap<>();
        legacy.put("postgres-1", Map.of(
            "#connection", Map.of("user", "old-user", "password", "old-secret"),
            "network/ssh_tunnel", Map.of("password", "ssh-secret")));
        legacy.put("oracle-2", Map.of("#connection", Map.of("password", "oracle-secret")));
        legacy.put("broken", null);

        Map<String, Map<String, Map<String, String>>> merged = merge(stored, legacy);

        Assertions.assertEquals(Map.of("user", "new-user", "password", "new-secret"), merged.get("postgres-1").get("#connection"));
        // Nodes and connections only the old file has are kept
        Assertions.assertEquals(Map.of("password", "ssh-secret"), merged.get("postgres-1").get("network/ssh_tunnel"));
        Assertions.assertEquals(Map.of("password", "oracle-secret"), merged.get("oracle-2").get("#connection"));
        Assertions.assertFalse(merged.containsKey("broken"));
        // Nothing stored yet: the old file is taken as it is
        Assertions.assertEquals(legacy.get("oracle-2"), merge(null, legacy).get("oracle-2"));
    }

    @Test
    public void oldFileIsKeptAndReadWhenTheSecureStorageCannotBeUsed() throws Exception {
        Assumptions.assumeFalse(ChromiteSecureStorage.isAvailable(), "The OS secure storage is available to this test application");
        Path legacyFile = writeLegacyFile("{\"postgres-1\":{\"#connection\":{\"user\":\"u\",\"password\":\"p\"}}}");

        Map<String, Map<String, Map<String, String>>> loaded = ProjectCredentialsStorage.load(projectFolder, FILE_NAME);

        Assertions.assertNotNull(loaded);
        Assertions.assertEquals("p", loaded.get("postgres-1").get("#connection").get("password"));
        // Not moved: deleting it before the stored copy was read back would lose the passwords
        Assertions.assertTrue(Files.exists(legacyFile));
    }

    @Test
    public void savingWithoutTheSecureStorageFailsAndKeepsTheOldFile() throws Exception {
        Assumptions.assumeFalse(ChromiteSecureStorage.isAvailable(), "The OS secure storage is available to this test application");
        Path legacyFile = writeLegacyFile("{}");

        Assertions.assertThrows(DBSecurityException.class, () -> ProjectCredentialsStorage.save(projectFolder, FILE_NAME,
            Map.of("postgres-1", Map.of("#connection", Map.of("password", "p")))));
        Assertions.assertTrue(Files.exists(legacyFile));

        // No passwords at all: nothing to keep, and still nothing is deleted
        ProjectCredentialsStorage.save(projectFolder, FILE_NAME, Map.of());
        Assertions.assertTrue(Files.exists(legacyFile));
    }

    @Test
    public void unreadableStorageWithoutAnOldFileIsAnError() {
        Assumptions.assumeFalse(ChromiteSecureStorage.isAvailable(), "The OS secure storage is available to this test application");
        Assertions.assertThrows(DBException.class, () -> ProjectCredentialsStorage.load(projectFolder, FILE_NAME));
    }

    @NotNull
    private Path writeLegacyFile(@NotNull String json) throws Exception {
        Path file = projectFolder.resolve(FILE_NAME);
        Files.write(file, BaseProjectImpl.getLegacyCredentialsEncryptor().encryptValue(json.getBytes(StandardCharsets.UTF_8)));
        return file;
    }

    @SuppressWarnings("unchecked")
    @NotNull
    private static Map<String, Map<String, Map<String, String>>> merge(
        Map<String, Map<String, Map<String, String>>> stored,
        @NotNull Map<String, Map<String, Map<String, String>>> legacy
    ) throws Exception {
        Method merge = ProjectCredentialsStorage.class.getDeclaredMethod("merge", Map.class, Map.class);
        merge.setAccessible(true);
        return (Map<String, Map<String, Map<String, String>>>) merge.invoke(null, stored, legacy);
    }
}
