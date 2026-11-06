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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPDataSourceFolder;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.navigator.DBNDataSource;
import org.jkiss.dbeaver.model.navigator.DBNLocalFolder;
import org.jkiss.dbeaver.model.navigator.DBNModel;
import org.jkiss.dbeaver.model.navigator.DBNProject;
import org.jkiss.dbeaver.model.navigator.DBNProjectDatabases;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.utils.AlphanumericComparator;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Registered Servers의 서버 그룹·서버 명령: 그룹 만들기·이름 바꾸기·삭제, 서버와 그룹 옮기기, 정렬, Object Explorer의 서버 고르기.
 *
 * 설계: 서버 그룹은 DBeaver 연결 폴더({@link DBPDataSourceFolder}, 중첩 가능)이고 서버는 연결({@link DBPDataSourceContainer})이다.
 * 새 저장 형식 없이 SWT 탐색기의 폴더 명령과 같은 모델 API를 같은 순서로 부른다. 바꾼 뒤에는 SWT처럼
 * {@link DBNModel#updateConfigAndRefreshDatabases}로 연결 설정 파일을 저장하고(flushConfig) 탐색기 모델에 알린다(연결 목록 STRUCT_REFRESH).
 * <ul>
 *     <li>그룹 만들기: {@code NavigatorHandlerLocalFolderCreate.createFolder}(ui.navigator, 89–99행)처럼 {@code addFolder} 뒤 저장.</li>
 *     <li>그룹 이름 바꾸기·그룹 옮기기: {@link DBNLocalFolder#rename}, {@link DBNLocalFolder#dropNodes}처럼
 *     {@code DBPDataSourceRegistry.moveFolder}(폴더 경로를 바꿈).</li>
 *     <li>그룹 삭제: {@code NavigatorObjectsDeleter.deleteLocalFolder}(ui.navigator, 234–237행)처럼 {@code removeFolder(folder, false)}.
 *     {@code DataSourceRegistry.removeFolderContents}(registry, 349–368행)에 따라 하위 그룹은 모두 지워지고, 그 안의 서버는 모두
 *     지운 그룹의 상위 그룹(맨 위 그룹이면 최상위)으로 옮겨진다. 서버(연결)는 지우지 않는다. 확인 질문에 이 결과를 적는다.</li>
 *     <li>서버 옮기기: {@link DBNDataSource#moveToFolder}(분산 모드면 그 자리에서 저장).</li>
 *     <li>그룹 이름: 비어 있으면 안 되고, 모델의 자원 이름 규칙({@link GeneralUtils#validateResourceNameUnconditionally}: 경로 구분자 / 등
 *     금지)을 따른다. 같은 상위 그룹에 같은 이름이 있으면 경로가 겹쳐 하나로 합쳐지므로 막는다.</li>
 *     <li>정렬: 이름의 자연 정렬({@link AlphanumericComparator}, 대소문자 무시)로 "01. DEV", "02. QAS", "10. ..." 순이 된다.
 *     DBeaver 탐색기의 이름 정렬({@code DBNUtils.NodeNameComparator})과 같은 비교기다.</li>
 * </ul>
 * EDT에서 부른다(레지스트리 변경은 메모리와 설정 파일만 건드린다).
 *
 * 변경 이력:
 * - 2026-11-06 Registered Servers: 최초 작성 (docs/chromite/swing-registered-servers.md)
 */
final class RegisteredServerCommands {

    /**
     * Natural order of names ("2" before "10"), ignoring case.
     */
    static final Comparator<String> NAME_ORDER = AlphanumericComparator.getInstance()::compareIgnoreCase;

    private RegisteredServerCommands() {
    }

    /**
     * The groups directly in a group (or the top-level groups), in name order.
     */
    @NotNull
    static List<DBPDataSourceFolder> getGroups(@NotNull DBPDataSourceRegistry registry, @Nullable DBPDataSourceFolder parent) {
        List<DBPDataSourceFolder> groups = new ArrayList<>();
        for (DBPDataSourceFolder folder : registry.getAllFolders()) {
            if (folder.getParent() == parent) {
                groups.add(folder);
            }
        }
        groups.sort(Comparator.comparing(DBPDataSourceFolder::getName, NAME_ORDER));
        return groups;
    }

    /**
     * The servers directly in a group (or at the top level), in name order; hidden connections are left out as in the navigator.
     */
    @NotNull
    static List<DBPDataSourceContainer> getServers(@NotNull DBPDataSourceRegistry registry, @Nullable DBPDataSourceFolder folder) {
        List<DBPDataSourceContainer> servers = new ArrayList<>();
        for (DBPDataSourceContainer container : registry.getDataSources()) {
            if (container.getFolder() == folder && !container.isHidden()) {
                servers.add(container);
            }
        }
        servers.sort(Comparator.comparing(DBPDataSourceContainer::getName, NAME_ORDER));
        return servers;
    }

    /**
     * New Server Group: asks for a name and adds the group.
     *
     * @return the new group, or null if canceled or failed
     */
    @Nullable
    static DBPDataSourceFolder newGroup(@NotNull Component parent, @NotNull DBPDataSourceRegistry registry, @Nullable DBPDataSourceFolder parentGroup) {
        String name = askGroupName(parent, "New Server Group",
            parentGroup == null ? "Group name:" : "Group name (in " + parentGroup.getName() + "):", "", registry, parentGroup);
        if (name == null) {
            return null;
        }
        DBPDataSourceFolder folder = registry.addFolder(parentGroup, name);
        updateConfig(registry);
        return folder;
    }

    static void renameGroup(@NotNull Component parent, @NotNull DBPDataSourceFolder folder) {
        DBPDataSourceRegistry registry = folder.getDataSourceRegistry();
        String name = askGroupName(parent, "Rename Server Group", "New name:", folder.getName(), registry, folder.getParent());
        if (name == null || name.equals(folder.getName())) {
            return;
        }
        DBNLocalFolder node = getFolderNode(folder);
        try {
            if (node != null) {
                // Moves the folder path and saves the configuration (DBNLocalFolder.rename)
                node.rename(new VoidProgressMonitor(), name);
            }
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError("Rename Server Group", "Can't rename group '" + folder.getName() + "'", e);
        }
    }

    /**
     * Asks for confirmation (saying where the servers go) and deletes a group with its subgroups; the servers are kept.
     */
    static void deleteGroup(@NotNull Component parent, @NotNull DBPDataSourceFolder folder) {
        int servers = countServers(folder);
        int subgroups = countSubgroups(folder);
        String target = folder.getParent() == null ? "the top level" : "'" + folder.getParent().getName() + "'";
        StringBuilder message = new StringBuilder("Delete server group '" + folder.getName() + "'?");
        if (subgroups > 0) {
            message.append("\n\nIts ").append(subgroups).append(subgroups == 1 ? " subgroup is" : " subgroups are").append(" deleted too.");
        }
        if (servers > 0) {
            message.append(subgroups > 0 ? "\n" : "\n\n").append(servers == 1 ? "The server" : "The " + servers + " servers")
                .append(" in it ").append(subgroups > 0 ? "(including subgroups) " : "")
                .append(servers == 1 ? "is" : "are").append(" not deleted: ").append(servers == 1 ? "it moves" : "they move")
                .append(" to ").append(target).append('.');
        }
        int answer = JOptionPane.showConfirmDialog(getWindow(parent), message.toString(), "Delete Server Group",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        DBPDataSourceRegistry registry = folder.getDataSourceRegistry();
        registry.removeFolder(folder, false);
        updateConfig(registry);
    }

    /**
     * Moves a server to a group (null: the top level).
     */
    static void moveServer(@NotNull DBPDataSourceContainer container, @Nullable DBPDataSourceFolder folder) {
        if (container.getFolder() == folder) {
            return;
        }
        DBNProjectDatabases databases = getDatabasesNode(container.getRegistry());
        DBNDataSource node = databases == null ? null : databases.getDataSource(container);
        if (node != null) {
            node.moveToFolder(container.getProject(), folder);
        } else {
            container.setFolder(folder);
        }
        updateConfig(container.getRegistry());
    }

    /**
     * True if a group may be moved into another group (null: the top level): not into itself or one of its subgroups.
     */
    static boolean canMoveGroup(@NotNull DBPDataSourceFolder folder, @Nullable DBPDataSourceFolder target) {
        return folder.getParent() != target && (target == null || folder.canMoveTo(target));
    }

    /**
     * Moves a group with its contents into another group (null: the top level).
     */
    static void moveGroup(@NotNull Component parent, @NotNull DBPDataSourceFolder folder, @Nullable DBPDataSourceFolder target) {
        if (!canMoveGroup(folder, target)) {
            return;
        }
        DBPDataSourceRegistry registry = folder.getDataSourceRegistry();
        if (findGroup(registry, target, folder.getName()) != null) {
            JOptionPane.showMessageDialog(getWindow(parent),
                (target == null ? "The top level" : "'" + target.getName() + "'") + " already has a group named '" + folder.getName() + "'.",
                "Move Server Group", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String newPath = target == null ? folder.getName() : target.getFolderPath() + "/" + folder.getName(); //$NON-NLS-1$
        try {
            registry.moveFolder(folder.getFolderPath(), newPath);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError("Move Server Group", "Can't move group '" + folder.getName() + "'", e);
            return;
        }
        updateConfig(registry);
    }

    /**
     * Object Explorer "Connect": the server to open, chosen among the registered servers (shown with their group path).
     *
     * @return the chosen server, or null if canceled or there is none
     */
    @Nullable
    static DBPDataSourceContainer chooseServer(@NotNull Component parent, @NotNull DBPDataSourceRegistry registry) {
        List<DBPDataSourceContainer> servers = new ArrayList<>();
        collectServers(registry, null, servers);
        if (servers.isEmpty()) {
            JOptionPane.showMessageDialog(getWindow(parent), "There are no registered servers. Create one with New Connection.",
                "Connect to Server", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        JComboBox<DBPDataSourceContainer> combo = new JComboBox<>(servers.toArray(new DBPDataSourceContainer[0]));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof DBPDataSourceContainer container) {
                    setText(getDisplayPath(container));
                    setIcon(NodeIcons.getDataSourceIcon(container));
                }
                return this;
            }
        });
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel("Server:"), BorderLayout.NORTH);
        panel.add(combo, BorderLayout.CENTER);
        int answer = JOptionPane.showConfirmDialog(getWindow(parent), panel, "Connect to Server",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return answer == JOptionPane.OK_OPTION ? (DBPDataSourceContainer) combo.getSelectedItem() : null;
    }

    /**
     * "Group / Subgroup / Server" (the server name alone at the top level).
     */
    @NotNull
    static String getDisplayPath(@NotNull DBPDataSourceContainer container) {
        DBPDataSourceFolder folder = container.getFolder();
        return folder == null ? container.getName() : folder.getFolderPath().replace("/", " / ") + " / " + container.getName(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The navigator node of a connection (rename uses it, as in Object Explorer).
     */
    @Nullable
    static DBNDataSource getServerNode(@NotNull DBPDataSourceContainer container) {
        DBNProjectDatabases databases = getDatabasesNode(container.getRegistry());
        return databases == null ? null : databases.getDataSource(container);
    }

    @Nullable
    private static DBNLocalFolder getFolderNode(@NotNull DBPDataSourceFolder folder) {
        DBNProjectDatabases databases = getDatabasesNode(folder.getDataSourceRegistry());
        return databases == null ? null : databases.getFolderNode(folder);
    }

    @Nullable
    private static DBNProjectDatabases getDatabasesNode(@NotNull DBPDataSourceRegistry registry) {
        DBNProject projectNode = DBWorkbench.getPlatform().getNavigatorModel().getRoot().getProjectNode(registry.getProject());
        return projectNode == null ? null : projectNode.getDatabases();
    }

    /**
     * Saves the connection configuration and tells the navigator model (DBNModel.updateConfigAndRefreshDatabases).
     */
    private static void updateConfig(@NotNull DBPDataSourceRegistry registry) {
        DBNProjectDatabases databases = getDatabasesNode(registry);
        if (databases != null) {
            DBNModel.updateConfigAndRefreshDatabases(databases);
        } else {
            registry.flushConfig();
        }
    }

    private static void collectServers(@NotNull DBPDataSourceRegistry registry, @Nullable DBPDataSourceFolder folder, @NotNull List<DBPDataSourceContainer> result) {
        for (DBPDataSourceFolder group : getGroups(registry, folder)) {
            collectServers(registry, group, result);
        }
        result.addAll(getServers(registry, folder));
    }

    private static int countServers(@NotNull DBPDataSourceFolder folder) {
        List<DBPDataSourceContainer> servers = new ArrayList<>();
        collectServers(folder.getDataSourceRegistry(), folder, servers);
        return servers.size();
    }

    private static int countSubgroups(@NotNull DBPDataSourceFolder folder) {
        int count = 0;
        for (DBPDataSourceFolder child : folder.getChildren()) {
            count += 1 + countSubgroups(child);
        }
        return count;
    }

    @Nullable
    private static DBPDataSourceFolder findGroup(@NotNull DBPDataSourceRegistry registry, @Nullable DBPDataSourceFolder parent, @NotNull String name) {
        for (DBPDataSourceFolder group : getGroups(registry, parent)) {
            if (group.getName().equalsIgnoreCase(name)) {
                return group;
            }
        }
        return null;
    }

    /**
     * Asks for a group name until it is valid and not used in the parent group.
     *
     * @return the name, or null if canceled
     */
    @Nullable
    private static String askGroupName(
        @NotNull Component parent,
        @NotNull String title,
        @NotNull String prompt,
        @NotNull String initial,
        @NotNull DBPDataSourceRegistry registry,
        @Nullable DBPDataSourceFolder parentGroup
    ) {
        String value = initial;
        while (true) {
            Object input = JOptionPane.showInputDialog(getWindow(parent), prompt, title, JOptionPane.PLAIN_MESSAGE, null, null, value);
            if (input == null) {
                return null;
            }
            value = input.toString().trim();
            String problem = null;
            if (value.isEmpty()) {
                problem = "Enter a group name.";
            } else {
                try {
                    GeneralUtils.validateResourceNameUnconditionally(value);
                } catch (DBException e) {
                    problem = e.getMessage();
                }
                DBPDataSourceFolder existing = findGroup(registry, parentGroup, value);
                if (problem == null && existing != null && !Objects.equals(existing.getName(), initial)) {
                    problem = "There is already a group named '" + existing.getName() + "' here.";
                }
            }
            if (problem == null) {
                return value;
            }
            JOptionPane.showMessageDialog(getWindow(parent), problem, title, JOptionPane.WARNING_MESSAGE);
        }
    }

    @Nullable
    private static Window getWindow(@NotNull Component component) {
        return component instanceof Window window ? window : SwingUtilities.getWindowAncestor(component);
    }
}
