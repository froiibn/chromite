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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPSystemObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.DBSWrapper;
import com.seizia.chromite.model.struct.DBStructUtils;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.model.struct.rdb.DBSView;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.tools.compare.schema.SchemaCompareItem;
import com.seizia.chromite.tools.compare.schema.SchemaCompareResult;
import com.seizia.chromite.tools.compare.schema.SchemaComparer;
import com.seizia.chromite.tools.compare.schema.SchemaScriptGenerator;
import com.seizia.chromite.ui.swing.internal.CompareMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * Schema Compare 문서 탭: 두 스키마(또는 데이터베이스)를 비교하고, 고른 차이를 대상에 반영하는 스크립트를 쿼리 창에 연다.
 *
 * 설계:
 * <ul>
 *     <li>CE에는 스키마 비교가 없다(SWT {@code cmp.simple.ui}의 "Simple compare"는 객체 속성 보고서일 뿐 스크립트가 없음).
 *     비교와 스크립트는 모델 번들 {@code cmp.simple}의 {@link SchemaComparer}, {@link SchemaScriptGenerator}가 하고(UI 없음),
 *     이 화면은 고르기·결과·DDL 보기만 한다. 배치는 SSMS·DataGrip의 비교 창처럼 위에 원본·대상, 가운데 차이 트리, 아래 두 DDL이다.</li>
 *     <li>고르기: 연결 → 데이터베이스 → 스키마. 모델 계층 구조({@link DBSObjectContainer#getPrimaryChildType})로 단계를 정하므로
 *     카탈로그만 있는 DB(MySQL), 스키마만 있는 DB(Oracle), 둘 다 있는 DB(SQL Server, PostgreSQL), 둘 다 없는 DB(SQLite)를 같은 코드로 다룬다.
 *     연결되지 않은 연결은 고를 때 연결한다(모달 진행 대화상자, 비밀번호 질문 가능). 처음 값은 Object Explorer에서 고른 노드의
 *     연결·데이터베이스·스키마, 없으면 연결의 기본값이다.</li>
 *     <li>결과 트리: 원본에만, 대상에만, 다름 세 묶음. 확인란을 누르거나 Space로 스크립트에 넣을 항목을 고른다. 대상에만 있는 항목(DROP)은
 *     처음에 꺼져 있다({@link SchemaCompareItem}). 항목을 고르면 그 객체(열·키는 그 테이블)의 원본·대상 DDL을 나란히 보이고 다른 줄을
 *     칠한다(텍스트 비교와 함께 쓰는 {@link TextDiffView}, 줄 앞뒤 공백 무시). 같은 종류 DB면 원본 DDL의 스키마 이름을 대상 이름으로 바꿔 보인다(비교와 같은 기준).</li>
 *     <li>안전: 스크립트는 대상 연결의 새 쿼리 창에 열기만 하고 실행하지 않는다({@code openScript(…, execute=false)}).
 *     쿼리 창의 실행 확인·트랜잭션 규칙이 그대로 적용된다. 스크립트 글은 Modify와 같은 {@link SourceScripts#generateContainerScript}가
 *     구분자(SQL Server {@code GO}, MySQL {@code DELIMITER})와 대상 DB 맞추기를 정한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/schema-compare.md)
 * - 2026-11-12 DDL 나란히 보기를 텍스트 비교와 함께 쓰는 {@link TextDiffView}로 옮김: 줄 비교는 LCS 대신 모델의 Myers {@code Diff}(EDT 밖),
 *   좌우 맞춤 빈 행·바뀐 낱말 표시·스크롤 맞춤이 더해짐, 색과 줄 앞뒤 공백 무시는 그대로 (docs/chromite/text-compare.md)
 */
final class SchemaComparePanel extends JPanel implements ToolDocument {

    private static final Log log = Log.getLog(SchemaComparePanel.class);

    private static int documentCounter;

    /**
     * A database or schema in a picker.
     */
    private record Choice(@NotNull DBSObjectContainer container) {
        @Override
        public String toString() {
            return container.getName();
        }
    }

    private final ObjectExplorerPanel.QueryWindows host;
    private final SidePicker sourcePicker = new SidePicker();
    private final SidePicker targetPicker = new SidePicker();
    private final JButton generateButton = new JButton(CompareMessages.compare_generate);
    private final JLabel statusLabel = new JLabel(CompareMessages.compare_hint);
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree tree = new JTree(treeModel);
    private final ItemRenderer itemRenderer = new ItemRenderer();
    private final TextDiffView ddlView = new TextDiffView();
    @Nullable
    private SchemaCompareResult result;
    @Nullable
    private AbstractJob ddlJob;
    private int ddlSerial;

    /**
     * Opens a new Schema Compare tab (each command opens its own, so two compares can stay side by side).
     *
     * @param selectedNode node selected in Object Explorer: its connection, database and schema are the first source
     */
    static void open(@NotNull ObjectExplorerPanel.QueryWindows host, @Nullable DBNNode selectedNode) {
        DBSObjectContainer initial = findContainer(selectedNode);
        host.openToolDocument("schemaCompare#" + (++documentCounter), () -> new SchemaComparePanel(host, initial)); //$NON-NLS-1$
    }

    private SchemaComparePanel(@NotNull ObjectExplorerPanel.QueryWindows host, @Nullable DBSObjectContainer initial) {
        super(new BorderLayout());
        this.host = host;

        JButton swapButton = new JButton(CompareMessages.compare_swap);
        swapButton.setToolTipText(CompareMessages.compare_swap_tip);
        swapButton.addActionListener(e -> swap());
        JButton compareButton = new JButton(CompareMessages.compare_button);
        compareButton.addActionListener(e -> compare());

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        form.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(10), UIScale.scale(12), UIScale.scale(6), UIScale.scale(12)));
        sourcePicker.addRow(form, 0, CompareMessages.compare_label_source);
        targetPicker.addRow(form, 1, CompareMessages.compare_label_target);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), 0));
        buttons.setOpaque(false);
        buttons.add(compareButton);
        buttons.add(swapButton);
        GridBagConstraints buttonConstraints = new GridBagConstraints();
        buttonConstraints.gridx = 7;
        buttonConstraints.gridy = 0;
        buttonConstraints.gridheight = 2;
        buttonConstraints.anchor = GridBagConstraints.WEST;
        buttonConstraints.insets = new Insets(0, UIScale.scale(12), 0, 0);
        form.add(buttons, buttonConstraints);
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 8;
        filler.weightx = 1;
        form.add(Box.createHorizontalGlue(), filler);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(itemRenderer);
        // The renderer's tool tip says what checking a DROP item does
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                Rectangle bounds = path == null ? null : tree.getPathBounds(path);
                if (bounds != null && SwingUtilities.isLeftMouseButton(e) && e.getX() < bounds.x + itemRenderer.getCheckWidth()) {
                    toggle((DefaultMutableTreeNode) path.getLastPathComponent());
                }
            }
        });
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "chromite.toggle"); //$NON-NLS-1$
        tree.getActionMap().put("chromite.toggle", new AbstractAction() { //$NON-NLS-1$
            @Override
            public void actionPerformed(ActionEvent e) {
                TreePath path = tree.getSelectionPath();
                if (path != null) {
                    toggle((DefaultMutableTreeNode) path.getLastPathComponent());
                }
            }
        });
        tree.addTreeSelectionListener(e -> showDdl());
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        // The split gives the rest to the DDL views; without this the expanded tree's height would take it all
        treeScroll.setPreferredSize(new Dimension(UIScale.scale(600), UIScale.scale(260)));

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeScroll, ddlView);
        mainSplit.setResizeWeight(0.45);
        mainSplit.setBorder(BorderFactory.createEmptyBorder());

        generateButton.setToolTipText(CompareMessages.compare_generate_tip);
        generateButton.setEnabled(false);
        generateButton.addActionListener(e -> generateScript());
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        JPanel statusBar = new JPanel(new BorderLayout(UIScale.scale(8), 0));
        statusBar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(12), UIScale.scale(6), UIScale.scale(12)));
        statusBar.add(statusLabel, BorderLayout.CENTER);
        statusBar.add(generateButton, BorderLayout.EAST);

        add(form, BorderLayout.NORTH);
        add(mainSplit, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        if (initial != null) {
            SwingUtilities.invokeLater(() -> sourcePicker.select(initial));
        }
    }

    /**
     * The database or schema of the node or of its nearest ancestor that is one.
     */
    @Nullable
    private static DBSObjectContainer findContainer(@Nullable DBNNode node) {
        for (DBNNode current = node; current != null; current = current.getParentNode()) {
            if (current instanceof DBSWrapper wrapper && (wrapper.getObject() instanceof DBSSchema || wrapper.getObject() instanceof DBSCatalog)) {
                return (DBSObjectContainer) wrapper.getObject();
            }
            if (current instanceof DBSWrapper wrapper && wrapper.getObject() instanceof DBPDataSource dataSource
                && dataSource instanceof DBSObjectContainer container) {
                return container;
            }
        }
        return null;
    }

    //////////////////////////////////////////////////////////
    // Pickers

    /**
     * Connection, database and schema of one side.
     */
    private final class SidePicker {
        private final JComboBox<DBPDataSourceContainer> connectionCombo = new JComboBox<>();
        private final JComboBox<Choice> databaseCombo = new JComboBox<>();
        private final JComboBox<Choice> schemaCombo = new JComboBox<>();
        @Nullable
        private DBSObjectContainer root;
        private boolean updating;

        SidePicker() {
            connectionCombo.addItem(null);
            DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
            if (project != null) {
                for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                    connectionCombo.addItem(container);
                }
            }
            connectionCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    setText(value instanceof DBPDataSourceContainer container ? container.getName() : CompareMessages.compare_choose_connection);
                    return this;
                }
            });
            for (JComboBox<?> combo : List.of(connectionCombo, databaseCombo, schemaCombo)) {
                combo.setPreferredSize(new Dimension(UIScale.scale(200), combo.getPreferredSize().height));
            }
            databaseCombo.setEnabled(false);
            schemaCombo.setEnabled(false);
            connectionCombo.addActionListener(e -> {
                if (!updating) {
                    loadConnection((DBPDataSourceContainer) connectionCombo.getSelectedItem(), null);
                }
            });
            databaseCombo.addActionListener(e -> {
                if (!updating && databaseCombo.getSelectedItem() instanceof Choice choice) {
                    loadSchemas(choice.container(), null);
                }
            });
            schemaCombo.addActionListener(e -> clearResult());
        }

        void addRow(@NotNull JPanel form, int row, @NotNull String label) {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridy = row;
            constraints.anchor = GridBagConstraints.WEST;
            constraints.insets = new Insets(UIScale.scale(2), 0, UIScale.scale(2), UIScale.scale(8));
            constraints.gridx = 0;
            form.add(new JLabel(label), constraints);
            constraints.gridx = 1;
            form.add(connectionCombo, constraints);
            constraints.gridx = 2;
            form.add(new JLabel(CompareMessages.compare_label_database), constraints);
            constraints.gridx = 3;
            form.add(databaseCombo, constraints);
            constraints.gridx = 4;
            form.add(new JLabel(CompareMessages.compare_label_schema), constraints);
            constraints.gridx = 5;
            form.add(schemaCombo, constraints);
        }

        /**
         * The chosen container: the schema, else the database, else the connection when it has neither.
         */
        @Nullable
        DBSObjectContainer getContainer() {
            if (schemaCombo.isEnabled()) {
                return schemaCombo.getSelectedItem() instanceof Choice choice ? choice.container() : null;
            }
            if (databaseCombo.isEnabled()) {
                return databaseCombo.getSelectedItem() instanceof Choice choice ? choice.container() : null;
            }
            return root;
        }

        /**
         * Chooses a container (Object Explorer selection, Swap): its connection, then its database and schema.
         */
        void select(@NotNull DBSObjectContainer container) {
            DBPDataSource dataSource = container.getDataSource();
            if (dataSource == null) {
                return;
            }
            updating = true;
            try {
                connectionCombo.setSelectedItem(dataSource.getContainer());
            } finally {
                updating = false;
            }
            loadConnection(dataSource.getContainer(), container);
        }

        /**
         * Connects if needed and lists the connection's databases (or schemas).
         *
         * @param preferred container to choose, or null for the connection's defaults
         */
        private void loadConnection(@Nullable DBPDataSourceContainer connection, @Nullable DBSObjectContainer preferred) {
            clearResult();
            root = null;
            setChoices(databaseCombo, List.of(), null);
            setChoices(schemaCombo, List.of(), null);
            if (connection == null) {
                return;
            }
            Level level;
            try {
                level = ProgressDialog.runTask(CompareMessages.compare_progress_read, monitor -> readRoot(monitor, connection));
            } catch (DBException e) {
                showError(NLS.bind(CompareMessages.compare_read_failed, connection.getName()), e);
                return;
            }
            root = level.parent();
            if (level.choices() == null) {
                return;
            }
            DBSObjectContainer preferredCatalog = getSelfOrParent(DBSCatalog.class, preferred);
            DBSObjectContainer preferredSchema = getSelfOrParent(DBSSchema.class, preferred);
            if (level.catalogs()) {
                Choice chosen = setChoices(databaseCombo, level.choices(), preferredCatalog != null ? preferredCatalog.getName() : level.defaultName());
                if (chosen != null) {
                    loadSchemas(chosen.container(), preferredSchema);
                }
            } else {
                setChoices(schemaCombo, level.choices(), preferredSchema != null ? preferredSchema.getName() : level.defaultName());
            }
        }

        private void loadSchemas(@NotNull DBSObjectContainer database, @Nullable DBSObjectContainer preferred) {
            clearResult();
            setChoices(schemaCombo, List.of(), null);
            Level level;
            try {
                level = ProgressDialog.runTask(CompareMessages.compare_progress_read, monitor -> readLevel(monitor, database));
            } catch (DBException e) {
                showError(NLS.bind(CompareMessages.compare_read_failed, database.getName()), e);
                return;
            }
            if (level.choices() != null) {
                setChoices(schemaCombo, level.choices(), preferred != null ? preferred.getName() : level.defaultName());
            }
        }

        /**
         * Fills a combo and selects the choice with the name (else the first); a combo without choices is disabled.
         */
        @Nullable
        private Choice setChoices(@NotNull JComboBox<Choice> combo, @NotNull List<Choice> choices, @Nullable String selectName) {
            updating = true;
            try {
                combo.removeAllItems();
                Choice selected = null;
                for (Choice choice : choices) {
                    combo.addItem(choice);
                    if (selected == null && choice.container().getName().equals(selectName)) {
                        selected = choice;
                    }
                }
                if (selected == null && !choices.isEmpty()) {
                    selected = choices.getFirst();
                }
                combo.setSelectedItem(selected);
                combo.setEnabled(!choices.isEmpty());
                return selected;
            } finally {
                updating = false;
            }
        }
    }

    /**
     * The children of a container that are databases or schemas.
     *
     * @param choices     null when the container holds objects itself (no databases or schemas below it)
     * @param catalogs    the choices are databases (catalogs)
     * @param defaultName the connection's default database or schema at this level, or null
     */
    private record Level(@NotNull DBSObjectContainer parent, @Nullable List<Choice> choices, boolean catalogs, @Nullable String defaultName) {
    }

    @NotNull
    private static Level readRoot(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSourceContainer connection) throws DBException {
        if (!connection.isConnected()) {
            DBUtils.initDataSource(monitor, connection, null);
        }
        DBPDataSource dataSource = connection.getDataSource();
        if (!connection.isConnected() || !(dataSource instanceof DBSObjectContainer container)) {
            throw new DBException(NLS.bind(CompareMessages.compare_read_failed, connection.getName()));
        }
        return readLevel(monitor, container);
    }

    @NotNull
    private static Level readLevel(@NotNull DBRProgressMonitor monitor, @NotNull DBSObjectContainer parent) throws DBException {
        Class<? extends DBSObject> childType = parent.getPrimaryChildType(monitor);
        boolean catalogs = childType != null && DBSCatalog.class.isAssignableFrom(childType);
        if (childType == null || !catalogs && !DBSSchema.class.isAssignableFrom(childType)) {
            return new Level(parent, null, false, null);
        }
        List<Choice> choices = new ArrayList<>();
        Collection<? extends DBSObject> children = parent.getChildren(monitor);
        for (DBSObject child : CommonUtils.safeCollection(children)) {
            if (child instanceof DBSObjectContainer container && (child instanceof DBSCatalog || child instanceof DBSSchema)
                && !DBUtils.isHiddenObject(child) && !(child instanceof DBPSystemObject system && system.isSystem())) {
                choices.add(new Choice(container));
            }
        }
        String defaultName = null;
        DBPDataSource dataSource = parent.getDataSource();
        DBCExecutionContext context = dataSource == null ? null : DBUtils.getDefaultContext(dataSource, true);
        DBCExecutionContextDefaults<?, ?> defaults = context == null ? null : context.getContextDefaults();
        if (defaults != null) {
            DBSObject current = catalogs ? defaults.getDefaultCatalog() : defaults.getDefaultSchema();
            defaultName = current == null ? null : current.getName();
        }
        return new Level(parent, choices, catalogs, defaultName);
    }

    @Nullable
    private static DBSObjectContainer getSelfOrParent(@NotNull Class<? extends DBSObjectContainer> type, @Nullable DBSObject object) {
        if (object == null) {
            return null;
        }
        return type.isInstance(object) ? type.cast(object) : DBUtils.getParentOfType(type, object);
    }

    private void swap() {
        DBSObjectContainer source = sourcePicker.getContainer();
        DBSObjectContainer target = targetPicker.getContainer();
        if (target != null) {
            sourcePicker.select(target);
        }
        if (source != null) {
            targetPicker.select(source);
        }
    }

    //////////////////////////////////////////////////////////
    // Compare

    private void clearResult() {
        result = null;
        treeRoot.removeAllChildren();
        treeModel.reload();
        generateButton.setEnabled(false);
        statusLabel.setText(CompareMessages.compare_hint);
        showTexts(null, null, null, null);
    }

    private void compare() {
        DBSObjectContainer source = sourcePicker.getContainer();
        DBSObjectContainer target = targetPicker.getContainer();
        if (source == null || target == null) {
            statusLabel.setText(CompareMessages.compare_choose_both);
            return;
        }
        if (source == target) {
            statusLabel.setText(CompareMessages.compare_same);
            return;
        }
        clearResult();
        SchemaCompareResult compared;
        try {
            compared = ProgressDialog.runTask(CompareMessages.compare_progress_compare, monitor -> SchemaComparer.compare(monitor, source, target));
        } catch (DBException e) {
            showError(CompareMessages.compare_failed, e);
            return;
        }
        showResult(compared);
    }

    private void showResult(@NotNull SchemaCompareResult compared) {
        result = compared;
        treeRoot.removeAllChildren();
        List<DefaultMutableTreeNode> expand = new ArrayList<>();
        for (SchemaCompareItem.Status status : List.of(SchemaCompareItem.Status.ONLY_IN_SOURCE, SchemaCompareItem.Status.ONLY_IN_TARGET,
            SchemaCompareItem.Status.DIFFERENT)) {
            List<SchemaCompareItem> items = compared.getItems().stream().filter(i -> i.getStatus() == status).toList();
            if (items.isEmpty()) {
                continue;
            }
            DefaultMutableTreeNode group = new DefaultMutableTreeNode(new Group(status, items));
            treeRoot.add(group);
            expand.add(group);
            for (SchemaCompareItem item : items) {
                DefaultMutableTreeNode itemNode = new DefaultMutableTreeNode(item);
                group.add(itemNode);
                for (SchemaCompareItem child : item.getChildren()) {
                    itemNode.add(new DefaultMutableTreeNode(child));
                }
                if (!item.getChildren().isEmpty()) {
                    expand.add(itemNode);
                }
            }
        }
        treeModel.reload();
        for (DefaultMutableTreeNode node : expand) {
            tree.expandPath(new TreePath(node.getPath()));
        }
        int differences = compared.getItems().size();
        String summary = differences == 0 ? CompareMessages.compare_no_differences
            : NLS.bind(CompareMessages.compare_summary, differences, compared.getIdenticalCount());
        if (compared.getNotComparedCount() > 0) {
            summary = NLS.bind(CompareMessages.compare_summary_not_compared, summary, compared.getNotComparedCount());
        }
        statusLabel.setText(summary);
        generateButton.setEnabled(differences > 0);
        if (treeRoot.getChildCount() > 0) {
            tree.setSelectionRow(0);
        }
    }

    /**
     * A status group of the result tree.
     */
    private record Group(@NotNull SchemaCompareItem.Status status, @NotNull List<SchemaCompareItem> items) {
        @NotNull
        String getText() {
            return NLS.bind(switch (status) {
                case ONLY_IN_SOURCE -> CompareMessages.compare_group_only_source;
                case ONLY_IN_TARGET -> CompareMessages.compare_group_only_target;
                case DIFFERENT -> CompareMessages.compare_group_different;
            }, items.size());
        }

        boolean isSelected() {
            return items.stream().anyMatch(SchemaCompareItem::isSelected);
        }
    }

    /**
     * Checks or unchecks an item (with its children) or a whole group: everything when something was unchecked.
     */
    private void toggle(@NotNull DefaultMutableTreeNode node) {
        Object value = node.getUserObject();
        if (value instanceof SchemaCompareItem item) {
            item.setSelected(!isFullySelected(item));
        } else if (value instanceof Group group) {
            boolean select = !group.items().stream().allMatch(SchemaComparePanel::isFullySelected);
            group.items().forEach(item -> item.setSelected(select));
        } else {
            return;
        }
        tree.repaint();
    }

    private static boolean isFullySelected(@NotNull SchemaCompareItem item) {
        return item.getChildren().isEmpty() ? item.isSelected() : item.getChildren().stream().allMatch(SchemaComparePanel::isFullySelected);
    }

    //////////////////////////////////////////////////////////
    // DDL views

    private void showDdl() {
        TreePath path = tree.getSelectionPath();
        SchemaCompareResult compared = result;
        if (path == null || compared == null || !(((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof SchemaCompareItem item)) {
            return;
        }
        // A column, key or trigger shows its table
        SchemaCompareItem shown = item.getParent() != null ? item.getParent() : item;
        DBSObject source = shown.getSource();
        DBSObject target = shown.getTarget();
        int serial = ++ddlSerial;
        if (ddlJob != null) {
            ddlJob.cancel();
        }
        ddlView.setHeader(true, CompareMessages.compare_ddl_loading);
        ddlView.setHeader(false, CompareMessages.compare_ddl_loading);
        ddlJob = new AbstractJob("Read schema compare DDL") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                String sourceDdl = null;
                String targetDdl = null;
                try {
                    sourceDdl = source == null ? null : readDdl(monitor, source);
                    if (sourceDdl != null && compared.isSameDialect()) {
                        sourceDdl = compared.getNameMapper().remap(sourceDdl);
                    }
                    targetDdl = target == null ? null : readDdl(monitor, target);
                } catch (Throwable e) {
                    log.debug("Can't read the DDL of " + shown.getName(), e); //$NON-NLS-1$
                    String message = "-- " + ErrorText.describeLine(e); //$NON-NLS-1$
                    sourceDdl = sourceDdl == null && source != null ? message : sourceDdl;
                    targetDdl = targetDdl == null && target != null ? message : targetDdl;
                }
                String finalSource = sourceDdl;
                String finalTarget = targetDdl;
                SwingUtilities.invokeLater(() -> {
                    if (serial == ddlSerial && compared == result) {
                        showTexts(compared, source, finalSource, finalTarget);
                    }
                });
                return Status.OK_STATUS;
            }
        };
        ddlJob.schedule();
    }

    @Nullable
    private static String readDdl(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object) throws DBException {
        if (object instanceof DBSEntity entity && !(object instanceof DBSView) && !(object instanceof DBSTable table && table.isView())) {
            return DBStructUtils.getTableDDL(monitor, entity, new HashMap<>(), false).strip();
        }
        return SchemaComparer.readDefinition(monitor, object);
    }

    private void showTexts(@Nullable SchemaCompareResult compared, @Nullable DBSObject source, @Nullable String sourceDdl, @Nullable String targetDdl) {
        if (compared == null) {
            ddlView.setHeader(true, " "); //$NON-NLS-1$
            ddlView.setHeader(false, " "); //$NON-NLS-1$
            ddlView.compare(null, null, null, null, CompareMessages.compare_ddl_absent, TextDiff.Options.SCHEMA, null);
            return;
        }
        String sourceName = describe(compared.getSource());
        ddlView.setHeader(true, NLS.bind(compared.isSameDialect() && source != null ? CompareMessages.compare_ddl_source_mapped
            : CompareMessages.compare_ddl_source, sourceName));
        ddlView.setHeader(false, NLS.bind(CompareMessages.compare_ddl_target, describe(compared.getTarget())));
        // Lines compared without their surrounding spaces, as before the view was shared (TextDiff.Options.SCHEMA)
        ddlView.compare(sourceDdl, targetDdl, getContainer(compared.getSource()), getContainer(compared.getTarget()),
            CompareMessages.compare_ddl_absent, TextDiff.Options.SCHEMA, null);
    }

    @Nullable
    private static DBPDataSourceContainer getContainer(@NotNull DBSObjectContainer container) {
        DBPDataSource dataSource = container.getDataSource();
        return dataSource == null ? null : dataSource.getContainer();
    }

    @NotNull
    private static String describe(@NotNull DBSObjectContainer container) {
        DBPDataSource dataSource = container.getDataSource();
        return (dataSource == null ? "" : dataSource.getContainer().getName() + " / ") //$NON-NLS-1$ //$NON-NLS-2$
            + DBUtils.getObjectFullName(container, DBPEvaluationContext.UI);
    }

    //////////////////////////////////////////////////////////
    // Script

    private void generateScript() {
        SchemaCompareResult compared = result;
        if (compared == null) {
            return;
        }
        if (compared.getItems().stream().noneMatch(SchemaCompareItem::isSelected)) {
            statusLabel.setText(CompareMessages.compare_nothing_checked);
            return;
        }
        String script;
        try {
            script = ProgressDialog.runTask(CompareMessages.compare_progress_script, monitor -> {
                List<DBEPersistAction> actions = new ArrayList<>(SchemaScriptGenerator.createHeader(compared));
                actions.addAll(SchemaScriptGenerator.generate(monitor, compared));
                return SourceScripts.generateContainerScript(monitor, compared.getTarget(), actions);
            });
        } catch (DBException e) {
            showError(CompareMessages.compare_script_failed, e);
            return;
        }
        DBPDataSource dataSource = Objects.requireNonNull(compared.getTarget().getDataSource());
        // Never run here: the user reviews the script in the query window and runs it there
        host.openScript(dataSource.getContainer(), script, 0, false);
    }

    private void showError(@NotNull String message, @NotNull DBException e) {
        if (e instanceof DBInterruptedException) {
            return;
        }
        DBWorkbench.getPlatformUI().showError(CompareMessages.compare_title, message, e);
    }

    //////////////////////////////////////////////////////////
    // ToolDocument

    @NotNull
    @Override
    public String getTitle() {
        return CompareMessages.compare_title;
    }

    @NotNull
    @Override
    public String getToolTip() {
        return CompareMessages.compare_tooltip;
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return UiIcons.get("schema"); //$NON-NLS-1$
    }

    @Override
    public void dispose() {
        ddlSerial++;
        if (ddlJob != null) {
            ddlJob.cancel();
        }
        ddlView.dispose();
    }

    //////////////////////////////////////////////////////////
    // Tree rendering

    /**
     * A check box and the item's icon and text: kind, name and what differs.
     */
    private static final class ItemRenderer extends JPanel implements TreeCellRenderer {
        private final JCheckBox check = new JCheckBox();
        private final DefaultTreeCellRenderer label = new DefaultTreeCellRenderer();

        ItemRenderer() {
            super(new BorderLayout(0, 0));
            setOpaque(false);
            check.setOpaque(false);
            add(check, BorderLayout.WEST);
            add(label, BorderLayout.CENTER);
        }

        int getCheckWidth() {
            return check.getPreferredSize().width;
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            label.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, false);
            Object userObject = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
            setToolTipText(null);
            if (userObject instanceof SchemaCompareItem item) {
                check.setSelected(item.isSelected());
                label.setIcon(UiIcons.get(getIconId(item.getKind())));
                label.setText(getItemText(item));
                if (item.getStatus() == SchemaCompareItem.Status.ONLY_IN_TARGET) {
                    setToolTipText(CompareMessages.compare_drop_tip);
                }
            } else if (userObject instanceof Group group) {
                check.setSelected(group.isSelected());
                label.setIcon(null);
                label.setText(group.getText());
            } else {
                check.setSelected(false);
                label.setText(""); //$NON-NLS-1$
            }
            return this;
        }
    }

    @NotNull
    private static String getIconId(@NotNull SchemaCompareItem.Kind kind) {
        return switch (kind) {
            case TABLE -> "table"; //$NON-NLS-1$
            case VIEW -> "view"; //$NON-NLS-1$
            case ROUTINE -> "procedure"; //$NON-NLS-1$
            case SEQUENCE -> "sequence"; //$NON-NLS-1$
            case COLUMN -> "column"; //$NON-NLS-1$
            case PRIMARY_KEY -> "primary-key"; //$NON-NLS-1$
            case FOREIGN_KEY -> "foreign-key"; //$NON-NLS-1$
            case INDEX -> "index"; //$NON-NLS-1$
            case TRIGGER -> "trigger"; //$NON-NLS-1$
        };
    }

    @NotNull
    private static String getKindLabel(@NotNull SchemaCompareItem.Kind kind) {
        return switch (kind) {
            case TABLE -> CompareMessages.compare_kind_table;
            case VIEW -> CompareMessages.compare_kind_view;
            case ROUTINE -> CompareMessages.compare_kind_routine;
            case SEQUENCE -> CompareMessages.compare_kind_sequence;
            case COLUMN -> CompareMessages.compare_kind_column;
            case PRIMARY_KEY -> CompareMessages.compare_kind_primary_key;
            case FOREIGN_KEY -> CompareMessages.compare_kind_foreign_key;
            case INDEX -> CompareMessages.compare_kind_index;
            case TRIGGER -> CompareMessages.compare_kind_trigger;
        };
    }

    @NotNull
    private static String getPropertyLabel(@NotNull SchemaCompareItem.Property property) {
        return switch (property) {
            case TYPE -> CompareMessages.compare_property_type;
            case NULLABLE -> CompareMessages.compare_property_nullable;
            case DEFAULT -> CompareMessages.compare_property_default;
            case COLUMNS -> CompareMessages.compare_property_columns;
            case UNIQUE -> CompareMessages.compare_property_unique;
            case REFERENCE -> CompareMessages.compare_property_reference;
            case DELETE_RULE -> CompareMessages.compare_property_delete_rule;
            case UPDATE_RULE -> CompareMessages.compare_property_update_rule;
            case DEFINITION -> CompareMessages.compare_property_definition;
            case INCREMENT -> CompareMessages.compare_property_increment;
            case MIN_VALUE -> CompareMessages.compare_property_min_value;
            case MAX_VALUE -> CompareMessages.compare_property_max_value;
        };
    }

    /**
     * "Column email — Type: source varchar(200), target varchar(100)"; a child whose status is not its group's says it.
     */
    @NotNull
    static String getItemText(@NotNull SchemaCompareItem item) {
        StringBuilder text = new StringBuilder(getKindLabel(item.getKind())).append(' ').append(item.getName());
        List<String> details = new ArrayList<>();
        if (item.getParent() != null && item.getStatus() == SchemaCompareItem.Status.ONLY_IN_SOURCE) {
            details.add(CompareMessages.compare_only_source);
        } else if (item.getParent() != null && item.getStatus() == SchemaCompareItem.Status.ONLY_IN_TARGET) {
            details.add(CompareMessages.compare_only_target);
        }
        for (SchemaCompareItem.Difference difference : item.getDifferences()) {
            if (difference.sourceValue() == null && difference.targetValue() == null) {
                details.add(getPropertyLabel(difference.property()));
            } else {
                details.add(NLS.bind(CompareMessages.compare_difference, new Object[]{getPropertyLabel(difference.property()),
                    valueText(difference.sourceValue()), valueText(difference.targetValue())}));
            }
        }
        if (!details.isEmpty()) {
            text.append(" — ").append(String.join("; ", details)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return text.toString();
    }

    @NotNull
    private static String valueText(@Nullable String value) {
        return CommonUtils.isEmpty(value) ? CompareMessages.compare_empty_value : value;
    }
}
