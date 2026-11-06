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
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPDataSourceFolder;
import org.jkiss.dbeaver.model.DBPEventListener;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPConnectionType;
import org.jkiss.dbeaver.model.navigator.DBNDataSource;
import org.jkiss.dbeaver.model.navigator.DBNEvent;
import org.jkiss.dbeaver.model.navigator.DBNLocalFolder;
import org.jkiss.dbeaver.model.navigator.DBNProjectDatabases;
import org.jkiss.dbeaver.model.navigator.INavigatorListener;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Registered Servers 도구 창(SSMS "등록된 서버"): 서버 그룹(중첩)과 등록된 서버의 트리. Object Explorer와 따로 있다.
 *
 * 설계:
 * <ul>
 *     <li>저장 형식은 DBeaver 모델 그대로다. 서버 그룹 = 연결 폴더({@link DBPDataSourceFolder}), 서버 = 활성 프로젝트 레지스트리의 연결.
 *     기존 연결과 폴더가 그대로 보이고, 바꾼 내용은 레지스트리의 보통 저장(flushConfig)으로 남는다({@link RegisteredServerCommands}).</li>
 *     <li>트리는 레지스트리에서 바로 만든다(메모리에 있는 목록이라 EDT에서 읽는다). 레지스트리 이벤트({@link DBPEventListener}: 추가·삭제·변경·연결 상태)와
 *     탐색기 모델 이벤트({@link INavigatorListener}: 폴더 변경 뒤의 연결 목록 STRUCT_REFRESH)를 받으면 다음 EDT 차례에 한 번만 다시 만들고,
 *     펼친 그룹과 선택은 객체로 기억해 되살린다(이름을 바꾸거나 옮긴 그룹도 펼친 채로 남는다).</li>
 *     <li>정렬: 그룹 다음 서버, 각각 이름의 자연 정렬("01. DEV", "02. QAS", "03. PRD").</li>
 *     <li>아이콘: 그룹은 아이콘 팩의 폴더(펼치면 열린 폴더), 서버는 Object Explorer 연결 노드와 같은
 *     {@link NodeIcons#getDataSourceIcon}(드라이버의 DB별 아이콘 + 연결 상태 점)이다. 두 창이 같은 서버를 같은 아이콘으로 보인다.</li>
 *     <li>연결 종류 색(dev/test/prod): 모델이 주는 {@link DBPConnectionType#getColorLight()}/{@link DBPConnectionType#getColorDark()}를
 *     서버 행 바탕에 둥글게 칠한다(DBeaver 탐색기의 연결 종류 바탕색과 같은 값). 흰색(기본 dev)은 칠하지 않는다.</li>
 *     <li>서버 메뉴: New Query(필요하면 연결하고 그 연결의 SQL 편집기를 연다. SSMS처럼 Object Explorer에는 넣지 않는다),
 *     Object Explorer(더블클릭·Enter도 같음: Object Explorer에 넣고 골라 펼친다 = 연결), Connect / Disconnect, Move To,
 *     Properties…(연결 대화상자), Rename(F2), Delete(Delete, 확인). 연결 명령은 Object Explorer와 같은 {@link ConnectionCommands}다.</li>
 *     <li>그룹 메뉴: New Server Registration…(그 그룹을 고른 연결 대화상자), New Server Group…, Expand All / Collapse All(그 그룹 아래),
 *     Move To, Rename(F2), Delete(Delete, 확인. 하위 그룹은 지우고 서버는 상위로 옮긴다는 것을 알린다).</li>
 *     <li>끌어 놓기: 서버와 그룹을 그룹 위(또는 그룹 안의 서버 위, 빈 곳 = 최상위)로 옮긴다. 그룹을 자기 자신이나 하위 그룹으로는 옮길 수 없다.</li>
 *     <li>펼친 그룹은 이 번들의 설정 저장소에 그룹 경로로 저장해 다시 시작해도 남는다. 저장한 적이 없으면(처음) 모든 그룹을 펼친다.</li>
 *     <li>모양은 Object Explorer와 같다: 머리말 아래 아이콘 도구 막대, 이름 뒤 보조 글자(연결 호스트), 마우스를 올린 행의 둥근 바탕.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Registered Servers: 최초 작성 (docs/chromite/swing-registered-servers.md)
 */
final class RegisteredServersPanel extends JPanel {

    private static final String PREF_EXPANDED = "registeredServers.expanded"; //$NON-NLS-1$
    private static final String EXPANDED_SEPARATOR = "\n"; //$NON-NLS-1$
    /**
     * Default of {@link #PREF_EXPANDED}: never saved (expand every group). An empty list is a real value: the store
     * drops values equal to their default, so the default must not be "".
     */
    private static final String EXPANDED_NEVER_SAVED = "*"; //$NON-NLS-1$
    private static final String NO_COLOR = "255,255,255"; //$NON-NLS-1$

    /**
     * The main window side of the server commands.
     */
    interface Host {
        /**
         * Opens a query window for the server ("New Query").
         */
        void openQuery(@NotNull DBPDataSourceContainer dataSource);

        /**
         * Adds the server to Object Explorer, selects and expands (connects) it.
         */
        void showInObjectExplorer(@NotNull DBPDataSourceContainer dataSource);
    }

    private final Host host;
    @Nullable
    private final DBPDataSourceRegistry registry;
    private final DefaultTreeModel treeModel;
    private final ServersTree tree;
    private final JPopupMenu popupMenu = new JPopupMenu();
    private final Action newServerAction;
    private final Action newGroupAction;
    private final Action collapseAllAction;
    private final DBPEventListener registryListener = event -> {
        // Registry events come from any thread
        if (event.getObject() instanceof DBPDataSourceContainer) {
            SwingUtilities.invokeLater(this::scheduleRebuild);
        }
    };
    private final INavigatorListener navigatorListener = this::navigatorNodeChanged;
    /**
     * Expanded groups by object, kept across rebuilds (a renamed or moved group stays expanded).
     */
    private final Set<DBPDataSourceFolder> expandedGroups = new HashSet<>();
    /**
     * Group paths read from the preferences, applied on the first build; null when nothing was saved (expand all).
     */
    @Nullable
    private Set<String> savedExpandedPaths;
    private boolean firstBuild = true;
    /**
     * A group or server to select after the next rebuild (created here).
     */
    @Nullable
    private Object pendingSelection;
    private boolean rebuildScheduled;
    private boolean rebuilding;
    private boolean disposed;

    RegisteredServersPanel(@NotNull Host host) {
        super(new BorderLayout());
        this.host = host;
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        registry = project == null ? null : project.getDataSourceRegistry();

        treeModel = new DefaultTreeModel(new DefaultMutableTreeNode(project != null ? project.getName() : "No active project"));
        tree = new ServersTree(treeModel);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new ServersCellRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.putClientProperty(FlatClientProperties.TREE_WIDE_SELECTION, true);
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                if (!rebuilding && getUserObject(event.getPath()) instanceof DBPDataSourceFolder folder) {
                    expandedGroups.add(folder);
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                if (!rebuilding && getUserObject(event.getPath()) instanceof DBPDataSourceFolder folder) {
                    expandedGroups.remove(folder);
                }
            }
        });

        newServerAction = createAction("New Server Registration…", () -> newServer(getSelectedGroup()));
        newServerAction.putValue(Action.SHORT_DESCRIPTION, "Register a server in the selected group");
        newGroupAction = createAction("New Server Group…", () -> newGroup(getSelectedGroup()));
        newGroupAction.putValue(Action.SHORT_DESCRIPTION, "Create a server group in the selected group");
        collapseAllAction = createAction("Collapse All", () -> setSubtreeExpanded(null, false));
        newServerAction.setEnabled(registry != null);
        newGroupAction.setEnabled(registry != null);

        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "chromite.rename", this::renameSelected); //$NON-NLS-1$
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "chromite.delete", this::deleteSelected); //$NON-NLS-1$
        // Enter on a server: Object Explorer (as double-click); on a group it keeps expanding/collapsing
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chromite.open", () -> { //$NON-NLS-1$
            if (getSelectedObject() instanceof DBPDataSourceContainer container) {
                host.showInObjectExplorer(container);
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

        popupMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                fillPopupMenu();
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
                // The context menu acts on the row under the mouse, as in SSMS; on empty space it offers the top level
                if (SwingUtilities.isRightMouseButton(e)) {
                    // The menu's keys (F2, Delete, Enter) then act on this tree
                    tree.requestFocusInWindow();
                    TreePath path = tree.getClosestPathForLocation(e.getX(), e.getY());
                    Rectangle bounds = path == null ? null : tree.getPathBounds(path);
                    if (bounds != null && e.getY() >= bounds.y && e.getY() < bounds.y + bounds.height) {
                        tree.setSelectionPath(path);
                    } else {
                        tree.clearSelection();
                    }
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                // SSMS: double-clicking a registered server opens it in Object Explorer
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null && getUserObject(path) instanceof DBPDataSourceContainer container) {
                        host.showInObjectExplorer(container);
                    }
                }
            }
        });
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON);
        tree.setTransferHandler(new ServerTransferHandler());

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        add(createToolBar(), BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        if (registry != null) {
            DBPPreferenceStore preferences = ChromiteSwingActivator.getInstance().getPreferences();
            preferences.setDefault(PREF_EXPANDED, EXPANDED_NEVER_SAVED);
            String saved = preferences.getString(PREF_EXPANDED);
            if (!EXPANDED_NEVER_SAVED.equals(saved)) {
                savedExpandedPaths = new HashSet<>();
                if (!CommonUtils.isEmpty(saved)) {
                    savedExpandedPaths.addAll(List.of(saved.split(EXPANDED_SEPARATOR)));
                }
            }
            registry.addDataSourceListener(registryListener);
            DBWorkbench.getPlatform().getNavigatorModel().addListener(navigatorListener);
            rebuild();
        }
    }

    /**
     * Stops listening to the model and saves the expanded groups (the main window is closing).
     */
    void dispose() {
        if (registry == null || disposed) {
            return;
        }
        disposed = true;
        registry.removeDataSourceListener(registryListener);
        DBWorkbench.getPlatform().getNavigatorModel().removeListener(navigatorListener);
        List<String> paths = new ArrayList<>();
        for (DBPDataSourceFolder folder : expandedGroups) {
            if (registry.getAllFolders().contains(folder)) {
                paths.add(folder.getFolderPath());
            }
        }
        DBPPreferenceStore preferences = ChromiteSwingActivator.getInstance().getPreferences();
        preferences.setValue(PREF_EXPANDED, String.join(EXPANDED_SEPARATOR, paths));
        PrefUtils.savePreferenceStore(preferences);
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
     * Tool bar below the header, as in Object Explorer: New Server Registration, New Server Group | Collapse All.
     */
    @NotNull
    private JComponent createToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(3), UIScale.scale(8), UIScale.scale(3), UIScale.scale(8)));
        toolBar.add(UiIcons.createActionButton(newServerAction, "new-connection")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(newGroupAction, "new-group")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createActionButton(collapseAllAction, "collapse-all")); //$NON-NLS-1$
        return toolBar;
    }

    //////////////////////////////////////////////////////////
    // Context menu

    /**
     * Fills the context menu for the selection: a server, a group, or nothing (the top level).
     */
    private void fillPopupMenu() {
        popupMenu.removeAll();
        Object selected = getSelectedObject();
        if (selected instanceof DBPDataSourceContainer container) {
            boolean connected = container.isConnected();
            popupMenu.add(menuItem("New Query", null, () -> {
                ConnectionCommands.connect(container);
                host.openQuery(container);
            }));
            popupMenu.add(menuItem("Object Explorer", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), () -> host.showInObjectExplorer(container)));
            popupMenu.addSeparator();
            popupMenu.add(menuItem("Connect", null, () -> ConnectionCommands.connect(container))).setEnabled(!connected);
            popupMenu.add(menuItem("Disconnect", null, () -> ConnectionCommands.disconnect(container))).setEnabled(connected);
            popupMenu.addSeparator();
            popupMenu.add(createMoveToMenu(container));
            popupMenu.addSeparator();
            popupMenu.add(menuItem("Properties…", null, () -> ConnectionCommands.edit(this, container)));
            popupMenu.add(menuItem("Rename", KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), this::renameSelected));
            popupMenu.add(menuItem("Delete", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), this::deleteSelected));
        } else if (selected instanceof DBPDataSourceFolder folder) {
            popupMenu.add(menuItem("New Server Registration…", null, () -> newServer(folder)));
            popupMenu.add(menuItem("New Server Group…", null, () -> newGroup(folder)));
            popupMenu.addSeparator();
            popupMenu.add(menuItem("Expand All", null, () -> setSubtreeExpanded(folder, true)));
            popupMenu.add(menuItem("Collapse All", null, () -> setSubtreeExpanded(folder, false)));
            popupMenu.addSeparator();
            popupMenu.add(createMoveToMenu(folder));
            popupMenu.addSeparator();
            popupMenu.add(menuItem("Rename", KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), this::renameSelected));
            popupMenu.add(menuItem("Delete", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), this::deleteSelected));
        } else {
            popupMenu.add(new JMenuItem(newServerAction));
            popupMenu.add(new JMenuItem(newGroupAction));
            popupMenu.addSeparator();
            popupMenu.add(menuItem("Expand All", null, () -> setSubtreeExpanded(null, true)));
            popupMenu.add(new JMenuItem(collapseAllAction));
        }
    }

    @NotNull
    private static JMenuItem menuItem(@NotNull String text, @Nullable KeyStroke accelerator, @NotNull Runnable task) {
        JMenuItem item = new JMenuItem(text);
        // Shown only: the keys are bound to the tree (WHEN_FOCUSED), not to this transient menu
        item.setAccelerator(accelerator);
        item.addActionListener(e -> task.run());
        return item;
    }

    /**
     * "Move To": the top level and every group, indented by depth; targets that are not possible are disabled.
     */
    @NotNull
    private JMenu createMoveToMenu(@NotNull Object item) {
        JMenu menu = new JMenu("Move To");
        if (registry == null) {
            menu.setEnabled(false);
            return menu;
        }
        menu.add(createMoveToItem(item, null, 0));
        addMoveToItems(menu, item, null, 1);
        return menu;
    }

    private void addMoveToItems(@NotNull JMenu menu, @NotNull Object item, @Nullable DBPDataSourceFolder parent, int depth) {
        for (DBPDataSourceFolder group : RegisteredServerCommands.getGroups(registry, parent)) {
            menu.add(createMoveToItem(item, group, depth));
            addMoveToItems(menu, item, group, depth + 1);
        }
    }

    @NotNull
    private JMenuItem createMoveToItem(@NotNull Object item, @Nullable DBPDataSourceFolder target, int depth) {
        JMenuItem menuItem = new JMenuItem(target == null ? "(Top Level)" : target.getName(), target == null ? null : UiIcons.get("folder")); //$NON-NLS-1$
        menuItem.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(16 * depth), 0, 0));
        menuItem.setEnabled(canMove(item, target));
        menuItem.addActionListener(e -> move(item, target));
        return menuItem;
    }

    //////////////////////////////////////////////////////////
    // Commands

    private void newServer(@Nullable DBPDataSourceFolder folder) {
        DBPDataSourceContainer created = ConnectionCommands.newConnection(this, folder);
        if (created != null) {
            // SSMS: a new registration stays out of Object Explorer
            selectLater(created);
        }
    }

    private void newGroup(@Nullable DBPDataSourceFolder parent) {
        if (registry == null) {
            return;
        }
        DBPDataSourceFolder created = RegisteredServerCommands.newGroup(this, registry, parent);
        if (created != null) {
            selectLater(created);
        }
    }

    private void renameSelected() {
        Object selected = getSelectedObject();
        if (selected instanceof DBPDataSourceContainer container) {
            DBNDataSource node = RegisteredServerCommands.getServerNode(container);
            if (node != null) {
                ConnectionCommands.rename(this, node);
            }
        } else if (selected instanceof DBPDataSourceFolder folder) {
            RegisteredServerCommands.renameGroup(this, folder);
        }
    }

    private void deleteSelected() {
        Object selected = getSelectedObject();
        if (selected instanceof DBPDataSourceContainer container) {
            ConnectionCommands.delete(this, container);
        } else if (selected instanceof DBPDataSourceFolder folder) {
            RegisteredServerCommands.deleteGroup(this, folder);
        }
    }

    private static boolean canMove(@NotNull Object item, @Nullable DBPDataSourceFolder target) {
        if (item instanceof DBPDataSourceContainer container) {
            return container.getFolder() != target;
        }
        return item instanceof DBPDataSourceFolder folder && RegisteredServerCommands.canMoveGroup(folder, target);
    }

    private void move(@NotNull Object item, @Nullable DBPDataSourceFolder target) {
        if (!canMove(item, target)) {
            return;
        }
        if (item instanceof DBPDataSourceContainer container) {
            RegisteredServerCommands.moveServer(container, target);
        } else if (item instanceof DBPDataSourceFolder folder) {
            RegisteredServerCommands.moveGroup(this, folder, target);
        }
        if (target != null) {
            // Show the moved item in its new group
            expandedGroups.add(target);
        }
        selectLater(item);
    }

    /**
     * Expands or collapses a group and all its subgroups (null: every group).
     */
    private void setSubtreeExpanded(@Nullable DBPDataSourceFolder folder, boolean expand) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        DefaultMutableTreeNode start = folder == null ? root : findNode(folder);
        if (start == null) {
            return;
        }
        List<DefaultMutableTreeNode> groups = new ArrayList<>();
        Enumeration<TreeNode> nodes = start.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            if (nodes.nextElement() instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof DBPDataSourceFolder) {
                groups.add(node);
            }
        }
        if (expand) {
            // Parents first, so every level becomes visible
            Collections.reverse(groups);
        }
        for (DefaultMutableTreeNode node : groups) {
            TreePath path = new TreePath(node.getPath());
            if (expand) {
                tree.expandPath(path);
            } else {
                tree.collapsePath(path);
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Tree contents

    private void navigatorNodeChanged(@NotNull DBNEvent event) {
        // Folder changes refresh the connection list node (DBNModel.updateConfigAndRefreshDatabases)
        if ((event.getNode() instanceof DBNProjectDatabases databases && databases.getDataSourceRegistry() == registry)
            || (event.getNode() instanceof DBNLocalFolder folder && folder.getDataSourceRegistry() == registry)) {
            scheduleRebuild();
        }
    }

    private void selectLater(@NotNull Object item) {
        pendingSelection = item;
        scheduleRebuild();
    }

    /**
     * Rebuilds the tree once on the next EDT turn, however many events arrive now.
     */
    private void scheduleRebuild() {
        if (rebuildScheduled || disposed) {
            return;
        }
        rebuildScheduled = true;
        SwingUtilities.invokeLater(() -> {
            rebuildScheduled = false;
            if (!disposed) {
                rebuild();
            }
        });
    }

    private void rebuild() {
        if (registry == null) {
            return;
        }
        Object selected = pendingSelection != null ? pendingSelection : getSelectedObject();
        pendingSelection = null;
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(registry.getProject().getName());
        addChildren(root, null);

        rebuilding = true;
        try {
            treeModel.setRoot(root);
            if (firstBuild) {
                // The saved groups, or all of them if nothing was saved yet
                firstBuild = false;
                for (DBPDataSourceFolder folder : registry.getAllFolders()) {
                    if (savedExpandedPaths == null || savedExpandedPaths.contains(folder.getFolderPath())) {
                        expandedGroups.add(folder);
                    }
                }
                savedExpandedPaths = null;
            }
            expandedGroups.retainAll(registry.getAllFolders());
            if (selected instanceof DBPDataSourceContainer container && container.getFolder() != null) {
                expandParents(container.getFolder());
            } else if (selected instanceof DBPDataSourceFolder folder && folder.getParent() != null) {
                expandParents(folder.getParent());
            }
            // Parents before children: a path expands only when its parent is expanded
            Enumeration<TreeNode> nodes = root.preorderEnumeration();
            while (nodes.hasMoreElements()) {
                if (nodes.nextElement() instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof DBPDataSourceFolder folder
                    && expandedGroups.contains(folder) && isPathExpanded(node.getParent())) {
                    tree.expandPath(new TreePath(node.getPath()));
                }
            }
        } finally {
            rebuilding = false;
        }
        DefaultMutableTreeNode selectedNode = selected == null ? null : findNode(selected);
        if (selectedNode != null) {
            TreePath path = new TreePath(selectedNode.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }
    }

    private boolean isPathExpanded(@Nullable TreeNode node) {
        return node == null || node == treeModel.getRoot() || tree.isExpanded(new TreePath(((DefaultMutableTreeNode) node).getPath()));
    }

    private void expandParents(@NotNull DBPDataSourceFolder folder) {
        for (DBPDataSourceFolder group = folder; group != null; group = group.getParent()) {
            expandedGroups.add(group);
        }
    }

    private void addChildren(@NotNull DefaultMutableTreeNode node, @Nullable DBPDataSourceFolder folder) {
        for (DBPDataSourceFolder group : RegisteredServerCommands.getGroups(registry, folder)) {
            DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(group);
            addChildren(groupNode, group);
            node.add(groupNode);
        }
        for (DBPDataSourceContainer container : RegisteredServerCommands.getServers(registry, folder)) {
            node.add(new DefaultMutableTreeNode(container, false));
        }
    }

    @Nullable
    private DefaultMutableTreeNode findNode(@NotNull Object userObject) {
        Enumeration<TreeNode> nodes = ((DefaultMutableTreeNode) treeModel.getRoot()).breadthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            if (nodes.nextElement() instanceof DefaultMutableTreeNode node && node.getUserObject() == userObject) {
                return node;
            }
        }
        return null;
    }

    @Nullable
    private static Object getUserObject(@Nullable TreePath path) {
        return path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
    }

    /**
     * The selected server or group; null when nothing is selected.
     */
    @Nullable
    private Object getSelectedObject() {
        Object object = getUserObject(tree.getSelectionPath());
        return object instanceof DBPDataSourceContainer || object instanceof DBPDataSourceFolder ? object : null;
    }

    /**
     * The group for new servers and groups: the selected group, the group of the selected server, or the top level.
     */
    @Nullable
    private DBPDataSourceFolder getSelectedGroup() {
        Object selected = getSelectedObject();
        if (selected instanceof DBPDataSourceFolder folder) {
            return folder;
        }
        return selected instanceof DBPDataSourceContainer container ? container.getFolder() : null;
    }

    /**
     * The connection type color of a server for the current theme, or null for none (white, the plain dev type).
     */
    @Nullable
    private static Color getConnectionTypeColor(@NotNull DBPDataSourceContainer container) {
        DBPConnectionType type = container.getConnectionConfiguration().getConnectionType();
        String value = FlatLaf.isLafDark() ? type.getColorDark() : type.getColorLight();
        if (CommonUtils.isEmpty(value) || NO_COLOR.equals(value.replace(" ", ""))) { //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        String[] parts = value.split(","); //$NON-NLS-1$
        if (parts.length != 3) {
            return null;
        }
        try {
            return new Color(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    //////////////////////////////////////////////////////////
    // Drag and drop

    /**
     * JVM-local flavor: the dragged model object itself, never serialized (drags stay in this tree).
     */
    private static final DataFlavor ITEM_FLAVOR = createItemFlavor();

    @NotNull
    private static DataFlavor createItemFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=java.lang.Object"); //$NON-NLS-1$
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Moves servers and groups by drag and drop: onto a group, onto a server (its group) or onto empty space (the top level).
     */
    private final class ServerTransferHandler extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Nullable
        @Override
        protected Transferable createTransferable(JComponent c) {
            Object selected = getSelectedObject();
            return selected == null ? null : new ItemTransferable(selected);
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || !support.isDataFlavorSupported(ITEM_FLAVOR)) {
                return false;
            }
            Object item = getItem(support);
            return item != null && canMove(item, getDropGroup(support));
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            Object item = getItem(support);
            DBPDataSourceFolder target = getDropGroup(support);
            // After the drag has ended: moving a group may show a message
            SwingUtilities.invokeLater(() -> move(item, target));
            return true;
        }

        @Nullable
        private Object getItem(@NotNull TransferSupport support) {
            try {
                return support.getTransferable().getTransferData(ITEM_FLAVOR);
            } catch (Exception e) {
                return null;
            }
        }

        @Nullable
        private DBPDataSourceFolder getDropGroup(@NotNull TransferSupport support) {
            JTree.DropLocation location = (JTree.DropLocation) support.getDropLocation();
            Object target = getUserObject(location.getPath());
            if (target instanceof DBPDataSourceFolder folder) {
                return folder;
            }
            return target instanceof DBPDataSourceContainer container ? container.getFolder() : null;
        }
    }

    private record ItemTransferable(@NotNull Object item) implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{ITEM_FLAVOR};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return ITEM_FLAVOR.equals(flavor);
        }

        @NotNull
        @Override
        public Object getTransferData(DataFlavor flavor) {
            return item;
        }
    }

    //////////////////////////////////////////////////////////
    // Look

    /**
     * Tree with the Object Explorer hover highlight (bg.hover) and the connection type color behind server rows, both
     * rounded like the selection and painted between the background and the rows.
     */
    private static final class ServersTree extends JTree {
        private int hoverRow = -1;

        ServersTree(@NotNull DefaultTreeModel model) {
            super(model);
            MouseAdapter hoverListener = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    setHoverRow(getRowAtY(e.getY()));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    setHoverRow(-1);
                }
            };
            addMouseListener(hoverListener);
            addMouseMotionListener(hoverListener);
        }

        private int getRowAtY(int y) {
            int row = getClosestRowForLocation(0, y);
            Rectangle bounds = row >= 0 ? getRowBounds(row) : null;
            return bounds != null && y >= bounds.y && y < bounds.y + bounds.height ? row : -1;
        }

        private void setHoverRow(int row) {
            if (row != hoverRow) {
                repaintRow(hoverRow);
                hoverRow = row;
                repaintRow(row);
            }
        }

        private void repaintRow(int row) {
            Rectangle bounds = row >= 0 ? getRowBounds(row) : null;
            if (bounds != null) {
                repaint(0, bounds.y, getWidth(), bounds.height);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (ui == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                if (isOpaque()) {
                    g2.setColor(getBackground());
                    g2.fillRect(0, 0, getWidth(), getHeight());
                }
                paintRowBackgrounds(g2);
                ui.paint(g2, this);
            } finally {
                g2.dispose();
            }
        }

        private void paintRowBackgrounds(@NotNull Graphics2D g) {
            Insets selectionInsets = UIManager.getInsets("Tree.selectionInsets"); //$NON-NLS-1$
            Insets insets = UIScale.scale(selectionInsets != null ? selectionInsets : new Insets(0, 0, 0, 0));
            float arc = UIScale.scale((float) UIManager.getInt("Tree.selectionArc")); //$NON-NLS-1$
            Rectangle clip = g.getClipBounds();
            Object[] oldHints = FlatUIUtils.setRenderingHints(g);
            try {
                int first = clip == null ? 0 : Math.max(0, getClosestRowForLocation(0, clip.y));
                int last = clip == null ? getRowCount() - 1 : getClosestRowForLocation(0, clip.y + clip.height);
                for (int row = first; row <= last && row < getRowCount(); row++) {
                    if (isRowSelected(row)) {
                        continue;
                    }
                    Color color = null;
                    if (getUserObject(getPathForRow(row)) instanceof DBPDataSourceContainer container) {
                        color = getConnectionTypeColor(container);
                    }
                    if (row == hoverRow) {
                        Color hover = UIManager.getColor("Chromite.Tree.hoverBackground"); //$NON-NLS-1$
                        color = hover != null ? hover : color;
                    }
                    Rectangle bounds = color == null ? null : getRowBounds(row);
                    if (bounds != null) {
                        g.setColor(color);
                        g.fill(new RoundRectangle2D.Float(insets.left, bounds.y + insets.top, getWidth() - insets.left - insets.right,
                            bounds.height - insets.top - insets.bottom, arc, arc));
                    }
                }
            } finally {
                FlatUIUtils.resetRenderingHints(g, oldHints);
            }
        }
    }

    /**
     * Group folder or server icon (the driver's database icon with the state dot), the name, and the server's host
     * after it in text.secondary (as in Object Explorer).
     */
    private static final class ServersCellRenderer extends DefaultTreeCellRenderer {
        // space.6: icon-text gap and gap before the secondary text
        private static final int GAP = 6;

        @Nullable
        private String secondaryText;

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
            Object userObject = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : value;
            super.getTreeCellRendererComponent(tree, getText(userObject), selected, expanded, leaf, row, hasFocus);
            setIconTextGap(UIScale.scale(GAP));
            secondaryText = null;
            if (userObject instanceof DBPDataSourceFolder folder) {
                setIcon(UiIcons.get(expanded ? "folder-open" : "folder")); //$NON-NLS-1$ //$NON-NLS-2$
                setToolTipText(CommonUtils.isEmpty(folder.getDescription()) ? folder.getFolderPath() : folder.getDescription());
            } else if (userObject instanceof DBPDataSourceContainer container) {
                setIcon(NodeIcons.getDataSourceIcon(container));
                DBPConnectionConfiguration configuration = container.getConnectionConfiguration();
                String host = configuration.getHostName();
                if (!CommonUtils.isEmpty(host)) {
                    secondaryText = CommonUtils.isEmpty(configuration.getHostPort()) ? host : host + ":" + configuration.getHostPort(); //$NON-NLS-1$
                }
                setToolTipText(container.getDriver().getName() + " · " + configuration.getConnectionType().getName() //$NON-NLS-1$
                    + (container.isConnected() ? " · connected" : "")); //$NON-NLS-1$
            } else {
                setIcon(null);
                setToolTipText(null);
            }
            return this;
        }

        @NotNull
        private static String getText(@Nullable Object userObject) {
            if (userObject instanceof DBPDataSourceFolder folder) {
                return folder.getName();
            }
            if (userObject instanceof DBPDataSourceContainer container) {
                return container.getName();
            }
            return String.valueOf(userObject);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            if (size != null && secondaryText != null) {
                size.width += UIScale.scale(GAP) + getFontMetrics(getFont()).stringWidth(secondaryText);
            }
            return size;
        }

        @Override
        public void paint(Graphics g) {
            super.paint(g);
            if (secondaryText == null) {
                return;
            }
            FontMetrics metrics = getFontMetrics(getFont());
            Icon icon = getIcon();
            Insets insets = getInsets();
            int x = insets.left + (icon != null ? icon.getIconWidth() + getIconTextGap() : 0)
                + metrics.stringWidth(CommonUtils.notEmpty(getText())) + UIScale.scale(GAP);
            int y = insets.top + (getHeight() - insets.top - insets.bottom - metrics.getHeight()) / 2 + metrics.getAscent();
            Color color = UIManager.getColor("Chromite.secondaryForeground"); //$NON-NLS-1$
            g.setColor(color != null ? color : getForeground());
            g.setFont(getFont());
            FlatUIUtils.drawString(this, g, secondaryText, x, y);
        }
    }
}
