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

/**
 * DataGrip New UI 스크롤바(design-spec "13. 스크롤바"): 트랙은 보이지 않고(스크롤하는 내용의 바탕색), 막대는 폭 7의 둥근 반투명 막대이며
 * 마우스를 스크롤바 위에 올리면 폭 9로 넓어지고 진해진다.
 *
 * 설계: 폭·막대 여백·모서리·막대 색은 테마 properties의 FlatLaf 키({@code ScrollBar.width}, {@code thumbInsets}, {@code thumbArc}, {@code thumb},
 * {@code hoverThumbColor})다. FlatLaf에 없는 두 가지만 여기서 한다.
 * <ul>
 *     <li>보이지 않는 트랙: FlatLaf 트랙 색은 창 바탕(bg.panel)에서 만들어져, 바탕이 다른 편집기·결과 그리드(bg.editor) 옆에 띠가 생긴다.
 *     반투명 색은 불투명 컴포넌트의 바탕에 쓸 수 없으므로, 트랙을 스크롤하는 내용(뷰)의 바탕색으로 칠한다.</li>
 *     <li>마우스 올림에 넓어지기: FlatLaf에는 마우스 올림 때의 막대 여백 키가 없어, 그릴 때 여백을 1씩 줄인다.</li>
 * </ul>
 * Look and Feel의 {@code ScrollBarUI}로 등록한다({@link ChromiteLightLaf#getDefaults()}, {@link ChromiteDarkLaf#getDefaults()}).
 * {@code UIDefaults.getUI}가 리플렉션으로 {@link #createUI}를 부르므로 public이다.
 *
 * 변경 이력:
 * - 2026-11-06 P1: 최초 작성 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 */
public class ChromiteScrollBarUI extends FlatScrollBarUI {

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
}
