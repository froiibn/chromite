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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.connection.DBPDriverDependencies;
import com.seizia.chromite.model.connection.DBPDriverLibrary;
import com.seizia.chromite.registry.driver.DriverDependencies;
import com.seizia.chromite.registry.driver.DriverUtils;
import com.seizia.chromite.ui.swing.internal.DriverManagerMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 드라이버 라이브러리 정보 창(SWT {@code DriverLibraryDetailsDialog}의 Swing 판): 드라이버, 라이브러리, 경로, 버전, 로컬 파일과
 * 라이브러리의 의존성 트리, 설명.
 *
 * 설계: SWT처럼 라이브러리 하나로 {@link DriverDependencies}를 만들어 {@code resolveDependencies}로 트리를 얻는다(SWT {@code DriverDependenciesTree}).
 * Maven 라이브러리는 이때 저장소에서 POM(메타데이터)을 읽는다. jar는 받지 않는다. 오프라인 모드에서는 모델이 원격 저장소를 막으므로 로컬 저장소에 있는 것만 보인다.
 * 그래서 창을 열 때가 아니라 Dependencies 탭의 단추로 읽는다(SWT는 창을 열자마자 읽어, 폐쇄망에서는 창이 늦게 떴다).
 * 진행 대화상자({@link ProgressDialog})에서 읽고, 일부만 풀렸으면 풀린 것과 오류를 함께 보인다.
 * 노드에는 이름, 버전과 상태(파일 있음, 다운로드 안 됨, 다른 가지에서 쓰는 중복)를 보인다. SWT의 버전 고르기(다운로드 대화상자 전용)와
 * 고정 문구뿐인 License 탭은 옮기지 않았다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-driver-manager.md "라이브러리 정보")
 */
final class DriverLibraryDetailsDialog extends JDialog {

    private static final Log log = Log.getLog(DriverLibraryDetailsDialog.class);

    private final DBPDriverLibrary library;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private final JLabel statusLabel = new JLabel(" ");

    private DriverLibraryDetailsDialog(@NotNull Window owner, @NotNull DBPDriver driver, @NotNull DBPDriverLibrary library) {
        super(owner, NLS.bind(DriverManagerMessages.driverdeps_title, library.getDisplayName()), ModalityType.APPLICATION_MODAL);
        this.library = library;
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel info = new JPanel(new GridBagLayout());
        int row = 0;
        addInfo(info, row++, DriverManagerMessages.driverdeps_driver, driver.getName());
        addInfo(info, row++, DriverManagerMessages.driverdeps_library, library.getDisplayName());
        addInfo(info, row++, DriverManagerMessages.driverdeps_path, library.getPath());
        addInfo(info, row++, DriverManagerMessages.driverdeps_version, CommonUtils.notEmpty(library.getVersion()));
        Path localFile = library.getLocalFile();
        addInfo(info, row, DriverManagerMessages.driverdeps_file, localFile == null ? "" : localFile.toAbsolutePath().toString()); //$NON-NLS-1$

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new DependencyRenderer());
        ToolTipManager.sharedInstance().registerComponent(tree);
        JButton resolveButton = new JButton(DriverManagerMessages.driverdeps_resolve);
        resolveButton.setToolTipText(DriverUtils.isOfflineMode() ? DriverManagerMessages.driverdeps_resolve_offline_tip
            : DriverManagerMessages.driverdeps_resolve_tip);
        resolveButton.addActionListener(e -> resolve());
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        statusLabel.setText(DriverManagerMessages.driverdeps_not_resolved);
        JPanel depsTop = new JPanel(new BorderLayout(8, 0));
        depsTop.add(statusLabel, BorderLayout.CENTER);
        depsTop.add(resolveButton, BorderLayout.EAST);
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setPreferredSize(new Dimension(560, 260));
        JPanel depsTab = new JPanel(new BorderLayout(0, 6));
        depsTab.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        depsTab.add(depsTop, BorderLayout.NORTH);
        depsTab.add(treeScroll, BorderLayout.CENTER);

        JTextArea descriptionArea = new JTextArea(CommonUtils.notEmpty(library.getDescription()), 6, 40);
        descriptionArea.setEditable(false);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        JScrollPane descriptionScroll = new JScrollPane(descriptionArea);
        descriptionScroll.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10), descriptionScroll.getBorder()));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(DriverManagerMessages.driverdeps_tab_dependencies, depsTab);
        tabs.addTab(DriverManagerMessages.driverdeps_tab_description, descriptionScroll);

        JButton closeButton = new JButton(DriverManagerMessages.drivermgr_button_close);
        closeButton.addActionListener(e -> dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        south.add(closeButton);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        // Dialog insets of docs/chromite/design-spec.md "11. 대화상자" (10, 12)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(info, BorderLayout.NORTH);
        content.add(tabs, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(closeButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the library details. Blocks until closed.
     */
    static void open(@NotNull Window owner, @NotNull DBPDriver driver, @NotNull DBPDriverLibrary library) {
        new DriverLibraryDetailsDialog(owner, driver, library).setVisible(true);
    }

    private static void addInfo(@NotNull JPanel panel, int row, @NotNull String label, @NotNull String value) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(3, 0, 3, 12);
        JTextField field = new JTextField(value);
        field.setEditable(false);
        JLabel labelComponent = new JLabel(label);
        labelComponent.setLabelFor(field);
        panel.add(labelComponent, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(3, 0, 3, 0);
        panel.add(field, c);
    }

    /**
     * Resolves the dependency tree in a progress dialog; a partial tree is shown with the error.
     */
    private void resolve() {
        DriverDependencies dependencies = new DriverDependencies(List.of(library));
        String error = null;
        try {
            ProgressDialog.runTask(DriverManagerMessages.driverdeps_task, monitor -> {
                dependencies.resolveDependencies(monitor);
                return null;
            });
        } catch (DBException e) {
            log.debug("Error resolving the dependencies of " + library.getDisplayName(), e);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            error = CommonUtils.notEmpty(cause.getMessage());
        }
        root.removeAllChildren();
        int count = 0;
        for (DBPDriverDependencies.DependencyNode node : dependencies.getLibraryMap()) {
            root.add(createNode(node));
        }
        for (DBPDriverDependencies.DependencyNode node : dependencies.getLibraryList()) {
            if (node.library != library) {
                count++;
            }
        }
        treeModel.reload();
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
        if (error != null) {
            statusLabel.setText(NLS.bind(DriverManagerMessages.driverdeps_error, error));
        } else {
            statusLabel.setText(count == 0 ? DriverManagerMessages.driverdeps_none : NLS.bind(DriverManagerMessages.driverdeps_count, count));
        }
    }

    @NotNull
    private static DefaultMutableTreeNode createNode(@NotNull DBPDriverDependencies.DependencyNode node) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
        if (!node.duplicate) {
            for (DBPDriverDependencies.DependencyNode child : node.dependencies) {
                treeNode.add(createNode(child));
            }
        }
        return treeNode;
    }

    /**
     * Name, version and state (file present, not downloaded, duplicate) of a dependency.
     */
    private static final class DependencyRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            if (((DefaultMutableTreeNode) value).getUserObject() instanceof DBPDriverDependencies.DependencyNode node) {
                DBPDriverLibrary dependency = node.library;
                setIcon(NodeIcons.getIcon(dependency.getIcon()));
                StringBuilder text = new StringBuilder(dependency.getDisplayName());
                if (!CommonUtils.isEmpty(dependency.getVersion())) {
                    text.append(" [").append(dependency.getVersion()).append(']'); //$NON-NLS-1$
                }
                text.append("  ").append(getState(node)); //$NON-NLS-1$
                setText(text.toString());
                Path file = dependency.getLocalFile();
                setToolTipText(file == null ? null : file.toAbsolutePath().toString());
            }
            return this;
        }

        @NotNull
        private static String getState(@NotNull DBPDriverDependencies.DependencyNode node) {
            if (node.duplicate) {
                return DriverManagerMessages.driverdeps_state_duplicate;
            }
            Path file = node.library.getLocalFile();
            return file != null && Files.exists(file) ? DriverManagerMessages.driverdeps_state_present
                : DriverManagerMessages.driverdeps_state_not_downloaded;
        }
    }
}
