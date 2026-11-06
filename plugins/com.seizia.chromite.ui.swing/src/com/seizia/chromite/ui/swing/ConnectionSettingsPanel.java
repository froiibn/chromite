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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.DatabaseURL;
import org.jkiss.dbeaver.model.connection.DBPAuthModelDescriptor;
import org.jkiss.dbeaver.model.connection.DBPConnectionBootstrap;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPConnectionType;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.connection.DBPDriverConfigurationType;
import org.jkiss.dbeaver.model.impl.PropertyDescriptor;
import org.jkiss.dbeaver.model.preferences.DBPPropertyDescriptor;
import org.jkiss.dbeaver.registry.DataSourceDescriptor;
import org.jkiss.dbeaver.registry.DataSourceProviderRegistry;
import org.jkiss.dbeaver.runtime.properties.PropertySourceCustom;
import org.jkiss.dbeaver.utils.GeneralUtils;
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
 *     <li>Options 탭: Connection Properties(연결 종류, 자동 커밋, 기본 DB·스키마, 읽기 전용, keep-alive, 유휴 연결 닫기. SWT
 *     {@code ConnectionPageGeneral}·{@code ConnectionPageInitialization}과 같은 값), Driver Properties, Provider Properties,
 *     Additional Connection Parameters(연결 방식: 칸 / JDBC URL 직접 입력, {@link DBPDriverConfigurationType}).</li>
 *     <li>Driver Properties 탭: 처음 열 때 모델의 {@code DBPDataSourceProvider#getConnectionProperties}(JDBC 드라이버의
 *     {@code getPropertyInfo})로 읽는다(SWT {@code ConnectionPropertiesControl}과 같은 호출, 드라이버 파일이 없으면 이때 내려받기를 묻는다).
 *     열지 않았으면 저장할 때 연결의 드라이버 속성을 건드리지 않는다.</li>
 *     <li>Provider Properties 탭: 드라이버 정의의 공급자 속성({@code getProviderPropertyDescriptors}, 예: Oracle SID/Service, 역할).
 *     SWT에서는 DB별 전용 화면이 이 값을 고친다. 여기서는 범용 표로 고친다.</li>
 *     <li>새 연결의 기본값: 이 드라이버의 마지막 서버 이름(없으면 드라이버의 기본 호스트, 그것도 없으면 localhost)·포트·서버·DB·사용자.</li>
 * </ul>
 * 저장은 {@link #saveSettings}가 SWT의 페이지 저장 순서(주소 → 인증 → URL → 속성 → 연결 속성)대로 {@link DBPConnectionConfiguration}에 쓴다.
 * 색은 테마 키({@code Chromite.*}, 없으면 FlatLaf 키)로 읽어 테마를 바꾸는 작업과 독립적으로 둔다.
 *
 * 변경 이력:
 * - 2026-11-06 D단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 * - 2026-11-06 SSMS식 Connect 대화상자: Main/Driver/Provider 탭 → 주 양식 + Options 탭. Server name 한 칸과 서버 기록,
 *   인증 방식 선택, Connection Properties·Additional Connection Parameters 탭 추가, 라벨 끝 ':' 제거
 *   (docs/chromite/swing-frontend.md "Connect 대화상자 (SSMS식)")
 */
final class ConnectionSettingsPanel extends JPanel {

    private static final Log log = Log.getLog(ConnectionSettingsPanel.class);

    private static final String[] AUTO_COMMIT_ITEMS = {"Default (connection type)", "Auto-commit", "Manual commit"};

    private final DataSourceDescriptor dataSource;
    private final DBPDriver driver;
    private final Runnable changeListener;

    @Nullable
    private final DatabaseURL.Pattern urlPattern;
    private final Set<String> urlProperties;
    private final boolean urlOnly;

    private final JTextField nameField = new JTextField();
    private final JRadioButton hostModeRadio = new JRadioButton("Server name and database fields");
    private final JRadioButton urlModeRadio = new JRadioButton("JDBC URL");
    private final JTextField urlField = new JTextField();
    private final JComboBox<String> serverCombo = new JComboBox<>();
    private final JTextField serverNameField;
    private final JTextField serverField = new JTextField();
    private final JTextField databaseField = new JTextField();
    private final JTextField pathField = new JTextField();
    private final List<JComponent> manualComponents = new ArrayList<>();

    private final List<DBPAuthModelDescriptor> authModels;
    private final JComboBox<DBPAuthModelDescriptor> authCombo = new JComboBox<>();
    private final JLabel userLabel = new JLabel("Login");
    private final JTextField userField = new JTextField();
    private final JLabel passwordLabel = new JLabel("Password");
    private final JPasswordField passwordField = new JPasswordField();
    private final JCheckBox savePasswordCheck = new JCheckBox("Remember password");

    private final JComboBox<DBPConnectionType> connectionTypeCombo = new JComboBox<>();
    private final JComboBox<String> autoCommitCombo = new JComboBox<>(AUTO_COMMIT_ITEMS);
    private final JTextField defaultCatalogField = new JTextField();
    private final JTextField defaultSchemaField = new JTextField();
    private final JCheckBox readOnlyCheck = new JCheckBox("Read-only connection");
    private final JSpinner keepAliveSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
    private final JCheckBox closeIdleCheck = new JCheckBox("Close idle connection after (seconds)");
    private final JSpinner closeIdleSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));

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
        this.changeListener = changeListener;

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

        DBPPropertyDescriptor[] providerDescriptors = driver.getProviderPropertyDescriptors();
        providerPropertiesTable = providerDescriptors == null || providerDescriptors.length == 0 ? null : new ConnectionPropertiesTable(false);

        add(createMainForm(databaseTypeCombo), BorderLayout.NORTH);
        optionsTabs.addTab("Connection Properties", createConnectionPropertiesPage());
        optionsTabs.addTab("Driver Properties", driverPropertiesTable);
        if (providerPropertiesTable != null) {
            optionsTabs.addTab("Provider Properties", providerPropertiesTable);
        }
        if (!urlOnly) {
            optionsTabs.addTab("Additional Connection Parameters", createAdditionalParametersPage());
        }
        driverPropertiesTable.setSource(null, "Driver properties are read when this tab is opened.");
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
        if (isUrlMode()) {
            return urlField.getText().isBlank() ? "Enter the JDBC URL." : null;
        }
        String[] hostAndPort = getHostAndPort();
        if (!hostAndPort[1].isEmpty() && !hostAndPort[1].chars().allMatch(Character::isDigit)) {
            return "The port after the server name must be a number.";
        }
        for (String property : urlPattern.getMandatoryPropertyNames()) {
            String missing = switch (property) {
                case DBConstants.PROP_HOST -> hostAndPort[0].isBlank() ? "server name" : null;
                case DBConstants.PROP_PORT -> hostAndPort[1].isBlank() ? "port (server name:port)" : null;
                case DBConstants.PROP_DATABASE -> databaseField.getText().isBlank() ? "database" : null;
                case DBConstants.PROP_SERVER -> serverField.getText().isBlank() ? "server" : null;
                case DBConstants.PROP_FILE -> pathField.getText().isBlank() ? "file" : null;
                case DBConstants.PROP_FOLDER -> pathField.getText().isBlank() ? "folder" : null;
                default -> null;
            };
            if (missing != null) {
                return "Enter the " + missing + ".";
            }
        }
        return null;
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
        saveConnectionProperties(target);
    }

    //////////////////////////////////////////////////////////
    // Main form

    @NotNull
    private JComponent createMainForm(@NotNull JComponent databaseTypeCombo) {
        FormBuilder form = new FormBuilder();
        form.addRow("Database type", databaseTypeCombo);

        if (urlProperties.contains(DBConstants.PROP_HOST)) {
            serverNameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                urlProperties.contains(DBConstants.PROP_PORT) ? "host or host:port" : "host");
            serverCombo.setToolTipText(urlProperties.contains(DBConstants.PROP_PORT)
                ? "Host name or address, optionally followed by :port or ,port (default port " + CommonUtils.notEmpty(driver.getDefaultPort()) + ")"
                : "Host name or address");
            form.addRow("Server name", serverCombo);
            manualComponents.add(serverCombo);
        }
        if (urlProperties.contains(DBConstants.PROP_SERVER)) {
            form.addRow(urlProperties.contains(DBConstants.PROP_HOST) ? "Server instance" : "Server name", serverField);
            manualComponents.add(serverField);
        }
        if (urlProperties.contains(DBConstants.PROP_DATABASE)) {
            form.addRow("Database", databaseField);
            manualComponents.add(databaseField);
        }
        if (urlProperties.contains(DBConstants.PROP_FILE) || urlProperties.contains(DBConstants.PROP_FOLDER)) {
            boolean folder = !urlProperties.contains(DBConstants.PROP_FILE);
            JButton browseButton = new JButton("Browse…");
            browseButton.addActionListener(e -> browsePath(folder));
            JPanel pathPanel = new JPanel(new BorderLayout(6, 0));
            pathPanel.add(pathField, BorderLayout.CENTER);
            pathPanel.add(browseButton, BorderLayout.EAST);
            form.addRow(folder ? "Folder" : "File", pathPanel);
            manualComponents.add(pathField);
            manualComponents.add(browseButton);
        }
        if (urlOnly) {
            form.addRow("JDBC URL", urlField);
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
                form.addRow("Authentication", authCombo);
            }
            form.addRow(userLabel, userField);
            form.addRow(passwordLabel, passwordField);
            form.addRow((JLabel) null, savePasswordCheck);
        }

        form.addGroupGap();
        nameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Generated from the database or server name when empty");
        form.addRow("Connection name", nameField);

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
        userLabel.setText(user ? fields.userLabel() : "Login");
        passwordLabel.setText(password ? fields.passwordLabel() : "Password");
        userLabel.setVisible(user);
        userField.setVisible(user);
        passwordLabel.setVisible(password);
        passwordField.setVisible(password);
        savePasswordCheck.setVisible(password);
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
        });
        closeIdleCheck.addActionListener(e -> closeIdleSpinner.setEnabled(closeIdleCheck.isSelected()));
        for (JSpinner spinner : List.of(keepAliveSpinner, closeIdleSpinner)) {
            // Seconds without digit grouping
            JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "#"); //$NON-NLS-1$
            editor.getTextField().setColumns(7);
            spinner.setEditor(editor);
        }
        defaultCatalogField.setToolTipText("Name of the catalog (database) set as default after connecting");
        defaultSchemaField.setToolTipText("Name of the schema set as default after connecting");
        keepAliveSpinner.setToolTipText("Interval between queries that keep the connection alive; 0 turns keep-alive off");

        FormBuilder form = new FormBuilder();
        form.addRow("Connection type", connectionTypeCombo);
        form.addRow("Auto-commit", autoCommitCombo);
        form.addRow("Default database", defaultCatalogField);
        form.addRow("Default schema", defaultSchemaField);
        form.addRow((JLabel) null, readOnlyCheck);
        form.addGroupGap();
        form.addRow("Keep-alive (seconds)", leftAligned(keepAliveSpinner));
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
        form.addRow("Connect with", hostModeRadio);
        form.addRow((JLabel) null, urlModeRadio);
        form.addRow("JDBC URL", urlField);
        JLabel note = new JLabel("<html>With the fields, the URL is built from the driver's template and shown here. "
            + "Other driver parameters can be added in Driver Properties (Add…).</html>");
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
                providerPropertiesTable.setSource(
                    new PropertySourceCustom(descriptors, new HashMap<>(configuration.getProviderProperties())), null);
            }
            loadConnectionProperties(configuration);
        } finally {
            loading = false;
        }
        authModelChanged();
        modeChanged();
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
    private static JComponent leftAligned(@NotNull JComponent field) {
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
        if (!driver.isAnonymousAccess()) {
            configuration.setUserName(GeneralUtils.trimAllWhitespaces(userField.getText()));
        }
        String url;
        String problem = null;
        try {
            url = driver.getConnectionURL(configuration);
        } catch (DBException e) {
            url = null;
            problem = e.getMessage();
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
        chooser.setDialogTitle(folder ? "Database folder" : "Database file");
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
        driverPropertiesTable.setSource(null, "Loading driver properties…");
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
            List<DBPPropertyDescriptor> descriptors = ProgressDialog.runTask("Loading driver properties", monitor -> {
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
                        error[0] = CommonUtils.notEmpty(e.getMessage());
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
            problem = error[0] == null ? null : "Can't read the driver's properties: " + error[0];
        } catch (DBException e) {
            problem = "Can't read the driver's properties: " + e.getMessage();
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
     * Label/field rows on a GridBagLayout with the spacing of docs/chromite/design-spec.md "11. 대화상자"
     * (label to field 6, rows 6, groups 20). Hidden rows take no space.
     */
    private static final class FormBuilder {
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
