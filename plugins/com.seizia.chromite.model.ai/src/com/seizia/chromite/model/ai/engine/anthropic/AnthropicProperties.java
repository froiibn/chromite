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
 *     <li>요청에는 temperature를 명시적으로 정했을 때만 넣는다({@link #getRequestTemperature}). 설정 파일에는 늘 기본값 0.0이 저장되는데,
 *     지금의 Claude 모델은 이 매개변수를 받지 않고 요청 전체를 거부한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "엔진")
 * - 2026-11-08 temperature를 명시적으로 정했을 때만 보냄: 실제 API가 claude-sonnet-5-5 요청을 모두 거부함 (docs/chromite/ai-agent.md "실제 API 확인")
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

    /**
     * The temperature only when one was set explicitly ({@link #setTemperature}); otherwise none, so the model uses its own
     * default. Current Claude models reject the parameter ("`temperature` is deprecated for this model"), and the settings
     * file always holds the field's default 0.0, which the base class would send.
     */
    @Nullable
    @Override
    public Double getRequestTemperature() {
        return hasExplicitTemperature() ? getTemperature() : null;
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
