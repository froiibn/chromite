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
import com.seizia.chromite.ui.swing.internal.DriverManagerMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPDataSourceProviderDescriptor;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.registry.DataSourceProviderDescriptor;
import org.jkiss.dbeaver.registry.DataSourceProviderRegistry;
import org.jkiss.dbeaver.registry.DataSourceRegistry;
import org.jkiss.dbeaver.registry.driver.DriverDescriptor;
import org.jkiss.dbeaver.registry.driver.DriverUtils;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tools &gt; Driver Manager…: 드라이버 목록(범주별 트리, 검색)과 New / Copy / Edit / Delete / Restore Deleted, 오프라인 모드 설정.
 *
 * 설계: SWT {@code DriverManagerDialog}(ui.editors.connection, DriverManagerDialog.java)를 모델 API 그대로 옮겼다.
 * <ul>
 *     <li>목록: SWT {@code DriverTreeViewer.collectDrivers}처럼 활성 공급자의 사용 가능한 드라이버({@code getEnabledDrivers}: 꺼짐·대체됨·이 OS 미지원 제외)를
 *     드라이버의 {@code getCategory()}로 묶는다. 범주가 없는 드라이버는 맨 위 수준에 범주와 함께 이름순으로 둔다. 상용 드라이버 자리표시
 *     ({@code DBPDriverWithLicense})와 연결을 만들 수 없는 것은 Connect 대화상자와 같은 규칙({@link DriverSelectionPanel#getConnectableDrivers()})으로 뺀다.
 *     검색은 대소문자·공백을 무시하고 이름·설명·범주·ID에서 찾고, 찾는 동안에는 범주를 모두 펼친다.</li>
 *     <li>아이콘은 모델의 드라이버 아이콘({@code DriverDescriptor.getIcon()}: 사용자 정의는 전구, 로드 실패는 오류 덧그림)이고 아래에 범례를 둔다(SWT와 같음).</li>
 *     <li>단추 조건(SWT {@code updateButtons}): New는 드라이버를 관리할 수 있는 공급자가 있을 때, Copy는 고른 드라이버의 공급자가 관리할 수 있을 때,
 *     Edit는 드라이버를 고르면, Delete는 공급자가 관리할 수 있을 때. 새 드라이버의 공급자는 고른 드라이버의 공급자(관리 가능할 때), 아니면
 *     {@code generic}, 그것도 아니면 관리 가능한 첫 공급자다.</li>
 *     <li>Delete(SWT {@code deleteDriver}): 그 드라이버를 쓰는 연결이 있으면 막고 연결 이름을 보여 준다. 사용자 정의 드라이버는 지우고,
 *     기본 제공 드라이버는 숨긴다({@code DataSourceProviderDescriptor.removeDriver}). Restore Deleted(SWT {@code undeleteDrivers})로 숨긴 드라이버를 되살린다.
 *     둘 다 {@code saveDrivers()}로 {@code drivers.xml}에 바로 저장한다.</li>
 *     <li>오프라인 모드: SWT는 이 대화상자에서 환경설정 > 드라이버 페이지로 가는 링크를 두는데, Swing 앱에는 그 페이지가 없어
 *     같은 설정({@link ModelPreferences#UI_DRIVERS_OFFLINE})을 체크 상자로 둔다. 모델은 값을 매번 읽으므로 바로 적용된다(docs/chromite/drivers.md).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-driver-manager.md)
 */
final class DriverManagerDialog extends JDialog {

    private static final Log log = Log.getLog(DriverManagerDialog.class);

    // SWT DriverManagerDialog.DEFAULT_DS_PROVIDER
    private static final String DEFAULT_PROVIDER_ID = "generic"; //$NON-NLS-1$

    private final JTextField searchField = new JTextField();
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private final JButton newButton = new JButton(DriverManagerMessages.drivermgr_button_new);
    private final JButton copyButton = new JButton(DriverManagerMessages.drivermgr_button_copy);
    private final JButton editButton = new JButton(DriverManagerMessages.drivermgr_button_edit);
    private final JButton deleteButton = new JButton(DriverManagerMessages.drivermgr_button_delete);
    private final JLabel descriptionLabel = new JLabel(" ");

    private DriverManagerDialog(@Nullable Window owner) {
        super(owner, DriverManagerMessages.drivermgr_title, ModalityType.APPLICATION_MODAL);
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        // Drivers contributed at run time (driver providers) are listed too (SWT does the same on open)
        DataSourceProviderRegistry.getInstance().refreshProvidedDrivers();

        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, DriverManagerMessages.drivermgr_search);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                reload(getSelectedDriver());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                reload(getSelectedDriver());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                reload(getSelectedDriver());
            }
        });

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new DriverRenderer());
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.getSelectionModel().addTreeSelectionListener(e -> updateButtons());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && getSelectedDriver() != null) {
                    editDriver();
                }
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chromite.editDriver"); //$NON-NLS-1$
        tree.getActionMap().put("chromite.editDriver", new AbstractAction() { //$NON-NLS-1$
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (getSelectedDriver() != null) {
                    editDriver();
                }
            }
        });

        newButton.addActionListener(e -> createDriver());
        copyButton.addActionListener(e -> copyDriver());
        editButton.addActionListener(e -> editDriver());
        deleteButton.addActionListener(e -> deleteDriver());
        JButton restoreButton = new JButton(DriverManagerMessages.drivermgr_button_restore);
        restoreButton.addActionListener(e -> restoreDrivers());

        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        buttons.add(newButton);
        buttons.add(copyButton);
        buttons.add(editButton);
        buttons.add(deleteButton);
        buttons.add(restoreButton);
        JPanel legend = new JPanel(new GridLayout(0, 1, 0, 4));
        legend.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        legend.add(createLegend(DBIcon.OVER_LAMP, DriverManagerMessages.drivermgr_legend_custom));
        legend.add(createLegend(DBIcon.OVER_ERROR, DriverManagerMessages.drivermgr_legend_unavailable));
        // Buttons and legend keep their preferred heights at the top (the dialog may be taller)
        JPanel eastTop = new JPanel(new BorderLayout());
        eastTop.add(buttons, BorderLayout.NORTH);
        eastTop.add(legend, BorderLayout.CENTER);
        JPanel east = new JPanel(new BorderLayout());
        east.add(eastTop, BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setPreferredSize(new Dimension(460, 420));
        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.add(searchField, BorderLayout.NORTH);
        center.add(scrollPane, BorderLayout.CENTER);
        descriptionLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        center.add(descriptionLabel, BorderLayout.SOUTH);

        JCheckBox offlineCheck = new JCheckBox(DriverManagerMessages.drivermgr_offline, DriverUtils.isOfflineMode());
        offlineCheck.setToolTipText(DriverManagerMessages.drivermgr_offline_tip);
        offlineCheck.addActionListener(e -> setOfflineMode(offlineCheck.isSelected()));
        JButton closeButton = new JButton(DriverManagerMessages.drivermgr_button_close);
        closeButton.addActionListener(e -> dispose());
        JPanel south = new JPanel(new BorderLayout(12, 0));
        south.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        south.add(offlineCheck, BorderLayout.WEST);
        south.add(closeButton, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(10, 0));
        // Dialog insets of docs/chromite/design-spec.md "11. 대화상자" (10, 12)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(center, BorderLayout.CENTER);
        content.add(east, BorderLayout.EAST);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(closeButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        reload(null);
        pack();
        setMinimumSize(new Dimension(520, 400));
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(searchField::requestFocusInWindow);
    }

    /**
     * Tools &gt; Driver Manager…. Blocks until the dialog is closed.
     */
    static void open(@Nullable Window owner) {
        new DriverManagerDialog(owner).setVisible(true);
    }

    @NotNull
    private static JLabel createLegend(@NotNull DBIcon overlay, @NotNull String text) {
        JLabel label = new JLabel(text, NodeIcons.getIcon(overlay), SwingConstants.LEADING);
        label.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        return label;
    }

    //////////////////////////////////////////////////////////
    // Tree

    /**
     * Rebuilds the tree for the search text and selects the driver (its category expanded).
     */
    private void reload(@Nullable DBPDriver select) {
        String pattern = normalize(searchField.getText());
        Map<String, DefaultMutableTreeNode> categories = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<DefaultMutableTreeNode> topLevel = new ArrayList<>();
        List<DBPDriver> drivers = new ArrayList<>(DriverSelectionPanel.getConnectableDrivers());
        drivers.sort(Comparator.comparing(DBPDriver::getName, String.CASE_INSENSITIVE_ORDER));
        for (DBPDriver driver : drivers) {
            if (!pattern.isEmpty() && !matches(driver, pattern)) {
                continue;
            }
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(driver);
            @SuppressWarnings("deprecation")
            String category = driver.getCategory();
            if (CommonUtils.isEmpty(category)) {
                topLevel.add(node);
            } else {
                categories.computeIfAbsent(category, name -> {
                    DefaultMutableTreeNode categoryNode = new DefaultMutableTreeNode(name);
                    topLevel.add(categoryNode);
                    return categoryNode;
                }).add(node);
            }
        }
        // Categories and plain drivers together by name (SWT DriverTreeViewer sorts them the same way)
        topLevel.sort(Comparator.comparing(DriverManagerDialog::getNodeName, String.CASE_INSENSITIVE_ORDER));
        root.removeAllChildren();
        topLevel.forEach(root::add);
        treeModel.reload();
        if (!pattern.isEmpty()) {
            for (int row = 0; row < tree.getRowCount(); row++) {
                tree.expandRow(row);
            }
        }
        TreePath selectPath = select == null ? null : findPath(select);
        if (selectPath == null && !pattern.isEmpty()) {
            // The first matching driver, so that Enter edits it
            selectPath = findFirstDriverPath();
        }
        if (selectPath != null) {
            tree.setSelectionPath(selectPath);
            tree.scrollPathToVisible(selectPath);
        }
        updateButtons();
    }

    @NotNull
    private static String getNodeName(@NotNull DefaultMutableTreeNode node) {
        return node.getUserObject() instanceof DBPDriver driver ? driver.getName() : String.valueOf(node.getUserObject());
    }

    @Nullable
    private TreePath findPath(@NotNull DBPDriver driver) {
        for (var nodes = root.depthFirstEnumeration(); nodes.hasMoreElements(); ) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() == driver) {
                return new TreePath(node.getPath());
            }
        }
        return null;
    }

    @Nullable
    private TreePath findFirstDriverPath() {
        for (var nodes = root.preorderEnumeration(); nodes.hasMoreElements(); ) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() instanceof DBPDriver) {
                return new TreePath(node.getPath());
            }
        }
        return null;
    }

    private static boolean matches(@NotNull DBPDriver driver, @NotNull String pattern) {
        @SuppressWarnings("deprecation")
        String category = driver.getCategory();
        if (normalize(driver.getName()).contains(pattern) || normalize(driver.getDescription()).contains(pattern)
            || normalize(category).contains(pattern) || normalize(driver.getId()).contains(pattern)) {
            return true;
        }
        return driver.getCategories().stream().anyMatch(item -> normalize(item).contains(pattern));
    }

    @NotNull
    private static String normalize(@Nullable String text) {
        return CommonUtils.notEmpty(text).toLowerCase(Locale.ENGLISH).replaceAll("\\s+", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Nullable
    private DriverDescriptor getSelectedDriver() {
        TreePath path = tree.getSelectionPath();
        Object value = path == null ? null : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return value instanceof DriverDescriptor driver ? driver : null;
    }

    @Nullable
    private String getSelectedCategory() {
        TreePath path = tree.getSelectionPath();
        Object value = path == null ? null : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return value instanceof String category ? category : null;
    }

    private void updateButtons() {
        DriverDescriptor driver = getSelectedDriver();
        newButton.setEnabled(getProviderForNewDriver() != null);
        copyButton.setEnabled(driver != null && driver.isManageable());
        editButton.setEnabled(driver != null);
        deleteButton.setEnabled(driver != null && driver.getProviderDescriptor().isDriversManageable());
        String category = getSelectedCategory();
        String description = driver != null ? CommonUtils.notEmpty(driver.getDescription())
            : category != null ? NLS.bind(DriverManagerMessages.drivermgr_category_description, category) : ""; //$NON-NLS-1$
        descriptionLabel.setText(description.isEmpty() ? " " : description); //$NON-NLS-1$
        descriptionLabel.setToolTipText(description.isEmpty() ? null : description);
    }

    //////////////////////////////////////////////////////////
    // Commands

    /**
     * The provider of a new driver: the selected driver's (when it manages drivers), else "generic", else the first
     * provider that manages drivers.
     */
    @Nullable
    private DataSourceProviderDescriptor getProviderForNewDriver() {
        DriverDescriptor selected = getSelectedDriver();
        if (selected != null && selected.getProviderDescriptor().isDriversManageable()) {
            return selected.getProviderDescriptor();
        }
        DataSourceProviderRegistry registry = DataSourceProviderRegistry.getInstance();
        DataSourceProviderDescriptor generic = registry.getDataSourceProvider(DEFAULT_PROVIDER_ID);
        if (generic != null && generic.isDriversManageable()) {
            return generic;
        }
        for (DBPDataSourceProviderDescriptor provider : registry.getEnabledDataSourceProviders()) {
            if (provider.isDriversManageable() && provider instanceof DataSourceProviderDescriptor descriptor) {
                return descriptor;
            }
        }
        return null;
    }

    private void createDriver() {
        DataSourceProviderDescriptor provider = getProviderForNewDriver();
        if (provider == null) {
            return;
        }
        DriverDescriptor created = DriverEditorDialog.openNew(this, provider);
        if (created != null) {
            searchField.setText(""); //$NON-NLS-1$
            reload(created);
        }
    }

    private void copyDriver() {
        DriverDescriptor driver = getSelectedDriver();
        if (driver == null) {
            return;
        }
        DriverDescriptor copy = DriverEditorDialog.openCopy(this, driver);
        if (copy != null) {
            searchField.setText(""); //$NON-NLS-1$
            reload(copy);
        }
    }

    private void editDriver() {
        DriverDescriptor selected = getSelectedDriver();
        if (selected == null) {
            return;
        }
        // SWT edits the provider's current driver of that ID (it may have been replaced) and copies the result back
        DBPDriver driver = selected.getProviderDescriptor().getDriver(selected.getId());
        if (!(driver instanceof DriverDescriptor driverDescriptor)) {
            log.warn("Driver not found for ID '" + selected.getId() + "' in provider '" + selected.getProviderDescriptor().getId() + "'");
            return;
        }
        boolean changed = DriverEditorDialog.openEdit(this, driverDescriptor);
        if (changed && selected != driverDescriptor) {
            selected.applyFrom(driverDescriptor);
        }
        reload(selected);
    }

    private void deleteDriver() {
        DriverDescriptor driver = getSelectedDriver();
        if (driver == null) {
            return;
        }
        List<DBPDataSourceContainer> usedBy = DriverUtils.getUsedBy(driver, DataSourceRegistry.getAllDataSources());
        if (!usedBy.isEmpty()) {
            StringBuilder message = new StringBuilder(NLS.bind(DriverManagerMessages.drivermgr_delete_used, driver.getName()));
            for (DBPDataSourceContainer dataSource : usedBy) {
                message.append("\n - ").append(dataSource.getName()); //$NON-NLS-1$
            }
            JOptionPane.showMessageDialog(this, message.toString(), DriverManagerMessages.drivermgr_delete_used_title, JOptionPane.ERROR_MESSAGE);
            return;
        }
        String question = NLS.bind(driver.isCustom() ? DriverManagerMessages.drivermgr_delete_custom : DriverManagerMessages.drivermgr_delete_builtin,
            driver.getName());
        if (JOptionPane.showConfirmDialog(this, question, DriverManagerMessages.drivermgr_delete_title, JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        // Custom drivers are removed, predefined ones only disabled (DataSourceProviderDescriptor.removeDriver)
        driver.getProviderDescriptor().removeDriver(driver);
        saveDrivers();
        reload(null);
    }

    /**
     * SWT {@code undeleteDrivers}: the disabled drivers of the enabled providers, checked ones enabled again.
     */
    private void restoreDrivers() {
        List<DriverDescriptor> deleted = new ArrayList<>();
        for (DBPDataSourceProviderDescriptor provider : DataSourceProviderRegistry.getInstance().getEnabledDataSourceProviders()) {
            for (DBPDriver driver : provider.getDrivers()) {
                if (driver.isDisabled() && driver instanceof DriverDescriptor descriptor) {
                    deleted.add(descriptor);
                }
            }
        }
        if (deleted.isEmpty()) {
            JOptionPane.showMessageDialog(this, DriverManagerMessages.drivermgr_restore_none, DriverManagerMessages.drivermgr_restore_title,
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        deleted.sort(Comparator.comparing(DBPDriver::getName, String.CASE_INSENSITIVE_ORDER));
        JList<DriverDescriptor> list = new JList<>(deleted.toArray(new DriverDescriptor[0]));
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> jList, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(jList, value, index, isSelected, cellHasFocus);
                if (value instanceof DriverDescriptor driver) {
                    setText(driver.getName());
                    setIcon(NodeIcons.getIcon(driver.getPlainIcon()));
                }
                return this;
            }
        });
        list.setVisibleRowCount(Math.min(12, deleted.size()));
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel(DriverManagerMessages.drivermgr_restore_hint), BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, panel, DriverManagerMessages.drivermgr_restore_title, JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION || list.getSelectedValuesList().isEmpty()) {
            return;
        }
        for (DriverDescriptor driver : list.getSelectedValuesList()) {
            driver.setDisabled(false);
        }
        saveDrivers();
        reload(list.getSelectedValuesList().getFirst());
    }

    private void saveDrivers() {
        try {
            DataSourceProviderRegistry.getInstance().saveDrivers();
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(DriverManagerMessages.drivermgr_title, DriverManagerMessages.drivermgr_save_error, e);
        }
    }

    private static void setOfflineMode(boolean offline) {
        DBPPreferenceStore preferences = DBWorkbench.getPlatform().getPreferenceStore();
        preferences.setValue(ModelPreferences.UI_DRIVERS_OFFLINE, offline);
        PrefUtils.savePreferenceStore(preferences);
    }

    /**
     * Driver icon (with the custom / failed overlays) and name; categories as folders.
     */
    private static final class DriverRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
            if (userObject instanceof DBPDriver driver) {
                setIcon(NodeIcons.getIcon(driver.getIcon()));
                setText(driver.getName());
                setToolTipText(CommonUtils.isEmpty(driver.getDescription()) ? driver.getFullName() : driver.getDescription());
            } else {
                setIcon(UiIcons.get(expanded ? "folder-open" : "folder")); //$NON-NLS-1$ //$NON-NLS-2$
                setText(String.valueOf(userObject));
                setToolTipText(null);
            }
            return this;
        }
    }
}
