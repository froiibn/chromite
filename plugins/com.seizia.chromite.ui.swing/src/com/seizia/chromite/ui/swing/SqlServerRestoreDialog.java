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

import com.seizia.chromite.model.admin.mssql.SQLServerBackupFile;
import com.seizia.chromite.model.admin.mssql.SQLServerBackupRestore;
import com.seizia.chromite.model.admin.mssql.SQLServerBackupSet;
import com.seizia.chromite.model.admin.mssql.SQLServerRestoreSettings;
import com.seizia.chromite.ui.swing.internal.BackupRestoreMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.mssql.model.SQLServerDataSource;
import org.jkiss.dbeaver.ext.mssql.model.SQLServerDatabase;
import org.jkiss.dbeaver.model.navigator.DBNDataSource;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNEvent;
import org.jkiss.dbeaver.model.navigator.DBNModel;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.sql.SQLException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL Server 데이터베이스 복원 대화상자(Object Explorer의 데이터베이스 &gt; Tasks &gt; Restore…).
 *
 * 설계: SSMS Restore Database 대화상자의 "장치에서 복원"을 따른다. 일반(서버의 백업 파일, 대상 데이터베이스, 복원할 백업 세트),
 * 파일(파일을 옮길 위치, {@code WITH MOVE}), 옵션(덮어쓰기, 복구 상태, 기존 연결 닫기) 세 페이지다.
 * <ul>
 *     <li>백업 파일 칸은 msdb 백업 기록의 디스크 파일(이 데이터베이스 것 먼저)을 목록으로 주고, 직접 입력할 수도 있다.
 *     경로는 서버의 경로다. 기록에 파일이 있으면 열 때 가장 최근 파일을 바로 읽는다.</li>
 *     <li>"읽기"는 {@code RESTORE HEADERONLY}로 세트 목록을 읽고 기본 복원 계획(마지막 전체 + 차등 + 이어지는 로그)을 고른 뒤
 *     첫 데이터베이스 세트의 {@code FILELISTONLY}로 파일 목록을 읽는다. 고른 세트가 바뀌어 첫 데이터베이스 세트가 달라지면
 *     파일 목록을 다시 읽는다.</li>
 *     <li>대상 이름이나 폴더 옮기기 설정이 바뀌면 파일 위치를 기본값으로 다시 계산한다({@link SQLServerRestoreSettings#resetTargetPaths}).</li>
 *     <li>기존 연결 닫기는 대상 데이터베이스가 서버에 있을 때만 의미가 있으므로, 실행(또는 스크립트)할 때 서버에서 확인한다.</li>
 *     <li>성공하면 탐색기에서 데이터베이스 폴더를 다시 읽는다(새 이름으로 복원했거나 상태가 바뀐 데이터베이스가 보이게). 폴더의 F5와 같이
 *     연결 노드를 새로 고친 뒤, 탐색기가 자식을 다시 읽도록 폴더에 REFRESH 이벤트를 보낸다(연결 노드의 새로 고침은 탐색기가 다시 그리기만 한다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 백업·복원 (docs/chromite/swing-backup-restore.md)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 */
final class SqlServerRestoreDialog extends DatabaseTaskDialog {

    private static final Log log = Log.getLog(SqlServerRestoreDialog.class);

    private final SQLServerDatabase database;
    private final SQLServerDataSource dataSource;
    private final SQLServerBackupRestore.ServerInfo serverInfo;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final SQLServerRestoreSettings settings = new SQLServerRestoreSettings();

    private final JComboBox<String> sourceCombo = new JComboBox<>();
    private final JTextField targetField = new JTextField();
    private final SetTableModel setModel = new SetTableModel();
    private final JTable setTable = new JTable(setModel);
    private final JCheckBox relocateCheck = new JCheckBox(BackupRestoreMessages.restore_relocate);
    private final JTextField dataFolderField = new JTextField();
    private final JTextField logFolderField = new JTextField();
    private final FileTableModel fileModel = new FileTableModel();
    private final JTable fileTable = new JTable(fileModel);
    private final JCheckBox replaceCheck = new JCheckBox(BackupRestoreMessages.restore_replace);
    private final JComboBox<String> recoveryCombo = new JComboBox<>(new String[] {
        BackupRestoreMessages.restore_recovery,
        BackupRestoreMessages.restore_norecovery
    });
    private final JTextArea recoveryNote = FormPanel.note(""); //$NON-NLS-1$
    private final JCheckBox closeConnectionsCheck = new JCheckBox(BackupRestoreMessages.restore_close_connections);
    @Nullable
    private SQLServerBackupSet loadedBaseSet;
    private String loadedSource = ""; //$NON-NLS-1$

    private SqlServerRestoreDialog(
        @NotNull Component owner,
        @NotNull SQLServerDatabase database,
        @NotNull SQLServerBackupRestore.ServerInfo serverInfo,
        @NotNull List<String> historyFiles,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        super(owner, NLS.bind(BackupRestoreMessages.restore_title, database.getName()), database.getDataSource().getContainer());
        this.database = database;
        this.dataSource = database.getDataSource();
        this.serverInfo = serverInfo;
        this.queryWindows = queryWindows;

        // General
        FormPanel general = new FormPanel();
        general.addSection(BackupRestoreMessages.restore_section_source);
        sourceCombo.setEditable(true);
        for (String file : historyFiles) {
            sourceCombo.addItem(file);
        }
        sourceCombo.setSelectedItem(historyFiles.isEmpty() ? serverInfo.backupPath(database.getName() + ".bak") : historyFiles.get(0)); //$NON-NLS-1$
        JButton readButton = new JButton(BackupRestoreMessages.restore_read);
        readButton.setToolTipText(BackupRestoreMessages.restore_read_tip);
        readButton.addActionListener(e -> readBackupSets());
        JPanel sourcePanel = new JPanel(new BorderLayout(6, 0));
        sourcePanel.add(sourceCombo, BorderLayout.CENTER);
        sourcePanel.add(readButton, BorderLayout.EAST);
        general.addRow(BackupRestoreMessages.restore_source, sourcePanel);
        general.addFull(FormPanel.note(BackupRestoreMessages.backup_server_path_note));
        general.addSection(BackupRestoreMessages.restore_section_destination);
        targetField.setText(database.getName());
        targetField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                targetChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                targetChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                targetChanged();
            }
        });
        general.addRow(BackupRestoreMessages.restore_target, targetField);
        general.addSection(BackupRestoreMessages.restore_section_sets);
        setTable.setFillsViewportHeight(true);
        setTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        setTable.getColumnModel().getColumn(0).setPreferredWidth(72);
        setTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        setTable.getColumnModel().getColumn(2).setPreferredWidth(110);
        setTable.getColumnModel().getColumn(3).setPreferredWidth(140);
        setTable.getColumnModel().getColumn(5).setPreferredWidth(140);
        setTable.getColumnModel().getColumn(6).setPreferredWidth(140);
        general.addFiller(new JScrollPane(setTable));

        // Files
        FormPanel files = new FormPanel();
        files.addSection(BackupRestoreMessages.restore_section_relocate);
        files.addFull(relocateCheck);
        dataFolderField.setText(serverInfo.dataFolder());
        logFolderField.setText(serverInfo.logFolder());
        files.addRow(BackupRestoreMessages.restore_data_folder, dataFolderField);
        files.addRow(BackupRestoreMessages.restore_log_folder, logFolderField);
        relocateCheck.addActionListener(e -> {
            updateRelocate();
            resetFileTargets();
        });
        DocumentListener folderListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                resetFileTargets();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                resetFileTargets();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                resetFileTargets();
            }
        };
        dataFolderField.getDocument().addDocumentListener(folderListener);
        logFolderField.getDocument().addDocumentListener(folderListener);
        updateRelocate();
        files.addSection(BackupRestoreMessages.restore_section_files);
        fileTable.setFillsViewportHeight(true);
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(220);
        fileTable.getColumnModel().getColumn(3).setPreferredWidth(220);
        files.addFiller(new JScrollPane(fileTable));
        files.addFull(FormPanel.note(BackupRestoreMessages.backup_server_path_note));

        // Options
        FormPanel options = new FormPanel();
        options.addSection(BackupRestoreMessages.restore_section_options);
        options.addFull(replaceCheck);
        options.addSection(BackupRestoreMessages.restore_section_recovery);
        options.addRow(BackupRestoreMessages.restore_recovery_state, recoveryCombo);
        options.addFull(recoveryNote);
        recoveryCombo.addActionListener(e -> updateRecoveryNote());
        updateRecoveryNote();
        options.addSection(BackupRestoreMessages.restore_section_server_connections);
        options.addFull(closeConnectionsCheck);
        options.finish();

        initDialog(List.of(
            new Page(BackupRestoreMessages.task_page_general, general),
            new Page(BackupRestoreMessages.restore_page_files, files),
            new Page(BackupRestoreMessages.restore_page_options, options)));
        if (!historyFiles.isEmpty()) {
            SwingUtilities.invokeLater(this::readBackupSets);
        }
    }

    /**
     * Reads the server facts and the backup history with a progress dialog and opens the dialog.
     */
    static void open(@NotNull Component owner, @NotNull SQLServerDatabase database, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        Object[] facts;
        try {
            facts = ProgressDialog.runTask(BackupRestoreMessages.task_progress_reading, monitor -> {
                SQLServerBackupRestore.ServerInfo info = SQLServerBackupRestore.readServerInfo(monitor, database.getDataSource());
                List<String> history = new ArrayList<>(
                    SQLServerBackupRestore.readBackupHistoryFiles(monitor, database.getDataSource(), database.getName()));
                for (String file : SQLServerBackupRestore.readBackupHistoryFiles(monitor, database.getDataSource(), null)) {
                    if (!history.contains(file)) {
                        history.add(file);
                    }
                }
                return new Object[] {info, history};
            });
        } catch (Exception e) {
            DBWorkbench.getPlatformUI().showError(BackupRestoreMessages.restore_menu, BackupRestoreMessages.task_error_read_server, e);
            return;
        }
        SQLServerBackupRestore.ServerInfo serverInfo = (SQLServerBackupRestore.ServerInfo) facts[0];
        if (!serverInfo.backupSupported()) {
            JOptionPane.showMessageDialog(owner, BackupRestoreMessages.backup_not_supported,
                BackupRestoreMessages.restore_menu, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        @SuppressWarnings("unchecked")
        List<String> history = (List<String>) facts[1];
        new SqlServerRestoreDialog(owner, database, serverInfo, history, queryWindows).open();
    }

    @NotNull
    private String getSourcePath() {
        Object item = sourceCombo.getEditor().getItem();
        return item == null ? "" : item.toString().trim(); //$NON-NLS-1$
    }

    private void readBackupSets() {
        String path = getSourcePath();
        if (path.isEmpty()) {
            JOptionPane.showMessageDialog(this, BackupRestoreMessages.restore_error_no_source, getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        Object[] result;
        try {
            result = ProgressDialog.runTask(BackupRestoreMessages.restore_progress_reading_sets, monitor -> {
                List<SQLServerBackupSet> sets = SQLServerBackupRestore.readBackupSets(monitor, dataSource, path);
                SQLServerRestoreSettings plan = new SQLServerRestoreSettings();
                plan.setBackupSets(sets);
                plan.selectDefaultPlan();
                SQLServerBackupSet base = plan.getBaseSet();
                List<SQLServerBackupFile> baseFiles = base == null ? List.of()
                    : SQLServerBackupRestore.readBackupFiles(monitor, dataSource, path, base.position());
                return new Object[] {sets, plan.getSelectedSets(), baseFiles};
            });
        } catch (Exception e) {
            ErrorDialog.show(this, getTitle(), getErrorText(e), e);
            return;
        }
        @SuppressWarnings("unchecked")
        List<SQLServerBackupSet> sets = (List<SQLServerBackupSet>) result[0];
        @SuppressWarnings("unchecked")
        List<SQLServerBackupSet> selected = (List<SQLServerBackupSet>) result[1];
        @SuppressWarnings("unchecked")
        List<SQLServerBackupFile> baseFiles = (List<SQLServerBackupFile>) result[2];
        loadedSource = path;
        settings.setSourcePath(path);
        settings.setBackupSets(sets);
        for (SQLServerBackupSet set : selected) {
            settings.setSelected(set, true);
        }
        loadedBaseSet = settings.getBaseSet();
        settings.setFiles(baseFiles);
        if (CommonUtils.isEmptyTrimmed(targetField.getText()) && loadedBaseSet != null) {
            targetField.setText(loadedBaseSet.databaseName());
        }
        setModel.fireTableDataChanged();
        resetFileTargets();
    }

    /**
     * The selection of sets changed: reads the file list again if the base set is another one.
     */
    private void selectionChanged() {
        SQLServerBackupSet base = settings.getBaseSet();
        if (base == null) {
            loadedBaseSet = null;
            settings.setFiles(List.of());
            fileModel.fireTableDataChanged();
            return;
        }
        if (base.equals(loadedBaseSet)) {
            return;
        }
        String path = settings.getSourcePath();
        try {
            List<SQLServerBackupFile> baseFiles = ProgressDialog.runTask(BackupRestoreMessages.restore_progress_reading_files,
                monitor -> SQLServerBackupRestore.readBackupFiles(monitor, dataSource, path, base.position()));
            loadedBaseSet = base;
            settings.setFiles(baseFiles);
        } catch (Exception e) {
            ErrorDialog.show(this, getTitle(), getErrorText(e), e);
        }
        resetFileTargets();
    }

    private void targetChanged() {
        settings.setTargetDatabase(targetField.getText().trim());
        resetFileTargets();
    }

    private void updateRelocate() {
        dataFolderField.setEnabled(relocateCheck.isSelected());
        logFolderField.setEnabled(relocateCheck.isSelected());
    }

    private void resetFileTargets() {
        settings.setTargetDatabase(targetField.getText().trim());
        settings.resetTargetPaths(
            relocateCheck.isSelected() ? dataFolderField.getText() : null,
            relocateCheck.isSelected() ? logFolderField.getText() : null);
        fileModel.fireTableDataChanged();
    }

    private void updateRecoveryNote() {
        recoveryNote.setText(recoveryCombo.getSelectedIndex() == 0
            ? BackupRestoreMessages.restore_recovery_note
            : BackupRestoreMessages.restore_norecovery_note);
    }

    @Nullable
    @Override
    protected String validateInput() {
        if (fileTable.isEditing()) {
            fileTable.getCellEditor().stopCellEditing();
        }
        if (getSourcePath().isEmpty()) {
            return BackupRestoreMessages.restore_error_no_source;
        }
        if (!getSourcePath().equals(loadedSource)) {
            return BackupRestoreMessages.restore_error_not_read;
        }
        if (CommonUtils.isEmptyTrimmed(targetField.getText())) {
            return BackupRestoreMessages.restore_error_no_target;
        }
        if (settings.getSelectedSets().isEmpty()) {
            return BackupRestoreMessages.restore_error_no_sets;
        }
        return null;
    }

    private void applyOptions() {
        settings.setTargetDatabase(targetField.getText().trim());
        settings.setReplace(replaceCheck.isSelected());
        settings.setRecovery(recoveryCombo.getSelectedIndex() == 0);
        settings.setCloseConnections(closeConnectionsCheck.isSelected());
    }

    @NotNull
    @Override
    protected TaskRun prepareRun() {
        applyOptions();
        String target = settings.getTargetDatabase();
        return monitor -> {
            settings.setTargetExists(SQLServerBackupRestore.readDatabaseInfo(monitor, dataSource, target) != null);
            List<String> batches = settings.buildBatches();
            SQLServerBackupRestore.execute(monitor, dataSource, batches, (number, text, percent) -> {
                appendMessage(text);
                if (percent >= 0) {
                    reportProgress(percent);
                }
            });
        };
    }

    @NotNull
    @Override
    protected String getSuccessText() {
        return NLS.bind(BackupRestoreMessages.restore_success, settings.getTargetDatabase());
    }

    @NotNull
    @Override
    protected String getErrorText(@NotNull Throwable error) {
        // All server messages of a failed batch; other errors as everywhere else
        return CommonUtils.getCauseOfType(error, SQLException.class) != null
            ? SQLServerBackupRestore.getErrorText(error) : super.getErrorText(error);
    }

    @Override
    protected void runSucceeded() {
        // Show the restored database (a new name, or another state) in Object Explorer. As F5 on the databases folder
        // (a folder refreshes its parent, the connection), then tell the explorer to read the folder again: the
        // explorer reloads the children of a REFRESH-ed object node, while a connection refresh only repaints it.
        DBNModel navigatorModel = DBWorkbench.getPlatform().getNavigatorModel();
        DBNDatabaseNode databaseNode = navigatorModel == null ? null : navigatorModel.findNode(database);
        DBNDataSource dataSourceNode = databaseNode == null ? null : DBNDataSource.getDataSourceNode(databaseNode);
        if (dataSourceNode == null) {
            return;
        }
        new AbstractJob("Refresh restored database") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    dataSourceNode.refreshNode(monitor, SqlServerRestoreDialog.this);
                    for (DBNDatabaseNode child : dataSourceNode.getChildren(monitor)) {
                        if (child instanceof DBNDatabaseFolder folder && folder.getChildrenClass() != null
                            && SQLServerDatabase.class.isAssignableFrom(folder.getChildrenClass())) {
                            navigatorModel.fireNodeUpdate(SqlServerRestoreDialog.this, folder, DBNEvent.NodeChange.REFRESH);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Can't refresh the databases after the restore", e);
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    @NotNull
    @Override
    protected List<Action> getScriptActions() {
        return createSqlScriptActions(queryWindows, () -> {
            applyOptions();
            String target = settings.getTargetDatabase();
            settings.setTargetExists(ProgressDialog.runTask(BackupRestoreMessages.task_progress_reading,
                monitor -> SQLServerBackupRestore.readDatabaseInfo(monitor, dataSource, target) != null));
            return SQLServerBackupRestore.toScript(settings.buildBatches());
        });
    }

    @NotNull
    private static String getTypeName(@NotNull SQLServerBackupSet set) {
        return switch (set.backupType()) {
            case SQLServerBackupSet.TYPE_DATABASE -> BackupRestoreMessages.backup_type_full;
            case SQLServerBackupSet.TYPE_DIFFERENTIAL -> BackupRestoreMessages.backup_type_differential;
            case SQLServerBackupSet.TYPE_LOG -> BackupRestoreMessages.backup_type_log;
            default -> String.valueOf(set.backupType());
        };
    }

    private final class SetTableModel extends AbstractTableModel {
        private final String[] columns = {
            BackupRestoreMessages.restore_column_restore,
            BackupRestoreMessages.restore_column_name,
            BackupRestoreMessages.restore_column_type,
            BackupRestoreMessages.restore_column_database,
            BackupRestoreMessages.restore_column_position,
            BackupRestoreMessages.restore_column_start,
            BackupRestoreMessages.restore_column_finish,
            BackupRestoreMessages.restore_column_first_lsn,
            BackupRestoreMessages.restore_column_last_lsn,
            BackupRestoreMessages.restore_column_size,
            BackupRestoreMessages.restore_column_user,
            BackupRestoreMessages.restore_column_server
        };
        private final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);

        @Override
        public int getRowCount() {
            return settings.getBackupSets().size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : column == 4 ? Integer.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0;
        }

        @Override
        public Object getValueAt(int row, int column) {
            SQLServerBackupSet set = settings.getBackupSets().get(row);
            return switch (column) {
                case 0 -> settings.isSelected(set);
                case 1 -> CommonUtils.notEmpty(set.name());
                case 2 -> getTypeName(set) + (set.copyOnly() ? " " + BackupRestoreMessages.restore_copy_only_suffix : ""); //$NON-NLS-1$ //$NON-NLS-2$
                case 3 -> set.databaseName();
                case 4 -> set.position();
                case 5 -> set.startDate() == null ? "" : dateFormat.format(set.startDate()); //$NON-NLS-1$
                case 6 -> set.finishDate() == null ? "" : dateFormat.format(set.finishDate()); //$NON-NLS-1$
                case 7 -> set.firstLsn() == null ? "" : set.firstLsn().toPlainString(); //$NON-NLS-1$
                case 8 -> set.lastLsn() == null ? "" : set.lastLsn().toPlainString(); //$NON-NLS-1$
                case 9 -> formatSize(set.backupSize());
                case 10 -> CommonUtils.notEmpty(set.userName());
                case 11 -> CommonUtils.notEmpty(set.serverName());
                default -> ""; //$NON-NLS-1$
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 0) {
                settings.setSelected(settings.getBackupSets().get(row), Boolean.TRUE.equals(value));
                fireTableCellUpdated(row, column);
                SwingUtilities.invokeLater(SqlServerRestoreDialog.this::selectionChanged);
            }
        }
    }

    @NotNull
    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024)); //$NON-NLS-1$
        }
        if (bytes >= 1024L * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024)); //$NON-NLS-1$
        }
        return String.format("%.0f KB", bytes / 1024.0); //$NON-NLS-1$
    }

    private final class FileTableModel extends AbstractTableModel {
        private final String[] columns = {
            BackupRestoreMessages.restore_column_logical_name,
            BackupRestoreMessages.restore_column_file_type,
            BackupRestoreMessages.restore_column_original,
            BackupRestoreMessages.restore_column_restore_as
        };

        @Override
        public int getRowCount() {
            return settings.getFiles().size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 3;
        }

        @Override
        public Object getValueAt(int row, int column) {
            SQLServerBackupFile file = settings.getFiles().get(row);
            return switch (column) {
                case 0 -> file.logicalName();
                case 1 -> file.isLogFile() ? BackupRestoreMessages.restore_file_type_log : BackupRestoreMessages.restore_file_type_data;
                case 2 -> file.physicalName();
                case 3 -> settings.getTargetPath(file);
                default -> ""; //$NON-NLS-1$
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 3 && value != null && !CommonUtils.isEmptyTrimmed(value.toString())) {
                settings.setTargetPath(settings.getFiles().get(row), value.toString().trim());
                fireTableCellUpdated(row, column);
            }
        }
    }
}
