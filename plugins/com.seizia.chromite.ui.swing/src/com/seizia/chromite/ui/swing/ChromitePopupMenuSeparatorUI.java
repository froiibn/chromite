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

import com.formdev.flatlaf.ui.FlatPopupMenuSeparatorUI;

import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.UIResource;
import java.awt.*;

/**
 * Group separators in menus (menu bar menus, right-click menus) drawn in their own theme color
 * {@code PopupMenuSeparator.foreground} instead of {@code Separator.foreground}.
 *
 * 설계: Swing의 메뉴 구분선 UI는 색을 {@code Separator.foreground}에서만 가져온다(BasicSeparatorUI가 키를 고정해 둠). 그 색은
 * 대화상자의 구분선, 제목 붙은 테두리, 메뉴 막대 아래 선과 함께 쓰는 옅은 구분 색(border.subtle)이라 흰 메뉴 위에서 거의 보이지 않고
 * (라이트 #e9eaee, 1.2:1) 다크 테마에서는 메뉴 바탕색과 같아 아예 보이지 않았다. 메뉴의 구분선만 진하게 하려고 이 UI가 메뉴 구분선에만
 * 따로 키를 읽는다. 다른 구분선은 그대로 둔다. 색은 테마 properties에 있다(앱 변경 #98, docs/chromite/swing-frontend.md "메뉴 간격과 구분선").
 * Look and Feel의 {@code PopupMenuSeparatorUI}로 등록한다({@link ChromiteLightLaf#getDefaults()}, {@link ChromiteDarkLaf#getDefaults()}).
 * {@code UIDefaults.getUI}가 리플렉션으로 {@link #createUI}를 부르므로 public이다.
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성 (앱 변경 #98: 메뉴 구분선이 보이지 않음)
 */
public class ChromitePopupMenuSeparatorUI extends FlatPopupMenuSeparatorUI {

    private static final String FOREGROUND_KEY = "PopupMenuSeparator.foreground"; //$NON-NLS-1$

    private ChromitePopupMenuSeparatorUI() {
        // One UI per separator (not shared), so the sizes are read again after a theme or density change
        super(false);
    }

    public static ComponentUI createUI(JComponent c) {
        return new ChromitePopupMenuSeparatorUI();
    }

    /**
     * Registers this UI in the look and feel defaults. The class object is stored under its name as well, so Swing
     * does not have to find the class through a class loader (the bundle's loader is not the one Swing would use).
     */
    static void install(UIDefaults defaults) {
        defaults.put("PopupMenuSeparatorUI", ChromitePopupMenuSeparatorUI.class.getName()); //$NON-NLS-1$
        defaults.put(ChromitePopupMenuSeparatorUI.class.getName(), ChromitePopupMenuSeparatorUI.class);
    }

    @Override
    protected void installDefaults(JSeparator s) {
        super.installDefaults(s);
        // A color set by code (not a UIResource) is kept, as the look and feel keeps it
        Color color = UIManager.getColor(FOREGROUND_KEY);
        if (color != null && (s.getForeground() == null || s.getForeground() instanceof UIResource)) {
            s.setForeground(new ColorUIResource(color));
        }
    }
}
