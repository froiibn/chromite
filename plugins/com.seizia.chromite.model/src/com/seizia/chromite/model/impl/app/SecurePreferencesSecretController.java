/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.impl.app;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.equinox.security.storage.provider.IProviderHints;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.exec.DBCFeatureNotSupportedException;
import com.seizia.chromite.model.secret.*;
import com.seizia.chromite.runtime.DBSecurityException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Uses Eclipse secure preferences to read/write local secrets.
 *
 * 설계: OS 제공자(Windows DPAPI, macOS 키체인)의 실패는 unchecked 예외로도 올라온다. 이 컨트롤러의 호출자는
 * {@link DBException}만 처리하므로 값 읽기·쓰기·암호화 확인에서 그런 예외를 {@link DBSecurityException}으로 바꾼다.
 *
 * 변경 이력:
 * - 2026-11-07 제공자의 unchecked 예외를 DBSecurityException으로 바꿈. AI 키를 이 저장소로 옮긴 뒤(AIUtils) 복호화할 수 없는
 *   보안 저장소에서 Win32Exception이 AI 창을 거쳐 앱 시작을 멈췄다 (docs/chromite/ai-agent.md "엔진")
 */
public class SecurePreferencesSecretController implements DBSSecretController, DBSSecretBrowser {

    public static final String SECRET_PREFS_ROOT = "chromite";

    public static final SecurePreferencesSecretController INSTANCE = new SecurePreferencesSecretController(
        "", SECRET_PREFS_ROOT, Map.of());

    private final Path root;
    private final String nodeName;
    private final Map<String, String> options;

    private SecurePreferencesSecretController(String root, String nodeName, Map<String, String> options) {
        this.root = Path.of(root);
        this.nodeName = nodeName;
        this.options = options;
    }

    @NotNull
    public static SecurePreferencesSecretController create(@NotNull String nodeName, @Nullable String providerId) {
        return new SecurePreferencesSecretController("", nodeName, providerId == null
            ? Map.of() : Map.of(IProviderHints.REQUIRED_MODULE_ID, providerId));
    }

    @Nullable
    @Override
    public String getPrivateSecretValue(@NotNull String secretId) throws DBException {
        try {
            Path keyPath = root.resolve(escapeSecretKey(secretId));

            return getNodeByPath(keyPath.getParent())
                .get(keyPath.getFileName().toString(), null);
        } catch (StorageException e) {
            if (e.getErrorCode() == StorageException.NO_PASSWORD) {
                throw new DBSecurityException("Cannot load secure settings - master password is not provided");
            }
            throw new DBSecurityException("Error getting preference value '" + secretId + "'", e);
        } catch (RuntimeException e) {
            throw providerFailure(secretId, e);
        }
    }

    @Override
    public void setPrivateSecretValue(@NotNull String secretId, @Nullable String secretValue) throws DBException {
        try {
            Path keyPath = root.resolve(escapeSecretKey(secretId));

            ISecurePreferences node = getNodeByPath(keyPath.getParent());
            if (secretValue != null) {
                node.put(keyPath.getFileName().toString(), secretValue, true);
            } else {
                node.remove(keyPath.getFileName().toString());
            }
        } catch (StorageException e) {
            if (e.getErrorCode() == StorageException.NO_PASSWORD) {
                throw new DBSecurityException("Cannot save secure settings - password is invalid or was not provided", e);
            }
            throw new DBSecurityException("Error setting preference value '" + secretId + "'", e);
        } catch (RuntimeException e) {
            throw providerFailure(secretId, e);
        }
    }

    /**
     * An OS password provider can fail with an unchecked exception (the Windows provider throws JNA's
     * {@code Win32Exception} when DPAPI can't decrypt the master password). Callers handle {@link DBException} only,
     * so an unchecked failure would escape to whatever UI code asked for the secret.
     */
    @NotNull
    private static DBSecurityException providerFailure(@NotNull String secretId, @NotNull RuntimeException e) {
        return new DBSecurityException("Secure storage failed for '" + secretId + "': " + e.getMessage(), e);
    }

    @NotNull
    @Override
    public List<DBSSecretValue> discoverCurrentUserSecrets(@NotNull DBSSecretObject secretObject) throws DBException {
        throw new DBCFeatureNotSupportedException("Secrets discovery not supported");
    }

    @Override
    public void flushChanges() throws DBException {
        try {
            getNodeByPath(null).flush();
        } catch (IOException e) {
            throw new DBException("Error flushing secure preferences", e);
        }
    }

    private ISecurePreferences getNodeByPath(@Nullable Path path) throws DBException {
        try {
            ISecurePreferences rootNode = (options.isEmpty()
                ? SecurePreferencesFactory.getDefault()
                : SecurePreferencesFactory.open(null, options)).node(nodeName);
            if (path != null) {
                for (Path name : path) {
                    rootNode = rootNode.node(name.toString());
                }
            }
            return rootNode;
        } catch (IOException e) {
            throw new DBException("Error opening secure preferences", e);
        }
    }

    public boolean isPrivateSecretEncrypted(@NotNull String secretId) throws DBException {
        try {
            Path keyPath = root.resolve(escapeSecretKey(secretId));
            return getNodeByPath(keyPath.getParent()).isEncrypted(keyPath.getFileName().toString());
        } catch (StorageException e) {
            throw new DBSecurityException("Error checking encryption of preference value '" + secretId + "'", e);
        } catch (RuntimeException e) {
            throw providerFailure(secretId, e);
        }
    }

    @NotNull
    @Override
    public List<DBSSecret> listSecrets(@Nullable String path) throws DBException {
        Path keyPath = path == null ? root : root.resolve(escapeSecretKey(path));
        return Arrays.stream(getNodeByPath(keyPath).keys())
            .map(k -> new DBSSecret(keyPath.resolve(escapeSecretKey(k)).toString(), k))
            .collect(Collectors.toList());
    }

    @Nullable
    @Override
    public DBSSecret getSecret(@NotNull String secretId) throws DBException {
        try {
            Path keyPath = root.resolve(escapeSecretKey(secretId));
            String keyId = keyPath.getFileName().toString();
            String value = getNodeByPath(keyPath.getParent()).get(keyId, null);
            if (value == null) {
                return null;
            }
            return new DBSSecret(keyPath.toString(), keyId);
        } catch (StorageException e) {
            throw new DBSecurityException("Error getting secret info '" + secretId + "'", e);
        }
    }

    @Override
    public void deleteSecret(@NotNull String secretId) throws DBException {
        throw new DBCFeatureNotSupportedException();
    }

    @Override
    public void clearAllSecrets(String keyPrefix) throws DBException {
        getNodeByPath(root.resolve(escapeSecretKey(keyPrefix))).removeNode();
    }

    private String escapeSecretKey(String key) {
        // Replace : with _ because Windows do not support : in path names
        return key.replace(':', '_');
    }

}
