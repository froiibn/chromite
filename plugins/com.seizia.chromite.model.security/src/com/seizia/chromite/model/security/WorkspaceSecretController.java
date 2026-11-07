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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.exec.DBCFeatureNotSupportedException;
import com.seizia.chromite.model.impl.app.LocalSecretController;
import com.seizia.chromite.model.secret.DBSSecretController;
import com.seizia.chromite.model.secret.DBSSecretObject;
import com.seizia.chromite.model.secret.DBSSecretValue;
import org.jkiss.utils.CommonUtils;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Secret controller of the desktop workspace session (the "global" secret controller): global network profiles,
 * proxy and Maven repository credentials, and the AI keys that {@code AIUtils} has not moved yet.
 *
 * 설계:
 * <ul>
 *     <li>값은 {@link ChromiteSecureStorage}에 작업 공간 {@code .metadata} 폴더의 범위로 둔다. 예전 컨트롤러
 *     {@link LocalSecretController}는 작업 공간 설정 파일에 평문으로 썼다. 범위를 작업 공간마다 두는 것은 예전과 같이 작업 공간마다
 *     따로 저장되게 하기 위해서다.</li>
 *     <li>예전 평문 값은 처음 읽을 때(그리고 시작할 때 {@link #migrateLegacySecrets()}로 전부) 보안 저장소에 쓰고 되읽어 확인한 뒤에야
 *     지운다. 보안 저장소에 이미 값이 있으면 그 값을 쓰고 평문 사본만 지운다. 중간에 앱이 죽어도 평문이 남아 있으므로 다음 시작이
 *     이어서 옮긴다.</li>
 *     <li>보안 저장소를 쓸 수 없으면 {@link DBException}을 던진다. 평문 컨트롤러로 대신 쓰지 않는다.</li>
 * </ul>
 * 자세한 내용은 docs/chromite/credential-storage.md에 있다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public final class WorkspaceSecretController implements DBSSecretController {

    private static final Log log = Log.getLog(WorkspaceSecretController.class);

    private static final String AREA = "workspaces"; //$NON-NLS-1$
    private static final String STATE_LEGACY_MIGRATED = "legacy.secrets.migrated"; //$NON-NLS-1$

    @NotNull
    private final Path metadataFolder;
    private volatile String scope;

    /**
     * @param metadataFolder the {@code .metadata} folder of the workspace
     */
    public WorkspaceSecretController(@NotNull Path metadataFolder) {
        this.metadataFolder = metadataFolder;
    }

    @Nullable
    @Override
    public String getPrivateSecretValue(@NotNull String secretId) throws DBException {
        String value = ChromiteSecureStorage.get(getPath(secretId));
        if (value != null) {
            removeLegacySecret(secretId);
            return value;
        }
        return moveLegacySecret(secretId);
    }

    @Override
    public void setPrivateSecretValue(@NotNull String secretId, @Nullable String secretValue) throws DBException {
        ChromiteSecureStorage.put(getPath(secretId), CommonUtils.isEmpty(secretValue) ? null : secretValue);
        removeLegacySecret(secretId);
    }

    @NotNull
    @Override
    public List<DBSSecretValue> discoverCurrentUserSecrets(@NotNull DBSSecretObject secretObject) throws DBException {
        throw new DBCFeatureNotSupportedException("Secrets discovery not supported");
    }

    /**
     * Nothing to do: every write is flushed and read back.
     */
    @Override
    public void flushChanges() {
    }

    /**
     * Moves every plain text secret of the workspace settings to the secure storage. Run at startup so that values
     * that are never read (a proxy that is not used now, for example) do not stay in plain text.
     *
     * @return the number of moved secrets
     */
    public int migrateLegacySecrets() throws DBException {
        List<String> ids = LocalSecretController.INSTANCE.getSecretIds();
        int moved = 0;
        for (String secretId : ids) {
            if (ChromiteSecureStorage.get(getPath(secretId)) == null) {
                moveLegacySecret(secretId);
                moved++;
            } else {
                removeLegacySecret(secretId);
            }
        }
        if (!ids.isEmpty()) {
            ChromiteSecureStorage.setState(metadataFolder, STATE_LEGACY_MIGRATED, OffsetDateTime.now().toString());
            log.info(moved + " saved secret(s) of the workspace settings were moved to the secure storage");
        }
        return moved;
    }

    @NotNull
    private String getPath(@NotNull String secretId) throws DBException {
        if (scope == null) {
            scope = ChromiteSecureStorage.resolveScope(metadataFolder, AREA);
        }
        return scope + "/" + secretId;
    }

    /**
     * Writes a plain text secret of an earlier version to the secure storage and removes the plain copy only after
     * the secure copy was read back.
     */
    @Nullable
    private String moveLegacySecret(@NotNull String secretId) throws DBException {
        String legacyValue = LocalSecretController.INSTANCE.getPrivateSecretValue(secretId);
        if (CommonUtils.isEmpty(legacyValue)) {
            return null;
        }
        ChromiteSecureStorage.put(getPath(secretId), legacyValue);
        removeLegacySecret(secretId);
        log.debug("Secret '" + secretId + "' was moved from the workspace settings to the secure storage");
        return legacyValue;
    }

    private static void removeLegacySecret(@NotNull String secretId) throws DBException {
        if (LocalSecretController.INSTANCE.getPrivateSecretValue(secretId) != null) {
            LocalSecretController.INSTANCE.setPrivateSecretValue(secretId, null);
            LocalSecretController.INSTANCE.flushChanges();
        }
    }
}
