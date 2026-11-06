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

import com.formdev.flatlaf.ui.FlatScrollBarUI;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import java.awt.*;
import java.awt.event.ContainerEvent;
import java.awt.event.ContainerListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/**
 * DataGrip New UI 스크롤바(design-spec "13. 스크롤바"): 트랙은 보이지 않고(스크롤하는 내용의 바탕색), 막대는 폭 7의 둥근 반투명 막대이며
 * 마우스를 스크롤바 위에 올리면 폭 9로 넓어지고 진해진다.
 *
 * 설계: 폭·막대 여백·모서리·막대 색은 테마 properties의 FlatLaf 키({@code ScrollBar.width}, {@code thumbInsets}, {@code thumbArc}, {@code thumb},
 * {@code hoverThumbColor})다. FlatLaf에 없는 두 가지만 여기서 한다.
 * <ul>
 *     <li>보이지 않는 트랙: FlatLaf 트랙 색은 창 바탕(bg.panel)에서 만들어져, 바탕이 다른 편집기·결과 그리드(bg.editor) 옆에 띠가 생긴다.
 *     반투명 색은 불투명 컴포넌트의 바탕에 쓸 수 없으므로, 트랙을 스크롤하는 내용(뷰)의 바탕색으로 칠한다.
 *     뷰의 바탕색이 바뀌면(테마 전환 뒤 편집기가 색을 다시 적용할 때, 옵션 미리보기) 뷰만 다시 그려지고 스크롤바는 옛 색으로 남는다.
 *     그래서 UI가 설치될 때 스크롤 창·뷰포트·뷰를 따라가며 뷰와 뷰포트의 {@code background}·{@code opaque} 변경을 듣고 스크롤바를 다시 그린다.
 *     바탕색을 바꾸는 쪽(각 편집기)마다 스크롤바를 다시 그리게 하지 않고 색을 가져다 쓰는 이 UI에서 처리하므로, 뷰의 바탕색이 바뀌는
 *     모든 스크롤 창에 적용된다.</li>
 *     <li>마우스 올림에 넓어지기: FlatLaf에는 마우스 올림 때의 막대 여백 키가 없어, 그릴 때 여백을 1씩 줄인다.</li>
 * </ul>
 * Look and Feel의 {@code ScrollBarUI}로 등록한다({@link ChromiteLightLaf#getDefaults()}, {@link ChromiteDarkLaf#getDefaults()}).
 * {@code UIDefaults.getUI}가 리플렉션으로 {@link #createUI}를 부르므로 public이다.
 *
 * 변경 이력:
 * - 2026-11-06 P1: 최초 작성 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-07 테마 전환 뒤 SQL 편집기 세로 스크롤바 트랙이 옛 테마 색으로 남던 문제: 뷰 바탕색 변경을 듣고 스크롤바를 다시 그린다
 */
public class ChromiteScrollBarUI extends FlatScrollBarUI {

    @Nullable
    private ViewBackgroundTracker viewBackgroundTracker;

    public static ComponentUI createUI(JComponent c) {
        return new ChromiteScrollBarUI();
    }

    /**
     * Registers this UI in the look and feel defaults. The class object is stored under its name as well, so Swing
     * does not have to find the class through a class loader (the bundle's loader is not the one Swing would use).
     */
    static void install(UIDefaults defaults) {
        defaults.put("ScrollBarUI", ChromiteScrollBarUI.class.getName()); //$NON-NLS-1$
        defaults.put(ChromiteScrollBarUI.class.getName(), ChromiteScrollBarUI.class);
    }

    @Override
    protected void installListeners() {
        super.installListeners();
        viewBackgroundTracker = new ViewBackgroundTracker();
        scrollbar.addHierarchyListener(viewBackgroundTracker);
        viewBackgroundTracker.bind();
    }

    @Override
    protected void uninstallListeners() {
        if (viewBackgroundTracker != null) {
            scrollbar.removeHierarchyListener(viewBackgroundTracker);
            viewBackgroundTracker.unbind();
            viewBackgroundTracker = null;
        }
        super.uninstallListeners();
    }

    @Override
    protected Color getTrackColor(JComponent c, boolean hover, boolean pressed) {
        Color viewBackground = getViewBackground();
        return viewBackground != null ? viewBackground : super.getTrackColor(c, hover, pressed);
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
        Insets normalInsets = thumbInsets;
        if ((hoverTrack || hoverThumb) && normalInsets != null) {
            // Expanded thumb: 1px less inset on each side
            thumbInsets = new Insets(Math.max(0, normalInsets.top - 1), Math.max(0, normalInsets.left - 1),
                Math.max(0, normalInsets.bottom - 1), Math.max(0, normalInsets.right - 1));
        }
        try {
            super.paintThumb(g, c, thumbBounds);
        } finally {
            thumbInsets = normalInsets;
        }
    }

    /**
     * Background of the scrolled content: the view of the scroll pane this scroll bar belongs to.
     */
    @Nullable
    private Color getViewBackground() {
        if (scrollbar != null && scrollbar.getParent() instanceof JScrollPane scrollPane) {
            JViewport viewport = scrollPane.getViewport();
            Component view = viewport != null ? viewport.getView() : null;
            if (view != null && view.isOpaque()) {
                return view.getBackground();
            }
            if (viewport != null) {
                return viewport.getBackground();
            }
        }
        return null;
    }

    /**
     * Repaints the scroll bar when the colors its track is painted with change. Changing the background of the view
     * (an editor applying theme colors after a look and feel switch, the Options preview) repaints only the view, so
     * without this the track keeps the previous color until something else repaints the scroll bar.
     * Follows the scroll bar into a scroll pane (it is added only after the UI is installed), the scroll pane's
     * viewport and the viewport's view.
     */
    private final class ViewBackgroundTracker implements HierarchyListener, PropertyChangeListener, ContainerListener {
        @Nullable
        private JScrollPane scrollPane;
        @Nullable
        private JViewport viewport;
        @Nullable
        private Component view;

        void bind() {
            if (scrollbar.getParent() instanceof JScrollPane pane) {
                scrollPane = pane;
                pane.addPropertyChangeListener("viewport", this); //$NON-NLS-1$
                viewport = pane.getViewport();
                if (viewport != null) {
                    viewport.addPropertyChangeListener(this);
                    viewport.addContainerListener(this);
                    view = viewport.getView();
                    if (view != null) {
                        view.addPropertyChangeListener(this);
                    }
                }
            }
        }

        void unbind() {
            if (view != null) {
                view.removePropertyChangeListener(this);
                view = null;
            }
            if (viewport != null) {
                viewport.removePropertyChangeListener(this);
                viewport.removeContainerListener(this);
                viewport = null;
            }
            if (scrollPane != null) {
                scrollPane.removePropertyChangeListener("viewport", this); //$NON-NLS-1$
                scrollPane = null;
            }
        }

        private void rebind() {
            unbind();
            bind();
            scrollbar.repaint();
        }

        @Override
        public void hierarchyChanged(HierarchyEvent e) {
            if ((e.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0 && e.getChanged() == scrollbar) {
                rebind();
            }
        }

        @Override
        public void propertyChange(PropertyChangeEvent e) {
            String name = e.getPropertyName();
            if ("viewport".equals(name)) { //$NON-NLS-1$
                rebind();
            } else if ("background".equals(name) || "opaque".equals(name)) { //$NON-NLS-1$ //$NON-NLS-2$
                // The track color comes from these (getViewBackground)
                scrollbar.repaint();
            }
        }

        @Override
        public void componentAdded(ContainerEvent e) {
            // JViewport.setView removes the old view and adds the new one
            rebind();
        }

        @Override
        public void componentRemoved(ContainerEvent e) {
            rebind();
        }
    }
}
