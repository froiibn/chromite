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
package org.jkiss.dbeaver.ui.ai.preferences;

import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.forms.events.ExpansionAdapter;
import org.eclipse.ui.forms.events.ExpansionEvent;
import org.eclipse.ui.forms.widgets.ExpandableComposite;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.ai.AIConstants;
import org.jkiss.dbeaver.model.ai.engine.AIEngineProperties;
import org.jkiss.dbeaver.model.ai.engine.AIModel;
import org.jkiss.dbeaver.model.ai.engine.BaseAIEngineProperties;
import org.jkiss.dbeaver.model.ai.registry.AIEngineDescriptor;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.ai.internal.AIUIMessages;
import org.jkiss.dbeaver.ui.ai.model.ContextWindowSizeField;
import org.jkiss.utils.CommonUtils;

import java.util.Locale;

/**
 * Base class for AI engine settings configurators.
 *
 * 설계: 모델을 고르면 제공자 모델 목록이 준 컨텍스트 크기를 기본값으로 보여 준다. 제공자가 크기를 주지 않으면
 * OpenAIProperties.selectModel()과 같은 보수적 기본값 AIConstants.DEFAULT_CONTEXT_WINDOW_SIZE를 보여 준다.
 * 사용자는 이 값을 고칠 수 있다.
 *
 * 변경 이력:
 * - 2026-11-06 dbeaver.com AI 모델 카탈로그 제거: 카탈로그가 채우던 컨텍스트 크기 대신, 목록에 크기가 없는 모델에는
 *   AIConstants.DEFAULT_CONTEXT_WINDOW_SIZE를 기본값으로 보여 준다(공식 OpenAI API의 모델 목록은 크기를 주지 않는다).
 */
public abstract class AbstractAIEngineConfigurator<ENGINE extends AIEngineDescriptor, PROPERTIES extends AIEngineProperties>
    implements AIIObjectPropertyConfigurator<ENGINE, PROPERTIES> {

    @Nullable
    private Text timeoutText;
    @Nullable
    private Button logQueryCheck;

    @Nullable
    protected Button useEnvVariablesCheck;
    protected volatile boolean useEnvVariables;

    private Composite advancedSettingsClient;
    private boolean updatingEnvironmentControls;

    private int timeout = AIEngineProperties.DEFAULT_TIMEOUT;
    private boolean logQuery;

    protected void saveTemperature(@NotNull BaseAIEngineProperties configuration, @NotNull String temperature) {
        if (temperature.isBlank()) {
            configuration.clearTemperature();
        } else {
            configuration.setTemperature(CommonUtils.toDouble(temperature, 0.0));
        }
    }

    protected void updateModelParameters(
        @Nullable AIModel model,
        @NotNull Text temperatureText,
        @Nullable ContextWindowSizeField contextField,
        boolean modelChanged
    ) {
        if (modelChanged) {
            if (contextField != null) {
                contextField.setValue(null);
            }
            temperatureText.setText(String.valueOf(model == null ? 0.0 : model.defaultTemperature()));
        }
        temperatureText.setEnabled(model == null || model.isTemperatureEditable());
        if (contextField != null && model != null) {
            Integer contextSize = model.contextWindowSize();
            contextField.setDefaultValue(contextSize != null ? contextSize : AIConstants.DEFAULT_CONTEXT_WINDOW_SIZE);
        }
    }

    protected void createEnvironmentSettings(@NotNull Runnable propertyChangeListener) {
        useEnvVariablesCheck = UIUtils.createCheckbox(
            advancedSettingsClient,
            AIUIMessages.ai_engine_configurator_use_env,
            AIUIMessages.ai_engine_configurator_use_env_tip,
            false,
            2
        );
        useEnvVariablesCheck.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> {
            useEnvVariables = useEnvVariablesCheck.getSelection();
            environmentSettingsChanged();
            propertyChangeListener.run();
        }));
    }

    protected void environmentSettingsChanged() {
        updateEnvironmentControls();
    }

    protected void updateEnvironmentControls() {
    }

    protected boolean isUpdatingEnvironmentControls() {
        return updatingEnvironmentControls;
    }

    @Nullable
    protected String getEnvironmentValue(@NotNull String... names) {
        if (useEnvVariables) {
            for (String name : names) {
                String value = System.getenv(name);
                if (!CommonUtils.isEmptyTrimmed(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    protected void updateEnvironmentText(@Nullable Text control, @Nullable String configuredValue, @Nullable String environmentValue) {
        if (control == null || control.isDisposed()) {
            return;
        }
        boolean wasUpdating = updatingEnvironmentControls;
        updatingEnvironmentControls = true;
        try {
            String value = CommonUtils.notEmpty(environmentValue == null ? configuredValue : environmentValue);
            if (!value.equals(control.getText())) {
                control.setText(value);
            }
            control.setEnabled(environmentValue == null);
        } finally {
            updatingEnvironmentControls = wasUpdating;
        }
    }

    protected void updateEnvironmentCombo(@NotNull Combo control, @Nullable String configuredValue, @Nullable String environmentValue) {
        boolean wasUpdating = updatingEnvironmentControls;
        updatingEnvironmentControls = true;
        try {
            String value = CommonUtils.notEmpty(environmentValue == null ? configuredValue : environmentValue);
            if ((control.getStyle() & SWT.READ_ONLY) != 0) {
                if (value.isEmpty()) {
                    control.deselectAll();
                } else {
                    if (control.indexOf(value) < 0) {
                        control.add(value);
                    }
                    control.select(control.indexOf(value));
                }
            } else {
                control.setText(value);
            }
            control.setEnabled(environmentValue == null);
        } finally {
            updatingEnvironmentControls = wasUpdating;
        }
    }

    @NotNull
    protected Composite createAdvancedSettings(@NotNull Composite parent) {
        ExpandableComposite advancedSettings = UIUtils.createExpandableCompositeWithSeparator(
            parent,
            SWT.NONE,
            ExpandableComposite.TWISTIE | ExpandableComposite.COMPACT
        );
        advancedSettings.setText(AIUIMessages.ai_engine_configurator_advanced_settings);
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.horizontalSpan = parent.getLayout() instanceof GridLayout gridLayout ? gridLayout.numColumns : 1;
        advancedSettings.setLayoutData(gd);

        Composite client = UIUtils.createComposite(advancedSettings, 2);
        advancedSettingsClient = client;

        timeoutText = UIUtils.createLabelText(
            client,
            AIUIMessages.ai_engine_configurator_timeout_label,
            String.valueOf(timeout),
            SWT.BORDER
        );
        timeoutText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        timeoutText.setToolTipText(AIUIMessages.ai_engine_configurator_timeout_tip);
        timeoutText.addVerifyListener(UIUtils.getIntegerVerifyListener(Locale.getDefault()));
        timeoutText.addModifyListener(e ->
            timeout = CommonUtils.toInt(timeoutText.getText(), AIEngineProperties.DEFAULT_TIMEOUT));

        logQueryCheck = UIUtils.createCheckbox(
            client,
            AIUIMessages.openai_configurator_log_query_label,
            AIUIMessages.openai_configurator_log_query_tip,
            false,
            2
        );
        logQueryCheck.addSelectionListener(
            SelectionListener.widgetSelectedAdapter(e -> logQuery = logQueryCheck.getSelection()));

        advancedSettings.setClient(client);
        advancedSettings.setExpanded(false);
        advancedSettings.addExpansionListener(new ExpansionAdapter() {
            @Override
            public void expansionStateChanged(@NotNull ExpansionEvent e) {
                parent.getShell().layout(true, true);
            }
        });
        return client;
    }

    protected void loadAdvancedSettings(@NotNull AIEngineProperties configuration) {
        useEnvVariables = configuration.isUseEnvVariables();
        if (useEnvVariablesCheck != null) {
            useEnvVariablesCheck.setSelection(useEnvVariables);
            useEnvVariablesCheck.setToolTipText(NLS.bind(
                AIUIMessages.ai_engine_configurator_use_env_variables_tip,
                String.join(", ", configuration.getEnvironmentVariables())
            ));
        }
        timeout = configuration.getTimeout();
        logQuery = CommonUtils.toBoolean(configuration.isLoggingEnabled());
        applyAdvancedSettings();
    }

    protected void applyAdvancedSettings() {
        if (timeoutText != null && !timeoutText.isDisposed()) {
            timeoutText.setText(String.valueOf(timeout));
        }
        if (logQueryCheck != null && !logQueryCheck.isDisposed()) {
            logQueryCheck.setSelection(logQuery);
        }
    }

    protected void saveAdvancedSettings(@NotNull AIEngineProperties configuration) {
        configuration.setUseEnvVariables(useEnvVariables);
        configuration.setTimeout(timeout);
        configuration.setLoggingEnabled(logQuery);
    }
}
