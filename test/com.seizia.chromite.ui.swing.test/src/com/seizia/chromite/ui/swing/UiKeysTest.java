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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The platform key mapping of {@link UiKeys} run with the macOS command key, without a Mac: every Windows shortcut of the
 * application, the macOS keys that are not a plain Ctrl → Command swap, the reserved macOS system keys, the shortcut text and
 * the mouse modifier.
 *
 * 설계: {@link UiKeys}의 플랫폼을 받는 메서드({@code forPlatform}, {@code getText(KeyStroke, boolean)}, {@code isCommandDown(int, int)})를
 * 부르므로 화면도 Mac도 필요 없다. {@link #WINDOWS_KEYS}는 앱이 {@code UiKeys.command}로 거는 Windows 키 목록이다
 * (docs/chromite/shortcuts.md의 표와 같다). 단축키를 더하면 이 목록과 문서에도 더한다.
 *
 * 변경 이력:
 * - 2026-11-09 최초 작성(앱 변경 #24) (docs/chromite/shortcuts.md)
 * - 2026-11-12 Ctrl+Shift+R(결과 최대화)을 목록에 더함, Ctrl+Shift+T는 닫은 탭 다시 열기로 남음(앱 변경 #66)
 */
public class UiKeysTest {

    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int CMD = InputEvent.META_DOWN_MASK;
    private static final int ALT = InputEvent.ALT_DOWN_MASK;
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;

    /**
     * Every Windows shortcut given to {@code UiKeys.command}: key code and the modifiers besides Ctrl.
     */
    private static final List<int[]> WINDOWS_KEYS = List.of(
        // Edit menu
        key(KeyEvent.VK_Z), key(KeyEvent.VK_Y), key(KeyEvent.VK_X), key(KeyEvent.VK_C), key(KeyEvent.VK_V), key(KeyEvent.VK_A),
        key(KeyEvent.VK_F), key(KeyEvent.VK_H),
        // File, Query, View (MainFrame)
        key(KeyEvent.VK_N), key(KeyEvent.VK_O), key(KeyEvent.VK_S), key(KeyEvent.VK_S, SHIFT), key(KeyEvent.VK_A, ALT),
        key(KeyEvent.VK_ENTER), key(KeyEvent.VK_QUOTE, ALT), key(KeyEvent.VK_L), key(KeyEvent.VK_M), key(KeyEvent.VK_T),
        key(KeyEvent.VK_4), key(KeyEvent.VK_8), key(KeyEvent.VK_K, ALT | SHIFT), key(KeyEvent.VK_R, ALT | SHIFT),
        key(KeyEvent.VK_P, SHIFT), key(KeyEvent.VK_T, SHIFT), key(KeyEvent.VK_R, SHIFT), key(KeyEvent.VK_9), key(KeyEvent.VK_0), key(KeyEvent.VK_R),
        key(KeyEvent.VK_G, ALT), key(KeyEvent.VK_T, ALT), key(KeyEvent.VK_L, ALT), key(KeyEvent.VK_O, ALT), key(KeyEvent.VK_I, ALT),
        // Query editor
        key(KeyEvent.VK_K), key(KeyEvent.VK_D), key(KeyEvent.VK_U), key(KeyEvent.VK_F, SHIFT), key(KeyEvent.VK_U, SHIFT),
        key(KeyEvent.VK_L, SHIFT), key(KeyEvent.VK_SLASH, SHIFT), key(KeyEvent.VK_W, ALT | SHIFT),
        key(KeyEvent.VK_SUBTRACT), key(KeyEvent.VK_ADD), key(KeyEvent.VK_DIVIDE), key(KeyEvent.VK_MULTIPLY),
        // Results grid and viewer
        key(KeyEvent.VK_C, SHIFT), key(KeyEvent.VK_BACK_SPACE), key(KeyEvent.VK_U, SHIFT | ALT), key(KeyEvent.VK_INSERT, ALT),
        key(KeyEvent.VK_D, ALT), key(KeyEvent.VK_V, SHIFT), key(KeyEvent.VK_BACK_QUOTE), key(KeyEvent.VK_G),
        key(KeyEvent.VK_G, SHIFT), key(KeyEvent.VK_B), key(KeyEvent.VK_1, SHIFT),
        // ERD and plan zoom
        key(KeyEvent.VK_EQUALS), key(KeyEvent.VK_MINUS));

    /**
     * macOS system and convention keys an application shortcut must not take.
     */
    private static final Set<KeyStroke> MAC_RESERVED = Set.of(
        KeyStroke.getKeyStroke(KeyEvent.VK_H, CMD), KeyStroke.getKeyStroke(KeyEvent.VK_H, CMD | ALT),
        KeyStroke.getKeyStroke(KeyEvent.VK_M, CMD), KeyStroke.getKeyStroke(KeyEvent.VK_M, CMD | ALT),
        KeyStroke.getKeyStroke(KeyEvent.VK_Q, CMD), KeyStroke.getKeyStroke(KeyEvent.VK_W, CMD),
        KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, CMD), KeyStroke.getKeyStroke(KeyEvent.VK_BACK_QUOTE, CMD),
        KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, CMD | SHIFT), KeyStroke.getKeyStroke(KeyEvent.VK_D, CMD | ALT),
        KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, CMD), KeyStroke.getKeyStroke(KeyEvent.VK_TAB, CMD),
        KeyStroke.getKeyStroke(KeyEvent.VK_3, CMD | SHIFT), KeyStroke.getKeyStroke(KeyEvent.VK_4, CMD | SHIFT),
        KeyStroke.getKeyStroke(KeyEvent.VK_5, CMD | SHIFT), KeyStroke.getKeyStroke(KeyEvent.VK_Q, CMD | SHIFT),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, CMD | ALT), KeyStroke.getKeyStroke(KeyEvent.VK_F, CMD | CTRL),
        KeyStroke.getKeyStroke(KeyEvent.VK_Q, CMD | CTRL), KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, CMD | CTRL),
        KeyStroke.getKeyStroke(KeyEvent.VK_D, CMD | CTRL));

    @Test
    public void windowsKeysStayCtrl() {
        for (int[] key : WINDOWS_KEYS) {
            Assertions.assertEquals(KeyStroke.getKeyStroke(key[0], CTRL | key[1]), UiKeys.forPlatform(key[0], key[1], false));
        }
    }

    @Test
    public void macKeysUseCommand() {
        Assertions.assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_S, CMD), UiKeys.forPlatform(KeyEvent.VK_S, 0, true));
        Assertions.assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_S, CMD | SHIFT), UiKeys.forPlatform(KeyEvent.VK_S, SHIFT, true));
        Assertions.assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_G, CMD | ALT), UiKeys.forPlatform(KeyEvent.VK_G, ALT, true));
        Assertions.assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, CMD), UiKeys.forPlatform(KeyEvent.VK_ENTER, 0, true));
        // The keys that are not a plain swap
        Assertions.assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_F, CMD | ALT), UiKeys.forPlatform(KeyEvent.VK_H, 0, true));
        Assertions.assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_M, CMD | SHIFT), UiKeys.forPlatform(KeyEvent.VK_M, 0, true));
        Assertions.assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_Z, CMD | SHIFT), UiKeys.forPlatform(KeyEvent.VK_Y, 0, true));
        Assertions.assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, CMD | ALT), UiKeys.forPlatform(KeyEvent.VK_SLASH, SHIFT, true));
        Assertions.assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_QUOTE, CTRL), UiKeys.forPlatform(KeyEvent.VK_BACK_QUOTE, 0, true));
        Assertions.assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_D, CMD | SHIFT), UiKeys.forPlatform(KeyEvent.VK_D, ALT, true));
        // Control on every platform
        Assertions.assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, CTRL), UiKeys.control(KeyEvent.VK_SPACE));
    }

    @Test
    public void macKeysAvoidSystemKeysAndStayDistinct() {
        Map<KeyStroke, KeyStroke> windowsByMac = new HashMap<>();
        for (int[] key : WINDOWS_KEYS) {
            KeyStroke windows = UiKeys.forPlatform(key[0], key[1], false);
            KeyStroke mac = UiKeys.forPlatform(key[0], key[1], true);
            Assertions.assertFalse(MAC_RESERVED.contains(mac), windows + " -> " + mac + " is a macOS system key");
            KeyStroke other = windowsByMac.put(mac, windows);
            Assertions.assertTrue(other == null || other.equals(windows), other + " and " + windows + " both become " + mac);
        }
        // Completion keeps Control+Space: Command+Space is Spotlight
        Assertions.assertFalse(MAC_RESERVED.contains(UiKeys.control(KeyEvent.VK_SPACE)));
    }

    @Test
    public void shortcutText() {
        Assertions.assertEquals("⇧⌘F", UiKeys.getText(UiKeys.forPlatform(KeyEvent.VK_F, SHIFT, true), true));
        Assertions.assertEquals("⌥⌘F", UiKeys.getText(UiKeys.forPlatform(KeyEvent.VK_H, 0, true), true));
        Assertions.assertEquals("⌥⇧⌘W", UiKeys.getText(UiKeys.forPlatform(KeyEvent.VK_W, ALT | SHIFT, true), true));
        Assertions.assertEquals("⌃`", UiKeys.getText(UiKeys.forPlatform(KeyEvent.VK_BACK_QUOTE, 0, true), true));
        Assertions.assertEquals("⌃" + KeyEvent.getKeyText(KeyEvent.VK_SPACE), UiKeys.getText(UiKeys.control(KeyEvent.VK_SPACE), true));
        String ctrl = InputEvent.getModifiersExText(CTRL);
        String ctrlShift = InputEvent.getModifiersExText(CTRL | SHIFT);
        Assertions.assertEquals(ctrlShift + "+F", UiKeys.getText(UiKeys.forPlatform(KeyEvent.VK_F, SHIFT, false), false));
        Assertions.assertEquals(ctrl + "+`", UiKeys.getText(UiKeys.forPlatform(KeyEvent.VK_BACK_QUOTE, 0, false), false));
        Assertions.assertEquals("F5", UiKeys.getText(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), true));
        for (int[] key : WINDOWS_KEYS) {
            String mac = UiKeys.getText(UiKeys.forPlatform(key[0], key[1], true), true);
            Assertions.assertFalse(mac.contains("+") && key[0] != KeyEvent.VK_ADD, mac);
            Assertions.assertFalse(mac.contains(ctrl), mac);
        }
    }

    @Test
    public void mouseModifier() {
        // macOS: Command-click adds to the selection; Control-click is the right click
        Assertions.assertTrue(UiKeys.isCommandDown(CMD, CMD));
        Assertions.assertFalse(UiKeys.isCommandDown(CTRL, CMD));
        Assertions.assertTrue(UiKeys.isCommandDown(CTRL | SHIFT, CTRL));
        Assertions.assertFalse(UiKeys.isCommandDown(CMD, CTRL));
    }

    private static int[] key(int keyCode) {
        return key(keyCode, 0);
    }

    private static int[] key(int keyCode, int modifiers) {
        return new int[] {keyCode, modifiers};
    }
}
