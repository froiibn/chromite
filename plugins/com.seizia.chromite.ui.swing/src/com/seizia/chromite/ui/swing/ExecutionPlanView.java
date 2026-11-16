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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.PlanMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPNamedObject;
import com.seizia.chromite.model.DBValueFormatting;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.exec.plan.DBCPlan;
import com.seizia.chromite.model.exec.plan.DBCPlanNode;
import com.seizia.chromite.model.exec.plan.DBCPlanNodeKind;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.preferences.DBPPropertySource;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.runtime.properties.ObjectAttributeDescriptor;
import com.seizia.chromite.runtime.properties.ObjectPropertyDescriptor;
import com.seizia.chromite.runtime.properties.PropertyCollector;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Execution plan tab of a query window: the plans in three views (graphical, tree, raw), with the selected node's
 * properties beside the graphical and tree views.
 *
 * 설계: 계획 탭 자체 도구 막대의 분할 단추(그래픽 | 트리 | 원본)로 보기를 바꾼다. 마지막에 고른 보기는 사용자 설정
 * ({@value #PREF_VIEW})에 남겨 다음 계획 탭도 그 보기로 연다(기본은 그래픽).
 * <ul>
 *     <li>그래픽: {@link PlanDiagramView}(계획 모델을 모르는 그림)와 {@link PlanDiagramBuilder}(계획 → 그림 내용). 상자를 누르면 트리의
 *     같은 노드를 고르므로 오른쪽 속성 표는 두 보기가 함께 쓴다. 확대·축소·창에 맞추기 단추는 그래픽 보기에서만 보인다.</li>
 *     <li>트리: 아래의 트리 표(SWT 계획 보기를 옮긴 것).</li>
 *     <li>원본: {@link PlanRawView}. 서버가 돌려준 계획 글자라 속성 표 없이 탭 전체를 쓰고, 복사·저장 단추와 문장 목록이 보인다.</li>
 * </ul>
 * SQL 편집기({@code SqlEditorPanel})는 이 클래스의 API({@link #clear}, {@link #addEntry})만 쓰므로 세 보기를 더해도 바뀌지 않았다.
 *
 * 트리 보기 설계: SWT 편집기의 계획 보기({@code ExplainPlanViewer}의 단순 보기 {@code PlanNodesTree}, {@code SQLPlanTreeViewer})를 옮겼다.
 * <ul>
 *     <li>계획은 DBeaver 계획 모델({@link DBCPlan}, {@link DBCPlanNode})이라 DB마다 다른 화면을 만들지 않는다. 노드는
 *     {@code DBCPlan.getPlanNodes(keepOriginal)}로 받는다(SWT와 같은 옵션: 서버가 만든 구조를 그대로).</li>
 *     <li>열은 SWT 목록과 같이 노드 클래스의 {@code @Property(viewable = true)} 속성이다({@link ObjectAttributeDescriptor#extractAnnotations}).
 *     첫 열은 트리(노드 종류)이고, 같은 값을 다시 보이지 않도록 {@code nodeType} 속성은 뺀다. 문장마다 맨 위에
 *     "Query N" 행을 두고(SQL Server 계획 도구처럼 일괄 처리 대비 비용을 함께 보이는 문장 행) 그 아래에 계획 트리를 둔다.</li>
 *     <li>트리 표는 Swing에 없으므로 {@link JTable}에 펼친 행만 넣고 첫 열에 들여쓰기·펼침 화살표를 그린다. 첫 열 화살표를 누르거나
 *     행을 두 번 누르면 접고 편다. 처음에는 모두 펼친다(SWT {@code expandToLevel(10)}과 같은 의도).</li>
 *     <li>열 너비는 계획이 들어올 때 머리글과 보이는 행의 내용에 맞춘다({@link #fitColumns}, SWT 계획 표 {@code PlanNodesTree}의 상위 {@code ObjectListControl}이 내용을 채운 뒤 열을 다시 맞추는 것과 같은 의도).
 *     열이 보기보다 좁으면 보기를 채우고, 넓으면 가로로 스크롤한다. 모든 열을 보기 안에 밀어 넣으면(속성 열이 많은 SQL Server 계획)
 *     연산 이름이 잘렸다.</li>
 *     <li>SWT와 같이 테이블 전체 읽기({@link DBCPlanNodeKind#TABLE_SCAN})는 오류 색, 인덱스 전체 읽기({@link DBCPlanNodeKind#INDEX_SCAN})는
 *     경고 색으로 행 바탕을 옅게 칠한다(테마의 상태 색을 섞어 두 테마 모두에서 읽히게).</li>
 *     <li>오른쪽 상세 표는 선택한 노드의 모든 속성이다. 노드가 {@link DBPPropertySource}이면(PostgreSQL 노드의 계획 속성 전체) 그것을,
 *     아니면 {@link PropertyCollector}로 {@code @Property} 게터를 모은다(Properties 창과 같은 방법).</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-06 열이 너무 좁아 연산 이름이 잘리던 문제: 고정 비율로 보기에 맞추던 열을 내용에 맞추고, 넘치면 가로 스크롤 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 세 보기(그래픽·트리·원본)와 보기 전환 도구 막대, 마지막 보기 기억 (docs/chromite/swing-sql-editor-plus.md "실행 계획 세 보기")
 * - 2026-11-09 단축키를 플랫폼 명령 키로({@link UiKeys}: macOS Command, 앱 변경 #24) (docs/chromite/shortcuts.md), 빈 계획 안내와 확대·축소 도움말의 키 글자
 * - 2026-11-12 보기 전환(그래픽 / 트리 / 원본)의 좌우 여백을 {@link UiSizes#TEXT_TOOL_BUTTON_STYLE}로: 아이콘 단추 여백(4)이라 눌린 바탕과 글자 사이가 거의 없었다
 *   (앱 변경 #71 e, docs/chromite/design-spec.md "치수 토큰")
 */
final class ExecutionPlanView {

    private static final Log log = Log.getLog(ExecutionPlanView.class);

    /**
     * Preference: the last chosen view ({@link View} name).
     */
    static final String PREF_VIEW = "plan.view"; //$NON-NLS-1$
    private static final String CARD_SPLIT = "split"; //$NON-NLS-1$
    private static final String CARD_RAW = "raw"; //$NON-NLS-1$

    /**
     * The views of the plan tab, in the order of the switch.
     */
    enum View {
        GRAPHICAL,
        TREE,
        RAW
    }

    private static final String NODE_TYPE_PROPERTY = "nodeType"; //$NON-NLS-1$
    private static final int INDENT = 16;
    // Column fitting (fitColumns), unscaled pixels
    private static final int MEASURED_ROWS = 2000;
    private static final int MIN_OPERATION_WIDTH = 200;
    private static final int MAX_OPERATION_WIDTH = 700;
    private static final int MIN_PROPERTY_WIDTH = 60;
    private static final int MAX_PROPERTY_WIDTH = 360;
    private static final int COLUMN_PADDING = 12;

    /**
     * The plan of one statement, or why it could not be made.
     *
     * @param query  statement text
     * @param plan   the plan, or null when it failed
     * @param error  error message when there is no plan
     * @param actual true for a plan with run-time values (the statement was executed)
     */
    record Entry(@NotNull String query, @Nullable DBCPlan plan, @Nullable String error, boolean actual) {
    }

    // Declared before the tables: their models read them while the tables are created
    private final List<Entry> entries = new ArrayList<>();
    // Visible rows of the tree; nodes and entries are compared by identity
    private final List<Row> rows = new ArrayList<>();
    private final Set<Object> collapsed = Collections.newSetFromMap(new IdentityHashMap<>());
    private List<ObjectPropertyDescriptor> columns = List.of();
    private final JPanel component = new JPanel(new BorderLayout());
    private final JLabel headerLabel = new JLabel();
    private final PlanTableModel tableModel = new PlanTableModel();
    private final JTable table = new JTable(tableModel) {
        /**
         * Fills the view when the fitted columns are narrower than it, scrolls horizontally otherwise (squeezing every
         * column into the view cut the operation names).
         */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof JViewport viewport && getPreferredSize().width < viewport.getWidth();
        }
    };
    private final DetailsModel detailsModel = new DetailsModel();
    private final JTable detailsTable = new JTable(detailsModel);
    private final PlanDiagramView diagramView;
    private final PlanRawView rawView = new PlanRawView();
    private final CardLayout mainCards = new CardLayout();
    private final JPanel mainPanel = new JPanel(mainCards);
    private final CardLayout leftCards = new CardLayout();
    private final JPanel leftPanel = new JPanel(leftCards);
    private final Map<View, JToggleButton> viewButtons = new LinkedHashMap<>();
    private final List<Component> graphicalTools = new ArrayList<>();
    private final List<Component> rawTools = new ArrayList<>();
    private final JButton zoomButton = new JButton();
    private View view;

    ExecutionPlanView() {
        headerLabel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(10), 0, UIScale.scale(10)));
        headerLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        diagramView = new PlanDiagramView(new PlanDiagramView.Listener() {
            @Override
            public void selected(@Nullable Object userObject) {
                selectItem(userObject);
            }

            @Override
            public void zoomChanged() {
                updateZoomLabel();
            }
        });

        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowVerticalLines(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new CellRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetails();
                int index = table.getSelectedRow();
                diagramView.setSelected(index >= 0 && index < rows.size() ? rows.get(index).item : null);
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row < 0 || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                Row item = rows.get(row);
                Rectangle cell = table.getCellRect(row, 0, false);
                int arrowEnd = cell.x + UIScale.scale(4 + INDENT * (item.depth + 1));
                if ((e.getClickCount() == 1 && table.columnAtPoint(e.getPoint()) == 0 && e.getX() < arrowEnd) || e.getClickCount() == 2) {
                    toggle(item);
                }
            }
        });

        detailsTable.setFillsViewportHeight(true);
        detailsTable.setShowVerticalLines(false);
        detailsTable.getTableHeader().setReorderingAllowed(false);
        detailsTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(t, value, isSelected, false, row, column);
                setToolTipText(value == null || value.toString().isEmpty() ? null : value.toString());
                return this;
            }
        });

        JScrollPane treeScroll = new JScrollPane(table);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        JScrollPane diagramScroll = new JScrollPane(diagramView);
        diagramScroll.setBorder(BorderFactory.createEmptyBorder());
        JScrollPane detailsScroll = new JScrollPane(detailsTable);
        detailsScroll.setBorder(BorderFactory.createEmptyBorder());
        leftPanel.add(diagramScroll, View.GRAPHICAL.name());
        leftPanel.add(treeScroll, View.TREE.name());
        // The divider starts from the preferred widths: most of the room for the plan
        leftPanel.setPreferredSize(new Dimension(UIScale.scale(900), UIScale.scale(200)));
        detailsScroll.setPreferredSize(new Dimension(UIScale.scale(320), UIScale.scale(200)));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, detailsScroll);
        split.setResizeWeight(0.72);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        mainPanel.add(split, CARD_SPLIT);
        mainPanel.add(rawView.getComponent(), CARD_RAW);
        component.add(createToolBar(), BorderLayout.NORTH);
        component.add(mainPanel, BorderLayout.CENTER);
        bindZoomKeys();
        View saved = CommonUtils.valueOf(View.class, getPreferences().getString(PREF_VIEW), View.GRAPHICAL);
        showView(saved);
        clear();
    }

    //////////////////////////////////////////////////////////
    // Views

    /**
     * The plan tab's own tool bar: the view switch, the tools of the shown view, then the header text.
     */
    @NotNull
    private JComponent createToolBar() {
        SegmentedPanel switchPanel = new SegmentedPanel();
        ButtonGroup group = new ButtonGroup();
        addViewButton(switchPanel, group, View.GRAPHICAL, PlanMessages.plan_view_graphical, PlanMessages.plan_view_graphical_tip);
        addViewButton(switchPanel, group, View.TREE, PlanMessages.plan_view_tree, PlanMessages.plan_view_tree_tip);
        addViewButton(switchPanel, group, View.RAW, PlanMessages.plan_view_raw, PlanMessages.plan_view_raw_tip);

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        toolBar.add(switchPanel);

        // Graphical: zoom out, zoom ratio (resets to 100%), zoom in, fit
        zoomButton.setAction(createAction("100%", diagramView::zoomActual)); //$NON-NLS-1$
        zoomButton.setToolTipText(PlanMessages.plan_zoom_actual);
        zoomButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        zoomButton.setFocusable(false);
        addTool(toolBar, graphicalTools, Box.createHorizontalStrut(UIScale.scale(8)));
        addTool(toolBar, graphicalTools, UiIcons.createActionButton(createAction(NLS.bind(PlanMessages.plan_zoom_out, UiKeys.getCommandText()), diagramView::zoomOut), "zoom-out")); //$NON-NLS-1$
        addTool(toolBar, graphicalTools, zoomButton);
        addTool(toolBar, graphicalTools, UiIcons.createActionButton(createAction(NLS.bind(PlanMessages.plan_zoom_in, UiKeys.getCommandText()), diagramView::zoomIn), "zoom-in")); //$NON-NLS-1$
        addTool(toolBar, graphicalTools, UiIcons.createActionButton(createAction(PlanMessages.plan_zoom_fit, diagramView::zoomToFit), "zoom-fit")); //$NON-NLS-1$

        // Raw: statement picker, copy, save
        addTool(toolBar, rawTools, Box.createHorizontalStrut(UIScale.scale(8)));
        JComboBox<?> selector = rawView.getSelector();
        selector.setMaximumSize(new Dimension(UIScale.scale(180), selector.getPreferredSize().height));
        toolBar.add(selector);
        Action copyAction = createAction(PlanMessages.plan_raw_copy, rawView::copy);
        copyAction.putValue(Action.SHORT_DESCRIPTION, PlanMessages.plan_raw_copy_tip);
        Action saveAction = createAction(PlanMessages.plan_raw_save, rawView::saveAs);
        saveAction.putValue(Action.SHORT_DESCRIPTION, PlanMessages.plan_raw_save_tip);
        addTool(toolBar, rawTools, UiIcons.createToolButton(copyAction, "copy")); //$NON-NLS-1$
        addTool(toolBar, rawTools, UiIcons.createToolButton(saveAction, "save")); //$NON-NLS-1$

        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.add(toolBar, BorderLayout.WEST);
        bar.add(headerLabel, BorderLayout.CENTER);
        return bar;
    }

    private void addViewButton(@NotNull JPanel panel, @NotNull ButtonGroup group, @NotNull View target, @NotNull String text, @NotNull String toolTip) {
        JToggleButton button = new JToggleButton(text);
        button.setToolTipText(toolTip);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.putClientProperty(FlatClientProperties.STYLE, UiSizes.TEXT_TOOL_BUTTON_STYLE);
        button.setFocusable(false);
        button.addActionListener(e -> {
            showView(target);
            getPreferences().setValue(PREF_VIEW, target.name());
            PrefUtils.savePreferenceStore(getPreferences());
        });
        group.add(button);
        panel.add(button);
        viewButtons.put(target, button);
    }

    private static void addTool(@NotNull JToolBar toolBar, @NotNull List<Component> tools, @NotNull Component tool) {
        toolBar.add(tool);
        tools.add(tool);
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

    private void showView(@NotNull View newView) {
        view = newView;
        JToggleButton button = viewButtons.get(newView);
        if (button != null && !button.isSelected()) {
            button.setSelected(true);
        }
        for (Component tool : graphicalTools) {
            tool.setVisible(newView == View.GRAPHICAL);
        }
        for (Component tool : rawTools) {
            tool.setVisible(newView == View.RAW);
        }
        rawView.getSelector().setVisible(newView == View.RAW && rawView.getSelector().getItemCount() > 1);
        if (newView == View.RAW) {
            mainCards.show(mainPanel, CARD_RAW);
        } else {
            leftCards.show(leftPanel, newView.name());
            mainCards.show(mainPanel, CARD_SPLIT);
        }
        component.revalidate();
        component.repaint();
    }

    @NotNull
    View getView() {
        return view;
    }

    private void bindZoomKeys() {
        InputMap inputs = diagramView.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actions = diagramView.getActionMap();
        inputs.put(UiKeys.command(KeyEvent.VK_EQUALS), "zoomIn"); //$NON-NLS-1$
        inputs.put(UiKeys.command(KeyEvent.VK_ADD), "zoomIn"); //$NON-NLS-1$
        inputs.put(UiKeys.command(KeyEvent.VK_MINUS), "zoomOut"); //$NON-NLS-1$
        inputs.put(UiKeys.command(KeyEvent.VK_SUBTRACT), "zoomOut"); //$NON-NLS-1$
        inputs.put(UiKeys.command(KeyEvent.VK_0), "zoomActual"); //$NON-NLS-1$
        actions.put("zoomIn", createAction("zoomIn", diagramView::zoomIn)); //$NON-NLS-1$ //$NON-NLS-2$
        actions.put("zoomOut", createAction("zoomOut", diagramView::zoomOut)); //$NON-NLS-1$ //$NON-NLS-2$
        actions.put("zoomActual", createAction("zoomActual", diagramView::zoomActual)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void updateZoomLabel() {
        zoomButton.setText(Math.round(diagramView.getZoom() * 100) + "%"); //$NON-NLS-1$
    }

    /**
     * Selects a diagram item in the tree (which shows its properties); an item hidden in a collapsed branch only shows its properties.
     */
    private void selectItem(@Nullable Object item) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).item == item) {
                table.getSelectionModel().setSelectionInterval(i, i);
                table.scrollRectToVisible(table.getCellRect(i, 0, true));
                return;
            }
        }
        table.clearSelection();
        showDetails(item);
        diagramView.setSelected(item);
    }

    /**
     * Rebuilds the graphical and raw views from the entries (the tree rows are rebuilt by {@link #rebuild}).
     */
    private void refreshViews() {
        diagramView.setStatements(PlanDiagramBuilder.build(entries));
        rawView.setEntries(entries);
        showView(view);
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }

    /**
     * The view switch: toggle buttons side by side in one rounded outline.
     */
    private static final class SegmentedPanel extends JPanel {
        SegmentedPanel() {
            super(new FlowLayout(FlowLayout.LEFT, 0, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color border = UIManager.getColor("Component.borderColor"); //$NON-NLS-1$
                g2.setColor(border != null ? border : getForeground());
                float arc = UIScale.scale(8f);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, arc, arc));
            } finally {
                g2.dispose();
            }
        }
    }

    @NotNull
    JComponent getComponent() {
        return component;
    }

    boolean hasContent() {
        return !entries.isEmpty();
    }

    /**
     * Removes the plans and shows a message instead (e.g. while the plan is being made).
     */
    void clear(@NotNull String message) {
        entries.clear();
        collapsed.clear();
        columns = List.of();
        headerLabel.setText(message);
        rebuild();
        refreshViews();
    }

    void clear() {
        // The keys of Query > Display Estimated Plan and Include Actual Plan (MainFrame)
        clear(UiKeys.format(EditorMessages.editor_plan_empty, UiKeys.command(KeyEvent.VK_L), UiKeys.command(KeyEvent.VK_M)));
    }

    void addEntry(@NotNull Entry entry) {
        entries.add(entry);
        if (entry.plan() != null) {
            collectColumns();
        }
        updateHeader();
        rebuild();
        fitColumns();
        refreshViews();
        if (table.getSelectedRow() < 0 && !rows.isEmpty()) {
            table.getSelectionModel().setSelectionInterval(0, 0);
        }
    }

    private void updateHeader() {
        boolean actual = entries.stream().anyMatch(Entry::actual);
        long failed = entries.stream().filter(e -> e.plan() == null).count();
        // A plan can hold several statements (a SQL Server batch has one plan with a root node per statement)
        long statements = failed;
        for (Entry entry : entries) {
            if (entry.plan() != null) {
                statements += Math.max(1, getRootNodes(entry.plan()).size());
            }
        }
        String text = NLS.bind(actual
            ? statements == 1 ? EditorMessages.editor_plan_header_actual_one : EditorMessages.editor_plan_header_actual_many
            : statements == 1 ? EditorMessages.editor_plan_header_estimated_one : EditorMessages.editor_plan_header_estimated_many, statements);
        if (failed > 0) {
            text = NLS.bind(EditorMessages.editor_plan_header_failed, text, failed);
        }
        headerLabel.setText(text);
    }

    /**
     * Viewable properties of every node class in the plans, in property order (SWT list columns).
     */
    private void collectColumns() {
        Map<Class<?>, Boolean> classes = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry.plan() != null) {
                for (DBCPlanNode node : getRootNodes(entry.plan())) {
                    collectClasses(node, classes);
                }
            }
        }
        Map<String, ObjectPropertyDescriptor> byId = new LinkedHashMap<>();
        for (Class<?> nodeClass : classes.keySet()) {
            for (ObjectPropertyDescriptor property : ObjectAttributeDescriptor.extractAnnotations(null, nodeClass, null, null)) {
                if (property.isViewable() && !NODE_TYPE_PROPERTY.equals(property.getId())) {
                    byId.putIfAbsent(property.getId(), property);
                }
            }
        }
        List<ObjectPropertyDescriptor> newColumns = new ArrayList<>(byId.values());
        if (!newColumns.equals(columns)) {
            columns = newColumns;
            tableModel.fireTableStructureChanged();
        }
    }

    private static void collectClasses(@NotNull DBCPlanNode node, @NotNull Map<Class<?>, Boolean> classes) {
        classes.putIfAbsent(node.getClass(), Boolean.TRUE);
        for (DBCPlanNode child : CommonUtils.safeCollection(node.getNested())) {
            collectClasses(child, classes);
        }
    }

    /**
     * Fits the columns to their header and the shown rows, measured with the table's renderer (indentation and font
     * included). The operation column fits the plan nodes only: a statement row is the SQL text, which the details show
     * in full. Property values are capped so that one long value (a filter, an output list) does not take the view.
     */
    private void fitColumns() {
        boolean hasNodes = rows.stream().anyMatch(row -> row.item instanceof DBCPlanNode);
        int measuredRows = Math.min(rows.size(), MEASURED_ROWS);
        for (int column = 0; column < table.getColumnCount(); column++) {
            TableColumn tableColumn = table.getColumnModel().getColumn(column);
            TableCellRenderer headerRenderer = tableColumn.getHeaderRenderer() != null
                ? tableColumn.getHeaderRenderer() : table.getTableHeader().getDefaultRenderer();
            int width = headerRenderer.getTableCellRendererComponent(table, tableColumn.getHeaderValue(), false, false, -1, column)
                .getPreferredSize().width;
            for (int row = 0; row < measuredRows; row++) {
                if (column == 0 && hasNodes && !(rows.get(row).item instanceof DBCPlanNode)) {
                    continue;
                }
                width = Math.max(width, table.prepareRenderer(table.getCellRenderer(row, column), row, column).getPreferredSize().width);
            }
            int minWidth = UIScale.scale(column == 0 ? MIN_OPERATION_WIDTH : MIN_PROPERTY_WIDTH);
            int maxWidth = UIScale.scale(column == 0 ? MAX_OPERATION_WIDTH : MAX_PROPERTY_WIDTH);
            tableColumn.setMinWidth(minWidth);
            tableColumn.setPreferredWidth(Math.clamp(width + UIScale.scale(COLUMN_PADDING), minWidth, maxWidth));
        }
    }

    /**
     * The root nodes of a plan, as all three views show them.
     */
    @NotNull
    static List<? extends DBCPlanNode> getRootNodes(@NotNull DBCPlan plan) {
        // Keep the server's own node structure, as the SWT plan tree does
        return plan.getPlanNodes(Collections.singletonMap(DBCPlan.OPTION_KEEP_ORIGINAL, true));
    }

    //////////////////////////////////////////////////////////
    // Tree rows

    private void toggle(@NotNull Row row) {
        if (!row.expandable) {
            return;
        }
        if (!collapsed.remove(row.item)) {
            collapsed.add(row.item);
        }
        Object selected = table.getSelectedRow() >= 0 ? rows.get(table.getSelectedRow()).item : null;
        rebuild();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).item == selected) {
                table.getSelectionModel().setSelectionInterval(i, i);
                break;
            }
        }
    }

    private void rebuild() {
        rows.clear();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            List<? extends DBCPlanNode> roots = entry.plan() == null ? List.of() : getRootNodes(entry.plan());
            rows.add(new Row(entry, 0, !roots.isEmpty(), i + 1));
            if (!collapsed.contains(entry)) {
                for (DBCPlanNode root : roots) {
                    addNodeRows(root, 1);
                }
            }
        }
        tableModel.fireTableDataChanged();
        showDetails();
    }

    private void addNodeRows(@NotNull DBCPlanNode node, int depth) {
        Collection<? extends DBCPlanNode> nested = node.getNested();
        boolean expandable = !CommonUtils.isEmpty(nested);
        rows.add(new Row(node, depth, expandable, 0));
        if (expandable && !collapsed.contains(node)) {
            for (DBCPlanNode child : nested) {
                addNodeRows(child, depth + 1);
            }
        }
    }

    /**
     * A visible row: a statement ({@link Entry}) or a plan node.
     *
     * @param number one-based statement number for statement rows
     */
    private record Row(@NotNull Object item, int depth, boolean expandable, int number) {
    }

    @NotNull
    private String getTreeText(@NotNull Row row) {
        if (row.item instanceof Entry entry) {
            String firstLine = entry.query().strip().lines().findFirst().orElse("");
            String text = NLS.bind(EditorMessages.editor_plan_query, row.number, CommonUtils.truncateString(firstLine, 200));
            return entry.error() != null ? text + " - " + entry.error() : text;
        }
        DBCPlanNode node = (DBCPlanNode) row.item;
        String type = node.getNodeType();
        return CommonUtils.isEmpty(type) ? CommonUtils.notEmpty(node.getNodeName()) : type;
    }

    @Nullable
    private Object getValue(@NotNull Row row, int column) {
        if (column == 0) {
            return getTreeText(row);
        }
        if (!(row.item instanceof DBCPlanNode node)) {
            return null;
        }
        ObjectPropertyDescriptor property = columns.get(column - 1);
        if (!property.getGetter().getDeclaringClass().isInstance(node)) {
            return null;
        }
        try {
            return formatValue(property.readValue(node, null, true));
        } catch (Exception e) {
            log.debug("Can't read plan node property " + property.getId(), e);
            return null;
        }
    }

    @NotNull
    private static String formatValue(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof DBPNamedObject named) {
            return CommonUtils.notEmpty(named.getName());
        }
        if (value instanceof Double number && number == Math.rint(number) && Math.abs(number) < 1e15) {
            return String.valueOf(number.longValue());
        }
        return CommonUtils.notEmpty(DBValueFormatting.getDefaultValueDisplayString(value, DBDDisplayFormat.UI));
    }

    private final class PlanTableModel extends AbstractTableModel {
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
            return column == 0 ? EditorMessages.editor_plan_operation : columns.get(column - 1).getDisplayName();
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return getValue(rows.get(rowIndex), columnIndex);
        }
    }

    /**
     * Tree column with indentation, arrow and icon; scan rows tinted; numbers right-aligned.
     */
    private final class CellRenderer extends DefaultTableCellRenderer {
        private Row row;
        private boolean treeColumn;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int rowIndex, int column) {
            // The renderer keeps colors set for a cell as its "unselected" colors: start from the table's colors again
            setForeground(null);
            setBackground(null);
            super.getTableCellRendererComponent(t, value, isSelected, false, rowIndex, column);
            row = rows.get(rowIndex);
            treeColumn = column == 0;
            setFont(t.getFont().deriveFont(row.item instanceof Entry ? Font.BOLD : Font.PLAIN));
            setToolTipText(value == null || value.toString().isEmpty() ? null : value.toString());
            setHorizontalAlignment(!treeColumn && isNumeric(value) ? RIGHT : LEFT);
            if (treeColumn) {
                setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(4 + INDENT * (row.depth + 1)), 0, UIScale.scale(4)));
                setIcon(row.item instanceof Entry ? UiIcons.get("plan") : null); //$NON-NLS-1$
            } else {
                setIcon(null);
            }
            if (row.item instanceof Entry entry && entry.error() != null && !isSelected) {
                setForeground(UIManager.getColor("Chromite.status.error")); //$NON-NLS-1$
            }
            if (!isSelected && row.item instanceof DBCPlanNode node) {
                Color tint = switch (node.getNodeKind()) {
                    case TABLE_SCAN -> UIManager.getColor("Chromite.status.error"); //$NON-NLS-1$
                    case INDEX_SCAN -> UIManager.getColor("Chromite.status.warning"); //$NON-NLS-1$
                    default -> null;
                };
                if (tint != null) {
                    setBackground(blend(t.getBackground(), tint, 0.16f));
                }
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (treeColumn && row != null && row.expandable) {
                // Expand arrow left of the text, in the indentation of the row
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(UIManager.getColor("Label.disabledForeground")); //$NON-NLS-1$
                    int size = UIScale.scale(4);
                    int cx = UIScale.scale(4 + INDENT * row.depth + INDENT / 2);
                    int cy = getHeight() / 2;
                    Polygon arrow = new Polygon();
                    if (collapsed.contains(row.item)) {
                        arrow.addPoint(cx - size / 2, cy - size);
                        arrow.addPoint(cx + size / 2 + 1, cy);
                        arrow.addPoint(cx - size / 2, cy + size);
                    } else {
                        arrow.addPoint(cx - size, cy - size / 2);
                        arrow.addPoint(cx + size, cy - size / 2);
                        arrow.addPoint(cx, cy + size / 2 + 1);
                    }
                    g2.fillPolygon(arrow);
                } finally {
                    g2.dispose();
                }
            }
        }
    }

    private static boolean isNumeric(@Nullable Object value) {
        if (value == null) {
            return false;
        }
        String text = value.toString();
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isDigit(c) && c != '.' && c != ',' && c != '-' && c != 'E' && c != 'e') {
                return false;
            }
        }
        return true;
    }

    @NotNull
    private static Color blend(@NotNull Color base, @NotNull Color tint, float amount) {
        return new Color(
            Math.round(base.getRed() + (tint.getRed() - base.getRed()) * amount),
            Math.round(base.getGreen() + (tint.getGreen() - base.getGreen()) * amount),
            Math.round(base.getBlue() + (tint.getBlue() - base.getBlue()) * amount));
    }

    //////////////////////////////////////////////////////////
    // Details

    private void showDetails() {
        int index = table.getSelectedRow();
        showDetails(index >= 0 && index < rows.size() ? rows.get(index).item : null);
    }

    /**
     * Shows the properties of a statement or plan node in the details table.
     */
    private void showDetails(@Nullable Object item) {
        List<String[]> properties = new ArrayList<>();
        if (item != null) {
            if (item instanceof Entry entry) {
                properties.add(new String[]{EditorMessages.editor_plan_statement, entry.query().strip()});
                if (entry.plan() != null) {
                    try {
                        String planQuery = entry.plan().getPlanQueryString();
                        if (!planQuery.equals(entry.query()) && !planQuery.equals(entry.query().strip())) {
                            properties.add(new String[]{EditorMessages.editor_plan_plan_query, planQuery});
                        }
                    } catch (Exception e) {
                        log.debug("Can't read the plan query", e);
                    }
                }
                properties.add(new String[]{EditorMessages.editor_plan_plan, entry.actual() ? EditorMessages.editor_plan_actual : EditorMessages.editor_plan_estimated});
                if (entry.error() != null) {
                    properties.add(new String[]{EditorMessages.editor_plan_error, entry.error()});
                }
            } else if (item instanceof DBCPlanNode node) {
                readNodeProperties(node, properties);
            }
        }
        detailsModel.setProperties(properties);
    }

    private static void readNodeProperties(@NotNull DBCPlanNode node, @NotNull List<String[]> properties) {
        DBPPropertySource source;
        if (node instanceof DBPPropertySource ownSource) {
            source = ownSource;
        } else {
            PropertyCollector collector = new PropertyCollector(node, false);
            collector.collectProperties();
            source = collector;
        }
        VoidProgressMonitor monitor = new VoidProgressMonitor();
        for (DBPPropertyDescriptor property : source.getProperties()) {
            if (property.isHidden()) {
                continue;
            }
            try {
                String value = formatValue(source.getPropertyValue(monitor, property.getId()));
                if (!value.isEmpty()) {
                    properties.add(new String[]{property.getDisplayName(), value});
                }
            } catch (Exception e) {
                log.debug("Can't read plan node property " + property.getId(), e);
            }
        }
        if (!CommonUtils.isEmpty(node.getNodeDescription())
            && properties.stream().noneMatch(p -> p[1].equals(node.getNodeDescription()))) {
            properties.add(new String[]{EditorMessages.editor_common_description, node.getNodeDescription()});
        }
    }

    private static final class DetailsModel extends AbstractTableModel {
        private List<String[]> properties = List.of();

        void setProperties(@NotNull List<String[]> properties) {
            this.properties = properties;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return properties.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? EditorMessages.editor_plan_property : EditorMessages.editor_common_value;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return properties.get(rowIndex)[columnIndex];
        }
    }
}
