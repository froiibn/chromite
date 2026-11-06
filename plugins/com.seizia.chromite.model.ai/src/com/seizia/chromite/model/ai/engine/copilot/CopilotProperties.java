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
package com.seizia.chromite.model.ai.engine.copilot;

import com.google.gson.annotations.SerializedName;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.ai.AIConfigurationProfile;
import com.seizia.chromite.model.ai.engine.AIModel;
import com.seizia.chromite.model.ai.engine.BaseAIEngineProperties;
import com.seizia.chromite.model.ai.utils.AIUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.SecureProperty;
import org.jkiss.utils.CommonUtils;

import java.util.List;

/**
 * GitHub Copilot engine settings.
 *
 * 설계: 컨텍스트 크기는 사용자가 저장한 값이나 모델을 고를 때 Copilot 모델 목록 응답에서 받은 값(selectModel)만 쓴다.
 * temperature는 저장한 값을 쓰고, 값이 없으면 AIUtils.DEFAULT_TEMPERATURE를 쓴다.
 *
 * 변경 이력:
 * - 2026-11-06 dbeaver.com AI 모델 카탈로그 제거: 모델 이름으로 카탈로그에서 컨텍스트 크기와 기본 temperature를 찾던 코드
 *   (CopilotModels.getModelByName 사용)를 삭제했다. 그 결과 저장값과 같아진 getEffectiveTemperature(),
 *   getEffectiveContextWindowSize() 재정의, getConfiguredContextWindowSize()도 삭제했다.
 */
public class CopilotProperties extends BaseAIEngineProperties {
    public static final String ENV_TOKEN = "COPILOT_GITHUB_TOKEN";
    public static final String ENV_GH_TOKEN = "GH_TOKEN";
    public static final String ENV_GITHUB_TOKEN = "GITHUB_TOKEN";
    public static final String ENV_MODEL = "COPILOT_MODEL";
    public static final String ENV_AUTH_URL = "CHROMITE_AI_COPILOT_AUTH_URL";

    private static final String COPILOT_ACCESS_TOKEN = "copilot.access.token";
    private static final String GPT_MODEL = "gpt.model";
    private static final String GPT_CONTEXT_WINDOW_SIZE = "gpt.contextWindowSize";

    @Nullable
    @SecureProperty
    @SerializedName(COPILOT_ACCESS_TOKEN)
    private String token;

    @Nullable
    @SerializedName(GPT_MODEL)
    private String model;

    @Nullable
    @SerializedName(GPT_CONTEXT_WINDOW_SIZE)
    private Integer contextWindowSize;

    @Nullable
    @Property(order = 1, password = true, required = true)
    public String getToken() {
        return token;
    }

    @NotNull
    public String getBaseAuthUrl() {
        return CopilotConstants.BASE_AUTH_URL;
    }

    public void setToken(@Nullable String token) {
        this.token = token;
    }

    @Nullable
    @Property(order = 2)
    public String getModel() {
        return model;
    }

    public void setModel(@Nullable String model) {
        this.model = model;
    }

    @Override
    public void selectModel(@NotNull AIModel model) {
        setModel(model.name());
        setContextWindowSize(model.contextWindowSize());
    }

    @Override
    @Property(order = 3)
    public double getTemperature() {
        return Double.isFinite(temperature) ? temperature : AIUtils.DEFAULT_TEMPERATURE;
    }

    @Override
    @Nullable
    @Property(order = 4, min = 1)
    public Integer getContextWindowSize() {
        return contextWindowSize;
    }

    public void setContextWindowSize(@Nullable Integer contextWindowSize) {
        this.contextWindowSize = contextWindowSize;
    }

    /**
     * Resolve secrets from the secret controller.
     */
    public void resolveSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        if (token == null) {
            token = AIUtils.getSecretValueOrDefault(profile, CopilotConstants.COPILOT_ACCESS_TOKEN, token);
        }
    }

    /**
     * Save secrets to the secret controller.
     */
    public void saveSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        AIUtils.setSecretValue(profile, CopilotConstants.COPILOT_ACCESS_TOKEN, token);
    }

    @Override
    public void deleteSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        AIUtils.deleteSecretValue(profile, CopilotConstants.COPILOT_ACCESS_TOKEN);
    }

    @Override
    public boolean isValidConfiguration() {
        return !CommonUtils.isEmpty(getEffectiveToken());
    }

    @NotNull
    @Override
    public List<String> getEnvironmentVariables() {
        return List.of(ENV_TOKEN, ENV_GH_TOKEN, ENV_GITHUB_TOKEN, ENV_MODEL, ENV_AUTH_URL);
    }

    @Nullable
    @Override
    protected String getEnvironmentModel() {
        return getEnvironmentValue(ENV_MODEL);
    }

    @Nullable
    public String getEffectiveToken() {
        return resolveEnvironmentValue(getToken(), ENV_TOKEN, ENV_GH_TOKEN, ENV_GITHUB_TOKEN);
    }

    @NotNull
    public String getEffectiveBaseAuthUrl() {
        return resolveEnvironmentValue(getBaseAuthUrl(), ENV_AUTH_URL);
    }
}
