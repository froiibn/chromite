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

import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * macOS: the character of an Option key press that a shortcut handled (⌥P Execute From Cursor, ⌥X Execute) does not reach a
 * text component; an Option key press that nothing handled still types its character (⌥O "ø"), and dead keys still compose.
 *
 * 설계 (앱 변경 #122, docs/chromite/shortcuts.md "Option 단축키의 글자"):
 * <ul>
 *     <li>macOS AWT는 키 하나에 키 누름과 글자 이벤트를 차례로 이벤트 큐에 넣는다({@code CPlatformResponder.handleKeyEvent}: 누름 뒤에
 *     Command가 없으면 같은 수정자로 글자 이벤트). Option 글자는 macOS에서 인쇄 글자이고({@code LWCToolkit.isPrintableCharacterModifiersMask}),
 *     Swing은 키 바인딩이 누름을 처리해도 그 뒤의 글자 이벤트를 버리지 않는다({@code DefaultKeyboardFocusManager.consumeNextKeyTyped}는 초점 이동
 *     키와 열린 메뉴의 니모닉에만 쓰임). 그래서 ⌥P를 누르면 실행하고 "π"도 편집기에 들어간다.</li>
 *     <li>판단 근거는 그 누름 이벤트 자체다: 단축키(메뉴 단축키, 창·구성 요소의 키 바인딩)가 처리한 누름은 {@code JComponent.processKeyEvent}가
 *     소비한다. 글자 이벤트는 같은 누름이 모두 처리된 뒤 EDT에 오므로, 바로 앞의 Option 누름이 소비되었으면 그 글자 이벤트를 버린다. 어느
 *     단축키가 처리했는지 목록을 두지 않으므로 앞으로 더하는 Option 단축키에도 그대로 맞는다. 처리되지 않은 누름의 글자(⌥O "ø")는 그대로 가고,
 *     데드 키(⌥E, ⌥N)는 누름에 글자 이벤트가 없고 다음 키의 누름이 표시를 지우므로 조합 글자도 그대로다.</li>
 *     <li>키 디스패처는 모든 구성 요소와 후처리기보다 먼저 이벤트를 받는다. 누름에서는 기억만 하고 {@code false}를 돌려주어 처리 순서를
 *     바꾸지 않는다. 버리는 글자 이벤트에만 {@code true}를 돌려준다.</li>
 *     <li>macOS에서만 설치한다. Windows·Linux에서는 Alt만 누른 글자가 인쇄 글자가 아니라({@code SunToolkit.isPrintableCharacterModifiersMask})
 *     이 문제가 없고, 설치하지 않으므로 동작도 그대로다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성(앱 변경 #122): ⌥X를 Execute의 둘째 키로 더하면서 ⌥P와 함께 모든 Option 단축키의 글자를 막음
 */
final class MacOptionKeyGuard implements KeyEventDispatcher {

    // EDT only: the last Option key press without Command, until its character event or the next key event
    @Nullable
    private KeyEvent optionPress;

    private MacOptionKeyGuard() {
    }

    /**
     * Installs the guard for the life of the main window (macOS only).
     */
    static void install(@NotNull Window mainWindow) {
        if (!RuntimeUtils.isMacOS()) {
            return;
        }
        MacOptionKeyGuard guard = new MacOptionKeyGuard();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(guard);
        mainWindow.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(guard);
                mainWindow.removeWindowListener(this);
            }
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        switch (e.getID()) {
            case KeyEvent.KEY_PRESSED -> {
                // With Command macOS sends no character event; the press is dispatched as usual and looked at again below
                optionPress = e.isAltDown() && !e.isMetaDown() ? e : null;
                return false;
            }
            case KeyEvent.KEY_TYPED -> {
                KeyEvent press = optionPress;
                optionPress = null;
                if (press != null && press.isConsumed()) {
                    // The character of a key press a shortcut handled
                    e.consume();
                    return true;
                }
                return false;
            }
            default -> {
                optionPress = null;
                return false;
            }
        }
    }
}
