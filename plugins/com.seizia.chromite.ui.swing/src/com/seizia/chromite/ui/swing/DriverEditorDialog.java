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
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.DriverManagerMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.connection.DBPDriverLibrary;
import com.seizia.chromite.model.impl.PropertyDescriptor;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.registry.DataSourceProviderDescriptor;
import com.seizia.chromite.registry.DataSourceProviderRegistry;
import com.seizia.chromite.registry.driver.DriverDescriptor;
import com.seizia.chromite.registry.driver.DriverLibraryAbstract;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.properties.PropertySourceCustom;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 드라이버 만들기·복사·편집 대화상자(SWT {@code DriverEditDialog}의 Swing 판). 탭: Settings, Libraries, Default Properties,
 * Advanced Parameters, License(드라이버에 라이선스가 있을 때).
 *
 * 설계: SWT {@code DriverEditDialog}(ui.editors.connection)의 동작을 모델 API 그대로 옮겼다.
 * <ul>
 *     <li>세 가지로 연다(DriverEditDialog.java:121-155): 편집은 등록된 드라이버를 직접 고치고, 새로 만들기는
 *     {@code DataSourceProviderDescriptor.createDriver()}, 복사는 {@code createDriver(원본)}에 이름 뒤 "Copy"를 붙이고
 *     드라이버와 라이브러리를 사용자 정의({@code setCustom(true)})로 표시한다.</li>
 *     <li>공급자가 드라이버를 관리할 수 없으면({@code isDriversManageable() == false}) 설정 칸을 읽기 전용으로 둔다(라이브러리는 바꿀 수 있음, SWT와 같음).</li>
 *     <li>Reset to Defaults: SWT {@code loadSettings(true)}처럼 원래 값({@code getOrig*})과 원래 라이브러리를 폼에 다시 채운다.
 *     OK를 눌러야 드라이버에 쓴다. 사용자 정의 드라이버에는 원래 값이 없어 끈다.</li>
 *     <li>OK(DriverEditDialog.java:945-1023): 같은 범주에 같은 이름의 다른 드라이버가 있으면 막는다. SWT는 이 검사 전에 폼 값을 드라이버에 써서
 *     취소해도 바뀐 값이 남았는데, 여기서는 검사한 뒤에 쓴다. 그다음 폼 값을 쓰고, 드라이버 인스턴스(클래스 로더)를 버리고
 *     (SWT는 같은 이름의 드라이버가 있을 때만 버려 이름을 바꾸면 이전 jar로 계속 연결했다), 새 드라이버면 공급자에 더한 뒤
 *     {@code DataSourceProviderRegistry.saveDrivers()}로 {@code drivers.xml}에 저장한다({@code DriverDescriptorSerializerLegacy}).</li>
 *     <li>Default Properties: SWT {@code ConnectionPropertiesControl.makeProperties(driver, props)}처럼 드라이버 수준 연결 속성을
 *     사용자 속성으로만 보인다(JDBC 드라이버에 묻지 않음). Advanced Parameters: 공급자의 드라이버 매개 변수
 *     ({@code getDriverProperties()})와 기본값. 두 표 모두 {@link ConnectionPropertiesTable}을 그대로 쓴다.</li>
 * </ul>
 * SWT에 있던 Local Client 탭(네이티브 클라이언트 홈)과 분산 제품의 라이브러리 업로드는 없다(설계 문서 "남긴 것").
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-driver-manager.md)
 */
final class DriverEditorDialog extends JDialog implements DriverLibrariesPanel.Host {

    private static final Log log = Log.getLog(DriverEditorDialog.class);

    private final boolean newDriver;
    private DataSourceProviderDescriptor provider;
    private DriverDescriptor driver;
    private final List<DBPDriverLibrary> libraries = new ArrayList<>();
    private boolean accepted;

    private final JTextField nameField = new JTextField(24);
    private final JComboBox<DataSourceProviderDescriptor> providerCombo = new JComboBox<>();
    private final JTextField classField = new JTextField();
    private final JTextField urlField = new JTextField();
    private final JTextField portField = new JTextField(8);
    private final JTextField databaseField = new JTextField(16);
    private final JTextField userField = new JTextField(16);
    private final JCheckBox embeddedCheck = createCheck(DriverManagerMessages.driveredit_embedded, DriverManagerMessages.driveredit_embedded_tip);
    private final JCheckBox propagateCheck = createCheck(DriverManagerMessages.driveredit_propagate, DriverManagerMessages.driveredit_propagate_tip);
    private final JCheckBox anonymousCheck = createCheck(DriverManagerMessages.driveredit_anonymous, DriverManagerMessages.driveredit_anonymous_tip);
    private final JCheckBox emptyPasswordCheck = createCheck(DriverManagerMessages.driveredit_empty_password,
        DriverManagerMessages.driveredit_empty_password_tip);
    private final JCheckBox legacyCheck = createCheck(DriverManagerMessages.driveredit_legacy, DriverManagerMessages.driveredit_legacy_tip);
    private final JCheckBox threadSafeCheck = createCheck(DriverManagerMessages.driveredit_thread_safe, DriverManagerMessages.driveredit_thread_safe_tip);
    private final JTextField idField = new JTextField();
    private final JTextField providerIdField = new JTextField();
    private final JTextArea descriptionArea = new JTextArea(3, 40);
    private final ConnectionPropertiesTable connectionPropertiesTable = new ConnectionPropertiesTable(true);
    private final ConnectionPropertiesTable parametersTable = new ConnectionPropertiesTable(false);
    private final DriverLibrariesPanel librariesPanel;
    private final JButton okButton = new JButton(CoreMessages.common_ok);
    private final JLabel problemLabel = new JLabel(" ");

    private DriverEditorDialog(@Nullable Window owner, @NotNull DataSourceProviderDescriptor provider, @NotNull DriverDescriptor driver,
                               boolean newDriver) {
        super(owner, newDriver ? DriverManagerMessages.driveredit_title_new : NLS.bind(DriverManagerMessages.driveredit_title_edit, driver.getName()),
            ModalityType.APPLICATION_MODAL);
        this.provider = provider;
        this.driver = driver;
        this.newDriver = newDriver;
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        libraries.addAll(driver.getEnabledDriverLibraries());
        librariesPanel = new DriverLibrariesPanel(this, libraries);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(DriverManagerMessages.driveredit_tab_settings, createSettingsTab());
        tabs.addTab(DriverManagerMessages.driveredit_tab_libraries, null, librariesPanel, DriverManagerMessages.driveredit_tab_libraries_tip);
        connectionPropertiesTable.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        tabs.addTab(DriverManagerMessages.driveredit_tab_connection_properties, null, connectionPropertiesTable,
            DriverManagerMessages.driveredit_tab_connection_properties_tip);
        parametersTable.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        tabs.addTab(DriverManagerMessages.driveredit_tab_parameters, null, parametersTable, DriverManagerMessages.driveredit_tab_parameters_tip);
        String license = driver.getLicense();
        if (license != null) {
            JTextArea licenseArea = new JTextArea(license);
            licenseArea.setEditable(false);
            licenseArea.setLineWrap(true);
            licenseArea.setWrapStyleWord(true);
            licenseArea.setCaretPosition(0);
            JScrollPane licenseScroll = new JScrollPane(licenseArea);
            licenseScroll.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10), licenseScroll.getBorder()));
            tabs.addTab(DriverManagerMessages.driveredit_tab_license, licenseScroll);
        }

        JButton resetButton = new JButton(DriverManagerMessages.driveredit_reset);
        resetButton.setToolTipText(DriverManagerMessages.driveredit_reset_tip);
        // Custom drivers have no original settings (SWT disables the button too)
        resetButton.setEnabled(!driver.isCustom());
        resetButton.addActionListener(e -> loadSettings(true));
        okButton.addActionListener(e -> save());
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());
        problemLabel.setForeground(ConnectionDialog.getThemeColor("Chromite.status.error", "Component.error.focusedBorderColor", Color.RED)); //$NON-NLS-1$ //$NON-NLS-2$

        JPanel left = new JPanel(new BorderLayout(12, 0));
        left.add(resetButton, BorderLayout.WEST);
        left.add(problemLabel, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(okButton);
        right.add(cancelButton);
        JPanel south = new JPanel(new BorderLayout(12, 0));
        south.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        south.add(left, BorderLayout.CENTER);
        south.add(right, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout());
        // Dialog insets of docs/chromite/design-spec.md "11. 대화상자" (10, 12)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(tabs, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        loadSettings(false);
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                validateForm();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                validateForm();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                validateForm();
            }
        });
        validateForm();
        pack();
        setMinimumSize(new Dimension(Math.max(getWidth(), 720), Math.max(getHeight(), 520)));
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(() -> (newDriver ? nameField : classField).requestFocusInWindow());
    }

    /**
     * Edits a registered driver (Driver Manager Edit, the Connect dialog's Edit Driver). Blocks until closed.
     *
     * @return true if the driver was changed and saved
     */
    static boolean openEdit(@Nullable Window owner, @NotNull DriverDescriptor driver) {
        DriverEditorDialog dialog = new DriverEditorDialog(owner, driver.getProviderDescriptor(), driver, false);
        dialog.setVisible(true);
        return dialog.accepted;
    }

    /**
     * Creates a driver of the provider (SWT: {@code provider.createDriver()}). Blocks until closed.
     *
     * @return the new registered driver, or null if canceled
     */
    @Nullable
    static DriverDescriptor openNew(@Nullable Window owner, @NotNull DataSourceProviderDescriptor provider) {
        DriverEditorDialog dialog = new DriverEditorDialog(owner, provider, provider.createDriver(), true);
        dialog.setVisible(true);
        return dialog.accepted ? dialog.driver : null;
    }

    /**
     * Creates a custom copy of a driver (SWT: the copy constructor of DriverEditDialog). Blocks until closed.
     *
     * @return the new registered driver, or null if canceled
     */
    @Nullable
    static DriverDescriptor openCopy(@Nullable Window owner, @NotNull DriverDescriptor source) {
        DataSourceProviderDescriptor provider = source.getProviderDescriptor();
        DriverDescriptor copy = provider.createDriver(source);
        copy.setName(NLS.bind(DriverManagerMessages.driveredit_copy_name, copy.getName()));
        copy.setModified(true);
        // The copy and all its libraries are user-defined (SWT #3867): saved in drivers.xml as a whole
        copy.setCustom(true);
        for (DBPDriverLibrary library : copy.getDriverLibraries()) {
            if (library instanceof DriverLibraryAbstract libraryAbstract) {
                libraryAbstract.setCustom(true);
            }
        }
        DriverEditorDialog dialog = new DriverEditorDialog(owner, provider, copy, true);
        dialog.setVisible(true);
        return dialog.accepted ? dialog.driver : null;
    }

    //////////////////////////////////////////////////////////
    // Layout

    @NotNull
    private JComponent createSettingsTab() {
        boolean manageable = isManageable();

        providerCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof DataSourceProviderDescriptor descriptor) {
                    setText(descriptor.getName());
                    setIcon(NodeIcons.getIcon(descriptor.getIcon()));
                }
                return this;
            }
        });
        if (newDriver && driver.getOrigName() == null && driver.getDriverLibraries().isEmpty()) {
            // A brand-new driver can still go to another provider (SWT: the Driver Type combo of a new driver)
            for (DataSourceProviderDescriptor descriptor : DataSourceProviderRegistry.getInstance().getDataSourceProviders()) {
                if (descriptor.isDriversManageable()) {
                    providerCombo.addItem(descriptor);
                }
            }
            providerCombo.setSelectedItem(provider);
            providerCombo.addActionListener(e -> changeProvider());
        } else {
            providerCombo.addItem(provider);
            providerCombo.setEnabled(false);
        }

        classField.setToolTipText(DriverManagerMessages.driveredit_class_tip);
        urlField.setToolTipText(DriverManagerMessages.driveredit_url_tip);
        // SWT: the name of a predefined driver is not changed, nor the URL template of a driver that has none
        nameField.setEditable(manageable && driver.isCustom());
        urlField.setEditable(manageable && driver.isSampleURLApplicable());
        for (JTextField field : new JTextField[]{classField, portField, databaseField, userField}) {
            field.setEditable(manageable);
        }
        for (JCheckBox check : new JCheckBox[]{embeddedCheck, propagateCheck, anonymousCheck, emptyPasswordCheck, legacyCheck, threadSafeCheck}) {
            check.setEnabled(manageable);
        }
        descriptionArea.setEditable(manageable);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        idField.setEditable(false);
        idField.setToolTipText(DriverManagerMessages.driveredit_id_tip);
        providerIdField.setEditable(false);
        providerIdField.setToolTipText(DriverManagerMessages.driveredit_provider_id_tip);

        Form form = new Form();
        form.row(DriverManagerMessages.driveredit_name, nameField, DriverManagerMessages.driveredit_type, providerCombo);
        form.wide(DriverManagerMessages.driveredit_class, classField);
        form.wide(DriverManagerMessages.driveredit_url, urlField);
        form.row(DriverManagerMessages.driveredit_port, portField, DriverManagerMessages.driveredit_database, databaseField);
        form.row(DriverManagerMessages.driveredit_user, userField, null, null);

        JPanel checks = new JPanel(new GridLayout(0, 3, 12, 4));
        checks.add(embeddedCheck);
        checks.add(propagateCheck);
        checks.add(anonymousCheck);
        checks.add(emptyPasswordCheck);
        checks.add(legacyCheck);
        checks.add(threadSafeCheck);
        form.wide(null, checks);

        JLabel section = new JLabel(DriverManagerMessages.driveredit_section_description);
        section.putClientProperty(FlatClientProperties.STYLE, "font: bold"); //$NON-NLS-1$
        form.wide(null, section);
        form.row(DriverManagerMessages.driveredit_id, idField, DriverManagerMessages.driveredit_provider_id, providerIdField);
        form.wide(DriverManagerMessages.driveredit_description, new JScrollPane(descriptionArea));
        if (!CommonUtils.isEmpty(driver.getWebURL())) {
            JTextField website = new JTextField(driver.getWebURL());
            website.setEditable(false);
            website.setBorder(BorderFactory.createEmptyBorder());
            website.setOpaque(false);
            form.wide(DriverManagerMessages.driveredit_website, website);
        }
        if (!manageable) {
            JLabel note = new JLabel(DriverManagerMessages.driveredit_read_only);
            note.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            form.wide(null, note);
        }
        form.panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(form.panel, BorderLayout.NORTH);
        return holder;
    }

    @NotNull
    private static JCheckBox createCheck(@NotNull String text, @NotNull String tip) {
        JCheckBox check = new JCheckBox(text);
        check.setToolTipText(tip);
        return check;
    }

    /**
     * A new driver moved to another provider: a fresh driver of that provider (SWT does the same), keeping the
     * libraries added so far.
     */
    private void changeProvider() {
        if (!(providerCombo.getSelectedItem() instanceof DataSourceProviderDescriptor selected) || selected == provider) {
            return;
        }
        provider = selected;
        DriverDescriptor previous = driver;
        driver = provider.createDriver();
        List<DBPDriverLibrary> copies = new ArrayList<>();
        for (DBPDriverLibrary library : libraries) {
            copies.add(library instanceof DriverLibraryAbstract libraryAbstract ? libraryAbstract.copyLibrary(driver) : library);
        }
        libraries.clear();
        libraries.addAll(copies);
        idField.setText(driver.getId());
        providerIdField.setText(driver.getProviderId());
        parametersTable.setSource(createParametersSource(driver), null);
        librariesPanel.reload();
        log.debug("New driver moved from provider " + previous.getProviderId() + " to " + provider.getId());
    }

    //////////////////////////////////////////////////////////
    // Values

    /**
     * Fills the form from the driver, or from its original (plugin.xml) settings (SWT {@code loadSettings}).
     */
    private void loadSettings(boolean original) {
        nameField.setText(CommonUtils.notEmpty(original ? driver.getOrigName() : driver.getName()));
        descriptionArea.setText(CommonUtils.notEmpty(original ? driver.getOrigDescription() : driver.getDescription()));
        classField.setText(CommonUtils.notEmpty(original ? driver.getOrigClassName() : driver.getDriverClassName()));
        urlField.setText(CommonUtils.notEmpty(original ? driver.getOrigSampleURL() : driver.getSampleURL()));
        portField.setText(CommonUtils.notEmpty(original ? driver.getOrigDefaultPort() : driver.getDefaultPort()));
        databaseField.setText(CommonUtils.notEmpty(original ? driver.getOrigDefaultDatabase() : driver.getDefaultDatabase()));
        userField.setText(CommonUtils.notEmpty(original ? driver.getOrigDefaultUser() : driver.getDefaultUser()));
        propagateCheck.setSelected(original ? driver.isOrigPropagateDriverProperties() : driver.isPropagateDriverProperties());
        embeddedCheck.setSelected(original ? driver.isOrigEmbedded() : driver.isEmbedded());
        anonymousCheck.setSelected(original ? driver.isOrigAnonymousAccess() : driver.isAnonymousAccess());
        emptyPasswordCheck.setSelected(original ? driver.isOrigAllowsEmptyPassword() : driver.isAllowsEmptyPassword());
        legacyCheck.setSelected(original ? !driver.isOrigInstantiable() : !driver.isInstantiable());
        threadSafeCheck.setSelected(original ? driver.isOrigThreadSafeDriver() : driver.isThreadSafeDriver());
        idField.setText(driver.getId());
        providerIdField.setText(driver.getProviderId());
        if (original) {
            libraries.clear();
            libraries.addAll(driver.getOrigLibraries());
        }
        librariesPanel.reload();
        if (!original) {
            // SWT keeps the edited property values on reset too (the property sources are not recreated)
            connectionPropertiesTable.setSource(createConnectionPropertiesSource(driver), null);
            parametersTable.setSource(createParametersSource(driver), null);
        }
    }

    /**
     * Driver-level connection properties as user properties (SWT {@code ConnectionPropertiesControl.makeProperties(driver, props)}:
     * the JDBC driver is not asked for its properties here).
     */
    @NotNull
    private static PropertySourceCustom createConnectionPropertiesSource(@NotNull DriverDescriptor driver) {
        Map<String, Object> values = new HashMap<>(driver.getConnectionProperties());
        List<DBPPropertyDescriptor> descriptors = new ArrayList<>();
        for (String name : values.keySet()) {
            descriptors.add(new PropertyDescriptor(ConnectionPropertiesTable.USER_PROPERTIES_CATEGORY, name, name, null, String.class,
                false, null, null, true));
        }
        return new PropertySourceCustom(descriptors, values);
    }

    /**
     * The provider's driver parameters with the driver's values and defaults (SWT {@code createParametersTab}).
     */
    @NotNull
    private static PropertySourceCustom createParametersSource(@NotNull DriverDescriptor driver) {
        PropertySourceCustom source = new PropertySourceCustom(driver.getProviderDescriptor().getDriverProperties(), driver.getDriverParameters());
        source.addDefaultValues(driver.getDefaultDriverParameters());
        return source;
    }

    private void validateForm() {
        boolean valid = !nameField.getText().isBlank();
        okButton.setEnabled(valid);
        problemLabel.setText(valid ? " " : DriverManagerMessages.driveredit_name_required);
    }

    /**
     * Writes the form into a driver (SWT {@code saveDriverSettings}).
     */
    private void saveDriverSettings(@NotNull DriverDescriptor target) {
        target.setName(nameField.getText().trim());
        target.setDescription(CommonUtils.notEmpty(descriptionArea.getText()));
        target.setDriverClassName(classField.getText().trim(), true);
        target.setSampleURL(urlField.getText().trim());
        target.setDriverDefaultPort(portField.getText().trim());
        target.setDriverDefaultDatabase(databaseField.getText().trim());
        target.setDriverDefaultUser(userField.getText().trim());
        target.setEmbedded(embeddedCheck.isSelected());
        target.setPropagateDriverProperties(propagateCheck.isSelected());
        target.setAnonymousAccess(anonymousCheck.isSelected());
        target.setAllowsEmptyPassword(emptyPasswordCheck.isSelected());
        target.setInstantiable(!legacyCheck.isSelected());
        target.setThreadSafeDriver(threadSafeCheck.isSelected());
        target.setModified(true);

        connectionPropertiesTable.stopEditing();
        parametersTable.stopEditing();
        PropertySourceCustom parameters = parametersTable.getSource();
        if (parameters != null) {
            target.setDriverParameters(parameters.getPropertiesWithDefaults());
        }
        PropertySourceCustom connectionProperties = connectionPropertiesTable.getSource();
        if (connectionProperties != null) {
            target.setConnectionProperties(connectionProperties.getPropertyValues());
        }
        target.setDriverLibraries(libraries);
    }

    private void save() {
        String name = nameField.getText().trim();
        DBPDriver sameName = provider.getDriverByName(driver.getCategory(), name);
        if (sameName != null && sameName != driver && !sameName.isDisabled() && sameName.getReplacedBy() == null) {
            JOptionPane.showMessageDialog(this, NLS.bind(DriverManagerMessages.driveredit_exists, name),
                DriverManagerMessages.driveredit_exists_title, JOptionPane.ERROR_MESSAGE);
            return;
        }
        saveDriverSettings(driver);
        // The class loader still has the previous files and class: the next connection loads the driver again
        driver.resetDriverInstance();
        if (provider.getDriver(driver.getId()) == null) {
            provider.addDriver(driver);
        }
        try {
            provider.getRegistry().saveDrivers();
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(DriverManagerMessages.drivermgr_title, DriverManagerMessages.drivermgr_save_error, e);
            return;
        }
        accepted = true;
        dispose();
    }

    //////////////////////////////////////////////////////////
    // DriverLibrariesPanel.Host

    @NotNull
    @Override
    public DriverDescriptor getDriver() {
        return driver;
    }

    @NotNull
    @Override
    public DriverDescriptor createTestDriver() {
        DriverDescriptor test = new DriverDescriptor(driver.getProviderDescriptor(), "test", driver); //$NON-NLS-1$
        saveDriverSettings(test);
        return test;
    }

    @Override
    public boolean isManageable() {
        return provider.isDriversManageable();
    }

    @Override
    public void setDriverClassName(@NotNull String className) {
        classField.setText(className);
    }

    /**
     * Label/field rows in four columns (label, field, label, field) with the spacing of docs/chromite/design-spec.md "11. 대화상자".
     */
    private static final class Form {
        private final JPanel panel = new JPanel(new GridBagLayout());
        private int row;

        void row(@NotNull String label, @NotNull JComponent field, @Nullable String label2, @Nullable JComponent field2) {
            add(label, field, 0, 1);
            if (label2 != null && field2 != null) {
                add(label2, field2, 2, 1);
            }
            row++;
        }

        void wide(@Nullable String label, @NotNull JComponent field) {
            add(label, field, 0, 3);
            row++;
        }

        private void add(@Nullable String label, @NotNull JComponent field, int column, int span) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row;
            c.insets = new Insets(4, column == 0 ? 0 : 16, 4, 8);
            c.anchor = GridBagConstraints.LINE_START;
            if (label != null) {
                c.gridx = column;
                JLabel labelComponent = new JLabel(label);
                labelComponent.setLabelFor(field);
                panel.add(labelComponent, c);
            }
            c.gridx = column + 1;
            c.gridwidth = span;
            c.insets = new Insets(4, 0, 4, 0);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            if (label == null) {
                c.gridx = column;
                c.gridwidth = span + 1;
            }
            panel.add(field, c);
        }
    }
}
