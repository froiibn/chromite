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

import com.formdev.flatlaf.ui.FlatTabbedPaneUI;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;

/**
 * 탭 창 UI: 탭 줄이 넘칠 때 나오는 숨겨진 탭 단추가 테마를 바꾼 뒤에도 도구 설명을 갖게 한다.
 *
 * 설계: FlatLaf 3.8의 {@link FlatTabbedPaneUI}는 숨겨진 탭 단추를 만들 때 생성자에서 도구 설명({@code moreTabsButtonToolTipText})을 읽는다.
 * 단추는 {@code BasicTabbedPaneUI.installUI}의 {@code installComponents}에서 만들어지는데, 이 필드를 채우는 {@code installDefaults}는
 * 그 뒤에 불린다. 그래서 새 UI 객체의 첫 단추는 도구 설명이 없다. 처음 실행할 때는 탭 창이 만들어진 뒤
 * {@code setTabLayoutPolicy(SCROLL_TAB_LAYOUT)}가 같은 UI 객체를 다시 설치해 단추를 다시 만들므로 보이지만, 실행 중 테마 전환은
 * 탭 창마다 새 UI 객체를 설치할 뿐이라 단추가 도구 설명 없이 남았다. 문서 탭({@link MainFrame}), 도구 창 탭, 연결 설정 탭이 모두 같다.
 * 단추를 만든 뒤 도구 설명이 비어 있으면 UI 기본값({@code TabbedPane.moreTabsButtonToolTipText}, {@link ChromiteTheme}가 화면 언어 문구를 넣음)에서 채운다.
 * 테마 전환 뒤에 창마다 단추를 찾아 고치지 않고 UI에서 고치므로, 이후에 만들어지는 탭 창에도 적용된다.
 * Look and Feel의 {@code TabbedPaneUI}로 등록한다({@link ChromiteLightLaf#getDefaults()}, {@link ChromiteDarkLaf#getDefaults()}).
 * {@code UIDefaults.getUI}가 리플렉션으로 {@link #createUI}를 부르므로 public이다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 테마를 바꾸면 숨겨진 탭 단추의 도구 설명이 사라지던 문제 (docs/chromite/swing-followup-fixes.md "작은 화면 수정")
 */
public class ChromiteTabbedPaneUI extends FlatTabbedPaneUI {

    private static final String MORE_TABS_TOOLTIP_KEY = "TabbedPane.moreTabsButtonToolTipText"; //$NON-NLS-1$

    public static ComponentUI createUI(JComponent c) {
        return new ChromiteTabbedPaneUI();
    }

    /**
     * Registers this UI in the look and feel defaults. The class object is stored under its name as well, so Swing
     * does not have to find the class through a class loader (the bundle's loader is not the one Swing would use).
     */
    static void install(UIDefaults defaults) {
        defaults.put("TabbedPaneUI", ChromiteTabbedPaneUI.class.getName()); //$NON-NLS-1$
        defaults.put(ChromiteTabbedPaneUI.class.getName(), ChromiteTabbedPaneUI.class);
    }

    @Override
    protected JButton createMoreTabsButton() {
        JButton button = super.createMoreTabsButton();
        if (button.getToolTipText() == null) {
            // Created by installComponents, before installDefaults has read the tool tip (see the class comment)
            button.setToolTipText(UIManager.getString(MORE_TABS_TOOLTIP_KEY));
        }
        return button;
    }
}
