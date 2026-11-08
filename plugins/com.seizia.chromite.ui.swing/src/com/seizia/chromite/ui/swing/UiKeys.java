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

import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Map;

/**
 * Shortcuts and modifier keys of the platform: the command key is Ctrl on Windows and Linux, Command (⌘) on macOS.
 *
 * 설계 (앱 변경 #24, docs/chromite/shortcuts.md):
 * <ul>
 *     <li>단축키는 Windows 키(Ctrl+키)로 적고 {@link #command}가 플랫폼 키로 바꾼다. 명령 키는 한 곳
 *     ({@link Toolkit#getMenuShortcutKeyMaskEx()}: Windows·Linux Ctrl, macOS Command)에서만 정한다. 그래서 메뉴, 창 단축키,
 *     편집기 키, 도움말 글자가 같은 키를 쓴다. RSyntaxTextArea의 기본 키도 같은 마스크를 써서({@code RTextArea.getDefaultModifier})
 *     편집기에 Ctrl과 Command가 함께 걸리지 않는다.</li>
 *     <li>macOS에서 Ctrl→Command로만 바꾸면 시스템 단축키와 겹치거나 관례와 다른 키는 {@link #MAC_KEYS} 표 한 곳에서 다른 키로 바꾼다.
 *     같은 Windows 키를 쓰는 곳(Edit 메뉴와 편집기의 Replace 등)은 모두 같은 macOS 키가 된다.</li>
 *     <li>모든 플랫폼에서 진짜 Ctrl인 키(Ctrl+Space 자동 완성, JetBrains IDE와 같음)는 {@link #control}로 적는다.</li>
 *     <li>마우스 수정자(Ctrl+클릭 다중 선택, Ctrl+휠 확대)도 같은 명령 키를 본다({@link #isCommandDown}). macOS에서 Ctrl+클릭은
 *     오른쪽 클릭(팝업 메뉴)이다.</li>
 *     <li>도움말·안내의 단축키 글자는 {@link KeyStroke}에서 만든다({@link #getText}): Windows "Ctrl+Shift+F", macOS "⇧⌘F"
 *     (Apple 순서 ⌃⌥⇧⌘, 구분 기호 없음). 그래서 .properties에는 키 글자를 적지 않는다.</li>
 *     <li>플랫폼을 받는 메서드({@link #forPlatform}, {@link #getText(KeyStroke, boolean)}, {@link #isCommandDown(int, int)})는
 *     Mac 없이 macOS 키를 시험하기 위해서다(UiKeysTest).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-09 최초 작성: macOS 단축키를 Command로(앱 변경 #24), {@code WelcomePanel.getKeyText}를 {@link #getText}로 옮김
 *   (docs/chromite/shortcuts.md)
 */
final class UiKeys {

    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int COMMAND = InputEvent.META_DOWN_MASK;
    private static final int ALT = InputEvent.ALT_DOWN_MASK;
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;

    /**
     * macOS keys that are not the plain Ctrl → Command swap, by the Windows key.
     */
    private static final Map<KeyStroke, KeyStroke> MAC_KEYS = Map.of(
        // Cmd+H hides the application: Replace is Option+Cmd+F (Xcode, JetBrains IDEs)
        KeyStroke.getKeyStroke(KeyEvent.VK_H, CTRL), KeyStroke.getKeyStroke(KeyEvent.VK_F, COMMAND | ALT),
        // Cmd+M minimizes the window: Include Actual Plan is Shift+Cmd+M
        KeyStroke.getKeyStroke(KeyEvent.VK_M, CTRL), KeyStroke.getKeyStroke(KeyEvent.VK_M, COMMAND | SHIFT),
        // Redo is Shift+Cmd+Z on macOS; the text area takes it as well as Cmd+Y
        KeyStroke.getKeyStroke(KeyEvent.VK_Y, CTRL), KeyStroke.getKeyStroke(KeyEvent.VK_Z, COMMAND | SHIFT),
        // Shift+Cmd+/ opens the Help menu search: Toggle Block Comment is Option+Cmd+/ (JetBrains IDEs)
        KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, CTRL | SHIFT), KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, COMMAND | ALT),
        // Cmd+` moves to the application's next window: the text view keeps Control+`
        KeyStroke.getKeyStroke(KeyEvent.VK_BACK_QUOTE, CTRL), KeyStroke.getKeyStroke(KeyEvent.VK_BACK_QUOTE, CTRL),
        // Option+Cmd+D shows or hides the Dock: Copy from Row Below is Shift+Cmd+D
        KeyStroke.getKeyStroke(KeyEvent.VK_D, CTRL | ALT), KeyStroke.getKeyStroke(KeyEvent.VK_D, COMMAND | SHIFT));

    /**
     * Punctuation keys named by their character (Ctrl+`), not by the AWT key name (Ctrl+Back Quote).
     */
    private static final Map<Integer, String> KEY_CHARACTERS = Map.ofEntries(
        Map.entry(KeyEvent.VK_BACK_QUOTE, "`"), //$NON-NLS-1$
        Map.entry(KeyEvent.VK_QUOTE, "'"), //$NON-NLS-1$
        Map.entry(KeyEvent.VK_SLASH, "/"), //$NON-NLS-1$
        Map.entry(KeyEvent.VK_BACK_SLASH, "\\"), //$NON-NLS-1$
        Map.entry(KeyEvent.VK_EQUALS, "="), //$NON-NLS-1$
        Map.entry(KeyEvent.VK_MINUS, "-"), //$NON-NLS-1$
        Map.entry(KeyEvent.VK_COMMA, ","), //$NON-NLS-1$
        Map.entry(KeyEvent.VK_PERIOD, "."), //$NON-NLS-1$
        Map.entry(KeyEvent.VK_SEMICOLON, ";"), //$NON-NLS-1$
        Map.entry(KeyEvent.VK_OPEN_BRACKET, "["), //$NON-NLS-1$
        Map.entry(KeyEvent.VK_CLOSE_BRACKET, "]")); //$NON-NLS-1$

    private UiKeys() {
    }

    /**
     * The command key's modifier mask: Ctrl on Windows and Linux, Command on macOS.
     */
    static int getCommandMask() {
        return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    static boolean isMac() {
        return getCommandMask() == COMMAND;
    }

    /**
     * The platform's key for the Windows shortcut Ctrl+{@code keyCode}.
     */
    @NotNull
    static KeyStroke command(int keyCode) {
        return command(keyCode, 0);
    }

    /**
     * The platform's key for the Windows shortcut Ctrl+{@code modifiers}+{@code keyCode}.
     *
     * @param modifiers the other modifiers of the Windows key (Alt, Shift), without Ctrl
     */
    @NotNull
    static KeyStroke command(int keyCode, int modifiers) {
        return forPlatform(keyCode, modifiers, isMac());
    }

    /**
     * {@link #command(int, int)} for the given platform.
     */
    @NotNull
    static KeyStroke forPlatform(int keyCode, int modifiers, boolean mac) {
        KeyStroke windowsKey = KeyStroke.getKeyStroke(keyCode, CTRL | modifiers);
        if (!mac) {
            return windowsKey;
        }
        KeyStroke macKey = MAC_KEYS.get(windowsKey);
        return macKey != null ? macKey : KeyStroke.getKeyStroke(keyCode, COMMAND | modifiers);
    }

    /**
     * Ctrl+{@code keyCode} on every platform, for the keys that stay Control on macOS (Ctrl+Space completion, as in the
     * JetBrains IDEs).
     */
    @NotNull
    static KeyStroke control(int keyCode) {
        return KeyStroke.getKeyStroke(keyCode, CTRL);
    }

    /**
     * Whether the command key is down in a mouse or key event (Ctrl+click on Windows, Command-click on macOS).
     */
    static boolean isCommandDown(@NotNull InputEvent event) {
        return isCommandDown(event.getModifiersEx(), getCommandMask());
    }

    static boolean isCommandDown(int modifiersEx, int commandMask) {
        return (modifiersEx & commandMask) != 0;
    }

    /**
     * Name of the command key for help texts that combine it with the mouse ("Ctrl+click", "⌘+click").
     */
    @NotNull
    static String getCommandText() {
        return getModifiersText(getCommandMask(), isMac());
    }

    /**
     * Shortcut text for tool tips and help texts: "Ctrl+Shift+F" on Windows, "⇧⌘F" on macOS.
     */
    @NotNull
    static String getText(@NotNull KeyStroke keyStroke) {
        return getText(keyStroke, isMac());
    }

    /**
     * Two-key chord text ("Ctrl+K, Ctrl+D").
     */
    @NotNull
    static String getChordText(@NotNull KeyStroke first, @NotNull KeyStroke second) {
        return getText(first) + ", " + getText(second); //$NON-NLS-1$
    }

    /**
     * A help text whose {0}, {1}... are the shortcut texts of {@code keys} ("Save ({0})").
     */
    @NotNull
    static String format(@NotNull String pattern, @NotNull KeyStroke... keys) {
        Object[] texts = new Object[keys.length];
        for (int i = 0; i < keys.length; i++) {
            texts[i] = getText(keys[i]);
        }
        return NLS.bind(pattern, texts);
    }

    /**
     * A help text whose {0} is the shortcut text of the action's accelerator.
     */
    @NotNull
    static String format(@NotNull String pattern, @NotNull Action action) {
        return format(pattern, (KeyStroke) action.getValue(Action.ACCELERATOR_KEY));
    }

    /**
     * Puts the shortcut text of the action's accelerator into its tool tip ({0}), so the key is written in one place.
     *
     * @return the action
     */
    @NotNull
    static Action formatDescription(@NotNull Action action) {
        action.putValue(Action.SHORT_DESCRIPTION, format((String) action.getValue(Action.SHORT_DESCRIPTION), action));
        return action;
    }

    @NotNull
    static String getText(@NotNull KeyStroke keyStroke, boolean mac) {
        String modifiers = getModifiersText(keyStroke.getModifiers(), mac);
        String key = KEY_CHARACTERS.getOrDefault(keyStroke.getKeyCode(), KeyEvent.getKeyText(keyStroke.getKeyCode()));
        return modifiers.isEmpty() || mac ? modifiers + key : modifiers + "+" + key; //$NON-NLS-1$
    }

    /**
     * macOS: the symbols in Apple's order (⌃⌥⇧⌘) without separators; elsewhere the AWT names (Ctrl+Alt+Shift).
     */
    @NotNull
    private static String getModifiersText(int modifiers, boolean mac) {
        if (!mac) {
            return InputEvent.getModifiersExText(modifiers);
        }
        StringBuilder text = new StringBuilder();
        if ((modifiers & CTRL) != 0) {
            text.append('⌃');
        }
        if ((modifiers & ALT) != 0) {
            text.append('⌥');
        }
        if ((modifiers & SHIFT) != 0) {
            text.append('⇧');
        }
        if ((modifiers & COMMAND) != 0) {
            text.append('⌘');
        }
        return text.toString();
    }
}
