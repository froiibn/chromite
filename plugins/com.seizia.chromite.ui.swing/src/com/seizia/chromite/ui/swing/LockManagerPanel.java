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
import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.admin.locks.DBAServerLock;
import org.jkiss.dbeaver.model.admin.locks.DBAServerLockItem;
import org.jkiss.dbeaver.model.admin.locks.DBAServerLockManager;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Activity Monitor의 Locks 탭: 잠금을 기다리는 세션과 막는 세션, 각 세션의 잠금.
 *
 * 설계: SWT의 {@code LockManagerViewer}(잠금 표, 잠금 그래프, 대기·보유 세션의 잠금 상세 표 둘, 기다리는 세션 끝내기)를 옮겼다.
 * 모델 API({@code DBAServerLockManager#getLocks}, {@code #getLockItems}, {@code #alterSession})를 그대로 쓰고,
 * 상세 표의 옵션(어느 세션의 잠금인가)은 SWT 잠금 편집기마다 다르던 것을 {@code ServerAdminProfile#getLockItemOptions}가 준다.
 * <ul>
 *     <li>GEF 잠금 그래프 대신 나무로 보인다: 뿌리는 남을 막기만 하는 세션(head blocker), 자식은 그 세션을 기다리는 세션이다.
 *     관계는 잠금 관리자가 {@code LockGraphManager#buildGraphs}로 채운 {@link DBAServerLock#getHoldBy()}/{@link DBAServerLock#waitThis()}다.</li>
 *     <li>나무와 표의 선택은 서로 따라간다. 고른 잠금의 세션(기다리는 쪽)과 그 세션을 막는 세션의 잠금을 아래 두 표에 읽는다.</li>
 *     <li>"Kill Session"은 SWT의 "Kill waiting session"처럼 고른 행의 세션을 확인 후 끝낸다(뿌리를 고르면 막는 세션).</li>
 *     <li>잠금 관리자가 없는 DB(DBeaver·Chromite 모두 없음)는 안내만 보인다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 */
final class LockManagerPanel extends JPanel {

    private static final Log log = Log.getLog(LockManagerPanel.class);
    private static final String PREF_REFRESH = "activityMonitor.locks.refreshSeconds"; //$NON-NLS-1$

    private final AdminConnection connection;
    private final ObjectListTable<DBAServerLock> lockTable;
    private final ObjectListTable<DBAServerLockItem> waitingItems;
    private final ObjectListTable<DBAServerLockItem> holdingItems;
    private final JLabel waitingLabel = new JLabel(" ");
    private final JLabel holdingLabel = new JLabel(" ");
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree lockTree = new JTree(treeModel) {
        @Override
        public void updateUI() {
            super.updateUI();
            // The custom renderer is not in the component tree: take the colors of the new theme
            if (getCellRenderer() instanceof LockTreeRenderer renderer) {
                renderer.updateUI();
            }
        }
    };
    private final JLabel statusLabel = new JLabel(" ");
    private final AutoRefreshBox autoRefresh;
    private final Action killAction;
    private boolean loading;
    private boolean syncingSelection;
    @Nullable
    private DBAServerLock currentLock;
    private int detailSerial;

    LockManagerPanel(@NotNull AdminConnection connection) {
        super(new BorderLayout());
        this.connection = connection;
        lockTable = new ObjectListTable<>(DBAServerLock::getId, connection.getContainer());
        lockTable.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lockTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !syncingSelection) {
                lockSelected(lockTable.getSelectedObject());
            }
        });
        waitingItems = new ObjectListTable<>(item -> item, connection.getContainer());
        holdingItems = new ObjectListTable<>(item -> item, connection.getContainer());

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(3), UIScale.scale(8), UIScale.scale(3), UIScale.scale(8)));
        Action refreshAction = new AbstractAction(ConnectionMessages.common_refresh) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                refresh();
            }
        };
        refreshAction.putValue(Action.SHORT_DESCRIPTION, ConnectionMessages.locks_refresh_tip);
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$
        autoRefresh = new AutoRefreshBox(PREF_REFRESH, this, () -> {
            // Not after a failed connect: that would ask for the password again at each interval
            if (!loading && connection.getContainer().isConnected()) {
                refresh();
            }
        });
        autoRefresh.setMaximumSize(autoRefresh.getPreferredSize());
        toolBar.add(autoRefresh);
        toolBar.addSeparator();
        killAction = new AbstractAction(ConnectionMessages.locks_kill) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                killSelectedSession();
            }
        };
        killAction.putValue(Action.SHORT_DESCRIPTION, ConnectionMessages.locks_kill_tip);
        killAction.setEnabled(false);
        toolBar.add(UiIcons.createToolButton(killAction, "disconnect")); //$NON-NLS-1$

        lockTree.setRootVisible(false);
        lockTree.setShowsRootHandles(true);
        lockTree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        lockTree.putClientProperty(FlatClientProperties.TREE_WIDE_SELECTION, true);
        lockTree.setCellRenderer(new LockTreeRenderer());
        lockTree.addTreeSelectionListener(e -> {
            if (syncingSelection) {
                return;
            }
            TreePath path = lockTree.getSelectionPath();
            if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node
                && node.getUserObject() instanceof DBAServerLock lock) {
                lockSelected(lock);
            }
        });
        JScrollPane treeScroll = new JScrollPane(lockTree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel treePanel = new JPanel(new BorderLayout());
        treePanel.add(createHeader(ConnectionMessages.locks_blocking_chains), BorderLayout.NORTH);
        treePanel.add(treeScroll, BorderLayout.CENTER);

        JScrollPane tableScroll = new JScrollPane(lockTable);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel itemsPanel = new JPanel(new GridLayout(1, 2, UIScale.scale(6), 0));
        itemsPanel.add(createItemsPanel(waitingLabel, waitingItems));
        itemsPanel.add(createItemsPanel(holdingLabel, holdingItems));
        // Chains and lock rows side by side on top, the locks of the two sessions across the whole width below
        JSplitPane topSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, tableScroll);
        topSplit.setResizeWeight(0.3);
        topSplit.setContinuousLayout(true);
        topSplit.setBorder(BorderFactory.createEmptyBorder());
        topSplit.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(260)));
        itemsPanel.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(240)));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topSplit, itemsPanel);
        split.setResizeWeight(0.5);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder());

        statusLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(10), UIScale.scale(4), UIScale.scale(10)));
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$

        add(toolBar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    @NotNull
    private static JComponent createHeader(@NotNull String text) {
        JLabel label = new JLabel(text);
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
        label.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(8), UIScale.scale(4), UIScale.scale(8)));
        return label;
    }

    @NotNull
    private static JComponent createItemsPanel(@NotNull JLabel label, @NotNull ObjectListTable<DBAServerLockItem> table) {
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
        label.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(8), UIScale.scale(4), UIScale.scale(8)));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(label, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Reads the locks (connecting first if needed).
     */
    void refresh() {
        loading = true;
        statusLabel.setText(connection.getContainer().isConnected() ? ConnectionMessages.locks_reading : ConnectionMessages.common_connecting);
        connection.run("Read server locks", (monitor, session, profile) -> {
            DBAServerLockManager<DBAServerLock, DBAServerLockItem> manager = profile.getLockManager();
            if (manager == null) {
                return null;
            }
            Map<?, DBAServerLock> locks = manager.getLocks(session, null);
            return new LockList(new ArrayList<>(locks.values()), manager.getLocksType());
        }, this::showLocks, error -> {
            loading = false;
            statusLabel.setText(NLS.bind(ConnectionMessages.common_status_error, ErrorText.describeLine(error)));
            statusLabel.setToolTipText(ErrorText.describeLine(error));
            log.debug("Error reading locks", error);
        });
    }

    void dispose() {
        autoRefresh.dispose();
    }

    private record LockList(@NotNull List<DBAServerLock> locks, @NotNull Class<?> type) {
    }

    private void showLocks(@Nullable LockList lockList) {
        loading = false;
        if (lockList == null) {
            autoRefresh.dispose();
            autoRefresh.setEnabled(false);
            killAction.setEnabled(false);
            statusLabel.setText(NLS.bind(ConnectionMessages.locks_not_available, connection.getContainer().getDriver().getName()));
            return;
        }
        Object selectedId = currentLock != null ? currentLock.getId() : null;
        syncingSelection = true;
        try {
            lockTable.setObjects(lockList.locks(), lockList.type());
            fillTree(lockList.locks());
        } finally {
            syncingSelection = false;
        }
        DBAServerLock selected = null;
        for (DBAServerLock lock : lockList.locks()) {
            if (selectedId != null && selectedId.equals(lock.getId())) {
                selected = lock;
            }
        }
        lockSelected(selected);
        int waiting = 0;
        for (DBAServerLock lock : lockList.locks()) {
            if (lock.getHoldBy() != null) {
                waiting++;
            }
        }
        String text = lockList.locks().isEmpty() ? ConnectionMessages.locks_none_waiting
            : NLS.bind(waiting == 1 ? ConnectionMessages.locks_waiting_one : ConnectionMessages.locks_waiting_many, waiting);
        statusLabel.setText(NLS.bind(ConnectionMessages.common_status_updated, text, DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date())));
        statusLabel.setToolTipText(null);
    }

    /**
     * Blocking chains: the sessions that block others without waiting themselves, with the sessions waiting for them below.
     */
    private void fillTree(@NotNull List<DBAServerLock> locks) {
        treeRoot.removeAllChildren();
        Set<DBAServerLock> added = new HashSet<>();
        for (DBAServerLock lock : locks) {
            if (lock.getHoldBy() == null) {
                treeRoot.add(createTreeNode(lock, added));
            }
        }
        if (treeRoot.getChildCount() == 0 && !locks.isEmpty()) {
            // Only a cycle (deadlock being resolved): show the rows as they are
            for (DBAServerLock lock : locks) {
                if (!added.contains(lock)) {
                    treeRoot.add(createTreeNode(lock, added));
                }
            }
        }
        treeModel.reload();
        for (int row = 0; row < lockTree.getRowCount(); row++) {
            lockTree.expandRow(row);
        }
    }

    @NotNull
    private static DefaultMutableTreeNode createTreeNode(@NotNull DBAServerLock lock, @NotNull Set<DBAServerLock> added) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(lock);
        added.add(lock);
        for (DBAServerLock waiter : lock.waitThis()) {
            // Cycles are possible (deadlock): each lock once
            if (!added.contains(waiter)) {
                node.add(createTreeNode(waiter, added));
            }
        }
        return node;
    }

    private void lockSelected(@Nullable DBAServerLock lock) {
        currentLock = lock;
        killAction.setEnabled(lock != null);
        syncingSelection = true;
        try {
            if (lock != lockTable.getSelectedObject()) {
                lockTable.selectObject(lock);
            }
            selectInTree(lock);
        } finally {
            syncingSelection = false;
        }
        loadItems(lock);
    }

    private void selectInTree(@Nullable DBAServerLock lock) {
        if (lock == null) {
            lockTree.clearSelection();
            return;
        }
        java.util.Enumeration<javax.swing.tree.TreeNode> nodes = treeRoot.breadthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            if (nodes.nextElement() instanceof DefaultMutableTreeNode node && node.getUserObject() == lock) {
                TreePath path = new TreePath(node.getPath());
                lockTree.setSelectionPath(path);
                lockTree.scrollPathToVisible(path);
                return;
            }
        }
        lockTree.clearSelection();
    }

    /**
     * Reads the locks of the selected session and of the session blocking it (SWT: blocked / blocking detail tables).
     */
    private void loadItems(@Nullable DBAServerLock lock) {
        int serial = ++detailSerial;
        if (lock == null) {
            waitingLabel.setText(" ");
            holdingLabel.setText(" ");
            waitingItems.clearObjects();
            holdingItems.clearObjects();
            return;
        }
        DBAServerLock holder = lock.getHoldBy();
        waitingLabel.setText(NLS.bind(holder != null ? ConnectionMessages.locks_of_session_waiting : ConnectionMessages.locks_of_session_blocking,
            lock.getTitle()));
        holdingLabel.setText(holder != null ? NLS.bind(ConnectionMessages.locks_of_blocking_session, holder.getTitle()) : " "); //$NON-NLS-1$
        connection.run("Read session locks", (monitor, session, profile) -> {
            DBAServerLockManager<DBAServerLock, DBAServerLockItem> manager = profile.getLockManager();
            if (manager == null) {
                return null;
            }
            List<Collection<DBAServerLockItem>> items = new ArrayList<>();
            items.add(new ArrayList<>(manager.getLockItems(session, profile.getLockItemOptions(lock, false))));
            items.add(holder != null ? new ArrayList<>(manager.getLockItems(session, profile.getLockItemOptions(lock, true))) : List.of());
            return items;
        }, items -> {
            if (serial != detailSerial || items == null) {
                return;
            }
            showItems(waitingItems, items.get(0));
            showItems(holdingItems, items.get(1));
        }, error -> {
            if (serial == detailSerial) {
                statusLabel.setText(NLS.bind(ConnectionMessages.common_status_error, ErrorText.describeLine(error)));
                log.debug("Error reading lock items", error);
            }
        });
    }

    private static void showItems(@NotNull ObjectListTable<DBAServerLockItem> table, @NotNull Collection<DBAServerLockItem> items) {
        if (items.isEmpty()) {
            table.clearObjects();
        } else {
            table.setObjects(items, items.iterator().next().getClass());
        }
    }

    private void killSelectedSession() {
        DBAServerLock lock = currentLock;
        if (lock == null) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
            NLS.bind(ConnectionMessages.locks_kill_confirm, lock.getTitle()),
            ConnectionMessages.locks_kill, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        connection.run("Kill session", (monitor, session, profile) -> {
            DBAServerLockManager<DBAServerLock, DBAServerLockItem> manager = profile.getLockManager();
            if (manager == null) {
                throw new DBException("No lock manager"); //$NON-NLS-1$
            }
            manager.alterSession(session, lock, null);
            return null;
        }, result -> refresh(), error -> {
            DBWorkbench.getPlatformUI().showError(ConnectionMessages.locks_kill, NLS.bind(ConnectionMessages.locks_kill_error, lock.getTitle()), error);
            refresh();
        });
    }

    /**
     * "Session 52" with what it does in the chain: blocking others (top) or waiting for its parent.
     */
    private static final class LockTreeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof DBAServerLock lock) {
                DBAServerLock holder = lock.getHoldBy();
                setText(holder == null ? NLS.bind(ConnectionMessages.locks_tree_blocking, lock.getTitle(), lock.waitThis().size())
                    : NLS.bind(ConnectionMessages.locks_tree_waiting, lock.getTitle(), holder.getTitle()));
                setIcon(UiIcons.get(holder == null ? "warning" : "user")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return this;
        }
    }
}
