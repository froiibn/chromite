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
package com.seizia.chromite.model.ai.engine.anthropic;

import com.google.gson.annotations.SerializedName;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.ai.AIConfigurationProfile;
import com.seizia.chromite.model.ai.engine.BaseAIEngineProperties;
import com.seizia.chromite.model.ai.utils.AIUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.SecureProperty;
import org.jkiss.utils.CommonUtils;

import java.util.List;

/**
 * Settings of the Anthropic (Claude) engine.
 *
 * 설계:
 * <ul>
 *     <li>API 키는 {@link SecureProperty}라 설정 파일에 쓰지 않고, {@link #saveSecrets}가 보안 저장소({@code AIUtils.setSecretValue})에 둔다.
 *     OpenAI 엔진({@code OpenAIProperties})과 같은 방식이다. 환경 변수 {@value #ENV_API_KEY}도 "환경 변수 사용"을 켜면 쓴다.</li>
 *     <li>모델 목록은 제공자의 목록 API에서 받는다(docs/chromite/ai.md "내장 정적 모델 목록을 두지 않는다"). 기본 모델 이름만 둔다.</li>
 *     <li>temperature, 로그, 시간 제한 같은 공통 값은 {@link BaseAIEngineProperties}가 다룬다. 그 클래스의 직렬화 이름에 이미
 *     {@code anthropic.temperature} 같은 옛 이름이 대체 이름으로 있다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "엔진")
 */
public class AnthropicProperties extends BaseAIEngineProperties {

    public static final String ENV_API_KEY = "ANTHROPIC_API_KEY"; //$NON-NLS-1$
    public static final String ENV_BASE_URL = "ANTHROPIC_BASE_URL"; //$NON-NLS-1$
    public static final String ENV_MODEL = "CHROMITE_AI_ANTHROPIC_MODEL"; //$NON-NLS-1$

    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com/"; //$NON-NLS-1$
    public static final String DEFAULT_MODEL = "claude-sonnet-5-5"; //$NON-NLS-1$
    public static final int DEFAULT_CONTEXT_WINDOW_SIZE = 200_000;
    public static final int DEFAULT_MAX_TOKENS = 8192;

    static final String API_KEY_SECRET = "anthropic.apiKey"; //$NON-NLS-1$

    @Nullable
    @SerializedName("anthropic.baseUrl")
    private String baseUrl;

    @Nullable
    @SecureProperty
    @SerializedName(API_KEY_SECRET)
    private String apiKey;

    @Nullable
    @SerializedName("anthropic.model")
    private String model;

    @Nullable
    @SerializedName("anthropic.contextWindowSize")
    private Integer contextWindowSize;

    @Nullable
    @SerializedName("anthropic.maxTokens")
    private Integer maxTokens;

    @Nullable
    @Property(order = 1, password = true)
    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(@Nullable String apiKey) {
        this.apiKey = apiKey;
    }

    @NotNull
    @Property(order = 2)
    public String getBaseUrl() {
        return CommonUtils.isEmptyTrimmed(baseUrl) ? DEFAULT_BASE_URL : baseUrl;
    }

    public void setBaseUrl(@Nullable String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Nullable
    @Override
    @Property(order = 3, required = true)
    public String getModel() {
        return CommonUtils.isEmptyTrimmed(model) ? DEFAULT_MODEL : model;
    }

    @Override
    public void setModel(@NotNull String model) {
        this.model = model;
    }

    @Nullable
    @Override
    @Property(order = 4, min = 1)
    public Integer getContextWindowSize() {
        return contextWindowSize == null || contextWindowSize <= 0 ? DEFAULT_CONTEXT_WINDOW_SIZE : contextWindowSize;
    }

    public void setContextWindowSize(@Nullable Integer contextWindowSize) {
        this.contextWindowSize = contextWindowSize;
    }

    @Property(order = 5, min = 1)
    public int getMaxTokens() {
        return maxTokens == null || maxTokens <= 0 ? DEFAULT_MAX_TOKENS : maxTokens;
    }

    public void setMaxTokens(@Nullable Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    @Override
    @Property(order = 6)
    public double getTemperature() {
        return AIUtils.normalizeTemperature(temperature);
    }

    @Nullable
    public String getEffectiveApiKey() {
        return resolveEnvironmentValue(apiKey, ENV_API_KEY);
    }

    @NotNull
    public String getEffectiveBaseUrl() {
        String url = resolveEnvironmentValue(baseUrl, ENV_BASE_URL);
        return CommonUtils.isEmptyTrimmed(url) ? DEFAULT_BASE_URL : url;
    }

    @Nullable
    @Override
    protected String getEnvironmentModel() {
        return getEnvironmentValue(ENV_MODEL);
    }

    @NotNull
    @Override
    public List<String> getEnvironmentVariables() {
        return List.of(ENV_API_KEY, ENV_BASE_URL, ENV_MODEL);
    }

    @Override
    public boolean isValidConfiguration() {
        return !CommonUtils.isEmptyTrimmed(getEffectiveApiKey()) && !CommonUtils.isEmptyTrimmed(getEffectiveModel());
    }

    @Override
    public void resolveSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        if (apiKey == null) {
            apiKey = AIUtils.getSecretValueOrDefault(profile, API_KEY_SECRET, null);
        }
    }

    @Override
    public void saveSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        AIUtils.setSecretValue(profile, API_KEY_SECRET, apiKey);
    }

    @Override
    public void deleteSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        AIUtils.deleteSecretValue(profile, API_KEY_SECRET);
    }
}
