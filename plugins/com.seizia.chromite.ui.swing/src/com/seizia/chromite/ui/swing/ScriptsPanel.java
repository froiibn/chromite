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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.ScriptsMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Scripts 도구 창(SSMS의 Solution Explorer, SWT의 Project Explorer·Scripts): 활성 프로젝트의 스크립트 폴더 트리와 파일 명령.
 *
 * 설계:
 * <ul>
 *     <li>루트는 활성 프로젝트의 스크립트 폴더다({@link ScriptFiles#getActiveRoot}, SWT와 같은 {@code Scripts} 폴더). SWT Project Explorer도
 *     활성 프로젝트만 보였다. 폴더와 스크립트 확장자 파일만 보이고, 이름이 {@code .}으로 시작하는 것은 숨긴다.</li>
 *     <li>읽기는 작업 스레드에서 폴더를 모두 읽고(연결 묶기도 함께) EDT에서 트리를 다시 만들며, 펼친 폴더와 선택을 경로로 되살린다
 *     (Query Library와 같은 방식). 이 창의 명령과 메인 창의 저장 뒤에 다시 읽고, Refresh(도구 막대, 트리에 초점이 있을 때 F5)도 된다.
 *     파일 시스템 감시는 하지 않는다(탐색기에서 바꾼 것은 Refresh로 읽는다).</li>
 *     <li>명령: Enter·더블클릭·Open(쿼리 창에서 열기, 이미 열려 있으면 그 탭), New Folder, Rename(F2), Delete(Delete 키, 확인의 기본 단추는 취소,
 *     시스템 휴지통이 있으면 휴지통으로), Link to Connection…, Remove Connection Link, Refresh.
 *     이름 규칙과 같은 이름 피하기는 Query Library와 같다({@link QueryLibraryFile#toFileName}, {@link QueryLibrary#uniquePath}).</li>
 *     <li>열린 쿼리 창: 이름을 바꾼 스크립트(바꾼 폴더 안의 스크립트)를 연 창은 새 경로로 따라간다({@link SqlEditorPanel#fileMoved}).
 *     열린 스크립트(또는 그것이 든 폴더)는 지우지 않고 먼저 창을 닫으라고 알린다(고치던 글이 파일 없이 남지 않게).</li>
 *     <li>연결 묶기({@link ScriptFiles}): 스크립트를 열 때 쓸 연결이다. 이름 뒤 보조 글자로 연결 이름을 보인다. 묶기를 바꿔도 이미 열린
 *     쿼리 창의 연결은 바꾸지 않는다(창의 서버는 그 창의 연결 변경 명령으로만 바뀐다. 앱 변경 #69·#73).</li>
 *     <li>모양은 Query Library와 같다: 같은 트리({@link QueryLibraryPanel.LibraryTree}), 머리말 아래 아이콘 도구 막대, 이름 뒤 보조 글자.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: SWT 프로젝트·스크립트 탐색기의 스크립트 부분 (docs/chromite/swing-scripts-explorer.md)
 * - 2026-11-12 주석만: 열린 창의 연결을 바꾸지 않는 이유를 고침(창의 연결 콤보가 없어짐, 앱 변경 #69) (docs/chromite/swing-frontend.md "쿼리 창은 한 서버의 창")
 * - 2026-11-12 주석만: 창의 서버는 연결 변경 명령으로만 바뀜(앱 변경 #73) (docs/chromite/swing-frontend.md "쿼리 창의 연결 변경")
 */
final class ScriptsPanel extends JPanel {

    private static final Log log = Log.getLog(ScriptsPanel.class);

    /**
     * Folders deeper than this are not read (a link loop would never end).
     */
    private static final int MAX_DEPTH = 32;

    /**
     * The main window side of the Scripts tool window.
     */
    interface Host {
        /**
         * Parent of the dialogs.
         */
        @NotNull
        Component getComponent();

        @NotNull
        List<SqlEditorPanel> getQueryWindows();

        /**
         * Opens a script in a query window (with its linked connection), or shows the window that has it open.
         */
        void openScript(@NotNull Path file);
    }

    /**
     * A folder or script read from disk (job thread).
     */
    private static final class Entry {
        final Path path;
        final boolean folder;
        final boolean root;
        // The script folder does not exist yet (root only)
        final boolean missing;
        final ScriptFiles.Link link;
        final List<Entry> children = new ArrayList<>();

        Entry(@NotNull Path path, boolean folder, boolean root, boolean missing, @NotNull ScriptFiles.Link link) {
            this.path = path;
            this.folder = folder;
            this.root = root;
            this.missing = missing;
            this.link = link;
        }

        @NotNull
        String getName() {
            return String.valueOf(path.getFileName());
        }

        @Override
        public String toString() {
            return getName();
        }
    }

    private final Host host;
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final QueryLibraryPanel.LibraryTree tree = new QueryLibraryPanel.LibraryTree(treeModel);
    private final Action openAction;
    private final Action newFolderAction;
    private final Action renameAction;
    private final Action deleteAction;
    private final Action linkAction;
    private final Action unlinkAction;
    private final Action refreshAction;
    // EDT only
    @Nullable
    private Entry rootEntry;
    private int loadSerial;

    ScriptsPanel(@NotNull Host host) {
        super(new BorderLayout());
        this.host = host;

        openAction = createAction(ScriptsMessages.scripts_open, ScriptsMessages.scripts_open_tip, this::openSelected);
        newFolderAction = createAction(ScriptsMessages.scripts_new_folder, ScriptsMessages.scripts_new_folder_tip, this::newFolder);
        renameAction = createAction(ScriptsMessages.scripts_rename, ScriptsMessages.scripts_rename_tip, this::renameSelected);
        deleteAction = createAction(ScriptsMessages.scripts_delete, ScriptsMessages.scripts_delete_tip, this::deleteSelected);
        linkAction = createAction(ScriptsMessages.scripts_link, ScriptsMessages.scripts_link_tip, this::linkSelected);
        unlinkAction = createAction(ScriptsMessages.scripts_unlink, ScriptsMessages.scripts_unlink_tip, this::unlinkSelected);
        refreshAction = createAction(ScriptsMessages.scripts_refresh, ScriptsMessages.scripts_refresh_tip, this::reload);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new ScriptCellRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.putClientProperty(FlatClientProperties.TREE_WIDE_SELECTION, true);
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
                Entry entry = getSelectedEntry();
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && entry != null && !entry.folder) {
                    openSelected();
                }
            }
        });
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chromite.open", () -> { //$NON-NLS-1$
            Entry entry = getSelectedEntry();
            TreePath path = tree.getSelectionPath();
            if (entry != null && !entry.folder) {
                openSelected();
            } else if (path != null) {
                if (tree.isExpanded(path)) {
                    tree.collapsePath(path);
                } else {
                    tree.expandPath(path);
                }
            }
        });
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "chromite.rename", this::renameSelected); //$NON-NLS-1$
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "chromite.delete", this::deleteSelected); //$NON-NLS-1$
        // As in Object Explorer and Query Library: F5 refreshes while the tree has the focus
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "chromite.refresh", this::reload); //$NON-NLS-1$

        add(createToolBar(), BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        add(scrollPane, BorderLayout.CENTER);
        updateActions();
        reload();
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
     * Icon tool bar below the header: New Folder, Link to Connection | Refresh; buttons that don't fit a narrow tool
     * window go to the overflow menu.
     */
    @NotNull
    private JComponent createToolBar() {
        JToolBar toolBar = new OverflowToolBar();
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        toolBar.add(UiIcons.createActionButton(newFolderAction, "new-folder")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(linkAction, "connection")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$
        return toolBar;
    }

    //////////////////////////////////////////////////////////
    // Loading (job) and tree (EDT)

    /**
     * Reads the script folder in a job and rebuilds the tree (a change here or by a save, Refresh).
     */
    void reload() {
        reload(null);
    }

    /**
     * @param select file or folder to select once read (one this window created or renamed), or null to keep the selection
     */
    private void reload(@Nullable Path select) {
        int serial = ++loadSerial;
        new AbstractJob("Read scripts") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                ScriptFiles.Root root = ScriptFiles.getActiveRoot();
                Entry loaded = root == null ? null : read(root.folder(), true, 0);
                SwingUtilities.invokeLater(() -> {
                    if (serial == loadSerial) {
                        rootEntry = loaded;
                        rebuildTree(select);
                    }
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * A folder with its sub folders and scripts, folders first, by name (job thread).
     */
    @NotNull
    private static Entry read(@NotNull Path folder, boolean root, int depth) {
        Entry entry = new Entry(folder, true, root, root && !Files.isDirectory(folder), ScriptFiles.Link.NONE);
        if (!Files.isDirectory(folder) || depth > MAX_DEPTH) {
            return entry;
        }
        List<Path> paths;
        try (Stream<Path> stream = Files.list(folder)) {
            paths = stream.toList();
        } catch (IOException e) {
            log.debug("Can't read the script folder " + folder, e);
            return entry;
        }
        for (Path path : paths) {
            if (String.valueOf(path.getFileName()).startsWith(".")) { //$NON-NLS-1$
                continue;
            }
            if (Files.isDirectory(path)) {
                entry.children.add(read(path, false, depth + 1));
            } else if (Files.isRegularFile(path) && ScriptFiles.isScript(path)) {
                entry.children.add(new Entry(path, false, false, false, ScriptFiles.getLink(path)));
            }
        }
        entry.children.sort(Comparator.<Entry, Boolean>comparing(child -> !child.folder)
            .thenComparing(Entry::getName, String.CASE_INSENSITIVE_ORDER));
        return entry;
    }

    /**
     * Rebuilds the tree from the last read folder, keeping expanded folders and the selection.
     */
    private void rebuildTree(@Nullable Path select) {
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
        String selected = select != null ? select.toAbsolutePath().normalize().toString()
            : tree.getSelectionPath() != null ? getKey(tree.getSelectionPath().getLastPathComponent()) : null;

        rootNode.removeAllChildren();
        if (rootEntry == null) {
            rootNode.add(new DefaultMutableTreeNode(ScriptsMessages.scripts_no_project, false));
        } else {
            rootNode.add(createNode(rootEntry));
        }
        treeModel.reload();

        List<DefaultMutableTreeNode> nodes = new ArrayList<>();
        collectNodes(rootNode, nodes);
        DefaultMutableTreeNode selectedNode = null;
        for (DefaultMutableTreeNode node : nodes) {
            String key = getKey(node);
            if (node != rootNode && !node.isLeaf() && ((firstBuild && node.getParent() == rootNode) || (key != null && expanded.contains(key)))) {
                tree.expandPath(new TreePath(node.getPath()));
            }
            if (selected != null && selected.equals(key)) {
                selectedNode = node;
            }
        }
        if (selectedNode != null) {
            TreePath path = new TreePath(selectedNode.getPath());
            tree.makeVisible(path);
            tree.setSelectionPath(path);
            // Vertically only: a row with a long connection name would scroll the folder handles out of a narrow window
            Rectangle bounds = tree.getPathBounds(path);
            if (bounds != null) {
                tree.scrollRectToVisible(new Rectangle(0, bounds.y, 1, bounds.height));
            }
        }
        updateActions();
    }

    @NotNull
    private static DefaultMutableTreeNode createNode(@NotNull Entry entry) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(entry, entry.folder);
        for (Entry child : entry.children) {
            node.add(createNode(child));
        }
        return node;
    }

    private static void collectNodes(@NotNull DefaultMutableTreeNode node, @NotNull List<DefaultMutableTreeNode> result) {
        result.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            collectNodes((DefaultMutableTreeNode) node.getChildAt(i), result);
        }
    }

    /**
     * Identity of a node across reloads: its path.
     */
    @Nullable
    private static String getKey(@Nullable Object node) {
        Object userObject = node instanceof DefaultMutableTreeNode treeNode ? treeNode.getUserObject() : null;
        return userObject instanceof Entry entry ? entry.path.toAbsolutePath().normalize().toString() : null;
    }

    //////////////////////////////////////////////////////////
    // Selection

    @Nullable
    private Entry getSelectedEntry() {
        TreePath path = tree.getSelectionPath();
        return path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node
            && node.getUserObject() instanceof Entry entry ? entry : null;
    }

    /**
     * The selected folder, the folder of the selected script, or the script folder.
     */
    @Nullable
    private Path getTargetFolder() {
        Entry entry = getSelectedEntry();
        if (entry != null) {
            return entry.folder ? entry.path : entry.path.getParent();
        }
        return rootEntry != null ? rootEntry.path : null;
    }

    private void updateActions() {
        Entry entry = getSelectedEntry();
        boolean script = entry != null && !entry.folder;
        openAction.setEnabled(script);
        newFolderAction.setEnabled(rootEntry != null);
        renameAction.setEnabled(entry != null && !entry.root);
        deleteAction.setEnabled(entry != null && !entry.root);
        linkAction.setEnabled(script);
        unlinkAction.setEnabled(script && entry.link.dataSourceId() != null);
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
        Entry entry = getSelectedEntry();
        JPopupMenu menu = new JPopupMenu();
        if (entry != null && !entry.folder) {
            JMenuItem openItem = menu.add(openAction);
            openItem.setFont(openItem.getFont().deriveFont(Font.BOLD));
            menu.addSeparator();
            menu.add(linkAction);
            menu.add(unlinkAction);
            menu.addSeparator();
        }
        menu.add(newFolderAction);
        menu.add(renameAction);
        menu.add(deleteAction);
        menu.addSeparator();
        menu.add(refreshAction);
        menu.show(tree, e.getX(), e.getY());
    }

    //////////////////////////////////////////////////////////
    // Commands (EDT)

    private void openSelected() {
        Entry entry = getSelectedEntry();
        if (entry != null && !entry.folder) {
            host.openScript(entry.path);
        }
    }

    private void newFolder() {
        Path parent = getTargetFolder();
        if (parent == null) {
            return;
        }
        String name = askName(ScriptsMessages.scripts_new_folder, ScriptsMessages.scripts_new_folder_prompt, ScriptsMessages.scripts_new_folder);
        if (name == null) {
            return;
        }
        try {
            // The script folder itself is created on first use, as SWT did
            Files.createDirectories(parent);
            Path folder = QueryLibrary.uniquePath(parent, QueryLibraryFile.toFileName(name), ""); //$NON-NLS-1$
            Files.createDirectory(folder);
            reload(folder);
        } catch (IOException | InvalidPathException e) {
            DBWorkbench.getPlatformUI().showError(ScriptsMessages.scripts_new_folder, ScriptsMessages.scripts_error_create_folder, e);
        }
    }

    private void renameSelected() {
        Entry entry = getSelectedEntry();
        if (entry == null || entry.root) {
            return;
        }
        Path source = entry.path.toAbsolutePath().normalize();
        String fileName = entry.getName();
        // A script keeps its extension; the name is asked without it
        int dot = fileName.lastIndexOf('.');
        String extension = entry.folder || dot <= 0 ? "" : fileName.substring(dot); //$NON-NLS-1$
        String current = fileName.substring(0, fileName.length() - extension.length());
        String name = askName(ScriptsMessages.scripts_rename,
            entry.folder ? ScriptsMessages.scripts_rename_folder_prompt : ScriptsMessages.scripts_rename_script_prompt, current);
        if (name == null) {
            return;
        }
        if (!extension.isEmpty() && name.toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT))) {
            name = name.substring(0, name.length() - extension.length());
        }
        String stem = QueryLibraryFile.toFileName(name);
        if ((stem + extension).equals(fileName)) {
            return;
        }
        try {
            Path target = QueryLibrary.uniquePath(source.getParent(), stem, extension);
            Files.move(source, target);
            ScriptFiles.moved(source, target);
            // Open query windows follow their files
            for (SqlEditorPanel editor : host.getQueryWindows()) {
                Path file = editor.getFile() != null ? editor.getFile().toAbsolutePath().normalize() : null;
                if (file != null && file.startsWith(source)) {
                    editor.fileMoved(target.resolve(source.relativize(file)));
                }
            }
            reload(target);
        } catch (IOException | InvalidPathException e) {
            DBWorkbench.getPlatformUI().showError(ScriptsMessages.scripts_rename, NLS.bind(ScriptsMessages.scripts_error_rename, fileName), e);
        }
    }

    private void deleteSelected() {
        Entry entry = getSelectedEntry();
        if (entry == null || entry.root) {
            return;
        }
        Path path = entry.path.toAbsolutePath().normalize();
        // The text of an open window would stay without its file
        for (SqlEditorPanel editor : host.getQueryWindows()) {
            Path file = editor.getFile() != null ? editor.getFile().toAbsolutePath().normalize() : null;
            if (file != null && file.startsWith(path)) {
                JOptionPane.showMessageDialog(host.getComponent(), NLS.bind(ScriptsMessages.scripts_delete_open, file.getFileName()),
                    ScriptsMessages.scripts_delete, JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        }
        String question = NLS.bind(entry.folder ? ScriptsMessages.scripts_confirm_delete_folder : ScriptsMessages.scripts_confirm_delete_script,
            entry.getName());
        if (!SwingPlatformUI.confirmChange(host.getComponent(), question, ScriptsMessages.scripts_delete, ScriptsMessages.scripts_delete,
            JOptionPane.WARNING_MESSAGE)) {
            return;
        }
        try {
            boolean trashed = false;
            try {
                trashed = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
                    && Desktop.getDesktop().moveToTrash(path.toFile());
            } catch (RuntimeException e) {
                log.debug("Can't move to trash: " + path, e);
            }
            if (!trashed) {
                QueryLibrary.deleteRecursively(path);
            }
            ScriptFiles.deleted(path);
            reload(path.getParent());
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(ScriptsMessages.scripts_delete, NLS.bind(ScriptsMessages.scripts_error_delete, entry.getName()), e);
            reload();
        }
    }

    private void linkSelected() {
        Entry entry = getSelectedEntry();
        if (entry == null || entry.folder) {
            return;
        }
        DBPDataSourceContainer connection = QueryLibraryEditor.chooseConnection(host.getComponent(), ScriptsMessages.scripts_link_title,
            NLS.bind(ScriptsMessages.scripts_link_reason, entry.getName()), entry.link.connection());
        if (connection == null) {
            return;
        }
        ScriptFiles.setLink(entry.path, connection);
        reload(entry.path);
    }

    private void unlinkSelected() {
        Entry entry = getSelectedEntry();
        if (entry == null || entry.folder || entry.link.dataSourceId() == null) {
            return;
        }
        ScriptFiles.setLink(entry.path, null);
        reload(entry.path);
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

    //////////////////////////////////////////////////////////
    // Look

    /**
     * The script folder (secondary text when it does not exist yet), folders, scripts (linked connection as secondary
     * text, path and link as tool tip).
     */
    private static final class ScriptCellRenderer extends DefaultTreeCellRenderer {
        @Nullable
        private String secondaryText;

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            setIconTextGap(UiSizes.get(UiSizes.TREE_ICON_TEXT_GAP));
            secondaryText = null;
            Object userObject = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
            if (userObject instanceof Entry entry) {
                setText(entry.getName());
                if (entry.folder) {
                    setIcon(UiIcons.get(expanded ? "folder-open" : "folder")); //$NON-NLS-1$ //$NON-NLS-2$
                    setToolTipText(entry.path.toString());
                    secondaryText = entry.missing ? ScriptsMessages.scripts_tree_not_created : null;
                } else {
                    setIcon(UiIcons.get("file-sql")); //$NON-NLS-1$
                    DBPDataSourceContainer connection = entry.link.connection();
                    String linkTip = null;
                    if (connection != null) {
                        secondaryText = connection.getName();
                        linkTip = NLS.bind(ScriptsMessages.scripts_tip_link, connection.getName());
                    } else if (entry.link.isMissing()) {
                        secondaryText = ScriptsMessages.scripts_tree_link_missing;
                        linkTip = ScriptsMessages.scripts_tip_link_missing;
                    }
                    setToolTipText("<html>" + CommonUtils.escapeHtml(entry.path.toString()) //$NON-NLS-1$
                        + (linkTip != null ? "<br>" + CommonUtils.escapeHtml(linkTip) : "") + "</html>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            } else {
                // No active project
                setIcon(null);
                setToolTipText(null);
            }
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            if (size != null && secondaryText != null) {
                size.width += UiSizes.get(UiSizes.TREE_SECONDARY_GAP) + getFontMetrics(getFont()).stringWidth(secondaryText);
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
                + metrics.stringWidth(CommonUtils.notEmpty(getText())) + UiSizes.get(UiSizes.TREE_SECONDARY_GAP);
            int y = insets.top + (getHeight() - insets.top - insets.bottom - metrics.getHeight()) / 2 + metrics.getAscent();
            Color color = UIManager.getColor("Chromite.secondaryForeground"); //$NON-NLS-1$
            g.setColor(color != null ? color : getForeground());
            g.setFont(getFont());
            FlatUIUtils.drawString(this, g, secondaryText, x, y);
        }
    }
}
