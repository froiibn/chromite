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
package com.seizia.chromite.ui.swing;

import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.connection.DBPDriverSubstitutionDescriptor;
import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import com.seizia.chromite.model.net.DBWNetworkProfile;
import com.seizia.chromite.registry.DataSourceDescriptor;
import com.seizia.chromite.registry.network.NetworkHandlerDescriptor;
import com.seizia.chromite.registry.network.NetworkHandlerRegistry;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Connect 대화상자 Options의 네트워크 핸들러 탭 하나(SSH, Proxy, SSL): 맨 위 "Use …" 확인란과 핸들러 설정 칸({@link NetworkHandlerPanel}).
 *
 * 설계: SWT 연결 화면의 핸들러 페이지({@code ConnectionPageNetworkHandler})와 탭 목록({@code ConnectionPageSettings.getDialogPages})을 따른다.
 * <ul>
 *     <li>탭 목록: {@link NetworkHandlerRegistry#getDescriptors(DBPDriver)}(대체된 핸들러 제외, 드라이버에 맞는 것, SSH는 작업 공간이
 *     터널을 허용할 때만). 내장 DB 드라이버는 {@code enableNetworkParameters}가 없으면, 그리고 {@code disableNetworkParameters} 드라이버는 탭을 만들지 않는다.
 *     드라이버 대체(substitution)가 지원하지 않는 핸들러도 뺀다. Swing 칸이 없는 핸들러는 탭이 없다({@link NetworkHandlerPanel#create}).</li>
 *     <li>읽기: 연결 설정의 핸들러 설정(없으면 새 설정)을 칸에 채운다. 확인란은 그 설정의 {@code enabled}다.</li>
 *     <li>저장: 켜져 있으면 연결의 핸들러 설정 사본(없으면 새 설정)에 칸 값을 쓰고 {@code updateHandler}, 꺼져 있으면 {@code removeHandler}
 *     ({@code ConnectionPageNetworkHandler.saveSettings}와 같음. 연결 레지스트리도 꺼진 핸들러는 저장하지 않는다).</li>
 *     <li>네트워크 프로필: 연결이 프로필을 쓰고 그 프로필에 이 핸들러가 켜져 있으면 SWT처럼 칸을 끄고 프로필 이름을 보인다.
 *     이때는 연결의 핸들러 설정을 건드리지 않는다. 대화상자에서 프로필을 바꾸면 그 프로필로 다시 읽는다. 프로필 편집은 {@link NetworkProfilesDialog}.</li>
 *     <li>확인: 켜져 있을 때만 칸의 {@code getErrorMessage}를 대화상자의 빠진 값 안내로 올린다(SWT {@code isPageComplete}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 고급 연결 설정 (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 대화상자에서 고른 네트워크 프로필로 다시 읽기({@link #loadSettings(DataSourceDescriptor, DBWNetworkProfile)})
 *   (docs/chromite/swing-conn-advanced.md "네트워크 프로필")
 */
final class ConnectionNetworkPage extends JPanel {

    private final NetworkHandlerDescriptor descriptor;
    private final NetworkHandlerPanel handlerPanel;
    private final JCheckBox enableCheck;
    private final JLabel profileLabel = new JLabel();
    private boolean profileProvided;

    private ConnectionNetworkPage(
        @NotNull NetworkHandlerDescriptor descriptor,
        @NotNull NetworkHandlerPanel handlerPanel,
        @NotNull Runnable changeListener
    ) {
        super(new BorderLayout());
        this.descriptor = descriptor;
        this.handlerPanel = handlerPanel;
        // The short name, as on the tab (SSH, Proxy, SSL)
        enableCheck = new JCheckBox(NLS.bind(ConnectionMessages.network_use_handler, descriptor.getCodeName()));
        enableCheck.setToolTipText(descriptor.getDescription());
        enableCheck.addActionListener(e -> {
            handlerPanel.setFieldsEnabled(enableCheck.isSelected());
            changeListener.run();
        });
        profileLabel.setForeground(ConnectionDialog.getThemeColor("Chromite.secondaryForeground", "Label.disabledForeground", Color.GRAY)); //$NON-NLS-1$ //$NON-NLS-2$
        profileLabel.setVisible(false);

        JPanel header = new JPanel(new BorderLayout(0, 4));
        // Page insets of the other option tabs (10, 12); the form below has its own
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));
        header.add(enableCheck, BorderLayout.NORTH);
        header.add(profileLabel, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(handlerPanel.getComponent(),
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * The handler tabs for a connection, in the handlers' order (SSH, SSL, Proxy).
     */
    @NotNull
    static List<ConnectionNetworkPage> createPages(@NotNull DataSourceDescriptor dataSource, @NotNull Runnable changeListener) {
        List<ConnectionNetworkPage> pages = new ArrayList<>();
        DBPDriver driver = dataSource.getDriver();
        // Same condition as ConnectionPageSettings.getDialogPages
        boolean networkParameters = (!driver.isEmbedded()
            || CommonUtils.toBoolean(driver.getDriverParameter(DBConstants.DRIVER_PARAM_ENABLE_NETWORK_PARAMETERS)))
            && !CommonUtils.toBoolean(driver.getDriverParameter(DBConstants.DRIVER_PARAM_DISABLE_NETWORK_PARAMETERS));
        if (!networkParameters) {
            return pages;
        }
        DBPDriverSubstitutionDescriptor substitution = dataSource.getDriverSubstitution();
        for (NetworkHandlerDescriptor descriptor : NetworkHandlerRegistry.getInstance().getDescriptors(driver)) {
            if (substitution != null && !substitution.getInstance().isNetworkHandlerSupported(descriptor)) {
                continue;
            }
            NetworkHandlerPanel panel = NetworkHandlerPanel.create(descriptor, changeListener);
            if (panel != null) {
                pages.add(new ConnectionNetworkPage(descriptor, panel, changeListener));
            }
        }
        return pages;
    }

    /**
     * Tab title: the handler's short name (SSH, Proxy, SSL).
     */
    @NotNull
    String getTitle() {
        return descriptor.getCodeName();
    }

    /**
     * The handler is (or will be) used by the connection; the tab shows a check mark.
     */
    boolean isHandlerEnabled() {
        return enableCheck.isSelected();
    }

    void loadSettings(@NotNull DataSourceDescriptor dataSource) {
        loadSettings(dataSource, getActiveProfile(dataSource));
    }

    /**
     * Loads the tab for a connection that uses the given network profile (the one chosen in the dialog): when the profile
     * turns this handler on, the profile's settings are shown read-only and the connection's own ones are kept.
     */
    void loadSettings(@NotNull DataSourceDescriptor dataSource, @Nullable DBWNetworkProfile profile) {
        DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
        DBWHandlerConfiguration profileConfiguration = profile == null ? null : profile.getConfiguration(descriptor);
        profileProvided = profileConfiguration != null && profileConfiguration.isEnabled();
        DBWHandlerConfiguration handlerConfiguration;
        if (profileProvided) {
            handlerConfiguration = new DBWHandlerConfiguration(profileConfiguration);
            profileLabel.setText(NLS.bind(ConnectionMessages.network_profile, profile.getProfileName()));
        } else {
            handlerConfiguration = configuration.getHandler(descriptor.getId());
            if (handlerConfiguration == null) {
                handlerConfiguration = new DBWHandlerConfiguration(descriptor, dataSource);
            }
        }
        profileLabel.setVisible(profileProvided);
        handlerPanel.loadSettings(handlerConfiguration);
        enableCheck.setSelected(handlerConfiguration.isEnabled());
        enableCheck.setEnabled(!profileProvided);
        handlerPanel.setFieldsEnabled(handlerConfiguration.isEnabled() && !profileProvided);
    }

    void saveSettings(@NotNull DataSourceDescriptor target) {
        if (profileProvided) {
            return;
        }
        DBPConnectionConfiguration configuration = target.getConnectionConfiguration();
        if (!enableCheck.isSelected()) {
            configuration.removeHandler(descriptor.getId());
            return;
        }
        DBWHandlerConfiguration existing = configuration.getHandler(descriptor.getId());
        DBWHandlerConfiguration handlerConfiguration = existing != null
            ? new DBWHandlerConfiguration(existing) : new DBWHandlerConfiguration(descriptor, target);
        handlerConfiguration.setEnabled(true);
        handlerPanel.saveSettings(handlerConfiguration);
        configuration.updateHandler(handlerConfiguration);
    }

    /**
     * Why the enabled handler can't be used yet, or {@code null}.
     */
    @Nullable
    String getErrorMessage() {
        if (profileProvided || !enableCheck.isSelected()) {
            return null;
        }
        String problem = handlerPanel.getErrorMessage();
        return problem == null ? null : descriptor.getCodeName() + ": " + problem;
    }

    /**
     * The handler's short name for the connection test result ("SSH", "SSL"), or {@code null} when not used.
     */
    @Nullable
    String describeUsage() {
        return enableCheck.isSelected() || profileProvided ? descriptor.getCodeName() : null;
    }

    @Nullable
    static DBWNetworkProfile getActiveProfile(@NotNull DataSourceDescriptor dataSource) {
        DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
        if (CommonUtils.isEmpty(configuration.getConfigProfileName())) {
            return null;
        }
        return dataSource.getRegistry().getNetworkProfiles().getProfile(
            configuration.getConfigProfileSource(), configuration.getConfigProfileName());
    }
}
