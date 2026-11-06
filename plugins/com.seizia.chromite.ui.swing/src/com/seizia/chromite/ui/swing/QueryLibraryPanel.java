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
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Query Library 도구 창: 라이브러리 루트·폴더·쿼리 트리와 쿼리 명령(실행, 편집, 새로 만들기, 이름 바꾸기, 복제, 삭제, 끌어다 옮기기).
 *
 * 설계:
 * <ul>
 *     <li>트리는 디스크의 폴더 구조({@link QueryLibrary})를 그대로 보여 준다. 다시 읽기는 작업 스레드에서 모든 루트를 읽은 뒤 EDT에서 트리를
 *     다시 만들고, 펼친 폴더와 선택을 경로로 되살린다. 이 창에서 바꾼 것은 {@link QueryLibrary}의 알림으로 바로 다시 읽고, 다른 사람이 바꾼 공유 폴더는
 *     Refresh(도구 막대, 트리에 초점이 있을 때 F5)로 읽는다.</li>
 *     <li>검색란은 이름·설명에 글자가 들어 있는 쿼리와 그 상위 폴더만 남기고 모두 펼친다.</li>
 *     <li>명령: Enter·Run(기본 대상에서 실행), Run on…(연결 고르기), 더블클릭·Edit(쿼리 창에서 편집), New Query, New Folder, Duplicate,
 *     Rename(F2), Delete(Delete 키, 확인 후 휴지통), Open Folder(탐색기에서 열기), Refresh, Library Roots….
 *     읽기 전용 루트에서는 쓰기 명령을 끈다. 실행·편집은 {@link QueryLibraryEditor}가 쿼리 창으로 한다.</li>
 *     <li>끌어다 놓기: 쿼리·폴더를 다른 폴더로 옮긴다(파일 이동, ID는 그대로). 파일 탐색기에서 {@code .sql} 파일을 놓으면 그 폴더에 복사한다.</li>
 *     <li>모양은 Object Explorer와 같다: 머리말 아래 아이콘 도구 막대, 아이콘 팩 아이콘, 이름 뒤 보조 글자(읽기 전용, 고정 대상 연결), 마우스를 올린 행의 둥근 바탕.</li>
 *     <li>"Save to Query Library…"(Query 메뉴)는 활성 쿼리 창의 선택(없으면 전체)을 고른 폴더에 새 쿼리로 저장한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md)
 * - 2026-11-06 Query Library ③: Schedule…, History…, Scheduled Queries…, 알림 배지와 예약 보조 글자, 예약 실행기 시작·정지
 *   (docs/chromite/swing-query-library.md "예약 실행")
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 */
final class QueryLibraryPanel extends JPanel {

    private static final Log log = Log.getLog(QueryLibraryPanel.class);

    private static final DataFlavor PATH_FLAVOR = createPathFlavor();

    /**
     * The main window side of the Query Library.
     */
    interface Host {
        /**
         * Parent of the dialogs.
         */
        @NotNull
        Component getComponent();

        /**
         * The connection selected in Object Explorer, else the one of the active query window.
         */
        @Nullable
        DBPDataSourceContainer getCurrentConnection();

        @NotNull
        List<SqlEditorPanel> getQueryWindows();

        @Nullable
        SqlEditorPanel getActiveQueryWindow();

        void showQueryWindow(@NotNull SqlEditorPanel editor);

        /**
         * Opens a new query window tab.
         */
        @NotNull
        SqlEditorPanel openQueryWindow(@Nullable DBPDataSourceContainer container, @NotNull String fileName);
    }

    private final Host host;
    private final QueryLibrary library = QueryLibrary.getInstance();
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final LibraryTree tree = new LibraryTree(treeModel);
    private final JTextField searchField = new JTextField();
    private final Action runAction;
    private final Action runOnAction;
    private final Action editAction;
    private final Action newQueryAction;
    private final Action newFolderAction;
    private final Action duplicateAction;
    private final Action renameAction;
    private final Action deleteAction;
    private final Action openFolderAction;
    private final Action refreshAction;
    private final Action rootsAction;
    private final Action saveToLibraryAction;
    private final Action scheduleAction;
    private final Action historyAction;
    private final Action scheduledAction;
    private List<QueryLibrary.Folder> folders = List.of();
    private int loadSerial;
    @Nullable
    private Runnable showWindow;

    QueryLibraryPanel(@NotNull Host host) {
        super(new BorderLayout());
        this.host = host;

        runAction = createAction(ToolsMessages.library_action_run, ToolsMessages.library_action_run_tip, this::runSelected);
        runOnAction = createAction(ToolsMessages.library_action_run_on, ToolsMessages.library_action_run_on_tip, this::runSelectedOn);
        editAction = createAction(ToolsMessages.library_action_edit, ToolsMessages.library_action_edit_tip, this::editSelected);
        newQueryAction = createAction(ToolsMessages.library_action_new_query, ToolsMessages.library_action_new_query_tip, this::newQuery);
        newFolderAction = createAction(ToolsMessages.library_action_new_folder, ToolsMessages.library_action_new_folder_tip, this::newFolder);
        duplicateAction = createAction(ToolsMessages.library_duplicate, ToolsMessages.library_action_duplicate_tip, this::duplicateSelected);
        renameAction = createAction(ToolsMessages.library_action_rename, ToolsMessages.library_action_rename_tip, this::renameSelected);
        deleteAction = createAction(ToolsMessages.library_delete, ToolsMessages.library_action_delete_tip, this::deleteSelected);
        openFolderAction = createAction(ToolsMessages.library_open_folder, ToolsMessages.library_action_open_folder_tip, this::openFolder);
        refreshAction = createAction(ToolsMessages.library_action_refresh, ToolsMessages.library_action_refresh_tip, this::reload);
        rootsAction = createAction(ToolsMessages.library_action_roots, ToolsMessages.library_action_roots_tip, this::editRoots);
        saveToLibraryAction = createAction(ToolsMessages.library_action_save,
            ToolsMessages.library_action_save_tip, this::saveActiveWindow);
        scheduleAction = createAction(ToolsMessages.library_action_schedule, ToolsMessages.library_action_schedule_tip, this::scheduleSelected);
        historyAction = createAction(ToolsMessages.library_action_history, ToolsMessages.library_action_history_tip, this::showHistory);
        scheduledAction = createAction(ToolsMessages.library_action_scheduled, ToolsMessages.library_action_scheduled_tip,
            () -> QueryScheduledDialog.open(host.getComponent()));

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new LibraryCellRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.putClientProperty(FlatClientProperties.TREE_WIDE_SELECTION, true);
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON);
        tree.setTransferHandler(new LibraryTransferHandler());
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addTreeSelectionListener(e -> updateActions());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowMenu(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && getSelectedQuery() != null) {
                    editSelected();
                }
            }
        });
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chromite.run", () -> { //$NON-NLS-1$
            if (getSelectedQuery() != null) {
                runSelected();
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
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "chromite.rename", this::renameSelected); //$NON-NLS-1$
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "chromite.delete", this::deleteSelected); //$NON-NLS-1$
        // As in Object Explorer: F5 refreshes while the tree has the focus, it executes the query elsewhere
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "chromite.refresh", this::reload); //$NON-NLS-1$
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK), "chromite.duplicate", this::duplicateSelected); //$NON-NLS-1$

        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, ToolsMessages.library_search_placeholder);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                rebuildTree();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                rebuildTree();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        searchField.registerKeyboardAction(e -> tree.requestFocusInWindow(),
            KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), JComponent.WHEN_FOCUSED);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(createToolBar(), BorderLayout.NORTH);
        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.setOpaque(false);
        searchPanel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(8), UIScale.scale(4), UIScale.scale(8)));
        searchPanel.add(searchField, BorderLayout.CENTER);
        top.add(searchPanel, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        add(scrollPane, BorderLayout.CENTER);

        library.addListener(this::reload);
        // Alerts and schedule state show in the tree
        QueryScheduler.getInstance().addListener(tree::repaint);
        QueryScheduler.getInstance().start();
        updateActions();
        reload();
    }

    /**
     * The main window closes: scheduled runs stop.
     */
    void dispose() {
        QueryScheduler.getInstance().stop();
    }

    /**
     * Query &gt; Save to Query Library… of the main window.
     */
    @NotNull
    Action getSaveToLibraryAction() {
        return saveToLibraryAction;
    }

    /**
     * Shows the tool window (after "Save to Query Library…").
     */
    void setShowWindow(@NotNull Runnable showWindow) {
        this.showWindow = showWindow;
    }

    @NotNull
    private static Action createAction(@NotNull String name, @NotNull String description, @NotNull Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, description);
        return action;
    }

    private void bindKey(@NotNull KeyStroke keyStroke, @NotNull String actionKey, @NotNull Runnable task) {
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(keyStroke, actionKey);
        tree.getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        });
    }

    /**
     * Icon tool bar below the header: Run | New Query, New Folder, Save to Query Library | Scheduled Queries, Refresh, Library Roots.
     */
    @NotNull
    private JComponent createToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(3), UIScale.scale(8), UIScale.scale(3), UIScale.scale(8)));
        toolBar.add(UiIcons.createActionButton(runAction, "run")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createActionButton(newQueryAction, "new-query")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(newFolderAction, "new-folder")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(saveToLibraryAction, "save")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createActionButton(scheduledAction, "schedule")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(rootsAction, "settings")); //$NON-NLS-1$
        return toolBar;
    }

    //////////////////////////////////////////////////////////
    // Loading (job) and tree (EDT)

    /**
     * Reads all roots in a job and rebuilds the tree.
     */
    void reload() {
        int serial = ++loadSerial;
        List<QueryLibrary.Root> roots = library.getRoots();
        new AbstractJob("Read Query Library") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<QueryLibrary.Folder> loaded = new ArrayList<>();
                for (QueryLibrary.Root root : roots) {
                    try {
                        loaded.add(library.load(root));
                    } catch (IOException e) {
                        log.warn("Can't read Query Library root " + root.path(), e);
                        loaded.add(new QueryLibrary.Folder(root, root.path()));
                    }
                }
                List<QueryLibraryItem> scheduled = new ArrayList<>();
                loaded.forEach(folder -> collectScheduled(folder, scheduled));
                QueryScheduler.getInstance().update(scheduled);
                SwingUtilities.invokeLater(() -> {
                    if (serial == loadSerial) {
                        folders = loaded;
                        rebuildTree();
                    }
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * Rebuilds the tree from the last read folders with the search filter, keeping expanded folders and the selection.
     */
    private void rebuildTree() {
        Set<String> expanded = new HashSet<>();
        Enumeration<TreePath> expandedPaths = tree.getExpandedDescendants(new TreePath(rootNode));
        boolean firstBuild = rootNode.getChildCount() == 0;
        if (expandedPaths != null) {
            while (expandedPaths.hasMoreElements()) {
                String key = getKey(expandedPaths.nextElement().getLastPathComponent());
                if (key != null) {
                    expanded.add(key);
                }
            }
        }
        String selected = tree.getSelectionPath() != null ? getKey(tree.getSelectionPath().getLastPathComponent()) : null;

        String filter = searchField.getText().strip().toLowerCase(Locale.ROOT);
        rootNode.removeAllChildren();
        for (QueryLibrary.Folder folder : folders) {
            DefaultMutableTreeNode node = createFolderNode(folder, filter);
            // Roots stay visible even if nothing in them matches
            rootNode.add(node != null ? node : new DefaultMutableTreeNode(folder));
        }
        treeModel.reload();

        List<DefaultMutableTreeNode> nodes = new ArrayList<>();
        collectNodes(rootNode, nodes);
        for (DefaultMutableTreeNode node : nodes) {
            if (node == rootNode || node.isLeaf()) {
                continue;
            }
            String key = getKey(node);
            if (!filter.isEmpty() || (firstBuild && node.getParent() == rootNode) || (key != null && expanded.contains(key))) {
                tree.expandPath(new TreePath(node.getPath()));
            }
        }
        if (selected != null) {
            for (DefaultMutableTreeNode node : nodes) {
                if (selected.equals(getKey(node))) {
                    TreePath path = new TreePath(node.getPath());
                    tree.setSelectionPath(path);
                    tree.scrollPathToVisible(path);
                    break;
                }
            }
        }
        updateActions();
    }

    @Nullable
    private static DefaultMutableTreeNode createFolderNode(@NotNull QueryLibrary.Folder folder, @NotNull String filter) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(folder);
        for (QueryLibrary.Folder subfolder : folder.folders) {
            DefaultMutableTreeNode child = createFolderNode(subfolder, filter);
            if (child != null) {
                node.add(child);
            }
        }
        for (QueryLibraryItem item : folder.queries) {
            if (filter.isEmpty() || item.getName().toLowerCase(Locale.ROOT).contains(filter)
                || CommonUtils.notEmpty(item.getDescription()).toLowerCase(Locale.ROOT).contains(filter)) {
                node.add(new DefaultMutableTreeNode(item, false));
            }
        }
        return filter.isEmpty() || node.getChildCount() > 0 ? node : null;
    }

    private static void collectScheduled(@NotNull QueryLibrary.Folder folder, @NotNull List<QueryLibraryItem> result) {
        for (QueryLibraryItem item : folder.queries) {
            if (item.getSchedule() != null && item.getId() != null) {
                result.add(item);
            }
        }
        folder.folders.forEach(f -> collectScheduled(f, result));
    }

    private static void collectNodes(@NotNull DefaultMutableTreeNode node, @NotNull List<DefaultMutableTreeNode> result) {
        result.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            collectNodes((DefaultMutableTreeNode) node.getChildAt(i), result);
        }
    }

    /**
     * Identity of a node across reloads: its file or folder path.
     */
    @Nullable
    private static String getKey(@Nullable Object node) {
        Path path = getPath(node);
        return path != null ? path.toString() : null;
    }

    @Nullable
    private static Path getPath(@Nullable Object node) {
        Object userObject = node instanceof DefaultMutableTreeNode treeNode ? treeNode.getUserObject() : null;
        if (userObject instanceof QueryLibrary.Folder folder) {
            return folder.path;
        }
        return userObject instanceof QueryLibraryItem item ? item.getFile() : null;
    }

    //////////////////////////////////////////////////////////
    // Selection

    @Nullable
    private DefaultMutableTreeNode getSelectedNode() {
        TreePath path = tree.getSelectionPath();
        return path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node ? node : null;
    }

    @Nullable
    private QueryLibraryItem getSelectedQuery() {
        DefaultMutableTreeNode node = getSelectedNode();
        return node != null && node.getUserObject() instanceof QueryLibraryItem item ? item : null;
    }

    /**
     * The selected folder, the folder of the selected query, or the first root.
     */
    @Nullable
    private QueryLibrary.Folder getTargetFolder() {
        DefaultMutableTreeNode node = getSelectedNode();
        while (node != null) {
            if (node.getUserObject() instanceof QueryLibrary.Folder folder) {
                return folder;
            }
            node = (DefaultMutableTreeNode) node.getParent();
        }
        for (QueryLibrary.Folder folder : folders) {
            if (!folder.root.readOnly()) {
                return folder;
            }
        }
        return null;
    }

    private void updateActions() {
        DefaultMutableTreeNode node = getSelectedNode();
        QueryLibraryItem query = getSelectedQuery();
        Path path = getPath(node);
        boolean writable = path != null && !library.isReadOnly(path);
        boolean isRoot = node != null && node.getParent() == rootNode;
        QueryLibrary.Folder targetFolder = getTargetFolder();
        boolean targetWritable = targetFolder != null && !targetFolder.root.readOnly();
        runAction.setEnabled(query != null);
        runOnAction.setEnabled(query != null);
        editAction.setEnabled(query != null);
        scheduleAction.setEnabled(query != null && (writable || query.getSchedule() != null));
        historyAction.setEnabled(query != null);
        newQueryAction.setEnabled(targetWritable);
        newFolderAction.setEnabled(targetWritable);
        duplicateAction.setEnabled(query != null && targetWritable);
        renameAction.setEnabled(writable && !isRoot);
        deleteAction.setEnabled(writable && !isRoot);
        openFolderAction.setEnabled(path != null && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN));
    }

    private void maybeShowMenu(@NotNull MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = tree.getClosestRowForLocation(e.getX(), e.getY());
        Rectangle bounds = row >= 0 ? tree.getRowBounds(row) : null;
        if (bounds != null && e.getY() >= bounds.y && e.getY() < bounds.y + bounds.height) {
            tree.setSelectionRow(row);
        } else {
            tree.clearSelection();
        }
        updateActions();
        JPopupMenu menu = new JPopupMenu();
        if (getSelectedQuery() != null) {
            JMenuItem runItem = menu.add(runAction);
            runItem.setFont(runItem.getFont().deriveFont(Font.BOLD));
            menu.add(runOnAction);
            menu.add(editAction);
            menu.addSeparator();
            menu.add(scheduleAction);
            menu.add(historyAction);
            menu.addSeparator();
        }
        menu.add(newQueryAction);
        menu.add(newFolderAction);
        if (getSelectedQuery() != null) {
            menu.add(duplicateAction);
        }
        menu.addSeparator();
        menu.add(renameAction);
        menu.add(deleteAction);
        menu.addSeparator();
        menu.add(openFolderAction);
        menu.add(scheduledAction);
        menu.add(refreshAction);
        menu.add(rootsAction);
        menu.show(tree, e.getX(), e.getY());
    }

    //////////////////////////////////////////////////////////
    // Commands (EDT)

    private void runSelected() {
        QueryLibraryItem item = getSelectedQuery();
        if (item != null) {
            QueryLibraryEditor.run(host, readCurrent(item), null);
        }
    }

    private void runSelectedOn() {
        QueryLibraryItem item = getSelectedQuery();
        if (item == null) {
            return;
        }
        DBPDataSourceContainer container = QueryLibraryEditor.chooseConnection(host.getComponent(),
            NLS.bind(ToolsMessages.library_run_on_title, item.getName()),
            null, host.getCurrentConnection());
        if (container != null) {
            QueryLibraryEditor.run(host, readCurrent(item), container);
        }
    }

    private void editSelected() {
        QueryLibraryItem item = getSelectedQuery();
        if (item != null) {
            QueryLibraryEditor.edit(host, readCurrent(item));
        }
    }

    /**
     * The query as it is on disk now (another person may have changed a shared file since the tree was read).
     */
    @NotNull
    private QueryLibraryItem readCurrent(@NotNull QueryLibraryItem item) {
        Path file = library.resolveFile(item);
        if (file != null && Files.isRegularFile(file)) {
            try {
                return QueryLibraryFile.read(file);
            } catch (IOException e) {
                log.debug("Can't read " + file, e);
            }
        }
        return item;
    }

    /**
     * Schedule…: edits the schedule in the query file (the definition is shared with the file) and whether it runs on
     * this computer (local). In a read-only root only the local part can change.
     */
    private void scheduleSelected() {
        QueryLibraryItem selected = getSelectedQuery();
        if (selected == null) {
            return;
        }
        QueryLibraryItem item = readCurrent(selected);
        Path file = library.resolveFile(item);
        boolean readOnly = file == null || library.isReadOnly(file);
        QueryScheduleDialog.Result result = QueryScheduleDialog.open(host.getComponent(), item,
            item.getId() != null && QueryLibraryState.isScheduleActive(item));
        if (result == null) {
            return;
        }
        if (readOnly) {
            // The shared definition stays as it is; only this computer's choice changes
            QueryLibraryState.setScheduleActive(item, result.active() && item.getSchedule() != null);
            library.fireChanged();
            return;
        }
        item.setSchedule(result.schedule());
        try {
            library.save(item);
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.library_schedule_title,
                NLS.bind(ToolsMessages.library_error_save_schedule, item.getName()), e);
            return;
        }
        QueryLibraryState.setScheduleActive(item, result.active() && result.schedule() != null);
        library.fireChanged();
    }

    private void showHistory() {
        QueryLibraryItem item = getSelectedQuery();
        if (item != null) {
            QueryRunHistoryDialog.open(host.getComponent(), item);
        }
    }

    private void newQuery() {
        QueryLibrary.Folder folder = getTargetFolder();
        if (folder == null || folder.root.readOnly()) {
            return;
        }
        String name = askName(ToolsMessages.library_new_query, ToolsMessages.library_new_query_prompt, ToolsMessages.library_new_query);
        if (name == null) {
            return;
        }
        QueryLibraryItem item = QueryLibraryItem.create(name, ""); //$NON-NLS-1$
        DBPDataSourceContainer current = host.getCurrentConnection();
        if (current != null) {
            item.setTargetMode(QueryLibraryItem.TargetMode.CONNECTION);
            item.setTarget(current.getId(), current.getName());
        }
        try {
            library.createQuery(folder.path, item);
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.library_new_query, ToolsMessages.library_error_create_query, e);
            return;
        }
        selectAfterReload(item.getFile());
        QueryLibraryEditor.edit(host, item);
    }

    private void newFolder() {
        QueryLibrary.Folder folder = getTargetFolder();
        if (folder == null || folder.root.readOnly()) {
            return;
        }
        String name = askName(ToolsMessages.library_new_folder, ToolsMessages.library_new_folder_prompt, ToolsMessages.library_new_folder);
        if (name == null) {
            return;
        }
        try {
            selectAfterReload(library.createFolder(folder.path, name));
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.library_new_folder, ToolsMessages.library_error_create_folder, e);
        }
    }

    private void duplicateSelected() {
        QueryLibraryItem item = getSelectedQuery();
        QueryLibrary.Folder folder = getTargetFolder();
        if (item == null || folder == null) {
            return;
        }
        // A query of a read-only root is copied to the first editable root
        Path target = folder.root.readOnly() ? null : folder.path;
        if (target == null) {
            for (QueryLibrary.Folder root : folders) {
                if (!root.root.readOnly()) {
                    target = root.path;
                    break;
                }
            }
        }
        if (target == null) {
            return;
        }
        try {
            QueryLibraryItem copy = library.duplicate(readCurrent(item), target);
            selectAfterReload(copy.getFile());
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.library_duplicate, ToolsMessages.library_error_duplicate, e);
        }
    }

    private void renameSelected() {
        DefaultMutableTreeNode node = getSelectedNode();
        Path path = getPath(node);
        if (node == null || path == null || library.isReadOnly(path) || node.getParent() == rootNode) {
            return;
        }
        try {
            if (node.getUserObject() instanceof QueryLibraryItem item) {
                String name = askName(ToolsMessages.library_rename, ToolsMessages.library_rename_query_prompt, item.getName());
                if (name != null && !name.equals(item.getName())) {
                    library.renameQuery(item, name);
                    selectAfterReload(library.resolveFile(readCurrent(item)));
                }
            } else if (node.getUserObject() instanceof QueryLibrary.Folder folder) {
                String name = askName(ToolsMessages.library_rename, ToolsMessages.library_rename_folder_prompt, folder.getName());
                if (name != null && !name.equals(folder.getName())) {
                    selectAfterReload(library.renameFolder(folder.path, name));
                }
            }
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.library_rename, NLS.bind(ToolsMessages.library_error_rename, path.getFileName()), e);
        }
    }

    private void deleteSelected() {
        DefaultMutableTreeNode node = getSelectedNode();
        Path path = getPath(node);
        if (node == null || path == null || library.isReadOnly(path) || node.getParent() == rootNode) {
            return;
        }
        String question = node.getUserObject() instanceof QueryLibraryItem item
            ? NLS.bind(ToolsMessages.library_confirm_delete_query, item.getName())
            : NLS.bind(ToolsMessages.library_confirm_delete_folder, path.getFileName());
        if (JOptionPane.showConfirmDialog(host.getComponent(), question,
            ToolsMessages.library_delete, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            library.delete(path);
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.library_delete, NLS.bind(ToolsMessages.library_error_delete, path.getFileName()), e);
        }
    }

    private void openFolder() {
        Path path = getPath(getSelectedNode());
        if (path == null) {
            return;
        }
        Path folder = Files.isDirectory(path) ? path : path.getParent();
        try {
            Desktop.getDesktop().open(folder.toFile());
        } catch (IOException | RuntimeException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.library_open_folder, NLS.bind(ToolsMessages.library_error_open_folder, folder), e);
        }
    }

    private void editRoots() {
        List<QueryLibrary.Root> roots = QueryLibraryRootsDialog.open(host.getComponent(), library.getRoots());
        if (roots != null) {
            library.setRoots(roots);
        }
    }

    /**
     * Query &gt; Save to Query Library…: the selection of the active query window (or its whole text) as a new query
     * in a folder, with the window's connection as its target.
     */
    private void saveActiveWindow() {
        SqlEditorPanel editor = host.getActiveQueryWindow();
        if (editor == null) {
            JOptionPane.showMessageDialog(host.getComponent(), ToolsMessages.library_save_error_no_window, ToolsMessages.library_save_title,
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String selection = editor.getTextArea().getSelectedText();
        String sql = !CommonUtils.isEmpty(selection) && !selection.isBlank() ? selection : editor.getContent().text();
        if (sql.isBlank()) {
            JOptionPane.showMessageDialog(host.getComponent(), ToolsMessages.library_save_error_empty, ToolsMessages.library_save_title,
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<QueryLibrary.Folder> writableFolders = new ArrayList<>();
        for (QueryLibrary.Folder folder : folders) {
            collectWritableFolders(folder, writableFolders);
        }
        if (writableFolders.isEmpty()) {
            JOptionPane.showMessageDialog(host.getComponent(), ToolsMessages.library_save_error_no_root, ToolsMessages.library_save_title,
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String fileName = editor.getFileName();
        String suggestedName = fileName.toLowerCase(Locale.ROOT).endsWith(QueryLibraryFile.EXTENSION)
            ? fileName.substring(0, fileName.length() - QueryLibraryFile.EXTENSION.length()) : fileName;
        JTextField nameField = new JTextField(suggestedName, 28);
        JTextField descriptionField = new JTextField(28);
        JComboBox<QueryLibrary.Folder> folderCombo = new JComboBox<>(writableFolders.toArray(new QueryLibrary.Folder[0]));
        folderCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof QueryLibrary.Folder folder) {
                    setText(describeFolder(folder));
                    setIcon(UiIcons.get(folder.path.equals(folder.root.path()) ? "library" : "folder")); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return this;
            }
        });
        QueryLibrary.Folder selectedFolder = getTargetFolder();
        if (selectedFolder != null && writableFolders.contains(selectedFolder)) {
            folderCombo.setSelectedItem(selectedFolder);
        }
        DBPDataSourceContainer connection = editor.getDataSourceContainer();
        JCheckBox targetBox = new JCheckBox(connection != null ? NLS.bind(ToolsMessages.library_save_run_on_default, connection.getName())
            : ToolsMessages.library_save_run_on_current,
            connection != null);
        targetBox.setEnabled(connection != null);

        JPanel form = new JPanel(new GridBagLayout());
        addFormRow(form, 0, ToolsMessages.library_field_name, nameField);
        addFormRow(form, 1, ToolsMessages.library_field_description, descriptionField);
        addFormRow(form, 2, ToolsMessages.library_field_folder, folderCombo);
        addFormRow(form, 3, null, targetBox);
        while (true) {
            if (JOptionPane.showConfirmDialog(host.getComponent(), form, ToolsMessages.library_save_title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
                return;
            }
            if (!nameField.getText().isBlank()) {
                break;
            }
            JOptionPane.showMessageDialog(host.getComponent(), ToolsMessages.library_error_enter_name, ToolsMessages.library_save_title,
                JOptionPane.INFORMATION_MESSAGE);
        }
        QueryLibraryItem item = QueryLibraryItem.create(nameField.getText().strip(), sql);
        item.setDescription(descriptionField.getText().strip());
        if (connection != null && targetBox.isSelected()) {
            item.setTargetMode(QueryLibraryItem.TargetMode.CONNECTION);
            item.setTarget(connection.getId(), connection.getName());
        }
        item.getParameters().addAll(QueryParameterDefinitionsDialog.merge(List.of(), QueryParameterBinder.detect(sql)));
        QueryLibrary.Folder folder = (QueryLibrary.Folder) folderCombo.getSelectedItem();
        try {
            library.createQuery(folder != null ? folder.path : writableFolders.getFirst().path, item);
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.library_save_title, ToolsMessages.library_error_save, e);
            return;
        }
        if (showWindow != null) {
            showWindow.run();
        }
        selectAfterReload(item.getFile());
    }

    @NotNull
    private static String describeFolder(@NotNull QueryLibrary.Folder folder) {
        Path relative = folder.root.path().relativize(folder.path);
        String rest = relative.toString().replace(File.separatorChar, '/');
        return rest.isEmpty() ? folder.root.name() : folder.root.name() + " / " + rest.replace("/", " / "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static void collectWritableFolders(@NotNull QueryLibrary.Folder folder, @NotNull List<QueryLibrary.Folder> result) {
        if (folder.root.readOnly()) {
            return;
        }
        result.add(folder);
        folder.folders.forEach(f -> collectWritableFolders(f, result));
    }

    private static void addFormRow(@NotNull JPanel form, int row, @Nullable String label, @NotNull JComponent control) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 6, 8);
        if (label != null) {
            c.gridx = 0;
            form.add(new JLabel(label), c);
        }
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(control, c);
    }

    @Nullable
    private String askName(@NotNull String title, @NotNull String message, @NotNull String initial) {
        Object input = JOptionPane.showInputDialog(host.getComponent(), message, title, JOptionPane.PLAIN_MESSAGE, null, null, initial);
        if (input == null) {
            return null;
        }
        String name = input.toString().strip();
        return name.isEmpty() ? null : name;
    }

    /**
     * Selects a file or folder once the tree has been read again (changes reload it through the library listener).
     */
    private void selectAfterReload(@Nullable Path path) {
        if (path == null) {
            return;
        }
        int serial = loadSerial;
        String key = path.toString();
        // The reload started by the change finishes later; poll a few times on the EDT
        Timer timer = new Timer(100, null);
        int[] attempts = {0};
        timer.addActionListener(e -> {
            attempts[0]++;
            if (loadSerial != serial || attempts[0] > 30) {
                List<DefaultMutableTreeNode> nodes = new ArrayList<>();
                collectNodes(rootNode, nodes);
                for (DefaultMutableTreeNode node : nodes) {
                    if (key.equals(getKey(node))) {
                        TreePath treePath = new TreePath(node.getPath());
                        tree.setSelectionPath(treePath);
                        tree.scrollPathToVisible(treePath);
                        tree.requestFocusInWindow();
                        timer.stop();
                        return;
                    }
                }
                if (attempts[0] > 30) {
                    timer.stop();
                }
            }
        });
        timer.start();
    }

    //////////////////////////////////////////////////////////
    // Drag and drop

    @Nullable
    private static DataFlavor createPathFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=java.nio.file.Path", "Query Library item", //$NON-NLS-1$
                Path.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            log.error("No Path flavor", e);
            return null;
        }
    }

    /**
     * Moves queries and folders between folders; copies {@code .sql} files dropped from the file manager.
     */
    private final class LibraryTransferHandler extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Nullable
        @Override
        protected Transferable createTransferable(JComponent c) {
            DefaultMutableTreeNode node = getSelectedNode();
            Path path = getPath(node);
            if (node == null || path == null || node.getParent() == rootNode || library.isReadOnly(path) || PATH_FLAVOR == null) {
                return null;
            }
            return new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[]{PATH_FLAVOR};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor flavor) {
                    return PATH_FLAVOR.equals(flavor);
                }

                @NotNull
                @Override
                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                    if (!isDataFlavorSupported(flavor)) {
                        throw new UnsupportedFlavorException(flavor);
                    }
                    return path;
                }
            };
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                && (PATH_FLAVOR == null || !support.isDataFlavorSupported(PATH_FLAVOR)))) {
                return false;
            }
            Path target = getDropFolder(support);
            return target != null && !library.isReadOnly(target);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            Path target = getDropFolder(support);
            if (target == null) {
                return false;
            }
            try {
                if (PATH_FLAVOR != null && support.isDataFlavorSupported(PATH_FLAVOR)) {
                    Path source = (Path) support.getTransferable().getTransferData(PATH_FLAVOR);
                    selectAfterReload(library.move(source, target));
                    return true;
                }
                List<?> files = (List<?>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                for (Object file : files) {
                    if (file instanceof File sqlFile && sqlFile.getName().toLowerCase(Locale.ROOT).endsWith(QueryLibraryFile.EXTENSION)) {
                        Path copy = QueryLibrary.uniquePath(target, sqlFile.getName().substring(0,
                            sqlFile.getName().length() - QueryLibraryFile.EXTENSION.length()), QueryLibraryFile.EXTENSION);
                        Files.copy(sqlFile.toPath(), copy);
                    }
                }
                library.fireChanged();
                return true;
            } catch (UnsupportedFlavorException | IOException e) {
                DBWorkbench.getPlatformUI().showError(ToolsMessages.library_move_title, ToolsMessages.library_error_move, e);
                return false;
            }
        }

        @Nullable
        private Path getDropFolder(@NotNull TransferSupport support) {
            if (!(support.getDropLocation() instanceof JTree.DropLocation location) || location.getPath() == null) {
                return null;
            }
            Object node = location.getPath().getLastPathComponent();
            Object userObject = node instanceof DefaultMutableTreeNode treeNode ? treeNode.getUserObject() : null;
            if (userObject instanceof QueryLibrary.Folder folder) {
                return folder.path;
            }
            if (userObject instanceof QueryLibraryItem item && item.getFile() != null) {
                return item.getFile().getParent();
            }
            return null;
        }
    }

    //////////////////////////////////////////////////////////
    // Look

    /**
     * The tree with a rounded background on the row under the mouse (as Object Explorer).
     */
    private static final class LibraryTree extends JTree {
        private int hoverRow = -1;

        LibraryTree(@NotNull DefaultTreeModel model) {
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
                Rectangle bounds = hoverRow >= 0 && hoverRow < getRowCount() && !isRowSelected(hoverRow) ? getRowBounds(hoverRow) : null;
                Color color = UIManager.getColor("Chromite.Tree.hoverBackground"); //$NON-NLS-1$
                if (bounds != null && color != null) {
                    Insets selectionInsets = UIManager.getInsets("Tree.selectionInsets"); //$NON-NLS-1$
                    Insets insets = UIScale.scale(selectionInsets != null ? selectionInsets : new Insets(0, 0, 0, 0));
                    float arc = UIScale.scale((float) UIManager.getInt("Tree.selectionArc")); //$NON-NLS-1$
                    Object[] oldHints = FlatUIUtils.setRenderingHints(g2);
                    g2.setColor(color);
                    g2.fill(new RoundRectangle2D.Float(insets.left, bounds.y + insets.top, getWidth() - insets.left - insets.right,
                        bounds.height - insets.top - insets.bottom, arc, arc));
                    FlatUIUtils.resetRenderingHints(g2, oldHints);
                }
                ui.paint(g2, this);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * Roots (library icon, "read-only"), folders, queries (name, target connection as secondary text, description and
     * file as tool tip). A query with an unseen alert has a red dot, and so has a collapsed folder that contains one.
     */
    private static final class LibraryCellRenderer extends DefaultTreeCellRenderer {
        private static final int GAP = 6;
        @Nullable
        private String secondaryText;

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            setIconTextGap(UIScale.scale(GAP));
            secondaryText = null;
            Object userObject = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
            if (userObject instanceof QueryLibrary.Folder folder) {
                boolean root = folder.path.equals(folder.root.path());
                setText(folder.getName());
                Icon icon = UiIcons.get(root ? "library" : expanded ? "folder-open" : "folder"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                // An alert inside a collapsed folder still shows
                setIcon(!expanded && hasAlert((DefaultMutableTreeNode) value) ? UiIcons.withStatusDot(icon, UiIcons.Status.ERROR) : icon);
                setToolTipText(folder.path.toString());
                if (root && folder.missing) {
                    secondaryText = ToolsMessages.library_tree_folder_missing;
                } else if (root && folder.root.readOnly()) {
                    secondaryText = ToolsMessages.library_tree_read_only;
                }
            } else if (userObject instanceof QueryLibraryItem item) {
                setText(item.getName());
                Icon icon = UiIcons.get("library-query"); //$NON-NLS-1$
                // A scheduled run met its condition and nobody has looked at it yet
                setIcon(item.getId() != null && QueryLibraryState.hasAlert(item) ? UiIcons.withStatusDot(icon, UiIcons.Status.ERROR) : icon);
                String description = item.getDescription();
                setToolTipText("<html>" + (description != null ? CommonUtils.escapeHtml(description) + "<br>" : "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + CommonUtils.escapeHtml(String.valueOf(item.getFile())) + "</html>"); //$NON-NLS-1$
                if (item.getTargetMode() == QueryLibraryItem.TargetMode.CONNECTION) {
                    secondaryText = item.getTargetName();
                } else if (item.getTargetMode() == QueryLibraryItem.TargetMode.ASK) {
                    secondaryText = ToolsMessages.library_tree_ask;
                }
                QueryLibrarySchedule schedule = item.getSchedule();
                if (schedule != null && schedule.isEnabled() && item.getId() != null && QueryLibraryState.isScheduleActive(item)) {
                    secondaryText = (secondaryText != null ? secondaryText + " · " : "") + schedule.describe(); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            return this;
        }

        private static boolean hasAlert(@NotNull DefaultMutableTreeNode node) {
            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                if (child.getUserObject() instanceof QueryLibraryItem item ? item.getId() != null && QueryLibraryState.hasAlert(item) : hasAlert(child)) {
                    return true;
                }
            }
            return false;
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
