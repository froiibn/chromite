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

import javax.swing.*;
import javax.swing.plaf.UIResource;
import java.awt.*;
import java.awt.dnd.DragSource;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;

/**
 * 메인 창 문서 탭을 탭 줄 위에서 끌어 순서를 바꾼다(앱 변경 #171).
 *
 * 설계:
 * <ul>
 *     <li>문서 탭의 종류(쿼리 창, DDL 탭, 도구 문서, 테이블 디자이너)와 상관없이 탭 줄이 하는 일이라, 탭 창에 마우스 리스너로 붙는다.
 *     누르기·선택·닫기 단추·가운데 단추 닫기·오른쪽 클릭 메뉴는 그대로 두고, 왼쪽 단추로 탭을 누른 뒤 플랫폼의 끌기 문턱
 *     ({@link DragSource#getDragThreshold()})을 넘게 움직였을 때만 끌기가 시작된다. 닫기 단추에서 누른 것은 끌지 않는다(FlatLaf가 닫기로 처리).</li>
 *     <li>놓을 자리는 포인터에 가장 가까운 보이는 탭의 앞이나 뒤(탭 줄 방향의 가운데를 기준)다. 위쪽 배치는 가로, 왼쪽·오른쪽 배치는 세로로
 *     재고, 여러 행 배치는 가장 가까운 탭이 행을 정한다. 놓을 자리는 테마의 밑줄 색({@code TabbedPane.underlineColor}) 2px 선으로 보인다.
 *     탭을 끄는 동안 다른 탭을 옮기지 않는 것은 탭을 뺄 때마다 FlatLaf의 눌림·마우스 위 상태가 흔들리기 때문이다.</li>
 *     <li>고정 탭 규칙(고정 탭은 왼쪽에 모임)을 지키려고, 고정 탭은 고정 탭 사이에서만, 고정하지 않은 탭은 그 사이에서만 옮긴다.
 *     무리 밖을 가리키면 무리의 끝에 놓는다.</li>
 *     <li>탭 줄(보이는 탭이 있는 띠, 탭 두께의 반만큼 여유) 밖에서 놓으면 옮기지 않는다. 한 행 배치에서 포인터가 보이는 탭 줄의 끝에 가면
 *     탭 줄을 그쪽으로 넘긴다(30개 탭처럼 다 보이지 않을 때).</li>
 *     <li>탭 줄에서 탭 두께만큼 더 떨어진 곳에 놓으면 그 문서와 화면 위치를 넘긴다({@link DocumentTabMenu}: 쿼리 창은 별도 창, 앱 변경 #173).
 *     그 사이 띠는 아무것도 하지 않아, 순서를 바꾸려다 조금 벗어나 놓은 것이 창을 열지 않는다.</li>
 *     <li>옮기기는 {@link MainFrame.EditorTabbedPane#moveTab} 하나로 한다(탭 고정과 같은 경로). 탭의 모든 것이 따라가고, 선택 이벤트는
 *     옮긴 뒤 한 번만 나며, 다시 시작 후 복원({@link QueryWindowRecovery})은 탭 순서대로 쓰므로 끈 순서가 저장된다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-22 최초 작성(앱 변경 #171) (docs/chromite/swing-frontend.md "문서 탭 메뉴")
 * - 2026-11-22 탭 줄에서 멀리 놓은 탭을 알림(쿼리 창을 별도 창으로, 앱 변경 #173) (docs/chromite/swing-frontend.md "쿼리 창을 별도 창으로")
 */
final class DocumentTabDrag extends MouseAdapter {

    private static final int SCROLL_DELAY_MS = 40;

    private final MainFrame.EditorTabbedPane tabs;
    // A tab dropped well off the tab strip: its document and the screen point (a query window gets a window of its own)
    private final BiConsumer<Component, Point> droppedOff;
    private final Timer scrollTimer;
    // The pressed tab's document: its index changes when tabs open or close during the drag
    @Nullable
    private Component dragged;
    @Nullable
    private Point pressedPoint;
    @Nullable
    private Point lastPoint;
    private boolean dragging;
    private int dropIndex = -1;
    // Pixels per timer tick along the tab run while the pointer is at an end of the scrolling tab row
    private int scrollStep;

    private DocumentTabDrag(@NotNull MainFrame.EditorTabbedPane tabs, @NotNull BiConsumer<Component, Point> droppedOff) {
        this.tabs = tabs;
        this.droppedOff = droppedOff;
        scrollTimer = new Timer(SCROLL_DELAY_MS, e -> autoScroll());
    }

    /**
     * @param droppedOff called with the document and the screen point when a tab is dropped well off the tab strip
     */
    static void install(@NotNull MainFrame.EditorTabbedPane tabs, @NotNull BiConsumer<Component, Point> droppedOff) {
        DocumentTabDrag drag = new DocumentTabDrag(tabs, droppedOff);
        tabs.addMouseListener(drag);
        tabs.addMouseMotionListener(drag);
    }

    @Override
    public void mousePressed(MouseEvent e) {
        reset();
        if (!SwingUtilities.isLeftMouseButton(e) || e.isPopupTrigger()) {
            return;
        }
        int index = tabs.indexAtLocation(e.getX(), e.getY());
        if (index < 0 || tabs.isCloseButtonAt(index, e.getPoint())) {
            return;
        }
        dragged = tabs.getComponentAt(index);
        pressedPoint = e.getPoint();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (dragged == null || pressedPoint == null) {
            return;
        }
        if (!dragging) {
            if (pressedPoint.distance(e.getPoint()) < DragSource.getDragThreshold()) {
                return;
            }
            dragging = true;
        }
        update(e.getPoint());
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (!SwingUtilities.isLeftMouseButton(e)) {
            return;
        }
        if (dragging && dragged != null) {
            update(e.getPoint());
            Component document = dragged;
            int from = tabs.indexOfComponent(document);
            int to = dropIndex;
            boolean off = to < 0 && isWellOffStrip(e.getPoint());
            reset();
            if (from >= 0 && to >= 0) {
                tabs.moveTab(from, to);
            } else if (from >= 0 && off) {
                droppedOff.accept(document, e.getLocationOnScreen());
            }
        } else {
            reset();
        }
    }

    private void reset() {
        dragged = null;
        pressedPoint = null;
        lastPoint = null;
        dragging = false;
        dropIndex = -1;
        scrollStep = 0;
        scrollTimer.stop();
        tabs.setDropIndicator(null);
    }

    private void update(@NotNull Point point) {
        lastPoint = point;
        int from = dragged != null ? tabs.indexOfComponent(dragged) : -1;
        if (from < 0) {
            // Closed while dragged
            reset();
            return;
        }
        dropIndex = findDropIndex(point, from);
        tabs.setDropIndicator(dropIndex >= 0 && dropIndex != from ? getIndicator(from, dropIndex) : null);
        updateScrolling(point);
    }

    /**
     * The index the dragged tab gets when dropped at the point, within its group (pinned or not), or -1 outside the
     * tab strip.
     */
    private int findDropIndex(@NotNull Point point, int from) {
        Rectangle strip = getStrip();
        if (strip == null || !strip.contains(point)) {
            return -1;
        }
        Rectangle visible = getVisibleTabArea();
        int nearest = -1;
        double nearestDistance = Double.MAX_VALUE;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Rectangle bounds = tabs.getBoundsAt(i);
            if (bounds == null || !bounds.intersects(visible)) {
                continue;
            }
            double distance = distance(point, bounds);
            if (distance < nearestDistance) {
                nearest = i;
                nearestDistance = distance;
            }
        }
        if (nearest < 0) {
            return -1;
        }
        Rectangle bounds = tabs.getBoundsAt(nearest);
        boolean after;
        if (isVertical()) {
            after = point.y >= bounds.getCenterY();
        } else {
            after = tabs.getComponentOrientation().isLeftToRight() ? point.x >= bounds.getCenterX() : point.x < bounds.getCenterX();
        }
        int insert = after ? nearest + 1 : nearest;
        int to = insert > from ? insert - 1 : insert;
        // Pinned tabs stay together at the start: each group keeps to its own range
        int pinned = 0;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (DocumentTabMenu.isPinned(tabs.getComponentAt(i))) {
                pinned++;
            }
        }
        boolean draggedPinned = DocumentTabMenu.isPinned(tabs.getComponentAt(from));
        int first = draggedPinned ? 0 : pinned;
        int last = draggedPinned ? pinned - 1 : tabs.getTabCount() - 1;
        return Math.clamp(to, first, last);
    }

    /**
     * A 2px line at the edge where the tab lands: before the tab now at {@code to} when it moves toward the start,
     * after it when it moves toward the end. Null when that edge is scrolled out of sight.
     */
    @Nullable
    private Rectangle getIndicator(int from, int to) {
        Rectangle bounds = tabs.getBoundsAt(to);
        if (bounds == null) {
            return null;
        }
        int thickness = UIScale.scale(2);
        int inset = UiSizes.get(UiSizes.DOCUMENT_TAB_PILL_INSET);
        boolean trailing = to > from;
        // Inside the tab at that edge, so that the first and the last tab show it whole
        Rectangle line;
        if (isVertical()) {
            int y = trailing ? bounds.y + bounds.height - thickness : bounds.y;
            line = new Rectangle(bounds.x + inset, y, Math.max(thickness, bounds.width - 2 * inset), thickness);
        } else {
            boolean rightEdge = trailing == tabs.getComponentOrientation().isLeftToRight();
            int x = rightEdge ? bounds.x + bounds.width - thickness : bounds.x;
            line = new Rectangle(x, bounds.y + inset, thickness, Math.max(thickness, bounds.height - 2 * inset));
        }
        Rectangle visible = getVisibleTabArea().intersection(line);
        return visible.isEmpty() ? null : visible;
    }

    /**
     * The band of the tab row(s) across the whole pane, with half a tab's thickness to spare on each side, or null
     * without tabs.
     */
    @Nullable
    private Rectangle getStrip() {
        Rectangle tabArea = null;
        JViewport viewport = getTabViewport();
        if (viewport != null) {
            tabArea = viewport.getBounds();
        } else {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                Rectangle bounds = tabs.getBoundsAt(i);
                if (bounds != null) {
                    tabArea = tabArea == null ? new Rectangle(bounds) : tabArea.union(bounds);
                }
            }
        }
        if (tabArea == null || tabs.getTabCount() == 0) {
            return null;
        }
        Rectangle first = tabs.getBoundsAt(0);
        if (isVertical()) {
            int spare = first != null ? first.width / 2 : 0;
            return new Rectangle(tabArea.x - spare, 0, tabArea.width + 2 * spare, tabs.getHeight());
        }
        int spare = first != null ? first.height / 2 : 0;
        return new Rectangle(0, tabArea.y - spare, tabs.getWidth(), tabArea.height + 2 * spare);
    }

    /**
     * Whether a point is off the tab strip by more than another half tab beyond its own margin: a drop there takes a
     * query window out (app change #173), a drop between that and the strip does nothing, so that a sloppy reorder does
     * not open a window.
     */
    private boolean isWellOffStrip(@NotNull Point point) {
        Rectangle strip = getStrip();
        Rectangle first = tabs.getTabCount() > 0 ? tabs.getBoundsAt(0) : null;
        if (strip == null || first == null) {
            return false;
        }
        if (isVertical()) {
            strip.grow(first.width / 2, 0);
        } else {
            strip.grow(0, first.height / 2);
        }
        return !strip.contains(point);
    }

    /**
     * Where tabs can be seen: the scrolling tab row's viewport, or the whole pane in the several-rows layout.
     */
    @NotNull
    private Rectangle getVisibleTabArea() {
        JViewport viewport = getTabViewport();
        return viewport != null ? viewport.getBounds() : new Rectangle(0, 0, tabs.getWidth(), tabs.getHeight());
    }

    /**
     * The viewport of the scrolling tab row (one row layout), a look and feel child of the tabbed pane; null in the
     * several-rows layout.
     */
    @Nullable
    private JViewport getTabViewport() {
        if (tabs.getTabLayoutPolicy() != JTabbedPane.SCROLL_TAB_LAYOUT) {
            return null;
        }
        for (Component child : tabs.getComponents()) {
            if (child instanceof JViewport viewport && child instanceof UIResource) {
                return viewport;
            }
        }
        return null;
    }

    /**
     * Scrolls the one-row tab strip while the pointer is at its ends (or beyond them, over the scroll buttons).
     */
    private void updateScrolling(@NotNull Point point) {
        JViewport viewport = getTabViewport();
        Rectangle strip = getStrip();
        scrollStep = 0;
        if (viewport != null && strip != null && strip.contains(point)) {
            Rectangle area = viewport.getBounds();
            int zone = UIScale.scale(24);
            int position = isVertical() ? point.y - area.y : point.x - area.x;
            int length = isVertical() ? area.height : area.width;
            int step = UIScale.scale(12);
            if (position < zone) {
                scrollStep = -step;
            } else if (position > length - zone) {
                scrollStep = step;
            }
            if (!isVertical() && !tabs.getComponentOrientation().isLeftToRight()) {
                scrollStep = -scrollStep;
            }
        }
        if (scrollStep != 0) {
            scrollTimer.start();
        } else {
            scrollTimer.stop();
        }
    }

    private void autoScroll() {
        JViewport viewport = getTabViewport();
        if (viewport == null || viewport.getView() == null || scrollStep == 0 || lastPoint == null) {
            scrollTimer.stop();
            return;
        }
        Dimension view = viewport.getView().getSize();
        Dimension extent = viewport.getExtentSize();
        Point position = viewport.getViewPosition();
        if (isVertical()) {
            position.y = Math.clamp(position.y + scrollStep, 0, Math.max(0, view.height - extent.height));
        } else {
            position.x = Math.clamp(position.x + scrollStep, 0, Math.max(0, view.width - extent.width));
        }
        if (!position.equals(viewport.getViewPosition())) {
            viewport.setViewPosition(position);
            update(lastPoint);
        }
    }

    private boolean isVertical() {
        int placement = tabs.getTabPlacement();
        return placement == SwingConstants.LEFT || placement == SwingConstants.RIGHT;
    }

    private static double distance(@NotNull Point point, @NotNull Rectangle bounds) {
        double dx = Math.max(Math.max(bounds.x - point.x, 0), point.x - (bounds.x + bounds.width));
        double dy = Math.max(Math.max(bounds.y - point.y, 0), point.y - (bounds.y + bounds.height));
        return Math.hypot(dx, dy);
    }
}
