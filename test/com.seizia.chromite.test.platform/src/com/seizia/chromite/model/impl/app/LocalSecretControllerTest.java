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

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.junit.ChromiteUnitTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LocalSecretControllerTest extends ChromiteUnitTest {

    @Test
    public void testDiagnosticPreferenceExportExcludesLocalSecrets() throws Exception {
        String id = UUID.randomUUID().toString();
        String secretId = "diagnostic-test-" + id;
        String secretValue = "synthetic-secret-" + id;
        String ordinaryKey = "diagnostic-test-ordinary-" + id;
        String ordinaryValue = "ordinary-value-" + id;
        String similarKey = "secrets-other-" + id;
        var preferences = InstanceScope.INSTANCE.getNode(ModelPreferences.getMainBundle().getSymbolicName());

        try {
            LocalSecretController.INSTANCE.setPrivateSecretValue(secretId, secretValue);
            preferences.put(ordinaryKey, ordinaryValue);
            preferences.put(similarKey, ordinaryValue);

            var output = new ByteArrayOutputStream();
            Platform.getPreferencesService().exportPreferences(preferences, output, null);
            String exported = output.toString(StandardCharsets.ISO_8859_1);
            assertTrue(exported.contains(secretValue));

            String filtered = LocalSecretController.getDiagnosticSecretPreferencePattern().matcher(exported).replaceAll("");
            assertFalse(filtered.contains(secretValue));
            assertFalse(filtered.contains("//secrets/" + secretId));
            assertTrue(filtered.contains(ordinaryKey));
            assertTrue(filtered.contains(ordinaryValue));
            assertTrue(filtered.contains(similarKey));
        } finally {
            LocalSecretController.INSTANCE.setPrivateSecretValue(secretId, null);
            preferences.remove(ordinaryKey);
            preferences.remove(similarKey);
        }
    }
}
