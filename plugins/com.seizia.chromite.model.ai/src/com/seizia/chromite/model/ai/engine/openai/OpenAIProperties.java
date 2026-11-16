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
package com.seizia.chromite.model.ai.engine.openai;

import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.ai.AIConfigurationProfile;
import com.seizia.chromite.model.ai.AIConstants;
import com.seizia.chromite.model.ai.engine.AIAccountProperties;
import com.seizia.chromite.model.ai.engine.AIModel;
import com.seizia.chromite.model.ai.engine.BaseAIEngineProperties;
import com.seizia.chromite.model.ai.utils.AIUtils;
import com.seizia.chromite.model.auth.AuthProperty;
import com.seizia.chromite.model.data.json.JSONUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.SecureProperty;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

/**
 * OpenAI engine settings (API key or ChatGPT account sign-in).
 *
 * 설계: 컨텍스트 크기는 사용자가 저장한 값만 쓴다. 모델을 고를 때(selectModel) 제공자 모델 목록이 크기를 주면 그 값을,
 * 주지 않으면 AIConstants.DEFAULT_CONTEXT_WINDOW_SIZE를 저장한다. 사용자는 설정 화면에서 이 값을 바꿀 수 있다.
 *
 * 변경 이력:
 * - 2026-11-06 dbeaver.com AI 모델 카탈로그 제거: 모델 이름으로 카탈로그에서 컨텍스트 크기를 찾던 코드
 *   (OpenAIModels.getModelByName 사용)를 삭제했다. 그 결과 저장값과 같아진 getEffectiveContextWindowSize() 재정의와
 *   getConfiguredContextWindowSize()도 삭제했다.
 * - 2026-11-08 temperature를 명시적으로 정했을 때만 요청에 넣음(getRequestTemperature). 설정 파일의 기본값 0.0이 요청마다 가서 새 모델이
 *   거부할 수 있었다. Anthropic 엔진과 같은 원인·같은 방법 (docs/chromite/ai-agent.md "엔진")
 */
public class OpenAIProperties extends BaseAIEngineProperties implements OpenAIBaseProperties, AIAccountProperties {
    public static final String ENV_API_KEY = "OPENAI_API_KEY";
    public static final String ENV_BASE_URL = "OPENAI_BASE_URL";
    public static final String ENV_MODEL = "CHROMITE_AI_OPENAI_MODEL";

    protected static final String GPT_BASE_URL = "gpt.base_url";
    protected static final String GPT_TOKEN = "gpt.token";
    protected static final String GPT_MODEL = "gpt.model";
    protected static final String GPT_CONTEXT_WINDOW_SIZE = "gpt.contextWindowSize";
    public static final String AUTHENTICATION_API_TOKEN = "apiToken";
    public static final String AUTHENTICATION_CHATGPT_ACCOUNT = "chatgptAccount";
    public static final String ACCOUNT_ACCESS_TOKEN_PROPERTY = AIAccountProperties.ACCOUNT_ACCESS_TOKEN_PROPERTY;
    public static final String ACCOUNT_REFRESH_TOKEN_PROPERTY = AIAccountProperties.ACCOUNT_REFRESH_TOKEN_PROPERTY;
    public static final String ACCOUNT_ID_PROPERTY = AIAccountProperties.ACCOUNT_ID_PROPERTY;
    public static final String ACCOUNT_EMAIL_PROPERTY = AIAccountProperties.ACCOUNT_EMAIL_PROPERTY;
    public static final String ACCOUNT_EXPIRES_AT_PROPERTY = AIAccountProperties.ACCOUNT_EXPIRES_AT_PROPERTY;
    public static final int DEFAULT_ACCOUNT_CONTEXT_WINDOW_SIZE = 272_000;
    private static final String ACCESS_TOKEN = "openai.account.accessToken";
    private static final String REFRESH_TOKEN = "openai.account.refreshToken";
    private static final String CUSTOM_HEADERS = "openai.headers";


    @Nullable
    @SerializedName(GPT_BASE_URL)
    private String baseUrl;

    @Nullable
    @SecureProperty
    @SerializedName(GPT_TOKEN)
    private String token;

    @Nullable
    @SecureProperty
    @SerializedName(CUSTOM_HEADERS)
    private Map<String, String> customHeaders;

    @Nullable
    @SerializedName(GPT_MODEL)
    protected String model;

    @Nullable
    @SerializedName(GPT_CONTEXT_WINDOW_SIZE)
    protected Integer contextWindowSize;

    @SerializedName("openai.authentication")
    private String authentication = AUTHENTICATION_API_TOKEN;
    @SecureProperty
    @SerializedName(ACCESS_TOKEN)
    private String accessToken;
    @SecureProperty
    @SerializedName(REFRESH_TOKEN)
    private String refreshToken;
    @SerializedName("openai.account.expiresAt")
    private long expiresAt;
    @SerializedName("openai.account.accountId")
    private String accountId;
    @SerializedName("openai.account.email")
    private String accountEmail;
    private transient AIConfigurationProfile profile;
    private transient volatile OpenAIProperties accountCredentialsSource;
    private transient AIAccountProperties.AccountTokenPersistence accountTokenPersistence;
    private transient AIAccountProperties.AccountTokenValidator accountTokenValidator;
    private transient AIAccountProperties.AccountTokenRefreshHandler accountTokenRefreshHandler;

    public OpenAIProperties() {
    }

    @NotNull
    @Override
    @Property(order = 2, required = true)
    public String getBaseUrl() {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return OpenAIClientResponses.OPENAI_ENDPOINT;
        }
        return baseUrl;
    }

    public void setBaseUrl(@Nullable String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Nullable
    @Override
    @Property(order = 1, password = true, hideExpr = AIConstants.AI_NON_GLOBAL_CREDENTIALS_HIDE_EXPRESSION)
    public String getToken() {
        return token;
    }

    public void setToken(@Nullable String token) {
        this.token = token;
    }

    @NotNull
    @Override
    public Map<String, String> getCustomHeaders() {
        return customHeaders == null ? Map.of() : Map.copyOf(customHeaders);
    }

    public void setCustomHeaders(@NotNull Map<String, String> customHeaders) {
        this.customHeaders = Map.copyOf(customHeaders);
    }

    @Nullable
    @Override
    @Property(order = 3)
    public String getModel() {
        if (model != null) {
            return OpenAIModels.getEffectiveModelName(model);
        }

        String modelName = DBWorkbench.getPlatform()
            .getPreferenceStore()
            .getString(OpenAIConstants.GPT_MODEL);
        return OpenAIModels.getEffectiveModelName(modelName);
    }

    public void setModel(@Nullable String model) {
        this.model = model;
    }

    @Override
    public void selectModel(@NotNull AIModel model) {
        setModel(model.name());
        Integer contextSize = model.contextWindowSize();
        setContextWindowSize(contextSize != null ? contextSize : AIConstants.DEFAULT_CONTEXT_WINDOW_SIZE);
    }

    @Override
    @Property(order = 4)
    public double getTemperature() {
        if (Double.isFinite(temperature) && (hasExplicitTemperature() || temperature != AIUtils.DEFAULT_TEMPERATURE)) {
            return temperature;
        }

        return DBWorkbench.getPlatform()
            .getPreferenceStore()
            .getDouble(OpenAIConstants.AI_TEMPERATURE);
    }

    /**
     * The temperature only when one was set explicitly ({@link #setTemperature}); otherwise none, so the model uses its own
     * default. Newer OpenAI models reject the parameter, and the settings file always holds the field's default 0.0, which
     * the base class would send with every request (the same cause as the Anthropic engine's).
     */
    @Nullable
    @Override
    public Double getRequestTemperature() {
        return hasExplicitTemperature() ? getTemperature() : null;
    }

    @Override
    @Property(order = 1000)
    public boolean isLoggingEnabled() {
        if (loggingEnabled != null) {
            return loggingEnabled;
        }

        return DBWorkbench.getPlatform()
            .getPreferenceStore()
            .getBoolean(OpenAIConstants.AI_LOG_QUERY);
    }

    @Nullable
    @Override
    @Property(order = 6, min = 1)
    public Integer getContextWindowSize() {
        return contextWindowSize;
    }

    public void setContextWindowSize(@Nullable Integer contextWindowSize) {
        this.contextWindowSize = contextWindowSize;
    }

    @NotNull
    @Property(order = 1)
    public String getAuthentication() {
        return AUTHENTICATION_CHATGPT_ACCOUNT.equals(authentication)
            ? AUTHENTICATION_CHATGPT_ACCOUNT
            : AUTHENTICATION_API_TOKEN;
    }

    public boolean isChatGptAccountAuthentication() {
        return AUTHENTICATION_CHATGPT_ACCOUNT.equals(getAuthentication());
    }

    @Override
    public boolean isAccountAuthentication() {
        return isChatGptAccountAuthentication();
    }

    @Override
    public void setAccountAuthentication(boolean accountAuthentication) {
        setAuthentication(accountAuthentication ? AUTHENTICATION_CHATGPT_ACCOUNT : AUTHENTICATION_API_TOKEN);
    }

    @Override
    public boolean supportsDeviceAuthorization() {
        return true;
    }

    public void setAuthentication(@Nullable String authentication) {
        this.authentication = AUTHENTICATION_CHATGPT_ACCOUNT.equals(authentication)
            ? AUTHENTICATION_CHATGPT_ACCOUNT
            : AUTHENTICATION_API_TOKEN;
    }

    @Nullable
    @Property(id = ACCOUNT_ACCESS_TOKEN_PROPERTY, hidden = true, password = true)
    @AuthProperty
    @Override
    public String getAccessToken() {
        return accessToken;
    }

    @Override
    public void setAccessToken(@Nullable String accessToken) {
        this.accessToken = accessToken;
        accountId = OpenAIAccountAuthenticator.extractAccountId(accessToken);
        accountEmail = OpenAIAccountAuthenticator.extractEmail(accessToken);
        expiresAt = OpenAIAccountAuthenticator.extractExpiresAt(accessToken);
    }

    @Nullable
    @Property(id = ACCOUNT_REFRESH_TOKEN_PROPERTY, hidden = true, password = true)
    @AuthProperty
    @Override
    public String getRefreshToken() {
        return refreshToken;
    }

    @Override
    public void setRefreshToken(@Nullable String refreshToken) {
        this.refreshToken = refreshToken;
    }

    @Nullable
    @Property(id = ACCOUNT_ID_PROPERTY, hidden = true)
    @AuthProperty
    public String getStoredAccountId() {
        return accountId;
    }

    @Override
    public void setStoredAccountId(@Nullable String accountId) {
        this.accountId = accountId;
    }

    @Nullable
    @Property(id = ACCOUNT_EMAIL_PROPERTY, hidden = true)
    @AuthProperty
    public String getStoredAccountEmail() {
        return accountEmail;
    }

    @Override
    public void setStoredAccountEmail(@Nullable String accountEmail) {
        this.accountEmail = accountEmail;
    }

    @Property(id = ACCOUNT_EXPIRES_AT_PROPERTY, hidden = true)
    @AuthProperty
    @Override
    public long getStoredExpiresAt() {
        return expiresAt;
    }

    @Override
    public void setStoredExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isChatGptAccountConnected() {
        return isAccountConnected();
    }

    @Override
    public boolean isAccountConnected() {
        OpenAIProperties credentials = getAccountCredentialsOwner();
        synchronized (credentials) {
            return !CommonUtils.isEmpty(credentials.refreshToken);
        }
    }

    @Override
    public boolean isValidConfiguration() {
        return isChatGptAccountAuthentication()
            ? isChatGptAccountConnected()
            : OpenAIBaseProperties.super.isValidConfiguration();
    }

    @Nullable
    @Override
    public String getAccountId() {
        OpenAIProperties credentials = getAccountCredentialsOwner();
        synchronized (credentials) {
            return credentials.accountId;
        }
    }

    @Nullable
    @Override
    public String getAccountEmail() {
        OpenAIProperties credentials = getAccountCredentialsOwner();
        synchronized (credentials) {
            return credentials.accountEmail;
        }
    }

    @Override
    public synchronized void setAccountTokens(@NotNull AIAccountAuthenticator.Tokens tokens) {
        accessToken = tokens.accessToken();
        refreshToken = tokens.refreshToken();
        if (tokens.accountId() != null) {
            accountId = tokens.accountId();
        }
        if (tokens.email() != null) {
            accountEmail = tokens.email();
        }
        expiresAt = System.currentTimeMillis() + tokens.expiresInSeconds() * 1000;
    }

    @Override
    public void clearAccountTokens() {
        OpenAIProperties credentials = getAccountCredentialsOwner();
        synchronized (credentials) {
            credentials.accessToken = null;
            credentials.refreshToken = null;
            credentials.expiresAt = 0;
            credentials.accountId = null;
            credentials.accountEmail = null;
        }
    }

    public synchronized void copyAccountTokensFrom(@NotNull OpenAIProperties source) {
        OpenAIProperties credentials = source.getAccountCredentialsOwner();
        synchronized (credentials) {
            accessToken = credentials.accessToken;
            refreshToken = credentials.refreshToken;
            expiresAt = credentials.expiresAt;
            accountId = credentials.accountId;
            accountEmail = credentials.accountEmail;
        }
    }

    public void useAccountCredentialsFrom(@NotNull OpenAIProperties source) {
        accountCredentialsSource = source.getAccountCredentialsOwner();
    }

    @Override
    public void useAccountCredentialsFrom(@NotNull AIAccountProperties source) {
        if (!(source instanceof OpenAIProperties openAIProperties)) {
            throw new IllegalArgumentException("Incompatible AI account properties: " + source.getClass().getName());
        }
        useAccountCredentialsFrom(openAIProperties);
    }

    @Override
    public void setAccountTokenPersistence(
        @Nullable AIAccountProperties.AccountTokenPersistence accountTokenPersistence
    ) {
        getAccountCredentialsOwner().accountTokenPersistence = accountTokenPersistence;
    }

    public void setAccountTokenPersistence(@Nullable AccountTokenPersistence accountTokenPersistence) {
        setAccountTokenPersistence((AIAccountProperties.AccountTokenPersistence) accountTokenPersistence);
    }

    @Override
    public void setAccountTokenValidator(@Nullable AIAccountProperties.AccountTokenValidator accountTokenValidator) {
        getAccountCredentialsOwner().accountTokenValidator = accountTokenValidator;
    }

    public void setAccountTokenValidator(@Nullable AccountTokenValidator accountTokenValidator) {
        setAccountTokenValidator((AIAccountProperties.AccountTokenValidator) accountTokenValidator);
    }

    @Override
    public void setAccountTokenRefreshHandler(
        @Nullable AIAccountProperties.AccountTokenRefreshHandler accountTokenRefreshHandler
    ) {
        getAccountCredentialsOwner().accountTokenRefreshHandler = accountTokenRefreshHandler;
    }

    public void setAccountTokenRefreshHandler(@Nullable AccountTokenRefreshHandler accountTokenRefreshHandler) {
        setAccountTokenRefreshHandler((AIAccountProperties.AccountTokenRefreshHandler) accountTokenRefreshHandler);
    }

    @NotNull
    @Override
    public String getValidAccessToken(@NotNull AIAccountAuthenticator authenticator) throws DBException {
        OpenAIProperties credentials = getAccountCredentialsOwner();
        synchronized (credentials) {
            if (credentials.accountTokenValidator != null) {
                credentials.accountTokenValidator.validate(credentials.refreshToken);
            }
            if (!CommonUtils.isEmpty(credentials.accessToken)
                && credentials.expiresAt > System.currentTimeMillis() + 60_000
            ) {
                return credentials.accessToken;
            }
            if (CommonUtils.isEmpty(credentials.refreshToken)) {
                throw new DBException(getAccountProviderName() + " account is not connected");
            }
            String previousRefreshToken = credentials.refreshToken;
            AIAccountAuthenticator.Tokens tokens;
            if (credentials.accountTokenRefreshHandler != null) {
                tokens = credentials.accountTokenRefreshHandler.refresh(authenticator, previousRefreshToken);
            } else {
                tokens = authenticator.refresh(previousRefreshToken);
            }
            credentials.setAccountTokens(tokens);
            if (credentials.accountTokenRefreshHandler == null) {
                credentials.saveAccountTokensInternal(previousRefreshToken, new AIAccountAuthenticator.Tokens(
                    tokens.accessToken(),
                    tokens.refreshToken(),
                    tokens.expiresInSeconds(),
                    credentials.accountId,
                    credentials.accountEmail
                ));
            }
            return credentials.accessToken;
        }
    }

    @Override
    public void saveAccountTokens() throws DBException {
        OpenAIProperties credentials = getAccountCredentialsOwner();
        synchronized (credentials) {
            long expiresInSeconds = Math.max(0, (credentials.expiresAt - System.currentTimeMillis()) / 1000);
            credentials.saveAccountTokensInternal(credentials.refreshToken, new AIAccountAuthenticator.Tokens(
                credentials.accessToken,
                credentials.refreshToken,
                expiresInSeconds,
                credentials.accountId,
                credentials.accountEmail
            ));
        }
    }

    @Override
    public void resolveSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        if (token == null) {
            token = AIUtils.getSecretValueOrDefault(profile, getApiTokenSecretId(), token);
        }
        if (customHeaders == null) {
            String headers = AIUtils.getSecretValueOrDefault(profile, CUSTOM_HEADERS, null);
            if (headers != null) {
                customHeaders = JSONUtils.GSON.fromJson(headers, new TypeToken<Map<String, String>>() { }.getType());
            }
        }
        this.profile = profile;
        if (accessToken == null) {
            accessToken = AIUtils.getAccountTokenOrDefault(profile, getAccessTokenSecretId(), null);
        }
        if (refreshToken == null) {
            refreshToken = AIUtils.getAccountTokenOrDefault(profile, getRefreshTokenSecretId(), null);
        }
        String resolvedAccountId = OpenAIAccountAuthenticator.extractAccountId(null, accessToken);
        if (resolvedAccountId != null) {
            accountId = resolvedAccountId;
        }
        String resolvedEmail = OpenAIAccountAuthenticator.extractEmail(null, accessToken);
        if (resolvedEmail != null) {
            accountEmail = resolvedEmail;
        }
        long resolvedExpiresAt = OpenAIAccountAuthenticator.extractExpiresAt(accessToken);
        if (resolvedExpiresAt > 0) {
            expiresAt = resolvedExpiresAt;
        }
    }

    @Override
    public void saveSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        AIUtils.setSecretValue(profile, getApiTokenSecretId(), token);
        saveCustomHeaderSecrets(profile);
        this.profile = profile;
        AIUtils.setAccountToken(profile, getRefreshTokenSecretId(), refreshToken);
        AIUtils.setAccountToken(profile, getAccessTokenSecretId(), accessToken);
    }

    @Override
    public void deleteSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        AIUtils.deleteSecretValue(profile, getApiTokenSecretId());
        AIUtils.deleteSecretValue(profile, CUSTOM_HEADERS);
        AIUtils.deleteAccountToken(profile, getAccessTokenSecretId());
        AIUtils.deleteAccountToken(profile, getRefreshTokenSecretId());
    }

    public void saveCustomHeaderSecrets(@NotNull AIConfigurationProfile profile) throws DBException {
        if (customHeaders != null) {
            AIUtils.setSecretValue(profile, CUSTOM_HEADERS, JSONUtils.GSON.toJson(customHeaders));
        }
    }

    @NotNull
    protected String getApiTokenSecretId() {
        return OpenAIConstants.GPT_API_TOKEN;
    }

    @NotNull
    protected String getAccountProviderName() {
        return "ChatGPT";
    }

    @NotNull
    @Override
    public String getAccountAuthenticationProviderName() {
        return getAccountProviderName();
    }

    @NotNull
    @Override
    public AIAccountAuthenticator createAccountAuthenticator() throws DBException {
        if (!isChatGptAccountAuthentication()) {
            throw new DBException("Account authenticator is not configured for " + getAccountProviderName());
        }
        return new OpenAIAccountAuthenticator(getTimeout());
    }

    @NotNull
    protected String getAccessTokenSecretId() {
        return ACCESS_TOKEN;
    }

    @NotNull
    protected String getRefreshTokenSecretId() {
        return REFRESH_TOKEN;
    }

    @NotNull
    private OpenAIProperties getAccountCredentialsOwner() {
        OpenAIProperties source = accountCredentialsSource;
        return source == null ? this : source.getAccountCredentialsOwner();
    }

    @NotNull
    @Override
    public List<String> getEnvironmentVariables() {
        return List.of(ENV_API_KEY, ENV_BASE_URL, ENV_MODEL);
    }

    @Nullable
    @Override
    protected String getEnvironmentModel() {
        return getEnvironmentValue(ENV_MODEL);
    }

    @Nullable
    @Override
    public String getEffectiveToken() {
        return isAccountAuthentication() ? getToken() : resolveEnvironmentValue(getToken(), ENV_API_KEY);
    }

    @NotNull
    @Override
    public String getEffectiveBaseUrl() {
        return isAccountAuthentication() ? getBaseUrl() : resolveEnvironmentValue(getBaseUrl(), ENV_BASE_URL);
    }

    private void saveAccountTokensInternal(
        @Nullable String previousRefreshToken,
        @NotNull AIAccountAuthenticator.Tokens tokens
    ) throws DBException {
        if (profile != null) {
            saveSecrets(profile);
        }
        if (accountTokenPersistence != null && tokens.accessToken() != null && tokens.refreshToken() != null) {
            accountTokenPersistence.save(previousRefreshToken, tokens);
        }
    }

    @FunctionalInterface
    public interface AccountTokenPersistence extends AIAccountProperties.AccountTokenPersistence {
    }

    @FunctionalInterface
    public interface AccountTokenValidator extends AIAccountProperties.AccountTokenValidator {
    }

    @FunctionalInterface
    public interface AccountTokenRefreshHandler extends AIAccountProperties.AccountTokenRefreshHandler {
    }

}
