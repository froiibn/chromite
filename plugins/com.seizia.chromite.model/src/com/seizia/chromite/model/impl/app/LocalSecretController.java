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

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.exec.DBCFeatureNotSupportedException;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.secret.DBSSecretController;
import com.seizia.chromite.model.secret.DBSSecretObject;
import com.seizia.chromite.model.secret.DBSSecretValue;
import org.jkiss.utils.CommonUtils;
import org.osgi.service.prefs.BackingStoreException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Default local secret controller.
 * <p>
 * Uses Eclipse preferences to read/write secrets. Doesn't encrypt values.
 *
 * 설계: Chromite 데스크톱은 이 컨트롤러에 더 쓰지 않는다. 작업 공간 세션의 비밀 컨트롤러는 OS 보안 저장소를 쓰는
 * {@code WorkspaceSecretController}이고, 이 컨트롤러는 예전 버전이 평문으로 남긴 값을 읽고 지우는 데만 쓴다
 * (docs/chromite/credential-storage.md).
 *
 * 변경 이력:
 * - 2026-11-07 저장된 비밀 ID 목록({@link #getSecretIds()})을 더함: 시작할 때 평문 값을 모두 보안 저장소로 옮기기 위해
 */
public class LocalSecretController implements DBSSecretController {
    public static final LocalSecretController INSTANCE = new LocalSecretController();
    private static final String SECRET_PREFERENCE_PREFIX = "secrets/";

    private final DBPPreferenceStore preferences = ModelPreferences.getPreferences();

    @Nullable
    @Override
    public String getPrivateSecretValue(@NotNull String secretId) {
        String value = preferences.getString(makeKey(secretId));
        if (CommonUtils.isEmpty(value)) {
            return null;
        }
        return value;
    }

    @Override
    public void setPrivateSecretValue(@NotNull String secretId, @Nullable String secretValue) throws DBException {
        String key = makeKey(secretId);
        if (secretValue != null) {
            preferences.setValue(key, secretValue);
        } else {
            preferences.setToDefault(key);
        }
    }

    @NotNull
    @Override
    public List<DBSSecretValue> discoverCurrentUserSecrets(@NotNull DBSSecretObject secretObject) throws DBException {
        throw new DBCFeatureNotSupportedException("Secrets discovery not supported");
    }

    @Override
    public void flushChanges() throws DBException {
        try {
            preferences.save();
        } catch (IOException e) {
            throw new DBException("Error flushing secure preferences", e);
        }
    }

    /**
     * IDs of the secrets that are stored in the workspace settings, for the move to the secure storage
     * ({@code WorkspaceSecretController}).
     */
    @NotNull
    public List<String> getSecretIds() throws DBException {
        // DBPPreferenceStore cannot list keys: read the node of the main bundle, as the preference store does
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(ModelPreferences.getMainBundle().getSymbolicName());
        List<String> ids = new ArrayList<>();
        try {
            for (String key : node.keys()) {
                if (key.startsWith(SECRET_PREFERENCE_PREFIX) && key.length() > SECRET_PREFERENCE_PREFIX.length()) {
                    ids.add(key.substring(SECRET_PREFERENCE_PREFIX.length()));
                }
            }
        } catch (BackingStoreException e) {
            throw new DBException("Error reading the workspace settings", e);
        }
        return ids;
    }

    @NotNull
    public static Pattern getDiagnosticSecretPreferencePattern() {
        // Eclipse exports keys containing a slash after a double slash following the bundle node.
        String prefix = "/instance/" + ModelPreferences.getMainBundle().getSymbolicName() + "//" + SECRET_PREFERENCE_PREFIX;
        return Pattern.compile("(?m)^" + Pattern.quote(prefix) + "[^\r\n]*(?:\r?\n|$)");
    }

    @NotNull
    private static String makeKey(@NotNull String secretId) {
        return SECRET_PREFERENCE_PREFIX + secretId;
    }
}
