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
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.navigator.DBNProject;
import org.jkiss.dbeaver.model.navigator.DBNUtils;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBInterruptedException;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 C단계: "New Query" 메뉴, 선택한 연결 조회 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 */
final class ObjectExplorerPanel extends JPanel {

    private static final Log log = Log.getLog(ObjectExplorerPanel.class);

    private static final String LOADING_TEXT = "Loading…"; //$NON-NLS-1$

    private final DefaultTreeModel treeModel;
    private final JTree tree;
    private final ExplorerNode rootNode;
    private final Action refreshAction;
    private final Action newQueryAction;

    /**
     * @param selectionListener receives the selected navigator node (or null)
     * @param newQueryHandler   opens a query window for a connection ("New Query" menu)
     */
    ObjectExplorerPanel(@NotNull Consumer<DBNNode> selectionListener, @NotNull Consumer<DBPDataSourceContainer> newQueryHandler) {
        super(new BorderLayout());

        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        DBNProject projectNode = project == null ? null :
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
                    newQueryHandler.accept(dataSource);
                }
            }
        };
        newQueryAction.setEnabled(false);
        tree.addTreeSelectionListener(e -> newQueryAction.setEnabled(getSelectedDataSource() != null));

        JPopupMenu popupMenu = new JPopupMenu();
        popupMenu.add(newQueryAction);
        popupMenu.addSeparator();
        popupMenu.add(refreshAction);
        tree.setComponentPopupMenu(popupMenu);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // The context menu acts on the row under the mouse, as in SSMS
                if (SwingUtilities.isRightMouseButton(e)) {
                    TreePath path = tree.getClosestPathForLocation(e.getX(), e.getY());
                    if (path != null && tree.getPathBounds(path) != null && tree.getPathBounds(path).contains(e.getPoint())) {
                        tree.setSelectionPath(path);
                    }
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        add(scrollPane, BorderLayout.CENTER);

        if (projectNode != null) {
            loadChildren(rootNode, false);
        }
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
