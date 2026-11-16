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

import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * The rule of context menus: an item that applies to the kind of thing clicked is shown, an item that applies but is
 * not possible now is shown disabled, and an item that does not apply is not shown.
 *
 * 설계: 명령마다 두 답을 한곳에서 정한다. "이 종류에 해당하는가"(노드 형식, 개체 클래스, DB 종류·버전·에디션)는 동작의
 * {@link #APPLIES} 값(하위 메뉴는 클라이언트 속성)으로, "지금 할 수 있는가"(연결 여부, 읽기 전용, 개체 상태, 시스템 개체, 섞인 다중 선택)는
 * 늘 쓰던 활성 상태로 둔다. 활성 상태는 해당할 때만 켜지므로({@link #set}), 같은 동작을 쓰는 도구 막대·두 번 클릭은 전처럼 활성 상태만
 * 보고, 키 바인딩은 전처럼 명령을 부르고 명령이 스스로 조건을 확인한다. 예외로 {@link #setApplies}는 해당 여부만 정해, 메뉴에서는 숨어도
 * 도구 막대에서는 켜지는 명령에 쓴다. 보임은 팝업이 열릴 때 {@link #tidy}가 메뉴 항목에 옮긴다: 동작 객체에는 Swing 표준의 "보임" 값이 없고, 메뉴 막대와 도구 막대는
 * 항목을 숨기지 않기 때문이다(메뉴 막대는 지금처럼 끄기만 한다). 구분선도 같은 때 정리한다(맨 앞·맨 뒤·연달아 둘이면 숨김): 묶음 전체가
 * 숨으면 그 구분선도 사라져야 하는데, 묶음마다 손으로 맞추면 항목이 바뀔 때마다 어긋난다.
 * <p>
 * {@link #APPLIES} 값이 없는 항목은 스스로 보임을 정하는 항목(SQL Server 에이전트·스케줄러·유지 관리 명령)이거나 늘 해당하는 항목이라
 * 그대로 둔다. 값이 없는 하위 메뉴는 자식에서 정한다: 보이는 자식이 있으면 보이고, 켜진 자식이 있으면 켜진다.
 *
 * 변경 이력:
 * - 2026-11-16 최초 작성: Object Explorer 오른쪽 클릭 메뉴(앱 변경 #137, docs/chromite/context-menus.md)
 * - 2026-11-16 {@link #setApplies}: 메뉴에는 연결 노드에서만 보이지만 도구 막대·메뉴 막대에서는 전처럼 켜지는 연결 명령(사용자 결정 "연결 노드에서만")
 */
final class ContextMenus {

    /**
     * Action value (or client property of a submenu or a plain item): {@link Boolean#TRUE} when the item applies to the
     * kind of thing the menu is opened on.
     */
    static final String APPLIES = "chromite.applies"; //$NON-NLS-1$

    private ContextMenus() {
    }

    /**
     * Sets both answers of an action: whether it applies to the kind of thing selected (shown in context menus) and whether
     * it is possible now. It is enabled only when both are true.
     */
    static void set(@NotNull Action action, boolean applies, boolean possible) {
        action.putValue(APPLIES, applies);
        action.setEnabled(applies && possible);
    }

    /**
     * Sets only whether the action applies (shown in context menus) and leaves its enabled state to the caller: for a
     * command whose tool bar button or key works where the context menu does not show it (Object Explorer's connection
     * commands are shown on the connection node only, but the tool bar's Disconnect works on any node under it).
     */
    static void setApplies(@NotNull Action action, boolean applies) {
        action.putValue(APPLIES, applies);
    }

    /**
     * {@link #set(Action, boolean, boolean)} for a submenu or an item without an action.
     */
    static void set(@NotNull JMenuItem item, boolean applies, boolean possible) {
        item.putClientProperty(APPLIES, applies);
        item.setEnabled(applies && possible);
    }

    /**
     * Shows the items that apply and tidies the separators, also in the submenus; call it when the popup opens, after its
     * actions were updated.
     */
    static void tidy(@NotNull JPopupMenu menu) {
        tidy(menu.getComponents());
    }

    /**
     * @return whether a visible item is enabled (null when no item is visible)
     */
    private static Boolean tidy(@NotNull Component[] components) {
        boolean anyVisible = false;
        boolean anyEnabled = false;
        for (Component component : components) {
            if (!(component instanceof JMenuItem item)) {
                continue;
            }
            Boolean applies = getApplies(item);
            if (item instanceof JMenu menu) {
                Boolean children = tidy(menu.getMenuComponents());
                if (applies == null) {
                    // A plain submenu (Tasks, Script As, Generate SQL) follows its entries
                    menu.setVisible(children != null);
                    menu.setEnabled(Boolean.TRUE.equals(children));
                } else {
                    menu.setVisible(applies);
                }
            } else if (applies != null) {
                item.setVisible(applies);
            }
            if (item.isVisible()) {
                anyVisible = true;
                anyEnabled |= item.isEnabled();
            }
        }
        tidySeparators(components);
        return anyVisible ? anyEnabled : null;
    }

    private static Boolean getApplies(@NotNull JMenuItem item) {
        if (item.getClientProperty(APPLIES) instanceof Boolean applies) {
            return applies;
        }
        Action action = item.getAction();
        return action != null && action.getValue(APPLIES) instanceof Boolean applies ? applies : null;
    }

    /**
     * Separators: never first, never last, never two in a row (among the visible items).
     */
    private static void tidySeparators(@NotNull Component[] components) {
        JSeparator pending = null;
        boolean itemBefore = false;
        for (Component component : components) {
            if (component instanceof JSeparator separator) {
                separator.setVisible(false);
                if (itemBefore && pending == null) {
                    pending = separator;
                }
            } else if (component.isVisible()) {
                if (pending != null) {
                    pending.setVisible(true);
                    pending = null;
                }
                itemBefore = true;
            }
        }
    }
}
