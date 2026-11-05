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
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPDataSourceFolder;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPConnectionType;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.connection.DBPNativeClientLocation;
import org.jkiss.dbeaver.registry.DataSourceDescriptor;
import org.jkiss.dbeaver.registry.driver.DriverDescriptor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.jobs.ConnectionTestJob;
import org.jkiss.dbeaver.utils.DataSourceUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.StringTokenizer;

/**
 * 연결 만들기·편집 대화상자(SSMS "Connect to Server"에 해당).
 *
 * 설계: 새 연결은 두 단계다. 1단계 드라이버 고르기({@link DriverSelectionPanel}), 2단계 범용 설정({@link ConnectionSettingsPanel}).
 * 편집은 2단계만 연다. 저장 방식은 SWT 마법사를 모델 코드로 따른다.
 * <ul>
 *     <li>새 연결(SWT {@code ConnectionWizard.getActiveDataSource}, {@code NewConnectionWizard.persistDataSource}):
 *     활성 프로젝트의 레지스트리로 아직 등록하지 않은 {@link DataSourceDescriptor}를 만들고(기본 연결 종류, 기본 클라이언트,
 *     비밀번호 저장 켬), Save에서 폼 값을 쓴 뒤 {@code DBPDataSourceRegistry.addDataSource}로 등록한다.
 *     {@code addDataSource}가 설정 파일({@code .dbeaver/data-sources.json})을 바로 저장하고 {@code OBJECT_ADD} 이벤트를 보내므로
 *     Object Explorer가 이벤트로 새 연결을 보여 준다. 드라이버를 바꾸면 이전 드라이버로 만든 연결은 버린다.</li>
 *     <li>편집(SWT {@code EditConnectionWizard}): 원본의 사본(ID 같음)에서 폼을 채우고, Save에서 바뀐 것이 있을 때만
 *     원본에 쓰고 {@code persistConfiguration}으로 저장한다. 연결 중이면 다시 연결할지 묻는다.</li>
 *     <li>Test Connection(SWT {@code ConnectionWizard.testConnection}): 폼 값을 쓴 임시 연결(새 ID, 임시, 메타데이터용
 *     별도 연결 없음)로 모델의 {@link ConnectionTestJob}을 돌리고, 진행 대화상자(취소 가능)에서 기다린 뒤 서버·드라이버 버전을 보여 준다.</li>
 *     <li>이름: 비워 두면 SWT 기본 이름 규칙({@code ${host_or_database}}, {@code ConnectionNameResolver}의 legacy 규칙: DB 이름 →
 *     호스트 → 서버 → 드라이버 이름)으로 만들고, 다른 연결과 겹치면 {@code DataSourceUtils.generateUniqueDataSourceName}으로 번호를 붙인다.
 *     사용자가 바꾼 이름 규칙 설정({@code navigator.settings.default.connectionPattern})은 아직 읽지 않는다.</li>
 * </ul>
 * 연결 폴더, 연결 종류(개발·운영 등), 읽기 전용, 탐색기 설정, 필터, 초기화 SQL, 네트워크 핸들러(SSH 등)는 SWT의 다른 페이지 몫이라
 * 여기서 바꾸지 않는다(편집할 때는 원래 값이 그대로 남는다).
 *
 * 변경 이력:
 * - 2026-11-06 D단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 */
final class ConnectionDialog extends JDialog {

    private static final String DRIVER_PAGE = "driver"; //$NON-NLS-1$
    private static final String SETTINGS_PAGE = "settings"; //$NON-NLS-1$

    private final DBPDataSourceRegistry registry;
    @Nullable
    private final DataSourceDescriptor original;
    @Nullable
    private final DBPDataSourceFolder folder;

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JPanel settingsHolder = new JPanel(new BorderLayout());
    @Nullable
    private final DriverSelectionPanel driverPanel;
    @Nullable
    private ConnectionSettingsPanel settingsPanel;
    /**
     * New connection: not yet registered; edit: a copy of the original with the same ID.
     */
    @Nullable
    private DataSourceDescriptor working;
    private boolean registered;

    private final JLabel headerLabel = new JLabel();
    private final JLabel problemLabel = new JLabel(" ");
    private final JButton backButton = new JButton("< Back");
    private final JButton nextButton = new JButton("Next >");
    private final JButton testButton = new JButton("Test Connection");
    private final JButton saveButton = new JButton("Save");
    private final JButton cancelButton = new JButton("Cancel");

    private ConnectionDialog(
        @Nullable Window owner,
        @NotNull DBPDataSourceRegistry registry,
        @Nullable DataSourceDescriptor original,
        @Nullable DBPDataSourceFolder folder
    ) {
        super(owner, original == null ? "New Connection" : "Edit Connection - " + original.getName(), ModalityType.APPLICATION_MODAL);
        this.registry = registry;
        this.original = original;
        this.folder = folder;
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });

        headerLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3"); //$NON-NLS-1$
        headerLabel.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));

        if (original == null) {
            driverPanel = new DriverSelectionPanel(this::updateButtons, this::showSettingsPage);
            driverPanel.setBorder(BorderFactory.createEmptyBorder(4, 14, 4, 14));
            cardPanel.add(driverPanel, DRIVER_PAGE);
        } else {
            driverPanel = null;
        }
        cardPanel.add(settingsHolder, SETTINGS_PAGE);

        backButton.addActionListener(e -> showDriverPage());
        nextButton.addActionListener(e -> showSettingsPage());
        testButton.addActionListener(e -> testConnection());
        saveButton.addActionListener(e -> save());
        cancelButton.addActionListener(e -> close());

        problemLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.error.focusedBorderColor"); //$NON-NLS-1$
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        if (original == null) {
            buttons.add(backButton);
            buttons.add(nextButton);
        }
        buttons.add(saveButton);
        buttons.add(cancelButton);
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.setBorder(BorderFactory.createEmptyBorder(8, 14, 12, 14));
        JPanel left = new JPanel(new BorderLayout(10, 0));
        left.add(testButton, BorderLayout.WEST);
        left.add(problemLabel, BorderLayout.CENTER);
        south.add(left, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout());
        content.add(headerLabel, BorderLayout.NORTH);
        content.add(cardPanel, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().registerKeyboardAction(e -> close(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        setSize(new Dimension(720, 620));
        setMinimumSize(new Dimension(560, 480));
        setLocationRelativeTo(owner);

        if (original != null) {
            working = registry.createDataSource(original);
            working.setId(original.getId());
            if (!working.isSavePassword()) {
                working.resetPassword();
            }
            createSettingsPanel(false);
            showCard(SETTINGS_PAGE);
        } else {
            showCard(DRIVER_PAGE);
        }
    }

    /**
     * New connection in a project (File > New Connection, Object Explorer). Blocks until the dialog is closed.
     *
     * @param folder connection folder of the new connection (the folder selected in Object Explorer)
     */
    static void openNew(@Nullable Window owner, @NotNull DBPProject project, @Nullable DBPDataSourceFolder folder) {
        new ConnectionDialog(owner, project.getDataSourceRegistry(), null, folder).setVisible(true);
    }

    /**
     * Edits a connection ({@code DBPPlatformUI.openConnectionEditor}, Object Explorer). Blocks until the dialog is closed.
     */
    static void openEdit(@Nullable Window owner, @NotNull DataSourceDescriptor dataSource) {
        new ConnectionDialog(owner, dataSource.getRegistry(), dataSource, null).setVisible(true);
    }

    //////////////////////////////////////////////////////////
    // Pages

    private void showDriverPage() {
        if (driverPanel == null) {
            return;
        }
        showCard(DRIVER_PAGE);
        driverPanel.focusSearch();
    }

    private void showSettingsPage() {
        DBPDriver driver = driverPanel == null ? null : driverPanel.getSelectedDriver();
        if (driver == null) {
            return;
        }
        if (working == null || working.getDriver() != driver) {
            if (working != null) {
                // Another driver: the connection made for the previous one is not needed any more
                working.dispose();
            }
            working = createNewDataSource(driver);
            createSettingsPanel(true);
        }
        showCard(SETTINGS_PAGE);
        settingsPanel.focusFirstField();
    }

    private void showCard(@NotNull String card) {
        cards.show(cardPanel, card);
        boolean settings = SETTINGS_PAGE.equals(card);
        if (original != null) {
            headerLabel.setText("Edit connection settings");
        } else {
            headerLabel.setText(settings && working != null
                ? "Connection settings: " + working.getDriver().getName()
                : "Connect to a database");
        }
        getRootPane().setDefaultButton(settings ? saveButton : nextButton);
        updateButtons();
    }

    private void createSettingsPanel(boolean isNew) {
        settingsHolder.removeAll();
        settingsPanel = new ConnectionSettingsPanel(working, isNew, this::updateButtons);
        settingsHolder.add(settingsPanel, BorderLayout.CENTER);
        settingsHolder.revalidate();
        settingsHolder.repaint();
    }

    private void updateButtons() {
        // CardLayout hides the cards that are not shown
        boolean settings = settingsPanel != null && (original != null || settingsHolder.isVisible());
        String problem = settings ? settingsPanel.validateSettings() : null;
        backButton.setEnabled(settings && original == null);
        nextButton.setEnabled(!settings && driverPanel != null && driverPanel.getSelectedDriver() != null);
        testButton.setEnabled(settings && problem == null);
        saveButton.setEnabled(settings && problem == null);
        problemLabel.setText(problem == null ? " " : problem);
    }

    /**
     * A not yet registered connection for the driver, as ConnectionWizard.getActiveDataSource creates it.
     */
    @NotNull
    private DataSourceDescriptor createNewDataSource(@NotNull DBPDriver driver) {
        DBPConnectionType connectionType = DBPConnectionType.getDefaultConnectionType();
        DBPConnectionConfiguration configuration = new DBPConnectionConfiguration();
        configuration.setConnectionType(connectionType);
        configuration.setCloseIdleConnection(connectionType.isAutoCloseConnections());
        DataSourceDescriptor dataSource = registry.createDataSource(DataSourceDescriptor.generateNewId(driver), driver, configuration);
        if (driver instanceof DriverDescriptor driverDescriptor) {
            DBPNativeClientLocation defaultClientLocation = driverDescriptor.getDefaultClientLocation();
            if (defaultClientLocation != null) {
                dataSource.getConnectionConfiguration().setClientHomeId(defaultClientLocation.getName());
            }
        }
        dataSource.setSavePassword(true);
        if (folder != null) {
            dataSource.setFolder(folder);
        }
        return dataSource;
    }

    //////////////////////////////////////////////////////////
    // Actions

    private void testConnection() {
        if (working == null || settingsPanel == null || settingsPanel.validateSettings() != null) {
            return;
        }
        DataSourceDescriptor testDataSource = registry.createDataSource(working);
        // New ID (createDataSource) to avoid session conflicts in the query manager; no separate metadata connection
        testDataSource.setTemporary(true);
        testDataSource.getPreferenceStore().setValue(
            ModelPreferences.META_SEPARATE_CONNECTION, ModelPreferences.SeparateConnectionBehavior.NEVER.name());
        settingsPanel.saveSettings(testDataSource, true);
        // Named like the saved connection would be: the name appears in the progress dialog and the log
        applyName(testDataSource);
        testDataSource.getConnectionConfiguration().setRuntimeAttribute(
            DBPConnectionConfiguration.ATTRIBUTE_TEST_CONNECTION_SOURCE, working);

        ConnectionTestJob testJob = new ConnectionTestJob(testDataSource, session -> {
            // No page-specific testers (SWT pages implementing IDataSourceConnectionTester)
        });
        try {
            ProgressDialog.runTask("Test Connection", monitor -> {
                testJob.setOwnerMonitor(monitor);
                testJob.schedule();
                try {
                    while (!testJob.join(100, null)) {
                        if (monitor.isCanceled()) {
                            testJob.cancel();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    testJob.cancel();
                }
                return null;
            });
            Throwable error = testJob.getConnectError();
            if (error != null) {
                DBWorkbench.getPlatformUI().showError("Connection Test", "Can't connect to the database", error);
            } else if (testJob.getConnectStatus() != null && testJob.getConnectStatus().isOK()) {
                JOptionPane.showMessageDialog(this,
                    "Connected (" + testJob.getConnectTime() + " ms)\n\n" +
                        "Server: " + testJob.getServerVersion() + "\n" +
                        "Driver: " + testJob.getClientVersion(),
                    "Connection Test",
                    JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError("Connection Test", "Can't test the connection", e);
        } finally {
            testDataSource.dispose();
        }
    }

    private void save() {
        if (working == null || settingsPanel == null || settingsPanel.validateSettings() != null) {
            return;
        }
        if (original == null) {
            settingsPanel.saveSettings(working, false);
            applyName(working);
            try {
                registry.addDataSource(working);
            } catch (DBException e) {
                DBWorkbench.getPlatformUI().showError("Create failed", "Error adding new connection", e);
                return;
            }
            registered = true;
            dispose();
            return;
        }

        // Edit: compare the settings first, like EditConnectionWizard.persistDataSource
        DataSourceDescriptor before = registry.createDataSource(original);
        before.setId(original.getId());
        DataSourceDescriptor after = registry.createDataSource(working);
        after.setId(working.getId());
        boolean changed;
        try {
            settingsPanel.saveSettings(after, false);
            applyName(after);
            changed = !before.equalSettings(after);
        } finally {
            before.dispose();
            after.dispose();
        }
        if (changed) {
            settingsPanel.saveSettings(original, false);
            applyName(original);
            if (!original.persistConfiguration()) {
                // persistConfiguration has shown the error
                return;
            }
        }
        close();
        if (changed && original.isConnected() && DBWorkbench.getPlatformUI().confirmAction("Connection changed",
            "Connection '" + original.getName() + "' has been changed. Do you want to reconnect?")) {
            ConnectionCommands.reconnect(original);
        }
    }

    private void close() {
        if (working != null && !registered) {
            working.dispose();
            working = null;
        }
        dispose();
    }

    /**
     * The typed name, or a generated one; made unique among the other connections of the project.
     */
    private void applyName(@NotNull DataSourceDescriptor target) {
        String name = settingsPanel == null ? "" : settingsPanel.getConnectionName();
        if (original != null && name.equals(original.getName())) {
            target.setName(name);
            return;
        }
        if (name.isEmpty()) {
            name = generateName(target);
        }
        DBPDataSourceContainer existing = registry.findDataSourceByName(name);
        if (existing != null && existing != original) {
            name = DataSourceUtils.generateUniqueDataSourceName(registry, name, 1);
        }
        target.setName(name);
    }

    /**
     * The legacy connection name of ConnectionNameResolver (the default name pattern {@code ${host_or_database}}):
     * the last non-numeric part of the database name (a file name for file databases), else the host, the server
     * or the driver name.
     */
    @NotNull
    private static String generateName(@NotNull DataSourceDescriptor dataSource) {
        DBPConnectionConfiguration configuration = dataSource.getConnectionConfiguration();
        String name = configuration.getDatabaseName();
        if (CommonUtils.isEmpty(name) || name.isBlank()) {
            name = configuration.getHostName();
        }
        if (CommonUtils.isEmpty(name)) {
            name = configuration.getServerName();
        }
        if (CommonUtils.isEmpty(name)) {
            name = dataSource.getDriver().getName();
        }
        if (CommonUtils.isEmpty(name)) {
            name = "New Connection";
        }
        StringTokenizer tokenizer = new StringTokenizer(name, "/\\"); //$NON-NLS-1$
        while (tokenizer.hasMoreTokens()) {
            String part = tokenizer.nextToken();
            if (!part.matches("[0-9]+")) { //$NON-NLS-1$
                name = part;
            }
        }
        return CommonUtils.trim(CommonUtils.truncateString(name, 50));
    }
}
