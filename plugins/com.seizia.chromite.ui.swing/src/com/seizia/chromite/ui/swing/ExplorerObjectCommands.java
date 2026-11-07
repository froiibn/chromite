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

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommand;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectMaker;
import com.seizia.chromite.model.edit.DBEObjectManager;
import com.seizia.chromite.model.edit.DBEObjectRenamer;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.edit.AbstractCommandContext;
import com.seizia.chromite.model.navigator.DBNContainer;
import com.seizia.chromite.model.navigator.DBNDatabaseItem;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.sql.registry.SQLGeneratorDescriptor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.NavigatorMessages;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Object Explorer commands on database objects: Delete (Delete key), Rename (F2), Copy Name (Ctrl+C), Copy Qualified Name
 * (Ctrl+Shift+C) and the Generate SQL menu. Delete, the copy commands and Generate SQL work on the whole selection.
 *
 * 설계: 탐색기({@code ObjectExplorerPanel})에는 메뉴 항목과 키만 두고 판단과 실행은 이 클래스가 한다(다른 기능과 겹치는 파일을 적게 고치기 위해,
 * {@link SecurityCommands}와 같은 나눔). SWT 탐색기의 동작을 옮겼고 코드는 옮기지 않았다.
 * <ul>
 *     <li>삭제·이름 바꾸기는 SWT {@code NavigatorObjectsDeleter}·{@code NavigatorHandlerObjectRename}처럼 모델의 객체 관리자
 *     ({@link DBEObjectMaker#deleteObject}, {@link DBEObjectRenamer#renameObject})로 명령을 만들고 명령 묶음
 *     ({@link DBECommandContext#saveChanges})으로 실행한다. SWT처럼 객체마다 연결의 기본 실행 문맥
 *     ({@code DBUtils.getDefaultContext(object, false)})으로 원자적 명령 묶음({@code SimpleCommandContext(context, true)}과 같은
 *     {@link AbstractCommandContext})을 만든다. 원자적 묶음은 저장이 끝난 뒤에야 모델과 탐색기를 바꾸므로(이름, 캐시, 노드 제거),
 *     미리 보기에서 취소하면 아무것도 바뀌지 않는다. 저장 뒤 모델이 객체 이벤트를 보내 탐색기 노드가 빠지거나 이름이 바뀐다.</li>
 *     <li>실행 전에는 언제나 스크립트를 보여 준다({@link ScriptPreviewDialog#confirmChange}: 실행, 쿼리 창으로 열기, 취소; 취소가 기본).
 *     SWT는 미리 보기가 선택(View Script)이었지만 DROP은 되돌릴 수 없으므로 SSMS "Script" 단계처럼 늘 보인다. 그래서 연결 유형의
 *     "데이터 변경 확인"을 따로 볼 필요가 없다. 스크립트는 저장과 같은 명령의 실행 동작({@link DBECommand#getPersistActions})으로 만든다.</li>
 *     <li>삭제 확인은 객체 목록(이름·형식)과, 관리자가 지원할 때만 {@link DeleteOption} 확인란(연쇄 삭제, 기존 연결 닫기)을 보인다
 *     ({@link DBEObjectMaker#getMakerOptions}의 기능 비트, SWT와 같다). 서로 다른 연결의 객체는 SWT처럼 함께 지우지 않는다.</li>
 *     <li>제한: 읽기 전용 연결({@link DBPDataSourceContainer#isConnectionReadOnly})과 메타데이터 변경 제한·읽기 전용 메타데이터
 *     ({@link DBUtils#isReadOnly})이면 실행하지 않고 알린다(테이블 디자이너의 저장과 같은 권한).</li>
 *     <li>트랜잭션(사용자 규칙 "트랜잭션 안전"): 실행 직전에 {@link ExitTransactionCheck#confirmObjectChange}로 그 연결의 열린 트랜잭션을 묻는다.
 *     모델의 저장 경로는 명령마다 커밋하므로 같은 세션의 열린 트랜잭션이 함께 커밋되고, 다른 세션의 트랜잭션은 DDL이 기다릴 잠금을 잡고 있을 수 있다.
 *     DDL을 바로 커밋하는 DB(JDBC {@code DatabaseMetaData#dataDefinitionCausesTransactionCommit}, MySQL·Oracle 등)는 미리 보기 머리글에 그렇게 적는다.</li>
 *     <li>이름 복사: 이름은 객체 이름({@link DBSObject#getName}, SWT Copy와 같음), 정규화된 이름은 SQL에 넣을 수 있게 방언의 따옴표를 붙인
 *     {@link DBUtils#getObjectFullName}({@link DBPEvaluationContext#DML})이다. 여러 개면 줄마다 하나다. Ctrl+C와 Edit &gt; Copy는 트리의
 *     전송 처리기({@link #createTransferHandler})를 지나므로 Swing 기본(노드 글자)이 아닌 이름이 복사된다.</li>
 *     <li>Generate SQL: 생성기 확장점에서 선택에 맞는 생성기를 고르고({@link ObjectScripts#getGenerators}) 결과를 새 쿼리 창에 연다(실행하지 않음).
 *     SWT는 미리 보기 대화상자였지만 Swing의 Script as(New Query Editor Window)와 같은 흐름으로 했다. 복사는 쿼리 창에서 한다.</li>
 * </ul>
 * 모델을 읽거나 DB에 접속하는 일은 진행 대화상자({@link ProgressDialog})의 작업 스레드에서 한다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-explorer-object-actions.md)
 */
final class ExplorerObjectCommands {

    private static final Log log = Log.getLog(ExplorerObjectCommands.class);

    /**
     * Rows of the object list in the delete confirmation before it scrolls.
     */
    private static final int DELETE_LIST_ROWS = 10;

    /**
     * Options of the delete confirmation, offered when the object manager supports them (SWT
     * {@code NavigatorObjectsDeleter.Option}).
     */
    private enum DeleteOption {
        CASCADE(DBEObjectMaker.FEATURE_DELETE_CASCADE, DBEObjectManager.OPTION_DELETE_CASCADE,
            NavigatorMessages.navigator_delete_cascade, NavigatorMessages.navigator_delete_cascade_tip),
        CLOSE_EXISTING_CONNECTIONS(DBEObjectMaker.FEATURE_CLOSE_EXISTING_CONNECTIONS, DBEObjectManager.OPTION_CLOSE_EXISTING_CONNECTIONS,
            NavigatorMessages.navigator_delete_close_connections, NavigatorMessages.navigator_delete_close_connections_tip);

        private final long feature;
        private final String optionKey;
        private final String label;
        private final String tip;

        DeleteOption(long feature, @NotNull String optionKey, @NotNull String label, @NotNull String tip) {
            this.feature = feature;
            this.optionKey = optionKey;
            this.label = label;
            this.tip = tip;
        }

        boolean isSupported(@NotNull DBEObjectMaker<?, ?> maker, @Nullable DBPDataSource dataSource) {
            return dataSource != null && (maker.getMakerOptions(dataSource) & feature) != 0;
        }
    }

    /**
     * One object's pending change: its own atomic command context and the options its persist actions read.
     */
    private record Change(@NotNull DBSObject object, @NotNull DBECommandContext context, @NotNull Map<String, Object> options) {
    }

    /**
     * Changes of one command with their script, built in a job.
     *
     * @param ddlCommits the database commits DDL statements at once
     */
    private record PreparedChange(@NotNull List<Change> changes, @NotNull String script, boolean ddlCommits) {
    }

    @FunctionalInterface
    private interface ChangeBuilder {
        /**
         * Adds the commands; job thread.
         */
        void addCommands(@NotNull DBRProgressMonitor monitor, @NotNull List<Change> changes) throws DBException;
    }

    private final Component owner;
    private final Supplier<List<DBNNode>> selection;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final Action copyNameAction;
    private final Action copyQualifiedNameAction;
    private final JMenu generateSqlMenu = new JMenu(NavigatorMessages.navigator_generate_sql);

    /**
     * @param selection the selected navigator nodes (several with multi-select)
     */
    ExplorerObjectCommands(
        @NotNull Component owner,
        @NotNull Supplier<List<DBNNode>> selection,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        this.owner = owner;
        this.selection = selection;
        this.queryWindows = queryWindows;
        copyNameAction = new AbstractAction(NavigatorMessages.navigator_copy_name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                copyNames(false);
            }
        };
        copyNameAction.putValue(Action.SHORT_DESCRIPTION, NavigatorMessages.navigator_copy_name_tip);
        copyNameAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke("ctrl C")); //$NON-NLS-1$
        copyQualifiedNameAction = new AbstractAction(NavigatorMessages.navigator_copy_qualified_name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                copyNames(true);
            }
        };
        copyQualifiedNameAction.putValue(Action.SHORT_DESCRIPTION, NavigatorMessages.navigator_copy_qualified_name_tip);
        copyQualifiedNameAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke("ctrl shift C")); //$NON-NLS-1$
        generateSqlMenu.setToolTipText(NavigatorMessages.navigator_generate_sql_tip);
    }

    @NotNull
    Action getCopyNameAction() {
        return copyNameAction;
    }

    @NotNull
    Action getCopyQualifiedNameAction() {
        return copyQualifiedNameAction;
    }

    @NotNull
    JMenu getGenerateSqlMenu() {
        return generateSqlMenu;
    }

    /**
     * The tree's transfer handler: Ctrl+C (the tree's own copy key) and Edit &gt; Copy copy the plain names of the
     * selected objects, as Copy Name does.
     */
    @NotNull
    TransferHandler createTransferHandler() {
        return new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Nullable
            @Override
            protected Transferable createTransferable(JComponent c) {
                String names = getNames(selection.get(), false);
                return names == null ? null : new StringSelection(names);
            }
        };
    }

    /**
     * Enables the commands for the selection and rebuilds the Generate SQL menu. EDT, model data in memory only.
     */
    void update(@NotNull List<DBNNode> nodes) {
        copyNameAction.setEnabled(getNames(nodes, false) != null);
        copyQualifiedNameAction.setEnabled(getObjects(nodes) != null);
        generateSqlMenu.removeAll();
        List<DBSObject> objects = getObjects(nodes);
        DBPDataSourceContainer container = objects == null ? null : getContainer(objects);
        if (objects != null && container != null) {
            int lastGroup = 0;
            for (SQLGeneratorDescriptor generator : ObjectScripts.getGenerators(objects)) {
                // Groups as the SWT menu: table statements, then routines and DDL (order 1000 and up)
                int group = generator.getOrder() / 1000;
                if (group > lastGroup && generateSqlMenu.getItemCount() > 0) {
                    generateSqlMenu.addSeparator();
                }
                lastGroup = group;
                JMenuItem item = new JMenuItem(generator.getLabel());
                item.setToolTipText(generator.getDescription());
                item.addActionListener(e -> generateSql(generator, objects, container));
                generateSqlMenu.add(item);
            }
        }
        generateSqlMenu.setEnabled(generateSqlMenu.getItemCount() > 0);
    }

    //////////////////////////////////////////////////////////
    // Copy names

    private void copyNames(boolean qualified) {
        String names = getNames(selection.get(), qualified);
        if (names != null) {
            DBWorkbench.getPlatformUI().copyTextToClipboard(names, false);
        }
    }

    /**
     * Names of the nodes, one per line; null if there is none. Qualified names exist for database objects only (null if
     * another node is selected); other nodes (connections, folders) give their text in the tree.
     */
    @Nullable
    private static String getNames(@NotNull List<DBNNode> nodes, boolean qualified) {
        List<String> names = new ArrayList<>();
        for (DBNNode node : nodes) {
            DBSObject object = getObject(node);
            if (qualified) {
                if (object == null) {
                    return null;
                }
                names.add(DBUtils.getObjectFullName(object, DBPEvaluationContext.DML));
            } else {
                String name = object == null ? null : object.getName();
                // An unnamed return value parameter shows its tree text
                names.add(CommonUtils.isEmpty(name) ? ObjectExplorerPanel.getNodeText(node) : name);
            }
        }
        return names.isEmpty() ? null : String.join(System.lineSeparator(), names);
    }

    //////////////////////////////////////////////////////////
    // Generate SQL

    private void generateSql(
        @NotNull SQLGeneratorDescriptor generator,
        @NotNull List<DBSObject> objects,
        @NotNull DBPDataSourceContainer container
    ) {
        String script;
        try {
            script = ProgressDialog.runTask(NavigatorMessages.navigator_generate_sql + " - " + generator.getLabel(), //$NON-NLS-1$
                monitor -> ObjectScripts.generate(monitor, generator, objects));
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(NavigatorMessages.navigator_generate_sql, NavigatorMessages.navigator_generate_sql_error, e);
            return;
        }
        queryWindows.openScript(container, script, 0, false);
    }

    //////////////////////////////////////////////////////////
    // Rename

    /**
     * True if the node is a saved database object that its object manager can rename ({@link DBEObjectRenamer}).
     */
    static boolean canRename(@Nullable DBNNode node) {
        DBSObject object = getEditableObject(node);
        DBEObjectRenamer<DBSObject> renamer = object == null ? null : getRenamer(object);
        return renamer != null && renamer.canRenameObject(object);
    }

    /**
     * Asks the new name, shows the script and renames the object (SWT {@code NavigatorHandlerObjectRename}).
     */
    void rename(@NotNull DBNNode node) {
        DBSObject object = getEditableObject(node);
        DBEObjectRenamer<DBSObject> renamer = object == null ? null : getRenamer(object);
        if (renamer == null || !checkModifiable(List.of(object))) {
            return;
        }
        String oldName = object.getName();
        String title = NLS.bind(NavigatorMessages.navigator_rename_title, node.getNodeTypeLabel());
        Object input = JOptionPane.showInputDialog(SwingUtilities.getWindowAncestor(owner),
            NLS.bind(NavigatorMessages.navigator_rename_prompt, oldName), title, JOptionPane.PLAIN_MESSAGE, null, null, oldName);
        String newName = input == null ? null : input.toString().trim();
        if (CommonUtils.isEmpty(newName) || newName.equals(oldName)) {
            return;
        }
        runChange(title, object.getDataSource().getContainer(), NavigatorMessages.navigator_rename_error, (monitor, changes) -> {
            DBECommandContext context = createCommandContext(object);
            // Options of the command (the SWT handler passes its UI source); the persist actions get none, as in SWT
            Map<String, Object> commandOptions = new HashMap<>();
            commandOptions.put(DBEObjectManager.OPTION_UI_SOURCE, this);
            changes.add(new Change(object, context, new HashMap<>()));
            renamer.renameObject(context, object, commandOptions, newName);
        });
    }

    //////////////////////////////////////////////////////////
    // Delete

    /**
     * True if every node is a saved database object of one connection that its object manager can delete.
     */
    static boolean canDelete(@NotNull List<DBNNode> nodes) {
        List<DBSObject> objects = new ArrayList<>();
        for (DBNNode node : nodes) {
            DBSObject object = getEditableObject(node);
            DBEObjectMaker<DBSObject, ?> maker = object == null ? null : getMaker(object);
            if (maker == null || !maker.canDeleteObject(object)) {
                return false;
            }
            objects.add(object);
        }
        return !objects.isEmpty() && getContainer(objects) != null;
    }

    /**
     * Confirms (object list, options), shows the script and deletes the selected objects (SWT
     * {@code NavigatorHandlerObjectDelete}).
     */
    void delete(@NotNull List<DBNNode> nodes) {
        List<DBNNode> targets = new ArrayList<>();
        List<DBSObject> objects = new ArrayList<>();
        for (DBNNode node : nodes) {
            DBSObject object = getEditableObject(node);
            if (object != null && getMaker(object) != null) {
                targets.add(node);
                objects.add(object);
            }
        }
        if (objects.isEmpty()) {
            return;
        }
        DBPDataSourceContainer container = getContainer(objects);
        if (container == null) {
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(owner), NavigatorMessages.navigator_delete_different_connections,
                NavigatorMessages.navigator_delete_title, JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!checkModifiable(objects)) {
            return;
        }
        Set<DeleteOption> enabledOptions = confirmDelete(targets, objects, container);
        if (enabledOptions == null) {
            return;
        }
        runChange(NavigatorMessages.navigator_delete_title, container, NavigatorMessages.navigator_delete_error, (monitor, changes) -> {
            for (DBSObject object : objects) {
                DBEObjectMaker<DBSObject, ?> maker = getMaker(object);
                if (maker == null) {
                    continue;
                }
                // The persist actions read the options (DROP ... CASCADE); only those this object's manager supports
                Map<String, Object> options = new HashMap<>();
                for (DeleteOption option : enabledOptions) {
                    if (option.isSupported(maker, object.getDataSource())) {
                        options.put(option.optionKey, Boolean.TRUE);
                    }
                }
                DBECommandContext context = createCommandContext(object);
                changes.add(new Change(object, context, options));
                maker.deleteObject(context, object, options);
            }
        });
    }

    /**
     * The delete confirmation: the question, the objects (several), the options the object managers support.
     *
     * @return the options to use, or null if canceled
     */
    @Nullable
    private Set<DeleteOption> confirmDelete(@NotNull List<DBNNode> nodes, @NotNull List<DBSObject> objects, @NotNull DBPDataSourceContainer container) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        String question = nodes.size() == 1
            ? NLS.bind(NavigatorMessages.navigator_delete_question_one,
                new Object[]{nodes.get(0).getNodeTypeLabel(), DBUtils.getObjectFullName(objects.get(0), DBPEvaluationContext.UI), container.getName()})
            : NLS.bind(NavigatorMessages.navigator_delete_question_many, nodes.size(), container.getName());
        panel.add(new JLabel(question), BorderLayout.NORTH);
        if (nodes.size() > 1) {
            panel.add(createObjectList(nodes, objects), BorderLayout.CENTER);
        }
        Map<DeleteOption, JCheckBox> optionBoxes = new HashMap<>();
        for (DeleteOption option : DeleteOption.values()) {
            for (DBSObject object : objects) {
                DBEObjectMaker<DBSObject, ?> maker = getMaker(object);
                if (maker != null && option.isSupported(maker, object.getDataSource())) {
                    JCheckBox box = new JCheckBox(option.label);
                    box.setToolTipText(option.tip);
                    optionBoxes.put(option, box);
                    break;
                }
            }
        }
        if (!optionBoxes.isEmpty()) {
            JPanel optionsPanel = new JPanel(new GridLayout(0, 1));
            for (DeleteOption option : DeleteOption.values()) {
                JCheckBox box = optionBoxes.get(option);
                if (box != null) {
                    optionsPanel.add(box);
                }
            }
            panel.add(optionsPanel, BorderLayout.SOUTH);
        }
        Object[] buttons = {NavigatorMessages.navigator_delete_button, CoreMessages.common_cancel};
        // Cancel is the default button, as SWT: Enter must not start a DROP
        int answer = JOptionPane.showOptionDialog(SwingUtilities.getWindowAncestor(owner), panel, NavigatorMessages.navigator_delete_title,
            JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, buttons, buttons[1]);
        if (answer != 0) {
            return null;
        }
        Set<DeleteOption> enabled = EnumSet.noneOf(DeleteOption.class);
        optionBoxes.forEach((option, box) -> {
            if (box.isSelected()) {
                enabled.add(option);
            }
        });
        return enabled;
    }

    @NotNull
    private static JComponent createObjectList(@NotNull List<DBNNode> nodes, @NotNull List<DBSObject> objects) {
        DefaultTableModel model = new DefaultTableModel(
            new Object[]{NavigatorMessages.navigator_delete_column_name, NavigatorMessages.navigator_delete_column_type}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (int i = 0; i < nodes.size(); i++) {
            model.addRow(new Object[]{DBUtils.getObjectFullName(objects.get(i), DBPEvaluationContext.UI), nodes.get(i).getNodeTypeLabel()});
        }
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        int rows = Math.min(DELETE_LIST_ROWS, nodes.size());
        table.setPreferredScrollableViewportSize(new Dimension(440, rows * table.getRowHeight()));
        // All rows fit: no vertical scroll bar. The option pane may lay the list out a pixel shorter than its preferred
        // height at a fractional display scale (150%: 47 of 48), which showed a needless scroll bar.
        return new JScrollPane(table, nodes.size() > DELETE_LIST_ROWS ? ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            : ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    }

    //////////////////////////////////////////////////////////
    // Script and execution

    /**
     * Builds the commands and their script in a job, shows the script and executes it if the user chooses so (after
     * the open transaction question). Canceled or failed changes are discarded; nothing is left in the model.
     *
     * @param errorMessage the error of a failed object, {@code {0}} = the object
     */
    private void runChange(
        @NotNull String title,
        @NotNull DBPDataSourceContainer container,
        @NotNull String errorMessage,
        @NotNull ChangeBuilder builder
    ) {
        PreparedChange prepared;
        try {
            prepared = ProgressDialog.runTask(NavigatorMessages.navigator_change_progress, monitor -> prepare(monitor, builder));
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, null, e);
            return;
        }
        String header = NLS.bind(prepared.ddlCommits()
            ? NavigatorMessages.navigator_change_preview_header_ddl_commit
            : NavigatorMessages.navigator_change_preview_header, container.getName());
        ScriptPreviewDialog.Result result = ScriptPreviewDialog.confirmChange(owner, title, header, prepared.script());
        if (result != ScriptPreviewDialog.Result.EXECUTE || !ExitTransactionCheck.confirmObjectChange(owner, container)) {
            discard(prepared.changes());
            if (result == ScriptPreviewDialog.Result.OPEN_IN_EDITOR) {
                queryWindows.openScript(container, prepared.script(), 0, false);
            }
            return;
        }
        DBSObject[] current = new DBSObject[1];
        try {
            ProgressDialog.runTask(NavigatorMessages.navigator_change_execute_progress, monitor -> {
                for (Change change : prepared.changes()) {
                    current[0] = change.object();
                    DBPDataSource dataSource = change.context().getExecutionContext().getDataSource();
                    // Reconnects and retries once when the connection was lost, as the table designer's save
                    DBExecUtils.tryExecuteRecover(monitor, dataSource, param -> change.context().saveChanges(monitor, change.options()));
                }
                return null;
            });
        } catch (DBException e) {
            // Objects changed before the failing one stay changed (each command commits); the rest are discarded
            discard(prepared.changes());
            if (!(e instanceof DBInterruptedException)) {
                String objectName = current[0] == null ? "" : DBUtils.getObjectFullName(current[0], DBPEvaluationContext.UI); //$NON-NLS-1$
                DBWorkbench.getPlatformUI().showError(title, NLS.bind(errorMessage, objectName), e);
            }
        }
    }

    /**
     * Job thread: adds the commands and renders their persist actions, as the save will execute them.
     */
    @NotNull
    private static PreparedChange prepare(@NotNull DBRProgressMonitor monitor, @NotNull ChangeBuilder builder) throws DBException {
        List<Change> changes = new ArrayList<>();
        try {
            builder.addCommands(monitor, changes);
            StringBuilder script = new StringBuilder();
            boolean ddlCommits = false;
            for (Change change : changes) {
                DBCExecutionContext executionContext = change.context().getExecutionContext();
                DBPDataSource dataSource = executionContext.getDataSource();
                for (DBECommand<?> command : change.context().getFinalCommands()) {
                    DBEPersistAction[] actions = command.getPersistActions(monitor, executionContext, change.options());
                    String text = SQLUtils.generateScript(dataSource, actions, false);
                    if (text.isEmpty()) {
                        // Only comments (e.g. a manual step the driver describes)
                        text = SQLUtils.generateComments(dataSource, actions, false);
                    }
                    script.append(text);
                }
                ddlCommits |= isDdlCommitting(monitor, executionContext);
            }
            return new PreparedChange(changes, script.toString(), ddlCommits);
        } catch (DBException | RuntimeException e) {
            discard(changes);
            throw e;
        }
    }

    /**
     * True when the database commits a DDL statement at once, also committing the session's transaction (JDBC
     * {@code DatabaseMetaData#dataDefinitionCausesTransactionCommit}: MySQL, Oracle). The model's
     * {@code DBCTransactionManager#getDDLTransactionBehavior} answers when DDL becomes visible to other sessions, which
     * is not this question.
     */
    private static boolean isDdlCommitting(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext) {
        try (DBCSession session = executionContext.openSession(monitor, DBCExecutionPurpose.META, "Read DDL transaction behavior")) { //$NON-NLS-1$
            return session instanceof JDBCSession jdbcSession && jdbcSession.getMetaData().dataDefinitionCausesTransactionCommit();
        } catch (SQLException | RuntimeException e) {
            log.debug("Cannot read the DDL transaction behavior", e); //$NON-NLS-1$
            return false;
        }
    }

    private static void discard(@NotNull List<Change> changes) {
        for (Change change : changes) {
            change.context().resetChanges(true);
        }
    }

    /**
     * Tells the user and returns false when the connection does not allow changing the objects' metadata.
     */
    private boolean checkModifiable(@NotNull List<DBSObject> objects) {
        for (DBSObject object : objects) {
            DBPDataSource dataSource = object.getDataSource();
            if (dataSource == null) {
                return false;
            }
            DBPDataSourceContainer container = dataSource.getContainer();
            if (container.isConnectionReadOnly() || DBUtils.isReadOnly(object)) {
                JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(owner),
                    SwingPlatformUI.createMessageComponent(NLS.bind(NavigatorMessages.navigator_change_restricted, container.getName())),
                    NavigatorMessages.navigator_delete_title, JOptionPane.WARNING_MESSAGE);
                return false;
            }
        }
        return true;
    }

    /**
     * An atomic command context on the object's default execution context, as SWT's navigator commands create
     * ({@code NavigatorHandlerObjectBase.getCommandTarget}): the model is changed only after the save.
     */
    @NotNull
    private static DBECommandContext createCommandContext(@NotNull DBSObject object) throws DBException {
        DBCExecutionContext executionContext = DBUtils.getDefaultContext(object, false);
        if (executionContext == null) {
            throw new DBException(NLS.bind(ToolsMessages.designer_error_not_connected, object.getName()));
        }
        return new AbstractCommandContext(executionContext, true) {
        };
    }

    //////////////////////////////////////////////////////////
    // Selection

    /**
     * The database object of a node in a folder (tables, views, routines, schemas, ...), not connections or folders.
     */
    @Nullable
    private static DBSObject getObject(@Nullable DBNNode node) {
        return node instanceof DBNDatabaseItem item ? item.getObject() : null;
    }

    /**
     * The object of a node that object managers can change: a saved object whose parent node is a folder or another
     * container (as SWT checks).
     */
    @Nullable
    private static DBSObject getEditableObject(@Nullable DBNNode node) {
        DBSObject object = getObject(node);
        return object != null && object.isPersisted() && node.getParentNode() instanceof DBNContainer ? object : null;
    }

    /**
     * The objects of the nodes, or null if a node is not a database object.
     */
    @Nullable
    private static List<DBSObject> getObjects(@NotNull List<DBNNode> nodes) {
        List<DBSObject> objects = new ArrayList<>();
        for (DBNNode node : nodes) {
            DBSObject object = getObject(node);
            if (object == null) {
                return null;
            }
            objects.add(object);
        }
        return objects.isEmpty() ? null : objects;
    }

    /**
     * The connection of all objects, or null if they belong to several.
     */
    @Nullable
    private static DBPDataSourceContainer getContainer(@NotNull List<DBSObject> objects) {
        DBPDataSourceContainer container = null;
        for (DBSObject object : objects) {
            DBPDataSource dataSource = object.getDataSource();
            if (dataSource == null || container != null && container != dataSource.getContainer()) {
                return null;
            }
            container = dataSource.getContainer();
        }
        return container;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static DBEObjectMaker<DBSObject, ?> getMaker(@NotNull DBSObject object) {
        return DBWorkbench.getPlatform().getEditorsRegistry().getObjectManager(object.getClass(), DBEObjectMaker.class);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static DBEObjectRenamer<DBSObject> getRenamer(@NotNull DBSObject object) {
        return DBWorkbench.getPlatform().getEditorsRegistry().getObjectManager(object.getClass(), DBEObjectRenamer.class);
    }
}
