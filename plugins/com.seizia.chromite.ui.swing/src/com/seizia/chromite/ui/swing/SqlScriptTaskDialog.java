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

import com.seizia.chromite.ui.swing.internal.DatabaseTasksMessages;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.tools.sql.SQLScriptExecuteSettings;
import com.seizia.chromite.tools.transfer.task.DTSavedTasks;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 스크립트 작업(여러 스크립트 파일을 연결에서 순서대로 실행) 만들기·고치기 대화상자. Database Tasks의 새 SQL 스크립트 작업…과
 * 그 형식 작업의 편집…이 연다.
 *
 * 설계: SWT의 {@code SQLScriptTaskConfigurator}(작업 형식 {@code scriptExecute}) 설정 페이지를 한 대화상자로 옮겼다. 설정은 모델의
 * {@link SQLScriptExecuteSettings}에 담아 {@link DTSavedTasks#saveScriptTask}로 저장하므로 SWT에서 저장한 작업과 형식이 같고, 실행은 모델의
 * {@code SQLScriptExecuteHandler}가 한다(Database Tasks의 실행). 이 대화상자는 실행하지 않는다: 실행 확인과 트랜잭션 질문은 실행하는 쪽이 한다.
 * <ul>
 *     <li>연결은 목록이다. 브리프는 한 연결이지만 SWT 작업은 여러 연결을 저장할 수 있어, 그런 작업을 고쳐 저장해도 연결이 빠지지 않게 했다.</li>
 *     <li>스크립트는 파일 경로를 저장한다(SWT는 프로젝트 안 상대 경로도 씀. 모델이 둘 다 읽음). 순서를 위·아래로 바꾼다.</li>
 *     <li>옵션은 SWT와 같은 세 가지다. "첫 오류에서 멈춤"은 모델의 {@code ignoreErrors}의 반대이고 기본으로 켠다(브리프). "문장마다 커밋"은
 *     SWT의 자동 커밋이며 기본으로 끈다. 커밋하지 않은 트랜잭션은 실행 끝에 묻는다는 안내를 둔다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-database-tasks.md "SQL 스크립트 작업")
 */
final class SqlScriptTaskDialog extends JDialog {

    private static final Log log = Log.getLog(SqlScriptTaskDialog.class);

    private final DBPProject project;
    @Nullable
    private final DBTTask task;
    private final JTextField nameField = new JTextField(30);
    private final JComboBox<DBPDataSourceContainer> connectionCombo = new JComboBox<>();
    private final DefaultListModel<DBPDataSourceContainer> connections = new DefaultListModel<>();
    private final JList<DBPDataSourceContainer> connectionList = new JList<>(connections);
    private final DefaultListModel<String> scripts = new DefaultListModel<>();
    private final JList<String> scriptList = new JList<>(scripts);
    private final JCheckBox stopOnErrorCheck = new JCheckBox(DatabaseTasksMessages.script_stop_on_error, true);
    private final JCheckBox autoCommitCheck = new JCheckBox(DatabaseTasksMessages.script_auto_commit);
    private final JCheckBox dumpResultsCheck = new JCheckBox(DatabaseTasksMessages.script_dump_results);
    @Nullable
    private static File lastDirectory;

    private SqlScriptTaskDialog(@Nullable Window owner, @NotNull DBPProject project, @Nullable DBTTask task) {
        super(owner, task == null ? DatabaseTasksMessages.script_title_new : NLS.bind(DatabaseTasksMessages.script_title_edit, task.getName()),
            ModalityType.APPLICATION_MODAL);
        this.project = project;
        this.task = task;
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        DefaultListCellRenderer connectionRenderer = new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof DBPDataSourceContainer connection) {
                    setText(connection.getName());
                    setIcon(NodeIcons.getIcon(connection.getDriver().getIcon()));
                }
                return this;
            }
        };
        connectionCombo.setRenderer(connectionRenderer);
        connectionList.setCellRenderer(connectionRenderer);
        for (DBPDataSourceContainer connection : QueryLibraryEditor.getConnections()) {
            connectionCombo.addItem(connection);
        }
        // A fixed row height: an empty list would otherwise be one short row high
        int rowHeight = connectionCombo.getPreferredSize().height;
        connectionList.setFixedCellHeight(rowHeight);
        connectionList.setVisibleRowCount(3);
        scriptList.setFixedCellHeight(rowHeight);
        scriptList.setVisibleRowCount(6);

        JButton addConnection = new JButton(DatabaseTasksMessages.script_add_connection);
        addConnection.addActionListener(e -> {
            if (connectionCombo.getSelectedItem() instanceof DBPDataSourceContainer connection && !connections.contains(connection)) {
                connections.addElement(connection);
            }
        });
        JButton removeConnection = new JButton(DatabaseTasksMessages.script_remove);
        removeConnection.addActionListener(e -> removeSelected(connectionList, connections));
        JPanel connectionChooser = new JPanel(new BorderLayout(6, 0));
        connectionChooser.add(connectionCombo, BorderLayout.CENTER);
        connectionChooser.add(addConnection, BorderLayout.EAST);
        JPanel connectionPanel = new JPanel(new BorderLayout(0, 6));
        connectionPanel.add(connectionChooser, BorderLayout.NORTH);
        connectionPanel.add(createListScroll(connectionList), BorderLayout.CENTER);
        connectionPanel.add(createButtonColumn(removeConnection), BorderLayout.EAST);

        JButton addFiles = new JButton(DatabaseTasksMessages.script_add_files);
        addFiles.addActionListener(e -> chooseScripts());
        JButton removeScript = new JButton(DatabaseTasksMessages.script_remove);
        removeScript.addActionListener(e -> removeSelected(scriptList, scripts));
        JButton up = new JButton(DatabaseTasksMessages.script_up);
        up.addActionListener(e -> moveSelected(-1));
        JButton down = new JButton(DatabaseTasksMessages.script_down);
        down.addActionListener(e -> moveSelected(1));
        JPanel scriptPanel = new JPanel(new BorderLayout(6, 0));
        scriptPanel.add(createListScroll(scriptList), BorderLayout.CENTER);
        scriptPanel.add(createButtonColumn(addFiles, removeScript, up, down), BorderLayout.EAST);

        JPanel options = new JPanel(new GridLayout(0, 1, 0, 2));
        options.add(stopOnErrorCheck);
        options.add(autoCommitCheck);
        options.add(dumpResultsCheck);

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        row = DataTransferObjects.addRow(form, row, DatabaseTasksMessages.script_name, nameField, null);
        row = DataTransferObjects.addRow(form, row, DatabaseTasksMessages.script_connections, connectionPanel, null);
        row = DataTransferObjects.addRow(form, row, DatabaseTasksMessages.script_scripts, scriptPanel, null);
        row = DataTransferObjects.addRow(form, row, null, options, null);
        // Wraps at the form's width (a long label would widen the dialog)
        DataTransferObjects.addRow(form, row, null, DatabaseTaskDialog.FormPanel.note(DatabaseTasksMessages.script_note), null);

        JButton save = new JButton(DatabaseTasksMessages.script_save);
        save.addActionListener(e -> save());
        JButton cancel = new JButton(ToolsMessages.button_cancel);
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(save);
        buttons.add(cancel);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        // Saving does not run anything: Save is the default button
        getRootPane().setDefaultButton(save);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        // Again: the note knows its wrapped height only after the first layout gave it a width
        pack();
        setMinimumSize(new Dimension(Math.max(640, getWidth()), getHeight()));
        setLocationRelativeTo(owner);
    }

    /**
     * Opens the dialog for a new SQL script task of the active project.
     */
    static void openNew(@NotNull Component owner) {
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        if (project != null) {
            new SqlScriptTaskDialog(SwingUtilities.getWindowAncestor(owner), project, null).setVisible(true);
        }
    }

    /**
     * Opens the dialog of a saved SQL script task with its settings (no connecting); Save updates it.
     */
    static void openTask(@NotNull Component owner, @NotNull DBTTask task) {
        SQLScriptExecuteSettings settings;
        try {
            settings = DTSavedTasks.loadScriptTask(task);
        } catch (DBException e) {
            log.debug("Can't read the task settings", e);
            ErrorDialog.show(owner, task.getName(), ErrorText.describe(e), e);
            return;
        }
        SqlScriptTaskDialog dialog = new SqlScriptTaskDialog(SwingUtilities.getWindowAncestor(owner), task.getProject(), task);
        dialog.nameField.setText(task.getName());
        settings.getDataSources().forEach(dialog.connections::addElement);
        settings.getScriptFiles().forEach(dialog.scripts::addElement);
        dialog.stopOnErrorCheck.setSelected(!settings.isIgnoreErrors());
        dialog.autoCommitCheck.setSelected(settings.isAutoCommit());
        dialog.dumpResultsCheck.setSelected(settings.isDumpQueryResultsToLog());
        dialog.setVisible(true);
    }

    /**
     * A list's scroll pane that keeps its rows when the form is short of height (the form shrinks its rows to their
     * minimum size then).
     */
    @NotNull
    private static JScrollPane createListScroll(@NotNull JList<?> list) {
        JScrollPane scroll = new JScrollPane(list);
        scroll.setMinimumSize(scroll.getPreferredSize());
        return scroll;
    }

    @NotNull
    private static JPanel createButtonColumn(@NotNull JButton... buttons) {
        JPanel column = new JPanel(new GridLayout(0, 1, 0, 4));
        for (JButton button : buttons) {
            column.add(button);
        }
        JPanel top = new JPanel(new BorderLayout());
        top.add(column, BorderLayout.NORTH);
        return top;
    }

    private void chooseScripts() {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle(DatabaseTasksMessages.script_choose_title);
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(DatabaseTasksMessages.script_files_filter + " (*.sql)", "sql")); //$NON-NLS-1$ //$NON-NLS-2$
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        for (File file : chooser.getSelectedFiles()) {
            lastDirectory = file.getAbsoluteFile().getParentFile();
            String path = file.getAbsolutePath();
            if (!scripts.contains(path)) {
                scripts.addElement(path);
            }
        }
    }

    private static <T> void removeSelected(@NotNull JList<T> list, @NotNull DefaultListModel<T> model) {
        int[] selected = list.getSelectedIndices();
        for (int i = selected.length - 1; i >= 0; i--) {
            model.remove(selected[i]);
        }
    }

    /**
     * Moves the selected script up or down in the run order.
     */
    private void moveSelected(int direction) {
        int index = scriptList.getSelectedIndex();
        int target = index + direction;
        if (index < 0 || target < 0 || target >= scripts.size()) {
            return;
        }
        List<String> items = new ArrayList<>(Collections.list(scripts.elements()));
        Collections.swap(items, index, target);
        scripts.clear();
        items.forEach(scripts::addElement);
        scriptList.setSelectedIndex(target);
    }

    @Nullable
    private String validateInput() {
        if (nameField.getText().isBlank()) {
            return DatabaseTasksMessages.script_error_name;
        }
        if (connections.isEmpty()) {
            return DatabaseTasksMessages.script_error_connections;
        }
        if (scripts.isEmpty()) {
            return DatabaseTasksMessages.script_error_scripts;
        }
        for (int i = 0; i < scripts.size(); i++) {
            String script = scripts.get(i);
            // A project-relative script of an SWT task is checked when the task runs
            if (isMissingFile(script)) {
                return NLS.bind(DatabaseTasksMessages.script_error_missing, script);
            }
        }
        return null;
    }

    private static boolean isMissingFile(@NotNull String script) {
        try {
            Path path = Path.of(script);
            return path.isAbsolute() && !Files.isRegularFile(path);
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private void save() {
        String problem = validateInput();
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        SQLScriptExecuteSettings settings = new SQLScriptExecuteSettings();
        settings.setDataSources(new ArrayList<>(Collections.list(connections.elements())));
        settings.setScriptFiles(new ArrayList<>(Collections.list(scripts.elements())));
        settings.setIgnoreErrors(!stopOnErrorCheck.isSelected());
        settings.setAutoCommit(autoCommitCheck.isSelected());
        settings.setDumpQueryResultsToLog(dumpResultsCheck.isSelected());
        try {
            DTSavedTasks.saveScriptTask(project, settings, nameField.getText().strip(), task);
        } catch (DBException e) {
            log.debug("Can't save the task", e);
            ErrorDialog.show(this, getTitle(), ErrorText.describe(e), e);
            return;
        }
        dispose();
    }
}
