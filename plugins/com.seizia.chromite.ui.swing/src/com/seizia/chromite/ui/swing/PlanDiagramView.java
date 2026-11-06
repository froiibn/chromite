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
import com.seizia.chromite.ui.swing.internal.PlanMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.font.FontRenderContext;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * The graphical execution plan: per statement a header and the operators as boxes joined by arrows, data flowing from
 * the leaves on the right to the statement on the left (as SQL Server's plan tools draw it).
 *
 * 설계:
 * <ul>
 *     <li>계획 모델을 모르는 컴포넌트다. {@link PlanDiagram}만 받아 배치하고 그린다. 그래프 라이브러리는 쓰지 않았다: 계획은 트리라서
 *     배치가 간단하고(아래), 라이브러리(JGraphX 등)는 수 MB에 테마 색·확대·도구 설명을 다시 맞춰야 한다.</li>
 *     <li>배치: 깊이가 열이다(뿌리 문장이 왼쪽 첫 열, 자식은 오른쪽 다음 열). 부모는 첫 자식과 같은 줄에 놓고 다음 자식은 앞 자식의
 *     하위 트리 아래 줄부터 놓는다. SQL Server 계획 도구와 같은 모양이라 첫 입력(바깥쪽)이 곧게 이어지고, 선이 겹치지 않는다.</li>
 *     <li>화살표는 자식 상자의 왼쪽에서 부모 상자의 오른쪽으로 향한다(데이터 흐름). 굵기는 그 연산자가 넘기는 행 수(실제 계획이면 실제,
 *     아니면 예상)에 따라 문장 안 최대 행 수 대비 로그 비율로 1~{@value #MAX_ARROW}px다. 행 수 그대로 비례시키면 몇 행짜리 화살표는
 *     보이지 않고 큰 화살표는 상자를 덮는다.</li>
 *     <li>상자: 연산자 아이콘(16px 아이콘을 두 배로, 테마·아이콘 팩을 따름), 연산 이름, 개체(또는 조인의 논리 연산), 비용 %,
 *     행 수(실제 계획은 "실제 / 예상 (비율)"). 문장 안에서 비용이 가장 큰 연산자는 오류 색 굵은 테두리, 비용
 *     {@value #HIGH_COST_PERCENT}% 이상인 다른 연산자는 경고 색 테두리다. 서버가 보고한 경고가 있으면 아이콘 오른쪽 아래에 경고 배지를,
 *     예상이 크게 어긋난 행 수는 경고 색 글자로 그린다.</li>
 *     <li>배율은 Graphics 변환으로만 적용하고(배치는 배율 1의 화면 픽셀) Ctrl+휠은 마우스 아래 점을 고정한다. 휠만 돌리면 스크롤 창에
 *     넘긴다. 빈 곳을 끌면 화면을 옮긴다({@link ErdCanvas}와 같은 조작).</li>
 *     <li>색은 모두 테마 키에서 그릴 때마다 읽으므로 라이트·다크 전환 뒤 다시 그리기만 하면 된다.</li>
 * </ul>
 * EDT에서만 쓴다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sql-editor-plus.md "실행 계획 세 보기")
 */
final class PlanDiagramView extends JComponent implements Scrollable {

    /**
     * Selection and zoom changes.
     */
    interface Listener {
        /**
         * @param userObject the selected operator's or statement's object, null when nothing is selected
         */
        void selected(@Nullable Object userObject);

        void zoomChanged();
    }

    // Layout, unscaled pixels
    private static final int BOX_WIDTH = 168;
    private static final int BOX_PADDING = 6;
    private static final int ICON_SCALE = 2;
    private static final int COLUMN_GAP = 60;
    private static final int ROW_GAP = 22;
    private static final int MARGIN = 16;
    private static final int STATEMENT_GAP = 28;
    private static final int HEADER_GAP = 12;
    private static final int MAX_HEADER_WIDTH = 1200;
    private static final int ARC = 10;
    private static final int ARROW_ENTRY_SPACING = 7;
    private static final int MAX_ARROW = 10;
    private static final int HIGH_COST_PERCENT = 25;
    private static final double MIN_ZOOM = 0.1;
    private static final double MAX_ZOOM = 3.0;
    private static final double ZOOM_STEP = 1.2;

    private final Listener listener;
    private List<PlanDiagram.Statement> statements = List.of();
    private double zoom = 1.0;
    @Nullable
    private Object selected;
    private int contentWidth;
    private int contentHeight;
    // Pan by dragging an empty spot
    @Nullable
    private Point dragStart;
    @Nullable
    private Point viewStart;

    PlanDiagramView(@NotNull Listener listener) {
        this.listener = listener;
        setOpaque(true);
        setFocusable(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                Object hit = hitTest(toDiagram(e.getPoint()));
                if (hit != null) {
                    setSelected(hit);
                    listener.selected(hit);
                } else if (getViewport() != null) {
                    dragStart = e.getLocationOnScreen();
                    viewStart = getViewport().getViewPosition();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                JViewport viewport = getViewport();
                if (dragStart == null || viewStart == null || viewport == null) {
                    return;
                }
                Point now = e.getLocationOnScreen();
                Dimension extent = viewport.getExtentSize();
                int x = Math.max(0, Math.min(viewStart.x - (now.x - dragStart.x), getWidth() - extent.width));
                int y = Math.max(0, Math.min(viewStart.y - (now.y - dragStart.y), getHeight() - extent.height));
                viewport.setViewPosition(new Point(x, y));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragStart = null;
                viewStart = null;
                setCursor(null);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                onWheel(e);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /**
     * Shows new statements; the selection is kept when its object is still in the diagram.
     */
    void setStatements(@NotNull List<PlanDiagram.Statement> statements) {
        this.statements = statements;
        layoutDiagram();
        revalidate();
        repaint();
    }

    void setSelected(@Nullable Object userObject) {
        if (selected != userObject) {
            selected = userObject;
            repaint();
            scrollToSelected();
        }
    }

    double getZoom() {
        return zoom;
    }

    void zoomIn() {
        setZoom(zoom * ZOOM_STEP, null);
    }

    void zoomOut() {
        setZoom(zoom / ZOOM_STEP, null);
    }

    void zoomActual() {
        setZoom(1.0, null);
    }

    /**
     * Zoom so the whole diagram fits the viewport (not above 100%).
     */
    void zoomToFit() {
        JViewport viewport = getViewport();
        if (viewport == null || contentWidth <= 0 || contentHeight <= 0) {
            return;
        }
        Dimension extent = viewport.getExtentSize();
        if (extent.width <= 0 || extent.height <= 0) {
            return;
        }
        double fit = Math.min((double) extent.width / contentWidth, (double) extent.height / contentHeight);
        setZoom(Math.min(1.0, fit), null);
    }

    void setZoom(double newZoom, @Nullable Point viewportPoint) {
        newZoom = Math.clamp(newZoom, MIN_ZOOM, MAX_ZOOM);
        if (Math.abs(newZoom - zoom) < 1e-6) {
            return;
        }
        JViewport viewport = getViewport();
        if (viewport == null) {
            zoom = newZoom;
            revalidate();
            repaint();
            listener.zoomChanged();
            return;
        }
        Point anchor = viewportPoint != null ? viewportPoint
            : new Point(viewport.getExtentSize().width / 2, viewport.getExtentSize().height / 2);
        Point view = viewport.getViewPosition();
        double diagramX = (view.x + anchor.x) / zoom;
        double diagramY = (view.y + anchor.y) / zoom;
        zoom = newZoom;
        Dimension size = getPreferredSize();
        // Lay out the viewport now, so that the new view position is not clipped to the old size
        setSize(size);
        viewport.validate();
        Dimension extent = viewport.getExtentSize();
        int x = (int) Math.round(diagramX * zoom - anchor.x);
        int y = (int) Math.round(diagramY * zoom - anchor.y);
        x = Math.max(0, Math.min(x, size.width - extent.width));
        y = Math.max(0, Math.min(y, size.height - extent.height));
        viewport.setViewPosition(new Point(x, y));
        revalidate();
        repaint();
        listener.zoomChanged();
    }

    @Nullable
    private JViewport getViewport() {
        return getParent() instanceof JViewport viewport ? viewport : null;
    }

    private void onWheel(@NotNull MouseWheelEvent e) {
        if ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
            double factor = Math.pow(ZOOM_STEP, -e.getPreciseWheelRotation());
            JViewport viewport = getViewport();
            Point p = viewport != null ? SwingUtilities.convertPoint(this, e.getPoint(), viewport) : null;
            setZoom(zoom * factor, p);
            e.consume();
        } else if (getParent() != null && getParent().getParent() instanceof JScrollPane scrollPane) {
            // A wheel listener on the view stops the scroll pane from getting the event: pass it on
            scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, scrollPane));
        }
    }

    private void scrollToSelected() {
        Rectangle2D.Double bounds = null;
        for (PlanDiagram.Statement statement : statements) {
            if (statement.userObject == selected) {
                bounds = new Rectangle2D.Double(statement.x, statement.y, statement.width, statement.headerHeight);
            } else if (statement.root != null) {
                PlanDiagram.Op op = findOp(statement.root, selected);
                if (op != null) {
                    bounds = new Rectangle2D.Double(op.x, op.y, scale(BOX_WIDTH), getBoxHeight());
                }
            }
        }
        if (bounds != null) {
            scrollRectToVisible(new Rectangle((int) (bounds.x * zoom), (int) (bounds.y * zoom),
                (int) Math.ceil(bounds.width * zoom), (int) Math.ceil(bounds.height * zoom)));
        }
    }

    @Nullable
    private static PlanDiagram.Op findOp(@NotNull PlanDiagram.Op op, @Nullable Object userObject) {
        if (op.userObject == userObject) {
            return op;
        }
        for (PlanDiagram.Op child : op.children) {
            PlanDiagram.Op found = findOp(child, userObject);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    //////////////////////////////////////////////////////////
    // Layout

    private static int scale(int value) {
        return UIScale.scale(value);
    }

    @NotNull
    private Font getTextFont() {
        Font font = UIManager.getFont("Label.font"); //$NON-NLS-1$
        return font != null ? font : getFont();
    }

    @NotNull
    private Font getSmallFont() {
        Font font = getTextFont();
        return font.deriveFont(font.getSize2D() - UIScale.scale(1f));
    }

    private int getLineHeight() {
        return getFontMetrics(getTextFont()).getHeight();
    }

    private int getIconSize() {
        return scale(UiIcons.SIZE * ICON_SCALE);
    }

    /**
     * Icon, then four lines: operation, detail, cost, rows.
     */
    private double getBoxHeight() {
        return scale(BOX_PADDING) * 3 + getIconSize() + getLineHeight() * 4;
    }

    private void layoutDiagram() {
        int lineHeight = getLineHeight();
        double y = scale(MARGIN);
        double width = 0;
        for (PlanDiagram.Statement statement : statements) {
            statement.x = scale(MARGIN);
            statement.y = y;
            int headerLines = 2 + statement.notes.size();
            statement.headerHeight = headerLines * lineHeight + scale(HEADER_GAP);
            double right = statement.x;
            double bottom = y + statement.headerHeight;
            if (statement.root != null) {
                double[] extent = {statement.x};
                double next = layoutOp(statement.root, 0, statement.x, y + statement.headerHeight, extent);
                right = extent[0];
                bottom = next - scale(ROW_GAP);
            }
            statement.width = Math.max(right - statement.x, measureHeader(statement));
            statement.height = bottom - y;
            width = Math.max(width, statement.x + statement.width);
            y = bottom + scale(STATEMENT_GAP);
        }
        contentWidth = (int) Math.ceil(width + scale(MARGIN));
        contentHeight = (int) Math.ceil(y - scale(STATEMENT_GAP) + scale(MARGIN));
    }

    /**
     * Places an operator in its depth's column and its children to the right, the first one on the same row.
     *
     * @return the y where the next row may start
     */
    private double layoutOp(@NotNull PlanDiagram.Op op, int depth, double left, double y, double[] right) {
        double boxHeight = getBoxHeight();
        op.x = left + depth * (double) (scale(BOX_WIDTH) + scale(COLUMN_GAP));
        op.y = y;
        right[0] = Math.max(right[0], op.x + scale(BOX_WIDTH));
        double next = y;
        for (PlanDiagram.Op child : op.children) {
            next = layoutOp(child, depth + 1, left, next, right);
        }
        return Math.max(next, y + boxHeight + scale(ROW_GAP));
    }

    private double measureHeader(@NotNull PlanDiagram.Statement statement) {
        FontMetrics metrics = getFontMetrics(getTextFont());
        double width = metrics.stringWidth(statement.title);
        width = Math.max(width, metrics.stringWidth(statement.text));
        for (String note : statement.notes) {
            width = Math.max(width, metrics.stringWidth(note));
        }
        return Math.min(width, scale(MAX_HEADER_WIDTH));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension((int) Math.ceil(contentWidth * zoom), (int) Math.ceil(contentHeight * zoom));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Fonts and scale may have changed with the look and feel
        if (statements != null) {
            layoutDiagram();
            revalidate();
        }
    }

    //////////////////////////////////////////////////////////
    // Scrollable

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return scale(20);
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visibleRect.height - scale(20) : visibleRect.width - scale(20);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport viewport && getPreferredSize().width < viewport.getWidth();
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport viewport && getPreferredSize().height < viewport.getHeight();
    }

    //////////////////////////////////////////////////////////
    // Hit testing and tool tips

    @NotNull
    private Point2D toDiagram(@NotNull Point p) {
        return new Point2D.Double(p.x / zoom, p.y / zoom);
    }

    /**
     * The operator or statement header under a diagram point.
     */
    @Nullable
    private Object hitTest(@NotNull Point2D p) {
        for (PlanDiagram.Statement statement : statements) {
            if (p.getX() >= statement.x && p.getX() <= statement.x + statement.width
                && p.getY() >= statement.y && p.getY() <= statement.y + statement.headerHeight) {
                return statement.userObject;
            }
            if (statement.root != null) {
                PlanDiagram.Op op = hitOp(statement.root, p);
                if (op != null) {
                    return op.userObject;
                }
            }
        }
        return null;
    }

    @Nullable
    private PlanDiagram.Op hitOp(@NotNull PlanDiagram.Op op, @NotNull Point2D p) {
        if (p.getX() >= op.x && p.getX() <= op.x + scale(BOX_WIDTH) && p.getY() >= op.y && p.getY() <= op.y + getBoxHeight()) {
            return op;
        }
        for (PlanDiagram.Op child : op.children) {
            PlanDiagram.Op hit = hitOp(child, p);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        Point2D p = toDiagram(event.getPoint());
        for (PlanDiagram.Statement statement : statements) {
            if (statement.root == null) {
                continue;
            }
            PlanDiagram.Op op = hitOp(statement.root, p);
            if (op != null) {
                return createToolTip(op);
            }
            PlanDiagram.Op arrowOp = hitArrow(statement.root, p, maxRows(statement.root));
            if (arrowOp != null) {
                return createArrowToolTip(arrowOp);
            }
        }
        return null;
    }

    /**
     * The child operator whose arrow passes the point.
     */
    @Nullable
    private PlanDiagram.Op hitArrow(@NotNull PlanDiagram.Op op, @NotNull Point2D p, double maxRows) {
        for (int i = 0; i < op.children.size(); i++) {
            PlanDiagram.Op child = op.children.get(i);
            double tolerance = scale(4) + getArrowWidth(child, maxRows) / 2;
            for (Line2D segment : getArrowSegments(op, child, i)) {
                if (segment.ptSegDist(p) <= tolerance) {
                    return child;
                }
            }
            PlanDiagram.Op hit = hitArrow(child, p, maxRows);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    @NotNull
    private static String createToolTip(@NotNull PlanDiagram.Op op) {
        StringBuilder html = new StringBuilder("<html><b>").append(escape(op.title)).append("</b>"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!op.properties.isEmpty()) {
            html.append("<table cellpadding=1 cellspacing=0>"); //$NON-NLS-1$
            for (String[] property : op.properties) {
                html.append("<tr><td valign=top><nobr>").append(escape(property[0])) //$NON-NLS-1$
                    .append("</nobr></td><td>").append(escape(abbreviate(property[1]))).append("</td></tr>"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            html.append("</table>"); //$NON-NLS-1$
        }
        for (String warning : op.warnings) {
            html.append("<br>&#9888; ").append(escape(abbreviate(warning))); //$NON-NLS-1$
        }
        return html.append("</html>").toString(); //$NON-NLS-1$
    }

    @NotNull
    private static String createArrowToolTip(@NotNull PlanDiagram.Op child) {
        NumberFormat format = NumberFormat.getIntegerInstance();
        if (child.actualRows != null) {
            return NLS.bind(PlanMessages.plan_arrow_rows_actual, format.format(child.actualRows),
                child.estimatedRows == null ? "-" : format.format(child.estimatedRows)); //$NON-NLS-1$
        }
        return child.estimatedRows == null ? null : NLS.bind(PlanMessages.plan_arrow_rows_estimated, format.format(child.estimatedRows));
    }

    /**
     * Long values (output lists, predicates) are cut, so that a tool tip does not cover the screen; the property table shows them in full.
     */
    @NotNull
    private static String abbreviate(@NotNull String text) {
        // Tool tips do not wrap: break long values into lines
        String cut = text.length() > 600 ? text.substring(0, 600) + "…" : text; //$NON-NLS-1$
        StringBuilder wrapped = new StringBuilder();
        int lineLength = 0;
        for (int i = 0; i < cut.length(); i++) {
            char c = cut.charAt(i);
            wrapped.append(c);
            lineLength++;
            if (lineLength >= 90 && (c == ' ' || c == ',')) {
                wrapped.append('\n');
                lineLength = 0;
            }
        }
        return wrapped.toString();
    }

    @NotNull
    private static String escape(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
    }

    //////////////////////////////////////////////////////////
    // Arrows

    private static double maxRows(@NotNull PlanDiagram.Op op) {
        double max = op.getFlowRows() != null ? op.getFlowRows() : 0;
        for (PlanDiagram.Op child : op.children) {
            max = Math.max(max, maxRows(child));
        }
        return max;
    }

    /**
     * Arrow width from the rows the child passes on, on a log scale against the largest row count of the statement.
     */
    private double getArrowWidth(@NotNull PlanDiagram.Op child, double maxRows) {
        Double rows = child.getFlowRows();
        if (rows == null || rows <= 0 || maxRows <= 0) {
            return UIScale.scale(1f);
        }
        double ratio = Math.log1p(rows) / Math.log1p(Math.max(maxRows, 1));
        return UIScale.scale(1f) + (UIScale.scale((float) MAX_ARROW) - UIScale.scale(1f)) * Math.clamp(ratio, 0, 1);
    }

    private double getIconCenterY(@NotNull PlanDiagram.Op op) {
        return op.y + scale(BOX_PADDING) + getIconSize() / 2.0;
    }

    /**
     * The arrow path from a child's left side to its parent's right side: horizontal, vertical in the middle of the gap,
     * horizontal. The n-th child enters the parent a little lower than the one before, so arrows do not merge.
     */
    @NotNull
    private List<Line2D> getArrowSegments(@NotNull PlanDiagram.Op parent, @NotNull PlanDiagram.Op child, int index) {
        double startX = child.x;
        double startY = getIconCenterY(child);
        double endX = parent.x + scale(BOX_WIDTH);
        double maxOffset = getBoxHeight() / 2 - scale(BOX_PADDING);
        double endY = getIconCenterY(parent) + Math.min(index * scale(ARROW_ENTRY_SPACING), maxOffset);
        double middleX = endX + (startX - endX) / 2;
        List<Line2D> segments = new ArrayList<>(3);
        if (Math.abs(startY - endY) < 0.5) {
            segments.add(new Line2D.Double(startX, startY, endX, endY));
        } else {
            segments.add(new Line2D.Double(startX, startY, middleX, startY));
            segments.add(new Line2D.Double(middleX, startY, middleX, endY));
            segments.add(new Line2D.Double(middleX, endY, endX, endY));
        }
        return segments;
    }

    //////////////////////////////////////////////////////////
    // Painting

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(getCanvasBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.scale(zoom, zoom);
            Rectangle clip = g2.getClipBounds();
            for (PlanDiagram.Statement statement : statements) {
                if (clip != null && (statement.y > clip.getMaxY() || statement.y + statement.height < clip.getMinY())) {
                    continue;
                }
                paintHeader(g2, statement);
                if (statement.root != null) {
                    double maxRows = maxRows(statement.root);
                    PlanDiagram.Op top = findTopCost(statement.root, null);
                    paintArrows(g2, statement.root, maxRows);
                    paintOps(g2, statement.root, top);
                }
            }
        } finally {
            g2.dispose();
        }
    }

    @NotNull
    private static Color getCanvasBackground() {
        Color color = UIManager.getColor("Chromite.editorBackground"); //$NON-NLS-1$
        return color != null ? color : UIManager.getColor("Panel.background"); //$NON-NLS-1$
    }

    @NotNull
    private static Color getForegroundColor() {
        return UIManager.getColor("Label.foreground"); //$NON-NLS-1$
    }

    @NotNull
    private static Color getSecondaryColor() {
        Color color = UIManager.getColor("Chromite.secondaryForeground"); //$NON-NLS-1$
        return color != null ? color : UIManager.getColor("Label.disabledForeground"); //$NON-NLS-1$
    }

    @NotNull
    private static Color getStatusColor(@NotNull String key) {
        Color color = UIManager.getColor(key);
        return color != null ? color : getForegroundColor();
    }

    /**
     * A status color made readable as text on the canvas (the warning yellow is too light on a light background).
     */
    @NotNull
    private static Color getStatusTextColor(@NotNull String key) {
        return blend(getStatusColor(key), getForegroundColor(), 0.35f);
    }

    private void paintHeader(@NotNull Graphics2D g2, @NotNull PlanDiagram.Statement statement) {
        Font font = getTextFont();
        FontMetrics metrics = g2.getFontMetrics(font);
        int lineHeight = getLineHeight();
        double maxWidth = Math.max(statement.width, scale(200));
        boolean selectedHeader = statement.userObject == selected;
        if (selectedHeader) {
            g2.setColor(UIManager.getColor("Table.selectionBackground")); //$NON-NLS-1$
            g2.fill(new RoundRectangle2D.Double(statement.x - scale(4), statement.y - scale(2), statement.width + scale(8),
                statement.headerHeight - scale(HEADER_GAP) + scale(4), scale(6), scale(6)));
        }
        double baseline = statement.y + metrics.getAscent();
        g2.setFont(font.deriveFont(Font.BOLD));
        g2.setColor(getForegroundColor());
        drawString(g2, statement.title, statement.x, baseline, maxWidth);
        baseline += lineHeight;
        g2.setFont(font);
        g2.setColor(getSecondaryColor());
        drawString(g2, statement.text, statement.x, baseline, maxWidth);
        g2.setColor(statement.error ? getStatusTextColor("Chromite.status.error") : getStatusColor("Chromite.status.success")); //$NON-NLS-1$ //$NON-NLS-2$
        for (String note : statement.notes) {
            baseline += lineHeight;
            drawString(g2, note, statement.x, baseline, maxWidth);
        }
    }

    private void paintArrows(@NotNull Graphics2D g2, @NotNull PlanDiagram.Op op, double maxRows) {
        Color color = blend(getCanvasBackground(), getForegroundColor(), 0.45f);
        for (int i = 0; i < op.children.size(); i++) {
            PlanDiagram.Op child = op.children.get(i);
            double width = getArrowWidth(child, maxRows);
            List<Line2D> segments = getArrowSegments(op, child, i);
            Line2D last = segments.getLast();
            double headLength = width + scale(6);
            double headHalf = width / 2 + scale(3);
            // Stop the shaft where the head starts
            Path2D.Double shaft = new Path2D.Double();
            shaft.moveTo(segments.getFirst().getX1(), segments.getFirst().getY1());
            for (int s = 0; s < segments.size(); s++) {
                Line2D segment = segments.get(s);
                if (s == segments.size() - 1) {
                    shaft.lineTo(segment.getX2() + headLength - 0.5, segment.getY2());
                } else {
                    shaft.lineTo(segment.getX2(), segment.getY2());
                }
            }
            g2.setColor(color);
            g2.setStroke(new BasicStroke((float) width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g2.draw(shaft);
            Path2D.Double head = new Path2D.Double();
            head.moveTo(last.getX2(), last.getY2());
            head.lineTo(last.getX2() + headLength, last.getY2() - headHalf);
            head.lineTo(last.getX2() + headLength, last.getY2() + headHalf);
            head.closePath();
            g2.fill(head);
            paintArrows(g2, child, maxRows);
        }
    }

    /**
     * The operator with the largest own cost, which is highlighted (none when no operator has a cost).
     */
    @Nullable
    private static PlanDiagram.Op findTopCost(@NotNull PlanDiagram.Op op, @Nullable PlanDiagram.Op top) {
        if (op.costPercent != null && op.costPercent >= 1 && (top == null || op.costPercent > top.costPercent)) {
            top = op;
        }
        for (PlanDiagram.Op child : op.children) {
            top = findTopCost(child, top);
        }
        return top;
    }

    private void paintOps(@NotNull Graphics2D g2, @NotNull PlanDiagram.Op op, @Nullable PlanDiagram.Op top) {
        paintOp(g2, op, op == top);
        for (PlanDiagram.Op child : op.children) {
            paintOps(g2, child, top);
        }
    }

    private void paintOp(@NotNull Graphics2D g2, @NotNull PlanDiagram.Op op, boolean topCost) {
        double width = scale(BOX_WIDTH);
        double height = getBoxHeight();
        boolean isSelected = op.userObject == selected;
        boolean highCost = !topCost && op.costPercent != null && op.costPercent >= HIGH_COST_PERCENT;
        RoundRectangle2D box = new RoundRectangle2D.Double(op.x, op.y, width, height, scale(ARC), scale(ARC));
        Color background = getCanvasBackground();
        Color fill = isSelected ? UIManager.getColor("Table.selectionBackground") //$NON-NLS-1$
            : topCost ? blend(background, getStatusColor("Chromite.status.error"), 0.08f) //$NON-NLS-1$
            : blend(background, getForegroundColor(), 0.035f);
        g2.setColor(fill);
        g2.fill(box);
        Color border;
        float borderWidth;
        if (isSelected) {
            border = UIManager.getColor("Component.focusedBorderColor"); //$NON-NLS-1$
            borderWidth = UIScale.scale(2f);
        } else if (topCost) {
            border = getStatusColor("Chromite.status.error"); //$NON-NLS-1$
            borderWidth = UIScale.scale(2f);
        } else if (highCost) {
            border = getStatusColor("Chromite.status.warning"); //$NON-NLS-1$
            borderWidth = UIScale.scale(1.5f);
        } else {
            border = UIManager.getColor("Component.borderColor"); //$NON-NLS-1$
            borderWidth = UIScale.scale(1f);
        }
        g2.setColor(border != null ? border : getSecondaryColor());
        g2.setStroke(new BasicStroke(borderWidth));
        g2.draw(box);

        // Operator icon, drawn twice its size
        int iconSize = getIconSize();
        double iconX = op.x + (width - iconSize) / 2;
        double iconY = op.y + scale(BOX_PADDING);
        paintIcon(g2, UiIcons.get(op.iconId), iconX, iconY, (double) iconSize / UiIcons.SIZE);
        if (!op.warnings.isEmpty()) {
            double badge = scale(UiIcons.SIZE);
            paintIcon(g2, UiIcons.get("warning"), iconX + iconSize - badge * 0.6, iconY + iconSize - badge * 0.75, //$NON-NLS-1$
                badge / UiIcons.SIZE);
        }

        Font font = getTextFont();
        int lineHeight = getLineHeight();
        double center = op.x + width / 2;
        double textWidth = width - scale(BOX_PADDING) * 2;
        double baseline = iconY + iconSize + scale(BOX_PADDING) + g2.getFontMetrics(font).getAscent();
        g2.setColor(getForegroundColor());
        g2.setFont(font.deriveFont(Font.BOLD));
        drawCentered(g2, op.title, center, baseline, textWidth);
        baseline += lineHeight;
        g2.setFont(getSmallFont());
        g2.setColor(getSecondaryColor());
        if (op.detail != null) {
            drawCentered(g2, op.detail, center, baseline, textWidth);
        }
        baseline += lineHeight;
        if (op.costPercent != null) {
            String cost = NLS.bind(PlanMessages.plan_cost, formatPercent(op.costPercent));
            if (topCost || highCost) {
                g2.setFont(font.deriveFont(Font.BOLD));
                g2.setColor(getStatusTextColor(topCost ? "Chromite.status.error" : "Chromite.status.warning")); //$NON-NLS-1$ //$NON-NLS-2$
            } else {
                g2.setFont(font);
                g2.setColor(getForegroundColor());
            }
            drawCentered(g2, cost, center, baseline, textWidth);
        }
        baseline += lineHeight;
        String rows = formatRows(op);
        if (rows != null) {
            if (op.misestimate != null) {
                g2.setFont(getSmallFont().deriveFont(Font.BOLD));
                g2.setColor(getStatusTextColor("Chromite.status.warning")); //$NON-NLS-1$
            } else {
                g2.setFont(getSmallFont());
                g2.setColor(getSecondaryColor());
            }
            drawCentered(g2, rows, center, baseline, textWidth);
        }
    }

    private void paintIcon(@NotNull Graphics2D g2, @NotNull Icon icon, double x, double y, double factor) {
        Graphics2D ig = (Graphics2D) g2.create();
        try {
            ig.translate(x, y);
            ig.scale(factor, factor);
            icon.paintIcon(this, ig, 0, 0);
        } finally {
            ig.dispose();
        }
    }

    @Nullable
    private static String formatRows(@NotNull PlanDiagram.Op op) {
        NumberFormat format = NumberFormat.getIntegerInstance();
        if (op.actualRows != null) {
            String estimated = op.estimatedRows == null ? "-" : format.format(op.estimatedRows); //$NON-NLS-1$
            String ratio = op.estimatedRows == null || op.estimatedRows <= 0 ? "-" //$NON-NLS-1$
                : formatPercent(op.actualRows * 100 / op.estimatedRows);
            return NLS.bind(PlanMessages.plan_rows_actual, new Object[]{format.format(op.actualRows), estimated, ratio});
        }
        return op.estimatedRows == null ? null : NLS.bind(PlanMessages.plan_rows, format.format(op.estimatedRows));
    }

    @NotNull
    static String formatPercent(double percent) {
        return NumberFormat.getIntegerInstance().format(Math.round(percent)) + "%"; //$NON-NLS-1$
    }

    private static void drawCentered(@NotNull Graphics2D g2, @NotNull String text, double center, double baseline, double maxWidth) {
        String shown = fit(g2, text, maxWidth);
        double width = g2.getFont().getStringBounds(shown, g2.getFontRenderContext()).getWidth();
        g2.drawString(shown, (float) (center - width / 2), (float) baseline);
    }

    private static void drawString(@NotNull Graphics2D g2, @NotNull String text, double x, double baseline, double maxWidth) {
        g2.drawString(fit(g2, text, maxWidth), (float) x, (float) baseline);
    }

    /**
     * The text cut with an ellipsis to fit the width.
     */
    @NotNull
    private static String fit(@NotNull Graphics2D g2, @NotNull String text, double maxWidth) {
        Font font = g2.getFont();
        FontRenderContext context = g2.getFontRenderContext();
        if (font.getStringBounds(text, context).getWidth() <= maxWidth) {
            return text;
        }
        String ellipsis = "…"; //$NON-NLS-1$
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (font.getStringBounds(text.substring(0, middle) + ellipsis, context).getWidth() <= maxWidth) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return text.substring(0, low) + ellipsis;
    }

    @NotNull
    private static Color blend(@NotNull Color base, @NotNull Color tint, float amount) {
        return new Color(
            Math.round(base.getRed() + (tint.getRed() - base.getRed()) * amount),
            Math.round(base.getGreen() + (tint.getGreen() - base.getGreen()) * amount),
            Math.round(base.getBlue() + (tint.getBlue() - base.getBlue()) * amount));
    }
}
