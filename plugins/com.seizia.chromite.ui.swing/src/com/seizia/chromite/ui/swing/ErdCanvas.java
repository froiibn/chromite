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

import com.seizia.chromite.ui.swing.internal.ErdMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;

/**
 * ER 다이어그램을 그리고 마우스로 다루는 캔버스(스크롤 창 안).
 *
 * 설계:
 * <ul>
 *     <li>확대 비율은 Graphics 변환으로만 적용하고 상자 좌표는 다이어그램 좌표 그대로 둔다({@link ErdRenderer}).
 *     Ctrl+휠은 마우스 아래 점을 고정한 채 확대·축소하고, 휠만 돌리면 스크롤 창에 넘겨 위아래로 움직인다(Shift+휠은 좌우).</li>
 *     <li>빈 곳을 끌면 화면을 옮기고(pan), 상자를 끌면 고른 상자들을 옮긴다. Ctrl+클릭은 고르기를 더하거나 뺀다.
 *     옮긴 상자는 맨 위에 그린다(그리는 순서만 바꾸고 모델의 순서는 두어 자동 배치가 늘 같게).</li>
 *     <li>관계선 위에 마우스를 올리면 그 선을 강조하고 도구 설명에 외래 키 이름과 열을 보인다.</li>
 *     <li>두 번 클릭, 오른쪽 클릭, 상자 이동은 {@link Listener}로 문서 탭에 알린다(Object Explorer 연결, 메뉴, 저장 표시).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-erd.md)
 */
final class ErdCanvas extends JComponent implements Scrollable {

    static final double MIN_ZOOM = 0.1;
    static final double MAX_ZOOM = 3.0;
    private static final double ZOOM_STEP = 1.2;
    private static final double HIT_TOLERANCE = 5;

    interface Listener {
        void entityOpened(@NotNull ErdDiagramModel.Entity entity);

        void popupRequested(@NotNull MouseEvent event, @Nullable ErdDiagramModel.Entity entity);

        /**
         * Boxes were moved by the user.
         */
        void layoutChanged();

        void zoomChanged();
    }

    private final ErdRenderer renderer;
    private final Listener listener;
    @Nullable
    private ErdDiagramModel model;
    private final List<ErdDiagramModel.Entity> order = new ArrayList<>();
    private final Set<ErdDiagramModel.Entity> selected = new LinkedHashSet<>();
    @Nullable
    private ErdDiagramModel.Link hoverLink;
    private double zoom = 1.0;

    // Drag state
    @Nullable
    private Point dragStart;
    private final Map<ErdDiagramModel.Entity, Point2D> dragOrigins = new IdentityHashMap<>();
    private boolean dragMoved;
    @Nullable
    private Point panStart;
    @Nullable
    private Point panViewStart;

    ErdCanvas(@NotNull ErdRenderer renderer, @NotNull Listener listener) {
        this.renderer = renderer;
        this.listener = listener;
        setFocusable(true);
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                onPressed(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                onDragged(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                onReleased(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    ErdDiagramModel.Entity entity = entityAt(e.getPoint());
                    if (entity != null) {
                        listener.entityOpened(entity);
                    }
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                updateHover(e.getPoint());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hoverLink != null) {
                    hoverLink = null;
                    repaint();
                }
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

    void setModel(@Nullable ErdDiagramModel model) {
        this.model = model;
        order.clear();
        selected.clear();
        hoverLink = null;
        if (model != null) {
            order.addAll(model.entities);
        }
        diagramResized();
    }

    @Nullable
    ErdDiagramModel getModel() {
        return model;
    }

    double getZoom() {
        return zoom;
    }

    @NotNull
    Set<ErdDiagramModel.Entity> getSelection() {
        return selected;
    }

    void selectAll() {
        if (model != null) {
            selected.addAll(model.entities);
            repaint();
        }
    }

    /**
     * Sizes or positions changed: updates the scrollable size.
     */
    void diagramResized() {
        revalidate();
        JViewport viewport = getViewport();
        if (viewport != null) {
            viewport.setViewSize(getPreferredSize());
        }
        repaint();
    }

    /**
     * Zooms keeping the diagram point under a viewport point (null: the viewport center).
     */
    void setZoom(double newZoom, @Nullable Point viewportPoint) {
        newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        if (Math.abs(newZoom - zoom) < 1e-6) {
            return;
        }
        JViewport viewport = getViewport();
        if (viewport == null) {
            zoom = newZoom;
            diagramResized();
            listener.zoomChanged();
            return;
        }
        Point anchor = viewportPoint != null ? viewportPoint
            : new Point(viewport.getExtentSize().width / 2, viewport.getExtentSize().height / 2);
        Point view = viewport.getViewPosition();
        double diagramX = (view.x + anchor.x) / zoom;
        double diagramY = (view.y + anchor.y) / zoom;
        zoom = newZoom;
        diagramResized();
        Dimension size = getPreferredSize();
        Dimension extent = viewport.getExtentSize();
        int x = (int) Math.round(diagramX * zoom - anchor.x);
        int y = (int) Math.round(diagramY * zoom - anchor.y);
        x = Math.max(0, Math.min(x, size.width - extent.width));
        y = Math.max(0, Math.min(y, size.height - extent.height));
        viewport.setViewPosition(new Point(x, y));
        listener.zoomChanged();
    }

    void zoomIn() {
        setZoom(zoom * ZOOM_STEP, null);
    }

    void zoomOut() {
        setZoom(zoom / ZOOM_STEP, null);
    }

    /**
     * Zoom so the whole diagram fits the viewport (not above 100%).
     */
    void zoomToFit() {
        JViewport viewport = getViewport();
        if (model == null || viewport == null) {
            return;
        }
        Rectangle2D bounds = ErdRenderer.diagramBounds(model.entities);
        Dimension extent = viewport.getExtentSize();
        if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0 || extent.width <= 0 || extent.height <= 0) {
            return;
        }
        double fit = Math.min(extent.width / bounds.getWidth(), extent.height / bounds.getHeight());
        zoom = Math.max(MIN_ZOOM, Math.min(1.0, fit));
        diagramResized();
        viewport.setViewPosition(new Point(0, 0));
        listener.zoomChanged();
    }

    @Nullable
    private JViewport getViewport() {
        return getParent() instanceof JViewport viewport ? viewport : null;
    }

    //////////////////////////////////////////////////////////
    // Painting

    @Override
    public Dimension getPreferredSize() {
        if (model == null) {
            return new Dimension(1, 1);
        }
        Rectangle2D bounds = ErdRenderer.diagramBounds(model.entities);
        return new Dimension((int) Math.ceil(bounds.getWidth() * zoom), (int) Math.ceil(bounds.getHeight() * zoom));
    }

    @Override
    protected void paintComponent(Graphics g) {
        ErdRenderer.Palette palette = ErdRenderer.Palette.current();
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(palette.canvas());
            Rectangle clip = g2.getClipBounds();
            if (clip == null) {
                clip = new Rectangle(0, 0, getWidth(), getHeight());
            }
            g2.fill(clip);
            if (model == null) {
                return;
            }
            g2.scale(zoom, zoom);
            Rectangle2D diagramClip = new Rectangle2D.Double(clip.x / zoom, clip.y / zoom, clip.width / zoom, clip.height / zoom);
            renderer.paint(new ErdPainter.Java2D(g2), palette, order, model.links, selected, hoverLink, diagramClip, true);
        } finally {
            g2.dispose();
        }
    }

    //////////////////////////////////////////////////////////
    // Mouse

    @NotNull
    private Point2D toDiagram(@NotNull Point p) {
        return new Point2D.Double(p.x / zoom, p.y / zoom);
    }

    @Nullable
    private ErdDiagramModel.Entity entityAt(@NotNull Point p) {
        Point2D d = toDiagram(p);
        return ErdRenderer.entityAt(order, d.getX(), d.getY());
    }

    private void onPressed(@NotNull MouseEvent e) {
        requestFocusInWindow();
        ErdDiagramModel.Entity entity = entityAt(e.getPoint());
        if (e.isPopupTrigger()) {
            popup(e, entity);
            return;
        }
        boolean toggle = (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
        if (SwingUtilities.isLeftMouseButton(e) && entity != null) {
            if (toggle) {
                if (!selected.remove(entity)) {
                    selected.add(entity);
                }
            } else if (!selected.contains(entity)) {
                selected.clear();
                selected.add(entity);
            }
            order.remove(entity);
            order.add(entity);
            dragStart = e.getPoint();
            dragOrigins.clear();
            for (ErdDiagramModel.Entity s : selected) {
                dragOrigins.put(s, new Point2D.Double(s.x, s.y));
            }
            dragMoved = false;
            repaint();
        } else if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
            if (!toggle && SwingUtilities.isLeftMouseButton(e) && !selected.isEmpty()) {
                selected.clear();
                repaint();
            }
            JViewport viewport = getViewport();
            if (viewport != null) {
                panStart = e.getLocationOnScreen();
                panViewStart = viewport.getViewPosition();
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }
        }
    }

    private void onDragged(@NotNull MouseEvent e) {
        if (dragStart != null && !dragOrigins.isEmpty()) {
            double dx = (e.getX() - dragStart.x) / zoom;
            double dy = (e.getY() - dragStart.y) / zoom;
            // Keep the boxes in the positive quadrant (the scrollable area starts at 0, 0)
            for (Point2D origin : dragOrigins.values()) {
                dx = Math.max(dx, -origin.getX());
                dy = Math.max(dy, -origin.getY());
            }
            for (Map.Entry<ErdDiagramModel.Entity, Point2D> entry : dragOrigins.entrySet()) {
                entry.getKey().x = Math.round(entry.getValue().getX() + dx);
                entry.getKey().y = Math.round(entry.getValue().getY() + dy);
            }
            dragMoved = true;
            diagramResized();
            scrollRectToVisible(new Rectangle(e.getX() - 10, e.getY() - 10, 20, 20));
        } else if (panStart != null && panViewStart != null) {
            JViewport viewport = getViewport();
            if (viewport != null) {
                Point now = e.getLocationOnScreen();
                Dimension size = getPreferredSize();
                Dimension extent = viewport.getExtentSize();
                int x = panViewStart.x - (now.x - panStart.x);
                int y = panViewStart.y - (now.y - panStart.y);
                x = Math.max(0, Math.min(x, Math.max(0, size.width - extent.width)));
                y = Math.max(0, Math.min(y, Math.max(0, size.height - extent.height)));
                viewport.setViewPosition(new Point(x, y));
            }
        }
    }

    private void onReleased(@NotNull MouseEvent e) {
        if (e.isPopupTrigger()) {
            popup(e, entityAt(e.getPoint()));
        }
        if (dragMoved) {
            listener.layoutChanged();
        }
        dragStart = null;
        dragOrigins.clear();
        dragMoved = false;
        if (panStart != null) {
            panStart = null;
            panViewStart = null;
            setCursor(Cursor.getDefaultCursor());
        }
    }

    private void popup(@NotNull MouseEvent e, @Nullable ErdDiagramModel.Entity entity) {
        if (entity != null && !selected.contains(entity)) {
            selected.clear();
            selected.add(entity);
            repaint();
        }
        listener.popupRequested(e, entity);
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

    private void updateHover(@NotNull Point p) {
        ErdDiagramModel.Link link = null;
        if (model != null && entityAt(p) == null) {
            Point2D d = toDiagram(p);
            link = renderer.linkAt(model.links, order, d.getX(), d.getY(), HIT_TOLERANCE / Math.min(1, zoom));
        }
        if (link != hoverLink) {
            hoverLink = link;
            repaint();
        }
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        if (model == null) {
            return null;
        }
        ErdDiagramModel.Entity entity = entityAt(e.getPoint());
        if (entity != null) {
            return entity.fullName;
        }
        Point2D d = toDiagram(e.getPoint());
        ErdDiagramModel.Link link = renderer.linkAt(model.links, order, d.getX(), d.getY(), HIT_TOLERANCE / Math.min(1, zoom));
        if (link == null) {
            return null;
        }
        return NLS.bind(ErdMessages.erd_link_tooltip, new Object[]{
            link.name(),
            link.source().title + "(" + String.join(", ", link.sourceColumns()) + ")", //$NON-NLS-1$ //$NON-NLS-2$
            link.target().title + "(" + String.join(", ", link.targetColumns()) + ")"}); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Paints the whole diagram at zoom 1 (export).
     */
    void paintDiagram(@NotNull ErdPainter painter, @NotNull ErdRenderer.Palette palette) {
        if (model != null) {
            renderer.paint(painter, palette, order, model.links, Set.of(), null, null, false);
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
        return 20;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visibleRect.height - 40 : visibleRect.width - 40;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport viewport && viewport.getWidth() > getPreferredSize().width;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
    }
}
