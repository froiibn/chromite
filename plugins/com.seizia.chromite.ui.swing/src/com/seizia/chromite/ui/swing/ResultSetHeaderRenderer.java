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

import com.formdev.flatlaf.util.UIScale;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDRowIdentifier;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 결과 그리드 열 머리글: 열 아이콘, 이름, 오른쪽 끝의 필터(깔때기)·정렬 아이콘(DataGrip 결과 그리드처럼).
 *
 * 설계:
 * <ul>
 *     <li>Look and Feel의 머리글 렌더러를 감싼다. 바탕·글꼴·구분선은 그 렌더러의 테두리가 그리므로 그대로 두고, 그 안쪽에 오른쪽 여백을
 *     잡아 아이콘을 그리는 테두리({@link ActionsBorder})를 겹친다. 그래서 열이 좁으면 이름만 "…"로 줄고 아이콘은 늘 보이며, 열 너비 맞춤
 *     ({@link ResultSetColumnFit})도 아이콘 몫을 그대로 잰다.</li>
 *     <li>필터·정렬 아이콘은 SVG 아이콘 팩 대신 Java2D로 그린다. 상태(필터 있음, 정렬 방향, 마우스 올림, 끔)에 따라 테마 색을 바꿔야 하는데
 *     팩의 SVG는 색이 파일에 들어 있기 때문이다. 평소에는 흐린 색({@code Chromite.Table.headerIconForeground}), 필터나 정렬이 있으면
 *     강조색({@code Chromite.Table.headerIconActiveForeground})이다. 16 격자의 1px 선(0.5 좌표)이라 팩 아이콘과 굵기가 같다.</li>
 *     <li>열 아이콘은 팩의 {@code column}, 결과의 행 식별자가 기본 키이고 그 열이 키에 들면 {@code column-pk}다
 *     (DataGrip은 머리글에 자료형 대신 열 아이콘을 쓴다, design-spec "아이콘 요구 사항").</li>
 *     <li>누른 자리는 {@link #zoneAt}이 그릴 때와 같은 치수로 계산한다. 누를 때의 동작(깔때기는 필터 팝업, 정렬 아이콘은 정렬,
 *     이름 자리는 열 선택)은 결과 뷰어가 정한다.</li>
 * </ul>
 * EDT에서만 쓴다(렌더러 상태를 그릴 때마다 바꾼다).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 결과 필터 줄을 열별 필터로 바꿈 (docs/chromite/swing-grid-column-filters.md)
 * - 2026-11-08 상태 줄 정렬 요약의 강조색 정렬 화살표 아이콘 (docs/chromite/swing-grid-column-filters.md "정렬과 쿼리의 ORDER BY")
 * - 2026-11-08 깔때기를 열마다 끌 수 있게: 가상(계산) 열은 DB가 거를 수 없다 (docs/chromite/swing-saved-filters-virtual-columns.md)
 * - 2026-11-08 이름 자리를 누르면 정렬 대신 열 선택(결과 뷰어), 설계 문단만 고침 (docs/chromite/swing-grid-column-filters.md "머리글 누르기")
 */
final class ResultSetHeaderRenderer implements TableCellRenderer {

    /**
     * Part of a column header.
     */
    enum Zone {
        NAME,
        FILTER,
        SORT
    }

    /**
     * The result viewer's state of the columns.
     */
    interface Columns {
        @NotNull
        DBDAttributeBinding getBinding(int modelColumn);

        boolean isFiltered(int modelColumn);

        /**
         * 1 ascending, -1 descending, 0 not sorted.
         */
        int getSortDirection(int modelColumn);

        /**
         * Whether the filter icon of a column can be used now (the query can be read again and is not being read, and
         * the column is not a virtual one).
         */
        boolean isFilterEnabled(int modelColumn);
    }

    private static final String ICON_COLOR = "Chromite.Table.headerIconForeground"; //$NON-NLS-1$
    private static final String ICON_ACTIVE_COLOR = "Chromite.Table.headerIconActiveForeground"; //$NON-NLS-1$
    private static final String HOVER_BACKGROUND = "Button.toolbar.hoverBackground"; //$NON-NLS-1$
    private static final float DISABLED_ALPHA = 0.4f;
    private static final float GRID = 16f;

    private final TableCellRenderer delegate;
    private final Columns columns;
    private final ActionsBorder actions = new ActionsBorder();
    /**
     * The look and feel's cell border with the actions inside, by the cell border (one per look and feel).
     */
    private final Map<Border, Border> borders = new IdentityHashMap<>();
    private int hoverColumn = -1;
    @NotNull
    private Zone hoverZone = Zone.NAME;

    ResultSetHeaderRenderer(@NotNull TableCellRenderer delegate, @NotNull Columns columns) {
        this.delegate = delegate;
        this.columns = columns;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        Component component = delegate.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        if (!(component instanceof JLabel label) || column < 0) {
            return component;
        }
        int modelColumn = table.convertColumnIndexToModel(column);
        label.setIcon(getColumnIcon(columns.getBinding(modelColumn)));
        label.setHorizontalTextPosition(SwingConstants.TRAILING);
        label.setIconTextGap(UiSizes.get(UiSizes.GRID_HEADER_GAP));
        Border base = label.getBorder();
        if (base instanceof CompoundBorder compound && compound.getInsideBorder() == actions) {
            // The delegate kept the border of the last call
            base = compound.getOutsideBorder();
        } else if (base == actions) {
            base = null;
        }
        label.setBorder(base == null ? actions : borders.computeIfAbsent(base, outside -> new CompoundBorder(outside, actions)));
        actions.filtered = columns.isFiltered(modelColumn);
        actions.sortDirection = columns.getSortDirection(modelColumn);
        actions.filterEnabled = columns.isFilterEnabled(modelColumn);
        actions.hover = column == hoverColumn ? hoverZone : Zone.NAME;
        return label;
    }

    /**
     * The part of a header cell at a point of the header.
     *
     * @param column view column index
     */
    @NotNull
    Zone zoneAt(@NotNull JTableHeader header, int column, @NotNull Point point) {
        JTable table = header.getTable();
        if (table == null || column < 0) {
            return Zone.NAME;
        }
        Rectangle rect = header.getHeaderRect(column);
        Component component = getTableCellRendererComponent(table, header.getColumnModel().getColumn(column).getHeaderValue(),
            false, false, -1, column);
        int outsideRight = 0;
        if (component instanceof JLabel label && label.getBorder() instanceof CompoundBorder compound && compound.getOutsideBorder() != null) {
            outsideRight = compound.getOutsideBorder().getBorderInsets(label).right;
        }
        int size = UiSizes.get(UiSizes.GRID_HEADER_ACTION);
        int right = rect.x + rect.width - outsideRight;
        if (point.x >= right - size && point.x < right) {
            return Zone.SORT;
        }
        if (point.x >= right - 2 * size && point.x < right - size) {
            return Zone.FILTER;
        }
        return Zone.NAME;
    }

    /**
     * Sets the icon under the mouse (hover background).
     *
     * @param column view column index, or -1
     * @return whether it changed
     */
    boolean setHover(int column, @NotNull Zone zone) {
        if (column == hoverColumn && zone == hoverZone) {
            return false;
        }
        hoverColumn = column;
        hoverZone = zone;
        return true;
    }

    @NotNull
    private static Icon getColumnIcon(@NotNull DBDAttributeBinding binding) {
        DBDRowIdentifier identifier = binding.getRowIdentifier();
        boolean key = identifier != null
            && identifier.getUniqueKey().getConstraintType() == DBSEntityConstraintType.PRIMARY_KEY
            && identifier.hasAttribute(binding);
        return UiIcons.get(key ? "column-pk" : "column"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The filter icon in the accent color (the active filter summary of the result viewer).
     */
    @NotNull
    static Icon createActiveFilterIcon() {
        return createActiveIcon((g, box) -> paintFunnel(g, box, UIManager.getColor(ICON_ACTIVE_COLOR), true));
    }

    /**
     * The sort arrow in the accent color (the active sort summary of the result viewer).
     *
     * @param direction 1 ascending, -1 descending
     */
    @NotNull
    static Icon createActiveSortIcon(int direction) {
        return createActiveIcon((g, box) -> paintSort(g, box, UIManager.getColor(ICON_ACTIVE_COLOR), direction));
    }

    @NotNull
    private static Icon createActiveIcon(@NotNull BiConsumer<Graphics2D, Rectangle> painter) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    painter.accept(g2, new Rectangle(x, y, getIconWidth(), getIconHeight()));
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return UIScale.scale(UiIcons.SIZE);
            }

            @Override
            public int getIconHeight() {
                return UIScale.scale(UiIcons.SIZE);
            }
        };
    }

    /**
     * A funnel on the 16 grid: filled when the column is filtered.
     */
    private static void paintFunnel(@NotNull Graphics2D g, @NotNull Rectangle box, @Nullable Color color, boolean filled) {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(2.5f, 3.5f);
        path.lineTo(13.5f, 3.5f);
        path.lineTo(9.5f, 8.5f);
        path.lineTo(9.5f, 12f);
        path.lineTo(6.5f, 13.5f);
        path.lineTo(6.5f, 8.5f);
        path.closePath();
        paintGlyph(g, box, color, path, filled);
    }

    /**
     * Two small chevrons (not sorted) or an arrow in the sort direction.
     */
    private static void paintSort(@NotNull Graphics2D g, @NotNull Rectangle box, @Nullable Color color, int direction) {
        Path2D.Float path = new Path2D.Float();
        if (direction == 0) {
            path.moveTo(5.5f, 6.5f);
            path.lineTo(8f, 4f);
            path.lineTo(10.5f, 6.5f);
            path.moveTo(5.5f, 9.5f);
            path.lineTo(8f, 12f);
            path.lineTo(10.5f, 9.5f);
        } else {
            float tip = direction > 0 ? 3.5f : 12.5f;
            float tail = direction > 0 ? 12.5f : 3.5f;
            float wing = direction > 0 ? 7f : 9f;
            path.moveTo(8f, tail);
            path.lineTo(8f, tip);
            path.moveTo(4.5f, wing);
            path.lineTo(8f, tip);
            path.lineTo(11.5f, wing);
        }
        paintGlyph(g, box, color, path, false);
    }

    private static void paintGlyph(@NotNull Graphics2D g, @NotNull Rectangle box, @Nullable Color color, @NotNull Shape shape, boolean filled) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(box.x, box.y);
            g2.scale(box.width / GRID, box.height / GRID);
            g2.setColor(color != null ? color : Color.GRAY);
            if (filled) {
                g2.fill(shape);
            }
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(shape);
        } finally {
            g2.dispose();
        }
    }

    /**
     * Reserves the right end of the header cell and paints the filter and sort icons there. The state is set for each
     * cell just before it is painted (renderer stamp).
     */
    private static final class ActionsBorder extends AbstractBorder {
        private boolean filtered;
        private int sortDirection;
        private boolean filterEnabled;
        @NotNull
        private Zone hover = Zone.NAME;

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(0, 0, 0, UiSizes.get(UiSizes.GRID_HEADER_GAP) + 2 * UiSizes.get(UiSizes.GRID_HEADER_ACTION));
            return insets;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            int size = UiSizes.get(UiSizes.GRID_HEADER_ACTION);
            int top = y + (height - size) / 2;
            Rectangle sortBox = new Rectangle(x + width - size, top, size, size);
            Rectangle filterBox = new Rectangle(sortBox.x - size, top, size, size);
            Color muted = UIManager.getColor(ICON_COLOR);
            Color active = UIManager.getColor(ICON_ACTIVE_COLOR);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (hover != Zone.NAME && (hover == Zone.SORT || filterEnabled)) {
                    Rectangle box = hover == Zone.SORT ? sortBox : filterBox;
                    int arc = UIScale.scale(4);
                    g2.setColor(UIManager.getColor(HOVER_BACKGROUND));
                    g2.fillRoundRect(box.x, box.y, box.width, box.height, arc, arc);
                }
                Graphics2D filterGraphics = (Graphics2D) g2.create();
                if (!filterEnabled) {
                    filterGraphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DISABLED_ALPHA));
                }
                paintFunnel(filterGraphics, filterBox, filtered ? active : muted, filtered);
                filterGraphics.dispose();
                paintSort(g2, sortBox, sortDirection != 0 ? active : muted, sortDirection);
            } finally {
                g2.dispose();
            }
        }
    }
}
