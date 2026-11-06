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

import com.seizia.chromite.model.admin.mssql.SQLServerBackupRestore;
import com.seizia.chromite.model.admin.mssql.SQLServerBackupSettings;
import com.seizia.chromite.ui.swing.internal.BackupRestoreMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.mssql.model.SQLServerDatabase;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.sql.SQLException;
import java.util.List;

/**
 * SQL Server 데이터베이스 백업 대화상자(Object Explorer의 데이터베이스 &gt; Tasks &gt; Back Up…).
 *
 * 설계: SSMS Back Up Database 대화상자의 세 페이지를 따른다. 일반(원본 데이터베이스, 복구 모델, 백업 유형, 복사 전용,
 * 서버의 백업 파일 경로), 미디어 옵션(덮어쓰기 방식, 확인·체크섬·오류 시 계속), 백업 옵션(백업 세트 이름·설명, 압축).
 * T-SQL은 모델({@link SQLServerBackupSettings})이 만들고, 스크립트 메뉴로 새 쿼리 창이나 클립보드에 낼 수 있다.
 * <ul>
 *     <li>백업 파일은 서버가 쓰므로 경로는 서버의 경로이고 이 컴퓨터의 파일 대화상자를 쓰지 않는다(페이지에 안내).</li>
 *     <li>단순 복구 모델이면 트랜잭션 로그 백업을 고를 수 없다(서버가 거부한다).</li>
 *     <li>압축을 지원하지 않는 에디션(Express 등)이면 압축 칸은 "서버 기본 설정"으로 고정한다.</li>
 *     <li>백업 세트 이름은 사용자가 고치기 전까지 백업 유형을 따라 기본 이름이 바뀐다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 백업·복원 (docs/chromite/swing-backup-restore.md)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 */
final class SqlServerBackupDialog extends DatabaseTaskDialog {

    private final SQLServerDatabase database;
    private final SQLServerBackupRestore.ServerInfo serverInfo;
    private final ObjectExplorerPanel.QueryWindows queryWindows;

    private final JComboBox<String> typeCombo = new JComboBox<>(new String[] {
        BackupRestoreMessages.backup_type_full,
        BackupRestoreMessages.backup_type_differential,
        BackupRestoreMessages.backup_type_log
    });
    private final JCheckBox copyOnlyCheck = new JCheckBox(BackupRestoreMessages.backup_copy_only);
    private final JTextField destinationField = new JTextField();
    private final JRadioButton appendRadio = new JRadioButton(BackupRestoreMessages.backup_media_append);
    private final JRadioButton overwriteRadio = new JRadioButton(BackupRestoreMessages.backup_media_overwrite);
    private final JRadioButton newMediaRadio = new JRadioButton(BackupRestoreMessages.backup_media_new);
    private final JTextField mediaNameField = new JTextField();
    private final JTextField mediaDescriptionField = new JTextField();
    private final JCheckBox verifyCheck = new JCheckBox(BackupRestoreMessages.backup_verify);
    private final JCheckBox checksumCheck = new JCheckBox(BackupRestoreMessages.backup_checksum);
    private final JCheckBox continueCheck = new JCheckBox(BackupRestoreMessages.backup_continue_on_error);
    private final JTextField nameField = new JTextField();
    private final JTextField descriptionField = new JTextField();
    private final JComboBox<String> compressionCombo;
    private boolean nameEdited;
    private boolean settingName;

    private SqlServerBackupDialog(
        @NotNull Component owner,
        @NotNull SQLServerDatabase database,
        @NotNull SQLServerBackupRestore.ServerInfo serverInfo,
        @Nullable SQLServerBackupRestore.DatabaseInfo databaseInfo,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        super(owner, NLS.bind(BackupRestoreMessages.backup_title, database.getName()), database.getDataSource().getContainer());
        this.database = database;
        this.serverInfo = serverInfo;
        this.queryWindows = queryWindows;
        compressionCombo = new JComboBox<>(new String[] {
            NLS.bind(BackupRestoreMessages.backup_compression_default, serverInfo.compressionDefault()
                ? BackupRestoreMessages.backup_compression_on : BackupRestoreMessages.backup_compression_off),
            BackupRestoreMessages.backup_compression_compress,
            BackupRestoreMessages.backup_compression_none
        });
        boolean simpleRecovery = databaseInfo != null && databaseInfo.isSimpleRecovery();

        // General
        FormPanel general = new FormPanel();
        general.addSection(BackupRestoreMessages.backup_section_source);
        JTextField databaseField = new JTextField(database.getName());
        databaseField.setEditable(false);
        general.addRow(BackupRestoreMessages.backup_database, databaseField);
        JTextField recoveryField = new JTextField(databaseInfo == null ? "" : databaseInfo.recoveryModel()); //$NON-NLS-1$
        recoveryField.setEditable(false);
        general.addRow(BackupRestoreMessages.backup_recovery_model, recoveryField);
        typeCombo.setRenderer(new DisabledItemRenderer(simpleRecovery ? 2 : -1));
        typeCombo.addActionListener(e -> {
            if (simpleRecovery && typeCombo.getSelectedIndex() == 2) {
                typeCombo.setSelectedIndex(0);
                return;
            }
            updateDefaultName();
        });
        general.addRow(BackupRestoreMessages.backup_type, typeCombo);
        general.addFull(copyOnlyCheck);
        if (simpleRecovery) {
            general.addFull(FormPanel.note(BackupRestoreMessages.backup_simple_recovery_note));
        }
        general.addSection(BackupRestoreMessages.backup_section_destination);
        destinationField.setText(serverInfo.backupPath(database.getName() + ".bak")); //$NON-NLS-1$
        general.addRow(BackupRestoreMessages.backup_destination, destinationField);
        general.addFull(FormPanel.note(BackupRestoreMessages.backup_server_path_note));
        general.finish();

        // Media options
        FormPanel media = new FormPanel();
        media.addSection(BackupRestoreMessages.backup_section_overwrite);
        ButtonGroup mediaGroup = new ButtonGroup();
        mediaGroup.add(appendRadio);
        mediaGroup.add(overwriteRadio);
        mediaGroup.add(newMediaRadio);
        appendRadio.setSelected(true);
        media.addFull(appendRadio);
        media.addFull(overwriteRadio);
        media.addFull(newMediaRadio);
        media.addRow(BackupRestoreMessages.backup_media_name, mediaNameField);
        media.addRow(BackupRestoreMessages.backup_media_description, mediaDescriptionField);
        Runnable updateMedia = () -> {
            mediaNameField.setEnabled(newMediaRadio.isSelected());
            mediaDescriptionField.setEnabled(newMediaRadio.isSelected());
        };
        appendRadio.addActionListener(e -> updateMedia.run());
        overwriteRadio.addActionListener(e -> updateMedia.run());
        newMediaRadio.addActionListener(e -> updateMedia.run());
        updateMedia.run();
        media.addSection(BackupRestoreMessages.backup_section_reliability);
        media.addFull(verifyCheck);
        media.addFull(checksumCheck);
        media.addFull(continueCheck);
        media.finish();

        // Backup options
        FormPanel options = new FormPanel();
        options.addSection(BackupRestoreMessages.backup_section_backup_set);
        options.addRow(BackupRestoreMessages.backup_set_name, nameField);
        options.addRow(BackupRestoreMessages.backup_set_description, descriptionField);
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                nameChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                nameChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                nameChanged();
            }
        });
        options.addSection(BackupRestoreMessages.backup_section_compression);
        options.addRow(BackupRestoreMessages.backup_compression, compressionCombo);
        if (!serverInfo.compressionSupported()) {
            compressionCombo.setEnabled(false);
            options.addFull(FormPanel.note(BackupRestoreMessages.backup_compression_unsupported));
        }
        options.finish();

        updateDefaultName();
        initDialog(List.of(
            new Page(BackupRestoreMessages.task_page_general, general),
            new Page(BackupRestoreMessages.backup_page_media, media),
            new Page(BackupRestoreMessages.backup_page_options, options)));
    }

    /**
     * Reads the server facts with a progress dialog and opens the dialog.
     */
    static void open(@NotNull Component owner, @NotNull SQLServerDatabase database, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        Object[] facts;
        try {
            facts = ProgressDialog.runTask(BackupRestoreMessages.task_progress_reading, monitor -> new Object[] {
                SQLServerBackupRestore.readServerInfo(monitor, database.getDataSource()),
                SQLServerBackupRestore.readDatabaseInfo(monitor, database.getDataSource(), database.getName())
            });
        } catch (Exception e) {
            DBWorkbench.getPlatformUI().showError(BackupRestoreMessages.backup_menu, BackupRestoreMessages.task_error_read_server, e);
            return;
        }
        SQLServerBackupRestore.ServerInfo serverInfo = (SQLServerBackupRestore.ServerInfo) facts[0];
        if (!serverInfo.backupSupported()) {
            JOptionPane.showMessageDialog(owner, BackupRestoreMessages.backup_not_supported,
                BackupRestoreMessages.backup_menu, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        new SqlServerBackupDialog(owner, database, serverInfo, (SQLServerBackupRestore.DatabaseInfo) facts[1], queryWindows).open();
    }

    private void nameChanged() {
        if (!settingName) {
            nameEdited = true;
        }
    }

    private void updateDefaultName() {
        if (nameEdited) {
            return;
        }
        String pattern = switch (typeCombo.getSelectedIndex()) {
            case 1 -> BackupRestoreMessages.backup_set_name_differential;
            case 2 -> BackupRestoreMessages.backup_set_name_log;
            default -> BackupRestoreMessages.backup_set_name_full;
        };
        settingName = true;
        try {
            nameField.setText(NLS.bind(pattern, database.getName()));
        } finally {
            settingName = false;
        }
    }

    @NotNull
    private SQLServerBackupSettings createSettings() {
        SQLServerBackupSettings settings = new SQLServerBackupSettings(database.getName());
        settings.setBackupType(switch (typeCombo.getSelectedIndex()) {
            case 1 -> SQLServerBackupSettings.BackupType.DIFFERENTIAL;
            case 2 -> SQLServerBackupSettings.BackupType.LOG;
            default -> SQLServerBackupSettings.BackupType.FULL;
        });
        settings.setCopyOnly(copyOnlyCheck.isSelected());
        settings.setDestination(destinationField.getText());
        settings.setMediaMode(newMediaRadio.isSelected() ? SQLServerBackupSettings.MediaMode.NEW_MEDIA_SET
            : overwriteRadio.isSelected() ? SQLServerBackupSettings.MediaMode.OVERWRITE
            : SQLServerBackupSettings.MediaMode.APPEND);
        settings.setMediaName(mediaNameField.getText());
        settings.setMediaDescription(mediaDescriptionField.getText());
        settings.setVerify(verifyCheck.isSelected());
        settings.setChecksum(checksumCheck.isSelected());
        settings.setContinueAfterError(continueCheck.isSelected());
        settings.setBackupSetName(nameField.getText());
        settings.setBackupSetDescription(descriptionField.getText());
        settings.setCompression(switch (compressionCombo.getSelectedIndex()) {
            case 1 -> SQLServerBackupSettings.Compression.COMPRESS;
            case 2 -> SQLServerBackupSettings.Compression.NO_COMPRESSION;
            default -> SQLServerBackupSettings.Compression.SERVER_DEFAULT;
        });
        return settings;
    }

    @Nullable
    @Override
    protected String validateInput() {
        if (CommonUtils.isEmptyTrimmed(destinationField.getText())) {
            return BackupRestoreMessages.backup_error_no_destination;
        }
        if (CommonUtils.isEmptyTrimmed(nameField.getText())) {
            return BackupRestoreMessages.backup_error_no_name;
        }
        return null;
    }

    @NotNull
    @Override
    protected TaskRun prepareRun() {
        List<String> batches = createSettings().buildBatches();
        return monitor -> SQLServerBackupRestore.execute(monitor, database.getDataSource(), batches,
            (number, text, percent) -> {
                appendMessage(text);
                if (percent >= 0) {
                    reportProgress(percent);
                }
            });
    }

    @NotNull
    @Override
    protected String getSuccessText() {
        return NLS.bind(BackupRestoreMessages.backup_success, database.getName());
    }

    @NotNull
    @Override
    protected String getErrorText(@NotNull Throwable error) {
        // All server messages of a failed batch; other errors as everywhere else
        return CommonUtils.getCauseOfType(error, SQLException.class) != null
            ? SQLServerBackupRestore.getErrorText(error) : super.getErrorText(error);
    }

    @NotNull
    @Override
    protected List<Action> getScriptActions() {
        return createSqlScriptActions(queryWindows, () -> SQLServerBackupRestore.toScript(createSettings().buildBatches()));
    }

    /**
     * Shows one combo item as disabled (a log backup of a database in the simple recovery model).
     */
    static final class DisabledItemRenderer extends DefaultListCellRenderer {
        private final int disabledIndex;

        DisabledItemRenderer(int disabledIndex) {
            this.disabledIndex = disabledIndex;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            Component component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            // index -1 is the combo's own display of the selected item
            if (disabledIndex >= 0 && index == disabledIndex) {
                component.setEnabled(false);
            }
            return component;
        }
    }
}
