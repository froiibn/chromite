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

import com.formdev.flatlaf.ui.FlatMenuBarUI;
import com.seizia.chromite.Log;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.code.NotNull;

import javax.swing.*;
import javax.swing.plaf.MenuBarUI;
import java.lang.reflect.Field;

/**
 * The main window's menus in the macOS screen menu bar (the bar at the top of the screen) instead of inside the window.
 *
 * 설계 (앱 변경 #27, docs/chromite/mac-ui.md "화면 메뉴 막대"):
 * <ul>
 *     <li>켜는 값은 제품 런처의 macOS 전용 인자({@code chromite-swing.product}의 {@code vmArgsMac})
 *     {@code -Dapple.laf.useScreenMenuBar=true}다. JVM 시작 때 정해지므로 AWT·Look and Feel보다 확실히 앞이고, Windows에는 없다.
 *     FlatLaf는 이 값이 켜져 있으면 메뉴 막대 UI를 JDK의 {@code com.apple.laf.AquaMenuBarUI}로 바꾼다({@code FlatLaf.getDefaults}).
 *     {@code AquaMenuBarUI}는 메뉴 막대를 크기 0으로 두고 같은 메뉴를 AWT 메뉴 막대({@code ScreenMenuBar})로 창에 붙여 화면 메뉴 막대에 보인다.</li>
 *     <li>그러나 Eclipse 런처는 JVM 전에 평범한 {@code NSApplication}을 만들어(docs/chromite/platform-startup.md "macOS 앱 메뉴") AWT가
 *     내장(embedded) 상태로 시작하고, {@code AquaMenuBarUI.getScreenMenuBarProperty()}는 내장 상태면 값과 관계없이 false다
 *     ({@code LWCToolkit.isEmbedded()}, 다른 UI 도구가 메뉴 막대를 가진다고 보기 때문). 이 앱에서는 런처의 앱이 AWT 것만 남아 있고
 *     AWT 메뉴 막대({@code CMenuBar})는 어떤 {@code NSApplication}의 메인 메뉴에서도 동작하므로, 메뉴 막대 UI가 바뀔 때마다
 *     ({@link #attach}, 테마 바꾸기 포함) 그 UI의 {@code useScreenMenuBar}를 켠다. 이 필드는 JDK 내부라 런처 인자
 *     {@code --add-opens=java.desktop/com.apple.laf=ALL-UNNAMED}가 함께 있어야 한다.</li>
 *     <li>필드를 켜지 못하면(인자 없는 예전 설정 등) Aqua 메뉴 막대가 창 안에 남지 않게 FlatLaf 메뉴 막대 UI로 되돌린다: 예전처럼 창 안 메뉴.</li>
 *     <li>메뉴 막대에는 메뉴만 보인다. 그래서 메뉴가 아닌 것(업데이트 단추, {@link UpdateUi})은 {@link #isEnabled()}일 때 상태 표시줄로 가고,
 *     앱 메뉴와 겹치는 File &gt; Exit·Tools &gt; Options·Help &gt; About은 macOS에서 창 메뉴에 넣지 않는다({@code MainFrame}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-09 최초 작성: macOS 화면 메뉴 막대(앱 변경 #27) (docs/chromite/mac-ui.md)
 * - 2026-11-12 {@link #isAttached}: 통합 제목 줄({@link MacOSUnifiedTitleBar}, 앱 변경 #62)은 메뉴 막대가 창 안에 남으면 켜지 않음
 *   (창 안 메뉴 막대가 신호등 단추 줄에 겹침) (docs/chromite/mac-ui.md "통합 제목 줄")
 */
final class MacOSScreenMenuBar {

    private static final Log log = Log.getLog(MacOSScreenMenuBar.class);

    /** The JDK's system property; FlatLaf reads it too */
    private static final String USE_SCREEN_MENU_BAR = "apple.laf.useScreenMenuBar"; //$NON-NLS-1$
    private static final String AQUA_MENU_BAR_UI = "com.apple.laf.AquaMenuBarUI"; //$NON-NLS-1$
    private static final String USE_SCREEN_MENU_BAR_FIELD = "useScreenMenuBar"; //$NON-NLS-1$

    private static boolean failureLogged;

    private MacOSScreenMenuBar() {
    }

    /**
     * macOS with the screen menu bar turned on (launcher argument): the main window's menu bar shows menus only, at the
     * top of the screen.
     */
    static boolean isEnabled() {
        return RuntimeUtils.isMacOS() && Boolean.getBoolean(USE_SCREEN_MENU_BAR);
    }

    /**
     * Whether the menu bar is in the screen menu bar after {@link #attach}: its UI is still the Aqua one ({@link #attach}
     * goes back to FlatLaf's when it cannot use the screen menu bar, and then the menu bar takes a row of the window).
     */
    static boolean isAttached(@NotNull JMenuBar menuBar) {
        MenuBarUI ui = menuBar.getUI();
        return isEnabled() && ui != null && AQUA_MENU_BAR_UI.equals(ui.getClass().getName());
    }

    /**
     * Makes the menu bar's current UI use the screen menu bar. Call after every UI change of the menu bar
     * ({@link JMenuBar#updateUI()}); does nothing unless {@link #isEnabled()}.
     */
    static void attach(@NotNull JMenuBar menuBar) {
        if (!isEnabled()) {
            return;
        }
        MenuBarUI ui = menuBar.getUI();
        if (ui == null || !AQUA_MENU_BAR_UI.equals(ui.getClass().getName())) {
            return;
        }
        try {
            // AquaMenuBarUI reads the property only when AWT is not embedded; the launcher's NSApplication makes it embedded
            Field field = ui.getClass().getDeclaredField(USE_SCREEN_MENU_BAR_FIELD);
            field.setAccessible(true);
            field.setBoolean(ui, true);
            // The in-window menu bar's F10 key would open a menu of the zero-size bar
            SwingUtilities.replaceUIInputMap(menuBar, JComponent.WHEN_IN_FOCUSED_WINDOW, null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!failureLogged) {
                failureLogged = true;
                log.warn("Cannot use the macOS screen menu bar, the menus stay in the window", e);
            }
            // The Aqua menu bar UI would paint in the window: back to FlatLaf's
            menuBar.setUI((MenuBarUI) FlatMenuBarUI.createUI(menuBar));
        }
    }
}
