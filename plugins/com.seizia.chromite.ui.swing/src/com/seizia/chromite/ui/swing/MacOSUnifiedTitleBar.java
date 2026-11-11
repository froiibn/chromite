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

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatNativeMacLibrary;
import com.seizia.chromite.Log;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * The macOS main window as one piece: the content reaches the top of the window, the title bar is transparent, and the
 * main tool bar sits in the row of the traffic-light buttons.
 *
 * 설계 (앱 변경 #62, docs/chromite/mac-ui.md "통합 제목 줄"):
 * <ul>
 *     <li>JDK의 루트 창 속성 {@code apple.awt.fullWindowContent}·{@code apple.awt.transparentTitleBar}·
 *     {@code apple.awt.windowTitleVisible}({@code CPlatformWindow})로 내용을 제목 줄까지 넓히고 제목 글자를 숨긴다. 제목 줄 자리에는
 *     내용 창(캔버스 색, {@code MainFrame.CanvasPanel})이 그대로 보이므로 밝은·어두운 테마의 색이 따로 필요 없다. 신호등 단추의
 *     밝은·어두운 모양은 FlatLaf가 테마마다 창의 {@code NSAppearance}로 맞춘다({@code FlatRootPaneUI.macSetWindowAppearance}).</li>
 *     <li>신호등 단추 자리는 FlatLaf의 자리 표시 패널({@link FlatClientProperties#FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER},
 *     {@code "mac zeroInFullScreen"})이 차지한다. 크기는 FlatLaf가 실제 단추 범위로 정하고, 전체 화면에서는 0이 된다.
 *     직접 여백을 계산하지 않은 이유: 단추 범위는 macOS 버전·단추 간격 설정마다 다르다.</li>
 *     <li>제목 줄 높이 안의 빈 곳(도구 막대의 빈 자리, 구분선, 자리 표시 패널)을 끌면 창이 움직이고, 두 번 누르면 시스템 설정
 *     ({@code AppleActionOnDoubleClick})대로 확대·최소화한다. FlatLaf 3.8의 macOS 네이티브 라이브러리가 마우스 이벤트를 AWT보다
 *     먼저 보고 처리한다({@code FullWindowContentSupport.macInstallTitleBarCaption}). 단추처럼 마우스를 받는 컴포넌트는 그대로
 *     동작한다. 라이브러리를 읽지 못하면 창을 끌 수 없으므로 창이 열릴 때 디버그 로그에 남긴다(macos-smoke가 읽음).</li>
 *     <li>메뉴가 화면 메뉴 막대에 있을 때만 켠다({@link MacOSScreenMenuBar#isAttached}). 메뉴 막대가 창 안에 남으면 그 줄이 신호등
 *     단추와 겹친다.</li>
 *     <li>대화상자와 Windows는 바뀌지 않는다: 주 창에만, macOS에서만 설정한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: macOS 통합 제목 줄(앱 변경 #62) (docs/chromite/mac-ui.md "통합 제목 줄")
 */
final class MacOSUnifiedTitleBar {

    private static final Log log = Log.getLog(MacOSUnifiedTitleBar.class);

    // Root pane client properties of the JDK's macOS window (CPlatformWindow)
    private static final String FULL_WINDOW_CONTENT = "apple.awt.fullWindowContent"; //$NON-NLS-1$
    private static final String TRANSPARENT_TITLE_BAR = "apple.awt.transparentTitleBar"; //$NON-NLS-1$
    private static final String WINDOW_TITLE_VISIBLE = "apple.awt.windowTitleVisible"; //$NON-NLS-1$
    /**
     * The traffic-light buttons' place at the left on macOS, none in full screen (FlatLaf's placeholder syntax)
     */
    private static final String BUTTONS_PLACEHOLDER = "mac zeroInFullScreen"; //$NON-NLS-1$

    private MacOSUnifiedTitleBar() {
    }

    /**
     * Makes the frame's title bar part of its content on macOS when its menu bar is in the screen menu bar, and returns
     * the main tool bar's row: the tool bar after the traffic-light buttons' place. Elsewhere returns the tool bar as is.
     * Call after the menu bar is set and before the frame is shown.
     */
    @NotNull
    static JComponent install(@NotNull JFrame frame, @NotNull JComponent toolBar) {
        JMenuBar menuBar = frame.getJMenuBar();
        if (!RuntimeUtils.isMacOS() || menuBar == null || !MacOSScreenMenuBar.isAttached(menuBar)) {
            return toolBar;
        }
        JRootPane rootPane = frame.getRootPane();
        rootPane.putClientProperty(FULL_WINDOW_CONTENT, true);
        rootPane.putClientProperty(TRANSPARENT_TITLE_BAR, true);
        // The title stays the window's name for the Window menu, Mission Control and the Dock
        rootPane.putClientProperty(WINDOW_TITLE_VISIBLE, false);

        JPanel placeholder = new JPanel();
        placeholder.setOpaque(false);
        placeholder.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER, BUTTONS_PLACEHOLDER);
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.add(placeholder, BorderLayout.WEST);
        row.add(toolBar, BorderLayout.CENTER);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                frame.removeWindowListener(this);
                Object buttons = rootPane.getClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_BOUNDS);
                if (FlatNativeMacLibrary.isLoaded()) {
                    log.debug("macOS unified title bar: window buttons " + buttons + ", placeholder " + placeholder.getSize());
                } else {
                    // Without it the tool bar row can't move the window
                    log.warn("macOS unified title bar without FlatLaf's native library: window buttons " + buttons);
                }
            }
        });
        return row;
    }
}
