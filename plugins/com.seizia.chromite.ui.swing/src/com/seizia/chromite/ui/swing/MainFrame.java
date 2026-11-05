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
import java.awt.event.KeyEvent;

/**
 * Chromite Swing 메인 창.
 *
 * 설계: A단계에서는 모델 계층이 떴는지 확인할 수 있는 최소 화면만 둔다
 * (File > Exit 메뉴와 작업 공간 연결 수를 보여 주는 상태 표시줄). SSMS식 배치(Object Explorer, 쿼리 탭,
 * Results/Messages, Properties)는 B단계에서 가운데 영역에 채운다.
 * 창을 닫으면 dispose되고, {@link ChromiteSwingApplication}이 windowClosed 이벤트로 앱을 종료한다.
 * 창 아이콘은 Java 런타임(JBR) 기본 아이콘 대신 Chromite 아이콘을 쓴다({@link ChromiteIcons}).
 *
 * 변경 이력:
 * - 2026-11-06 A단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — A단계")
 */
final class MainFrame extends JFrame {

    MainFrame(@NotNull String title, @NotNull String statusText) {
        super(title);
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setJMenuBar(createMenuBar());

        JPanel content = new JPanel(new BorderLayout());
        content.add(new JPanel(), BorderLayout.CENTER);
        content.add(createStatusBar(statusText), BorderLayout.SOUTH);
        setContentPane(content);

        setSize(1200, 800);
        setLocationRelativeTo(null);
    }

    @NotNull
    private JMenuBar createMenuBar() {
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.setMnemonic(KeyEvent.VK_X);
        exitItem.addActionListener(e -> dispose());

        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);
        fileMenu.add(exitItem);

        JMenuBar menuBar = new JMenuBar();
        menuBar.add(fileMenu);
        return menuBar;
    }

    @NotNull
    private static JComponent createStatusBar(@NotNull String statusText) {
        JLabel statusLabel = new JLabel(statusText);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

        JPanel statusBar = new JPanel(new BorderLayout());
        Color separatorColor = UIManager.getColor("Separator.foreground");
        statusBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, separatorColor != null ? separatorColor : Color.GRAY));
        statusBar.add(statusLabel, BorderLayout.WEST);
        return statusBar;
    }
}
