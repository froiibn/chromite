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
package com.seizia.chromite.model.datadam;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.ai.AIConfigurationProfile;
import com.seizia.chromite.model.ai.engine.openai.OpenAIClientResponses;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.datadam.ai.DDAIEngine;
import com.seizia.chromite.model.datadam.ai.DDAIEngineProperties;
import com.seizia.chromite.model.impl.app.SecurePreferencesSecretController;
import com.seizia.chromite.model.secret.DBSSecretController;
import com.seizia.chromite.model.security.DBSecurityProviders;
import com.seizia.chromite.model.security.DBSecurityUtils;
import com.seizia.chromite.runtime.DBWorkbench;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Arrays;
import java.util.Map;

/**
 * DataDam AI engine: token requirement, custom headers and secrets.
 *
 * 변경 이력:
 * - 2026-11-07 AI 비밀이 OS 보안 저장소로 옮겨져 비밀 테스트의 저장소 목(mock)을 보안 저장소 컨트롤러로 바꿈
 */
class DataDamAIEngineTest {
    @Test
    void dataDamShouldRequireToken() {
        DDAIEngineProperties properties = new DDAIEngineProperties();
        for (String baseUrl : Arrays.asList(null, DDAIEngineProperties.DEFAULT_ENDPOINT, "http://localhost:8000/v1/")) {
            properties.setBaseUrl(baseUrl);
            Assertions.assertTrue(properties.isTokenRequired());
            for (String token : Arrays.asList(null, "", "  ")) {
                properties.setToken(token);
                Assertions.assertFalse(properties.isValidConfiguration());
                Assertions.assertThrows(DBException.class, () -> new TestDataDamEngine(properties).createClient());
            }
            properties.setToken("secret");
            Assertions.assertTrue(properties.isValidConfiguration());
        }
    }

    @Test
    void dataDamClientsShouldApplyCustomHeaders() throws DBException {
        DDAIEngineProperties properties = new DDAIEngineProperties();
        properties.setToken("secret");
        properties.setCustomHeaders(Map.of("X-Api-Key", "custom-secret"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl())).GET().build();

        try (OpenAIClientResponses client = new TestDataDamEngine(properties).createClient()) {
            HttpRequest filtered = client.applyFilters(request);
            Assertions.assertEquals("Bearer secret", filtered.headers().firstValue("Authorization").orElseThrow());
            Assertions.assertEquals("custom-secret", filtered.headers().firstValue("X-Api-Key").orElseThrow());
        }
    }

    @Test
    void dataDamShouldKeepItsTokenSecretAndStoreHeaderSecrets() throws DBException {
        // AI secrets live in the OS secure storage; the global controller only holds plain text values of older versions
        SecurePreferencesSecretController controller = Mockito.mock(SecurePreferencesSecretController.class);
        Mockito.when(controller.isPrivateSecretEncrypted(Mockito.anyString())).thenReturn(true);
        try (MockedStatic<DBWorkbench> workbench = Mockito.mockStatic(
                 DBWorkbench.class, Mockito.withSettings().mockMaker("mock-maker-inline"));
             MockedStatic<DBSSecretController> controllers = Mockito.mockStatic(
                 DBSSecretController.class, Mockito.withSettings().mockMaker("mock-maker-inline"));
             MockedStatic<DBSecurityUtils> providers = Mockito.mockStatic(
                 DBSecurityUtils.class, Mockito.withSettings().mockMaker("mock-maker-inline"));
             MockedStatic<SecurePreferencesSecretController> securePreferences = Mockito.mockStatic(
                 SecurePreferencesSecretController.class, Mockito.withSettings().mockMaker("mock-maker-inline"))) {
            workbench.when(DBWorkbench::getPlatform).thenReturn(Mockito.mock(DBPPlatform.class, Mockito.RETURNS_DEEP_STUBS));
            workbench.when(DBWorkbench::isPlatformStarted).thenReturn(true);
            controllers.when(DBSSecretController::getGlobalSecretControllerOrNull)
                .thenReturn(Mockito.mock(DBSSecretController.class));
            providers.when(DBSecurityUtils::findNativeOSProvider).thenReturn(DBSecurityProviders.WINDOWS_INTEGRATION);
            securePreferences.when(() -> SecurePreferencesSecretController.create(
                SecurePreferencesSecretController.SECRET_PREFS_ROOT, DBSecurityProviders.WINDOWS_INTEGRATION))
                .thenReturn(controller);
            AIConfigurationProfile profile = new AIConfigurationProfile();
            profile.setProfileId("datadam-test");
            Mockito.when(controller.getPrivateSecretValue("datadam.token_datadam-test")).thenReturn("secret");
            Mockito.when(controller.getPrivateSecretValue("openai.headers_datadam-test"))
                .thenReturn("{\"X-Api-Key\":\"custom-secret\"}");
            DDAIEngineProperties properties = new DDAIEngineProperties();

            properties.resolveSecrets(profile);
            Assertions.assertEquals("secret", properties.getToken());
            Assertions.assertEquals(Map.of("X-Api-Key", "custom-secret"), properties.getCustomHeaders());
            properties.saveSecrets(profile);
            Mockito.verify(controller).setPrivateSecretValue("datadam.token_datadam-test", "secret");
            Mockito.verify(controller).setPrivateSecretValue("openai.headers_datadam-test", "{\"X-Api-Key\":\"custom-secret\"}");
            properties.deleteSecrets(profile);
            Mockito.verify(controller).setPrivateSecretValue("datadam.token_datadam-test", null);
            Mockito.verify(controller).setPrivateSecretValue("openai.headers_datadam-test", null);
        }
    }

    private static class TestDataDamEngine extends DDAIEngine {
        private TestDataDamEngine(@NotNull DDAIEngineProperties properties) {
            super(properties);
        }

        @NotNull
        @Override
        public OpenAIClientResponses createClient() throws DBException {
            return super.createClient();
        }
    }
}
