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

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaEditorKit;
import org.fife.ui.rtextarea.RTextArea;
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
 * - 2026-11-17 {@link #tidyTextArea}: RSyntaxTextArea가 만든 글 영역 메뉴(SQL 편집기, 읽기 전용 보기, 값 패널, 원본 계획)를 한곳에서 같은 규칙으로
 *   (앱 변경 #141). 라이브러리가 만든 항목이라 동작 객체로 골라 낸다
 */
final class ContextMenus {

    /**
     * Action value (or client property of a submenu or a plain item): {@link Boolean#TRUE} when the item applies to the
     * kind of thing the menu is opened on.
     */
    static final String APPLIES = "chromite.applies"; //$NON-NLS-1$

    /**
     * Client property of a text area ({@link Boolean#TRUE}): it shows changing content while it is open (the value panel
     * shows each selected cell, the template page each selected template), so whether it is editable and its language can
     * change. Without it, a read-only text area is a viewer that never becomes editable.
     */
    static final String VARYING_TEXT = "chromite.varyingText"; //$NON-NLS-1$

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
     * The text area's own popup (RTextArea: Undo, Redo | Cut, Copy, Paste, Delete | Select All | Folding, and what the
     * caller added): call it from {@code configurePopupMenu} after the library set the enabled states.
     * <p>
     * The library disables the editing items in a read-only text area and Folding where the language has no folds. A viewer
     * that never becomes editable (DDL, sources, previews, logs) is a kind, so the editing items are hidden there; a text
     * area marked {@link #VARYING_TEXT} is read-only only now and keeps them disabled. Nothing to undo, no selection, nothing
     * to paste stay "now" (the library's enabled states). Folding is hidden where it never works: folding is off, or the
     * language of a text area that is not {@link #VARYING_TEXT} has no fold parser. The items are found by their actions,
     * shared by all text areas (the library keeps its item fields private).
     */
    static void tidyTextArea(@NotNull RSyntaxTextArea area, @NotNull JPopupMenu menu) {
        boolean varying = Boolean.TRUE.equals(area.getClientProperty(VARYING_TEXT));
        boolean editingApplies = area.isEditable() || varying;
        boolean folding = area.getFoldManager().isCodeFoldingSupportedAndEnabled();
        for (Component component : menu.getComponents()) {
            if (component instanceof JMenu submenu && submenu.getItemCount() > 0
                && submenu.getItem(0).getAction() instanceof RSyntaxTextAreaEditorKit.ToggleCurrentFoldAction) {
                set(submenu, varying ? area.isCodeFoldingEnabled() : folding, folding);
            } else if (component instanceof JMenuItem item && isEditingAction(item.getAction())) {
                set(item, editingApplies, item.isEnabled());
            }
        }
        tidy(menu);
    }

    private static boolean isEditingAction(Action action) {
        return action != null && (action == RTextArea.getAction(RTextArea.UNDO_ACTION) || action == RTextArea.getAction(RTextArea.REDO_ACTION)
            || action == RTextArea.getAction(RTextArea.CUT_ACTION) || action == RTextArea.getAction(RTextArea.PASTE_ACTION)
            || action == RTextArea.getAction(RTextArea.DELETE_ACTION));
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
