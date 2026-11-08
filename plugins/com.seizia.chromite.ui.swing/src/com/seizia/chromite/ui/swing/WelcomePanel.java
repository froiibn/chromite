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
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.UIScale;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.FloatSize;
import com.github.weisj.jsvg.view.ViewBox;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.net.URL;
import java.util.List;

/**
 * 문서가 하나도 열려 있지 않을 때 문서 영역에 보이는 빈 상태(IDE의 빈 편집기 화면): 가로 로고, 안내 두 줄, 단축키 몇 개.
 *
 * 설계:
 * <ul>
 *     <li>예전의 닫을 수 없는 "Welcome" 탭 대신 문서 영역 자체가 이 화면을 보인다(사용자 결정 2026-11-06). 탭이 아니라서 닫을 것도,
 *     Window 메뉴에 나올 것도 없다. 첫 문서가 열리면 사라지고 마지막 문서를 닫으면 돌아온다({@link MainFrame}의 카드 전환).</li>
 *     <li>로고는 {@code tools/branding/gen_app_icons.py}가 만드는 가로 잠금 로고 SVG({@code icons/brand/lockup-light.svg}, {@code lockup-dark.svg})를
 *     JSVG로 그릴 때마다 벡터로 그린다(화면 배율 150%에서도 선명). 라이트 테마는 남색 글자, 다크 테마는 흰 글자·회색 부제이고,
 *     그릴 때 현재 테마를 보므로 테마를 바꾸면 바로 바뀐다. 바탕 상자는 없고 편집기 바탕 위에 그린다.</li>
 *     <li>크기: 최대 너비 {@value #MAX_LOGO_WIDTH}(논리 픽셀), 문서 영역이 좁으면 양쪽 여백을 남기고 줄인다. 크기가 바뀌면 다시 배치한다.</li>
 *     <li>안내 문구와 단축키는 보조 글자색({@code Chromite.secondaryForeground} = text.secondary), 작은 글꼴이다. 단축키는 메뉴와 같은
 *     {@link Action}의 이름과 단축키에서 만든다(메뉴의 단축키가 바뀌면 함께 바뀐다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: Welcome 탭을 빈 문서 영역으로 바꿈 (docs/chromite/swing-frontend.md "빈 문서 영역")
 * - 2026-11-07 단축키 글자({@code getKeyText})를 SQL Editor 도구 막대의 Commit / Roll Back 도구 설명도 씀 (docs/chromite/transactions.md)
 * - 2026-11-09 단축키 글자는 {@link UiKeys#getText}(macOS 기호 ⌘⇧)로 옮기고 {@code getKeyText}는 지움 (docs/chromite/shortcuts.md)
 */
final class WelcomePanel extends JPanel {

    private static final Log log = Log.getLog(WelcomePanel.class);

    private static final int MAX_LOGO_WIDTH = 420;
    private static final int SIDE_MARGIN = 32;

    /**
     * @param hints     lines of help text below the logo
     * @param shortcuts actions whose name and accelerator are listed below the help text
     */
    WelcomePanel(@NotNull List<String> hints, @NotNull List<Action> shortcuts) {
        super(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.insets = new Insets(0, 0, UIScale.scale(24), 0);
        add(new LockupView(), constraints);
        constraints.insets = new Insets(0, 0, UIScale.scale(4), 0);
        for (String hint : hints) {
            add(createSecondaryLabel(hint), constraints);
        }
        // Shortcut table (name | key) of its own, centered as a whole below the help text
        JPanel shortcutTable = new JPanel(new GridBagLayout());
        shortcutTable.setOpaque(false);
        GridBagConstraints cell = new GridBagConstraints();
        for (Action action : shortcuts) {
            KeyStroke accelerator = (KeyStroke) action.getValue(Action.ACCELERATOR_KEY);
            if (accelerator == null) {
                continue;
            }
            cell.gridy++;
            cell.gridx = 0;
            cell.anchor = GridBagConstraints.LINE_END;
            cell.insets = new Insets(UIScale.scale(4), 0, 0, UIScale.scale(16));
            shortcutTable.add(createSecondaryLabel(getActionName(action)), cell);
            cell.gridx = 1;
            cell.anchor = GridBagConstraints.LINE_START;
            cell.insets = new Insets(UIScale.scale(4), 0, 0, 0);
            JLabel keyLabel = new JLabel(UiKeys.getText(accelerator));
            keyLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
            shortcutTable.add(keyLabel, cell);
        }
        constraints.insets = new Insets(UIScale.scale(16), 0, 0, 0);
        add(shortcutTable, constraints);
        // The logo width follows the panel width
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                revalidate();
            }
        });
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // The editor background, like the document tabs it stands in for
        Color background = UIManager.getColor("Chromite.editorBackground"); //$NON-NLS-1$
        if (background != null) {
            setBackground(background);
        }
    }

    @NotNull
    private static JLabel createSecondaryLabel(@NotNull String text) {
        JLabel label = new JLabel(text);
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
        label.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        return label;
    }

    /**
     * Menu name without the trailing ellipsis (the hint is not a command that opens a dialog).
     */
    @NotNull
    private static String getActionName(@NotNull Action action) {
        String name = String.valueOf(action.getValue(Action.NAME));
        return name.endsWith("…") ? name.substring(0, name.length() - 1) : name; //$NON-NLS-1$
    }

    /**
     * The horizontal lockup of the current theme, drawn as a vector at the component size.
     */
    private static final class LockupView extends JComponent {
        @Nullable
        private static SVGDocument lightDocument;
        @Nullable
        private static SVGDocument darkDocument;
        private static boolean loaded;

        @Override
        public Dimension getPreferredSize() {
            SVGDocument document = getDocument();
            if (document == null) {
                return new Dimension(0, 0);
            }
            FloatSize size = document.size();
            int available = getParent() != null && getParent().getWidth() > 0
                ? getParent().getWidth() - 2 * UIScale.scale(SIDE_MARGIN) : Integer.MAX_VALUE;
            int width = Math.max(UIScale.scale(80), Math.min(UIScale.scale(MAX_LOGO_WIDTH), available));
            return new Dimension(width, Math.round(width * size.height / size.width));
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            SVGDocument document = getDocument();
            if (document == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                document.render(this, g2, new ViewBox(0, 0, getWidth(), getHeight()));
            } finally {
                g2.dispose();
            }
        }

        @Nullable
        private static SVGDocument getDocument() {
            if (!loaded) {
                loaded = true;
                lightDocument = load("lockup-light.svg"); //$NON-NLS-1$
                darkDocument = load("lockup-dark.svg"); //$NON-NLS-1$
            }
            return FlatLaf.isLafDark() ? darkDocument : lightDocument;
        }

        @Nullable
        private static SVGDocument load(@NotNull String name) {
            String path = "/icons/brand/" + name; //$NON-NLS-1$
            URL url = WelcomePanel.class.getResource(path);
            if (url == null) {
                log.warn("Logo not found: " + path); //$NON-NLS-1$
                return null;
            }
            try {
                SVGDocument document = new SVGLoader().load(url);
                if (document == null) {
                    log.warn("Can't parse logo " + path); //$NON-NLS-1$
                }
                return document;
            } catch (Exception e) {
                log.warn("Error loading logo " + path, e); //$NON-NLS-1$
                return null;
            }
        }
    }
}
