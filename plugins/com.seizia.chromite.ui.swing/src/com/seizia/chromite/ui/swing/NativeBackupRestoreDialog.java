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

import com.seizia.chromite.ui.swing.internal.BackupRestoreMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.mysql.model.MySQLCatalog;
import org.jkiss.dbeaver.ext.mysql.tasks.MySQLDatabaseExportHandler;
import org.jkiss.dbeaver.ext.mysql.tasks.MySQLDatabaseExportInfo;
import org.jkiss.dbeaver.ext.mysql.tasks.MySQLExportSettings;
import org.jkiss.dbeaver.ext.mysql.tasks.MySQLScriptExecuteHandler;
import org.jkiss.dbeaver.ext.mysql.tasks.MySQLScriptExecuteSettings;
import org.jkiss.dbeaver.ext.mysql.tasks.MySQLTasks;
import org.jkiss.dbeaver.ext.postgresql.model.PostgreDatabase;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreBackupRestoreSettings;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreDatabaseBackupHandler;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreDatabaseBackupInfo;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreDatabaseBackupSettings;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreDatabaseRestoreHandler;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreDatabaseRestoreInfo;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreDatabaseRestoreSettings;
import org.jkiss.dbeaver.ext.postgresql.tasks.PostgreSQLTasks;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.connection.DBPNativeClientLocation;
import org.jkiss.dbeaver.model.connection.DBPNativeClientLocationManager;
import org.jkiss.dbeaver.model.connection.LocalNativeClientLocation;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.MonitorRunnableContext;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.task.DBTTask;
import org.jkiss.dbeaver.model.task.DBTTaskExecutionListener;
import org.jkiss.dbeaver.model.task.DBTTaskHandler;
import org.jkiss.dbeaver.model.task.DBTTaskType;
import org.jkiss.dbeaver.registry.driver.DriverDescriptor;
import org.jkiss.dbeaver.registry.driver.RemoteNativeClientLocation;
import org.jkiss.dbeaver.registry.task.TaskPreferenceStore;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.tasks.nativetool.AbstractNativeToolSettings;
import org.jkiss.dbeaver.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/**
 * PostgreSQL·MySQL/MariaDB 백업·복원 대화상자(Object Explorer의 데이터베이스 &gt; Tasks &gt; Back Up… / Restore…).
 *
 * 설계: DBeaver의 네이티브 도구 작업을 그대로 쓴다. SWT 마법사(ext.postgresql.ui {@code PostgreBackupWizard}·
 * {@code PostgreRestoreWizard}, ext.mysql.ui의 백업·복원 마법사)는 모델의 설정({@code PostgreDatabaseBackupSettings},
 * {@code MySQLExportSettings} 등)을 채워 작업에 저장한 뒤 모델의 처리기({@code PostgreDatabaseBackupHandler},
 * {@code MySQLDatabaseExportHandler} 등)로 실행한다. 처리기와 설정은 모델 번들(ext.postgresql, ext.mysql,
 * tasks.native)에 있고 SWT에 기대지 않으므로, 이 대화상자는 같은 설정을 채워 임시 작업({@code createTemporaryTask},
 * 데이터 전송 마법사와 같음)에 저장하고 같은 처리기를 부른다. 도구(pg_dump, pg_restore, psql, mysqldump, mysql)는 이 컴퓨터의
 * 로컬 클라이언트로 실행하므로 파일은 이 컴퓨터의 파일이다.
 * <ul>
 *     <li>로컬 클라이언트: SWT의 {@code ClientHomesSelector}처럼 드라이버의 클라이언트 위치와 클라이언트 관리자가 찾은 설치를
 *     목록으로 주고, 찾아보기로 폴더를 더하면 드라이버의 사용자 위치로 저장한다({@code ClientHomesPanel}과 같음). 고른 위치는
 *     연결 설정의 클라이언트 홈({@code clientHomeId})으로 저장한다(SWT {@code NativeClientConfigDialog}와 같음).
 *     처리기는 연결의 클라이언트 홈을 읽는다.</li>
 *     <li>내려받는 클라이언트({@code RemoteNativeClientLocation}, 처음 쓸 때 원격 저장소에서 받음)는 목록에 넣지 않는다.
 *     연결에 홈이 없으면 처리기가 목록 첫 항목(내려받는 클라이언트일 수 있음)을 고르므로, 실행 전에 늘 로컬 위치를 정해 둔다.
 *     로컬 클라이언트가 없으면 안내만 하고 실행하지 않는다.</li>
 *     <li>명령줄 미리 보기: 처리기의 {@code getCommandLine}(보호 메서드)을 미리 보기용 하위 클래스로 불러 실제로 실행할 명령을
 *     보여 준다. 암호는 명령줄에 없다(PGPASSWORD·MYSQL_PWD 환경 변수로 넘김).</li>
 *     <li>도구 출력(명령줄, 진행 로그, 오류)은 처리기의 로그 스트림으로 받아 메시지 페이지에 줄 단위로 보인다.</li>
 *     <li>PostgreSQL 복원은 작업 형식의 처리기를 상속한 {@code CheckedPostgreRestore}로 실행한다. pg_restore는 치명적인 오류
 *     (로그인 실패 등)에도 종료 코드 1을 돌려주는데 모델 처리기는 이를 경고 알림만 띄우고 성공으로 끝내므로, 종료 코드 1을
 *     기억해 두었다가 실패로 보인다. 모델 처리기(DBeaver 코드)는 SWT에서도 쓰므로 고치지 않았다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 백업·복원 (docs/chromite/swing-backup-restore.md)
 * - 2026-11-06 pg_restore가 오류로 끝난 복원(종료 코드 1)을 성공으로 보이던 것을 실패로 보이게 고침
 * - 2026-11-07 메시지 페이지에서 로그의 스택 추적 머리줄("…Exception: …", "Caused by:")도 빼고 클래스 이름을 줄임 (docs/chromite/swing-frontend.md "오류 문구")
 */
final class NativeBackupRestoreDialog extends DatabaseTaskDialog {

    private static final Log log = Log.getLog(NativeBackupRestoreDialog.class);

    private enum Kind {
        POSTGRES,
        MYSQL
    }

    private final Kind kind;
    private final boolean restore;
    private final DBSObject database;
    private final DBPDataSourceContainer container;

    private final JTextField folderField = new JTextField();
    private final JTextField fileNameField = new JTextField();
    private final JTextField inputFileField = new JTextField();
    private final JComboBox<PostgreBackupRestoreSettings.ExportFormat> formatCombo =
        new JComboBox<>(PostgreBackupRestoreSettings.ExportFormat.values());
    private final JTextField compressionField = new JTextField();
    private final JTextField encodingField = new JTextField();
    private final JComboBox<String> methodCombo = new JComboBox<>(new String[] {
        BackupRestoreMessages.native_mysql_method_online,
        BackupRestoreMessages.native_mysql_method_lock,
        BackupRestoreMessages.native_mysql_method_normal
    });
    private final Map<String, JCheckBox> optionChecks = new LinkedHashMap<>();
    private final DefaultComboBoxModel<DBPNativeClientLocation> clientModel = new DefaultComboBoxModel<>();
    private final JComboBox<DBPNativeClientLocation> clientCombo = new JComboBox<>(clientModel);
    private final JTextArea clientStatus = FormPanel.note(" "); //$NON-NLS-1$
    private final JTextArea commandArea = new JTextArea(5, 40);
    private final javax.swing.Timer previewTimer = new javax.swing.Timer(300, e -> updatePreview());
    private boolean fileNameEdited;
    private boolean settingFileName;

    private NativeBackupRestoreDialog(@NotNull Component owner, @NotNull DBSObject database, @NotNull Kind kind, boolean restore) {
        super(owner, NLS.bind(restore ? BackupRestoreMessages.restore_title : BackupRestoreMessages.backup_title, database.getName()),
            database.getDataSource().getContainer());
        this.kind = kind;
        this.restore = restore;
        this.database = database;
        this.container = database.getDataSource().getContainer();
        previewTimer.setRepeats(false);

        // General
        FormPanel general = new FormPanel();
        general.addSection(restore ? BackupRestoreMessages.restore_section_destination : BackupRestoreMessages.backup_section_source);
        JTextField databaseField = new JTextField(database.getName());
        databaseField.setEditable(false);
        general.addRow(BackupRestoreMessages.backup_database, databaseField);
        if (restore) {
            general.addSection(BackupRestoreMessages.restore_section_source);
            general.addRow(BackupRestoreMessages.native_input_file, withBrowse(inputFileField, true));
        } else {
            general.addSection(BackupRestoreMessages.backup_section_destination);
            folderField.setText(RuntimeUtils.getUserHomeDir().getAbsolutePath());
            general.addRow(BackupRestoreMessages.native_output_folder, withBrowse(folderField, false));
            general.addRow(BackupRestoreMessages.native_file_name, fileNameField);
            general.addFull(FormPanel.note(BackupRestoreMessages.native_file_name_note));
        }
        if (kind == Kind.POSTGRES) {
            formatCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    return super.getListCellRendererComponent(list, value instanceof PostgreBackupRestoreSettings.ExportFormat format
                        ? formatTitle(format) : value, index, isSelected, cellHasFocus);
                }
            });
            formatCombo.setSelectedItem(PostgreBackupRestoreSettings.ExportFormat.CUSTOM);
            formatCombo.addActionListener(e -> {
                updateDefaultFileName();
                schedulePreview();
            });
            general.addRow(BackupRestoreMessages.native_pg_format, formatCombo);
            if (!restore) {
                general.addRow(BackupRestoreMessages.native_pg_compression, compressionField);
                general.addRow(BackupRestoreMessages.native_pg_encoding, encodingField);
            }
        } else if (!restore) {
            methodCombo.setSelectedIndex(0);
            methodCombo.addActionListener(e -> schedulePreview());
            general.addRow(BackupRestoreMessages.native_mysql_method, methodCombo);
        }
        general.addFull(FormPanel.note(BackupRestoreMessages.native_local_file_note));
        general.finish();

        // Options
        FormPanel options = new FormPanel();
        options.addSection(restore ? BackupRestoreMessages.restore_section_options : BackupRestoreMessages.native_section_options);
        if (kind == Kind.POSTGRES && !restore) {
            addOption(options, "noOwner", BackupRestoreMessages.native_pg_no_owner, false); //$NON-NLS-1$
            addOption(options, "noPrivileges", BackupRestoreMessages.native_pg_no_privileges, false); //$NON-NLS-1$
            addOption(options, "inserts", BackupRestoreMessages.native_pg_inserts, false); //$NON-NLS-1$
            addOption(options, "clean", BackupRestoreMessages.native_pg_clean, false); //$NON-NLS-1$
            addOption(options, "create", BackupRestoreMessages.native_pg_create, false); //$NON-NLS-1$
        } else if (kind == Kind.POSTGRES) {
            addOption(options, "clean", BackupRestoreMessages.native_pg_restore_clean, false); //$NON-NLS-1$
            addOption(options, "noOwner", BackupRestoreMessages.native_pg_no_owner, false); //$NON-NLS-1$
            addOption(options, "create", BackupRestoreMessages.native_pg_restore_create, false); //$NON-NLS-1$
            options.addFull(FormPanel.note(BackupRestoreMessages.native_pg_plain_note));
        } else if (!restore) {
            addOption(options, "noCreate", BackupRestoreMessages.native_mysql_no_create, false); //$NON-NLS-1$
            addOption(options, "addDrop", BackupRestoreMessages.native_mysql_add_drop, true); //$NON-NLS-1$
            addOption(options, "disableKeys", BackupRestoreMessages.native_mysql_disable_keys, true); //$NON-NLS-1$
            addOption(options, "extendedInserts", BackupRestoreMessages.native_mysql_extended_inserts, true); //$NON-NLS-1$
            addOption(options, "noData", BackupRestoreMessages.native_mysql_no_data, false); //$NON-NLS-1$
            addOption(options, "routines", BackupRestoreMessages.native_mysql_routines, true); //$NON-NLS-1$
            addOption(options, "events", BackupRestoreMessages.native_mysql_events, false); //$NON-NLS-1$
            addOption(options, "comments", BackupRestoreMessages.native_mysql_comments, false); //$NON-NLS-1$
            addOption(options, "removeDefiner", BackupRestoreMessages.native_mysql_remove_definer, false); //$NON-NLS-1$
            addOption(options, "hexBlob", BackupRestoreMessages.native_mysql_hex_blob, false); //$NON-NLS-1$
        } else {
            addOption(options, "disableFk", BackupRestoreMessages.native_mysql_disable_fk, false); //$NON-NLS-1$
            addOption(options, "verbose", BackupRestoreMessages.native_mysql_verbose, false); //$NON-NLS-1$
        }
        options.finish();

        // Local client and command line
        FormPanel client = new FormPanel();
        client.addSection(BackupRestoreMessages.native_section_client);
        clientCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, value instanceof DBPNativeClientLocation location
                    ? describeClient(location) : BackupRestoreMessages.native_client_none, index, isSelected, cellHasFocus);
            }
        });
        clientCombo.addActionListener(e -> schedulePreview());
        JButton browseClient = new JButton(BackupRestoreMessages.native_browse);
        browseClient.setToolTipText(BackupRestoreMessages.native_client_browse_tip);
        browseClient.addActionListener(e -> browseClient());
        JPanel clientPanel = new JPanel(new BorderLayout(6, 0));
        clientPanel.add(clientCombo, BorderLayout.CENTER);
        clientPanel.add(browseClient, BorderLayout.EAST);
        client.addRow(BackupRestoreMessages.native_client, clientPanel);
        client.addFull(clientStatus);
        client.addFull(FormPanel.note(NLS.bind(BackupRestoreMessages.native_client_note, getToolNames())));
        client.addSection(BackupRestoreMessages.native_section_command);
        commandArea.setEditable(false);
        commandArea.setLineWrap(true);
        commandArea.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        client.addFiller(new JScrollPane(commandArea));

        DocumentListener previewListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                schedulePreview();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                schedulePreview();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                schedulePreview();
            }
        };
        folderField.getDocument().addDocumentListener(previewListener);
        fileNameField.getDocument().addDocumentListener(previewListener);
        fileNameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fileNameChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fileNameChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                fileNameChanged();
            }
        });
        inputFileField.getDocument().addDocumentListener(previewListener);
        compressionField.getDocument().addDocumentListener(previewListener);
        encodingField.getDocument().addDocumentListener(previewListener);
        updateDefaultFileName();
        loadClients(container.getConnectionConfiguration().getClientHomeId());

        initDialog(List.of(
            new Page(BackupRestoreMessages.task_page_general, general),
            new Page(restore ? BackupRestoreMessages.restore_page_options : BackupRestoreMessages.backup_page_options, options),
            new Page(BackupRestoreMessages.native_page_client, client)));
        updatePreview();
    }

    /**
     * Back Up / Restore of a PostgreSQL database or a MySQL/MariaDB catalog.
     */
    static void open(@NotNull Component owner, @NotNull DBSObject database, boolean restore) {
        Kind kind = database instanceof PostgreDatabase ? Kind.POSTGRES : Kind.MYSQL;
        new NativeBackupRestoreDialog(owner, database, kind, restore).open();
    }

    @NotNull
    private static String formatTitle(@NotNull PostgreBackupRestoreSettings.ExportFormat format) {
        return switch (format) {
            case PLAIN -> BackupRestoreMessages.native_pg_format_plain;
            case CUSTOM -> BackupRestoreMessages.native_pg_format_custom;
            case DIRECTORY -> BackupRestoreMessages.native_pg_format_directory;
            case TAR -> BackupRestoreMessages.native_pg_format_tar;
        };
    }

    private void addOption(@NotNull FormPanel panel, @NotNull String id, @NotNull String label, boolean selected) {
        JCheckBox check = new JCheckBox(label, selected);
        check.addActionListener(e -> schedulePreview());
        optionChecks.put(id, check);
        panel.addFull(check);
    }

    private boolean option(@NotNull String id) {
        JCheckBox check = optionChecks.get(id);
        return check != null && check.isSelected();
    }

    @NotNull
    private JPanel withBrowse(@NotNull JTextField field, boolean file) {
        JButton browse = new JButton(BackupRestoreMessages.native_browse);
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(field.getText().isEmpty() ? null : new File(field.getText()));
            chooser.setFileSelectionMode(file ? JFileChooser.FILES_AND_DIRECTORIES : JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.add(field, BorderLayout.CENTER);
        panel.add(browse, BorderLayout.EAST);
        return panel;
    }

    @NotNull
    private String getToolNames() {
        if (kind == Kind.POSTGRES) {
            return restore ? "pg_restore, psql" : "pg_dump"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return restore ? "mysql / mariadb" : "mysqldump / mariadb-dump"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void fileNameChanged() {
        if (!settingFileName) {
            fileNameEdited = true;
        }
    }

    /**
     * Default file name: as the SWT wizards, "dump-${database}-${timestamp}" with the format's extension.
     */
    private void updateDefaultFileName() {
        if (restore || fileNameEdited) {
            return;
        }
        String extension = kind == Kind.MYSQL ? "sql" //$NON-NLS-1$
            : ((PostgreBackupRestoreSettings.ExportFormat) Objects.requireNonNull(formatCombo.getSelectedItem())).getExt();
        settingFileName = true;
        try {
            fileNameField.setText("dump-${database}-${timestamp}" + (extension.isEmpty() ? "" : "." + extension)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } finally {
            settingFileName = false;
        }
    }

    //////////////////////////////////////////////////////////
    // Local client

    /**
     * Local client locations: the driver's own (custom) locations and the installations the client manager finds.
     * Locations downloaded from a remote repository are left out (see the class comment).
     */
    private void loadClients(@Nullable String selectedId) {
        DBPDriver driver = container.getDriver();
        Map<String, DBPNativeClientLocation> locations = new LinkedHashMap<>();
        for (DBPNativeClientLocation location : driver.getNativeClientLocations()) {
            if (!(location instanceof RemoteNativeClientLocation)) {
                locations.put(location.getName(), location);
            }
        }
        DBPNativeClientLocationManager manager = driver.getNativeClientManager();
        if (manager != null) {
            for (DBPNativeClientLocation location : manager.findLocalClientLocations()) {
                locations.putIfAbsent(location.getName(), location);
            }
        }
        clientModel.removeAllElements();
        for (DBPNativeClientLocation location : locations.values()) {
            clientModel.addElement(location);
        }
        DBPNativeClientLocation selected = selectedId == null ? null : locations.get(selectedId);
        if (selected == null && clientModel.getSize() > 0) {
            selected = clientModel.getElementAt(0);
        }
        clientModel.setSelectedItem(selected);
        schedulePreview();
    }

    /**
     * Name and folder of a client location; only the folder for a folder added with Browse (its name is the folder).
     */
    @NotNull
    private static String describeClient(@NotNull DBPNativeClientLocation location) {
        String path = location.getPath().getAbsolutePath();
        String name = location.getDisplayName();
        return CommonUtils.isEmpty(name) || name.equals(path) ? path : name + " (" + path + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void browseClient() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(NLS.bind(BackupRestoreMessages.native_client_choose, getToolNames()));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String path = chooser.getSelectedFile().getAbsolutePath();
        DBPDriver driver = container.getDriver();
        if (driver instanceof DriverDescriptor descriptor) {
            // Keep the driver's custom locations and add this one, as the SWT client homes panel saves them
            List<DBPNativeClientLocation> custom = new ArrayList<>();
            for (DBPNativeClientLocation location : driver.getNativeClientLocations()) {
                if (!(location instanceof RemoteNativeClientLocation) && !location.getName().equals(path)) {
                    custom.add(location);
                }
            }
            custom.add(new LocalNativeClientLocation(path, path));
            descriptor.setNativeClientLocations(custom);
            // Only modified drivers are written to drivers.xml; without this the folder is gone after a restart
            // (the SWT client homes panel relies on the driver being modified already)
            descriptor.setModified(true);
            try {
                descriptor.getProviderDescriptor().getRegistry().saveDrivers();
            } catch (DBException e) {
                log.debug("Can't save the client location", e);
            }
        }
        loadClients(path);
    }

    @Nullable
    private DBPNativeClientLocation getSelectedClient() {
        return (DBPNativeClientLocation) clientCombo.getSelectedItem();
    }

    //////////////////////////////////////////////////////////
    // Settings and command line

    @NotNull
    private AbstractNativeToolSettings<?> createSettings() {
        DBPProject project = container.getProject();
        DBPNativeClientLocation client = getSelectedClient();
        AbstractNativeToolSettings<?> settings;
        if (kind == Kind.POSTGRES && !restore) {
            PostgreDatabaseBackupSettings pg = new PostgreDatabaseBackupSettings(project);
            pg.loadSettingsFromInput(List.of(database), Map.of());
            pg.setFormat((PostgreBackupRestoreSettings.ExportFormat) formatCombo.getSelectedItem());
            pg.setOutputFolderPattern(folderField.getText().trim());
            pg.setOutputFilePattern(fileNameField.getText().trim());
            pg.setCompression(compressionField.getText().trim());
            pg.setEncoding(encodingField.getText().trim());
            pg.setNoOwner(option("noOwner")); //$NON-NLS-1$
            pg.setNoPrivileges(option("noPrivileges")); //$NON-NLS-1$
            pg.setUseInserts(option("inserts")); //$NON-NLS-1$
            pg.setDropObjects(option("clean")); //$NON-NLS-1$
            pg.setCreateDatabase(option("create")); //$NON-NLS-1$
            settings = pg;
        } else if (kind == Kind.POSTGRES) {
            PostgreDatabaseRestoreSettings pg = new PostgreDatabaseRestoreSettings(project);
            pg.loadSettingsFromInput(List.of(database), Map.of());
            pg.setRestoreInfo(new PostgreDatabaseRestoreInfo((PostgreDatabase) database));
            pg.setFormat((PostgreBackupRestoreSettings.ExportFormat) formatCombo.getSelectedItem());
            pg.setInputFile(inputFileField.getText().trim());
            pg.setCleanFirst(option("clean")); //$NON-NLS-1$
            pg.setNoOwner(option("noOwner")); //$NON-NLS-1$
            pg.setCreateDatabase(option("create")); //$NON-NLS-1$
            settings = pg;
        } else if (!restore) {
            MySQLExportSettings my = new MySQLExportSettings(project);
            my.loadSettingsFromInput(List.of(database), Map.of());
            my.setMethod(switch (methodCombo.getSelectedIndex()) {
                case 0 -> MySQLExportSettings.DumpMethod.ONLINE;
                case 1 -> MySQLExportSettings.DumpMethod.LOCK_ALL_TABLES;
                default -> MySQLExportSettings.DumpMethod.NORMAL;
            });
            my.setOutputFolderPattern(folderField.getText().trim());
            my.setOutputFilePattern(fileNameField.getText().trim());
            my.setNoCreateStatements(option("noCreate")); //$NON-NLS-1$
            my.setAddDropStatements(option("addDrop")); //$NON-NLS-1$
            my.setDisableKeys(option("disableKeys")); //$NON-NLS-1$
            my.setExtendedInserts(option("extendedInserts")); //$NON-NLS-1$
            my.setNoData(option("noData")); //$NON-NLS-1$
            my.setNoRoutines(!option("routines")); //$NON-NLS-1$
            my.setDumpEvents(option("events")); //$NON-NLS-1$
            my.setComments(option("comments")); //$NON-NLS-1$
            my.setRemoveDefiner(option("removeDefiner")); //$NON-NLS-1$
            my.setBinariesInHex(option("hexBlob")); //$NON-NLS-1$
            settings = my;
        } else {
            MySQLScriptExecuteSettings my = new MySQLScriptExecuteSettings(project);
            my.loadSettingsFromInput(List.of((MySQLCatalog) database), Map.of());
            my.setImport(true);
            my.setInputFile(inputFileField.getText().trim());
            my.setIsForeignKeyCheckDisabled(option("disableFk")); //$NON-NLS-1$
            my.setLogLevel(option("verbose") ? MySQLScriptExecuteSettings.LogLevel.Verbose : MySQLScriptExecuteSettings.LogLevel.Normal); //$NON-NLS-1$
            settings = my;
        }
        if (settings.getDataSourceContainer() == null) {
            settings.setDataSourceContainer(container);
        }
        settings.setClientHome(client);
        return settings;
    }

    @NotNull
    private String getTaskTypeId() {
        if (kind == Kind.POSTGRES) {
            return restore ? PostgreSQLTasks.TASK_DATABASE_RESTORE : PostgreSQLTasks.TASK_DATABASE_BACKUP;
        }
        return restore ? MySQLTasks.TASK_DATABASE_RESTORE : MySQLTasks.TASK_DATABASE_BACKUP;
    }

    /**
     * The command line the handler will run (see {@link PreviewPostgreBackup} and the other preview handlers).
     *
     * @throws IOException the tool is not in the client location
     */
    @NotNull
    private String buildCommandLine(@NotNull AbstractNativeToolSettings<?> settings) throws IOException {
        List<String> command;
        String redirect = null;
        if (settings instanceof PostgreDatabaseBackupSettings pg) {
            // A local output file is passed with --file
            command = new PreviewPostgreBackup().preview(pg, pg.getExportObjects().get(0));
        } else if (settings instanceof PostgreDatabaseRestoreSettings pg) {
            command = new PreviewPostgreRestore().preview(pg, pg.getRestoreInfo());
        } else if (settings instanceof MySQLExportSettings my) {
            MySQLDatabaseExportInfo info = my.getExportObjects().get(0);
            command = new PreviewMySQLExport().preview(my, info);
            redirect = "> " + my.getOutputFile(info); //$NON-NLS-1$
        } else {
            MySQLScriptExecuteSettings my = (MySQLScriptExecuteSettings) settings;
            command = new PreviewMySQLRestore().preview(my, my.getDatabaseObjects().get(0));
            redirect = "< " + my.getInputFile(); //$NON-NLS-1$
        }
        StringBuilder text = new StringBuilder();
        for (String arg : command) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(arg.isEmpty() || arg.indexOf(' ') >= 0 ? "\"" + arg + "\"" : arg); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (redirect != null) {
            text.append(' ').append(redirect);
        }
        return text.toString();
    }

    private void schedulePreview() {
        previewTimer.restart();
    }

    private void updatePreview() {
        DBPNativeClientLocation client = getSelectedClient();
        if (client == null) {
            clientStatus.setText(NLS.bind(BackupRestoreMessages.native_client_missing, getToolNames()));
            commandArea.setText(""); //$NON-NLS-1$
            return;
        }
        try {
            commandArea.setText(buildCommandLine(createSettings()));
            commandArea.setCaretPosition(0);
            clientStatus.setText(NLS.bind(BackupRestoreMessages.native_client_found, client.getPath().getAbsolutePath()));
        } catch (Exception e) {
            log.debug("Can't build the command line", e);
            commandArea.setText(""); //$NON-NLS-1$
            clientStatus.setText(NLS.bind(BackupRestoreMessages.native_client_tool_missing, getToolNames(), client.getPath().getAbsolutePath()));
        }
    }

    //////////////////////////////////////////////////////////
    // Run

    @Nullable
    @Override
    protected String validateInput() {
        if (restore) {
            String input = inputFileField.getText().trim();
            if (input.isEmpty()) {
                return BackupRestoreMessages.native_error_no_input;
            }
            if (!new File(input).exists()) {
                return NLS.bind(BackupRestoreMessages.native_error_input_missing, input);
            }
        } else if (folderField.getText().trim().isEmpty() || fileNameField.getText().trim().isEmpty()) {
            return BackupRestoreMessages.native_error_no_output;
        }
        DBPNativeClientLocation client = getSelectedClient();
        if (client == null) {
            return NLS.bind(BackupRestoreMessages.native_client_missing, getToolNames());
        }
        try {
            buildCommandLine(createSettings());
        } catch (Exception e) {
            return NLS.bind(BackupRestoreMessages.native_client_tool_missing, getToolNames(), client.getPath().getAbsolutePath());
        }
        return null;
    }

    @NotNull
    @Override
    protected TaskRun prepareRun() {
        AbstractNativeToolSettings<?> settings = createSettings();
        DBPNativeClientLocation client = Objects.requireNonNull(getSelectedClient());
        // The handlers read the client from the connection, as the SWT native client dialog stores it
        if (!client.getName().equals(container.getConnectionConfiguration().getClientHomeId())) {
            container.getConnectionConfiguration().setClientHomeId(client.getName());
            container.persistConfiguration();
        }
        DBPProject project = container.getProject();
        String title = getTitle();
        String taskTypeId = getTaskTypeId();
        return monitor -> runHandler(monitor, project, taskTypeId, title, settings);
    }

    private void runHandler(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPProject project,
        @NotNull String taskTypeId,
        @NotNull String title,
        @NotNull AbstractNativeToolSettings<?> settings
    ) throws Exception {
        DBTTaskType taskType = project.getTaskManager().getRegistry().getTaskType(taskTypeId);
        if (taskType == null) {
            throw new DBException("Task type " + taskTypeId + " is not registered"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        DBTTask task = project.getTaskManager().createTemporaryTask(taskType, title);
        MonitorRunnableContext context = new MonitorRunnableContext(monitor);
        // As the SWT wizards: the settings are saved into the task and the handler loads them from it
        Map<String, Object> properties = new LinkedHashMap<>();
        settings.saveSettings(context, new TaskPreferenceStore(properties));
        task.setProperties(properties);
        Throwable[] error = new Throwable[1];
        DBTTaskExecutionListener listener = new DBTTaskExecutionListener() {
            @Override
            public void taskStarted(@Nullable DBTTask task) {
            }

            @Override
            public void taskFinished(@Nullable DBTTask task, @Nullable Object result, @Nullable Throwable e, @Nullable Object s) {
                error[0] = e;
            }

            @Override
            public void subTaskFinished(@Nullable DBTTask task, @Nullable Throwable e, @Nullable Object s) {
            }
        };
        // pg_restore exits with 1 on any error, fatal ones too (a failed login, an unreadable file); the model handler
        // only shows a warning toast for it and returns normally, which made a failed restore look successful here
        DBTTaskHandler handler = PostgreSQLTasks.TASK_DATABASE_RESTORE.equals(taskTypeId)
            ? new CheckedPostgreRestore() : taskType.createHandler();
        try (PrintStream logStream = new PrintStream(new MessagesStream(), true, StandardCharsets.UTF_8)) {
            handler.executeTask(context, task, Locale.getDefault(), log, logStream, listener);
        }
        if (error[0] != null) {
            if (error[0] instanceof Exception e) {
                throw e;
            }
            throw new DBException("Native tool failed", error[0]); //$NON-NLS-1$
        }
        if (handler instanceof CheckedPostgreRestore restoreHandler && restoreHandler.errorsReported) {
            throw new DBException(BackupRestoreMessages.native_error_pg_restore_errors);
        }
    }

    @NotNull
    @Override
    protected String getSuccessText() {
        return NLS.bind(restore ? BackupRestoreMessages.restore_success : BackupRestoreMessages.backup_success, database.getName());
    }

    @NotNull
    @Override
    protected List<Action> getScriptActions() {
        Action copy = new AbstractAction(BackupRestoreMessages.native_copy_command) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                String command = buildScript(() -> buildCommandLine(createSettings()));
                if (command != null) {
                    DBWorkbench.getPlatformUI().copyTextToClipboard(command, false);
                }
            }
        };
        return List.of(copy);
    }

    /**
     * Writes the handler's log (command line, tool output) to the Messages page line by line.
     */
    private final class MessagesStream extends OutputStream {
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                line.write(b);
            }
        }

        @Override
        public synchronized void flush() {
            // Lines are passed on when complete
        }

        @Override
        public synchronized void close() {
            flushLine();
        }

        private void flushLine() {
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            // The handler also routes its log here. Printed stack traces of a logged error (header, "at", "Caused by")
            // are left out: the error itself is shown at the end, its stack trace behind Details. Class names in the
            // remaining lines are shortened as in every other error text.
            if (!CommonUtils.isEmptyTrimmed(text) && !ErrorText.isStackTraceLine(text)) {
                appendMessage(ErrorText.clean(text));
            }
        }
    }

    /**
     * The handler of the PostgreSQL restore task type that remembers an exit code 1 of pg_restore (errors occurred)
     * instead of passing it to the model handler, which would only show a warning toast and report success.
     */
    private static final class CheckedPostgreRestore extends PostgreDatabaseRestoreHandler {
        private volatile boolean errorsReported;

        @Override
        public void validateErrorCode(int exitCode) throws IOException {
            if (exitCode == 1) {
                errorsReported = true;
            } else {
                super.validateErrorCode(exitCode);
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Preview handlers: the command line building of the model handlers is protected

    private static final class PreviewPostgreBackup extends PostgreDatabaseBackupHandler {
        @NotNull
        List<String> preview(@NotNull PostgreDatabaseBackupSettings settings, @NotNull PostgreDatabaseBackupInfo info) throws IOException {
            return getCommandLine(settings, info);
        }
    }

    private static final class PreviewPostgreRestore extends PostgreDatabaseRestoreHandler {
        @NotNull
        List<String> preview(@NotNull PostgreDatabaseRestoreSettings settings, @NotNull PostgreDatabaseRestoreInfo info) throws IOException {
            return getCommandLine(settings, info);
        }
    }

    private static final class PreviewMySQLExport extends MySQLDatabaseExportHandler {
        @NotNull
        List<String> preview(@NotNull MySQLExportSettings settings, @NotNull MySQLDatabaseExportInfo info) throws IOException {
            return getCommandLine(settings, info);
        }
    }

    private static final class PreviewMySQLRestore extends MySQLScriptExecuteHandler {
        @NotNull
        List<String> preview(@NotNull MySQLScriptExecuteSettings settings, @NotNull MySQLCatalog catalog) throws IOException {
            return getCommandLine(settings, catalog);
        }
    }
}
