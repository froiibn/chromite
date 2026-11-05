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
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.navigator.DBNDataSource;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNEvent;
import org.jkiss.dbeaver.model.navigator.DBNLocalFolder;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.navigator.DBNProject;
import org.jkiss.dbeaver.model.navigator.DBNProjectDatabases;
import org.jkiss.dbeaver.model.navigator.DBNUtils;
import org.jkiss.dbeaver.model.navigator.INavigatorListener;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRRunnableWithReturn;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBInterruptedException;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Object Explorer: 활성 프로젝트의 연결과 DB 객체를 보여 주는 트리.
 *
 * 설계: SWT 버전의 Database Navigator와 같은 탐색기 모델({@code DBWorkbench.getPlatform().getNavigatorModel()})을 그대로 쓴다.
 * 트리의 최상위는 활성 프로젝트의 연결 목록({@code DBNProject#getDatabases()}의 자식: 연결 폴더와 연결)이다(SSMS의 서버 목록에 해당).
 * <ul>
 *     <li>자식은 처음 펼칠 때 읽는다. 읽는 동안 "Loading…" 자식을 보여 주고, {@link DBNUtils#getNodeChildrenFiltered}를
 *     {@link AbstractJob}(백그라운드 Job, {@link DBRProgressMonitor} 사용)에서 호출한다. EDT에서는 모델을 읽지 않는다
 *     (연결 목록처럼 메모리에 있는 자식도 같은 경로를 써서 경로를 하나로 둔다).</li>
 *     <li>연결 노드를 펼치면 {@code DBNDatabaseNode#getChildren} → {@code DBUtils.initDataSource} →
 *     {@code DBServiceConnections}({@link SwingConnectionService})로 연결한다. SWT 탐색기와 같은 흐름이다.</li>
 *     <li>결과는 {@link SwingUtilities#invokeLater}로 EDT에서 트리 모델에 반영한다. 노드마다 읽기 번호를 두어
 *     새로 고침 등으로 더 새 읽기가 시작되었으면 이전 결과는 버린다.</li>
 *     <li>오류는 {@code DBWorkbench.getPlatformUI().showError}({@link SwingPlatformUI})로 보여 주고 노드를 접어 다시 펼치면 다시 읽게 한다.
 *     연결 취소({@link DBInterruptedException})는 연결 서비스가 이미 알렸으므로 다시 알리지 않는다.</li>
 *     <li>새로 고침(F5는 쿼리 실행용으로 남겨 두고 머리말 버튼과 오른쪽 클릭 메뉴로만 제공)은 {@code DBNNode#refreshNode} 뒤에
 *     자식을 다시 읽는다. 아직 펼친 적 없는 노드는 연결을 새로 열지 않도록 다시 그리기만 한다.</li>
 *     <li>더블클릭은 Swing 트리의 기본 동작(펼치기/접기)만 한다. 객체 편집기는 다음 단계에서 만든다.</li>
 *     <li>오른쪽 클릭 메뉴 "New Query"는 선택한 노드가 속한 연결({@link DBNDatabaseNode#getDataSourceContainer()})로
 *     SQL 편집기를 연다(SSMS처럼 연결 아래 어느 노드에서나). 연결은 편집기가 실행할 때 필요하면 연다.</li>
 *     <li>연결 명령({@link ConnectionCommands}): New Connection(빈 곳·폴더에서도), Connect / Disconnect / Reconnect(연결 아래 어느 노드에서나),
 *     Edit Connection(Enter), Rename(F2), Delete(Delete 키, 확인 후)는 연결 노드에서.</li>
 *     <li>객체 스크립트(SSMS): "Select Top 1000 Rows"(테이블·뷰, 새 편집기에서 바로 실행), "Script as > CREATE To / SELECT To >
 *     New Query Editor Window / Clipboard", "View DDL"(읽기 전용 탭). SQL은 {@link ObjectScripts}(모델의 SQL 생성기)가 만들고,
 *     DB 메타데이터를 읽으므로 진행 대화상자({@link ProgressDialog})의 작업 스레드에서 만든다.</li>
 *     <li>모델 이벤트: 탐색기 모델({@link INavigatorListener}, 모델이 EDT에서 부름)을 듣고 다른 곳에서 일어난 변경을 반영한다.
 *     연결 목록이 바뀌면(추가·삭제·이름 변경·폴더) 목록을 다시 읽되 이미 있는 트리 노드와 펼친 상태는 그대로 둔다.
 *     연결이 끊기면({@code UNLOAD}) 그 연결을 접고 다시 펼칠 때 읽게 하고, 연결 상태가 바뀌면 아이콘을 다시 그린다.
 *     모델이 지운 DB 객체 노드는 트리에서 뺀다. 이 패널이 보낸 이벤트(새로 고침의 원천이 이 패널)는 이미 처리했으므로 무시한다.
 *     다른 곳에서 새로 만든 DB 객체({@code ADD})는 아직 반영하지 않는다(새로 고침으로 보임).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 C단계: "New Query" 메뉴, 선택한 연결 조회 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 * - 2026-11-06 D단계: 연결 명령 메뉴와 단축키, 탐색기 모델 이벤트로 트리 갱신 (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 * - 2026-11-06 E단계: Select Top 1000 Rows, Script as(CREATE·SELECT), View DDL (docs/chromite/swing-frontend.md "구현 현황 — E단계")
 */
final class ObjectExplorerPanel extends JPanel {

    private static final Log log = Log.getLog(ObjectExplorerPanel.class);

    private static final String LOADING_TEXT = "Loading…"; //$NON-NLS-1$
    /**
     * Rows of "Select Top N Rows", as SSMS.
     */
    private static final int SELECT_TOP_ROWS = 1000;

    /**
     * The main window side: document tabs for queries and scripts.
     */
    interface QueryWindows {
        /**
         * Opens an empty query window for the connection ("New Query").
         */
        void openQuery(@NotNull DBPDataSourceContainer dataSource);

        /**
         * Opens a query window with a generated script.
         *
         * @param maxRows rows the window fetches at least (0: the connection's maximum)
         * @param execute run the script right away
         */
        void openScript(@NotNull DBPDataSourceContainer dataSource, @NotNull String script, long maxRows, boolean execute);

        /**
         * Shows an object definition in a read-only tab ("View DDL").
         */
        void showDdl(@NotNull String objectName, @NotNull String ddl);
    }

    private final DefaultTreeModel treeModel;
    private final JTree tree;
    private final ExplorerNode rootNode;
    @Nullable
    private final DBNProject projectNode;
    private final Action refreshAction;
    private final Action newQueryAction;
    private final Action newConnectionAction;
    private final Action connectAction;
    private final Action disconnectAction;
    private final Action reconnectAction;
    private final Action editConnectionAction;
    private final Action renameAction;
    private final Action deleteAction;
    private final Action selectTopAction;
    private final Action viewDdlAction;
    private final JMenu scriptAsMenu;
    private final JMenu createToMenu;
    private final JMenu selectToMenu;
    private final INavigatorListener navigatorListener = this::navigatorNodeChanged;
    /**
     * A connection to select once the connection list has been read again (a connection created in this window).
     */
    @Nullable
    private DBNNode pendingReveal;
    private boolean disposed;

    /**
     * @param selectionListener receives the selected navigator node (or null)
     * @param queryWindows      opens query windows ("New Query", scripts) and DDL tabs
     */
    ObjectExplorerPanel(@NotNull Consumer<DBNNode> selectionListener, @NotNull QueryWindows queryWindows) {
        super(new BorderLayout());

        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        projectNode = project == null ? null :
            DBWorkbench.getPlatform().getNavigatorModel().getRoot().getProjectNode(project);
        rootNode = new ExplorerNode(projectNode != null ? projectNode : "No active project");
        treeModel = new DefaultTreeModel(rootNode);

        tree = new JTree(treeModel);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new ExplorerCellRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.putClientProperty(FlatClientProperties.TREE_WIDE_SELECTION, true);
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                if (event.getPath().getLastPathComponent() instanceof ExplorerNode node && node.state == LoadState.NOT_LOADED) {
                    loadChildren(node, false);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
        tree.addTreeSelectionListener(e -> selectionListener.accept(getSelectedNavigatorNode()));

        refreshAction = new AbstractAction("Refresh") {
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshSelection();
            }
        };
        refreshAction.putValue(Action.SHORT_DESCRIPTION, "Refresh the selected node (or all connections)");
        newQueryAction = new AbstractAction("New Query") {
            @Override
            public void actionPerformed(ActionEvent e) {
                DBPDataSourceContainer dataSource = getSelectedDataSource();
                if (dataSource != null) {
                    queryWindows.openQuery(dataSource);
                }
            }
        };
        selectTopAction = createAction("Select Top " + SELECT_TOP_ROWS + " Rows", () -> {
            DBPDataSourceContainer dataSource = getSelectedDataSource();
            if (getSelectedObject() instanceof DBSEntity entity && dataSource != null) {
                String script = generate("Select Top " + SELECT_TOP_ROWS + " Rows",
                    monitor -> ObjectScripts.generateSelect(monitor, entity, SELECT_TOP_ROWS));
                if (script != null) {
                    queryWindows.openScript(dataSource, script, SELECT_TOP_ROWS, true);
                }
            }
        });
        viewDdlAction = createAction("View DDL", () -> {
            DBSObject object = getSelectedObject();
            if (object != null) {
                String ddl = generate("View DDL", monitor -> ObjectScripts.generateCreate(monitor, object));
                if (ddl != null) {
                    queryWindows.showDdl(DBUtils.getObjectFullName(object, DBPEvaluationContext.UI), ddl);
                }
            }
        });
        createToMenu = new JMenu("CREATE To");
        createToMenu.add(createAction("New Query Editor Window", () -> scriptSelected(queryWindows, false, false)));
        createToMenu.add(createAction("Clipboard", () -> scriptSelected(queryWindows, false, true)));
        selectToMenu = new JMenu("SELECT To");
        selectToMenu.add(createAction("New Query Editor Window", () -> scriptSelected(queryWindows, true, false)));
        selectToMenu.add(createAction("Clipboard", () -> scriptSelected(queryWindows, true, true)));
        scriptAsMenu = new JMenu("Script as");
        scriptAsMenu.add(createToMenu);
        scriptAsMenu.add(selectToMenu);
        newConnectionAction = createAction("New Connection…", () -> ConnectionCommands.newConnection(this,
            getSelectedNavigatorNode() instanceof DBNLocalFolder folderNode ? folderNode.getFolder() : null));
        newConnectionAction.putValue(Action.SHORT_DESCRIPTION, "Create a connection in the active project");
        connectAction = createAction("Connect", () -> runOnSelectedDataSource(ConnectionCommands::connect));
        disconnectAction = createAction("Disconnect", () -> runOnSelectedDataSource(ConnectionCommands::disconnect));
        reconnectAction = createAction("Reconnect", () -> runOnSelectedDataSource(ConnectionCommands::reconnect));
        editConnectionAction = createAction("Edit Connection…", () -> {
            if (getSelectedNavigatorNode() instanceof DBNDataSource node) {
                ConnectionCommands.edit(this, node.getDataSourceContainer());
            }
        });
        editConnectionAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        renameAction = createAction("Rename", () -> {
            if (getSelectedNavigatorNode() instanceof DBNDataSource node) {
                ConnectionCommands.rename(this, node);
            }
        });
        renameAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0));
        deleteAction = createAction("Delete", () -> {
            if (getSelectedNavigatorNode() instanceof DBNDataSource node) {
                ConnectionCommands.delete(this, node.getDataSourceContainer());
            }
        });
        deleteAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
        updateActions();
        tree.addTreeSelectionListener(e -> updateActions());

        // Keys of the connection commands (shown in the menu); Enter on other nodes keeps expanding/collapsing them
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chromite.editOrToggle", () -> { //$NON-NLS-1$
            if (getSelectedNavigatorNode() instanceof DBNDataSource) {
                editConnectionAction.actionPerformed(null);
            } else {
                TreePath path = tree.getSelectionPath();
                if (path != null) {
                    if (tree.isExpanded(path)) {
                        tree.collapsePath(path);
                    } else {
                        tree.expandPath(path);
                    }
                }
            }
        });
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "chromite.rename", () -> renameAction.actionPerformed(null)); //$NON-NLS-1$
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "chromite.delete", () -> deleteAction.actionPerformed(null)); //$NON-NLS-1$

        JPopupMenu popupMenu = new JPopupMenu();
        popupMenu.add(newConnectionAction);
        popupMenu.addSeparator();
        popupMenu.add(newQueryAction);
        popupMenu.add(selectTopAction);
        popupMenu.add(scriptAsMenu);
        popupMenu.add(viewDdlAction);
        popupMenu.addSeparator();
        popupMenu.add(connectAction);
        popupMenu.add(disconnectAction);
        popupMenu.add(reconnectAction);
        popupMenu.addSeparator();
        popupMenu.add(editConnectionAction);
        popupMenu.add(renameAction);
        popupMenu.add(deleteAction);
        popupMenu.addSeparator();
        popupMenu.add(refreshAction);
        popupMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                // The connection state may have changed since the selection did
                updateActions();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        tree.setComponentPopupMenu(popupMenu);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // The context menu acts on the row under the mouse, as in SSMS; on empty space it offers New Connection
                if (SwingUtilities.isRightMouseButton(e)) {
                    TreePath path = tree.getClosestPathForLocation(e.getX(), e.getY());
                    if (path != null && tree.getPathBounds(path) != null && tree.getPathBounds(path).contains(e.getPoint())) {
                        tree.setSelectionPath(path);
                    } else {
                        tree.clearSelection();
                    }
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        add(scrollPane, BorderLayout.CENTER);

        if (projectNode != null) {
            DBWorkbench.getPlatform().getNavigatorModel().addListener(navigatorListener);
            loadChildren(rootNode, false);
        }
    }

    /**
     * Stops listening to the navigator model (the main window is closing).
     */
    void dispose() {
        if (projectNode != null && !disposed) {
            disposed = true;
            DBWorkbench.getPlatform().getNavigatorModel().removeListener(navigatorListener);
        }
    }

    @NotNull
    Action getNewConnectionAction() {
        return newConnectionAction;
    }

    @NotNull
    private static Action createAction(@NotNull String name, @NotNull Runnable task) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
    }

    private void bindKey(@NotNull KeyStroke keyStroke, @NotNull String actionKey, @NotNull Runnable task) {
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(keyStroke, actionKey);
        tree.getActionMap().put(actionKey, createAction(actionKey, task));
    }

    /**
     * "Script as > CREATE To / SELECT To": a new query window with the script, or the clipboard.
     */
    private void scriptSelected(@NotNull QueryWindows queryWindows, boolean select, boolean toClipboard) {
        DBSObject object = getSelectedObject();
        DBPDataSourceContainer dataSource = getSelectedDataSource();
        if (object == null || dataSource == null) {
            return;
        }
        String script = select
            ? (object instanceof DBSEntity entity ? generate("Script as SELECT", monitor -> ObjectScripts.generateSelect(monitor, entity, 0)) : null)
            : generate("Script as CREATE", monitor -> ObjectScripts.generateCreate(monitor, object));
        if (script == null) {
            return;
        }
        if (toClipboard) {
            DBWorkbench.getPlatformUI().copyTextToClipboard(script, false);
        } else {
            queryWindows.openScript(dataSource, script, 0, false);
        }
    }

    /**
     * Generates SQL in a job, waiting in a progress dialog (it reads metadata). Errors are shown; null if it
     * failed or was canceled.
     */
    @Nullable
    private String generate(@NotNull String title, @NotNull DBRRunnableWithReturn<String> generator) {
        try {
            return ProgressDialog.runTask(title, generator);
        } catch (DBInterruptedException e) {
            return null;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, "Can't generate the script", e);
            return null;
        }
    }

    /**
     * The database object of the selected node; null for connections and folders.
     */
    @Nullable
    private DBSObject getSelectedObject() {
        return getSelectedNavigatorNode() instanceof DBNDatabaseNode databaseNode && !(databaseNode instanceof DBNDataSource)
            ? databaseNode.getObject() : null;
    }

    private void runOnSelectedDataSource(@NotNull Consumer<DBPDataSourceContainer> command) {
        DBPDataSourceContainer dataSource = getSelectedDataSource();
        if (dataSource != null) {
            command.accept(dataSource);
        }
    }

    private void updateActions() {
        DBPDataSourceContainer dataSource = getSelectedDataSource();
        boolean connectionNode = getSelectedNavigatorNode() instanceof DBNDataSource;
        boolean connected = dataSource != null && dataSource.isConnected();
        newConnectionAction.setEnabled(projectNode != null);
        newQueryAction.setEnabled(dataSource != null);
        DBSObject object = getSelectedObject();
        boolean canScript = ObjectScripts.canScriptCreate(object);
        boolean canSelect = ObjectScripts.canSelect(object);
        selectTopAction.setEnabled(canSelect);
        createToMenu.setEnabled(canScript);
        selectToMenu.setEnabled(canSelect);
        scriptAsMenu.setEnabled(canScript || canSelect);
        viewDdlAction.setEnabled(canScript);
        connectAction.setEnabled(dataSource != null && !connected);
        disconnectAction.setEnabled(connected);
        reconnectAction.setEnabled(connected);
        editConnectionAction.setEnabled(connectionNode);
        renameAction.setEnabled(connectionNode);
        deleteAction.setEnabled(connectionNode);
    }

    /**
     * Header button for the tool window.
     */
    @NotNull
    JComponent createRefreshButton() {
        JButton button = new JButton(refreshAction);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setFocusable(false);
        return button;
    }

    /**
     * Focus goes to the tree when the main window opens, so the keyboard works right away.
     */
    void focusTree() {
        tree.requestFocusInWindow();
    }

    /**
     * The connection of the selected node: the connection itself or any object under it.
     */
    @Nullable
    DBPDataSourceContainer getSelectedDataSource() {
        return getSelectedNavigatorNode() instanceof DBNDatabaseNode databaseNode ? databaseNode.getDataSourceContainer() : null;
    }

    @Nullable
    private DBNNode getSelectedNavigatorNode() {
        TreePath path = tree.getSelectionPath();
        if (path != null && path.getLastPathComponent() instanceof ExplorerNode node && node.getUserObject() instanceof DBNNode navigatorNode) {
            return navigatorNode;
        }
        return null;
    }

    private void refreshSelection() {
        TreePath path = tree.getSelectionPath();
        ExplorerNode node = rootNode;
        if (path != null && path.getLastPathComponent() instanceof ExplorerNode selected) {
            // A "Loading..." placeholder stands for its parent
            node = selected.getUserObject() instanceof DBNNode ? selected : (ExplorerNode) selected.getParent();
        }
        if (node == null || !(node.getUserObject() instanceof DBNNode)) {
            return;
        }
        if (node.state == LoadState.NOT_LOADED) {
            // Never expanded: reading children would connect the data source, so only repaint the label and icon
            treeModel.nodeChanged(node);
            return;
        }
        loadChildren(node, node != rootNode);
    }

    /**
     * Reads the children of a node in a background job.
     *
     * @param refresh refresh the navigator node first ({@code DBNNode#refreshNode})
     */
    private void loadChildren(@NotNull ExplorerNode node, boolean refresh) {
        DBNNode navigatorNode = (DBNNode) node.getUserObject();
        int serial = ++node.loadSerial;
        node.state = LoadState.LOADING;
        if (refresh || node.getChildCount() != 1 || node.getFirstChild() instanceof ExplorerNode child && child.getUserObject() instanceof DBNNode) {
            // Replace the old children by the "Loading..." placeholder; an expanded node stays expanded
            node.removeAllChildren();
            node.add(new ExplorerNode(LOADING_TEXT));
            treeModel.nodeStructureChanged(node);
        }

        new AbstractJob("Load " + navigatorNode.getNodeDisplayName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    DBNNode loadNode = navigatorNode;
                    if (refresh) {
                        DBNNode refreshed = navigatorNode.refreshNode(monitor, ObjectExplorerPanel.this);
                        if (refreshed == null) {
                            // The object no longer exists
                            SwingUtilities.invokeLater(() -> removeNode(node, serial));
                            return Status.OK_STATUS;
                        }
                        loadNode = refreshed;
                    }
                    DBNNode[] children = readChildren(monitor, loadNode);
                    DBNNode finalLoadNode = loadNode;
                    SwingUtilities.invokeLater(() -> showChildren(node, serial, finalLoadNode, children));
                } catch (Throwable e) {
                    SwingUtilities.invokeLater(() -> showLoadError(node, serial, e));
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    @Nullable
    private static DBNNode[] readChildren(@NotNull DBRProgressMonitor monitor, @NotNull DBNNode node) throws DBException {
        if (node instanceof DBNProject projectNode) {
            // Root: the connections of the project, without the project's other resources (scripts, bookmarks)
            return DBNUtils.getNodeChildrenFiltered(monitor, projectNode.getDatabases(), true);
        }
        return DBNUtils.getNodeChildrenFiltered(monitor, node, true);
    }

    private void showChildren(@NotNull ExplorerNode node, int serial, @NotNull DBNNode loadedNode, @Nullable DBNNode[] children) {
        if (node.loadSerial != serial) {
            return;
        }
        // A refresh may be delegated to an ancestor (DBNNode#refreshNode); its subtree is then the one that changed
        ExplorerNode target = node;
        while (target != null && target.getUserObject() != loadedNode) {
            target = (ExplorerNode) target.getParent();
        }
        if (target == null) {
            target = node;
        }
        if (target != node) {
            node.state = LoadState.NOT_LOADED;
            target.loadSerial++;
        }
        target.removeAllChildren();
        if (children != null) {
            for (DBNNode child : children) {
                target.add(new ExplorerNode(child));
            }
        }
        target.state = LoadState.LOADED;
        treeModel.nodeStructureChanged(target);
        // Connecting may change the node icon (connected state)
        treeModel.nodeChanged(target);
    }

    private void showLoadError(@NotNull ExplorerNode node, int serial, @NotNull Throwable error) {
        if (node.loadSerial != serial) {
            return;
        }
        node.removeAllChildren();
        node.add(new ExplorerNode(LOADING_TEXT));
        node.state = LoadState.NOT_LOADED;
        treeModel.nodeStructureChanged(node);
        if (node != rootNode) {
            tree.collapsePath(new TreePath(node.getPath()));
        }
        DBNNode navigatorNode = (DBNNode) node.getUserObject();
        if (error instanceof DBInterruptedException) {
            // Connection canceled or failed: the connection service has already reported it
            log.debug("Loading of '" + navigatorNode.getNodeDisplayName() + "' interrupted: " + error.getMessage());
            return;
        }
        DBWorkbench.getPlatformUI().showError(
            "Object Explorer",
            "Can't read the contents of '" + navigatorNode.getNodeDisplayName() + "'",
            error);
    }

    private void removeNode(@NotNull ExplorerNode node, int serial) {
        if (node.loadSerial == serial && node.getParent() != null) {
            treeModel.removeNodeFromParent(node);
        }
    }

    //////////////////////////////////////////////////////////
    // Navigator model events

    /**
     * Called by the navigator model on the EDT ({@code DBPPlatformUI.executeInMainThread}), in batches.
     */
    private void navigatorNodeChanged(@NotNull DBNEvent event) {
        if (projectNode == null || event.getSource() == this) {
            // Refreshes started here are already shown by loadChildren
            return;
        }
        DBNNode node = event.getNode();
        DBNProjectDatabases databases = projectNode.getDatabases();
        if (node == databases) {
            // Connection list rebuilt (STRUCT_REFRESH after a removal, folder changes)
            reloadConnectionList();
            return;
        }
        if (node instanceof DBNDataSource || node instanceof DBNLocalFolder) {
            if (!isInProject(node, databases)) {
                return;
            }
            switch (event.getAction()) {
                case ADD -> {
                    if (event.getNodeChange() == DBNEvent.NodeChange.SELECT) {
                        // Created by this application with "reveal" (DBPDataSourceRegistry.addDataSource)
                        pendingReveal = node;
                    }
                    reloadConnectionList();
                }
                case REMOVE -> reloadConnectionList();
                case UPDATE -> connectionUpdated(node, event.getNodeChange());
            }
            return;
        }
        ExplorerNode explorerNode = findExplorerNode(node);
        if (explorerNode == null) {
            return;
        }
        switch (event.getAction()) {
            case REMOVE -> {
                if (explorerNode.getParent() != null) {
                    treeModel.removeNodeFromParent(explorerNode);
                }
            }
            case UPDATE -> {
                if (event.getNodeChange() == DBNEvent.NodeChange.UNLOAD) {
                    resetNode(explorerNode);
                }
                treeModel.nodeChanged(explorerNode);
            }
            case ADD -> {
                // Objects created elsewhere: shown after a refresh of their parent
            }
        }
    }

    private static boolean isInProject(@NotNull DBNNode node, @NotNull DBNProjectDatabases databases) {
        if (node instanceof DBNDataSource dataSourceNode) {
            return dataSourceNode.getDataSourceContainer().getRegistry() == databases.getDataSourceRegistry();
        }
        return node instanceof DBNLocalFolder folderNode && folderNode.getDataSourceRegistry() == databases.getDataSourceRegistry();
    }

    private void connectionUpdated(@NotNull DBNNode node, @NotNull DBNEvent.NodeChange change) {
        ExplorerNode explorerNode = findExplorerNode(node);
        switch (change) {
            case UNLOAD -> {
                // Disconnected: the children were disposed; they are read again (connecting) when the node is expanded
                if (explorerNode != null) {
                    resetNode(explorerNode);
                }
            }
            case REFRESH -> {
                // Saved settings (rename, edit): the name and the position in the sorted list may have changed
                reloadConnectionList();
            }
            default -> {
                // BEFORE_LOAD, AFTER_LOAD, LOAD, STRUCT_REFRESH: connection state icon
            }
        }
        if (explorerNode != null) {
            treeModel.nodeChanged(explorerNode);
        }
        updateActions();
    }

    /**
     * Collapses a node and makes it read its children again the next time it is expanded. A node whose children
     * are being read is left alone: that read ends with the current state (e.g. a failed connect reports its error).
     */
    private void resetNode(@NotNull ExplorerNode node) {
        if (node.state == LoadState.LOADING) {
            return;
        }
        node.loadSerial++;
        node.state = LoadState.NOT_LOADED;
        node.removeAllChildren();
        if (node.getUserObject() instanceof DBNNode navigatorNode && navigatorNode.hasChildren(true)) {
            node.add(new ExplorerNode(LOADING_TEXT));
        }
        treeModel.nodeStructureChanged(node);
        tree.collapsePath(new TreePath(node.getPath()));
    }

    /**
     * Reads the connection list (and the loaded connection folders) again, keeping the tree nodes that are still
     * there with their children and expansion state.
     */
    private void reloadConnectionList() {
        if (rootNode.state != LoadState.NOT_LOADED) {
            reloadPreserving(rootNode);
        }
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            if (rootNode.getChildAt(i) instanceof ExplorerNode child && child.getUserObject() instanceof DBNLocalFolder
                && child.state == LoadState.LOADED) {
                reloadPreserving(child);
            }
        }
    }

    private void reloadPreserving(@NotNull ExplorerNode node) {
        DBNNode navigatorNode = (DBNNode) node.getUserObject();
        int serial = ++node.loadSerial;
        new AbstractJob("Reload " + navigatorNode.getNodeDisplayName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    DBNNode[] children = readChildren(monitor, navigatorNode);
                    SwingUtilities.invokeLater(() -> mergeChildren(node, serial, children));
                } catch (Throwable e) {
                    log.debug("Error reloading '" + navigatorNode.getNodeDisplayName() + "'", e);
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void mergeChildren(@NotNull ExplorerNode node, int serial, @Nullable DBNNode[] children) {
        if (node.loadSerial != serial || (node != rootNode && node.getParent() == null)) {
            return;
        }
        TreePath nodePath = new TreePath(node.getPath());
        List<TreePath> expanded = new ArrayList<>();
        Enumeration<TreePath> expandedPaths = tree.getExpandedDescendants(nodePath);
        if (expandedPaths != null) {
            expanded.addAll(Collections.list(expandedPaths));
        }
        TreePath selection = tree.getSelectionPath();

        Map<DBNNode, ExplorerNode> existing = new HashMap<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof ExplorerNode child && child.getUserObject() instanceof DBNNode childNode) {
                existing.put(childNode, child);
            }
        }
        node.removeAllChildren();
        if (children != null) {
            for (DBNNode child : children) {
                ExplorerNode explorerChild = existing.get(child);
                node.add(explorerChild != null ? explorerChild : new ExplorerNode(child));
            }
        }
        node.state = LoadState.LOADED;
        treeModel.nodeStructureChanged(node);

        for (TreePath path : expanded) {
            if (isAttached(path)) {
                tree.expandPath(path);
            }
        }
        if (selection != null && isAttached(selection)) {
            tree.setSelectionPath(selection);
        }
        if (pendingReveal != null) {
            for (int i = 0; i < node.getChildCount(); i++) {
                if (node.getChildAt(i) instanceof ExplorerNode child && child.getUserObject() == pendingReveal) {
                    pendingReveal = null;
                    TreePath path = new TreePath(child.getPath());
                    tree.setSelectionPath(path);
                    tree.scrollPathToVisible(path);
                    break;
                }
            }
        }
        updateActions();
    }

    /**
     * True when the path's node is still in the tree (it was not removed by a reload).
     */
    private boolean isAttached(@NotNull TreePath path) {
        return path.getLastPathComponent() instanceof ExplorerNode node && node.getRoot() == rootNode;
    }

    /**
     * The tree node of a navigator node, searched among the children read so far.
     */
    @Nullable
    private ExplorerNode findExplorerNode(@NotNull DBNNode navigatorNode) {
        Enumeration<TreeNode> nodes = rootNode.breadthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            if (nodes.nextElement() instanceof ExplorerNode node && node.getUserObject() == navigatorNode) {
                return node;
            }
        }
        return null;
    }

    private enum LoadState {
        NOT_LOADED,
        LOADING,
        LOADED
    }

    /**
     * Tree node holding a navigator node, or a text for placeholders ("Loading…", "No active project").
     */
    private static final class ExplorerNode extends DefaultMutableTreeNode {
        private LoadState state = LoadState.NOT_LOADED;
        private int loadSerial;

        ExplorerNode(@NotNull Object userObject) {
            super(userObject);
            if (userObject instanceof DBNNode navigatorNode && navigatorNode.hasChildren(true)) {
                // Placeholder child: makes the node expandable; replaced when the children are read
                add(new ExplorerNode(LOADING_TEXT));
            } else if (!(userObject instanceof DBNNode)) {
                state = LoadState.LOADED;
            }
        }

        /**
         * Display text; also used by the tree's type-to-select search.
         */
        @Override
        public String toString() {
            return getUserObject() instanceof DBNNode navigatorNode
                ? CommonUtils.notEmpty(navigatorNode.getNodeDisplayName())
                : String.valueOf(getUserObject());
        }
    }

    private static final class ExplorerCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(
            JTree tree,
            Object value,
            boolean selected,
            boolean expanded,
            boolean leaf,
            int row,
            boolean hasFocus
        ) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            if (value instanceof ExplorerNode node && node.getUserObject() instanceof DBNNode navigatorNode) {
                setIcon(NodeIcons.getIcon(navigatorNode.getNodeIcon()));
                setToolTipText(navigatorNode.getNodeDescription());
            } else {
                // Placeholder text
                setIcon(null);
                setToolTipText(null);
                if (!selected) {
                    setForeground(UIManager.getColor("Label.disabledForeground")); //$NON-NLS-1$
                }
            }
            return this;
        }
    }
}
