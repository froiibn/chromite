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

import com.formdev.flatlaf.FlatClientProperties;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DatabaseURL;
import com.seizia.chromite.model.connection.DBPAuthModelDescriptor;
import com.seizia.chromite.model.connection.DBPConnectionBootstrap;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPConnectionType;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.connection.DBPDriverConfigurationType;
import com.seizia.chromite.model.impl.PropertyDescriptor;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.registry.DataSourceDescriptor;
import com.seizia.chromite.registry.DataSourceProviderRegistry;
import com.seizia.chromite.runtime.properties.PropertySourceCustom;
import com.seizia.chromite.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Connect 대화상자의 설정 화면(MVP 2번 "범용 연결 화면 하나로 전체 드라이버 지원"). 드라이버 정의만 보고 입력 칸을 정한다.
 *
 * 설계: 배치는 SSMS "Connect to Server"를 따른다. 위의 주 양식에 연결에 꼭 필요한 칸만 두고, 나머지는 "Options >>"로 펼치는 탭에 둔다.
 * 칸을 정하는 규칙은 SWT의 범용 연결 화면({@code GenericConnectionPage})과 같은 규칙을 모델 코드로 따른다.
 * <ul>
 *     <li>주 양식: Database type(대화상자가 넘긴 콤보), 드라이버의 예시 URL({@code sampleURL})을 {@link DatabaseURL#getUrlPattern}으로 읽어
 *     들어 있는 자리 표시의 칸, Authentication, Login, Password, Remember password, Connection name.
 *     {@code {host}}와 {@code {port}}는 SSMS처럼 "Server name" 한 칸({@code 호스트[:포트]}, SSMS식 {@code 호스트,포트}도 받음)으로 받고,
 *     이 드라이버로 연결한 적이 있는 서버 이름({@link ConnectionHistory})을 콤보 목록으로 보여 준다. 포트를 적지 않으면 드라이버의 기본 포트다.
 *     {@code {server}} → Server instance(또는 Server name), {@code {database}} → Database, {@code {file}}/{@code {folder}} → File/Folder + Browse.
 *     예시 URL이 없거나 그중 아무것도 없으면 JDBC URL만 입력한다.</li>
 *     <li>Authentication: {@link ConnectionAuthModels}가 고른 인증 방식(둘 이상일 때만 콤보를 보임, SWT {@code AuthModelSelector}와 같음).
 *     인증 방식마다 Login·Password 칸을 보이거나 숨긴다. 드라이버가 익명 접속이면 인증 칸을 모두 숨긴다.</li>
 *     <li>인증 방식별 추가 칸({@link ConnectionAuthExtras}: SQL Server NTLM의 integrated security, Oracle Role, PgPass 호스트 이름)은
 *     인증 묶음 아래에 두고 고른 방식의 것만 보인다. 저장할 때 고르지 않은 방식의 칸이 쓴 값은 지운다.</li>
 *     <li>드라이버별 주 칸({@link ConnectionDriverFields}: SQL Server Encrypt·Trust server certificate, Oracle Basic/TNS·SID/Service)은
 *     범용 칸 사이의 정해진 자리에 넣는다. 그 칸이 맡는 공급자 속성은 Provider Properties 표에서 뺀다(한 값을 두 곳에서 고치지 않게).</li>
 *     <li>Options 탭: Connection Properties(연결 종류, 자동 커밋, 기본 DB·스키마, 읽기 전용, 연결 색, 연결 직후 SQL, keep-alive, 유휴 연결 닫기. SWT
 *     {@code ConnectionPageGeneral}·{@code ConnectionPageInitialization}과 같은 값), 네트워크 핸들러 탭(SSH, Proxy, SSL: {@link ConnectionNetworkPage},
 *     쓰는 핸들러의 탭에는 확인 표시), Driver Properties, Provider Properties,
 *     Additional Connection Parameters(연결 방식: 칸 / JDBC URL 직접 입력, {@link DBPDriverConfigurationType}).</li>
 *     <li>Driver Properties 탭: 처음 열 때 모델의 {@code DBPDataSourceProvider#getConnectionProperties}(JDBC 드라이버의
 *     {@code getPropertyInfo})로 읽는다(SWT {@code ConnectionPropertiesControl}과 같은 호출, 드라이버 파일이 없으면 이때 내려받기를 묻는다).
 *     열지 않았으면 저장할 때 연결의 드라이버 속성을 건드리지 않는다.</li>
 *     <li>Provider Properties 탭: 드라이버 정의의 공급자 속성({@code getProviderPropertyDescriptors}, 예: Oracle SID/Service, 역할).
 *     SWT에서는 DB별 전용 화면이 이 값을 고친다. 여기서는 범용 표로 고친다.</li>
 *     <li>새 연결의 기본값: 이 드라이버의 마지막 서버 이름(없으면 드라이버의 기본 호스트, 그것도 없으면 localhost)·포트·서버·DB·사용자.</li>
 * </ul>
 * 저장은 {@link #saveSettings}가 주소 → 인증 → 드라이버·공급자 속성 표 → 드라이버별 칸·인증 방식별 칸 → URL → 연결 속성 → 네트워크 핸들러 순서로
 * {@link DBPConnectionConfiguration}에 쓴다. 드라이버별 칸은 표보다 뒤에 써서 같은 속성이면 주 양식이 이기고, URL은 그 뒤에 만들어
 * 공급자 속성으로 URL을 만드는 드라이버(Oracle TNS)도 맞는 URL이 된다.
 * 색은 테마 키({@code Chromite.*}, 없으면 FlatLaf 키)로 읽어 테마를 바꾸는 작업과 독립적으로 둔다.
 *
 * 변경 이력:
 * - 2026-11-06 D단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 * - 2026-11-06 SSMS식 Connect 대화상자: Main/Driver/Provider 탭 → 주 양식 + Options 탭. Server name 한 칸과 서버 기록,
 *   인증 방식 선택, Connection Properties·Additional Connection Parameters 탭 추가, 라벨 끝 ':' 제거
 *   (docs/chromite/swing-frontend.md "Connect 대화상자 (SSMS식)")
 * - 2026-11-06 고급 연결 설정: 인증 방식별 칸, 드라이버별 주 칸(SQL Server, Oracle), 네트워크 핸들러 탭(SSH, Proxy, SSL), 연결 색,
 *   연결 직후 SQL. 저장 순서에서 URL을 속성 표 뒤로 옮김 (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름). 빠진 값 안내는 조각을 잇지 않고 칸마다 문장 하나
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 */
final class ConnectionSettingsPanel extends JPanel {

    private static final Log log = Log.getLog(ConnectionSettingsPanel.class);

    private static final String[] AUTO_COMMIT_ITEMS = {
        ConnectionMessages.settings_auto_commit_default, ConnectionMessages.settings_auto_commit_auto, ConnectionMessages.settings_auto_commit_manual};

    private final DataSourceDescriptor dataSource;
    private final DBPDriver driver;
    private final Runnable changeListener;

    @Nullable
    private final DatabaseURL.Pattern urlPattern;
    private final Set<String> urlProperties;
    private final boolean urlOnly;

    private final JTextField nameField = new JTextField();
    private final JRadioButton hostModeRadio = new JRadioButton(ConnectionMessages.settings_mode_fields);
    private final JRadioButton urlModeRadio = new JRadioButton(ConnectionMessages.settings_jdbc_url);
    private final JTextField urlField = new JTextField();
    private final JComboBox<String> serverCombo = new JComboBox<>();
    private final JTextField serverNameField;
    private final JTextField serverField = new JTextField();
    private final JTextField databaseField = new JTextField();
    private final JTextField pathField = new JTextField();
    private final List<JComponent> manualComponents = new ArrayList<>();

    private final List<DBPAuthModelDescriptor> authModels;
    private final JComboBox<DBPAuthModelDescriptor> authCombo = new JComboBox<>();
    private final JLabel userLabel = new JLabel(ConnectionMessages.settings_login);
    private final JTextField userField = new JTextField();
    private final JLabel passwordLabel = new JLabel(ConnectionMessages.settings_password);
    private final JPasswordField passwordField = new JPasswordField();
    private final JCheckBox savePasswordCheck = new JCheckBox(ConnectionMessages.settings_remember_password);
    private final List<ConnectionAuthExtras.Extra> authExtras;
    @Nullable
    private final ConnectionDriverFields driverFields;
    private final JLabel serverNameLabel = new JLabel(ConnectionMessages.settings_server_name);
    private final JLabel databaseLabel = new JLabel(ConnectionMessages.settings_database);
    @Nullable
    private JComponent databaseRow;

    private final JComboBox<DBPConnectionType> connectionTypeCombo = new JComboBox<>();
    private final JComboBox<String> autoCommitCombo = new JComboBox<>(AUTO_COMMIT_ITEMS);
    private final JTextField defaultCatalogField = new JTextField();
    private final JTextField defaultSchemaField = new JTextField();
    private final JCheckBox readOnlyCheck = new JCheckBox(ConnectionMessages.settings_read_only);
    private final JSpinner keepAliveSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
    private final JCheckBox closeIdleCheck = new JCheckBox(ConnectionMessages.settings_close_idle);
    private final JSpinner closeIdleSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
    private final JCheckBox customColorCheck = new JCheckBox(ConnectionMessages.settings_custom_color);
    private final JButton colorButton = new JButton();
    @Nullable
    private Color customColor;
    private final JButton bootstrapButton = new JButton();
    private List<String> bootstrapQueries = new ArrayList<>();
    private boolean ignoreBootstrapErrors;
    private final List<ConnectionNetworkPage> networkPages;

    private final JTabbedPane optionsTabs = new JTabbedPane();
    private final ConnectionPropertiesTable driverPropertiesTable = new ConnectionPropertiesTable(true);
    @Nullable
    private final ConnectionPropertiesTable providerPropertiesTable;
    private boolean driverPropertiesRequested;
    private boolean loading;

    /**
     * @param dataSource         the edited (or new, not yet registered) connection; it is only read here
     * @param isNew              new connection: fields start with the driver defaults
     * @param databaseTypeCombo  the "Database type" row (owned by the dialog, it survives a driver change)
     * @param changeListener     called when a value changes (validation)
     */
    ConnectionSettingsPanel(
        @NotNull DataSourceDescriptor dataSource,
        boolean isNew,
        @NotNull JComponent databaseTypeCombo,
        @NotNull Runnable changeListener
    ) {
        super(new BorderLayout(0, 12));
        this.dataSource = dataSource;
        this.driver = dataSource.getDriver();
        // Every change also updates the network tabs (used or not) and the address rows (Oracle TNS hides some)
        this.changeListener = () -> {
            updateNetworkTabs();
            updateAddressRows();
            changeListener.run();
        };

        DatabaseURL.Pattern pattern = null;
        String sampleURL = driver.getSampleURL();
        if (!CommonUtils.isEmpty(sampleURL)) {
            try {
                pattern = DatabaseURL.getUrlPattern(sampleURL);
            } catch (DBException e) {
                log.debug("Can't parse the sample URL of " + driver.getName() + " (" + sampleURL + ")", e);
            }
        }
        Set<String> properties = pattern == null ? Collections.emptySet() : pattern.getAvailablePropertyNames();
        // Same rule as GenericConnectionPage.parseSampleURL: without any of these the template can't build a URL
        boolean usable = properties.contains(DBConstants.PROP_HOST) || properties.contains(DBConstants.PROP_DATABASE)
            || properties.contains(DBConstants.PROP_SERVER) || properties.contains(DBConstants.PROP_FOLDER)
            || properties.contains(DBConstants.PROP_FILE);
        this.urlPattern = usable ? pattern : null;
        this.urlProperties = usable ? properties : Collections.emptySet();
        this.urlOnly = !usable;

        serverCombo.setEditable(true);
        serverNameField = (JTextField) serverCombo.getEditor().getEditorComponent();
        for (String server : ConnectionHistory.getServers(driver)) {
            serverCombo.addItem(server);
        }

        DBPAuthModelDescriptor currentAuthModel = driver.isAnonymousAccess() ? null
            : ConnectionAuthModels.detect(driver, dataSource.getConnectionConfiguration());
        authModels = currentAuthModel == null ? List.of() : ConnectionAuthModels.getChoices(driver, currentAuthModel);
        authExtras = ConnectionAuthExtras.create(authModels);
        driverFields = urlOnly ? null : ConnectionDriverFields.create(driver, this.changeListener);
        networkPages = ConnectionNetworkPage.createPages(dataSource, this.changeListener);

        DBPPropertyDescriptor[] providerDescriptors = driver.getProviderPropertyDescriptors();
        providerPropertiesTable = providerDescriptors == null || providerDescriptors.length == 0 ? null : new ConnectionPropertiesTable(false);

        add(createMainForm(databaseTypeCombo), BorderLayout.NORTH);
        optionsTabs.addTab(ConnectionMessages.settings_tab_connection_properties, createConnectionPropertiesPage());
        for (ConnectionNetworkPage page : networkPages) {
            optionsTabs.addTab(page.getTitle(), page);
        }
        optionsTabs.addTab(ConnectionMessages.settings_tab_driver_properties, driverPropertiesTable);
        if (providerPropertiesTable != null) {
            optionsTabs.addTab(ConnectionMessages.settings_tab_provider_properties, providerPropertiesTable);
        }
        if (!urlOnly) {
            optionsTabs.addTab(ConnectionMessages.settings_tab_additional, createAdditionalParametersPage());
        }
        driverPropertiesTable.setSource(null, ConnectionMessages.settings_driver_properties_deferred);
        optionsTabs.addChangeListener(e -> loadDriverPropertiesIfShown());
        // One row of tabs that scrolls: wrapped rows move the selected tab around
        optionsTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        optionsTabs.setPreferredSize(new Dimension(600, 340));
        optionsTabs.setVisible(false);
        add(optionsTabs, BorderLayout.CENTER);

        loadSettings(isNew, currentAuthModel, providerDescriptors);
    }

    @NotNull
    DBPDriver getDriver() {
        return driver;
    }

    @NotNull
    String getConnectionName() {
        return nameField.getText().trim();
    }

    /**
     * The typed server name for the server history; {@code null} when this driver has no server name field
     * or the connection is made with a JDBC URL.
     */
    @Nullable
    String getServerHistoryEntry() {
        if (isUrlMode() || !urlProperties.contains(DBConstants.PROP_HOST)) {
            return null;
        }
        String server = serverNameField.getText().trim();
        return server.isEmpty() ? null : server;
    }

    boolean isOptionsVisible() {
        return optionsTabs.isVisible();
    }

    void setOptionsVisible(boolean visible) {
        optionsTabs.setVisible(visible);
        revalidate();
        loadDriverPropertiesIfShown();
    }

    void focusFirstField() {
        JComponent first = urlOnly ? urlField
            : urlProperties.contains(DBConstants.PROP_HOST) ? serverNameField
            : urlProperties.contains(DBConstants.PROP_FILE) || urlProperties.contains(DBConstants.PROP_FOLDER) ? pathField
            : urlProperties.contains(DBConstants.PROP_SERVER) ? serverField
            : databaseField;
        first.requestFocusInWindow();
    }

    /**
     * Returns the problem that prevents saving or testing, or {@code null} when the settings are complete
     * (the mandatory URL template parameters of GenericConnectionPage.isComplete).
     */
    @Nullable
    String validateSettings() {
        String problem = validateAddress();
        if (problem == null) {
            for (ConnectionNetworkPage page : networkPages) {
                problem = page.getErrorMessage();
                if (problem != null) {
                    break;
                }
            }
        }
        return problem;
    }

    /**
     * The address part of {@link #validateSettings}: the URL, the generic fields or the driver's own fields.
     */
    @Nullable
    private String validateAddress() {
        if (isUrlMode()) {
            return urlField.getText().isBlank() ? ConnectionMessages.settings_enter_url : null;
        }
        if (driverFields != null && !driverFields.isGenericAddressUsed()) {
            return driverFields.validate();
        }
        String[] hostAndPort = getHostAndPort();
        if (!hostAndPort[1].isEmpty() && !hostAndPort[1].chars().allMatch(Character::isDigit)) {
            return ConnectionMessages.settings_port_not_number;
        }
        for (String property : urlPattern.getMandatoryPropertyNames()) {
            String missing = switch (property) {
                case DBConstants.PROP_HOST -> hostAndPort[0].isBlank() ? ConnectionMessages.settings_enter_server_name : null;
                case DBConstants.PROP_PORT -> hostAndPort[1].isBlank() ? ConnectionMessages.settings_enter_port : null;
                case DBConstants.PROP_DATABASE -> databaseField.getText().isBlank() ? ConnectionMessages.settings_enter_database : null;
                case DBConstants.PROP_SERVER -> serverField.getText().isBlank() ? ConnectionMessages.settings_enter_server : null;
                case DBConstants.PROP_FILE -> pathField.getText().isBlank() ? ConnectionMessages.settings_enter_file : null;
                case DBConstants.PROP_FOLDER -> pathField.getText().isBlank() ? ConnectionMessages.settings_enter_folder : null;
                default -> null;
            };
            if (missing != null) {
                return missing;
            }
        }
        return driverFields == null ? null : driverFields.validate();
    }

    /**
     * Writes the form into a connection.
     *
     * @param forTest a temporary copy for "Test Connection": the password is kept even if it is not saved,
     *                as SWT's native authentication does for temporary connections
     */
    void saveSettings(@NotNull DataSourceDescriptor target, boolean forTest) {
        driverPropertiesTable.stopEditing();
        if (providerPropertiesTable != null) {
            providerPropertiesTable.stopEditing();
        }
        DBPConnectionConfiguration configuration = target.getConnectionConfiguration();
        boolean urlMode = isUrlMode();
        configuration.setConfigurationType(urlMode ? DBPDriverConfigurationType.URL : DBPDriverConfigurationType.MANUAL);
        writeAddress(configuration);

        DBPAuthModelDescriptor authModel = getSelectedAuthModel();
        if (authModel != null) {
            configuration.setAuthModelId(authModel.getId());
            ConnectionAuthModels.Fields fields = ConnectionAuthModels.getFields(authModel);
            // Same as DatabaseNativeAuthModelConfigurator.saveSettings: fields the model has no use for are not written
            if (fields.userLabel() != null) {
                configuration.setUserName(GeneralUtils.trimAllWhitespaces(userField.getText()));
            }
            if (fields.passwordLabel() != null) {
                boolean savePassword = forTest || savePasswordCheck.isSelected();
                configuration.setUserPassword(savePassword ? GeneralUtils.trimAllWhitespaces(new String(passwordField.getPassword())) : null);
                target.setSavePassword(savePassword);
                if (!savePassword) {
                    // Also the password properties of the auth model, as EditConnectionWizard.saveSettings does
                    target.resetPassword();
                }
            } else if (fields.managed()) {
                configuration.setUserPassword(null);
            }
        }

        PropertySourceCustom driverProperties = driverPropertiesTable.getSource();
        if (driverProperties != null) {
            // Same as DriverPropertiesDialogPage.savePropertyValues
            Map<String, String> properties = configuration.getProperties();
            properties.clear();
            for (Map.Entry<String, Object> entry : driverProperties.getPropertyValues().entrySet()) {
                String name = CommonUtils.toString(entry.getKey());
                if (!name.isEmpty() && entry.getValue() != null) {
                    properties.put(name, CommonUtils.toString(entry.getValue()));
                }
            }
        }
        PropertySourceCustom providerProperties = providerPropertiesTable == null ? null : providerPropertiesTable.getSource();
        if (providerProperties != null) {
            // Only the listed provider properties: other ones belong to other settings (e.g. authentication)
            Map<String, Object> values = providerProperties.getPropertyValues();
            for (DBPPropertyDescriptor property : providerProperties.getProperties()) {
                Object value = values.get(property.getId());
                if (value == null || CommonUtils.toString(value).isEmpty()) {
                    configuration.removeProviderProperty(property.getId());
                } else {
                    configuration.setProviderProperty(property.getId(), CommonUtils.toString(value));
                }
            }
        }
        // After the property tables: the main form's fields win over the same properties in the tables
        if (driverFields != null) {
            driverFields.saveSettings(configuration, urlMode);
        }
        for (ConnectionAuthExtras.Extra extra : authExtras) {
            if (extra.model == authModel) {
                extra.saveSettings(target);
            } else {
                extra.resetSettings(target);
            }
        }
        // The URL is built after the driver's fields: some providers build it from provider properties (Oracle)
        if (urlMode) {
            configuration.setUrl(urlField.getText().trim());
        } else {
            try {
                configuration.setUrl(driver.getConnectionURL(configuration));
            } catch (DBException e) {
                // Regenerated from the template when connecting, as in ConnectionPageAbstract.saveConnectionURL
                configuration.setUrl(null);
            }
        }
        saveConnectionProperties(target);
        for (ConnectionNetworkPage page : networkPages) {
            page.saveSettings(target);
        }
    }

    /**
     * The network handlers the connection uses ("SSH, SSL"), or {@code null} when none.
     */
    @Nullable
    String getNetworkSummary() {
        List<String> used = new ArrayList<>();
        for (ConnectionNetworkPage page : networkPages) {
            String usage = page.describeUsage();
            if (usage != null) {
                used.add(usage);
            }
        }
        return used.isEmpty() ? null : String.join(", ", used); //$NON-NLS-1$
    }

    /**
     * Hides the server name and database rows when the driver's own fields replace them (Oracle TNS).
     */
    private void updateAddressRows() {
        boolean generic = driverFields == null || driverFields.isGenericAddressUsed();
        for (JComponent component : new JComponent[]{serverNameLabel, serverCombo, databaseLabel, databaseRow}) {
            if (component != null) {
                component.setVisible(generic);
            }
        }
    }

    /**
     * A check mark on the tabs of the handlers the connection uses.
     */
    private void updateNetworkTabs() {
        if (networkPages == null) {
            // Called while the panel is being built
            return;
        }
        for (ConnectionNetworkPage page : networkPages) {
            int index = optionsTabs.indexOfComponent(page);
            if (index >= 0) {
                optionsTabs.setIconAt(index, page.isHandlerEnabled() ? UiIcons.get("check") : null); //$NON-NLS-1$
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Main form

    @NotNull
    private JComponent createMainForm(@NotNull JComponent databaseTypeCombo) {
        FormBuilder form = new FormBuilder();
        form.addRow(ConnectionMessages.settings_database_type, databaseTypeCombo);
        if (driverFields != null) {
            driverFields.addLeadingRows(form);
        }

        if (urlProperties.contains(DBConstants.PROP_HOST)) {
            serverNameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                urlProperties.contains(DBConstants.PROP_PORT) ? ConnectionMessages.settings_server_placeholder_port : ConnectionMessages.settings_server_placeholder);
            serverCombo.setToolTipText(urlProperties.contains(DBConstants.PROP_PORT)
                ? NLS.bind(ConnectionMessages.settings_server_tip_port, CommonUtils.notEmpty(driver.getDefaultPort()))
                : ConnectionMessages.settings_server_tip);
            form.addRow(serverNameLabel, serverCombo);
            manualComponents.add(serverCombo);
        }
        if (urlProperties.contains(DBConstants.PROP_SERVER)) {
            form.addRow(urlProperties.contains(DBConstants.PROP_HOST) ? ConnectionMessages.settings_server_instance : ConnectionMessages.settings_server_name, serverField);
            manualComponents.add(serverField);
        }
        if (urlProperties.contains(DBConstants.PROP_DATABASE)) {
            databaseRow = driverFields == null ? databaseField : driverFields.decorateDatabaseField(databaseField);
            form.addRow(databaseLabel, databaseRow);
            manualComponents.add(databaseField);
        }
        if (urlProperties.contains(DBConstants.PROP_FILE) || urlProperties.contains(DBConstants.PROP_FOLDER)) {
            boolean folder = !urlProperties.contains(DBConstants.PROP_FILE);
            JButton browseButton = new JButton(ConnectionMessages.common_browse);
            browseButton.addActionListener(e -> browsePath(folder));
            JPanel pathPanel = new JPanel(new BorderLayout(6, 0));
            pathPanel.add(pathField, BorderLayout.CENTER);
            pathPanel.add(browseButton, BorderLayout.EAST);
            form.addRow(folder ? ConnectionMessages.settings_folder : ConnectionMessages.settings_file, pathPanel);
            manualComponents.add(pathField);
            manualComponents.add(browseButton);
        }
        if (urlOnly) {
            form.addRow(ConnectionMessages.settings_jdbc_url, urlField);
        }
        if (driverFields != null) {
            driverFields.addAddressRows(form);
        }

        if (!authModels.isEmpty()) {
            form.addGroupGap();
            if (authModels.size() > 1) {
                for (DBPAuthModelDescriptor model : authModels) {
                    authCombo.addItem(model);
                }
                authCombo.setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                        if (value instanceof DBPAuthModelDescriptor model) {
                            setText(model.getName());
                            setToolTipText(model.getDescription());
                        }
                        return this;
                    }
                });
                authCombo.addActionListener(e -> authModelChanged());
                form.addRow(ConnectionMessages.settings_authentication, authCombo);
            }
            form.addRow(userLabel, userField);
            form.addRow(passwordLabel, passwordField);
            form.addRow((JLabel) null, savePasswordCheck);
            for (ConnectionAuthExtras.Extra extra : authExtras) {
                extra.addRows(form);
            }
        }
        if (driverFields != null) {
            driverFields.addSecurityRows(form);
        }

        form.addGroupGap();
        nameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, ConnectionMessages.settings_connection_name_placeholder);
        form.addRow(ConnectionMessages.settings_connection_name, nameField);

        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fieldChanged(e);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fieldChanged(e);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                fieldChanged(e);
            }
        };
        for (JTextField field : List.of(urlField, serverNameField, serverField, databaseField, pathField, userField)) {
            field.getDocument().addDocumentListener(listener);
        }
        return form.getPanel();
    }

    @Nullable
    private DBPAuthModelDescriptor getSelectedAuthModel() {
        if (authModels.isEmpty()) {
            return null;
        }
        return authModels.size() > 1 ? (DBPAuthModelDescriptor) authCombo.getSelectedItem() : authModels.getFirst();
    }

    /**
     * Shows the login and password fields the selected auth model uses.
     */
    private void authModelChanged() {
        DBPAuthModelDescriptor model = getSelectedAuthModel();
        if (model == null) {
            return;
        }
        authCombo.setToolTipText(model.getDescription());
        ConnectionAuthModels.Fields fields = ConnectionAuthModels.getFields(model);
        boolean user = fields.userLabel() != null;
        boolean password = fields.passwordLabel() != null;
        userLabel.setText(user ? fields.userLabel() : ConnectionMessages.settings_login);
        passwordLabel.setText(password ? fields.passwordLabel() : ConnectionMessages.settings_password);
        userLabel.setVisible(user);
        userField.setVisible(user);
        passwordLabel.setVisible(password);
        passwordField.setVisible(password);
        savePasswordCheck.setVisible(password);
        for (ConnectionAuthExtras.Extra extra : authExtras) {
            extra.setVisible(extra.model == model);
        }
        revalidate();
        repaint();
        if (!loading) {
            changeListener.run();
            if (SwingUtilities.getWindowAncestor(this) instanceof ConnectionDialog dialog) {
                // Rows were shown or hidden
                dialog.fitToContent();
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Options pages

    @NotNull
    private JComponent createConnectionPropertiesPage() {
        for (DBPConnectionType type : DataSourceProviderRegistry.getInstance().getConnectionTypes()) {
            connectionTypeCombo.addItem(type);
        }
        connectionTypeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof DBPConnectionType type) {
                    setText(type.getName());
                    setToolTipText(type.getDescription());
                    setIcon(new ColorSwatch(ConnectionColors.getTypeColor(type)));
                }
                return this;
            }
        });
        connectionTypeCombo.addActionListener(e -> {
            if (!loading && connectionTypeCombo.getSelectedItem() instanceof DBPConnectionType type) {
                // The connection type's defaults, as ConnectionPageInitialization does when the type changes
                closeIdleCheck.setSelected(type.isAutoCloseConnections());
                closeIdleSpinner.setValue(type.getCloseIdleConnectionPeriod());
                closeIdleSpinner.setEnabled(closeIdleCheck.isSelected());
            }
            updateColorButton();
        });
        closeIdleCheck.addActionListener(e -> closeIdleSpinner.setEnabled(closeIdleCheck.isSelected()));
        for (JSpinner spinner : List.of(keepAliveSpinner, closeIdleSpinner)) {
            // Seconds without digit grouping
            JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "#"); //$NON-NLS-1$
            editor.getTextField().setColumns(7);
            spinner.setEditor(editor);
        }
        defaultCatalogField.setToolTipText(ConnectionMessages.settings_default_database_tip);
        defaultSchemaField.setToolTipText(ConnectionMessages.settings_default_schema_tip);
        keepAliveSpinner.setToolTipText(ConnectionMessages.settings_keep_alive_tip);

        FormBuilder form = new FormBuilder();
        form.addRow(ConnectionMessages.settings_connection_type, connectionTypeCombo);
        form.addRow(ConnectionMessages.settings_auto_commit, autoCommitCombo);
        form.addRow(ConnectionMessages.settings_default_database, defaultCatalogField);
        form.addRow(ConnectionMessages.settings_default_schema, defaultSchemaField);
        form.addRow((JLabel) null, readOnlyCheck);
        JPanel colorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        colorPanel.add(customColorCheck);
        colorPanel.add(Box.createHorizontalStrut(6));
        colorPanel.add(colorButton);
        customColorCheck.setToolTipText(ConnectionMessages.settings_custom_color_tip);
        customColorCheck.addActionListener(e -> {
            if (customColorCheck.isSelected() && customColor == null) {
                chooseColor();
            }
            updateColorButton();
        });
        colorButton.addActionListener(e -> chooseColor());
        form.addRow(ConnectionMessages.settings_color, colorPanel);
        bootstrapButton.setToolTipText(ConnectionMessages.bootstrap_hint);
        bootstrapButton.addActionListener(e -> {
            BootstrapQueriesDialog dialog = BootstrapQueriesDialog.edit(this, bootstrapQueries, ignoreBootstrapErrors);
            if (dialog != null) {
                bootstrapQueries = dialog.getQueries();
                ignoreBootstrapErrors = dialog.isIgnoreErrors();
                updateBootstrapButton();
            }
        });
        form.addRow(ConnectionMessages.settings_bootstrap, leftAligned(bootstrapButton));
        form.addGroupGap();
        form.addRow(ConnectionMessages.settings_keep_alive, leftAligned(keepAliveSpinner));
        JPanel closeIdlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        closeIdlePanel.add(closeIdleCheck);
        closeIdlePanel.add(Box.createHorizontalStrut(6));
        closeIdlePanel.add(closeIdleSpinner);
        form.addRow((JLabel) null, closeIdlePanel);
        form.addFiller();
        JPanel page = form.getPanel();
        page.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        return new JScrollPane(page, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    }

    @NotNull
    private JComponent createAdditionalParametersPage() {
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(hostModeRadio);
        modeGroup.add(urlModeRadio);
        hostModeRadio.addActionListener(e -> modeChanged());
        urlModeRadio.addActionListener(e -> modeChanged());

        FormBuilder form = new FormBuilder();
        form.addRow(ConnectionMessages.settings_connect_with, hostModeRadio);
        form.addRow((JLabel) null, urlModeRadio);
        form.addRow(ConnectionMessages.settings_jdbc_url, urlField);
        JLabel note = new JLabel(ConnectionMessages.settings_url_note);
        note.setForeground(ConnectionDialog.getThemeColor("Chromite.secondaryForeground", "Label.disabledForeground", Color.GRAY)); //$NON-NLS-1$ //$NON-NLS-2$
        form.addRow((JLabel) null, note);
        form.addFiller();
        JPanel page = form.getPanel();
        page.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        return page;
    }

    private void loadSettings(boolean isNew, @Nullable DBPAuthModelDescriptor currentAuthModel, @Nullable DBPPropertyDescriptor[] providerDescriptors) {
        loading = true;
        try {
            DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
            nameField.setText(isNew ? "" : dataSource.getName());
            // Defaults of GenericConnectionPage.loadSettings; a new connection starts with the last server used with the driver
            String host = configuration.getHostName();
            String port = configuration.getHostPort();
            if (isNew && CommonUtils.isEmpty(host)) {
                List<String> history = ConnectionHistory.getServers(driver);
                if (!history.isEmpty()) {
                    String[] hostAndPort = splitServerName(history.getFirst(), urlProperties.contains(DBConstants.PROP_PORT));
                    host = hostAndPort[0];
                    port = hostAndPort[1];
                }
            }
            if (CommonUtils.isEmpty(host)) {
                host = CommonUtils.toString(driver.getDefaultHost(), DBConstants.HOST_LOCALHOST);
            }
            if (isNew && CommonUtils.isEmpty(port)) {
                port = driver.getDefaultPort();
            }
            serverNameField.setText(formatServerName(host, port));
            serverField.setText(isNew && CommonUtils.isEmpty(configuration.getServerName())
                ? CommonUtils.notEmpty(driver.getDefaultServer()) : CommonUtils.notEmpty(configuration.getServerName()));
            databaseField.setText(isNew && CommonUtils.isEmpty(configuration.getDatabaseName())
                ? CommonUtils.notEmpty(driver.getDefaultDatabase()) : CommonUtils.notEmpty(configuration.getDatabaseName()));
            pathField.setText(CommonUtils.notEmpty(configuration.getDatabaseName()));
            // ConnectionPageWithAuth: a new connection starts with the driver's default user
            userField.setText(isNew && CommonUtils.isEmpty(configuration.getUserName())
                ? CommonUtils.notEmpty(driver.getDefaultUser()) : CommonUtils.notEmpty(configuration.getUserName()));
            passwordField.setText(CommonUtils.notEmpty(configuration.getUserPassword()));
            savePasswordCheck.setSelected(dataSource.isSavePassword());
            if (currentAuthModel != null && authModels.size() > 1) {
                authCombo.setSelectedItem(currentAuthModel);
            }

            boolean urlMode = urlOnly || configuration.getConfigurationType() == DBPDriverConfigurationType.URL;
            hostModeRadio.setSelected(!urlMode);
            urlModeRadio.setSelected(urlMode);
            if (urlMode) {
                String url = configuration.getUrl();
                if (CommonUtils.isEmpty(url) && urlOnly && isNew) {
                    url = driver.getSampleURL();
                }
                urlField.setText(CommonUtils.notEmpty(url));
            }

            if (providerPropertiesTable != null && providerDescriptors != null) {
                List<DBPPropertyDescriptor> descriptors = new ArrayList<>(List.of(providerDescriptors));
                // Properties with a field in the main form are edited there only
                Set<String> handled = new HashSet<>();
                if (driverFields != null) {
                    handled.addAll(driverFields.getHandledProviderProperties());
                }
                for (ConnectionAuthExtras.Extra extra : authExtras) {
                    handled.addAll(extra.getHandledProviderProperties());
                }
                descriptors.removeIf(descriptor -> handled.contains(descriptor.getId()));
                providerPropertiesTable.setSource(
                    new PropertySourceCustom(descriptors, new HashMap<>(configuration.getProviderProperties())), null);
            }
            loadConnectionProperties(configuration);
            if (driverFields != null) {
                driverFields.loadSettings(dataSource, isNew);
            }
            for (ConnectionAuthExtras.Extra extra : authExtras) {
                extra.loadSettings(dataSource);
            }
            for (ConnectionNetworkPage page : networkPages) {
                page.loadSettings(dataSource);
            }
        } finally {
            loading = false;
        }
        authModelChanged();
        modeChanged();
        updateNetworkTabs();
        updateAddressRows();
    }

    /**
     * Connection type, transactions and keep-alive, as ConnectionPageGeneral and ConnectionPageInitialization load them.
     */
    private void loadConnectionProperties(@NotNull DBPConnectionConfiguration configuration) {
        DBPConnectionType type = configuration.getConnectionType();
        if (((DefaultComboBoxModel<DBPConnectionType>) connectionTypeCombo.getModel()).getIndexOf(type) < 0) {
            connectionTypeCombo.addItem(type);
        }
        connectionTypeCombo.setSelectedItem(type);
        Boolean autoCommit = configuration.getBootstrap().getDefaultAutoCommit();
        autoCommitCombo.setSelectedIndex(autoCommit == null ? 0 : autoCommit ? 1 : 2);
        defaultCatalogField.setText(CommonUtils.notEmpty(configuration.getBootstrap().getDefaultCatalogName()));
        defaultSchemaField.setText(CommonUtils.notEmpty(configuration.getBootstrap().getDefaultSchemaName()));
        readOnlyCheck.setSelected(dataSource.isConnectionReadOnly());
        keepAliveSpinner.setValue(Math.max(0, configuration.getKeepAliveInterval()));
        closeIdleCheck.setSelected(configuration.isCloseIdleConnection());
        closeIdleSpinner.setValue(Math.max(0, configuration.getCloseIdleInterval() > 0
            ? configuration.getCloseIdleInterval() : type.getCloseIdleConnectionPeriod()));
        closeIdleSpinner.setEnabled(closeIdleCheck.isSelected());
        customColor = ConnectionColors.parse(configuration.getConnectionColor());
        customColorCheck.setSelected(customColor != null);
        updateColorButton();
        bootstrapQueries = new ArrayList<>(configuration.getBootstrap().getInitQueries());
        ignoreBootstrapErrors = configuration.getBootstrap().isIgnoreErrors();
        updateBootstrapButton();
    }

    /**
     * The color button shows the custom color, or (disabled) the connection type's color.
     */
    private void updateColorButton() {
        boolean custom = customColorCheck.isSelected() && customColor != null;
        Color color = custom ? customColor
            : connectionTypeCombo.getSelectedItem() instanceof DBPConnectionType type ? ConnectionColors.getTypeColor(type) : null;
        colorButton.setIcon(new ColorSwatch(color));
        colorButton.setText(custom ? ConnectionMessages.settings_color_change
            : color == null ? ConnectionMessages.settings_color_none : ConnectionMessages.settings_color_type);
        colorButton.setEnabled(customColorCheck.isSelected());
    }

    private void chooseColor() {
        Color initial = customColor != null ? customColor
            : connectionTypeCombo.getSelectedItem() instanceof DBPConnectionType type ? ConnectionColors.getTypeColor(type) : null;
        Color chosen = JColorChooser.showDialog(this, ConnectionMessages.settings_color_dialog, initial);
        if (chosen != null) {
            customColor = chosen;
        } else if (customColor == null) {
            customColorCheck.setSelected(false);
        }
        updateColorButton();
    }

    private void updateBootstrapButton() {
        bootstrapButton.setText(bootstrapQueries.isEmpty() ? ConnectionMessages.common_edit
            : NLS.bind(ConnectionMessages.settings_bootstrap_edit_count, bootstrapQueries.size()));
    }

    /**
     * Same values as ConnectionPageGeneral.saveSettings (type, read-only) and ConnectionPageInitialization.saveSettings
     * (auto-commit, default catalog and schema, keep-alive, idle connections). Empty names are saved as "not set".
     */
    private void saveConnectionProperties(@NotNull DataSourceDescriptor target) {
        DBPConnectionConfiguration configuration = target.getConnectionConfiguration();
        if (connectionTypeCombo.getSelectedItem() instanceof DBPConnectionType type && !Objects.equals(type, configuration.getConnectionType())) {
            configuration.setConnectionType(type);
        }
        DBPConnectionBootstrap bootstrap = configuration.getBootstrap();
        int autoCommit = autoCommitCombo.getSelectedIndex();
        bootstrap.setDefaultAutoCommit(autoCommit == 1 ? Boolean.TRUE : autoCommit == 2 ? Boolean.FALSE : null);
        bootstrap.setDefaultCatalogName(CommonUtils.nullIfEmpty(defaultCatalogField.getText().trim()));
        bootstrap.setDefaultSchemaName(CommonUtils.nullIfEmpty(defaultSchemaField.getText().trim()));
        // ConnectionPageInitialization.saveSettings
        bootstrap.setInitQueries(bootstrapQueries);
        bootstrap.setIgnoreErrors(ignoreBootstrapErrors);
        // ConnectionPageGeneral.saveSettings: the custom color, or none (the connection type's color)
        configuration.setConnectionColor(customColorCheck.isSelected() && customColor != null ? ConnectionColors.format(customColor) : null);
        target.setConnectionReadOnly(readOnlyCheck.isSelected());
        configuration.setKeepAliveInterval((Integer) keepAliveSpinner.getValue());
        configuration.setCloseIdleConnection(closeIdleCheck.isSelected());
        int closeIdlePeriod = (Integer) closeIdleSpinner.getValue();
        // Saved only when enabled and different from the connection type's default
        configuration.setCloseIdleInterval(configuration.isCloseIdleConnection()
            && closeIdlePeriod != configuration.getConnectionType().getCloseIdleConnectionPeriod() ? closeIdlePeriod : 0);
    }

    /**
     * A field that keeps its preferred width in a form row.
     */
    @NotNull
    static JComponent leftAligned(@NotNull JComponent field) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(field, BorderLayout.WEST);
        return panel;
    }

    //////////////////////////////////////////////////////////
    // Address

    private boolean isUrlMode() {
        return urlOnly || urlModeRadio.isSelected();
    }

    private void modeChanged() {
        boolean urlMode = isUrlMode();
        urlField.setEditable(urlMode);
        for (JComponent component : manualComponents) {
            component.setEnabled(!urlMode);
        }
        if (!urlMode) {
            updateGeneratedUrl();
        }
        changeListener.run();
    }

    private void fieldChanged(@NotNull DocumentEvent event) {
        if (loading) {
            return;
        }
        if (!isUrlMode() && event.getDocument() != urlField.getDocument()) {
            // Invoked later: the URL field can't be changed while one of its own document events is being delivered
            SwingUtilities.invokeLater(this::updateGeneratedUrl);
        }
        changeListener.run();
    }

    /**
     * Host mode: shows the URL the driver builds from the fields (read-only), or why it can't be built.
     */
    private void updateGeneratedUrl() {
        if (isUrlMode()) {
            return;
        }
        DBPConnectionConfiguration configuration = new DBPConnectionConfiguration();
        configuration.setConfigurationType(DBPDriverConfigurationType.MANUAL);
        writeAddress(configuration);
        if (driverFields != null) {
            driverFields.saveSettings(configuration, false);
        }
        if (!driver.isAnonymousAccess()) {
            configuration.setUserName(GeneralUtils.trimAllWhitespaces(userField.getText()));
        }
        String url;
        String problem = null;
        try {
            url = driver.getConnectionURL(configuration);
        } catch (DBException e) {
            url = null;
            problem = ErrorText.describe(e);
        }
        loading = true;
        try {
            urlField.setText(CommonUtils.notEmpty(url));
            urlField.setCaretPosition(0);
        } finally {
            loading = false;
        }
        urlField.setToolTipText(problem);
    }

    /**
     * Host, port, server, database and path, for the parameters the URL template has (GenericConnectionPage.saveSettings).
     * A server name without a port uses the driver's default port.
     */
    private void writeAddress(@NotNull DBPConnectionConfiguration configuration) {
        String[] hostAndPort = getHostAndPort();
        if (urlProperties.contains(DBConstants.PROP_HOST)) {
            configuration.setHostName(hostAndPort[0]);
        }
        if (urlProperties.contains(DBConstants.PROP_PORT)) {
            configuration.setHostPort(hostAndPort[1].isEmpty() ? CommonUtils.notEmpty(driver.getDefaultPort()) : hostAndPort[1]);
        }
        if (urlProperties.contains(DBConstants.PROP_SERVER)) {
            configuration.setServerName(serverField.getText().trim());
        }
        if (urlProperties.contains(DBConstants.PROP_DATABASE)) {
            configuration.setDatabaseName(databaseField.getText().trim());
        }
        if (urlProperties.contains(DBConstants.PROP_FOLDER) || urlProperties.contains(DBConstants.PROP_FILE)) {
            configuration.setDatabaseName(pathField.getText().trim());
        }
    }

    /**
     * Host and port typed in the server name field ({@code [host, port]}, the port may be empty).
     */
    @NotNull
    private String[] getHostAndPort() {
        if (!urlProperties.contains(DBConstants.PROP_HOST)) {
            return new String[]{"", ""};
        }
        return splitServerName(serverNameField.getText(), urlProperties.contains(DBConstants.PROP_PORT));
    }

    /**
     * Splits an SSMS-style server name: {@code host}, {@code host:port}, {@code host,port} (SSMS) or {@code [IPv6]:port}.
     * A name with more than one ':' and no brackets is an IPv6 address without a port.
     *
     * @param withPort the URL template has a port: otherwise the whole text is the host
     */
    @NotNull
    static String[] splitServerName(@NotNull String text, boolean withPort) {
        String server = text.trim();
        if (!withPort) {
            return new String[]{server, ""};
        }
        int separator;
        if (server.startsWith("[")) { //$NON-NLS-1$
            int end = server.indexOf(']');
            separator = end < 0 ? -1 : end + 1 < server.length() ? end + 1 : -1;
        } else if (server.indexOf(',') >= 0) {
            separator = server.lastIndexOf(',');
        } else {
            separator = server.indexOf(':') >= 0 && server.indexOf(':') == server.lastIndexOf(':') ? server.indexOf(':') : -1;
        }
        if (separator < 0 || (server.charAt(separator) != ':' && server.charAt(separator) != ',')) {
            return new String[]{server, ""};
        }
        return new String[]{server.substring(0, separator).trim(), server.substring(separator + 1).trim()};
    }

    /**
     * The server name shown for a host and port: the port is left out when it is the driver's default one.
     */
    @NotNull
    private String formatServerName(@Nullable String host, @Nullable String port) {
        String server = CommonUtils.notEmpty(host);
        if (!CommonUtils.isEmpty(port) && !port.equals(driver.getDefaultPort()) && urlProperties.contains(DBConstants.PROP_PORT)) {
            server += ":" + port; //$NON-NLS-1$
        }
        return server;
    }

    private void browsePath(boolean folder) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(folder ? ConnectionMessages.settings_chooser_folder : ConnectionMessages.settings_chooser_file);
        chooser.setFileSelectionMode(folder ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        String current = pathField.getText().trim();
        if (!current.isEmpty()) {
            File file = new File(current);
            if (folder || file.isDirectory()) {
                chooser.setCurrentDirectory(file);
            } else {
                chooser.setSelectedFile(file);
            }
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            pathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    //////////////////////////////////////////////////////////
    // Driver properties

    private void loadDriverPropertiesIfShown() {
        if (optionsTabs.isVisible() && optionsTabs.getSelectedComponent() == driverPropertiesTable && !driverPropertiesRequested) {
            driverPropertiesRequested = true;
            // After the tab switch is painted
            SwingUtilities.invokeLater(this::loadDriverProperties);
        }
    }

    /**
     * Reads the driver's properties like DriverPropertiesDialogPage: on a temporary connection that has the current
     * form values, because some drivers report different properties for different URLs.
     */
    private void loadDriverProperties() {
        driverPropertiesTable.setSource(null, ConnectionMessages.settings_driver_properties_loading);
        DBPConnectionConfiguration temporaryConfiguration = new DBPConnectionConfiguration();
        DataSourceDescriptor temporaryDataSource = dataSource.getRegistry().createDataSource(
            dataSource.getId(), driver, temporaryConfiguration);
        String problem = null;
        PropertySourceCustom source = null;
        try {
            saveSettings(temporaryDataSource, true);
            temporaryConfiguration.getProperties().putAll(dataSource.getConnectionConfiguration().getProperties());
            Map<String, Object> values = new HashMap<>(driver.getConnectionProperties());
            values.putAll(temporaryConfiguration.getProperties());
            boolean substituted = dataSource.getDriverSubstitution() != null;
            String[] error = new String[1];
            List<DBPPropertyDescriptor> descriptors = ProgressDialog.runTask(ConnectionMessages.settings_driver_properties_task, monitor -> {
                List<DBPPropertyDescriptor> result = new ArrayList<>();
                if (!substituted) {
                    // Substitution drivers: not supported by SWT either (ConnectionPropertiesControl.makeProperties)
                    try {
                        DBPPropertyDescriptor[] driverProperties = driver.getDataSourceProvider().getConnectionProperties(
                            monitor, driver, temporaryDataSource, temporaryConfiguration);
                        if (driverProperties != null) {
                            Collections.addAll(result, driverProperties);
                        }
                    } catch (DBException e) {
                        log.warn("Can't load driver properties", e);
                        error[0] = ErrorText.describe(e);
                    }
                }
                return result;
            });
            // Values that are not driver properties become user properties (ConnectionPropertiesControl.loadCustomProperties)
            Set<String> known = new HashSet<>();
            for (DBPPropertyDescriptor descriptor : descriptors) {
                known.add(descriptor.getId());
            }
            for (String name : values.keySet()) {
                if (!known.contains(name)) {
                    descriptors.add(new PropertyDescriptor(ConnectionPropertiesTable.USER_PROPERTIES_CATEGORY,
                        name, name, null, String.class, false, null, null, true));
                }
            }
            source = new PropertySourceCustom(descriptors, values);
            problem = error[0] == null ? null : NLS.bind(ConnectionMessages.settings_driver_properties_error, error[0]);
        } catch (DBException e) {
            problem = NLS.bind(ConnectionMessages.settings_driver_properties_error, ErrorText.describe(e));
        } finally {
            temporaryDataSource.dispose();
        }
        if (source == null) {
            // Allow another try, e.g. after the driver files were downloaded
            driverPropertiesRequested = false;
        }
        driverPropertiesTable.setSource(source, problem);
    }

    /**
     * A 12x12 color square with a thin border; an empty square for "no color".
     */
    private static final class ColorSwatch implements Icon {
        @Nullable
        private final Color color;

        ColorSwatch(@Nullable Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            int size = getIconWidth();
            if (color != null) {
                g.setColor(color);
                g.fillRect(x, y, size, size);
            }
            g.setColor(ConnectionDialog.getThemeColor("Chromite.dividerColor", "Separator.foreground", Color.GRAY)); //$NON-NLS-1$ //$NON-NLS-2$
            g.drawRect(x, y, size - 1, size - 1);
        }

        @Override
        public int getIconWidth() {
            return 12;
        }

        @Override
        public int getIconHeight() {
            return 12;
        }
    }

    /**
     * Label/field rows on a GridBagLayout with the spacing of docs/chromite/design-spec.md "11. 대화상자"
     * (label to field 6, rows 6, groups 20). Hidden rows take no space.
     */
    static final class FormBuilder {
        private static final int LABEL_GAP = 6;
        private static final int ROW_GAP = 6;
        private static final int GROUP_GAP = 20;

        private final JPanel panel = new JPanel(new GridBagLayout());
        private final GridBagConstraints constraints = new GridBagConstraints();
        private int nextTopGap;

        FormBuilder() {
            constraints.gridy = 0;
            constraints.anchor = GridBagConstraints.WEST;
        }

        void addRow(@NotNull String label, @NotNull JComponent field) {
            addRow(new JLabel(label), field);
        }

        void addRow(@Nullable JLabel label, @NotNull JComponent field) {
            int top = constraints.gridy == 0 ? 0 : Math.max(ROW_GAP, nextTopGap);
            nextTopGap = 0;
            constraints.gridx = 0;
            constraints.gridwidth = 1;
            constraints.weightx = 0;
            constraints.fill = GridBagConstraints.NONE;
            constraints.insets = new Insets(top, 0, 0, LABEL_GAP);
            if (label != null) {
                label.setLabelFor(field);
                panel.add(label, constraints);
            }
            constraints.gridx = 1;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.insets = new Insets(top, 0, 0, 0);
            panel.add(field, constraints);
            constraints.gridy++;
        }

        /**
         * The next row starts a new group.
         */
        void addGroupGap() {
            nextTopGap = GROUP_GAP;
        }

        void addFiller() {
            constraints.gridx = 0;
            constraints.gridwidth = 2;
            constraints.weighty = 1;
            constraints.fill = GridBagConstraints.BOTH;
            constraints.insets = new Insets(0, 0, 0, 0);
            panel.add(Box.createGlue(), constraints);
            constraints.weighty = 0;
            constraints.gridwidth = 1;
            constraints.gridy++;
        }

        @NotNull
        JPanel getPanel() {
            return panel;
        }
    }
}
