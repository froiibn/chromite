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
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPHiddenObject;
import com.seizia.chromite.model.DBPNamedObject;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.DBNUtils;
import com.seizia.chromite.model.navigator.meta.DBXTreeItem;
import com.seizia.chromite.model.navigator.meta.DBXTreeNode;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.properties.DataSourcePropertyFilter;
import com.seizia.chromite.runtime.properties.ObjectAttributeDescriptor;
import com.seizia.chromite.runtime.properties.ObjectPropertyDescriptor;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.ObjectExplorerMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Object Details: the children of an Object Explorer node as tables with property columns (SSMS "Object Explorer Details",
 * SWT {@code FolderEditor}/{@code ItemListControl} and the list tabs of the object editor).
 *
 * 설계:
 * <ul>
 *     <li>폴더(Tables, Columns 등)를 열면 그 자식 목록 하나, 객체(테이블, 스키마, 루틴 등)를 열면 자식 폴더마다 탭 하나다. 폴더가 아닌 자식
 *     (루틴 매개 변수처럼 객체 바로 아래 항목)은 탐색기 메타({@link DBXTreeNode})별로 탭을 묶는다. 그래서 테이블에서는 SWT 객체 편집기의
 *     Columns·Constraints·Foreign Keys·Indexes·References·Dependencies·Triggers·Partitions 탭이 된다. 어떤 탭이 있는지는 드라이버 모델의
 *     탐색기 구성({@code plugin.xml})이 정한다(가상 폴더 References·Dependencies 포함). 이 앱에는 SWT 객체 편집기가 없어 이 문서가 그 목록 탭을 대신한다.
 *     객체를 열면 첫 탭은 SWT 편집기처럼 속성 탭이다(속성 창과 같은 {@link PropertiesPanel}, 고칠 수 있는 속성은 거기서 고친다).</li>
 *     <li>열은 자식 클래스의 {@code @Property} 중 {@code viewable}이고 숨김이 아닌 것({@link ObjectListTable}과 같은 규칙, 연결의 속성 필터
 *     {@link DataSourcePropertyFilter})이다. 자식의 클래스가 여럿이면 처음 나온 순서로 합친다. 첫 열이 노드 글자(이름, 겹친 이름은 형식까지)와 아이콘이라
 *     {@code name} 속성 열은 뺀다.</li>
 *     <li>인라인 자식({@code inline="true"}: 인덱스·키 열, Oracle 종속성 묶음의 개체)은 그 항목 아래 들여 쓴 행이다. SWT 목록도 항목 아래에 보였고,
 *     빼면 Oracle 종속성 탭이 묶음 이름 두 줄만 남는다. 들여 쓴 행이 있는 목록은 정렬하지 않는다(행이 항목에서 떨어지지 않게).</li>
 *     <li>읽기는 탭을 처음 볼 때 작업 스레드에서 한다. 게으른 속성(값마다 DB 조회)은 두 번째로 읽어 행이 먼저 보이고 값이 채워진다(SWT
 *     {@code ObjectListControl}의 지연 읽기와 같은 순서). 문서를 닫거나 새로 고치면 읽기를 취소하고 늦게 온 결과는 번호로 버린다.</li>
 *     <li>행을 두 번 누르거나 Enter를 누르면 Object Explorer에서 그 노드를 고른다. 찾기 상자는 어느 열 글자에든 들어 있는 행만 남긴다.</li>
 *     <li>Refresh는 노드를 DB에서 다시 읽는다({@code DBNNode#refreshNode}, Object Explorer의 새로 고침과 같음).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 폴더 내용 표(C-16)와 객체의 종속성·참조 등 목록 탭(B-E7) (docs/chromite/swing-explorer-productivity.md "Object Details")
 * - 2026-11-08 객체의 첫 탭으로 속성 탭(고칠 수 있는 속성 편집, {@link PropertiesPanel}, SWT 객체 편집기의 Properties 탭, C-15).
 *   자식이 없는 객체(시퀀스)도 속성 탭으로 열린다 (docs/chromite/swing-object-properties.md)
 */
final class ObjectDetailsPanel extends JPanel implements ToolDocument {

    private static final Log log = Log.getLog(ObjectDetailsPanel.class);

    /**
     * Shown in a lazy property's cell until its value is read.
     */
    private static final Object PENDING = new Object();
    /**
     * Rows read before a batch of lazy values goes to the table.
     */
    private static final int LAZY_BATCH = 25;
    private static final int MAX_COLUMN_WIDTH = 320;

    private final DBNDatabaseNode node;
    private final Consumer<DBNNode> reveal;
    /**
     * The Properties tab of an object (not of a folder), first as in the SWT object editor.
     */
    @Nullable
    private final PropertiesPanel propertiesPanel;
    private final JPanel body = new JPanel(new BorderLayout());
    private final JTextField searchField = new JTextField(18);
    private final JLabel statusLabel = new JLabel(" ");
    private final List<ListView> views = new ArrayList<>();
    @Nullable
    private JTabbedPane tabs;
    @Nullable
    private AbstractJob groupsJob;
    private int groupsSerial;
    private boolean disposed;

    /**
     * @param reveal       selects a node in Object Explorer (double click on a row)
     * @param queryWindows opens a property change script in a query window
     */
    ObjectDetailsPanel(@NotNull DBNDatabaseNode node, @NotNull Consumer<DBNNode> reveal, @NotNull ObjectExplorerPanel.QueryWindows queryWindows) {
        super(new BorderLayout());
        this.node = node;
        this.reveal = reveal;
        propertiesPanel = node instanceof DBNDatabaseFolder ? null : new PropertiesPanel(queryWindows, false);

        JLabel titleLabel = new JLabel(ObjectExplorerPanel.getNodeText(node), NodeIcons.getNodeIcon(node, false), SwingConstants.LEADING);
        titleLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4"); //$NON-NLS-1$
        JLabel typeLabel = new JLabel(CommonUtils.notEmpty(node.getNodeTypeLabel()) + "  ·  " + node.getDataSourceContainer().getName()); //$NON-NLS-1$
        typeLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JPanel header = new JPanel(new GridLayout(0, 1, 0, 2));
        header.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        header.add(titleLabel);
        header.add(typeLabel);

        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, ObjectExplorerMessages.oe_details_search);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.setMaximumSize(searchField.getPreferredSize());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                views.forEach(view -> view.setSearchText(searchField.getText()));
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                insertUpdate(e);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                insertUpdate(e);
            }
        });
        Action refreshAction = new AbstractAction(CoreMessages.explorer_refresh) {
            @Override
            public void actionPerformed(ActionEvent e) {
                refresh();
            }
        };
        refreshAction.putValue(Action.SHORT_DESCRIPTION, ObjectExplorerMessages.oe_details_refresh_tip);
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(searchField);
        toolBar.add(Box.createHorizontalGlue());

        JPanel top = new JPanel(new BorderLayout());
        top.add(header, BorderLayout.NORTH);
        top.add(toolBar, BorderLayout.SOUTH);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        add(top, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        build();
    }

    /**
     * The node this document shows (Link with Editor, an open document for the same node).
     */
    @NotNull
    DBNDatabaseNode getNode() {
        return node;
    }

    @NotNull
    @Override
    public String getTitle() {
        return NLS.bind(ObjectExplorerMessages.oe_details_title, ObjectExplorerPanel.getNodeText(node));
    }

    @NotNull
    @Override
    public String getToolTip() {
        return NLS.bind(ObjectExplorerMessages.oe_details_tooltip, node.getNodeFullName(), node.getDataSourceContainer().getName());
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return NodeIcons.getNodeIcon(node, false);
    }

    @Override
    public void dispose() {
        disposed = true;
        cancelGroups();
        views.forEach(ListView::cancel);
        if (propertiesPanel != null) {
            // Cancels its reading
            propertiesPanel.showNode(null);
        }
    }

    /**
     * A folder: one list. Any other node: its children grouped into tabs, read in a job.
     */
    private void build() {
        body.removeAll();
        views.forEach(ListView::cancel);
        views.clear();
        tabs = null;
        if (node instanceof DBNDatabaseFolder) {
            ListView view = new ListView(node, null);
            views.add(view);
            body.add(view, BorderLayout.CENTER);
            view.load(false);
        } else {
            body.add(createMessage(CoreMessages.common_loading), BorderLayout.CENTER);
            loadGroups(false);
        }
        body.revalidate();
        body.repaint();
    }

    private void refresh() {
        if (node instanceof DBNDatabaseFolder) {
            if (!views.isEmpty()) {
                views.get(0).load(true);
            }
        } else {
            views.forEach(ListView::cancel);
            loadGroups(true);
        }
    }

    private void cancelGroups() {
        groupsSerial++;
        if (groupsJob != null) {
            groupsJob.cancel();
            groupsJob = null;
        }
    }

    /**
     * Reads the node's children and groups them: each folder is a tab, other children make a tab per navigator meta.
     */
    private void loadGroups(boolean refresh) {
        cancelGroups();
        int serial = groupsSerial;
        statusLabel.setText(CoreMessages.common_loading);
        groupsJob = new AbstractJob("Read details of " + node.getNodeDisplayName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    DBNNode source = node;
                    if (refresh) {
                        DBNNode refreshed = node.refreshNode(monitor, ObjectDetailsPanel.this);
                        source = refreshed != null ? refreshed : node;
                    }
                    Map<Object, Group> groups = new LinkedHashMap<>();
                    for (DBNNode child : readChildren(monitor, source)) {
                        if (child instanceof DBNDatabaseFolder folder) {
                            groups.put(folder, new Group(ObjectExplorerPanel.getNodeText(folder), folder, null));
                        } else if (child instanceof DBNDatabaseNode databaseNode) {
                            DBXTreeNode meta = databaseNode.getMeta();
                            Group group = groups.computeIfAbsent(meta, m -> new Group(getGroupLabel(databaseNode), null, new ArrayList<>()));
                            group.items().add(child);
                        }
                    }
                    List<Group> result = new ArrayList<>(groups.values());
                    SwingUtilities.invokeLater(() -> showGroups(serial, result, null));
                } catch (Throwable e) {
                    log.debug("Error reading details of " + node.getNodeDisplayName(), e);
                    SwingUtilities.invokeLater(() -> showGroups(serial, List.of(), e));
                }
                return Status.OK_STATUS;
            }
        };
        groupsJob.schedule();
    }

    private void showGroups(int serial, @NotNull List<Group> groups, @Nullable Throwable error) {
        if (serial != groupsSerial || disposed) {
            return;
        }
        int selectedIndex = tabs != null ? tabs.getSelectedIndex() : 0;
        body.removeAll();
        views.clear();
        if (error != null || groups.isEmpty() && propertiesPanel == null) {
            tabs = null;
            String message = error != null
                ? NLS.bind(ObjectExplorerMessages.oe_details_error, ObjectExplorerPanel.getNodeText(node), ErrorText.describe(error))
                : ObjectExplorerMessages.oe_details_no_items;
            body.add(createMessage(message), BorderLayout.CENTER);
            statusLabel.setText(" ");
        } else {
            JTabbedPane groupTabs = new JTabbedPane();
            groupTabs.putClientProperty(FlatClientProperties.STYLE, UiSizes.TOOL_TABS_STYLE);
            if (propertiesPanel != null) {
                // An object without children (a sequence) still has its properties; read again on Refresh
                groupTabs.addTab(CoreMessages.main_properties, propertiesPanel);
                propertiesPanel.showNode(node);
            }
            for (Group group : groups) {
                ListView view = new ListView(group.folder(), group.items());
                views.add(view);
                groupTabs.addTab(group.label(), group.folder() != null ? NodeIcons.getNodeIcon(group.folder(), false) : null, view);
            }
            // A tab reads its list the first time it is shown
            groupTabs.addChangeListener(e -> {
                if (groupTabs.getSelectedComponent() instanceof ListView view) {
                    view.showing();
                } else {
                    statusLabel.setText(" ");
                }
            });
            tabs = groupTabs;
            body.add(groupTabs, BorderLayout.CENTER);
            groupTabs.setSelectedIndex(Math.clamp(selectedIndex, 0, groupTabs.getTabCount() - 1));
            if (groupTabs.getSelectedComponent() instanceof ListView view) {
                view.showing();
            } else {
                statusLabel.setText(" ");
            }
        }
        body.revalidate();
        body.repaint();
    }

    @NotNull
    private static String getGroupLabel(@NotNull DBNDatabaseNode child) {
        DBXTreeNode meta = child.getMeta();
        String label = meta instanceof DBXTreeItem item
            ? item.getChildrenTypeLabel(child.getDataSource(), null) : null;
        return CommonUtils.isEmpty(label) ? CommonUtils.notEmpty(child.getNodeTypeLabel()) : label;
    }

    /**
     * Children as Object Explorer lists them: hidden objects left out, sorted as the navigator sorts them.
     */
    @NotNull
    private static List<DBNNode> readChildren(@NotNull DBRProgressMonitor monitor, @NotNull DBNNode source) throws DBException {
        DBNNode[] children = source.getChildren(monitor);
        if (children == null) {
            return List.of();
        }
        List<DBNNode> shown = new ArrayList<>(children.length);
        for (DBNNode child : children) {
            if (!(child instanceof DBPHiddenObject hiddenObject && hiddenObject.isHidden())) {
                shown.add(child);
            }
        }
        return List.of(DBNUtils.filterNavigableChildren(shown.toArray(new DBNNode[0]), false));
    }

    @NotNull
    private static JComponent createMessage(@NotNull String text) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        return label;
    }

    /**
     * Display text of a property value: names of objects, lists joined with commas, else the UI format.
     */
    @NotNull
    static String formatValue(@Nullable Object value) {
        if (value == null || value == PENDING) {
            return value == PENDING ? "…" : ""; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (value instanceof DBPNamedObject namedObject && !(value instanceof Enum<?>)) {
            return CommonUtils.notEmpty(namedObject.getName());
        }
        if (value instanceof Object[] array) {
            return Stream.of(array).map(ObjectDetailsPanel::formatValue).collect(Collectors.joining(", ")); //$NON-NLS-1$
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(ObjectDetailsPanel::formatValue).collect(Collectors.joining(", ")); //$NON-NLS-1$
        }
        return ObjectListTable.formatValue(value);
    }

    /**
     * A tab: a folder whose children are read when shown, or the items found among the object's children.
     */
    private record Group(@NotNull String label, @Nullable DBNDatabaseFolder folder, @Nullable List<DBNNode> items) {
    }

    /**
     * One row: the child node and its property values (raw for sorting, {@link #PENDING} while a lazy value is read).
     *
     * @param depth 1 for an inline child of the row above it
     */
    private record Row(@NotNull DBNNode node, @NotNull Object[] values, int depth) {
    }

    /**
     * True when the item has inline children ({@code inline="true"} in the navigator configuration).
     */
    private static boolean hasInlineChildren(@NotNull DBNDatabaseNode item) {
        for (DBXTreeNode child : item.getMeta().getChildren(item)) {
            if (child.isInline()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The list of one folder or group, with its own reading job.
     */
    private final class ListView extends JPanel {
        private final DetailsModel model = new DetailsModel();
        private final TableRowSorter<DetailsModel> sorter = new TableRowSorter<>(model);
        private final JTable table = new JTable(model) {
            @Override
            public void updateUI() {
                super.updateUI();
                // The renderer is not in the component tree; a new one picks up the colors of a new theme
                setDefaultRenderer(Object.class, new ValueRenderer(model));
            }
        };
        @Nullable
        private final DBNNode folder;
        @Nullable
        private final List<DBNNode> items;
        @Nullable
        private AbstractJob job;
        private int serial;
        private boolean loaded;
        private String searchText = "";
        private String status = " ";

        ListView(@Nullable DBNNode folder, @Nullable List<DBNNode> items) {
            super(new BorderLayout());
            this.folder = folder;
            this.items = items;
            table.setRowSorter(sorter);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            table.setFillsViewportHeight(true);
            table.setShowHorizontalLines(false);
            table.setShowVerticalLines(false);
            table.setIntercellSpacing(new Dimension(0, 0));
            table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                        revealSelected();
                    }
                }
            });
            table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chromite.reveal"); //$NON-NLS-1$
            table.getActionMap().put("chromite.reveal", new AbstractAction() { //$NON-NLS-1$
                @Override
                public void actionPerformed(ActionEvent e) {
                    revealSelected();
                }
            });
            JScrollPane scrollPane = new JScrollPane(table);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            add(scrollPane, BorderLayout.CENTER);
        }

        void showing() {
            statusLabel.setText(status);
            if (!loaded) {
                load(false);
            }
        }

        void cancel() {
            serial++;
            if (job != null) {
                job.cancel();
                job = null;
            }
        }

        private void setStatus(@NotNull String text) {
            status = text;
            if (tabs == null || tabs.getSelectedComponent() == this) {
                statusLabel.setText(text);
            }
        }

        /**
         * Reads the children and their values: the rows with the plain values first, then the lazy values in batches.
         *
         * @param refresh read the folder from the database again first
         */
        void load(boolean refresh) {
            cancel();
            loaded = true;
            int loadSerial = serial;
            setStatus(CoreMessages.common_loading);
            job = new AbstractJob("Read list of " + (folder != null ? folder.getNodeDisplayName() : node.getNodeDisplayName())) {
                @NotNull
                @Override
                protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                    try {
                        List<DBNNode> children;
                        if (folder != null) {
                            DBNNode source = folder;
                            if (refresh) {
                                DBNNode refreshed = folder.refreshNode(monitor, ObjectDetailsPanel.this);
                                source = refreshed != null ? refreshed : folder;
                            }
                            children = readChildren(monitor, source);
                        } else {
                            children = items != null ? items : List.of();
                        }
                        readRows(monitor, loadSerial, children);
                    } catch (Throwable e) {
                        log.debug("Error reading the list of " + node.getNodeDisplayName(), e);
                        SwingUtilities.invokeLater(() -> {
                            if (loadSerial == serial) {
                                model.setContent(List.of(), List.of());
                                setStatus(NLS.bind(ObjectExplorerMessages.oe_details_error,
                                    folder != null ? ObjectExplorerPanel.getNodeText(folder) : ObjectExplorerPanel.getNodeText(node), ErrorText.describe(e)));
                            }
                        });
                    }
                    return Status.OK_STATUS;
                }
            };
            job.schedule();
        }

        /**
         * Job thread: columns of the children's classes, the plain values, then the lazy values.
         */
        private void readRows(@NotNull DBRProgressMonitor monitor, int loadSerial, @NotNull List<DBNNode> children) throws DBException {
            // Inline children (index and key columns, Oracle dependency entries) follow their item as indented rows, as the
            // SWT list shows them under it
            List<DBNNode> entries = new ArrayList<>(children.size());
            List<Integer> depths = new ArrayList<>(children.size());
            for (DBNNode child : children) {
                entries.add(child);
                depths.add(0);
                if (child instanceof DBNDatabaseNode databaseNode && hasInlineChildren(databaseNode)) {
                    for (DBNNode inline : readChildren(monitor, databaseNode)) {
                        entries.add(inline);
                        depths.add(1);
                    }
                }
            }
            List<DBSObject> objects = new ArrayList<>();
            Set<Class<?>> classes = new LinkedHashSet<>();
            for (DBNNode child : entries) {
                if (child instanceof DBNDatabaseNode databaseNode && databaseNode.getObject() != null) {
                    objects.add(databaseNode.getObject());
                    classes.add(databaseNode.getObject().getClass());
                }
            }
            List<ObjectPropertyDescriptor> columns = new ArrayList<>();
            if (!classes.isEmpty()) {
                for (ObjectPropertyDescriptor property : ObjectAttributeDescriptor.extractAnnotations(null, new ArrayList<>(classes),
                    new DataSourcePropertyFilter(node.getDataSourceContainer()))) {
                    // The first column already shows the node text (the name, with the type of an overloaded object)
                    if (property.isViewable() && !property.isHidden() && !DBConstants.PROP_ID_NAME.equals(property.getId())
                        && objects.stream().anyMatch(object -> isVisible(property, object))) {
                        columns.add(property);
                    }
                }
            }
            List<Row> rows = new ArrayList<>(entries.size());
            boolean anyLazy = false;
            for (int entry = 0; entry < entries.size(); entry++) {
                if (monitor.isCanceled()) {
                    return;
                }
                DBNNode child = entries.get(entry);
                Object object = child instanceof DBNDatabaseNode databaseNode ? databaseNode.getObject() : null;
                Object[] values = new Object[columns.size()];
                for (int i = 0; i < columns.size(); i++) {
                    ObjectPropertyDescriptor property = columns.get(i);
                    if (object == null || !property.getDeclaringClass().isInstance(object)) {
                        values[i] = null;
                    } else if (property.isLazy(object, true)) {
                        values[i] = PENDING;
                        anyLazy = true;
                    } else {
                        values[i] = readValue(monitor, property, object);
                    }
                }
                rows.add(new Row(child, values, depths.get(entry)));
            }
            // As ObjectListControl: an optional property is a column only if some row has a value (lazy ones are kept)
            List<Integer> keep = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                int column = i;
                if (!columns.get(i).isOptional() || rows.stream().anyMatch(row -> row.values()[column] != null)) {
                    keep.add(i);
                }
            }
            List<ObjectPropertyDescriptor> keptColumns = keep.stream().map(columns::get).toList();
            List<Row> keptRows = rows.stream()
                .map(row -> new Row(row.node(), keep.stream().map(i -> row.values()[i]).toArray(), row.depth()))
                .toList();
            boolean readLazy = anyLazy;
            SwingUtilities.invokeLater(() -> {
                if (loadSerial == serial) {
                    model.setContent(keptColumns, keptRows);
                    installComparators();
                    packColumns();
                    applySearch();
                    setStatus(readLazy ? NLS.bind(ObjectExplorerMessages.oe_details_reading_values, keptRows.size())
                        : NLS.bind(ObjectExplorerMessages.oe_details_count, keptRows.size()));
                }
            });
            if (!readLazy) {
                return;
            }
            // Second pass: the lazy values (one database read each), shown in batches
            List<Runnable> batch = new ArrayList<>();
            for (int rowIndex = 0; rowIndex < keptRows.size(); rowIndex++) {
                if (monitor.isCanceled()) {
                    return;
                }
                Row row = keptRows.get(rowIndex);
                Object object = row.node() instanceof DBNDatabaseNode databaseNode ? databaseNode.getObject() : null;
                Object[] lazyValues = row.values().clone();
                for (int i = 0; i < keptColumns.size(); i++) {
                    if (lazyValues[i] == PENDING) {
                        lazyValues[i] = object == null ? null : readValue(monitor, keptColumns.get(i), object);
                    }
                }
                int finalRow = rowIndex;
                batch.add(() -> {
                    System.arraycopy(lazyValues, 0, row.values(), 0, lazyValues.length);
                    model.fireTableRowsUpdated(finalRow, finalRow);
                });
                if (batch.size() >= LAZY_BATCH || rowIndex == keptRows.size() - 1) {
                    List<Runnable> updates = new ArrayList<>(batch);
                    batch.clear();
                    boolean last = rowIndex == keptRows.size() - 1;
                    SwingUtilities.invokeLater(() -> {
                        if (loadSerial == serial) {
                            updates.forEach(Runnable::run);
                            if (last) {
                                setStatus(NLS.bind(ObjectExplorerMessages.oe_details_count, keptRows.size()));
                            }
                        }
                    });
                }
            }
        }

        @Nullable
        private Object readValue(@NotNull DBRProgressMonitor monitor, @NotNull ObjectPropertyDescriptor property, @NotNull Object object) {
            try {
                return property.readValue(object, monitor, true);
            } catch (Throwable e) {
                log.debug("Can't read property " + property.getId(), e);
                return null;
            }
        }

        private boolean isVisible(@NotNull ObjectPropertyDescriptor property, @NotNull Object object) {
            try {
                return !property.getDeclaringClass().isInstance(object) || property.isPropertyVisible(object, object);
            } catch (Throwable e) {
                return false;
            }
        }

        void setSearchText(@NotNull String text) {
            searchText = text.trim().toLowerCase(Locale.ROOT);
            applySearch();
        }

        private void applySearch() {
            if (searchText.isEmpty()) {
                sorter.setRowFilter(null);
                return;
            }
            sorter.setRowFilter(new RowFilter<>() {
                @Override
                public boolean include(Entry<? extends DetailsModel, ? extends Integer> entry) {
                    Row row = model.rows.get(entry.getIdentifier());
                    if (ObjectExplorerPanel.getNodeText(row.node()).toLowerCase(Locale.ROOT).contains(searchText)) {
                        return true;
                    }
                    for (Object value : row.values()) {
                        if (formatValue(value).toLowerCase(Locale.ROOT).contains(searchText)) {
                            return true;
                        }
                    }
                    return false;
                }
            });
        }

        private void revealSelected() {
            int viewRow = table.getSelectedRow();
            if (viewRow >= 0) {
                reveal.accept(model.rows.get(table.convertRowIndexToModel(viewRow)).node());
            }
        }

        /**
         * Sorting by the raw values: numbers and dates by value, nodes by their text, the rest by display text.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void installComparators() {
            Comparator<Object> comparator = (a, b) -> {
                Object left = a == PENDING ? null : a;
                Object right = b == PENDING ? null : b;
                if (left == null || right == null) {
                    return left == null ? (right == null ? 0 : -1) : 1;
                }
                if (left instanceof DBNNode leftNode && right instanceof DBNNode rightNode) {
                    return ObjectExplorerPanel.getNodeText(leftNode).compareToIgnoreCase(ObjectExplorerPanel.getNodeText(rightNode));
                }
                if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
                    return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
                }
                if (left instanceof Comparable comparable && left.getClass() == right.getClass()) {
                    return comparable.compareTo(right);
                }
                return formatValue(left).compareToIgnoreCase(formatValue(right));
            };
            // Indented inline rows belong under their item: such a list keeps its order
            boolean nested = model.rows.stream().anyMatch(row -> row.depth() > 0);
            for (int column = 0; column < model.getColumnCount(); column++) {
                sorter.setComparator(column, comparator);
                sorter.setSortable(column, !nested);
            }
        }

        /**
         * Widths from the header and the first rows, capped so that long texts do not take the window.
         */
        private void packColumns() {
            FontMetrics metrics = table.getFontMetrics(table.getFont());
            int padding = UIScale.scale(20);
            int rowsToMeasure = Math.min(model.rows.size(), 50);
            for (int column = 0; column < model.getColumnCount(); column++) {
                int width = metrics.stringWidth(model.getColumnName(column)) + padding;
                for (int row = 0; row < rowsToMeasure; row++) {
                    width = Math.max(width, metrics.stringWidth(formatValue(model.getValueAt(row, column))) + padding
                        + (column == 0 ? UIScale.scale(20) : 0));
                }
                table.getColumnModel().getColumn(column).setPreferredWidth(Math.min(width, UIScale.scale(MAX_COLUMN_WIDTH)));
            }
        }
    }

    /**
     * Columns: the node text with its icon, then the properties. Sorting uses the raw values (numbers, dates).
     */
    private static final class DetailsModel extends AbstractTableModel {
        private List<ObjectPropertyDescriptor> columns = List.of();
        private List<Row> rows = List.of();

        void setContent(@NotNull List<ObjectPropertyDescriptor> columns, @NotNull List<Row> rows) {
            boolean structure = !columns.equals(this.columns);
            this.columns = columns;
            this.rows = rows;
            if (structure) {
                fireTableStructureChanged();
            } else {
                fireTableDataChanged();
            }
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.size() + 1;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? ObjectExplorerMessages.oe_details_name : columns.get(column - 1).getDisplayName();
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return Object.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            return columnIndex == 0 ? row.node() : row.values()[columnIndex - 1];
        }
    }

    /**
     * The name column shows the node icon (an inline child indented under its item); numbers are right aligned.
     */
    private static final class ValueRenderer extends DefaultTableCellRenderer {
        private final DetailsModel model;

        ValueRenderer(@NotNull DetailsModel model) {
            this.model = model;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            String text = value instanceof DBNNode nodeValue ? ObjectExplorerPanel.getNodeText(nodeValue) : formatValue(value);
            super.getTableCellRendererComponent(table, text, isSelected, false, row, column);
            setIcon(value instanceof DBNNode nodeValue ? NodeIcons.getNodeIcon(nodeValue, false) : null);
            setHorizontalAlignment(value instanceof Number ? SwingConstants.RIGHT : SwingConstants.LEFT);
            int modelRow = table.convertRowIndexToModel(row);
            int depth = column == 0 && modelRow >= 0 && modelRow < model.rows.size() ? model.rows.get(modelRow).depth() : 0;
            setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(6 + depth * 18), 0, UIScale.scale(6)));
            setToolTipText(text.length() > 40 ? text : null);
            return this;
        }
    }
}
