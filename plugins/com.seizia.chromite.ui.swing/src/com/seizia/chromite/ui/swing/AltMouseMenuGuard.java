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

import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Windows: an Alt that was held while the mouse was used (press, click, drag, wheel) in any Chromite window does not select
 * the menu bar when it is released; the focus stays where it is.
 *
 * 설계 (앱 변경 #100, #102, docs/chromite/swing-sql-column-selection.md "Alt 떼기와 메뉴 막대"):
 * <ul>
 *     <li>Windows에서 Alt를 눌렀다 떼면 FlatLaf {@code MnemonicHandler}(키 후처리기)가 메뉴 막대의 첫 메뉴를 고른다. 그 사이의 다른 키는 이를
 *     취소하지만 마우스는 취소하지 않는다. 그래서 Alt+끌기 열 선택, 긴 줄의 Alt+휠, 다른 곳의 Alt+클릭 뒤에 Alt를 떼면 포커스가 메뉴로 갔다.
 *     {@code MnemonicHandler}는 소비된 Alt 떼기를 건너뛰므로(메뉴를 고르지 않고 니모닉 밑줄만 정리함) 그 떼기를 소비한다.</li>
 *     <li>모든 창·구성 요소에 한 규칙: 마우스 쪽은 {@link AWTEventListener}(누르기·끌기·휠, Alt가 눌린 것)가 표시하고, 키 쪽은
 *     {@link KeyEventDispatcher}가 떼기를 소비한다. 키 디스패처는 구성 요소와 모든 후처리기보다 먼저 돌므로 LaF가 다시 설치되어 후처리기 순서가
 *     바뀌어도 상관없다. 떼기는 소비하기 전에 포커스 주인에게 그대로 보낸다({@code redispatchEvent}): 키 리스너·떼기 키 바인딩·AWT 리스너는
 *     예전처럼 떼기를 받고, 후처리기(FlatLaf)만 소비된 이벤트를 본다. 창의 기본 처리(시스템 메뉴)도 소비된 이벤트는 넘기지 않는다.</li>
 *     <li>표시는 Alt 떼기와, 포커스가 이 응용 프로그램을 떠날 때(Alt+Tab: 떼기는 다른 프로그램이 받음) 지운다. Alt 누르기에서는 지우지 않는다:
 *     Alt를 누르고 있으면 키 반복으로 누르기가 계속 온다.</li>
 *     <li>Windows에서만 설치한다: FlatLaf가 Alt로 메뉴를 고르는 것은 Windows뿐이다(macOS의 Option, Linux의 Alt는 메뉴 키가 아님).
 *     주 창과 함께 설치하고 주 창이 닫히면 지운다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성(앱 변경 #102): 쿼리 편집기에만 있던 표시({@code SqlColumnSelection}, 앱 변경 #100)를 모든 창의 마우스·휠로 넓혀 옮김
 */
final class AltMouseMenuGuard implements AWTEventListener, KeyEventDispatcher {

    // EDT only: Alt is down and the mouse was used since it was pressed
    private boolean altUsed;

    private AltMouseMenuGuard() {
    }

    /**
     * Installs the guard for the life of the main window (Windows only).
     */
    static void install(@NotNull Window mainWindow) {
        if (!RuntimeUtils.isWindows()) {
            return;
        }
        AltMouseMenuGuard guard = new AltMouseMenuGuard();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(guard);
        Toolkit.getDefaultToolkit().addAWTEventListener(guard,
            AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK);
        mainWindow.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(guard);
                Toolkit.getDefaultToolkit().removeAWTEventListener(guard);
                mainWindow.removeWindowListener(this);
            }
        });
    }

    @Override
    public void eventDispatched(AWTEvent event) {
        if (event instanceof MouseEvent mouseEvent) {
            int id = mouseEvent.getID();
            if (mouseEvent.isAltDown()
                && (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_DRAGGED || id == MouseEvent.MOUSE_WHEEL)) {
                altUsed = true;
            }
        } else if (event.getID() == WindowEvent.WINDOW_LOST_FOCUS && ((WindowEvent) event).getOppositeWindow() == null) {
            // Another application got the focus: it gets the Alt release
            altUsed = false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getID() != KeyEvent.KEY_RELEASED || (e.getKeyCode() != KeyEvent.VK_ALT && e.getKeyCode() != KeyEvent.VK_ALT_GRAPH)) {
            return false;
        }
        boolean used = altUsed;
        altUsed = false;
        if (used && e.getKeyCode() == KeyEvent.VK_ALT && !e.isConsumed()) {
            // The focus owner still gets the release; the post-processors (FlatLaf's menu bar selection) see it consumed
            Component target = e.getComponent();
            if (target != null && target.isEnabled()) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(target, e);
            }
            e.consume();
        }
        return false;
    }
}
