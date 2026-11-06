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
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceFolder;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPConnectionType;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.connection.DBPNativeClientLocation;
import com.seizia.chromite.registry.DataSourceDescriptor;
import com.seizia.chromite.registry.driver.DriverDescriptor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.jobs.ConnectionTestJob;
import com.seizia.chromite.utils.DataSourceUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.StringTokenizer;

/**
 * 연결 만들기·편집 대화상자(SSMS "Connect to Server"에 해당). 한 화면이다.
 *
 * 설계: SSMS처럼 새 연결도 한 화면에서 끝낸다. 맨 위 "Database type" 콤보({@link DatabaseTypeCombo})로 드라이버를 고르면 그 아래
 * 설정 화면({@link ConnectionSettingsPanel})을 그 드라이버에 맞게 다시 만든다. "Options >>"는 설정 화면 아래의 탭(연결 속성, 드라이버·공급자 속성,
 * 추가 연결 매개변수)을 펼치거나 접는다. 단추는 왼쪽에 Test Connection, 오른쪽에 Connect(편집은 Save) / Cancel / Options.
 * Help는 열 문서(사용자용 도움말 주소)가 아직 없어 두지 않는다. 저장 방식은 SWT 마법사를 모델 코드로 따른다.
 * <ul>
 *     <li>새 연결(SWT {@code ConnectionWizard.getActiveDataSource}, {@code NewConnectionWizard.persistDataSource}):
 *     활성 프로젝트의 레지스트리로 아직 등록하지 않은 {@link DataSourceDescriptor}를 만들고(기본 연결 종류, 기본 클라이언트,
 *     비밀번호 저장 켬), Connect에서 폼 값을 쓴 뒤 {@code DBPDataSourceRegistry.addDataSource}로 등록하고 SSMS처럼 바로 연결한다
 *     ({@link ConnectionCommands#connect}, 연결 Job. 실패하면 연결 서비스가 오류를 보이고 연결 설정은 남는다).
 *     {@code addDataSource}가 설정 파일({@code .chromite/data-sources.json})을 바로 저장하고 {@code OBJECT_ADD} 이벤트를 보내므로
 *     Object Explorer가 이벤트로 새 연결을 보여 준다. 드라이버를 바꾸면 이전 드라이버로 만든 연결은 버린다.</li>
 *     <li>편집(SWT {@code EditConnectionWizard}): 원본의 사본(ID 같음)에서 폼을 채우고(Database type은 바꿀 수 없음), Save에서 바뀐 것이
 *     있을 때만 원본에 쓰고 {@code persistConfiguration}으로 저장한다. 연결 중이면 다시 연결할지 묻는다.</li>
 *     <li>Test Connection(SWT {@code ConnectionWizard.testConnection}): 폼 값을 쓴 임시 연결(새 ID, 임시, 메타데이터용
 *     별도 연결 없음)로 모델의 {@link ConnectionTestJob}을 돌리고, 진행 대화상자(취소 가능)에서 기다린 뒤 서버·드라이버 버전과
 *     쓴 네트워크 핸들러(SSH 터널, 프록시, SSL)를 보여 준다. 임시 연결에도 폼의 핸들러 설정이 들어가므로 터널·SSL을 거쳐 연결해 본다.</li>
 *     <li>이름: 비워 두면 SWT 기본 이름 규칙({@code ${host_or_database}}, {@code ConnectionNameResolver}의 legacy 규칙: DB 이름 →
 *     호스트 → 서버 → 드라이버 이름)으로 만들고, 다른 연결과 겹치면 {@code DataSourceUtils.generateUniqueDataSourceName}으로 번호를 붙인다.
 *     사용자가 바꾼 이름 규칙 설정({@code navigator.settings.default.connectionPattern})은 아직 읽지 않는다.</li>
 *     <li>기록: Connect·Save가 끝나면 서버 이름과 드라이버를 {@link ConnectionHistory}에 넣는다(Server name 목록, Database type 콤보의 최근 항목).</li>
 * </ul>
 * 연결 폴더, 탐색기 설정, 필터는 SWT의 다른 페이지 몫이라 여기서 바꾸지 않는다(편집할 때는 원래 값이 그대로 남는다).
 * 연결 직후 SQL과 네트워크 핸들러(SSH, Proxy, SSL)는 설정 화면의 Options 탭이 고친다.
 *
 * 변경 이력:
 * - 2026-11-06 D단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 * - 2026-11-06 SSMS식 Connect 대화상자: 2단계 마법사(드라이버 고르기 → 설정, Back/Next) → 한 화면(Database type 콤보, Options >>),
 *   새 연결의 Save → Connect(저장하고 연결), 서버·드라이버 기록, 테마 키로 읽는 색 (docs/chromite/swing-frontend.md "Connect 대화상자 (SSMS식)")
 * - 2026-11-06 Registered Servers: {@link #openNew}가 등록한 연결을 돌려준다(Object Explorer에 넣거나 Registered Servers에서 고르기 위해)
 *   (docs/chromite/swing-registered-servers.md)
 * - 2026-11-06 고급 연결 설정: Test Connection 결과에 쓴 네트워크 핸들러 표시 (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 */
final class ConnectionDialog extends JDialog {

    private final DBPDataSourceRegistry registry;
    @Nullable
    private final DataSourceDescriptor original;
    @Nullable
    private final DBPDataSourceFolder folder;

    private final JPanel settingsHolder = new JPanel(new BorderLayout());
    private final DatabaseTypeCombo databaseTypeCombo;
    @Nullable
    private ConnectionSettingsPanel settingsPanel;
    /**
     * New connection: not yet registered; edit: a copy of the original with the same ID.
     */
    @Nullable
    private DataSourceDescriptor working;
    private boolean registered;

    private final JLabel problemLabel = new JLabel(" ");
    private final JButton testButton = new JButton(ConnectionMessages.dialog_button_test);
    private final JButton connectButton;
    private final JButton cancelButton = new JButton(ConnectionMessages.common_cancel);
    private final JButton optionsButton = new JButton(ConnectionMessages.dialog_button_options_show);

    private ConnectionDialog(
        @Nullable Window owner,
        @NotNull DBPDataSourceRegistry registry,
        @Nullable DataSourceDescriptor original,
        @Nullable DBPDataSourceFolder folder,
        @NotNull DBPDriver driver
    ) {
        super(owner, original == null ? ConnectionMessages.dialog_title_connect : NLS.bind(ConnectionMessages.dialog_title_edit, original.getName()),
            ModalityType.APPLICATION_MODAL);
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

        databaseTypeCombo = new DatabaseTypeCombo(driver, this::driverSelected);
        // The driver of an existing connection is not changed here (SWT: a separate "driver type" action)
        databaseTypeCombo.setEnabled(original == null);

        connectButton = new JButton(original == null ? ConnectionMessages.dialog_button_connect : ConnectionMessages.dialog_button_save);
        testButton.addActionListener(e -> testConnection());
        connectButton.addActionListener(e -> save());
        cancelButton.addActionListener(e -> close());
        optionsButton.addActionListener(e -> toggleOptions());
        optionsButton.setToolTipText(ConnectionMessages.dialog_button_options_tip);

        problemLabel.setForeground(getThemeColor("Chromite.status.error", "Component.error.focusedBorderColor", Color.RED)); //$NON-NLS-1$ //$NON-NLS-2$
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(connectButton);
        buttons.add(cancelButton);
        buttons.add(optionsButton);
        JPanel south = new JPanel(new BorderLayout(12, 0));
        south.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0));
        JPanel left = new JPanel(new BorderLayout(12, 0));
        left.add(testButton, BorderLayout.WEST);
        left.add(problemLabel, BorderLayout.CENTER);
        south.add(left, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout());
        // Dialog insets of docs/chromite/design-spec.md "11. 대화상자" (10, 12)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(settingsHolder, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(connectButton);
        getRootPane().registerKeyboardAction(e -> close(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        if (original != null) {
            working = registry.createDataSource(original);
            working.setId(original.getId());
            if (!working.isSavePassword()) {
                working.resetPassword();
            }
        } else {
            working = createNewDataSource(driver);
        }
        createSettingsPanel(original == null);
        fitToContent();
        setLocationRelativeTo(owner);
        // Only when the dialog opens: after a driver change the focus stays in the Database type combo
        SwingUtilities.invokeLater(() -> {
            if (settingsPanel != null) {
                settingsPanel.focusFirstField();
            }
        });
    }

    /**
     * New connection in a project (File > New Connection, Object Explorer). Blocks until the dialog is closed.
     *
     * @param folder connection folder of the new connection (the server group selected in Registered Servers)
     * @return the registered connection, or null if the dialog was canceled
     */
    @Nullable
    static DataSourceDescriptor openNew(@Nullable Window owner, @NotNull DBPProject project, @Nullable DBPDataSourceFolder folder) {
        DBPDriver driver = DatabaseTypeCombo.getDefaultDriver();
        if (driver == null) {
            DBWorkbench.getPlatformUI().showError(ConnectionMessages.dialog_new_connection, ConnectionMessages.dialog_no_drivers);
            return null;
        }
        ConnectionDialog dialog = new ConnectionDialog(owner, project.getDataSourceRegistry(), null, folder, driver);
        dialog.setVisible(true);
        return dialog.registered ? dialog.working : null;
    }

    /**
     * Edits a connection ({@code DBPPlatformUI.openConnectionEditor}, Object Explorer). Blocks until the dialog is closed.
     */
    static void openEdit(@Nullable Window owner, @NotNull DataSourceDescriptor dataSource) {
        new ConnectionDialog(owner, dataSource.getRegistry(), dataSource, null, dataSource.getDriver()).setVisible(true);
    }

    /**
     * A theme color: the Chromite token key (docs/chromite/design-spec.md "색 토큰"), else the FlatLaf key, else the fallback.
     */
    @NotNull
    static Color getThemeColor(@NotNull String chromiteKey, @NotNull String flatLafKey, @NotNull Color fallback) {
        Color color = UIManager.getColor(chromiteKey);
        if (color == null) {
            color = UIManager.getColor(flatLafKey);
        }
        return color != null ? color : fallback;
    }

    //////////////////////////////////////////////////////////
    // Layout

    private void driverSelected(@NotNull DBPDriver driver) {
        if (original != null || working == null || working.getDriver() == driver) {
            return;
        }
        // Another driver: the connection made for the previous one is not needed any more
        working.dispose();
        working = createNewDataSource(driver);
        createSettingsPanel(true);
        fitToContent();
    }

    private void createSettingsPanel(boolean isNew) {
        boolean optionsVisible = settingsPanel != null && settingsPanel.isOptionsVisible();
        settingsHolder.removeAll();
        settingsPanel = new ConnectionSettingsPanel(working, isNew, databaseTypeCombo, this::updateButtons);
        settingsPanel.setOptionsVisible(optionsVisible);
        settingsHolder.add(settingsPanel, BorderLayout.CENTER);
        settingsHolder.revalidate();
        settingsHolder.repaint();
        updateButtons();
    }

    private void toggleOptions() {
        if (settingsPanel == null) {
            return;
        }
        settingsPanel.setOptionsVisible(!settingsPanel.isOptionsVisible());
        fitToContent();
    }

    /**
     * Sizes the dialog to the form: as tall as the content (with the options tabs, a taller size the user made is kept),
     * never narrower than now.
     */
    void fitToContent() {
        boolean optionsVisible = settingsPanel != null && settingsPanel.isOptionsVisible();
        optionsButton.setText(optionsVisible ? ConnectionMessages.dialog_button_options_hide : ConnectionMessages.dialog_button_options_show);
        Dimension current = isShowing() ? getSize() : null;
        setMinimumSize(null);
        // pack: the preferred size includes the window frame only once the dialog is displayable
        pack();
        Dimension preferred = getSize();
        int width = Math.max(Math.max(preferred.width, 640), current == null ? 0 : current.width);
        int height = optionsVisible && current != null ? Math.max(preferred.height, current.height) : preferred.height;
        setMinimumSize(new Dimension(520, optionsVisible ? Math.min(preferred.height, 480) : preferred.height));
        setSize(width, height);
        validate();
    }

    private void updateButtons() {
        String problem = settingsPanel == null ? "" : settingsPanel.validateSettings();
        testButton.setEnabled(problem == null);
        connectButton.setEnabled(problem == null);
        problemLabel.setText(CommonUtils.isEmpty(problem) ? " " : problem);
        problemLabel.setToolTipText(CommonUtils.isEmpty(problem) ? null : problem);
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
            ProgressDialog.runTask(ConnectionMessages.dialog_button_test, monitor -> {
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
                DBWorkbench.getPlatformUI().showError(ConnectionMessages.dialog_test_title, ConnectionMessages.dialog_test_connect_error, error);
            } else if (testJob.getConnectStatus() != null && testJob.getConnectStatus().isOK()) {
                String network = settingsPanel.getNetworkSummary();
                Object[] arguments = {testJob.getConnectTime(), testJob.getServerVersion(), testJob.getClientVersion(), network};
                JOptionPane.showMessageDialog(this,
                    NLS.bind(network == null ? ConnectionMessages.dialog_test_result : ConnectionMessages.dialog_test_result_network, arguments),
                    ConnectionMessages.dialog_test_title,
                    JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ConnectionMessages.dialog_test_title, ConnectionMessages.dialog_test_error, e);
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
                DBWorkbench.getPlatformUI().showError(ConnectionMessages.dialog_create_failed_title, ConnectionMessages.dialog_create_failed, e);
                return;
            }
            registered = true;
            rememberHistory();
            dispose();
            // SSMS: Connect saves the connection and connects to it
            ConnectionCommands.connect(working);
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
        rememberHistory();
        close();
        if (changed && original.isConnected() && DBWorkbench.getPlatformUI().confirmAction(ConnectionMessages.dialog_changed_title,
            NLS.bind(ConnectionMessages.dialog_changed_reconnect, original.getName()))) {
            ConnectionCommands.reconnect(original);
        }
    }

    private void rememberHistory() {
        if (settingsPanel == null) {
            return;
        }
        DBPDriver driver = settingsPanel.getDriver();
        ConnectionHistory.addRecentDriver(driver);
        String server = settingsPanel.getServerHistoryEntry();
        if (server != null) {
            ConnectionHistory.addServer(driver, server);
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
            // A saved connection name, not UI text: the same in every language
            name = "New Connection"; //$NON-NLS-1$
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
