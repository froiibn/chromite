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
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mysql.model.MySQLCatalog;
import com.seizia.chromite.ext.mysql.tasks.MySQLExportSettings;
import com.seizia.chromite.ext.mysql.tasks.MySQLScriptExecuteSettings;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.tasks.PostgreBackupRestoreSettings;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseBackupSettings;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseRestoreInfo;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseRestoreSettings;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.admin.nativetool.NativeBackupRestore;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.connection.DBPNativeClientLocation;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.tasks.nativetool.AbstractNativeToolSettings;
import com.seizia.chromite.tasks.nativetool.AbstractScriptExecuteSettings;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/**
 * PostgreSQL·MySQL/MariaDB 백업·복원 대화상자(Object Explorer의 데이터베이스 &gt; Tasks &gt; Back Up… / Restore…)와
 * 네이티브 클라이언트로 스크립트 실행 대화상자(Tasks &gt; Run Script with Native Client…, Query &gt; Run with Native Client…:
 * psql, mysql, sqlplus).
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
 *     <li>PostgreSQL 복원에서 pg_restore가 종료 코드 1(오류 있음, 로그인 실패 같은 치명적인 오류도 같음)로 끝나면 실패로 보인다.
 *     모델 처리기는 이를 경고 알림만 띄우고 성공으로 끝낸다.</li>
 *     <li>화면과 상관없는 부분(클라이언트 목록, 작업 형식, 명령줄, 처리기 실행, pg_restore 종료 코드 1)은 모델의
 *     {@link NativeBackupRestore}에 있다. Chromite Agent 백업·복원 도구가 같은 실행 경로를 쓴다.</li>
 *     <li>스크립트 실행은 복원처럼 입력 파일을 읽는 세 번째 방식이다. 도구가 따로 접속하는 새 세션이라 쿼리 창의 세션·트랜잭션과
 *     상관없다는 것을 General 페이지와 실행 확인에 적는다. 실행 확인(Cancel이 기본 단추)이 명령줄과 표준 입력으로 보내는 것
 *     (SQL*Plus 로그온, 암호는 가림)을 보인다.</li>
 *     <li>저장된 작업: Script 메뉴의 Save as Task…가 설정을 프로젝트의 작업으로 저장하고, Database Tasks 보기의 Edit…가
 *     {@link #openTask}로 다시 연다(그 뒤의 저장은 같은 작업을 고침).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 백업·복원 (docs/chromite/swing-backup-restore.md)
 * - 2026-11-06 pg_restore가 오류로 끝난 복원(종료 코드 1)을 성공으로 보이던 것을 실패로 보이게 고침
 * - 2026-11-07 메시지 페이지에서 로그의 스택 추적 머리줄("…Exception: …", "Caused by:")도 빼고 클래스 이름을 줄임 (docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-07 PostgreSQL 복원 옵션 "첫 오류에서 멈춤"(psql ON_ERROR_STOP, pg_restore --exit-on-error) (docs/chromite/swing-backup-restore.md "실패한 백업과 psql 오류")
 * - 2026-11-08 모델 부분을 {@link NativeBackupRestore}(model.admin)로 옮김: Chromite Agent 백업·복원 도구와 같은 실행 경로 (docs/chromite/ai-agent.md)
 * - 2026-11-08 네이티브 클라이언트로 스크립트 실행(psql, mysql, sqlplus), 작업으로 저장·저장된 작업 편집, 클라이언트 폴더 더하기를 모델로 옮김
 *   (docs/chromite/swing-database-tasks.md)
 */
final class NativeBackupRestoreDialog extends DatabaseTaskDialog {

    private static final Log log = Log.getLog(NativeBackupRestoreDialog.class);

    private enum Kind {
        POSTGRES,
        MYSQL,
        ORACLE
    }

    private final Kind kind;
    private final boolean restore;
    // Run Script with Native Client: like a restore it reads an input file, with the script options
    private final boolean script;
    private final DBSObject database;
    private final DBPDataSourceContainer container;
    // The saved task this dialog edits (Save Task updates it), or null
    @Nullable
    private DBTTask savedTask;

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

    private NativeBackupRestoreDialog(
        @NotNull Component owner,
        @NotNull DBSObject database,
        boolean restore,
        boolean script,
        @Nullable DBTTask savedTask,
        @Nullable AbstractNativeToolSettings<?> savedSettings
    ) {
        super(owner, NLS.bind(script ? BackupRestoreMessages.native_script_title
            : restore ? BackupRestoreMessages.restore_title : BackupRestoreMessages.backup_title, database.getName()),
            database.getDataSource().getContainer());
        this.kind = database instanceof PostgreDatabase ? Kind.POSTGRES : database instanceof MySQLCatalog ? Kind.MYSQL : Kind.ORACLE;
        this.restore = restore;
        this.script = script;
        this.database = database;
        this.container = database.getDataSource().getContainer();
        this.savedTask = savedTask;
        previewTimer.setRepeats(false);

        // General
        FormPanel general = new FormPanel();
        general.addSection(script ? BackupRestoreMessages.native_script_section_target
            : restore ? BackupRestoreMessages.restore_section_destination : BackupRestoreMessages.backup_section_source);
        JTextField databaseField = new JTextField(database.getName());
        databaseField.setEditable(false);
        general.addRow(script ? BackupRestoreMessages.native_script_target : BackupRestoreMessages.backup_database, databaseField);
        if (script) {
            general.addSection(BackupRestoreMessages.native_script_section_script);
            general.addRow(BackupRestoreMessages.native_script_file, withBrowse(inputFileField, true));
            // A native client run is a session of its own: said where the script is chosen
            general.addFull(FormPanel.note(NLS.bind(BackupRestoreMessages.native_script_session_note, getToolNames())));
        } else if (restore) {
            general.addSection(BackupRestoreMessages.restore_section_source);
            general.addRow(BackupRestoreMessages.native_input_file, withBrowse(inputFileField, true));
        } else {
            general.addSection(BackupRestoreMessages.backup_section_destination);
            folderField.setText(RuntimeUtils.getUserHomeDir().getAbsolutePath());
            general.addRow(BackupRestoreMessages.native_output_folder, withBrowse(folderField, false));
            general.addRow(BackupRestoreMessages.native_file_name, fileNameField);
            general.addFull(FormPanel.note(BackupRestoreMessages.native_file_name_note));
        }
        if (kind == Kind.POSTGRES && !script) {
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
        } else if (kind == Kind.MYSQL && !restore && !script) {
            methodCombo.setSelectedIndex(0);
            methodCombo.addActionListener(e -> schedulePreview());
            general.addRow(BackupRestoreMessages.native_mysql_method, methodCombo);
        }
        general.addFull(FormPanel.note(BackupRestoreMessages.native_local_file_note));
        general.finish();

        // Options
        FormPanel options = new FormPanel();
        options.addSection(script ? BackupRestoreMessages.native_script_section_options
            : restore ? BackupRestoreMessages.restore_section_options : BackupRestoreMessages.native_section_options);
        if (script && kind == Kind.POSTGRES) {
            addOption(options, "stopOnError", BackupRestoreMessages.native_script_pg_stop_on_error, false); //$NON-NLS-1$
        } else if (script && kind == Kind.MYSQL) {
            addOption(options, "disableFk", BackupRestoreMessages.native_script_mysql_disable_fk, false); //$NON-NLS-1$
            addOption(options, "verbose", BackupRestoreMessages.native_mysql_verbose, false); //$NON-NLS-1$
        } else if (script) {
            options.addFull(FormPanel.note(BackupRestoreMessages.native_script_oracle_note));
        } else if (kind == Kind.POSTGRES && !restore) {
            addOption(options, "noOwner", BackupRestoreMessages.native_pg_no_owner, false); //$NON-NLS-1$
            addOption(options, "noPrivileges", BackupRestoreMessages.native_pg_no_privileges, false); //$NON-NLS-1$
            addOption(options, "inserts", BackupRestoreMessages.native_pg_inserts, false); //$NON-NLS-1$
            addOption(options, "clean", BackupRestoreMessages.native_pg_clean, false); //$NON-NLS-1$
            addOption(options, "create", BackupRestoreMessages.native_pg_create, false); //$NON-NLS-1$
        } else if (kind == Kind.POSTGRES) {
            addOption(options, "clean", BackupRestoreMessages.native_pg_restore_clean, false); //$NON-NLS-1$
            addOption(options, "noOwner", BackupRestoreMessages.native_pg_no_owner, false); //$NON-NLS-1$
            addOption(options, "create", BackupRestoreMessages.native_pg_restore_create, false); //$NON-NLS-1$
            // Off by default, as in DBeaver: a script made with --clean (without --if-exists) fails on its first DROP
            // of an object that does not exist yet
            addOption(options, "stopOnError", BackupRestoreMessages.native_pg_restore_stop_on_error, false); //$NON-NLS-1$
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
        if (savedSettings != null) {
            applySettings(savedSettings);
        }

        initDialog(List.of(
            new Page(BackupRestoreMessages.task_page_general, general),
            new Page(script ? BackupRestoreMessages.native_script_page_options
                : restore ? BackupRestoreMessages.restore_page_options : BackupRestoreMessages.backup_page_options, options),
            new Page(BackupRestoreMessages.native_page_client, client)));
        updatePreview();
    }

    /**
     * Back Up / Restore of a PostgreSQL database or a MySQL/MariaDB catalog.
     */
    static void open(@NotNull Component owner, @NotNull DBSObject database, boolean restore) {
        new NativeBackupRestoreDialog(owner, database, restore, false, null, null).open();
    }

    /**
     * Run Script with Native Client on a PostgreSQL database, MySQL/MariaDB catalog, Oracle schema or Oracle connection
     * ({@link NativeBackupRestore#isScriptSupported}).
     *
     * @param scriptFile the script file to fill in, or null
     */
    static void openScript(@NotNull Component owner, @NotNull DBSObject target, @Nullable String scriptFile) {
        NativeBackupRestoreDialog dialog = new NativeBackupRestoreDialog(owner, target, false, true, null, null);
        if (scriptFile != null) {
            dialog.inputFileField.setText(scriptFile);
        }
        dialog.open();
    }

    /**
     * Opens the dialog of a saved task with its settings (Edit in Database Tasks). Reading the settings finds the task's
     * database objects, which connects; the user can run the settings and save them back into the task.
     */
    static void openTask(@NotNull Component owner, @NotNull DBTTask task) {
        AbstractNativeToolSettings<?> settings;
        try {
            settings = ProgressDialog.runTask(NLS.bind(BackupRestoreMessages.native_task_loading, task.getName()),
                monitor -> NativeBackupRestore.loadTaskSettings(monitor, task));
        } catch (DBException e) {
            log.debug("Can't read the task settings", e);
            ErrorDialog.show(owner, task.getName(), ErrorText.describe(e), e);
            return;
        }
        DBSObject target = NativeBackupRestore.getTaskTarget(settings);
        if (target == null) {
            JOptionPane.showMessageDialog(owner, NLS.bind(BackupRestoreMessages.native_task_no_target, task.getName()),
                task.getName(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        // A MySQL restore is a script run of the restore task type (MySQLScriptExecuteSettings with the import flag)
        boolean script = settings instanceof AbstractScriptExecuteSettings<?> && !(settings instanceof MySQLScriptExecuteSettings my && my.isImport());
        boolean restore = !script && (settings instanceof PostgreDatabaseRestoreSettings || settings instanceof MySQLScriptExecuteSettings);
        new NativeBackupRestoreDialog(owner, target, restore, script, task, settings).open();
    }

    /**
     * Fills the controls from the settings of a saved task.
     */
    private void applySettings(@NotNull AbstractNativeToolSettings<?> settings) {
        if (settings instanceof PostgreDatabaseBackupSettings pg) {
            formatCombo.setSelectedItem(pg.getFormat());
            setOutputPatterns(pg.getOutputFolderPattern(), pg.getOutputFilePattern());
            compressionField.setText(CommonUtils.notEmpty(pg.getCompression()));
            encodingField.setText(CommonUtils.notEmpty(pg.getEncoding()));
            setOption("noOwner", pg.isNoOwner()); //$NON-NLS-1$
            setOption("noPrivileges", pg.isNoPrivileges()); //$NON-NLS-1$
            setOption("inserts", pg.isUseInserts()); //$NON-NLS-1$
            setOption("clean", pg.isDropObjects()); //$NON-NLS-1$
            setOption("create", pg.isCreateDatabase()); //$NON-NLS-1$
        } else if (settings instanceof PostgreDatabaseRestoreSettings pg) {
            formatCombo.setSelectedItem(pg.getFormat());
            inputFileField.setText(CommonUtils.notEmpty(pg.getInputFile()));
            setOption("clean", pg.isCleanFirst()); //$NON-NLS-1$
            setOption("noOwner", pg.isNoOwner()); //$NON-NLS-1$
            setOption("create", pg.isCreateDatabase()); //$NON-NLS-1$
            // The only extra arguments this dialog writes (see createSettings)
            setOption("stopOnError", !CommonUtils.isEmptyTrimmed(pg.getExtraCommandArgs())); //$NON-NLS-1$
        } else if (settings instanceof MySQLExportSettings my) {
            methodCombo.setSelectedIndex(switch (my.getMethod()) {
                case ONLINE -> 0;
                case LOCK_ALL_TABLES -> 1;
                default -> 2;
            });
            setOutputPatterns(my.getOutputFolderPattern(), my.getOutputFilePattern());
            setOption("noCreate", my.isNoCreateStatements()); //$NON-NLS-1$
            setOption("addDrop", my.isAddDropStatements()); //$NON-NLS-1$
            setOption("disableKeys", my.isDisableKeys()); //$NON-NLS-1$
            setOption("extendedInserts", my.isExtendedInserts()); //$NON-NLS-1$
            setOption("noData", my.isNoData()); //$NON-NLS-1$
            setOption("routines", !my.isNoRoutines()); //$NON-NLS-1$
            setOption("events", my.isDumpEvents()); //$NON-NLS-1$
            setOption("comments", my.isComments()); //$NON-NLS-1$
            setOption("removeDefiner", my.isRemoveDefiner()); //$NON-NLS-1$
            setOption("hexBlob", my.isBinariesInHex()); //$NON-NLS-1$
        } else if (settings instanceof AbstractScriptExecuteSettings<?> scriptSettings) {
            inputFileField.setText(CommonUtils.notEmpty(scriptSettings.getInputFile()));
            if (scriptSettings instanceof MySQLScriptExecuteSettings my) {
                setOption("disableFk", my.isForeignKeyCheckDisabled()); //$NON-NLS-1$
                setOption("verbose", my.getLogLevel() == MySQLScriptExecuteSettings.LogLevel.Verbose); //$NON-NLS-1$
            } else {
                setOption("stopOnError", !CommonUtils.isEmptyTrimmed(scriptSettings.getExtraCommandArgs())); //$NON-NLS-1$
            }
        }
    }

    private void setOutputPatterns(@Nullable String folder, @Nullable String fileName) {
        folderField.setText(CommonUtils.notEmpty(folder));
        if (!CommonUtils.isEmpty(fileName)) {
            fileNameField.setText(fileName);
            // The saved name stays when the format changes
            fileNameEdited = true;
        }
    }

    private void setOption(@NotNull String id, boolean selected) {
        JCheckBox check = optionChecks.get(id);
        if (check != null) {
            check.setSelected(selected);
        }
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
        return script ? NativeBackupRestore.getScriptToolName(database) : NativeBackupRestore.getToolNames(kind == Kind.POSTGRES, restore);
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
        if (restore || script || fileNameEdited) {
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
        clientModel.removeAllElements();
        DBPNativeClientLocation selected = null;
        for (DBPNativeClientLocation location : NativeBackupRestore.findLocalClients(container.getDriver())) {
            clientModel.addElement(location);
            if (location.getName().equals(selectedId)) {
                selected = location;
            }
        }
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
        NativeBackupRestore.addLocalClient(container.getDriver(), path);
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
        if (script) {
            AbstractScriptExecuteSettings<?> scriptSettings = NativeBackupRestore.createScriptSettings(database);
            scriptSettings.setInputFile(inputFileField.getText().trim());
            if (scriptSettings instanceof MySQLScriptExecuteSettings my) {
                my.setIsForeignKeyCheckDisabled(option("disableFk")); //$NON-NLS-1$
                my.setLogLevel(option("verbose") ? MySQLScriptExecuteSettings.LogLevel.Verbose : MySQLScriptExecuteSettings.LogLevel.Normal); //$NON-NLS-1$
            } else if (option("stopOnError")) { //$NON-NLS-1$
                // psql runs the script to the end and exits with 0 whatever failed; with ON_ERROR_STOP it stops and exits with 3
                scriptSettings.setExtraCommandArgs("-v ON_ERROR_STOP=1"); //$NON-NLS-1$
            }
            settings = scriptSettings;
        } else if (kind == Kind.POSTGRES && !restore) {
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
            if (option("stopOnError")) { //$NON-NLS-1$
                // psql runs a plain script to the end and exits with 0 whatever failed; with ON_ERROR_STOP it stops and
                // exits with 3. pg_restore goes on after errors too (exit code 1 at the end) unless --exit-on-error.
                pg.setExtraCommandArgs(pg.getFormat() == PostgreBackupRestoreSettings.ExportFormat.PLAIN
                    ? "-v ON_ERROR_STOP=1" : "--exit-on-error"); //$NON-NLS-1$ //$NON-NLS-2$
            }
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
            commandArea.setText(describeCommand(createSettings()));
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

    /**
     * The command line, and the commands written to the tool's standard input before the script (SQL*Plus logon, password
     * masked).
     */
    @NotNull
    private static String describeCommand(@NotNull AbstractNativeToolSettings<?> settings) throws Exception {
        String command = NativeBackupRestore.getCommandLine(settings);
        String input = NativeBackupRestore.getStandardInputPreview(settings);
        return input == null ? command : command + "\n\n" + BackupRestoreMessages.native_stdin + "\n" + input.strip(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Nullable
    @Override
    protected String validateInput() {
        if (restore || script) {
            String input = inputFileField.getText().trim();
            if (input.isEmpty()) {
                return script ? BackupRestoreMessages.native_script_error_no_input : BackupRestoreMessages.native_error_no_input;
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
            NativeBackupRestore.getCommandLine(createSettings());
        } catch (Exception e) {
            return NLS.bind(BackupRestoreMessages.native_client_tool_missing, getToolNames(), client.getPath().getAbsolutePath());
        }
        return null;
    }

    /**
     * A script run shows the command line and says that it is a session of its own before it starts (the command line is
     * on the Local Client page, which the user may not have opened). Cancel is the default button.
     */
    @Override
    protected boolean confirmRun() {
        if (!script) {
            return true;
        }
        String command;
        try {
            command = describeCommand(createSettings());
        } catch (Exception e) {
            log.debug("Can't build the command line", e);
            return false;
        }
        // Long command lines wrap: room for a few wrapped lines without a scroll bar
        JTextArea commandText = new JTextArea(command, Math.min(10, command.split("\n").length + 3), 60); //$NON-NLS-1$
        commandText.setEditable(false);
        commandText.setLineWrap(true);
        commandText.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        JPanel message = new JPanel(new BorderLayout(0, 8));
        message.add(new JLabel(NLS.bind(BackupRestoreMessages.native_script_confirm, getToolNames())), BorderLayout.NORTH);
        message.add(new JScrollPane(commandText), BorderLayout.CENTER);
        return SwingPlatformUI.confirmChange(this, message, BackupRestoreMessages.native_script_confirm_title,
            BackupRestoreMessages.native_script_confirm_run, JOptionPane.QUESTION_MESSAGE);
    }

    @NotNull
    @Override
    protected TaskRun prepareRun() {
        AbstractNativeToolSettings<?> settings = createSettings();
        storeClientHome();
        String title = getTitle();
        return monitor -> runHandler(monitor, title, settings);
    }

    /**
     * The handlers read the client from the connection, as the SWT native client dialog stores it: the chosen local client
     * becomes the connection's client home (a saved task runs with it too).
     */
    private void storeClientHome() {
        DBPNativeClientLocation client = Objects.requireNonNull(getSelectedClient());
        if (!client.getName().equals(container.getConnectionConfiguration().getClientHomeId())) {
            container.getConnectionConfiguration().setClientHomeId(client.getName());
            container.persistConfiguration();
        }
    }

    private void runHandler(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String title,
        @NotNull AbstractNativeToolSettings<?> settings
    ) throws Exception {
        boolean restoreErrors;
        try (PrintStream logStream = new PrintStream(new MessagesStream(), true, StandardCharsets.UTF_8)) {
            restoreErrors = NativeBackupRestore.execute(monitor, settings, title, logStream);
        }
        if (restoreErrors) {
            throw new DBException(BackupRestoreMessages.native_error_pg_restore_errors);
        }
    }

    @NotNull
    @Override
    protected String getSuccessText() {
        return NLS.bind(script ? BackupRestoreMessages.native_script_success
            : restore ? BackupRestoreMessages.restore_success : BackupRestoreMessages.backup_success, database.getName());
    }

    @NotNull
    @Override
    protected List<Action> getScriptActions() {
        Action copy = new AbstractAction(BackupRestoreMessages.native_copy_command) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                String command = buildScript(() -> NativeBackupRestore.getCommandLine(createSettings()));
                if (command != null) {
                    DBWorkbench.getPlatformUI().copyTextToClipboard(command, false);
                }
            }
        };
        // Built each time the menu opens: after the first save the item updates that task
        Action save = new AbstractAction(savedTask == null ? BackupRestoreMessages.native_task_save : BackupRestoreMessages.native_task_update) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                saveAsTask();
            }
        };
        return List.of(copy, save);
    }

    /**
     * Saves the settings as a task of the project (Database Tasks view), or into the task this dialog edits. The chosen local
     * client becomes the connection's client home, as when the dialog runs, so that the saved task runs with it.
     */
    private void saveAsTask() {
        String problem = validateInput();
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        Object name = JOptionPane.showInputDialog(this, BackupRestoreMessages.native_task_name, BackupRestoreMessages.native_task_save_title,
            JOptionPane.QUESTION_MESSAGE, null, null, savedTask != null ? savedTask.getName() : getTitle());
        if (name == null || name.toString().isBlank()) {
            return;
        }
        try {
            storeClientHome();
            savedTask = NativeBackupRestore.saveTask(createSettings(), name.toString().strip(), savedTask);
        } catch (DBException e) {
            log.debug("Can't save the task", e);
            ErrorDialog.show(this, BackupRestoreMessages.native_task_save_title, ErrorText.describe(e), e);
            return;
        }
        JOptionPane.showMessageDialog(this, NLS.bind(BackupRestoreMessages.native_task_saved, savedTask.getName()),
            BackupRestoreMessages.native_task_save_title, JOptionPane.INFORMATION_MESSAGE);
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
}
