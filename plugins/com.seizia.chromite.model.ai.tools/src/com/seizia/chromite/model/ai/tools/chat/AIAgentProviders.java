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
package com.seizia.chromite.model.ai.tools.chat;

import com.seizia.chromite.model.ai.AIConfigurationProfile;
import com.seizia.chromite.model.ai.AISettings;
import com.seizia.chromite.model.ai.engine.AIEngine;
import com.seizia.chromite.model.ai.engine.AIEngineProperties;
import com.seizia.chromite.model.ai.engine.AIModel;
import com.seizia.chromite.model.ai.registry.AIEngineDescriptor;
import com.seizia.chromite.model.ai.registry.AIEngineRegistry;
import com.seizia.chromite.model.ai.registry.AISettingsManager;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The AI providers of the assistant (Anthropic Claude, OpenAI, Ollama): their settings profiles, engines and model lists.
 *
 * 설계:
 * <ul>
 *     <li>제공자마다 설정 프로필({@link AIConfigurationProfile}) 하나를 쓰고, 프로필 ID는 엔진 ID와 같다. 기존 AI 설정 파일({@code ai-configuration.json})을
 *     그대로 쓰고, API 키는 {@code @SecureProperty}라 설정 파일에 쓰이지 않고 OS 보안 저장소({@code AIUtils.setSecretValue})에 저장된다
 *     (docs/chromite/ai-agent.md "엔진").</li>
 *     <li>{@code AISettingsManager.saveSettings}는 기본 프로필의 비밀만 보안 저장소에 쓴다. 채팅은 대화마다 제공자를 고르므로 세 프로필 모두 기본이 아닐 수 있어
 *     {@link #save}가 각 프로필의 비밀을 직접 쓴다. 비밀을 먼저 쓰므로 보안 저장소를 쓸 수 없으면 설정도 저장하지 않고 오류를 호출자(설정 화면)에 돌려준다.</li>
 *     <li>대화의 모델은 프로필 설정의 사본에 넣어 엔진을 만든다({@link #createEngine}). 대화에서 모델을 바꿔도 설정 화면의 기본 모델은 바뀌지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "2단계")
 * - 2026-11-07 비밀을 설정보다 먼저 저장(보안 저장소 실패 시 오류 대화상자 한 번, 아무것도 저장 안 함). 키가 실제로 OS 보안 저장소에 가도록
 *   {@code AIUtils}를 고쳐 설명을 사실에 맞춤
 * - 2026-11-07 "설정하지 않음" 오류의 설정 경로를 바뀐 탭 이름(Options &gt; Chromite Agent &gt; Providers)으로 고침
 *   (docs/chromite/ai-agent.md "명칭")
 */
public final class AIAgentProviders {

    public static final String ANTHROPIC = "anthropic"; //$NON-NLS-1$
    public static final String OPENAI = "openai"; //$NON-NLS-1$
    public static final String OLLAMA = "ollama"; //$NON-NLS-1$

    /**
     * Providers of the assistant, in picker order.
     */
    public static final List<String> PROVIDERS = List.of(ANTHROPIC, OPENAI, OLLAMA);

    /**
     * Models offered before the provider's model list was loaded (Anthropic names its models; the others depend on the account
     * or on what is installed).
     */
    private static final List<String> ANTHROPIC_MODELS = List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private AIAgentProviders() {
    }

    /**
     * Picker name of a provider. Product names are allowed here: they name the provider the user picks.
     */
    @NotNull
    public static String getLabel(@NotNull String engineId) {
        return switch (engineId) {
            case ANTHROPIC -> "Anthropic Claude"; //$NON-NLS-1$
            case OPENAI -> "OpenAI"; //$NON-NLS-1$
            case OLLAMA -> "Ollama"; //$NON-NLS-1$
            default -> engineId;
        };
    }

    /**
     * The provider's profile, or null if it was never set up.
     */
    @Nullable
    public static AIConfigurationProfile getProfile(@NotNull String engineId) {
        AISettings settings = AISettingsManager.getInstance().getSettings();
        AIConfigurationProfile profile = settings.getConfigurationOrNull(engineId);
        if (profile != null) {
            return profile;
        }
        for (AIConfigurationProfile p : settings.getConfigurations()) {
            if (engineId.equals(p.getEngineId())) {
                return p;
            }
        }
        return null;
    }

    /**
     * The provider's profile, created (not saved) if missing.
     */
    @NotNull
    public static AIConfigurationProfile getOrCreateProfile(@NotNull String engineId) throws DBException {
        AIConfigurationProfile profile = getProfile(engineId);
        if (profile == null) {
            AIEngineDescriptor descriptor = getEngine(engineId);
            profile = AISettingsManager.getInstance().getSettings().createConfiguration(engineId, descriptor);
            profile.setProfileName(getLabel(engineId));
        }
        return profile;
    }

    @NotNull
    private static AIEngineDescriptor getEngine(@NotNull String engineId) throws DBException {
        AIEngineDescriptor descriptor = AIEngineRegistry.getInstance().getEngineDescriptor(engineId);
        if (descriptor == null) {
            throw new DBException("AI engine '" + engineId + "' is not installed");
        }
        return descriptor;
    }

    /**
     * Whether the provider has what it needs to answer (key, host, model).
     */
    public static boolean isConfigured(@NotNull String engineId) {
        AIConfigurationProfile profile = getProfile(engineId);
        if (profile == null) {
            return false;
        }
        try {
            profile.resolveSecrets();
            return profile.getConfiguration().isValidConfiguration();
        } catch (DBException e) {
            return false;
        }
    }

    /**
     * The model of the provider's settings, or null.
     */
    @Nullable
    public static String getDefaultModel(@NotNull String engineId) {
        AIConfigurationProfile profile = getProfile(engineId);
        try {
            if (profile != null) {
                return profile.getConfiguration().getEffectiveModel();
            }
        } catch (DBException e) {
            return null;
        }
        return engineId.equals(ANTHROPIC) ? ANTHROPIC_MODELS.get(1) : null;
    }

    /**
     * Model names to offer before the provider's list was loaded: the configured model and the known names.
     */
    @NotNull
    public static List<String> getSuggestedModels(@NotNull String engineId) {
        List<String> models = new ArrayList<>();
        String configured = getDefaultModel(engineId);
        if (!CommonUtils.isEmpty(configured)) {
            models.add(configured);
        }
        if (engineId.equals(ANTHROPIC)) {
            for (String model : ANTHROPIC_MODELS) {
                if (!models.contains(model)) {
                    models.add(model);
                }
            }
        }
        return models;
    }

    /**
     * Saves the secrets of every assistant provider profile (OS secure storage, never the settings file), then the settings.
     * If a secret can't be saved, nothing is saved and the error goes to the caller.
     */
    public static void save() throws DBException {
        AISettingsManager manager = AISettingsManager.getInstance();
        AISettings settings = manager.getSettings();
        if (!AISettingsManager.saveSecretsAsPlainText()) {
            for (String engineId : PROVIDERS) {
                AIConfigurationProfile profile = settings.getConfigurationOrNull(engineId);
                if (profile != null) {
                    profile.saveSecrets();
                }
            }
        }
        manager.saveSettings(settings);
    }

    /**
     * A new engine of the provider with the given model (a copy of the settings, so the saved default model stays).
     * The caller closes it.
     */
    @NotNull
    public static AIEngine<?> createEngine(@NotNull String engineId, @Nullable String model) throws DBException {
        AIConfigurationProfile profile = getProfile(engineId);
        if (profile == null) {
            throw new DBException(getLabel(engineId) + " is not configured (Options > Chromite Agent > Providers)");
        }
        profile.resolveSecrets();
        AIEngineProperties source = profile.getConfiguration();
        AIEngineProperties copy = AISettingsManager.READ_PROPS_GSON.fromJson(
            AISettingsManager.READ_PROPS_GSON.toJson(source), source.getClass());
        if (!CommonUtils.isEmptyTrimmed(model) && copy.isModelSelectionSupported()) {
            copy.setModel(model.trim());
        }
        if (!copy.isValidConfiguration()) {
            throw new DBException(getLabel(engineId) + " is not configured (Options > Chromite Agent > Providers)");
        }
        return profile.getEngineDescriptor().createEngineInstance(copy);
    }

    /**
     * A copy of the provider's settings with the secrets resolved (new default settings if it was never set up), for a
     * settings page to edit and test before saving.
     */
    @NotNull
    public static AIEngineProperties copyProperties(@NotNull String engineId) throws DBException {
        AIConfigurationProfile profile = getProfile(engineId);
        if (profile == null) {
            return getEngine(engineId).createPropertiesInstance();
        }
        profile.resolveSecrets();
        AIEngineProperties source = profile.getConfiguration();
        return AISettingsManager.READ_PROPS_GSON.fromJson(AISettingsManager.READ_PROPS_GSON.toJson(source), source.getClass());
    }

    /**
     * Makes the edited settings the provider's settings and saves them (secrets to the secure storage).
     */
    public static void saveProperties(@NotNull String engineId, @NotNull AIEngineProperties properties) throws DBException {
        AIConfigurationProfile profile = getOrCreateProfile(engineId);
        profile.setConfiguration(properties);
        save();
    }

    /**
     * The provider's models with the given (maybe unsaved) settings: a request to the provider, used by the settings page's
     * Test button. Never logs the settings.
     */
    @NotNull
    public static List<String> loadModels(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String engineId,
        @NotNull AIEngineProperties properties
    ) throws DBException {
        try (AIEngine<?> engine = getEngine(engineId).createEngineInstance(properties)) {
            List<String> names = new ArrayList<>();
            for (AIModel model : engine.getModels(monitor)) {
                names.add(model.name());
            }
            return names;
        }
    }
}
