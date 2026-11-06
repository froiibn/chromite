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
package com.seizia.chromite.ui.ai.engine.copilot;


import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.ai.engine.AIEngineProperties;
import com.seizia.chromite.model.ai.engine.AIModel;
import com.seizia.chromite.model.ai.engine.AIModelFeature;
import com.seizia.chromite.model.ai.engine.copilot.*;
import com.seizia.chromite.model.ai.registry.AIEngineDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceAuth;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.ai.internal.AIUIMessages;
import com.seizia.chromite.ui.ai.model.CachedValue;
import com.seizia.chromite.ui.ai.model.ContextWindowSizeField;
import com.seizia.chromite.ui.ai.model.ModelSelectorField;
import com.seizia.chromite.ui.ai.preferences.AbstractAIEngineConfigurator;
import org.jkiss.utils.CommonUtils;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * GitHub Copilot engine settings page.
 *
 * 설계: 모델 콤보의 목록과 모델별 컨텍스트 크기는 Copilot 모델 목록 API에서만 가져온다.
 * 목록에 없는 모델 이름도 직접 입력할 수 있고, 그때 컨텍스트 크기는 사용자가 입력한다.
 *
 * 변경 이력:
 * - 2026-11-06 dbeaver.com AI 모델 카탈로그 제거: 목록에 없는 모델을 카탈로그(CopilotModels.getModelByName)에서 찾던 코드를
 *   삭제했다. 컨텍스트 크기 필드에는 저장된 값을 그대로 불러온다(getConfiguredContextWindowSize 삭제).
 */
public class CopilotConfigurator<ENGINE extends AIEngineDescriptor, PROPERTIES extends CopilotProperties>
    extends AbstractAIEngineConfigurator<ENGINE, PROPERTIES> {
    private static final Log log = Log.getLog(CopilotConfigurator.class);

    private Text temperatureText;
    private ContextWindowSizeField contextWindowSizeField;
    private ModelSelectorField modelSelectorField;
    protected Text accessTokenText;
    private Button authorizeButton;

    protected volatile String accessToken;
    protected String token = "";
    private String temperature = "0.0";

    protected final CachedValue<List<AIModel>> modelsCache = new CachedValue<>(this::fetchCopilotModels);

    @NotNull
    private List<AIModel> fetchCopilotModels(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (CommonUtils.isEmpty(createConnectionProperties().getEffectiveToken())) {
            throw new DBException(CopilotMessages.copilot_access_token_required);
        }

        try (CopilotCompletionEngine engine = createEngine()) {
            return engine.getModels(monitor);
        }
    }

    @Override
    public void createControl(
        @NotNull Composite parent,
        AIEngineDescriptor object,
        @NotNull Runnable propertyChangeListener
    ) {
        Composite composite = UIUtils.createComposite(parent, 3);
        composite.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        createConnectionParameters(composite);
        createModelParameters(composite);
        createAdditionalSettings(composite);
        createEnvironmentSettings(propertyChangeListener);
        UIUtils.syncExec(this::applySettings);
    }

    @Override
    public void loadSettings(@NotNull PROPERTIES configuration) {
        token = CommonUtils.toString(configuration.getToken());
        modelSelectorField.setSelectedModel(configuration.getModel());
        contextWindowSizeField.setValue(configuration.getContextWindowSize());
        temperature = configuration.getRequestTemperature() == null ? "" : String.valueOf(configuration.getTemperature());
        accessToken = token;
        accessTokenText.setText(accessToken);
        applySettings();
        loadAdvancedSettings(configuration);
        updateEnvironmentControls();

        modelSelectorField.refreshModelListSilently(true);
    }

    @Override
    public void saveSettings(@NotNull PROPERTIES properties) {
        properties.setToken(accessToken);
        properties.setModel(modelSelectorField.getSelectedModelName());
        properties.setContextWindowSize(contextWindowSizeField.getValue());
        saveTemperature(properties, temperature);
        saveAdvancedSettings(properties);
    }

    @Override
    public void resetSettings(@NotNull PROPERTIES copilotPropertiesLegacyAISettings) {

    }

    @Override
    public boolean isComplete() {
        return true;
    }

    protected void createModelParameters(@NotNull Composite parent) {
        ModelSelectorField.ModelListProvider modelListProvider = (monitor, forceRefresh) ->
            modelsCache.get(monitor, forceRefresh).stream()
            .filter(it -> it.features().contains(AIModelFeature.CHAT))
            .toList();

        modelSelectorField = ModelSelectorField.builder()
            .withParent(parent)
            .withGridData(new GridData(GridData.FILL_HORIZONTAL))
            .withModelChangeListener(modelChanged -> {
                AIModel selectedModel = modelSelectorField.getSelectedModel();
                updateModelParameters(selectedModel, temperatureText, contextWindowSizeField, modelChanged);
            })
            .withModelListSupplier(modelListProvider)
            .build();

        contextWindowSizeField = ContextWindowSizeField.builder()
            .withParent(parent)
            .withGridData(GridDataFactory.fillDefaults().span(2, 1).create())
            .build();

        GridData gridData = new GridData(GridData.FILL_HORIZONTAL);
        gridData.horizontalSpan = 2;
        temperatureText = UIUtils.createLabelText(parent, AIUIMessages.gpt_preference_page_text_temperature, "0.0");
        temperatureText.setLayoutData(gridData);
        temperatureText.setToolTipText(AIUIMessages.openai_configurator_temperature_tip);
        temperatureText.setMessage(AIUIMessages.ai_engine_configurator_temperature_default);
        temperatureText.addVerifyListener(UIUtils.getNumberVerifyListener(Locale.getDefault()));
        temperatureText.addModifyListener((e) -> temperature = temperatureText.getText());
    }

    private void createAdditionalSettings(@NotNull Composite parent) {
        createAdvancedSettings(parent);
    }

    private void applySettings() {
        temperatureText.setText(temperature);
    }

    protected void createConnectionParameters(@NotNull Composite parent) {

        accessTokenText = UIUtils.createLabelText(
            parent,
            CopilotMessages.copilot_access_token,
            "",
            SWT.BORDER | SWT.PASSWORD
        );
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.widthHint = 150;
        accessTokenText.setLayoutData(gd);
        accessTokenText.addModifyListener(e -> {
            if (!isUpdatingEnvironmentControls()) {
                accessToken = accessTokenText.getText();
            }
        });
        accessTokenText.setMessage(CopilotMessages.copilot_preference_page_token_info);
        authorizeButton = UIUtils.createDialogButton(
            parent, CopilotMessages.copilot_access_token_authorize, SelectionListener.widgetSelectedAdapter(e -> {
            try {
                accessToken = UIUtils.runWithDialog(monitor -> {
                    var future = new CompletableFuture<Void>();
                    try {
                        return acquireAccessToken(monitor, future);
                    } finally {
                        future.complete(null);
                    }
                });
            } catch (DBException ex) {
                DBWorkbench.getPlatformUI().showError(
                    CopilotMessages.oauth_auth_title,
                    NLS.bind(CopilotMessages.oauth_auth_error_message, ex.getMessage()),
                    ex
                );
                return;
            }

            UIUtils.showMessageBox(
                UIUtils.getActiveShell(),
                CopilotMessages.oauth_auth_title,
                CopilotMessages.oauth_auth_success_message,
                SWT.ICON_INFORMATION
            );
            if (accessTokenText != null && !accessTokenText.isDisposed()) {
                accessTokenText.setText(accessToken);
                accessTokenText = UIUtils.recreateTextControl(accessTokenText, SWT.BORDER);
            }
            modelSelectorField.refreshModelListSilently(true);
        }));
    }

    @NotNull
    private String acquireAccessToken(@NotNull DBRProgressMonitor monitor, @NotNull CompletableFuture<Void> future) throws DBException {
        var service = DBWorkbench.getService(UIServiceAuth.class);
        if (service == null) {
            throw new DBException("No authentication service available");
        }
        try (var client = new CopilotClientChat(getCurrentAuthURL())) {
            monitor.subTask("Requesting device code");
            var deviceCodeResponse = client.requestDeviceCode(monitor);

            service.showCodePopup(URI.create(deviceCodeResponse.verificationUri()), deviceCodeResponse.userCode(), future);

            monitor.subTask("Awaiting access token");
            return client.requestAccessToken(monitor, deviceCodeResponse, future);
        } catch (InterruptedException e) {
            throw new DBException("Authorization was interrupted", e);
        }
    }

    @NotNull
    protected String getCurrentAuthURL() {
        return createConnectionProperties().getEffectiveBaseAuthUrl();
    }

    @NotNull
    @Override
    public Optional<AIEngineProperties> getCurrentProperties() {
        CopilotProperties copilotPropertiesCopy = new CopilotProperties();
        saveSettings((PROPERTIES) copilotPropertiesCopy);
        return Optional.of(copilotPropertiesCopy);
    }

    @NotNull
    protected CopilotCompletionEngine createEngine() {
        CopilotProperties properties = new CopilotProperties();
        saveAdvancedSettings(properties);
        properties.setToken(accessToken);

        return new CopilotCompletionEngine(properties);
    }

    @NotNull
    private CopilotProperties createConnectionProperties() {
        CopilotProperties currentProperties = new CopilotProperties();
        currentProperties.setUseEnvVariables(useEnvVariables);
        currentProperties.setToken(accessToken);
        return currentProperties;
    }

    @Override
    protected void updateEnvironmentControls() {
        String environmentToken = getEnvironmentValue(CopilotProperties.ENV_TOKEN, CopilotProperties.ENV_GH_TOKEN,
            CopilotProperties.ENV_GITHUB_TOKEN);
        updateEnvironmentText(accessTokenText, accessToken, environmentToken);
        authorizeButton.setEnabled(environmentToken == null);
        CopilotProperties currentProperties = createConnectionProperties();
        modelSelectorField.setEnvironmentModel(
            currentProperties.isModelSelectionSupported() ? null : currentProperties.getEffectiveModel());
    }

    @Override
    protected void environmentSettingsChanged() {
        super.environmentSettingsChanged();
        modelSelectorField.refreshModelListSilently(true);
    }
}
