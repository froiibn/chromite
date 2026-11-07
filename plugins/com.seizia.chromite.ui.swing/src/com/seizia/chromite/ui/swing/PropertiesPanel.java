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
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPNamedObject;
import com.seizia.chromite.model.DBValueFormatting;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.preferences.DBPPropertySource;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSWrapper;
import com.seizia.chromite.runtime.properties.PropertyCollector;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Properties 도구 창(SSMS 속성 창): Object Explorer에서 선택한 노드의 이름, 종류, 설명과 객체 속성을 속성 그리드로 보여 준다.
 *
 * 설계: SWT 버전의 속성 보기와 같은 모델을 쓴다({@code NavigatorAdapterFactory}의 {@code IPropertySource} 변환).
 * 노드가 감싼 객체({@link DBSWrapper#getObject()}, 아니면 노드 자체)가 {@link DBPPropertySource}면 그것을,
 * 아니면 {@link PropertyCollector}로 {@code @Property} 게터를 모은다. 둘 다 UI에 의존하지 않는다.
 * <ul>
 *     <li>속성 값 읽기는 게으른 속성(DB 조회가 필요한 값)을 포함하므로 백그라운드 Job에서 {@link DBRProgressMonitor}를 넘겨 읽는다.
 *     선택이 바뀌면 이전 Job을 취소하고, 늦게 끝난 이전 결과는 번호로 걸러 버린다.</li>
 *     <li>읽기 전용이다. 속성 편집은 객체 편집기 단계에서 다룬다.</li>
 *     <li>값은 객체면 이름, 배열·컬렉션이면 쉼표로 이은 이름, 나머지는 {@link DBValueFormatting}의 기본 표시 문자열로 보여 준다.</li>
 *     <li>속성 그리드(SSMS 배치, DataGrip 모양, design-spec "10. Properties 창"): 머리말 아래 도구 막대의 분류별·이름순 단추, 열 머리글 없는 이름 | 값 두 열,
 *     분류 줄은 셰브런 + semibold 이름이고 누르면(또는 ←/→, Enter) 접고 편다. 행 24, 가로선 grid.lineSubtle, 이름과 값 사이 1px border.subtle.
 *     분류 줄 바탕은 {@code Chromite.PropertyGrid.categoryBackground}(Islands {@code dialog-bg-inline}), 속성 줄 바탕은 표 바탕(카드 바탕)이라 SSMS처럼 분류가 띠로 보인다. 분류 없는 속성은 SWT 속성 보기처럼 제목 없이 맨 앞에 둔다.
 *     아래 설명 칸은 고른 속성의 이름과 설명({@link DBPPropertyDescriptor#getDescription()})이다.</li>
 *     <li>접은 분류와 정렬 방식은 실행 중에 기억한다(다른 노드를 골라도 같은 분류는 접힌 채로 둔다, SSMS와 같음).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 P1: 머리글 있는 표 → SSMS식 속성 그리드(분류별·이름순, 분류 접기, 설명 칸) (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-07 밀도 B: 치수를 테마의 Chromite.size.* 키({@link UiSizes})에서 읽음 (docs/chromite/design-spec.md "치수 토큰")
 * - 2026-11-07 제목을 Object Explorer와 같은 노드 글자로(이름 없는 반환 값 매개 변수, docs/chromite/swing-frontend.md "Object Explorer 탐색 불가 항목")
 * - 2026-11-08 분류 행 semibold 글꼴 주석을 고침: deriveFont도 대체 글꼴을 유지하며, semibold.font를 쓰는 이유는 Java에 semibold 스타일이 없어서다
 *   (docs/chromite/swing-followup-fixes.md "작은 화면 수정")
 * - 2026-11-08 촘촘한 모드: 행 높이를 UI가 바뀔 때 다시 정함(실행 중 밀도 전환) (docs/chromite/design-spec.md "적용 현황 — 촘촘한 모드")
 */
final class PropertiesPanel extends JPanel {

    private static final Log log = Log.getLog(PropertiesPanel.class);

    private static final int DESCRIPTION_HEIGHT = 72;
    private static final int CHEVRON_WIDTH = 16;

    private final JLabel nameLabel = new JLabel();
    private final JLabel typeLabel = new JLabel();
    private final JLabel descriptionLabel = new JLabel();
    private final PropertyGridModel gridModel = new PropertyGridModel();
    private final JTable grid;
    private final JLabel propertyNameLabel = new JLabel(" ");
    private final JTextArea propertyDescriptionText = new JTextArea();
    private final Set<String> collapsedCategories = new HashSet<>();
    /**
     * Properties of the shown node by category (an empty name for uncategorized ones), or a message (loading, error).
     */
    private List<Category> categories = List.of();
    @Nullable
    private String message;
    private boolean alphabetical;

    private int loadSerial;
    @Nullable
    private AbstractJob loadJob;

    PropertiesPanel() {
        super(new BorderLayout());

        nameLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4"); //$NON-NLS-1$
        typeLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
        typeLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        JPanel headerPanel = new JPanel(new GridLayout(0, 1, 0, 2));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        headerPanel.add(nameLabel);
        headerPanel.add(typeLabel);
        headerPanel.add(descriptionLabel);

        grid = new JTable(gridModel) {
            @Override
            public void updateUI() {
                super.updateUI();
                // size.row.properties, set again on theme and density changes (UiDensity)
                setRowHeight(UiSizes.get(UiSizes.PROPERTY_ROW_HEIGHT));
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                paintGridLines(this, g);
            }
        };
        grid.setTableHeader(null);
        grid.setShowGrid(false);
        grid.setIntercellSpacing(new Dimension(0, 0));
        grid.setFillsViewportHeight(true);
        grid.setRowSelectionAllowed(true);
        grid.setColumnSelectionAllowed(false);
        grid.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        grid.setDefaultRenderer(Object.class, new GridRenderer());
        grid.getColumnModel().getColumn(0).setPreferredWidth(140);
        grid.getColumnModel().getColumn(1).setPreferredWidth(180);
        grid.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDescription();
            }
        });
        grid.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = grid.rowAtPoint(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) && row >= 0 && gridModel.getRow(row).kind() == RowKind.CATEGORY) {
                    toggleCategory(gridModel.getRow(row).name(), null);
                }
            }
        });
        bindKey(KeyEvent.VK_LEFT, "chromite.collapseCategory", () -> setSelectedCategoryCollapsed(true)); //$NON-NLS-1$
        bindKey(KeyEvent.VK_RIGHT, "chromite.expandCategory", () -> setSelectedCategoryCollapsed(false)); //$NON-NLS-1$
        bindKey(KeyEvent.VK_ENTER, "chromite.toggleCategory", () -> { //$NON-NLS-1$
            int row = grid.getSelectedRow();
            if (row >= 0 && gridModel.getRow(row).kind() == RowKind.CATEGORY) {
                toggleCategory(gridModel.getRow(row).name(), null);
            }
        });

        JScrollPane scrollPane = new JScrollPane(grid);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        JPanel top = new JPanel(new BorderLayout());
        top.add(headerPanel, BorderLayout.NORTH);
        top.add(createToolBar(), BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(createDescriptionArea(), BorderLayout.SOUTH);
        showNode(null);
    }

    /**
     * Shows the properties of a navigator node, or clears the panel for {@code null}.
     */
    void showNode(@Nullable DBNNode node) {
        int serial = ++loadSerial;
        if (loadJob != null) {
            loadJob.cancel();
            loadJob = null;
        }
        if (node == null) {
            nameLabel.setText(CoreMessages.properties_no_selection);
            typeLabel.setText(" ");
            descriptionLabel.setText(" ");
            setContent(List.of(), null);
            return;
        }
        nameLabel.setText(ObjectExplorerPanel.getNodeText(node));
        typeLabel.setText(CommonUtils.notEmpty(node.getNodeTypeLabel()));
        String description = node.getNodeDescription();
        descriptionLabel.setText(CommonUtils.isEmpty(description) ? " " : description);
        descriptionLabel.setToolTipText(CommonUtils.isEmpty(description) ? null : description);
        setContent(List.of(), CoreMessages.common_loading);

        loadJob = new AbstractJob("Read properties of " + node.getNodeDisplayName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<Category> result;
                String resultMessage = null;
                try {
                    result = readProperties(monitor, node);
                    if (result.isEmpty()) {
                        resultMessage = CoreMessages.properties_none;
                    }
                } catch (Throwable e) {
                    log.debug("Error reading properties of " + node.getNodeDisplayName(), e);
                    result = List.of();
                    resultMessage = NLS.bind(CoreMessages.properties_error, ErrorText.describe(e));
                }
                if (!monitor.isCanceled()) {
                    List<Category> finalResult = result;
                    String finalMessage = resultMessage;
                    SwingUtilities.invokeLater(() -> {
                        if (serial == loadSerial) {
                            setContent(finalResult, finalMessage);
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        };
        loadJob.schedule();
    }

    /**
     * Collects the properties grouped by category, in the order the categories first appear.
     */
    @NotNull
    private static List<Category> readProperties(@NotNull DBRProgressMonitor monitor, @NotNull DBNNode node) {
        Object object = node instanceof DBSWrapper wrapper && wrapper.getObject() != null ? wrapper.getObject() : node;
        DBPPropertySource propertySource;
        if (object instanceof DBPPropertySource ownSource) {
            propertySource = ownSource;
        } else {
            PropertyCollector collector = new PropertyCollector(node, object, false);
            collector.collectProperties();
            propertySource = collector;
        }

        Map<String, List<Property>> byCategory = new LinkedHashMap<>();
        // Uncategorized properties come first without a heading, as in the SWT properties view
        byCategory.put("", new ArrayList<>());
        for (DBPPropertyDescriptor property : propertySource.getProperties()) {
            if (monitor.isCanceled()) {
                break;
            }
            if (property.isHidden()) {
                continue;
            }
            Object value = propertySource.getPropertyValue(monitor, property.getId());
            byCategory.computeIfAbsent(CommonUtils.notEmpty(property.getCategory()), c -> new ArrayList<>())
                .add(new Property(property.getDisplayName(), formatValue(value), CommonUtils.notEmpty(property.getDescription())));
        }
        List<Category> result = new ArrayList<>();
        byCategory.forEach((name, properties) -> {
            if (!properties.isEmpty()) {
                result.add(new Category(name, properties));
            }
        });
        return result;
    }

    @NotNull
    private static String formatValue(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof DBPNamedObject namedObject) {
            return CommonUtils.notEmpty(namedObject.getName());
        }
        if (value instanceof Object[] array) {
            return Stream.of(array).map(PropertiesPanel::formatValue).collect(Collectors.joining(", ")); //$NON-NLS-1$
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(PropertiesPanel::formatValue).collect(Collectors.joining(", ")); //$NON-NLS-1$
        }
        return CommonUtils.notEmpty(DBValueFormatting.getDefaultValueDisplayString(value, DBDDisplayFormat.UI));
    }

    //////////////////////////////////////////////////////////
    // Grid rows

    private void setContent(@NotNull List<Category> categories, @Nullable String message) {
        this.categories = categories;
        this.message = message;
        rebuildRows(null);
    }

    /**
     * Rows of the current mode: categories with their (expanded) properties, or all properties by name.
     *
     * @param selectName row to select afterwards (a category name), or null for none
     */
    private void rebuildRows(@Nullable String selectName) {
        List<GridRow> rows = new ArrayList<>();
        if (message != null) {
            rows.add(new GridRow(RowKind.MESSAGE, message, "", "", false));
        } else if (alphabetical) {
            categories.stream()
                .flatMap(category -> category.properties().stream())
                .sorted(Comparator.comparing(Property::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(property -> rows.add(new GridRow(RowKind.PROPERTY, property.name(), property.value(), property.description(), false)));
        } else {
            for (Category category : categories) {
                boolean headed = !category.name().isEmpty();
                if (headed) {
                    rows.add(new GridRow(RowKind.CATEGORY, category.name(), "", "", false));
                    if (collapsedCategories.contains(category.name())) {
                        continue;
                    }
                }
                for (Property property : category.properties()) {
                    rows.add(new GridRow(RowKind.PROPERTY, property.name(), property.value(), property.description(), headed));
                }
            }
        }
        gridModel.setRows(rows);
        if (selectName != null) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).kind() == RowKind.CATEGORY && rows.get(i).name().equals(selectName)) {
                    grid.setRowSelectionInterval(i, i);
                    break;
                }
            }
        }
        showDescription();
    }

    /**
     * @param collapse true to collapse, false to expand, null to toggle
     */
    private void toggleCategory(@NotNull String name, @Nullable Boolean collapse) {
        boolean collapsed = collapse != null ? collapse : !collapsedCategories.contains(name);
        if (collapsed) {
            collapsedCategories.add(name);
        } else {
            collapsedCategories.remove(name);
        }
        rebuildRows(name);
    }

    /**
     * Left / Right: collapse or expand the selected category; on a property, Left goes to its category.
     */
    private void setSelectedCategoryCollapsed(boolean collapse) {
        int row = grid.getSelectedRow();
        if (row < 0 || alphabetical) {
            return;
        }
        GridRow selected = gridModel.getRow(row);
        if (selected.kind() == RowKind.CATEGORY) {
            toggleCategory(selected.name(), collapse);
        } else if (collapse && selected.indented()) {
            for (int i = row - 1; i >= 0; i--) {
                if (gridModel.getRow(i).kind() == RowKind.CATEGORY) {
                    grid.setRowSelectionInterval(i, i);
                    grid.scrollRectToVisible(grid.getCellRect(i, 0, true));
                    break;
                }
            }
        }
    }

    private void setAlphabetical(boolean alphabetical) {
        if (this.alphabetical != alphabetical) {
            this.alphabetical = alphabetical;
            rebuildRows(null);
        }
    }

    private void showDescription() {
        int row = grid.getSelectedRow();
        GridRow selected = row >= 0 ? gridModel.getRow(row) : null;
        if (selected == null || selected.kind() == RowKind.MESSAGE) {
            propertyNameLabel.setText(" ");
            propertyDescriptionText.setText("");
        } else {
            propertyNameLabel.setText(selected.name());
            propertyDescriptionText.setText(selected.description());
            propertyDescriptionText.setCaretPosition(0);
        }
    }

    private void bindKey(int keyCode, @NotNull String actionKey, @NotNull Runnable task) {
        grid.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), actionKey);
        grid.getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        });
    }

    //////////////////////////////////////////////////////////
    // Layout

    /**
     * SSMS property grid tool bar: Categorized | Alphabetical (icon toggle buttons, 32 high like the Object Explorer tool bar).
     */
    @NotNull
    private JComponent createToolBar() {
        JToggleButton categorizedButton = createToggle(CoreMessages.properties_categorized, "categorized", () -> setAlphabetical(false)); //$NON-NLS-1$
        JToggleButton alphabeticalButton = createToggle(CoreMessages.properties_alphabetical, "sort-alpha", () -> setAlphabetical(true)); //$NON-NLS-1$
        ButtonGroup group = new ButtonGroup();
        group.add(categorizedButton);
        group.add(alphabeticalButton);
        categorizedButton.setSelected(true);
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        toolBar.add(categorizedButton);
        toolBar.add(alphabeticalButton);
        return toolBar;
    }

    @NotNull
    private static JToggleButton createToggle(@NotNull String toolTip, @NotNull String iconId, @NotNull Runnable task) {
        JToggleButton button = new JToggleButton(UiIcons.get(iconId));
        button.setToolTipText(toolTip);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.putClientProperty(FlatClientProperties.STYLE, "arc: 6"); //$NON-NLS-1$
        button.setFocusable(false);
        button.addActionListener(e -> task.run());
        return button;
    }

    /**
     * Description area at the bottom (SSMS): the selected property's name in semibold and its description, wrapped,
     * below a border.subtle line.
     */
    @NotNull
    private JComponent createDescriptionArea() {
        propertyNameLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
        propertyDescriptionText.setEditable(false);
        propertyDescriptionText.setFocusable(false);
        propertyDescriptionText.setLineWrap(true);
        propertyDescriptionText.setWrapStyleWord(true);
        propertyDescriptionText.setOpaque(false);
        propertyDescriptionText.setBorder(BorderFactory.createEmptyBorder());
        propertyDescriptionText.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JPanel area = new JPanel(new BorderLayout(0, UIScale.scale(2))) {
            @Override
            public void updateUI() {
                super.updateUI();
                Color lineColor = UIManager.getColor("Chromite.dividerColor"); //$NON-NLS-1$
                setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, lineColor != null ? lineColor : Color.GRAY),
                    BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(12), UIScale.scale(6), UIScale.scale(12))));
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(super.getPreferredSize().width, UIScale.scale(DESCRIPTION_HEIGHT));
            }
        };
        area.add(propertyNameLabel, BorderLayout.NORTH);
        area.add(propertyDescriptionText, BorderLayout.CENTER);
        return area;
    }

    /**
     * Horizontal lines (grid.lineSubtle) under every row and, on property rows, the 1px border.subtle line between
     * the name and the value column.
     */
    private void paintGridLines(@NotNull JTable table, @NotNull Graphics g) {
        Rectangle clip = g.getClipBounds();
        if (clip == null || table.getRowCount() == 0) {
            return;
        }
        Color rowLine = UIManager.getColor("Chromite.Table.horizontalLineColor"); //$NON-NLS-1$
        Color columnLine = UIManager.getColor("Chromite.dividerColor"); //$NON-NLS-1$
        int first = Math.max(0, table.rowAtPoint(new Point(0, clip.y)));
        int last = table.rowAtPoint(new Point(0, clip.y + clip.height - 1));
        if (last < 0) {
            last = table.getRowCount() - 1;
        }
        int columnX = table.getColumnModel().getColumn(0).getWidth();
        for (int row = first; row <= last; row++) {
            Rectangle rect = table.getCellRect(row, 0, true);
            if (rowLine != null) {
                g.setColor(rowLine);
                g.fillRect(clip.x, rect.y + rect.height - 1, clip.width, 1);
            }
            if (columnLine != null && gridModel.getRow(row).kind() == RowKind.PROPERTY) {
                g.setColor(columnLine);
                g.fillRect(columnX - 1, rect.y, 1, rect.height);
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Model

    private record Property(@NotNull String name, @NotNull String value, @NotNull String description) {
    }

    private record Category(@NotNull String name, @NotNull List<Property> properties) {
    }

    private enum RowKind {
        CATEGORY, PROPERTY, MESSAGE
    }

    /**
     * @param indented a property under a category heading (its name lines up with the heading text)
     */
    private record GridRow(@NotNull RowKind kind, @NotNull String name, @NotNull String value, @NotNull String description, boolean indented) {
    }

    private static final class PropertyGridModel extends AbstractTableModel {
        private List<GridRow> rows = List.of();

        void setRows(@NotNull List<GridRow> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        @NotNull
        GridRow getRow(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            GridRow row = rows.get(rowIndex);
            return columnIndex == 0 ? row.name() : row.value();
        }
    }

    /**
     * Category rows: chevron and semibold name on the category band (Chromite.PropertyGrid.categoryBackground). Property rows: name (indented under a heading)
     * and value on the table background. The selection keeps the text color (selection.bg only). Names show their
     * description and values their full text as a tool tip.
     */
    private final class GridRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
            JTable table,
            Object value,
            boolean isSelected,
            boolean hasFocus,
            int row,
            int column
        ) {
            super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
            GridRow gridRow = gridModel.getRow(row);
            boolean categoryRow = gridRow.kind() == RowKind.CATEGORY;
            // font.ui.semibold: the theme's semibold.font, a family of its own because Java has no semibold style.
            // deriveFont(Font.BOLD) would keep the composite fallbacks too, but it gives bold, not semibold
            Font semibold = UIManager.getFont("semibold.font"); //$NON-NLS-1$
            setFont(categoryRow && semibold != null ? semibold : table.getFont());
            setIcon(null);
            int left = UIScale.scale(8);
            if (categoryRow && column == 0) {
                setIcon(UIManager.getIcon(collapsedCategories.contains(gridRow.name()) ? "Tree.collapsedIcon" : "Tree.expandedIcon")); //$NON-NLS-1$ //$NON-NLS-2$
                setIconTextGap(UIScale.scale(4));
            } else if (column == 0 && gridRow.indented()) {
                left += UIScale.scale(CHEVRON_WIDTH + 4);
            }
            setBorder(BorderFactory.createEmptyBorder(0, left, 0, UIScale.scale(8)));
            if (isSelected) {
                setBackground(UIManager.getColor(table.isFocusOwner() ? "Table.selectionBackground" : "Table.selectionInactiveBackground")); //$NON-NLS-1$ //$NON-NLS-2$
            } else {
                Color band = UIManager.getColor("Chromite.PropertyGrid.categoryBackground"); //$NON-NLS-1$
                setBackground(categoryRow && band != null ? band : table.getBackground());
            }
            Color foreground = gridRow.kind() == RowKind.MESSAGE ? UIManager.getColor("Chromite.secondaryForeground") : table.getForeground(); //$NON-NLS-1$
            setForeground(foreground != null ? foreground : table.getForeground());
            String toolTip = column == 0 ? gridRow.description() : gridRow.value();
            setToolTipText(CommonUtils.isEmpty(toolTip) || categoryRow ? null : toolTip);
            return this;
        }
    }
}
