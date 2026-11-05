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
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.connection.DBPDriverConfigurationType;
import org.jkiss.dbeaver.model.impl.PropertyDescriptor;
import org.jkiss.dbeaver.model.preferences.DBPPropertyDescriptor;
import org.jkiss.dbeaver.registry.DataSourceDescriptor;
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
import java.util.Set;

/**
 * 범용 연결 설정 화면(MVP 2번). 드라이버 정의만 보고 입력 칸을 정한다.
 *
 * 설계: SWT의 범용 연결 화면({@code GenericConnectionPage})과 같은 규칙을 모델 코드로 따른다.
 * <ul>
 *     <li>입력 칸: 드라이버의 예시 URL({@code sampleURL})을 {@link DatabaseURL#getUrlPattern}으로 읽어, 들어 있는 자리 표시
 *     ({@code {host}}, {@code {port}}, {@code {server}}, {@code {database}}, {@code {file}}/{@code {folder}})의 칸만 보여 준다.
 *     예시 URL이 없거나 그중 아무것도 없으면 URL만 입력한다.</li>
 *     <li>연결 방식: Host(칸으로 입력, URL은 {@code DBPDriver#getConnectionURL}로 만들어 읽기 전용으로 보여 줌) / URL(직접 입력).
 *     {@link DBPDriverConfigurationType}으로 저장한다.</li>
 *     <li>새 연결의 기본값: 드라이버의 기본 호스트(없으면 localhost)·포트·서버·DB·사용자(SWT와 같음).</li>
 *     <li>인증: 사용자·비밀번호·비밀번호 저장. 드라이버가 익명 접속이면 숨긴다. 인증 방식은 고를 수 없고, 연결의 현재 방식
 *     ({@code detectConnectionAuthModel}, 새 연결은 기본 "Database Native")을 그대로 저장한다.</li>
 *     <li>Driver Properties 탭: 처음 열 때 모델의 {@code DBPDataSourceProvider#getConnectionProperties}(JDBC 드라이버의
 *     {@code getPropertyInfo})로 읽는다(SWT {@code ConnectionPropertiesControl}과 같은 호출, 드라이버 파일이 없으면 이때 내려받기를 묻는다).
 *     열지 않았으면 저장할 때 연결의 드라이버 속성을 건드리지 않는다.</li>
 *     <li>Provider Properties 탭: 드라이버 정의의 공급자 속성({@code getProviderPropertyDescriptors}, 예: Oracle SID/Service, 역할).
 *     SWT에서는 DB별 전용 화면이 이 값을 고친다. 여기서는 범용 표로 고친다.</li>
 *     <li>파일 기반 드라이버({@code {file}}, {@code {folder}}): Browse로 파일(또는 폴더)을 고른다.</li>
 * </ul>
 * 저장은 {@link #saveSettings}가 SWT의 페이지 저장 순서(주소 → 인증 → URL → 속성)대로 {@link DBPConnectionConfiguration}에 쓴다.
 *
 * 변경 이력:
 * - 2026-11-06 D단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 */
final class ConnectionSettingsPanel extends JPanel {

    private static final Log log = Log.getLog(ConnectionSettingsPanel.class);

    private final DataSourceDescriptor dataSource;
    private final DBPDriver driver;
    private final Runnable changeListener;

    @Nullable
    private final DatabaseURL.Pattern urlPattern;
    private final Set<String> urlProperties;
    private final boolean urlOnly;

    private final JTextField nameField = new JTextField();
    private final JRadioButton hostModeRadio = new JRadioButton("Host");
    private final JRadioButton urlModeRadio = new JRadioButton("URL");
    private final JTextField urlField = new JTextField();
    private final JTextField hostField = new JTextField();
    private final JTextField portField = new JTextField(6);
    private final JTextField serverField = new JTextField();
    private final JTextField databaseField = new JTextField();
    private final JTextField pathField = new JTextField();
    private final JTextField userField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JCheckBox savePasswordCheck = new JCheckBox("Save password");
    private final List<JComponent> manualComponents = new ArrayList<>();

    private final JTabbedPane tabs = new JTabbedPane();
    private final ConnectionPropertiesTable driverPropertiesTable = new ConnectionPropertiesTable(true);
    @Nullable
    private final ConnectionPropertiesTable providerPropertiesTable;
    private boolean driverPropertiesRequested;
    private boolean loading;

    /**
     * @param dataSource     the edited (or new, not yet registered) connection; it is only read here
     * @param isNew          new connection: fields start with the driver defaults
     * @param changeListener called when a value changes (validation)
     */
    ConnectionSettingsPanel(@NotNull DataSourceDescriptor dataSource, boolean isNew, @NotNull Runnable changeListener) {
        super(new BorderLayout());
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

        DBPPropertyDescriptor[] providerDescriptors = driver.getProviderPropertyDescriptors();
        providerPropertiesTable = providerDescriptors == null || providerDescriptors.length == 0 ? null : new ConnectionPropertiesTable(false);

        tabs.addTab("Main", createMainPage());
        tabs.addTab("Driver Properties", driverPropertiesTable);
        if (providerPropertiesTable != null) {
            tabs.addTab("Provider Properties", providerPropertiesTable);
        }
        driverPropertiesTable.setSource(null, "Driver properties are read when this tab is opened.");
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == driverPropertiesTable && !driverPropertiesRequested) {
                driverPropertiesRequested = true;
                // After the tab switch is painted
                SwingUtilities.invokeLater(this::loadDriverProperties);
            }
        });
        add(tabs, BorderLayout.CENTER);

        loadSettings(isNew, providerDescriptors);
    }

    @NotNull
    DBPDriver getDriver() {
        return driver;
    }

    @NotNull
    String getConnectionName() {
        return nameField.getText().trim();
    }

    void focusFirstField() {
        JTextField first = urlOnly ? urlField
            : urlProperties.contains(DBConstants.PROP_HOST) ? hostField
            : urlProperties.contains(DBConstants.PROP_FILE) || urlProperties.contains(DBConstants.PROP_FOLDER) ? pathField
            : urlProperties.contains(DBConstants.PROP_SERVER) ? serverField
            : databaseField;
        first.requestFocusInWindow();
    }

    void focusNameField() {
        nameField.requestFocusInWindow();
        nameField.selectAll();
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
        for (String property : urlPattern.getMandatoryPropertyNames()) {
            String missing = switch (property) {
                case DBConstants.PROP_HOST -> hostField.getText().isBlank() ? "host" : null;
                case DBConstants.PROP_PORT -> portField.getText().isBlank() ? "port" : null;
                case DBConstants.PROP_DATABASE -> databaseField.getText().isBlank() ? "database" : null;
                case DBConstants.PROP_SERVER -> serverField.getText().isBlank() ? "server" : null;
                case DBConstants.PROP_FILE, DBConstants.PROP_FOLDER -> pathField.getText().isBlank() ? "path" : null;
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

        if (!driver.isAnonymousAccess()) {
            configuration.setUserName(GeneralUtils.trimAllWhitespaces(userField.getText()));
            boolean savePassword = forTest || savePasswordCheck.isSelected();
            configuration.setUserPassword(savePassword ? GeneralUtils.trimAllWhitespaces(new String(passwordField.getPassword())) : null);
            target.setSavePassword(savePassword);
            // No auth model selector: keep the connection's model (new connections: the default one)
            configuration.setAuthModelId(driver.getDataSourceProvider().detectConnectionAuthModel(driver, configuration).getId());
            if (!savePassword) {
                // Also the password properties of the auth model, as EditConnectionWizard.saveSettings does
                target.resetPassword();
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
    }

    //////////////////////////////////////////////////////////
    // Main page

    @NotNull
    private JComponent createMainPage() {
        FormBuilder form = new FormBuilder();
        nameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Generated from the database or host when empty");
        form.addRow("Connection name:", nameField);

        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(hostModeRadio);
        modeGroup.add(urlModeRadio);
        hostModeRadio.addActionListener(e -> modeChanged());
        urlModeRadio.addActionListener(e -> modeChanged());
        if (!urlOnly) {
            JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            modePanel.add(hostModeRadio);
            modePanel.add(Box.createHorizontalStrut(12));
            modePanel.add(urlModeRadio);
            form.addRow("Connect by:", modePanel);
        }
        form.addRow("JDBC URL:", urlField);

        if (urlProperties.contains(DBConstants.PROP_HOST)) {
            JPanel hostPanel = new JPanel(new BorderLayout(8, 0));
            hostPanel.add(hostField, BorderLayout.CENTER);
            JPanel portPanel = new JPanel(new BorderLayout(6, 0));
            JLabel portLabel = new JLabel("Port:");
            portPanel.add(portLabel, BorderLayout.WEST);
            portPanel.add(portField, BorderLayout.CENTER);
            hostPanel.add(portPanel, BorderLayout.EAST);
            form.addRow("Host:", hostPanel);
            manualComponents.add(hostField);
            manualComponents.add(portField);
            manualComponents.add(portLabel);
        }
        if (urlProperties.contains(DBConstants.PROP_SERVER)) {
            form.addRow("Server:", serverField);
            manualComponents.add(serverField);
        }
        if (urlProperties.contains(DBConstants.PROP_DATABASE)) {
            form.addRow("Database:", databaseField);
            manualComponents.add(databaseField);
        }
        if (urlProperties.contains(DBConstants.PROP_FILE) || urlProperties.contains(DBConstants.PROP_FOLDER)) {
            boolean folder = !urlProperties.contains(DBConstants.PROP_FILE);
            JButton browseButton = new JButton("Browse…");
            browseButton.addActionListener(e -> browsePath(folder));
            JPanel pathPanel = new JPanel(new BorderLayout(6, 0));
            pathPanel.add(pathField, BorderLayout.CENTER);
            pathPanel.add(browseButton, BorderLayout.EAST);
            form.addRow(folder ? "Folder:" : "Database file:", pathPanel);
            manualComponents.add(pathField);
            manualComponents.add(browseButton);
        }

        if (!driver.isAnonymousAccess()) {
            form.addSeparator("Authentication");
            form.addRow("User name:", userField);
            form.addRow("Password:", passwordField);
            form.addRow(null, savePasswordCheck);
        }

        JLabel driverLabel = new JLabel("Driver: " + driver.getFullName(), NodeIcons.getIcon(driver.getIcon()), SwingConstants.LEFT);
        driverLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        form.addFiller();
        form.addFullRow(driverLabel);

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
        for (JTextField field : List.of(urlField, hostField, portField, serverField, databaseField, pathField, userField)) {
            field.getDocument().addDocumentListener(listener);
        }
        JPanel page = form.getPanel();
        page.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        return page;
    }

    private void loadSettings(boolean isNew, @Nullable DBPPropertyDescriptor[] providerDescriptors) {
        loading = true;
        try {
            DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
            nameField.setText(isNew ? "" : dataSource.getName());
            // Defaults of GenericConnectionPage.loadSettings
            hostField.setText(!CommonUtils.isEmpty(configuration.getHostName()) ? configuration.getHostName()
                : CommonUtils.toString(driver.getDefaultHost(), DBConstants.HOST_LOCALHOST));
            portField.setText(isNew && CommonUtils.isEmpty(configuration.getHostPort())
                ? CommonUtils.notEmpty(driver.getDefaultPort()) : CommonUtils.notEmpty(configuration.getHostPort()));
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
        } finally {
            loading = false;
        }
        modeChanged();
    }

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
     */
    private void writeAddress(@NotNull DBPConnectionConfiguration configuration) {
        if (urlProperties.contains(DBConstants.PROP_HOST)) {
            configuration.setHostName(hostField.getText().trim());
        }
        if (urlProperties.contains(DBConstants.PROP_PORT)) {
            configuration.setHostPort(portField.getText().trim());
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
     * Label/field rows on a GridBagLayout.
     */
    private static final class FormBuilder {
        private final JPanel panel = new JPanel(new GridBagLayout());
        private final GridBagConstraints constraints = new GridBagConstraints();

        FormBuilder() {
            constraints.gridy = 0;
            constraints.insets = new Insets(3, 0, 3, 8);
            constraints.anchor = GridBagConstraints.WEST;
        }

        void addRow(@Nullable String label, @NotNull JComponent field) {
            constraints.gridx = 0;
            constraints.gridwidth = 1;
            constraints.weightx = 0;
            constraints.fill = GridBagConstraints.NONE;
            if (label != null) {
                JLabel labelComponent = new JLabel(label);
                labelComponent.setLabelFor(field);
                panel.add(labelComponent, constraints);
            }
            constraints.gridx = 1;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            panel.add(field, constraints);
            constraints.gridy++;
        }

        void addSeparator(@NotNull String title) {
            JLabel label = new JLabel(title);
            label.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4"); //$NON-NLS-1$
            JPanel separatorRow = new JPanel(new BorderLayout(8, 0));
            separatorRow.add(label, BorderLayout.WEST);
            JSeparator separator = new JSeparator();
            JPanel separatorHolder = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            separatorHolder.add(separator, c);
            separatorRow.add(separatorHolder, BorderLayout.CENTER);
            Insets oldInsets = constraints.insets;
            constraints.insets = new Insets(12, 0, 4, 0);
            addFullRow(separatorRow);
            constraints.insets = oldInsets;
        }

        void addFullRow(@NotNull JComponent component) {
            constraints.gridx = 0;
            constraints.gridwidth = 2;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            panel.add(component, constraints);
            constraints.gridwidth = 1;
            constraints.gridy++;
        }

        void addFiller() {
            constraints.gridx = 0;
            constraints.gridwidth = 2;
            constraints.weighty = 1;
            constraints.fill = GridBagConstraints.BOTH;
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
