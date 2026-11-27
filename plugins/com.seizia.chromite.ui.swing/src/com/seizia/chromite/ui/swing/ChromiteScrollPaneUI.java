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

import com.formdev.flatlaf.ui.FlatScrollPaneUI;
import com.formdev.flatlaf.util.UIScale;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import java.awt.*;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

/**
 * 스크롤 창 UI: 안쪽 스크롤 창이 휠 방향으로 더 움직일 수 없으면 휠을 그 방향으로 움직일 수 있는 가장 가까운 바깥 스크롤 창에 넘긴다.
 *
 * 설계: AWT는 휠 이벤트를 포인터 아래에서 휠 듣는 것이 있는 가장 안쪽 구성 요소에 한 번만 보낸다. 스크롤 창은 모두 휠을 듣고
 * ({@code BasicScrollPaneUI}), 움직일 수 없어도 이벤트를 소비할 뿐 바깥으로 넘기지 않는다(세로 막대가 없으면 보이지 않는 세로 막대를
 * 움직이려다 아무 일도 하지 않는다). 그래서 스크롤되는 양식 안에 스크롤 창(다 펼쳐진 양식 묶음, 줄이 적은 표·목록, 미리 보기 글 상자)이
 * 있으면 포인터가 그 위에 있는 동안 휠이 죽는다. 옵션의 Chromite Agent &gt; Providers는 탭 전체가 스크롤 창이라 페이지 여백 말고는 어디서도
 * 스크롤되지 않았다(앱 변경 #97). 페이지마다 안쪽 스크롤 창을 없애거나 듣는 것을 다는 대신 Look and Feel의 {@code ScrollPaneUI} 한 곳에서
 * 규칙을 둔다. 그래서 옵션 밖의 같은 구조(연결 대화상자의 설정 탭, 도구 창, 대화상자)와 앞으로 만들 화면에도 같이 적용된다.
 * <ul>
 *     <li>휠 방향: 세로(Shift를 누르면 가로), 정밀 회전 값의 부호. 원격 데스크톱·고해상도 휠의 회전 0·소수 이벤트도 같은 규칙을 탄다.</li>
 *     <li>그 방향의 막대가 보이고 끝에 닿지 않았으면 이 창이 스크롤한다(지금까지와 같음). 움직일 수 있는 목록·글 상자는 먼저 자기가 스크롤한다.</li>
 *     <li>아니면 같은 창 안의 바깥 스크롤 창 중 그 방향으로 움직일 수 있는 가장 가까운 것에 이벤트를 넘긴다. 넘기는 이벤트는 정밀 회전 값을
 *     그대로 가지므로 받는 창의 스크롤 속도가 쓰인다.</li>
 *     <li>그런 바깥 창이 없으면 지금까지와 같다(세로 막대가 없는 창의 휠은 가로로 스크롤 등). 그래서 결과 표·쿼리 편집기처럼 바깥 스크롤 창이 없는
 *     곳의 동작은 바뀌지 않는다.</li>
 * </ul>
 * 바깥 창은 같은 창(window) 안에서만 찾는다. 창의 부모는 주인 창이라 팝업 목록의 휠이 주인 창으로 넘어가지 않게 하기 위해서다.
 * <p>
 * 스크롤 단위 기본값: 보기가 {@link Scrollable}이 아니면(양식 {@code JPanel} 등) Swing은 막대의 단위({@link JScrollBar#getUnitIncrement})를 쓰고,
 * 아무도 정하지 않은 막대의 단위는 1px이다. 그래서 휠 한 칸이 3px(운영체제 기본 3단위), 터치패드의 작은 이벤트가 1px만 움직였다(VOC 192,
 * 연결 대화상자 Options의 연결 속성 탭: 끝까지 97칸). 화면마다 막대에 단위를 주는 대신 여기서 그런 창에 {@link #DEFAULT_UNIT_INCREMENT}를 쓴다.
 * 막대에 {@code setUnitIncrement}를 부르지 않는 이유: 한 번 부르면 나중에 보기가 {@code Scrollable}(표, 목록)로 바뀌어도 그 보기의 단위가 무시된다.
 * 계산은 {@code FlatScrollPaneUI}의 고해상도 휠과 같다(한 칸은 보이는 크기를 넘지 않음, 소수 회전은 최소 1px). 단위를 정한 막대와
 * {@code Scrollable} 보기(표, 목록, 글 상자, 옵션 페이지)는 지금까지와 같다.
 * <p>
 * Look and Feel의 {@code ScrollPaneUI}로 등록한다({@link ChromiteLightLaf#getDefaults()}, {@link ChromiteDarkLaf#getDefaults()}).
 * {@code UIDefaults.getUI}가 리플렉션으로 {@link #createUI}를 부르므로 public이다.
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: 옵션의 Chromite Agent 등에서 안쪽 스크롤 창이 휠을 삼켜 페이지가 스크롤되지 않던 문제 (앱 변경 #97,
 *   docs/chromite/swing-options.md "휠 스크롤 (#97)")
 * - 2026-11-28 단위를 정하지 않은 막대와 Scrollable이 아닌 보기의 창은 휠 단위 16(연결 대화상자 Options의 연결 속성 탭이 휠 한 칸에 3px만
 *   움직이던 문제, VOC 192, docs/chromite/swing-options.md "스크롤 단위 기본값 (VOC 192)")
 */
public class ChromiteScrollPaneUI extends FlatScrollPaneUI {

    /**
     * Wheel unit of a pane whose view is not {@link Scrollable} and whose bar has no unit increment of its own (before scaling).
     * The connection dialog's network and Settings pages set the same 16 on their bars before; three units (a wheel step) are
     * about one and a half form rows.
     */
    private static final int DEFAULT_UNIT_INCREMENT = 16;

    // JScrollBar's unit increment when nobody set one
    private static final int SWING_UNIT_INCREMENT = 1;

    public static ComponentUI createUI(JComponent c) {
        return new ChromiteScrollPaneUI();
    }

    /**
     * Registers this UI in the look and feel defaults. The class object is stored under its name as well, so Swing
     * does not have to find the class through a class loader (the bundle's loader is not the one Swing would use).
     */
    static void install(UIDefaults defaults) {
        defaults.put("ScrollPaneUI", ChromiteScrollPaneUI.class.getName()); //$NON-NLS-1$
        defaults.put(ChromiteScrollPaneUI.class.getName(), ChromiteScrollPaneUI.class);
    }

    @Override
    protected MouseWheelListener createMouseWheelListener() {
        MouseWheelListener own = super.createMouseWheelListener();
        return e -> {
            JScrollPane outer = canScroll(scrollpane, e) ? null : findOuterScrollPane(e);
            if (outer == null) {
                if (!scrollWithDefaultUnit(e)) {
                    own.mouseWheelMoved(e);
                }
                return;
            }
            e.consume();
            outer.dispatchEvent(SwingUtilities.convertMouseEvent(scrollpane, e, outer));
        };
    }

    /**
     * Scrolls this pane by {@link #DEFAULT_UNIT_INCREMENT} per wheel unit when its view is not {@link Scrollable} and the scrolled
     * bar has the Swing unit increment (nobody chose one); returns false (nothing done) otherwise.
     */
    private boolean scrollWithDefaultUnit(@NotNull MouseWheelEvent e) {
        JViewport viewport = scrollpane.getViewport();
        Component view = viewport == null ? null : viewport.getView();
        double rotation = e.getPreciseWheelRotation();
        if (view == null || view instanceof Scrollable || !scrollpane.isWheelScrollingEnabled()
            || e.getScrollType() != MouseWheelEvent.WHEEL_UNIT_SCROLL || rotation == 0) {
            return false;
        }
        // The bar the Swing handler would scroll: the vertical one, the horizontal one with Shift or without a vertical bar
        JScrollBar bar = scrollpane.getVerticalScrollBar();
        if (bar == null || !bar.isVisible() || e.isShiftDown()) {
            bar = scrollpane.getHorizontalScrollBar();
            if (bar == null || !bar.isVisible()) {
                return false;
            }
        }
        if (bar.getUnitIncrement(rotation < 0 ? -1 : 1) != SWING_UNIT_INCREMENT) {
            return false;
        }
        int visible = bar.getOrientation() == SwingConstants.VERTICAL ? viewport.getHeight() : viewport.getWidth();
        int step = Math.min(UIScale.scale(DEFAULT_UNIT_INCREMENT) * e.getScrollAmount(), visible);
        int delta = (int) Math.round(rotation * step);
        if (delta == 0) {
            delta = rotation < 0 ? -1 : 1;
        }
        BoundedRangeModel model = bar.getModel();
        bar.setValue(Math.max(model.getMinimum(), Math.min(model.getValue() + delta, model.getMaximum() - model.getExtent())));
        e.consume();
        return true;
    }

    /**
     * The nearest enclosing scroll pane in the same window that can scroll in the wheel's direction, or null.
     */
    @Nullable
    private JScrollPane findOuterScrollPane(@NotNull MouseWheelEvent e) {
        for (Container parent = scrollpane.getParent(); parent != null && !(parent instanceof Window); parent = parent.getParent()) {
            if (parent instanceof JScrollPane pane && canScroll(pane, e)) {
                return pane;
            }
        }
        return null;
    }

    /**
     * Whether the pane scrolls on this wheel event: wheel scrolling on, and the bar of the wheel's orientation (horizontal with
     * Shift) visible and not yet at its end in the wheel's direction.
     */
    private static boolean canScroll(@NotNull JScrollPane pane, @NotNull MouseWheelEvent e) {
        double rotation = e.getPreciseWheelRotation();
        if (rotation == 0) {
            // No direction: nothing to hand on, the pane's own handler ignores it as before
            return true;
        }
        if (!pane.isWheelScrollingEnabled() || !pane.isEnabled()) {
            return false;
        }
        JScrollBar bar = e.isShiftDown() ? pane.getHorizontalScrollBar() : pane.getVerticalScrollBar();
        if (bar == null || !bar.isVisible()) {
            return false;
        }
        BoundedRangeModel model = bar.getModel();
        return rotation < 0
            ? model.getValue() > model.getMinimum()
            : model.getValue() + model.getExtent() < model.getMaximum();
    }
}
