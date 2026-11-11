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
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.connection.DBPAuthInfo;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.registry.maven.MavenRegistry;
import com.seizia.chromite.registry.maven.MavenRepository;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.net.GlobalProxyAuthenticator;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Tools &gt; Options &gt; Drivers: where driver files come from (SWT {@code PrefPageDrivers} and {@code PrefPageDriversMaven}).
 *
 * 설계:
 * <ul>
 *     <li>드라이버 폴더({@code ui.drivers.home}): {@code DriverDescriptor.getCustomDriversHome}이 드라이버 파일을 찾고 내려받을 때마다 읽는다.
 *     파일 원본({@code ui.drivers.sources}, {@code |}로 나눔): Maven이 아닌 드라이버 파일과 네이티브 클라이언트를 받을 주소
 *     ({@code DriverLibraryRepository}, 첫 줄이 주 원본). 업데이트 뒤 라이브러리 버전({@code ui.drivers.version.update}): 드라이버 설정을 읽을 때
 *     ({@code DriverDescriptorSerializerLegacy}) 저장된 버전이 제품의 버전보다 낮으면 지운다. 이 페이지는 기본값을 바꾸지 않는다(파일 원본의 기본값은
 *     모델에 있다).</li>
 *     <li>프록시({@code ui.proxy.host}, {@code ui.proxy.port}): {@code WebUtils.openConnection}(드라이버 내려받기)과 {@code GlobalProxyAuthenticator}가
 *     읽는다. 사용자 이름과 비밀번호는 설정 파일에 두지 않고 모델의 저장 경로({@link GlobalProxyAuthenticator#saveCredentials}, 보안 저장소)로 쓴다.
 *     예전 설정 파일의 값은 그 경로가 지운다.</li>
 *     <li>Maven 저장소: 키가 아니라 {@code maven-repositories.xml}이다({@link MavenRegistry}). 사본을 고치고 페이지의 [저장]에서 기본 제공 저장소의 사용 여부와 추가한
 *     저장소({@link MavenRepository.RepositoryType#CUSTOM})를 옮긴 뒤 {@link MavenRegistry#saveConfiguration}으로 저장한다(인증 정보는 보안 저장소).</li>
 *     <li>전역 라이브러리({@code ui.drivers.global.libraries})와 드라이버의 Maven 아티팩트는 드라이버 관리자에서 고친다(드라이버 관리자 작업과 겹치지
 *     않게). 여기서는 드라이버 관리자를 여는 단추만 둔다.</li>
 *     <li>모두 플랫폼 저장소에서 읽는 전역 설정이라 연결 대화상자에는 넣지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 * - 2026-11-12 Options 저장 표준: 프록시 인증 정보·저장소 목록의 바뀐 수와 되돌리기, 저장 실패는 대화상자 대신 페이지 아래 줄에,
 *   저장소의 스냅숏 체크 상자는 값(앱 변경 #59, docs/chromite/swing-options.md "옵션 화면 모양과 저장 (#59)")
 */
final class DriversOptionsPage extends SettingsPage {

    private static final Log log = Log.getLog(DriversOptionsPage.class);
    // ui.drivers.sources separates the addresses with '|'
    private static final char SOURCES_SEPARATOR = '|';

    private final JTextField driversHomeField = new JTextField(30);
    private final JTextArea sourcesArea = new JTextArea(3, 30);
    private final JCheckBox versionUpdateBox = new JCheckBox(SettingsMessages.drivers_version_update);
    private final JTextField proxyHostField = new JTextField(20);
    private final JSpinner proxyPortSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
    private final JTextField proxyUserField = new JTextField(20);
    private final JPasswordField proxyPasswordField = new JPasswordField(20);
    private final List<MavenRepository> repositories = new ArrayList<>();
    private final RepositoryTableModel repositoryModel = new RepositoryTableModel();
    private final JTable repositoryTable = new JTable(repositoryModel);
    private final JButton editButton = new JButton(SettingsMessages.drivers_maven_edit);
    private final JButton removeButton = new JButton(SettingsMessages.drivers_maven_remove);
    private String savedProxyUser = "";
    private String savedProxyPassword = "";
    private boolean repositoriesChanged;

    DriversOptionsPage() {
        sourcesArea.setToolTipText(SettingsMessages.drivers_sources_tip);
        versionUpdateBox.setToolTipText(SettingsMessages.drivers_version_update_tip);
        // Port without digit grouping
        proxyPortSpinner.setEditor(new JSpinner.NumberEditor(proxyPortSpinner, "#")); //$NON-NLS-1$
        JButton browseButton = new JButton(SettingsMessages.drivers_browse);
        browseButton.addActionListener(e -> chooseDriversHome());
        JPanel homeRow = new JPanel(new BorderLayout(6, 0));
        homeRow.add(driversHomeField, BorderLayout.CENTER);
        homeRow.add(browseButton, BorderLayout.EAST);

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.drivers_section_files);
        row = OptionsDialog.addRow(form, row, SettingsMessages.drivers_home, homeRow, SettingsMessages.drivers_home_hint);
        row = OptionsDialog.addRow(form, row, SettingsMessages.drivers_sources, new JScrollPane(sourcesArea), null);
        row = OptionsDialog.addRow(form, row, null, versionUpdateBox, null);
        row = OptionsDialog.addSection(form, row, SettingsMessages.drivers_section_proxy);
        row = OptionsDialog.addRow(form, row, SettingsMessages.drivers_proxy_host, proxyHostField, SettingsMessages.drivers_proxy_host_hint);
        row = OptionsDialog.addRow(form, row, SettingsMessages.drivers_proxy_port, proxyPortSpinner, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.drivers_proxy_user, proxyUserField, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.drivers_proxy_password, proxyPasswordField, SettingsMessages.drivers_proxy_hint);
        row = OptionsDialog.addSection(form, row, SettingsMessages.drivers_section_maven);
        row = addWide(form, row, createRepositoriesPanel());
        row = OptionsDialog.addSection(form, row, SettingsMessages.drivers_section_libraries);
        JButton driverManagerButton = new JButton(SettingsMessages.drivers_open_manager);
        driverManagerButton.addActionListener(e -> DriverManagerDialog.open(SwingUtilities.getWindowAncestor(this)));
        // A button of its own width, not stretched to the field column
        JPanel driverManagerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        driverManagerRow.add(driverManagerButton);
        OptionsDialog.addRow(form, row, null, driverManagerRow, SettingsMessages.drivers_libraries_hint);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);

        load(DBWorkbench.getPlatform().getPreferenceStore());
        loadProxyCredentials();
        loadRepositories();
    }

    /**
     * A component over the three form columns (the repository table).
     */
    private static int addWide(@NotNull JPanel form, int row, @NotNull JComponent component) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 6, 0);
        form.add(component, c);
        return row + 1;
    }

    @NotNull
    private JComponent createRepositoriesPanel() {
        repositoryTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        repositoryTable.getSelectionModel().addListSelectionListener(e -> updateButtons());
        repositoryTable.getColumnModel().getColumn(0).setMaxWidth(80);
        JScrollPane scrollPane = new JScrollPane(repositoryTable);
        scrollPane.setPreferredSize(new Dimension(560, 150));
        JButton addButton = new JButton(SettingsMessages.drivers_maven_add);
        addButton.addActionListener(e -> editRepository(null));
        editButton.addActionListener(e -> editRepository(getSelectedRepository()));
        removeButton.addActionListener(e -> removeRepository());
        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        buttons.add(addButton);
        buttons.add(editButton);
        buttons.add(removeButton);
        JPanel buttonsTop = new JPanel(new BorderLayout());
        buttonsTop.add(buttons, BorderLayout.NORTH);
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(buttonsTop, BorderLayout.EAST);
        return panel;
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.drivers_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        return List.of(ModelPreferences.UI_DRIVERS_HOME, ModelPreferences.UI_DRIVERS_SOURCES, ModelPreferences.UI_DRIVERS_VERSION_UPDATE,
            ModelPreferences.UI_PROXY_HOST, ModelPreferences.UI_PROXY_PORT);
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        driversHomeField.setText(store.getString(ModelPreferences.UI_DRIVERS_HOME));
        sourcesArea.setText(String.join("\n", CommonUtils.splitString(store.getString(ModelPreferences.UI_DRIVERS_SOURCES), SOURCES_SEPARATOR)));
        versionUpdateBox.setSelected(store.getBoolean(ModelPreferences.UI_DRIVERS_VERSION_UPDATE));
        proxyHostField.setText(store.getString(ModelPreferences.UI_PROXY_HOST));
        proxyPortSpinner.setValue(Math.clamp(store.getInt(ModelPreferences.UI_PROXY_PORT), 0, 65535));
    }

    @Override
    void save(@NotNull Writer writer) {
        writer.setString(ModelPreferences.UI_DRIVERS_HOME, driversHomeField.getText().trim());
        List<String> sources = Arrays.stream(sourcesArea.getText().split("\\R")) //$NON-NLS-1$
            .map(String::trim).filter(source -> !source.isEmpty()).toList();
        writer.setString(ModelPreferences.UI_DRIVERS_SOURCES, String.join(String.valueOf(SOURCES_SEPARATOR), sources));
        writer.setBoolean(ModelPreferences.UI_DRIVERS_VERSION_UPDATE, versionUpdateBox.isSelected());
        writer.setString(ModelPreferences.UI_PROXY_HOST, proxyHostField.getText().trim());
        writer.setInt(ModelPreferences.UI_PROXY_PORT, ((Number) proxyPortSpinner.getValue()).intValue());
    }

    /**
     * The proxy user and password (secure storage) and the repository list (maven-repositories.xml).
     */
    @Override
    int getOtherChangeCount() {
        int count = proxyUserField.getText().trim().equals(savedProxyUser) ? 0 : 1;
        count += new String(proxyPasswordField.getPassword()).equals(savedProxyPassword) ? 0 : 1;
        return count + (repositoriesChanged ? 1 : 0);
    }

    @Override
    void revertOther() {
        loadProxyCredentials();
        loadRepositories();
    }

    /**
     * Also saves the proxy credentials and the repositories; when one of them fails the other is still saved.
     */
    @Override
    void apply() throws DBException {
        super.apply();
        DBException failure = null;
        String user = proxyUserField.getText().trim();
        String password = new String(proxyPasswordField.getPassword());
        if (!user.equals(savedProxyUser) || !password.equals(savedProxyPassword)) {
            try {
                GlobalProxyAuthenticator.saveCredentials(user, password);
                savedProxyUser = user;
                savedProxyPassword = password;
            } catch (DBException e) {
                failure = e;
            }
        }
        if (repositoriesChanged) {
            try {
                saveRepositories();
                // New copies: the saved ones now belong to the registry
                loadRepositories();
            } catch (DBException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Copies of the registry's repositories, edited until they are saved.
     */
    private void loadRepositories() {
        repositories.clear();
        for (MavenRepository repository : MavenRegistry.getInstance().getRepositories()) {
            repositories.add(new MavenRepository(repository));
        }
        repositoriesChanged = false;
        repositoryModel.fireTableDataChanged();
        updateButtons();
    }

    private void loadProxyCredentials() {
        try {
            DBPAuthInfo credentials = GlobalProxyAuthenticator.readCredentials();
            if (credentials != null) {
                savedProxyUser = CommonUtils.notEmpty(credentials.getUserName());
                savedProxyPassword = CommonUtils.notEmpty(credentials.getUserPassword());
            }
        } catch (DBException e) {
            log.warn("Can't read the proxy credentials", e);
        }
        proxyUserField.setText(savedProxyUser);
        proxyPasswordField.setText(savedProxyPassword);
    }

    private void chooseDriversHome() {
        JFileChooser chooser = new JFileChooser(driversHomeField.getText().trim());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File folder = chooser.getSelectedFile();
            driversHomeField.setText(folder.getAbsolutePath());
        }
    }

    //////////////////////////////////////////////////////////
    // Maven repositories

    @Nullable
    private MavenRepository getSelectedRepository() {
        int row = repositoryTable.getSelectedRow();
        return row < 0 ? null : repositories.get(repositoryTable.convertRowIndexToModel(row));
    }

    /**
     * Built-in repositories (plugin.xml) can only be turned on or off; added ones can also be edited and removed.
     */
    private void updateButtons() {
        MavenRepository repository = getSelectedRepository();
        boolean custom = repository != null && repository.getType() == MavenRepository.RepositoryType.CUSTOM;
        editButton.setEnabled(custom);
        removeButton.setEnabled(custom);
    }

    private void removeRepository() {
        MavenRepository repository = getSelectedRepository();
        if (repository != null && repository.getType() == MavenRepository.RepositoryType.CUSTOM) {
            repositories.remove(repository);
            repositoriesChanged = true;
            repositoryModel.fireTableDataChanged();
            updateButtons();
        }
    }

    /**
     * Adds a repository ({@code repository} null) or edits an added one, in a form dialog that stays open until the
     * values are valid or it is cancelled.
     */
    private void editRepository(@Nullable MavenRepository repository) {
        JTextField idField = new JTextField(repository == null ? "" : repository.getId(), 24);
        idField.setEditable(repository == null);
        JTextField nameField = new JTextField(repository == null ? "" : repository.getName(), 24);
        JTextField urlField = new JTextField(repository == null ? "" : repository.getUrl(), 32);
        JCheckBox snapshotBox = new JCheckBox(SettingsMessages.drivers_maven_snapshot, repository != null && repository.isSnapshot());
        // A field of the repository, taken with the dialog's OK
        snapshotBox.putClientProperty(OptionsForm.VALUE_CHECK_BOX, true);
        JTextField scopesField = new JTextField(repository == null ? "" : String.join(",", repository.getScopes()), 24); //$NON-NLS-1$
        JTextField userField = new JTextField(repository == null ? "" : CommonUtils.notEmpty(repository.getAuthInfo().getUserName()), 20);
        JPasswordField passwordField = new JPasswordField(repository == null ? "" : CommonUtils.notEmpty(repository.getAuthInfo().getUserPassword()), 20);

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addRow(form, 0, SettingsMessages.drivers_maven_id, idField, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.drivers_maven_name, nameField, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.drivers_maven_url, urlField, null);
        row = OptionsDialog.addRow(form, row, null, snapshotBox, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.drivers_maven_scopes, scopesField, SettingsMessages.drivers_maven_scopes_hint);
        row = OptionsDialog.addRow(form, row, SettingsMessages.drivers_maven_user, userField, null);
        OptionsDialog.addRow(form, row, SettingsMessages.drivers_maven_password, passwordField, null);
        String title = repository == null ? SettingsMessages.drivers_maven_dialog_add : SettingsMessages.drivers_maven_dialog_edit;
        while (true) {
            if (JOptionPane.showConfirmDialog(this, form, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) {
                return;
            }
            String id = idField.getText().trim();
            String name = nameField.getText().trim();
            String url = urlField.getText().trim();
            String error = null;
            if (id.isEmpty() || name.isEmpty() || url.isEmpty()) {
                error = SettingsMessages.drivers_maven_required;
            } else if (repository == null && repositories.stream().anyMatch(other -> other.getId().equals(id))) {
                error = NLS.bind(SettingsMessages.drivers_maven_id_used, id);
            }
            if (error == null) {
                MavenRepository target = repository;
                if (target == null) {
                    target = new MavenRepository(id, name, url, MavenRepository.RepositoryType.CUSTOM);
                    // Added repositories come after the others, as SWT adds them
                    target.setOrder(repositories.stream().mapToInt(MavenRepository::getOrder).max().orElse(0) + 1);
                    repositories.add(target);
                } else {
                    target.setName(name);
                    target.setUrl(url);
                }
                target.setIsSnapshot(snapshotBox.isSelected());
                target.setScopes(CommonUtils.splitString(scopesField.getText(), ',').stream()
                    .map(String::trim).filter(scope -> !scope.isEmpty()).toList());
                target.getAuthInfo().setUserName(userField.getText().trim());
                target.getAuthInfo().setUserPassword(new String(passwordField.getPassword()));
                repositoriesChanged = true;
                repositoryModel.fireTableDataChanged();
                int index = repositories.indexOf(target);
                repositoryTable.getSelectionModel().setSelectionInterval(index, index);
                return;
            }
            JOptionPane.showMessageDialog(this, error, title, JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * Moves the edited copies into the registry and writes {@code maven-repositories.xml}.
     */
    private void saveRepositories() throws DBException {
        MavenRegistry registry = MavenRegistry.getInstance();
        List<MavenRepository> custom = new ArrayList<>();
        for (MavenRepository repository : repositories) {
            if (repository.getType() == MavenRepository.RepositoryType.CUSTOM) {
                custom.add(repository);
            } else {
                MavenRepository original = registry.findRepository(repository.getId());
                if (original != null) {
                    original.setEnabled(repository.isEnabled());
                }
            }
        }
        registry.setCustomRepositories(custom);
        try {
            registry.saveConfiguration();
        } catch (IOException e) {
            throw new DBException("Can't save the Maven repositories", e);
        }
    }

    /**
     * Enabled (editable), name, URL and source of the repositories.
     */
    private final class RepositoryTableModel extends AbstractTableModel {
        private final String[] columns = {
            SettingsMessages.drivers_maven_enabled, SettingsMessages.drivers_maven_name, SettingsMessages.drivers_maven_url,
            SettingsMessages.drivers_maven_type};

        @Override
        public int getRowCount() {
            return repositories.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0;
        }

        @Override
        public Object getValueAt(int row, int column) {
            MavenRepository repository = repositories.get(row);
            return switch (column) {
                case 0 -> repository.isEnabled();
                case 1 -> repository.getName();
                case 2 -> repository.getUrl();
                default -> repository.getType() == MavenRepository.RepositoryType.CUSTOM
                    ? SettingsMessages.drivers_maven_type_custom : SettingsMessages.drivers_maven_type_global;
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 0 && value instanceof Boolean enabled && !Objects.equals(enabled, repositories.get(row).isEnabled())) {
                repositories.get(row).setEnabled(enabled);
                repositoriesChanged = true;
            }
        }
    }
}
