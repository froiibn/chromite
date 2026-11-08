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
import com.seizia.chromite.ext.mysql.model.MySQLTableBase;
import com.seizia.chromite.ext.mysql.tasks.MySQLDatabaseExportInfo;
import com.seizia.chromite.ext.mysql.tasks.MySQLExportSettings;
import com.seizia.chromite.ext.mysql.tasks.MySQLScriptExecuteSettings;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.model.PostgreSchema;
import com.seizia.chromite.ext.postgresql.model.PostgreTableBase;
import com.seizia.chromite.ext.postgresql.tasks.PostgreBackupAllSettings;
import com.seizia.chromite.ext.postgresql.tasks.PostgreBackupRestoreSettings;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseBackupAllInfo;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseBackupInfo;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseBackupSelection;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseBackupSettings;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseRestoreInfo;
import com.seizia.chromite.ext.postgresql.tasks.PostgreDatabaseRestoreSettings;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
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
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

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
 *     <li>고급 백업(SWT 백업 마법사의 개체 페이지와 설정): PostgreSQL은 백업 범위(이 데이터베이스 pg_dump / 전역 개체 pg_dumpall /
 *     전체 클러스터 pg_dumpall)를 General에서 고르고, 개체 페이지에서 스키마·테이블(모델의 {@link PostgreDatabaseBackupSelection},
 *     SWT와 같은 규칙), 클러스터의 데이터베이스, 역할·테이블스페이스를 고른다. MySQL은 테이블을 고른다. 모두 고르면 지금처럼
 *     데이터베이스 전체다(인수 없음). 개체 목록은 대화상자를 열 때 읽고(PostgreSQL 테이블은 스키마를 고를 때), 읽지 못하면 전체 백업만 된다.</li>
 *     <li>도구 계정과 추가 인수(Local Client 페이지): 암호는 처리기가 환경 변수(PGPASSWORD, MYSQL_PWD)로만 넘기고 작업에 저장하지 않는다.
 *     추가 인수는 공백으로 나눠 명령줄 끝 쪽에 붙는다(모델이 따옴표를 풀지 않음). "첫 오류에서 멈춤" 인수는 이 칸과 따로 두고 뒤에 붙인다.</li>
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
 * - 2026-11-08 고급 백업·복원: PostgreSQL 스키마·테이블 고르기, pg_dumpall(전역 개체, 전체 클러스터), MySQL 테이블 고르기와 --compress,
 *   도구 계정, 추가 인수 (docs/chromite/swing-backup-restore.md "고급 백업·복원")
 */
final class NativeBackupRestoreDialog extends DatabaseTaskDialog {

    private static final Log log = Log.getLog(NativeBackupRestoreDialog.class);

    // The stop-at-the-first-error option is passed in the extra arguments, after the user's own
    private static final String PSQL_STOP_ON_ERROR = "-v ON_ERROR_STOP=1"; //$NON-NLS-1$
    private static final String PG_RESTORE_STOP_ON_ERROR = "--exit-on-error"; //$NON-NLS-1$

    private enum Kind {
        POSTGRES,
        MYSQL,
        ORACLE
    }

    /**
     * What a PostgreSQL backup covers: this database (pg_dump), the global objects of the server or the whole cluster (pg_dumpall).
     */
    private enum PgScope {
        DATABASE,
        GLOBALS,
        CLUSTER
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

    // Advanced backup: scope (PostgreSQL), objects, tool account, extra arguments
    private final JComboBox<PgScope> scopeCombo = new JComboBox<>(PgScope.values());
    private final CardLayout objectCards = new CardLayout();
    private final JPanel objectPanel = new JPanel(objectCards);
    private final CardLayout optionCards = new CardLayout();
    private final JPanel optionPanel = new JPanel(optionCards);
    private final JCheckBox showViewsCheck = new JCheckBox(BackupRestoreMessages.native_objects_show_views);
    // PostgreSQL schemas to choose from (empty when they could not be read: then the whole database is backed up)
    private final List<PostgreSchema> pgSchemas = new ArrayList<>();
    private final Map<PostgreSchema, List<PostgreTableBase>> pgTables = new HashMap<>();
    private final PostgreDatabaseBackupSelection pgSelection = new PostgreDatabaseBackupSelection();
    @Nullable
    private PostgreSchema pgCurrentSchema;
    // MySQL tables and views of the database
    private final List<MySQLTableBase> myTables = new ArrayList<>();
    private final CheckList<PostgreSchema> schemaList = new CheckList<>(BackupRestoreMessages.native_objects_schemas, PostgreSchema::getName, null);
    private final CheckList<DBSObject> tableList = new CheckList<>(BackupRestoreMessages.native_objects_tables, DBSObject::getName, showViewsCheck);
    private final CheckList<PostgreDatabase> databaseList = new CheckList<>(BackupRestoreMessages.native_objects_databases, PostgreDatabase::getName, null);
    private final JTextField toolUserField = new JTextField();
    private final JPasswordField toolPasswordField = new JPasswordField();
    private final JTextField extraArgsField = new JTextField();

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
        if (kind == Kind.POSTGRES && !restore && !script) {
            scopeCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    return super.getListCellRendererComponent(list, value instanceof PgScope scope
                        ? scopeTitle(scope) : value, index, isSelected, cellHasFocus);
                }
            });
            scopeCombo.addActionListener(e -> scopeChanged());
            general.addRow(BackupRestoreMessages.native_pg_scope, scopeCombo);
            general.addFull(FormPanel.note(BackupRestoreMessages.native_pg_scope_note));
        }
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
            addOption(options, "compress", BackupRestoreMessages.native_mysql_compress, false); //$NON-NLS-1$
        } else {
            addOption(options, "disableFk", BackupRestoreMessages.native_mysql_disable_fk, false); //$NON-NLS-1$
            addOption(options, "verbose", BackupRestoreMessages.native_mysql_verbose, false); //$NON-NLS-1$
        }
        options.finish();
        JComponent optionsPage = options;
        JComponent objectsPage = null;
        if (!restore && !script) {
            if (kind == Kind.POSTGRES) {
                // pg_dumpall has options of its own: the page shows those of the chosen scope
                FormPanel allOptions = new FormPanel();
                allOptions.addSection(BackupRestoreMessages.native_section_options);
                addOption(allOptions, "allSchemaOnly", BackupRestoreMessages.native_pg_all_schema_only, false); //$NON-NLS-1$
                addOption(allOptions, "allNoOwner", BackupRestoreMessages.native_pg_all_no_owner, false); //$NON-NLS-1$
                addOption(allOptions, "allNoPrivileges", BackupRestoreMessages.native_pg_no_privileges, false); //$NON-NLS-1$
                // Off by default, as in DBeaver (--no-role-passwords): the backup file would hold the password hashes
                addOption(allOptions, "allRolePasswords", BackupRestoreMessages.native_pg_all_role_passwords, false); //$NON-NLS-1$
                allOptions.addFull(FormPanel.note(BackupRestoreMessages.native_pg_all_note));
                allOptions.finish();
                optionPanel.add(options, "dump"); //$NON-NLS-1$
                optionPanel.add(allOptions, "all"); //$NON-NLS-1$
                optionsPage = optionPanel;
                objectsPage = createPostgresObjects();
            } else if (kind == Kind.MYSQL) {
                objectsPage = createMySQLObjects();
            }
        }

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
        if (kind != Kind.ORACLE) {
            // SQL*Plus logs on with the connection's account through its standard input; the model handler has no tool account for it
            client.addSection(BackupRestoreMessages.native_section_account);
            client.addRow(BackupRestoreMessages.native_tool_user, toolUserField);
            client.addRow(BackupRestoreMessages.native_tool_password, toolPasswordField);
            client.addFull(FormPanel.note(NLS.bind(BackupRestoreMessages.native_tool_account_note,
                kind == Kind.POSTGRES ? "PGPASSWORD" : "MYSQL_PWD"))); //$NON-NLS-1$ //$NON-NLS-2$
            client.addRow(BackupRestoreMessages.native_extra_args, extraArgsField);
            client.addFull(FormPanel.note(BackupRestoreMessages.native_extra_args_note));
        }
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
        toolUserField.getDocument().addDocumentListener(previewListener);
        // The password is never shown; it only decides whether pg_* gets --no-password
        toolPasswordField.getDocument().addDocumentListener(previewListener);
        extraArgsField.getDocument().addDocumentListener(previewListener);
        updateDefaultFileName();
        loadClients(container.getConnectionConfiguration().getClientHomeId());
        if (objectsPage != null) {
            loadObjects();
        }
        if (savedSettings != null) {
            applySettings(savedSettings);
        }
        if (kind == Kind.POSTGRES && !restore && !script) {
            scopeChanged();
        }

        List<Page> pages = new ArrayList<>();
        pages.add(new Page(BackupRestoreMessages.task_page_general, general));
        if (objectsPage != null) {
            pages.add(new Page(BackupRestoreMessages.native_page_objects, objectsPage));
        }
        pages.add(new Page(script ? BackupRestoreMessages.native_script_page_options
            : restore ? BackupRestoreMessages.restore_page_options : BackupRestoreMessages.backup_page_options, optionsPage));
        pages.add(new Page(BackupRestoreMessages.native_page_client, client));
        initDialog(pages);
        updatePreview();
    }

    //////////////////////////////////////////////////////////
    // Objects page

    /**
     * PostgreSQL objects: one card per scope. This database: schemas, and the tables of the schema selected in the list
     * (pg_dump -n / -t, as the SWT backup wizard). Global objects: roles and tablespaces. Whole cluster: its databases.
     */
    @NotNull
    private JComponent createPostgresObjects() {
        JPanel databaseCard = new JPanel(new BorderLayout(0, 8));
        databaseCard.add(FormPanel.note(BackupRestoreMessages.native_objects_pg_note), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, schemaList, tableList);
        split.setResizeWeight(0.4);
        split.setBorder(BorderFactory.createEmptyBorder());
        databaseCard.add(split, BorderLayout.CENTER);
        schemaList.setOnChange(this::pgSchemasChecked);
        schemaList.setOnSelect(this::showPgTables);
        tableList.setOnChange(this::pgTablesChecked);
        showViewsCheck.addActionListener(e -> showPgTables(pgCurrentSchema));

        FormPanel globalsCard = new FormPanel();
        globalsCard.addSection(BackupRestoreMessages.native_objects_globals);
        addOption(globalsCard, "globalsRoles", BackupRestoreMessages.native_objects_roles, true); //$NON-NLS-1$
        addOption(globalsCard, "globalsTablespaces", BackupRestoreMessages.native_objects_tablespaces, true); //$NON-NLS-1$
        globalsCard.addFull(FormPanel.note(BackupRestoreMessages.native_objects_globals_note));
        globalsCard.finish();

        JPanel clusterCard = new JPanel(new BorderLayout(0, 8));
        clusterCard.add(FormPanel.note(BackupRestoreMessages.native_objects_cluster_note), BorderLayout.NORTH);
        clusterCard.add(databaseList, BorderLayout.CENTER);
        databaseList.setOnChange(this::schedulePreview);

        objectPanel.add(databaseCard, PgScope.DATABASE.name());
        objectPanel.add(globalsCard, PgScope.GLOBALS.name());
        objectPanel.add(clusterCard, PgScope.CLUSTER.name());
        return objectPanel;
    }

    /**
     * MySQL objects: the tables (and views) of the database. All checked backs up the whole database (with its routines);
     * some backs up only those tables (mysqldump db table…, without routines), as the SWT export wizard.
     */
    @NotNull
    private JComponent createMySQLObjects() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(FormPanel.note(BackupRestoreMessages.native_objects_mysql_note), BorderLayout.NORTH);
        panel.add(tableList, BorderLayout.CENTER);
        tableList.setOnChange(this::schedulePreview);
        showViewsCheck.addActionListener(e -> showMySQLTables());
        return panel;
    }

    /**
     * Reads the objects to choose from when the dialog opens: the PostgreSQL schemas (their tables are read when a schema
     * is selected) and databases, or the MySQL tables and views. Everything is checked: the whole database, as before.
     * When they can't be read, the page says so and the whole database is backed up.
     */
    private void loadObjects() {
        try {
            if (database instanceof PostgreDatabase pg) {
                pgSchemas.addAll(ProgressDialog.runTask(NLS.bind(BackupRestoreMessages.native_objects_loading, database.getName()), monitor -> {
                    List<PostgreSchema> schemas = new ArrayList<>();
                    for (PostgreSchema schema : pg.getSchemas(monitor)) {
                        if (!schema.isSystem() && !schema.isUtility()) {
                            schemas.add(schema);
                        }
                    }
                    return schemas;
                }));
            } else if (database instanceof MySQLCatalog catalog) {
                myTables.addAll(ProgressDialog.runTask(NLS.bind(BackupRestoreMessages.native_objects_loading, database.getName()), monitor -> {
                    List<MySQLTableBase> tables = new ArrayList<>(catalog.getTables(monitor));
                    tables.addAll(catalog.getViews(monitor));
                    tables.sort(DBUtils.nameComparator());
                    return tables;
                }));
            }
        } catch (DBException e) {
            log.debug("Can't read the objects of " + database.getName(), e);
            JOptionPane.showMessageDialog(getOwner(), NLS.bind(BackupRestoreMessages.native_objects_error, ErrorText.describe(e)),
                getTitle(), JOptionPane.WARNING_MESSAGE);
        }
        if (database instanceof PostgreDatabase pg) {
            pgSelection.selectSchemas(pgSchemas, true);
            schemaList.setItems(pgSchemas, pgSelection::isSchemaSelected);
            databaseList.setItems(pg.getDataSource().getDatabases(), db -> true);
            if (!pgSchemas.isEmpty()) {
                schemaList.select(pgSchemas.get(0));
            }
        } else {
            showMySQLTables();
            tableList.setAllChecked(true);
        }
    }

    /**
     * Shows the tables (and views, if chosen) of the schema with their checks from the selection.
     */
    private void showPgTables(@Nullable PostgreSchema schema) {
        pgCurrentSchema = schema;
        if (schema == null) {
            tableList.setItems(List.of(), t -> false);
            return;
        }
        List<DBSObject> items = new ArrayList<>();
        for (PostgreTableBase table : getPgTables(schema)) {
            if (showViewsCheck.isSelected() || !table.isView()) {
                items.add(table);
            }
        }
        tableList.setItems(items, t -> pgSelection.isTableSelected(schema, (PostgreTableBase) t));
    }

    @NotNull
    private List<PostgreTableBase> getPgTables(@NotNull PostgreSchema schema) {
        List<PostgreTableBase> tables = pgTables.get(schema);
        if (tables == null) {
            try {
                tables = ProgressDialog.runTask(NLS.bind(BackupRestoreMessages.native_objects_loading, schema.getName()), monitor -> {
                    List<PostgreTableBase> list = new ArrayList<>(schema.getTables(monitor));
                    list.addAll(schema.getViews(monitor));
                    list.sort(DBUtils.nameComparator());
                    return list;
                });
            } catch (DBException e) {
                log.debug("Can't read the tables of " + schema.getName(), e);
                ErrorDialog.show(this, getTitle(), NLS.bind(BackupRestoreMessages.native_objects_error, ErrorText.describe(e)), e);
                tables = List.of();
            }
            pgTables.put(schema, tables);
        }
        return tables;
    }

    /**
     * Schema checks changed: a checked schema that was not selected is selected whole, an unchecked one is dropped. A schema
     * that stays checked keeps its table choice.
     */
    private void pgSchemasChecked() {
        for (PostgreSchema schema : pgSchemas) {
            boolean checked = schemaList.isChecked(schema);
            if (checked != pgSelection.isSchemaSelected(schema)) {
                pgSelection.selectSchema(schema, checked);
            }
        }
        showPgTables(pgCurrentSchema);
        schedulePreview();
    }

    /**
     * Table checks of the current schema changed: all listed tables select the whole schema (as the SWT wizard), none drops it.
     */
    private void pgTablesChecked() {
        if (pgCurrentSchema == null || tableList.isEmpty()) {
            // A schema without tables is chosen with its own check
            return;
        }
        List<DBSObject> checked = tableList.getChecked();
        List<PostgreTableBase> tables = new ArrayList<>();
        for (DBSObject object : checked) {
            tables.add((PostgreTableBase) object);
        }
        pgSelection.selectTables(pgCurrentSchema, tables, tableList.isAllChecked());
        schemaList.setChecked(pgCurrentSchema, pgSelection.isSchemaSelected(pgCurrentSchema));
        schedulePreview();
    }

    /**
     * Lists the MySQL tables (and views, if chosen). The checks stay; views that appear are checked when everything listed was.
     */
    private void showMySQLTables() {
        boolean all = tableList.isAllChecked();
        Set<DBSObject> checked = new HashSet<>(tableList.getChecked());
        List<DBSObject> items = new ArrayList<>();
        for (MySQLTableBase table : myTables) {
            if (showViewsCheck.isSelected() || !table.isView()) {
                items.add(table);
            }
        }
        tableList.setItems(items, t -> checked.contains(t) || all && ((MySQLTableBase) t).isView());
        schedulePreview();
    }

    @NotNull
    private PgScope getScope() {
        return kind == Kind.POSTGRES && !restore && !script ? (PgScope) Objects.requireNonNull(scopeCombo.getSelectedItem()) : PgScope.DATABASE;
    }

    @NotNull
    private static String scopeTitle(@NotNull PgScope scope) {
        return switch (scope) {
            case DATABASE -> BackupRestoreMessages.native_pg_scope_database;
            case GLOBALS -> BackupRestoreMessages.native_pg_scope_globals;
            case CLUSTER -> BackupRestoreMessages.native_pg_scope_cluster;
        };
    }

    /**
     * pg_dumpall writes a plain SQL script: the format and compression of pg_dump don't apply to it.
     */
    private void scopeChanged() {
        PgScope scope = getScope();
        boolean dump = scope == PgScope.DATABASE;
        formatCombo.setEnabled(dump);
        compressionField.setEnabled(dump);
        objectCards.show(objectPanel, scope.name());
        optionCards.show(optionPanel, dump ? "dump" : "all"); //$NON-NLS-1$ //$NON-NLS-2$
        setOptionEnabled("allSchemaOnly", scope == PgScope.CLUSTER); //$NON-NLS-1$
        updateDefaultFileName();
        schedulePreview();
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
            applyPgSelection(pg);
        } else if (settings instanceof PostgreBackupAllSettings pg) {
            setOutputPatterns(pg.getOutputFolderPattern(), pg.getOutputFilePattern());
            encodingField.setText(CommonUtils.notEmpty(pg.getEncoding()));
            boolean globals = pg.isExportOnlyGlobals() || pg.isExportOnlyRoles() || pg.isExportOnlyTablespaces();
            scopeCombo.setSelectedItem(globals ? PgScope.GLOBALS : PgScope.CLUSTER);
            setOption("globalsRoles", !pg.isExportOnlyTablespaces()); //$NON-NLS-1$
            setOption("globalsTablespaces", !pg.isExportOnlyRoles()); //$NON-NLS-1$
            setOption("allSchemaOnly", pg.isExportOnlyMetadata()); //$NON-NLS-1$
            setOption("allNoOwner", pg.isNoOwner()); //$NON-NLS-1$
            setOption("allNoPrivileges", pg.isNoPrivileges()); //$NON-NLS-1$
            setOption("allRolePasswords", pg.isAddRolesPasswords()); //$NON-NLS-1$
            // No databases saved: all of them (pg_dumpall gets no --exclude-database)
            Set<String> names = new HashSet<>();
            for (PostgreDatabaseBackupAllInfo info : pg.getExportObjects()) {
                for (PostgreDatabase db : CommonUtils.safeCollection(info.getDatabases())) {
                    names.add(db.getName());
                }
            }
            databaseList.setItems(databaseList.getItems(), db -> names.isEmpty() || names.contains(db.getName()));
        } else if (settings instanceof PostgreDatabaseRestoreSettings pg) {
            formatCombo.setSelectedItem(pg.getFormat());
            inputFileField.setText(CommonUtils.notEmpty(pg.getInputFile()));
            setOption("clean", pg.isCleanFirst()); //$NON-NLS-1$
            setOption("noOwner", pg.isNoOwner()); //$NON-NLS-1$
            setOption("create", pg.isCreateDatabase()); //$NON-NLS-1$
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
            setOption("compress", my.isCompressed()); //$NON-NLS-1$
            applyMySQLSelection(my);
        } else if (settings instanceof AbstractScriptExecuteSettings<?> scriptSettings) {
            inputFileField.setText(CommonUtils.notEmpty(scriptSettings.getInputFile()));
            if (scriptSettings instanceof MySQLScriptExecuteSettings my) {
                setOption("disableFk", my.isForeignKeyCheckDisabled()); //$NON-NLS-1$
                setOption("verbose", my.getLogLevel() == MySQLScriptExecuteSettings.LogLevel.Verbose); //$NON-NLS-1$
            }
        }
        if (kind != Kind.ORACLE) {
            toolUserField.setText(CommonUtils.notEmpty(settings.getToolUserName()));
            // Only a task saved by an older version has a password (this dialog never saves one): kept for this run
            toolPasswordField.setText(CommonUtils.notEmpty(settings.getToolUserPassword()));
            extraArgsField.setText(takeStopOnError(settings.getExtraCommandArgs()));
        }
    }

    /**
     * The extra arguments of saved settings without the stop-at-the-first-error argument this dialog appends (its option is
     * checked instead). Tasks saved before the extra arguments field had only that argument.
     */
    @NotNull
    private String takeStopOnError(@Nullable String args) {
        String text = CommonUtils.notEmpty(args).trim();
        if (!optionChecks.containsKey("stopOnError")) { //$NON-NLS-1$
            return text;
        }
        for (String stop : List.of(PSQL_STOP_ON_ERROR, PG_RESTORE_STOP_ON_ERROR)) {
            if (text.equals(stop) || text.endsWith(" " + stop)) { //$NON-NLS-1$
                setOption("stopOnError", true); //$NON-NLS-1$
                return text.substring(0, text.length() - stop.length()).trim();
            }
        }
        setOption("stopOnError", false); //$NON-NLS-1$
        return text;
    }

    /**
     * Checks the schemas and tables of saved PostgreSQL backup settings, matched by name, as the SWT wizard reads them:
     * a part without tables is a whole schema; one with tables is those tables of its (single) schema.
     */
    private void applyPgSelection(@NotNull PostgreDatabaseBackupSettings settings) {
        boolean partial = false;
        for (PostgreDatabaseBackupInfo info : settings.getExportObjects()) {
            partial |= !CommonUtils.isEmpty(info.getSchemas());
        }
        if (settings.isFullSchemaBackup() || !partial || pgSchemas.isEmpty()) {
            return;
        }
        pgSelection.clear();
        for (PostgreDatabaseBackupInfo info : settings.getExportObjects()) {
            for (PostgreSchema saved : CommonUtils.safeCollection(info.getSchemas())) {
                PostgreSchema schema = DBUtils.findObject(pgSchemas, saved.getName());
                if (schema == null) {
                    continue;
                }
                if (CommonUtils.isEmpty(info.getTables())) {
                    pgSelection.selectSchema(schema, true);
                    continue;
                }
                List<PostgreTableBase> tables = new ArrayList<>();
                for (PostgreTableBase savedTable : info.getTables()) {
                    PostgreTableBase table = DBUtils.findObject(getPgTables(schema), savedTable.getName());
                    if (table != null) {
                        tables.add(table);
                        if (table.isView()) {
                            showViewsCheck.setSelected(true);
                        }
                    }
                }
                pgSelection.selectTables(schema, tables, false);
                // Only the first schema of a part with tables (see PostgreDatabaseBackupSelection)
                break;
            }
        }
        schemaList.setItems(pgSchemas, pgSelection::isSchemaSelected);
        showPgTables(pgCurrentSchema);
    }

    /**
     * Checks the tables of saved MySQL backup settings, matched by name; none saved is the whole database.
     */
    private void applyMySQLSelection(@NotNull MySQLExportSettings settings) {
        Set<String> names = new HashSet<>();
        for (MySQLDatabaseExportInfo info : settings.getExportObjects()) {
            for (MySQLTableBase table : CommonUtils.safeCollection(info.getTables())) {
                names.add(table.getName());
            }
        }
        if (names.isEmpty()) {
            return;
        }
        for (MySQLTableBase table : myTables) {
            if (table.isView() && names.contains(table.getName())) {
                showViewsCheck.setSelected(true);
            }
        }
        showMySQLTables();
        tableList.setItems(tableList.getItems(), t -> names.contains(t.getName()));
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

    private void setOptionEnabled(@NotNull String id, boolean enabled) {
        JCheckBox check = optionChecks.get(id);
        if (check != null) {
            check.setEnabled(enabled);
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
        return check != null && check.isSelected() && check.isEnabled();
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
        if (kind == Kind.POSTGRES && !restore && !script) {
            // The scope decides the tool; the client folder is the same
            return "pg_dump, pg_dumpall"; //$NON-NLS-1$
        }
        return script ? NativeBackupRestore.getScriptToolName(database) : NativeBackupRestore.getToolNames(kind == Kind.POSTGRES, restore);
    }

    private void fileNameChanged() {
        if (!settingFileName) {
            fileNameEdited = true;
        }
    }

    /**
     * Default file name: as the SWT wizards, "dump-${database}-${timestamp}" with the format's extension. A pg_dumpall
     * backup is of the server, not of a database: "globals-${host}-…" or "cluster-${host}-…", a plain SQL script.
     */
    private void updateDefaultFileName() {
        if (restore || script || fileNameEdited) {
            return;
        }
        PgScope scope = getScope();
        String extension = kind == Kind.MYSQL || scope != PgScope.DATABASE ? "sql" //$NON-NLS-1$
            : ((PostgreBackupRestoreSettings.ExportFormat) Objects.requireNonNull(formatCombo.getSelectedItem())).getExt();
        String name = switch (scope) {
            case DATABASE -> "dump-${database}-${timestamp}"; //$NON-NLS-1$
            case GLOBALS -> "globals-${host}-${timestamp}"; //$NON-NLS-1$
            case CLUSTER -> "cluster-${host}-${timestamp}"; //$NON-NLS-1$
        };
        settingFileName = true;
        try {
            fileNameField.setText(name + (extension.isEmpty() ? "" : "." + extension)); //$NON-NLS-1$ //$NON-NLS-2$
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
        // Appended to the user's extra arguments
        String stopOnErrorArgs = null;
        if (script) {
            AbstractScriptExecuteSettings<?> scriptSettings = NativeBackupRestore.createScriptSettings(database);
            scriptSettings.setInputFile(inputFileField.getText().trim());
            if (scriptSettings instanceof MySQLScriptExecuteSettings my) {
                my.setIsForeignKeyCheckDisabled(option("disableFk")); //$NON-NLS-1$
                my.setLogLevel(option("verbose") ? MySQLScriptExecuteSettings.LogLevel.Verbose : MySQLScriptExecuteSettings.LogLevel.Normal); //$NON-NLS-1$
            } else if (option("stopOnError")) { //$NON-NLS-1$
                // psql runs the script to the end and exits with 0 whatever failed; with ON_ERROR_STOP it stops and exits with 3
                stopOnErrorArgs = PSQL_STOP_ON_ERROR;
            }
            settings = scriptSettings;
        } else if (kind == Kind.POSTGRES && !restore && getScope() != PgScope.DATABASE) {
            settings = createBackupAllSettings(project);
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
            pg.setShowViews(showViewsCheck.isSelected());
            // Everything chosen (or the schemas could not be read): the whole database with no -n/-t, as before; also
            // database-level objects such as extensions, which -n for every schema would leave out
            boolean complete = pgSchemas.isEmpty() || pgSelection.isCompleteBackup(pgSchemas);
            pg.setFullSchemaBackup(complete);
            if (!complete) {
                pg.getExportObjects().clear();
                pg.getExportObjects().addAll(pgSelection.getExportObjects((PostgreDatabase) database, false));
            }
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
                stopOnErrorArgs = pg.getFormat() == PostgreBackupRestoreSettings.ExportFormat.PLAIN
                    ? PSQL_STOP_ON_ERROR : PG_RESTORE_STOP_ON_ERROR;
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
            my.setCompressed(option("compress")); //$NON-NLS-1$
            my.setShowViews(showViewsCheck.isSelected());
            if (!tableList.isAllChecked()) {
                // Some tables: mysqldump db table… (the handler leaves out the routines then, as the SWT wizard)
                List<MySQLTableBase> tables = new ArrayList<>();
                for (DBSObject table : tableList.getChecked()) {
                    tables.add((MySQLTableBase) table);
                }
                my.getExportObjects().clear();
                my.getExportObjects().add(new MySQLDatabaseExportInfo((MySQLCatalog) database, tables));
            }
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
        if (kind != Kind.ORACLE) {
            String toolUser = toolUserField.getText().trim();
            settings.setToolUserName(toolUser.isEmpty() ? null : toolUser);
            char[] password = toolPasswordField.getPassword();
            // The handler passes it in PGPASSWORD / MYSQL_PWD only (never on the command line); saveTask leaves it out
            settings.setToolUserPassword(password.length == 0 ? null : new String(password));
            Arrays.fill(password, '\0');
            // One space between the arguments: the model splits them at each space
            String extra = String.join(" ", extraArgsField.getText().trim().split("\\s+")).trim(); //$NON-NLS-1$ //$NON-NLS-2$
            if (stopOnErrorArgs != null) {
                extra = extra.isEmpty() ? stopOnErrorArgs : extra + " " + stopOnErrorArgs; //$NON-NLS-1$
            }
            settings.setExtraCommandArgs(extra.isEmpty() ? null : extra);
        }
        return settings;
    }

    /**
     * pg_dumpall settings: the global objects (roles, tablespaces, or both) or the whole cluster, all databases or the
     * chosen ones (the handler turns the others into --exclude-database).
     */
    @NotNull
    private PostgreBackupAllSettings createBackupAllSettings(@NotNull DBPProject project) {
        PostgreDatabase pgDatabase = (PostgreDatabase) database;
        PgScope scope = getScope();
        PostgreBackupAllSettings settings = new PostgreBackupAllSettings(project);
        // The database the dialog was opened on is the saved task's object; pg_dumpall itself backs up the server
        settings.getDatabaseObjects().add(pgDatabase);
        // An empty list: all databases (no --exclude-database)
        List<PostgreDatabase> databases = new ArrayList<>();
        if (scope == PgScope.CLUSTER && !databaseList.isAllChecked()) {
            databases.addAll(databaseList.getChecked());
        }
        settings.getExportObjects().add(new PostgreDatabaseBackupAllInfo(pgDatabase.getDataSource(), databases));
        settings.setOutputFolderPattern(folderField.getText().trim());
        settings.setOutputFilePattern(fileNameField.getText().trim());
        settings.setEncoding(encodingField.getText().trim());
        boolean globals = scope == PgScope.GLOBALS;
        boolean roles = option("globalsRoles"); //$NON-NLS-1$
        boolean tablespaces = option("globalsTablespaces"); //$NON-NLS-1$
        settings.setExportOnlyGlobals(globals && roles && tablespaces);
        settings.setExportOnlyRoles(globals && roles && !tablespaces);
        settings.setExportOnlyTablespaces(globals && tablespaces && !roles);
        settings.setExportOnlyMetadata(option("allSchemaOnly")); //$NON-NLS-1$
        settings.setNoOwner(option("allNoOwner")); //$NON-NLS-1$
        settings.setNoPrivileges(option("allNoPrivileges")); //$NON-NLS-1$
        settings.setAddRolesPasswords(option("allRolePasswords")); //$NON-NLS-1$
        settings.setDataSourceContainer(container);
        return settings;
    }

    /**
     * Why the chosen objects can't be backed up, or null.
     */
    @Nullable
    private String validateObjects() {
        if (restore || script) {
            return null;
        }
        if (kind == Kind.POSTGRES) {
            return switch (getScope()) {
                case DATABASE -> pgSchemas.isEmpty() || pgSelection.isCompleteBackup(pgSchemas)
                    || !pgSelection.getExportObjects((PostgreDatabase) database, false).isEmpty() ? null : BackupRestoreMessages.native_objects_error_schemas;
                case GLOBALS -> option("globalsRoles") || option("globalsTablespaces") ? null : BackupRestoreMessages.native_objects_error_globals; //$NON-NLS-1$ //$NON-NLS-2$
                case CLUSTER -> !databaseList.isEmpty() && databaseList.getChecked().isEmpty() ? BackupRestoreMessages.native_objects_error_databases : null;
            };
        }
        // No tables listed (none, or they could not be read): the whole database
        return kind == Kind.MYSQL && !tableList.isEmpty() && tableList.getChecked().isEmpty() ? BackupRestoreMessages.native_objects_error_tables : null;
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
        String objectsProblem = validateObjects();
        if (objectsProblem != null) {
            commandArea.setText(objectsProblem);
            clientStatus.setText(NLS.bind(BackupRestoreMessages.native_client_found, client.getPath().getAbsolutePath()));
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
        String objectsProblem = validateObjects();
        if (objectsProblem != null) {
            return objectsProblem;
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
        PgScope scope = getScope();
        if (scope != PgScope.DATABASE) {
            return scope == PgScope.GLOBALS ? BackupRestoreMessages.native_pg_globals_success : BackupRestoreMessages.native_pg_cluster_success;
        }
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
     * A titled list of objects with check boxes and All / None buttons (the check tables of the SWT backup wizards).
     * {@link #setOnChange} runs after the user changed checks; {@link #setOnSelect} when the selected row changes.
     * Changes made by the code ({@link #setItems}, {@link #setChecked}) run neither.
     */
    private static final class CheckList<T> extends JPanel {
        private final Function<T, String> names;
        private final List<T> items = new ArrayList<>();
        private final Set<T> checked = new HashSet<>();
        private final JTable table;
        private final AbstractTableModel model = new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return items.size();
            }

            @Override
            public int getColumnCount() {
                return 2;
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
                T item = items.get(row);
                return column == 0 ? checked.contains(item) : names.apply(item);
            }

            @Override
            public void setValueAt(Object value, int row, int column) {
                T item = items.get(row);
                if (Boolean.TRUE.equals(value)) {
                    checked.add(item);
                } else {
                    checked.remove(item);
                }
                fireTableCellUpdated(row, column);
                onChange.run();
            }
        };
        private Runnable onChange = () -> { };
        private Consumer<T> onSelect = item -> { };
        // Set while the code replaces the items: the selection events of that are not the user's
        private boolean updating;

        CheckList(@NotNull String title, @NotNull Function<T, String> names, @Nullable JComponent extra) {
            super(new BorderLayout(0, 4));
            this.names = names;
            table = new JTable(model);
            table.setTableHeader(null);
            table.setShowGrid(false);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.getColumnModel().getColumn(0).setMaxWidth(32);
            table.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && !updating) {
                    onSelect.accept(getSelected());
                }
            });
            JLabel label = new JLabel(title);
            label.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
            add(label, BorderLayout.NORTH);
            add(new JScrollPane(table), BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            JButton all = new JButton(BackupRestoreMessages.native_objects_all);
            all.addActionListener(e -> {
                setAllChecked(true);
                onChange.run();
            });
            JButton none = new JButton(BackupRestoreMessages.native_objects_none);
            none.addActionListener(e -> {
                setAllChecked(false);
                onChange.run();
            });
            buttons.add(all);
            buttons.add(none);
            JPanel south = new JPanel(new BorderLayout());
            if (extra != null) {
                south.add(extra, BorderLayout.WEST);
            }
            south.add(buttons, BorderLayout.EAST);
            add(south, BorderLayout.SOUTH);
        }

        void setOnChange(@NotNull Runnable onChange) {
            this.onChange = onChange;
        }

        void setOnSelect(@NotNull Consumer<T> onSelect) {
            this.onSelect = onSelect;
        }

        void setItems(@NotNull List<? extends T> newItems, @NotNull Predicate<T> isChecked) {
            List<T> copy = new ArrayList<>(newItems);
            T selected = getSelected();
            updating = true;
            try {
                items.clear();
                items.addAll(copy);
                checked.clear();
                for (T item : items) {
                    if (isChecked.test(item)) {
                        checked.add(item);
                    }
                }
                model.fireTableDataChanged();
                // Keep the selected row (the list of tables follows the selected schema)
                int row = selected == null ? -1 : items.indexOf(selected);
                if (row >= 0) {
                    table.getSelectionModel().setSelectionInterval(row, row);
                }
            } finally {
                updating = false;
            }
        }

        @NotNull
        List<T> getItems() {
            return new ArrayList<>(items);
        }

        void setChecked(@NotNull T item, boolean check) {
            int row = items.indexOf(item);
            if ((check ? checked.add(item) : checked.remove(item)) && row >= 0) {
                model.fireTableRowsUpdated(row, row);
            }
        }

        void setAllChecked(boolean check) {
            checked.clear();
            if (check) {
                checked.addAll(items);
            }
            // Rows updated, not the data changed: that would clear the selection
            if (!items.isEmpty()) {
                model.fireTableRowsUpdated(0, items.size() - 1);
            }
        }

        boolean isChecked(@NotNull T item) {
            return checked.contains(item);
        }

        /**
         * Every listed item is checked (also when the list is empty).
         */
        boolean isAllChecked() {
            return checked.containsAll(items);
        }

        boolean isEmpty() {
            return items.isEmpty();
        }

        /**
         * The checked items in list order.
         */
        @NotNull
        List<T> getChecked() {
            List<T> result = new ArrayList<>();
            for (T item : items) {
                if (checked.contains(item)) {
                    result.add(item);
                }
            }
            return result;
        }

        void select(@NotNull T item) {
            int row = items.indexOf(item);
            if (row >= 0) {
                table.getSelectionModel().setSelectionInterval(row, row);
            }
        }

        @Nullable
        private T getSelected() {
            int row = table.getSelectedRow();
            return row >= 0 && row < items.size() ? items.get(row) : null;
        }
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
