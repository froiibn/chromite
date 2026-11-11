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
package com.seizia.chromite.model.ai.engine.chatcompletions;

import com.google.gson.annotations.SerializedName;
import com.seizia.chromite.model.ai.AIConfigurationProfile;
import com.seizia.chromite.model.ai.engine.BaseAIEngineProperties;
import com.seizia.chromite.model.ai.utils.AIUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.SecureProperty;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

/**
 * Settings of a provider reached over the OpenAI-compatible Chat Completions API ({@link ChatCompletionsEngine}):
 * base URL, API key, model.
 *
 * 설계:
 * <ul>
 *     <li>제공자(vLLM, Google Gemini)마다 하위 클래스 하나를 두고 엔진 클래스는 같이 쓴다. 하위 클래스는 기본 URL, 키가 꼭 필요한지,
 *     오류 문구에 쓸 이름, 도구 호출을 못 할 때의 안내, 모델 ID를 요청 이름으로 바꾸는 법만 정한다.</li>
 *     <li>API 키는 {@link SecureProperty}라 설정 파일에 쓰지 않고 {@link #saveSecrets}가 OS 보안 저장소({@code AIUtils.setSecretValue})에 둔다.
 *     Anthropic 엔진과 같은 방식이다. 비밀 이름은 제공자마다 다르다({@link #getApiKeySecretName}).</li>
 *     <li>temperature는 명시적으로 정했을 때만 보낸다({@link #getRequestTemperature}). 설정 파일에는 늘 기본값 0.0이 저장되는데, 이를 보내면
 *     서버·모델의 기본값 대신 0.0이 쓰인다(docs/chromite/ai-agent.md "실제 API 확인" 1).</li>
 *     <li>환경 변수는 읽지 않는다. 요청되지 않은 설정을 늘리지 않았다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-11 최초 작성: vLLM·Google Gemini 제공자 (app round 2 #34, #35, docs/chromite/ai-agent.md "Chat Completions 엔진")
 */
public abstract class ChatCompletionsProperties extends BaseAIEngineProperties {

    @Nullable
    @SerializedName("chat.baseUrl")
    private String baseUrl;

    @Nullable
    @SecureProperty
    @SerializedName("chat.apiKey")
    private String apiKey;

    @Nullable
    @SerializedName("chat.model")
    private String model;

    /**
     * Provider name for error texts, e.g. "vLLM".
     */
    @NotNull
    public abstract String getProviderName();

    /**
     * Base URL used when none is set, or null when the user must give one.
     */
    @Nullable
    public abstract String getDefaultBaseUrl();

    /**
     * Whether requests need an API key (a vLLM server needs one only when it was started with {@code --api-key}).
     */
    public abstract boolean isApiKeyRequired();

    /**
     * Name of the API key in the secure storage.
     */
    @NotNull
    protected abstract String getApiKeySecretName();

    /**
     * What the user can do when the server rejects tool calls, or null.
     */
    @Nullable
    public String getToolCallHint() {
        return null;
    }

    /**
     * Turns a model ID of the model list into the name that chat requests take.
     */
    @NotNull
    public String toModelName(@NotNull String modelId) {
        return modelId;
    }

    @Nullable
    @Property(order = 1, password = true)
    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(@Nullable String apiKey) {
        this.apiKey = CommonUtils.isEmptyTrimmed(apiKey) ? null : apiKey.trim();
    }

    /**
     * The base URL as set (null when not set).
     */
    @Nullable
    @Property(order = 2)
    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(@Nullable String baseUrl) {
        this.baseUrl = CommonUtils.isEmptyTrimmed(baseUrl) ? null : baseUrl.trim();
    }

    /**
     * The base URL requests go to: the one set, or the provider's default.
     */
    @Nullable
    public String getEffectiveBaseUrl() {
        return baseUrl != null ? baseUrl : getDefaultBaseUrl();
    }

    @Nullable
    @Override
    @Property(order = 3, required = true)
    public String getModel() {
        return model;
    }

    @Override
    public void setModel(@NotNull String model) {
        this.model = CommonUtils.isEmptyTrimmed(model) ? null : model.trim();
    }

    /**
     * None: the server knows the model's context window, and the callers that need a size use their default.
     */
    @Nullable
    @Override
    public Integer getContextWindowSize() {
        return null;
    }

    @Override
    public double getTemperature() {
        return AIUtils.normalizeTemperature(temperature);
    }

    /**
     * The temperature only when one was set explicitly; otherwise none, so the server or the model uses its own default.
     */
    @Nullable
    @Override
    public Double getRequestTemperature() {
        return hasExplicitTemperature() ? getTemperature() : null;
    }

    @Override
    public boolean isValidConfiguration() {
        return !CommonUtils.isEmptyTrimmed(getEffectiveBaseUrl())
            && !CommonUtils.isEmptyTrimmed(getEffectiveModel())
            && (!isApiKeyRequired() || !CommonUtils.isEmptyTrimmed(apiKey));
    }

    @Override
    public void resolveSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        if (apiKey == null) {
            apiKey = AIUtils.getSecretValueOrDefault(profile, getApiKeySecretName(), null);
        }
    }

    @Override
    public void saveSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        AIUtils.setSecretValue(profile, getApiKeySecretName(), apiKey);
    }

    @Override
    public void deleteSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        AIUtils.deleteSecretValue(profile, getApiKeySecretName());
    }
}
