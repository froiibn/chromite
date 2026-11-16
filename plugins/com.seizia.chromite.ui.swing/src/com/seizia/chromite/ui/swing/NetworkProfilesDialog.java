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

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import com.seizia.chromite.model.net.DBWNetworkProfile;
import com.seizia.chromite.model.net.DBWNetworkProfileManager;
import com.seizia.chromite.model.net.DBWNetworkProfileUsageProvider;
import com.seizia.chromite.model.secret.DBSSecretController;
import com.seizia.chromite.registry.DataSourceRegistry;
import com.seizia.chromite.registry.GlobalNetworkProfileManager;
import com.seizia.chromite.registry.network.NetworkHandlerDescriptor;
import com.seizia.chromite.registry.network.NetworkHandlerRegistry;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 네트워크 프로필 편집 대화상자(Tools &gt; Network Profiles…, 연결 대화상자의 Manage…).
 *
 * 설계: SWT {@code PrefPageNetworkProfiles}(전역 {@code PrefPageGlobalProjectNetworkProfiles}, 프로젝트 {@code PrefPageProjectNetworkProfiles})와
 * 같은 모델 동작이다. 네트워크 프로필은 여러 연결이 함께 쓰는 SSH 터널·프록시 설정 묶음이다.
 * <ul>
 *     <li>범위: 전역(작업 공간의 모든 프로젝트, {@link GlobalNetworkProfileManager}, {@code network-profiles.json})과 현재 프로젝트
 *     (프로젝트의 연결 설정 파일). 프로젝트 연결은 두 범위의 프로필을 모두 쓸 수 있다({@code getAllProfiles}).</li>
 *     <li>탭: 드라이버와 관계없는 핸들러(SSH, Proxy) 중 Swing 칸이 있는 것({@link NetworkHandlerPanel#create}). SWT도 {@code hasObjectTypes}가
 *     없는 핸들러만 보였다. 탭마다 "Use …" 확인란이 그 핸들러를 프로필에서 켠다.</li>
 *     <li>만들기·복사·삭제: 이름은 범위 안에서 겹치지 않아야 한다. 연결이 쓰는 프로필은 지우지 않고 쓰는 연결을 알린다(SWT {@code canDeleteProfile}).</li>
 *     <li>OK: 바꾼 프로필을 관리자에 넣고({@code addOrUpdateProfile}) 지운 것을 빼고 저장한다({@code saveSettings}). 프로젝트가 보안 저장소를 쓰면
 *     SWT처럼 프로필 비밀번호를 프로젝트 비밀 저장소에 쓴다. 전역 관리자는 저장할 때 스스로 비밀 정보를 쓴다. Cancel은 아무것도 바꾸지 않는다.</li>
 *     <li>프로필을 바꾸면 그 프로필을 쓰는 연결은 다음 연결부터 바뀐 값을 쓴다(열린 세션은 그대로).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-conn-advanced.md "네트워크 프로필")
 */
final class NetworkProfilesDialog extends JDialog {

    private static final Log log = Log.getLog(NetworkProfilesDialog.class);

    /**
     * One scope: its manager, the edited copies of its profiles and the names removed in this dialog.
     */
    private static final class Scope {
        final String title;
        @Nullable
        final DBPProject project;
        final DBWNetworkProfileManager manager;
        final List<DBWNetworkProfile> profiles = new ArrayList<>();
        final Set<String> removed = new HashSet<>();

        Scope(@NotNull String title, @Nullable DBPProject project, @NotNull DBWNetworkProfileManager manager) {
            this.title = title;
            this.project = project;
            this.manager = manager;
            for (DBWNetworkProfile profile : manager.getProfiles()) {
                profiles.add(copyOf(profile, project, profile.getProfileName()));
            }
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private final List<Scope> scopes = new ArrayList<>();
    private final JComboBox<Scope> scopeCombo = new JComboBox<>();
    private final DefaultListModel<DBWNetworkProfile> profilesModel = new DefaultListModel<>();
    private final JList<DBWNetworkProfile> profilesList = new JList<>(profilesModel);
    private final JTabbedPane handlerTabs = new JTabbedPane();
    private final Map<NetworkHandlerDescriptor, NetworkHandlerPanel> panels = new LinkedHashMap<>();
    private final Map<NetworkHandlerDescriptor, JCheckBox> useChecks = new LinkedHashMap<>();
    private final JButton copyButton = new JButton(ConnectionMessages.profiles_copy);
    private final JButton deleteButton = new JButton(ConnectionMessages.common_remove);
    @Nullable
    private DBWNetworkProfile shownProfile;
    private boolean accepted;

    private NetworkProfilesDialog(@Nullable Window owner, @Nullable DBPProject project) {
        super(owner, ConnectionMessages.profiles_title, ModalityType.APPLICATION_MODAL);
        scopes.add(new Scope(ConnectionMessages.profiles_scope_global, null, DBWorkbench.getPlatform().getNetworkProfiles()));
        if (project != null) {
            scopes.add(new Scope(NLS.bind(ConnectionMessages.profiles_scope_project, project.getDisplayName()), project,
                project.getDataSourceRegistry().getNetworkProfiles()));
        }
        scopes.forEach(scopeCombo::addItem);
        // The project's profiles first when there is a project: connections are created in it
        scopeCombo.setSelectedIndex(scopes.size() - 1);
        scopeCombo.addActionListener(e -> showScope());

        for (NetworkHandlerDescriptor descriptor : NetworkHandlerRegistry.getInstance().getDescriptors()) {
            NetworkHandlerPanel panel = descriptor.hasObjectTypes() ? null : NetworkHandlerPanel.create(descriptor, () -> { });
            if (panel == null) {
                continue;
            }
            JCheckBox useCheck = new JCheckBox(NLS.bind(ConnectionMessages.network_use_handler, descriptor.getCodeName()));
            useCheck.addActionListener(e -> panel.setFieldsEnabled(useCheck.isSelected()));
            JPanel page = new JPanel(new BorderLayout());
            JPanel header = new JPanel(new BorderLayout());
            header.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));
            header.add(useCheck, BorderLayout.NORTH);
            page.add(header, BorderLayout.NORTH);
            JScrollPane scrollPane = new JScrollPane(panel.getComponent(),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            page.add(scrollPane, BorderLayout.CENTER);
            handlerTabs.addTab(descriptor.getCodeName(), page);
            panels.put(descriptor, panel);
            useChecks.put(descriptor, useCheck);
        }
        handlerTabs.setPreferredSize(new Dimension(560, 420));

        profilesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profilesList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, value instanceof DBWNetworkProfile profile ? profile.getProfileName() : value,
                    index, isSelected, cellHasFocus);
            }
        });
        profilesList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showProfile(profilesList.getSelectedValue());
            }
        });
        JButton addButton = new JButton(ConnectionMessages.common_add);
        addButton.addActionListener(e -> addProfile(null));
        copyButton.addActionListener(e -> addProfile(profilesList.getSelectedValue()));
        deleteButton.addActionListener(e -> deleteProfile());
        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        listButtons.add(addButton);
        listButtons.add(copyButton);
        listButtons.add(deleteButton);

        JPanel left = new JPanel(new BorderLayout(0, 6));
        JPanel scopePanel = new JPanel(new BorderLayout(6, 0));
        scopePanel.add(new JLabel(ConnectionMessages.profiles_scope), BorderLayout.WEST);
        scopePanel.add(scopeCombo, BorderLayout.CENTER);
        left.add(scopePanel, BorderLayout.NORTH);
        JScrollPane listScroll = new JScrollPane(profilesList);
        listScroll.setPreferredSize(new Dimension(220, 300));
        left.add(listScroll, BorderLayout.CENTER);
        left.add(listButtons, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(12, 0));
        center.add(left, BorderLayout.WEST);
        center.add(handlerTabs, BorderLayout.CENTER);

        JButton okButton = new JButton(ConnectionMessages.common_ok);
        JButton cancelButton = new JButton(ConnectionMessages.common_cancel);
        okButton.addActionListener(e -> {
            if (save()) {
                accepted = true;
                dispose();
            }
        });
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(okButton);
        buttons.add(cancelButton);
        buttons.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0));

        JPanel content = new JPanel(new BorderLayout());
        // Dialog insets of docs/chromite/design-spec.md "11. 대화상자" (10, 12)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(center, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        showScope();
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Opens the editor for the global profiles and the project's ones; {@code true} when the profiles were saved.
     */
    static boolean open(@Nullable Component parent, @Nullable DBPProject project) {
        Window owner = parent == null ? null : parent instanceof Window window ? window : SwingUtilities.getWindowAncestor(parent);
        NetworkProfilesDialog dialog = new NetworkProfilesDialog(owner, project);
        dialog.setVisible(true);
        return dialog.accepted;
    }

    @NotNull
    private Scope getScope() {
        return scopes.get(Math.max(0, scopeCombo.getSelectedIndex()));
    }

    private void showScope() {
        keepShownProfile();
        shownProfile = null;
        profilesModel.clear();
        getScope().profiles.forEach(profilesModel::addElement);
        if (!profilesModel.isEmpty()) {
            profilesList.setSelectedIndex(0);
        } else {
            showProfile(null);
        }
    }

    /**
     * Shows a profile's handler settings; first keeps what was typed for the previously shown one.
     */
    private void showProfile(@Nullable DBWNetworkProfile profile) {
        if (profile == shownProfile && profile != null) {
            return;
        }
        keepShownProfile();
        shownProfile = profile;
        for (Map.Entry<NetworkHandlerDescriptor, NetworkHandlerPanel> entry : panels.entrySet()) {
            NetworkHandlerDescriptor descriptor = entry.getKey();
            DBWHandlerConfiguration configuration = profile == null ? null : profile.getConfiguration(descriptor);
            if (configuration == null) {
                configuration = new DBWHandlerConfiguration(descriptor, null);
            }
            entry.getValue().loadSettings(configuration);
            JCheckBox useCheck = useChecks.get(descriptor);
            useCheck.setSelected(profile != null && configuration.isEnabled());
            useCheck.setEnabled(profile != null);
            entry.getValue().setFieldsEnabled(profile != null && configuration.isEnabled());
        }
        copyButton.setEnabled(profile != null);
        deleteButton.setEnabled(profile != null);
    }

    /**
     * Writes the tabs into the shown profile's copy (PrefPageNetworkProfiles.saveHandlerSettings): a used handler with
     * its fields, an unused one is kept only as "off".
     */
    private void keepShownProfile() {
        DBWNetworkProfile profile = shownProfile;
        if (profile == null) {
            return;
        }
        for (Map.Entry<NetworkHandlerDescriptor, NetworkHandlerPanel> entry : panels.entrySet()) {
            NetworkHandlerDescriptor descriptor = entry.getKey();
            boolean used = useChecks.get(descriptor).isSelected();
            DBWHandlerConfiguration existing = profile.getConfiguration(descriptor);
            if (!used && existing == null) {
                continue;
            }
            DBWHandlerConfiguration configuration = existing == null
                ? new DBWHandlerConfiguration(descriptor, null) : new DBWHandlerConfiguration(existing);
            configuration.setEnabled(used);
            if (used) {
                entry.getValue().saveSettings(configuration);
            }
            profile.updateConfiguration(configuration);
        }
    }

    private void addProfile(@Nullable DBWNetworkProfile source) {
        Scope scope = getScope();
        String name = source == null ? "" : source.getProfileName(); //$NON-NLS-1$
        while (true) {
            name = (String) JOptionPane.showInputDialog(this, ConnectionMessages.profiles_name, ConnectionMessages.profiles_title,
                JOptionPane.PLAIN_MESSAGE, null, null, name);
            if (name == null || name.isBlank()) {
                return;
            }
            name = name.trim();
            String candidate = name;
            if (scope.profiles.stream().noneMatch(profile -> profile.getProfileName().equals(candidate))) {
                break;
            }
            JOptionPane.showMessageDialog(this, NLS.bind(ConnectionMessages.profiles_name_used, name), ConnectionMessages.profiles_title,
                JOptionPane.WARNING_MESSAGE);
        }
        keepShownProfile();
        DBWNetworkProfile profile = source == null ? newProfile(scope.project, name) : copyOf(source, scope.project, name);
        scope.removed.remove(name);
        scope.profiles.add(profile);
        profilesModel.addElement(profile);
        profilesList.setSelectedValue(profile, true);
    }

    private void deleteProfile() {
        DBWNetworkProfile profile = profilesList.getSelectedValue();
        if (profile == null) {
            return;
        }
        Scope scope = getScope();
        List<String> usedBy = findConnections(scope, profile);
        if (!usedBy.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                NLS.bind(ConnectionMessages.profiles_delete_used, profile.getProfileName(), String.join("\n", usedBy)), //$NON-NLS-1$
                ConnectionMessages.profiles_title, JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Remove / Cancel, Cancel is the default (the removal is saved with OK)
        Object[] options = {ConnectionMessages.common_remove, ConnectionMessages.common_cancel};
        if (JOptionPane.showOptionDialog(this, NLS.bind(ConnectionMessages.profiles_delete_confirm, profile.getProfileName()),
            ConnectionMessages.profiles_title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]) != 0) {
            return;
        }
        shownProfile = null;
        scope.profiles.remove(profile);
        scope.removed.add(profile.getProfileName());
        profilesModel.removeElement(profile);
        if (!profilesModel.isEmpty()) {
            profilesList.setSelectedIndex(0);
        } else {
            showProfile(null);
        }
    }

    /**
     * Names of the connections that use the profile (" - name" lines).
     */
    @NotNull
    private static List<String> findConnections(@NotNull Scope scope, @NotNull DBWNetworkProfile profile) {
        List<String> names = new ArrayList<>();
        try {
            if (scope.project != null && scope.project.getDataSourceRegistry() instanceof DataSourceRegistry<?> registry) {
                for (DBPDataSourceContainer container : registry.getDataSourcesByProfile(profile)) {
                    names.add(" - " + container.getName()); //$NON-NLS-1$
                }
            } else if (scope.manager instanceof GlobalNetworkProfileManager global) {
                for (DBWNetworkProfileUsageProvider.ProjectConnections project : global.findGlobalProfileConnections(profile.getProfileName())) {
                    for (String connection : project.connections().values()) {
                        names.add(" - " + CommonUtils.notEmpty(project.projectName()) + ": " + connection); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }
        } catch (DBException e) {
            log.warn("Can't find the connections of network profile " + profile.getProfileName(), e);
        }
        return names;
    }

    /**
     * Saves every scope (PrefPageManagedNetworkProfiles.performOk); {@code false} keeps the dialog open after an error.
     */
    private boolean save() {
        keepShownProfile();
        try {
            for (Scope scope : scopes) {
                for (DBWNetworkProfile profile : new ArrayList<>(scope.manager.getProfiles())) {
                    if (scope.removed.contains(profile.getProfileName())) {
                        scope.manager.removeProfile(profile);
                    }
                }
                for (DBWNetworkProfile profile : scope.profiles) {
                    if (scope.project != null && scope.project.isUseSecretStorage() && !DBWorkbench.isDistributed()) {
                        // PrefPageProjectNetworkProfiles.persistProfileSecrets
                        profile.persistSecrets(DBSSecretController.getProjectSecretController(scope.project));
                    }
                    scope.manager.addOrUpdateProfile(profile);
                }
                scope.manager.saveSettings();
            }
            return true;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ConnectionMessages.profiles_title, ConnectionMessages.profiles_save_error, e);
            return false;
        }
    }

    @NotNull
    private static DBWNetworkProfile newProfile(@Nullable DBPProject project, @NotNull String name) {
        DBWNetworkProfile profile = project == null ? new DBWNetworkProfile() : new DBWNetworkProfile(project);
        profile.setProfileName(name);
        return profile;
    }

    /**
     * A copy to edit: the manager's profile is replaced only when the dialog is saved.
     */
    @NotNull
    private static DBWNetworkProfile copyOf(@NotNull DBWNetworkProfile source, @Nullable DBPProject project, @NotNull String name) {
        DBWNetworkProfile profile = newProfile(project, name);
        if (CommonUtils.equalObjects(source.getProfileName(), name)) {
            profile.setProfileId(source.getProfileId());
            profile.setProfileDescription(source.getProfileDescription());
        }
        for (DBWHandlerConfiguration configuration : source.getConfigurations()) {
            profile.getConfigurations().add(new DBWHandlerConfiguration(configuration));
        }
        return profile;
    }
}
