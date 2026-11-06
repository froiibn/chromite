/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ui.net.ssh;

import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Widget;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import com.seizia.chromite.model.net.ssh.SSHUtils;
import com.seizia.chromite.model.net.ssh.config.SSHHostConfiguration;
import com.seizia.chromite.registry.configurator.DBPConnectionEditIntention;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.IObjectPropertyConfigurator;
import com.seizia.chromite.ui.UIUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class SSHTunnelCredsOnlyConfiguratorUI implements IObjectPropertyConfigurator<Object, DBWHandlerConfiguration> {
    private List<SSHTunnelDefaultConfiguratorUI.CredentialsPanel> credPanels;
    private Composite credPanelsContainer;
    private Runnable propertyChangeListener;

    @Override
    public void createControl(@NotNull Composite parent, Object object, @NotNull Runnable propertyChangeListener) {
        this.credPanelsContainer = UIUtils.createTitledComposite(parent, "SSH tunnel credentials", 1, GridData.FILL_HORIZONTAL);
        this.propertyChangeListener = propertyChangeListener;
    }

    @Override
    public void loadSettings(@NotNull DBWHandlerConfiguration handlerConfiguration) {
        List<SSHTunnelDefaultConfiguratorUI.ConfigurationWrapper> configurations;
        try {
            configurations = Arrays.stream(SSHUtils.loadHostConfigurations(handlerConfiguration, false))
                .map(SSHTunnelDefaultConfiguratorUI.ConfigurationWrapper::new).toList();
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError("SSH configuration", "Unable to load SSH configuration due to an error", e);
            return;
        }
        Arrays.stream(this.credPanelsContainer.getChildren()).forEach(Widget::dispose);

        this.credPanels = new ArrayList<>();
        for (SSHTunnelDefaultConfiguratorUI.ConfigurationWrapper cfg : configurations) {
            SSHTunnelDefaultConfiguratorUI.CredentialsPanel credsPanel = new SSHTunnelDefaultConfiguratorUI.CredentialsPanel(
                this.credPanelsContainer,
                this.propertyChangeListener,
                DBPConnectionEditIntention.CREDENTIALS_ONLY
            );
            this.credPanels.add(credsPanel);
            credsPanel.loadSettings(cfg, false);
        }
        this.credPanelsContainer.pack(true);
    }

    @Override
    public void saveSettings(@NotNull DBWHandlerConfiguration handlerConfiguration) {
        SSHUtils.saveHostConfigurations(
            handlerConfiguration,
            this.credPanels.stream().map(SSHTunnelDefaultConfiguratorUI.CredentialsPanel::saveSettings).toArray(SSHHostConfiguration[]::new)
        );
    }

    @Override
    public void resetSettings(@NotNull DBWHandlerConfiguration handlerConfiguration) {

    }

    @Override
    public boolean isComplete() {
        return this.credPanels.stream().map(SSHTunnelDefaultConfiguratorUI.CredentialsPanel::saveSettings)
            .map(SSHTunnelDefaultConfiguratorUI::validateConfiguration)
            .allMatch(Objects::isNull);
    }
}