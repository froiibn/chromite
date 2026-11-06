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
import com.github.weisj.jsvg.view.ViewBox;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Chromite UI 아이콘(아이콘 팩의 16px SVG)과 아이콘 단추.
 *
 * 설계:
 * <ul>
 *     <li>아이콘 파일은 번들의 {@code icons/ui/<팩>/<light|dark>/<id>.svg}다. 생성기({@code tools/branding/gen_ui_icons.py})가 두 팩
 *     ({@link UiIconStyle})과 두 테마의 파일을 같은 모양, 다른 색으로 만든다. 색이 파일에 들어 있으므로 다시 칠하지 않는다.</li>
 *     <li>{@link #get}이 주는 아이콘은 그릴 때마다 현재 팩과 테마(어두운 Look and Feel인지)의 문서를 고른다. 그래서 테마나 팩을 바꾼 뒤
 *     다시 그리기만 하면 모든 단추·트리·탭의 아이콘이 바뀐다(컴포넌트마다 아이콘을 다시 넣지 않는다).</li>
 *     <li>SVG는 JSVG로 읽어 그릴 때마다 벡터로 그린다(화면 배율과 관계없이 선명, {@link NodeIcons}와 같은 방식). 읽은 문서는 캐시한다.
 *     배지 자리를 비우는 {@code <mask>}도 JSVG가 그대로 그린다(2026-11-06 확인).</li>
 *     <li>비활성 단추는 FlatLaf가 일반 {@link Icon}의 흐린 모양을 만들지 못하므로({@code FlatLaf.getDisabledIcon}은 {@link ImageIcon}만 처리)
 *     {@link FlatLaf.DisabledIconProvider}로 반투명하게 그린 아이콘을 준다.</li>
 *     <li>연결 상태 점({@link #withStatusDot})은 아이콘 오른쪽 아래에 지름 6의 점과 행 바탕색 1px 테두리를 그린다(공급자 로고는 다시 칠하지 않음).</li>
 * </ul>
 * EDT에서만 호출한다(캐시를 동기화하지 않는다).
 *
 * 변경 이력:
 * - 2026-11-06 디자인 적용: 최초 작성 (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-07 밀도 B: 글자 단추의 위 여백을 줄여 아이콘 단추와 높이를 맞춤 (docs/chromite/design-spec.md "치수 토큰")
 */
final class UiIcons {

    private static final Log log = Log.getLog(UiIcons.class);

    static final int SIZE = 16;
    private static final float DISABLED_ALPHA = 0.4f;
    private static final int DOT_SIZE = 6;

    /**
     * Connection state shown by {@link #withStatusDot}.
     */
    enum Status {
        CONNECTED("Chromite.status.success"), //$NON-NLS-1$
        CONNECTING("Chromite.status.warning"), //$NON-NLS-1$
        ERROR("Chromite.status.error"); //$NON-NLS-1$

        private final String colorKey;

        Status(@NotNull String colorKey) {
            this.colorKey = colorKey;
        }
    }

    private static final Map<String, Icon> icons = new HashMap<>();
    // Documents by "<pack>/<light|dark>/<id>"; files that can't be read are cached as null
    private static final Map<String, SVGDocument> documents = new HashMap<>();

    private UiIcons() {
    }

    /**
     * The icon with the given id (file name in the packs); it follows the current icon pack and theme.
     */
    @NotNull
    static Icon get(@NotNull String id) {
        return icons.computeIfAbsent(id, ThemedIcon::new);
    }

    /**
     * An icon with a connection state dot in its lower right corner.
     */
    @NotNull
    static Icon withStatusDot(@NotNull Icon icon, @NotNull Status status) {
        return new StatusDotIcon(icon, status);
    }

    /**
     * Icon-only tool bar button (size.actionButton: 16 + Button.toolbar.margin, radius.action 6, hover background).
     * The tool tip is the action's description (which names the shortcut), or its name.
     */
    @NotNull
    static JButton createActionButton(@NotNull Action action, @NotNull String iconId) {
        JButton button = createButton(action, iconId);
        button.setHideActionText(true);
        if (action.getValue(Action.SHORT_DESCRIPTION) == null) {
            button.setToolTipText(String.valueOf(action.getValue(Action.NAME)));
        }
        return button;
    }

    /**
     * Tool bar button with icon and text (New Query, Execute), as high as the icon-only buttons.
     */
    @NotNull
    static JButton createToolButton(@NotNull Action action, @NotNull String iconId) {
        JButton button = createButton(action, iconId);
        // The text line (17 for the 13px UI font) is higher than the icon (16): take the difference off the top margin,
        // or the button and its tool bar are 1px higher than size.actionButton (the margin is unscaled, like the theme value)
        Insets margin = UIManager.getInsets("Button.toolbar.margin"); //$NON-NLS-1$
        if (margin != null) {
            int excess = Math.max(0, UIScale.unscale(button.getFontMetrics(button.getFont()).getHeight()) - SIZE);
            button.setMargin(new Insets(Math.max(0, margin.top - excess), margin.left, margin.bottom, margin.right));
        }
        return button;
    }

    @NotNull
    private static JButton createButton(@NotNull Action action, @NotNull String iconId) {
        JButton button = new JButton(action);
        button.setIcon(get(iconId));
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.putClientProperty(FlatClientProperties.STYLE, "arc: 6"); //$NON-NLS-1$
        button.setFocusable(false);
        return button;
    }

    @Nullable
    private static SVGDocument getDocument(@NotNull String id) {
        String key = UiIconStyle.getCurrent().getFolder() + "/" + (FlatLaf.isLafDark() ? "dark" : "light") + "/" + id; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (documents.containsKey(key)) {
            return documents.get(key);
        }
        SVGDocument document = null;
        String path = "/icons/ui/" + key + ".svg"; //$NON-NLS-1$ //$NON-NLS-2$
        URL url = UiIcons.class.getResource(path);
        if (url == null) {
            log.warn("UI icon not found: " + path);
        } else {
            try {
                document = new SVGLoader().load(url);
                if (document == null) {
                    log.warn("Can't parse UI icon " + path);
                }
            } catch (Exception e) {
                log.warn("Error loading UI icon " + path, e);
            }
        }
        documents.put(key, document);
        return document;
    }

    private static void paintDocument(@Nullable SVGDocument document, @Nullable Component c, @NotNull Graphics g, int x, int y, float alpha) {
        if (document == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.translate(x, y);
            if (alpha < 1f) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            }
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            document.render(c, g2, new ViewBox(0, 0, SIZE, SIZE));
        } finally {
            g2.dispose();
        }
    }

    /**
     * Icon of the current pack and theme, looked up at paint time.
     */
    private static final class ThemedIcon implements Icon, FlatLaf.DisabledIconProvider {
        private final String id;
        private Icon disabledIcon;

        ThemedIcon(@NotNull String id) {
            this.id = id;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            paintDocument(getDocument(id), c, g, x, y, 1f);
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public Icon getDisabledIcon() {
            if (disabledIcon == null) {
                disabledIcon = new Icon() {
                    @Override
                    public void paintIcon(Component c, Graphics g, int x, int y) {
                        paintDocument(getDocument(id), c, g, x, y, DISABLED_ALPHA);
                    }

                    @Override
                    public int getIconWidth() {
                        return SIZE;
                    }

                    @Override
                    public int getIconHeight() {
                        return SIZE;
                    }
                };
            }
            return disabledIcon;
        }
    }

    /**
     * An icon with a state dot (size.statusDot 6) in its lower right corner, separated from the icon by a 1px ring
     * in the tree background color.
     */
    private record StatusDotIcon(@NotNull Icon icon, @NotNull Status status) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            icon.paintIcon(c, g, x, y);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double dotX = x + getIconWidth() - DOT_SIZE;
                double dotY = y + getIconHeight() - DOT_SIZE;
                Color ring = UIManager.getColor("Tree.background"); //$NON-NLS-1$
                if (ring != null) {
                    g2.setColor(ring);
                    g2.fill(new Ellipse2D.Double(dotX - 1, dotY - 1, DOT_SIZE + 2, DOT_SIZE + 2));
                }
                Color color = UIManager.getColor(status.colorKey);
                g2.setColor(color != null ? color : Color.GRAY);
                g2.fill(new Ellipse2D.Double(dotX, dotY, DOT_SIZE, DOT_SIZE));
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return icon.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return icon.getIconHeight();
        }
    }
}
