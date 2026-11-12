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
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.ui.swing.internal.CoreMessages;

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
 * - 2026-11-07 UI 글꼴 12: 글자 단추 여백 주석을 고침(12px 글자 줄 15는 아이콘보다 낮아 여백을 줄이지 않음)
 * - 2026-11-12 UI 글꼴 13(운영체제 글꼴): 글자 단추 여백 주석을 고침(글자 줄 18, 위 여백에서 2를 뺌) (앱 변경 #67)
 * - 2026-11-07 {@link #statusDot}: 아이콘 없는 상태 점(쿼리 상태 줄의 커밋 방식, 사용자 요청) (docs/chromite/swing-frontend.md "자동 커밋 상태")
 * - 2026-11-07 {@link Status#WARNING}: 쿼리 상태 줄의 열린 트랜잭션 점(status.warning) (docs/chromite/idle-transactions.md)
 * - 2026-11-08 촘촘한 모드: 글자 단추의 여백을 UI가 바뀔 때 다시 정함(실행 중 밀도 전환) (docs/chromite/design-spec.md "적용 현황 — 촘촘한 모드")
 * - 2026-11-08 {@link #useToolButtonMargin}: 글자 단추 여백을 토글 단추(주 도구 막대의 Chromite Agent, 앱 변경 #6)에도
 *   (docs/chromite/swing-frontend.md "도구 막대")
 * - 2026-11-09 {@link Status#QUIET}: 쿼리 상태 줄의 조회 트랜잭션 점(보조 글자 색, 주의가 아님) (docs/chromite/idle-transactions.md
 *   "조회만 한 트랜잭션 끝내기")
 * - 2026-11-11 아이콘 단추의 도구 설명이 동작의 단축키를 함께 보임({@link ShortcutTipButton}, 앱 변경 #50: 단축키가 있는 줄 몰랐다)
 *   (docs/chromite/swing-sql-editor-session-ui.md "단축키를 도구 설명에")
 * - 2026-11-12 아이콘 단추의 도구 설명은 설명문이 아니라 기능 이름(동작의 이름, 끝의 "…" 뺌)과 단축키다. 설명문은 같은 동작의 메뉴 항목이
 *   쓴다. 꺼진 이유·상태를 말하는 단추는 {@link #DESCRIPTION_TIP}, 패널이 키를 묶은 단추는 {@link #TIP_SHORTCUT} (앱 변경 #103,
 *   docs/chromite/swing-frontend.md "아이콘 단추의 도구 설명")
 */
final class UiIcons {

    private static final Log log = Log.getLog(UiIcons.class);

    static final int SIZE = 16;
    private static final float DISABLED_ALPHA = 0.4f;
    private static final int DOT_SIZE = 6;

    /**
     * State color of {@link #withStatusDot} (connection state) and {@link #statusDot}.
     */
    enum Status {
        CONNECTED("Chromite.status.success"), //$NON-NLS-1$
        CONNECTING("Chromite.status.warning"), //$NON-NLS-1$
        ERROR("Chromite.status.error"), //$NON-NLS-1$
        // Attention without an error (an open transaction)
        WARNING("Chromite.status.warning"), //$NON-NLS-1$
        // A state to know about, not to act on (a read-only transaction that only holds read locks or a snapshot)
        QUIET("Chromite.secondaryForeground"); //$NON-NLS-1$

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
     * A state dot without an icon (the query status line's commit mode: success color for auto-commit, error color
     * for manual commit).
     */
    @NotNull
    static Icon statusDot(@NotNull Status status) {
        return new DotIcon(status);
    }

    /**
     * Client property of an icon button ({@link #createActionButton}, value {@link Boolean#TRUE}): its tool tip is the action's
     * description (or a text set on the button), not the action's name, because it says what the name can't: why the action is
     * disabled, the state it acts in. Without a description the name is shown.
     */
    static final String DESCRIPTION_TIP = "chromite.descriptionTip"; //$NON-NLS-1$

    /**
     * Client property of an icon button ({@link #createActionButton}): the {@link KeyStroke} its tool tip names when the action
     * has no accelerator of its own, because the panel binds the key (F5 of a tool window's tree).
     */
    static final String TIP_SHORTCUT = "chromite.tipShortcut"; //$NON-NLS-1$

    /**
     * Icon-only tool bar button (size.actionButton: 16 + Button.toolbar.margin, radius.action 6, hover background).
     * The tool tip is the function's name, the action's name, with its shortcut: "Save (Ctrl+S)" (user request, app change
     * #103: a name, not a description). The action's description is written for the menu items that share the action; see
     * {@link #DESCRIPTION_TIP} and {@link #TIP_SHORTCUT} for the exceptions.
     */
    @NotNull
    static JButton createActionButton(@NotNull Action action, @NotNull String iconId) {
        JButton button = createButton(action, iconId, true);
        button.setHideActionText(true);
        if (action.getValue(Action.SHORT_DESCRIPTION) == null) {
            button.setToolTipText(String.valueOf(action.getValue(Action.NAME)));
        }
        return button;
    }

    /**
     * An icon button ({@link #createActionButton(Action, String)}) whose tool tip names a key that the panel binds to the
     * action, not the action's accelerator ({@link #TIP_SHORTCUT}).
     */
    @NotNull
    static JButton createActionButton(@NotNull Action action, @NotNull String iconId, @NotNull KeyStroke shortcut) {
        JButton button = createActionButton(action, iconId);
        button.putClientProperty(TIP_SHORTCUT, shortcut);
        return button;
    }

    /**
     * Tool bar button with icon and text (New Query, Execute), as high as the icon-only buttons.
     */
    @NotNull
    static JButton createToolButton(@NotNull Action action, @NotNull String iconId) {
        JButton button = createButton(action, iconId, false);
        useToolButtonMargin(button);
        return button;
    }

    /**
     * The margin of a tool bar button with icon and text ({@link #createToolButton}) for another button with both, such
     * as a toggle button (the Chromite Agent button of the main tool bar).
     */
    static void useToolButtonMargin(@NotNull AbstractButton button) {
        setToolButtonMargin(button);
        // The explicit margin is kept by a new UI: set it again from the new Button.toolbar.margin and font (theme and
        // density changes, UiDensity)
        button.addPropertyChangeListener("UI", e -> setToolButtonMargin(button)); //$NON-NLS-1$
    }

    private static void setToolButtonMargin(@NotNull AbstractButton button) {
        // A text line higher than the icon (16) would make the button and its tool bar higher than size.actionButton:
        // take the difference off the top margin (the margin is unscaled, like the theme value). The 13px UI font's line
        // (18, Segoe UI) takes 2 off
        Insets margin = UIManager.getInsets("Button.toolbar.margin"); //$NON-NLS-1$
        if (margin != null) {
            int excess = Math.max(0, UIScale.unscale(button.getFontMetrics(button.getFont()).getHeight()) - SIZE);
            button.setMargin(new Insets(Math.max(0, margin.top - excess), margin.left, margin.bottom, margin.right));
        }
    }

    @NotNull
    private static JButton createButton(@NotNull Action action, @NotNull String iconId, boolean nameTip) {
        JButton button = new ShortcutTipButton(action, nameTip);
        button.setIcon(get(iconId));
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.putClientProperty(FlatClientProperties.STYLE, "arc: 6"); //$NON-NLS-1$
        button.setFocusable(false);
        return button;
    }

    /**
     * A button whose tool tip names the shortcut of its action ({@link Action#ACCELERATOR_KEY}, else {@link #TIP_SHORTCUT}),
     * "Hide Results (Ctrl+R)", unless the tool tip already does ({@link UiKeys#formatDescription}). Read when the tool tip
     * shows, so a description the action changes later (enabled or not, the active window) keeps the shortcut too. An icon
     * button ({@code nameTip}) shows the action's name in place of its description, unless {@link #DESCRIPTION_TIP} is set.
     */
    private static final class ShortcutTipButton extends JButton {
        private final boolean nameTip;

        ShortcutTipButton(@NotNull Action action, boolean nameTip) {
            super(action);
            this.nameTip = nameTip;
        }

        @Override
        public void setToolTipText(String text) {
            // An action whose description becomes null (it says only why it is disabled) would unregister the button from
            // the tool tip manager: keep the name. False while the superclass constructor sets the first tool tip
            Action action = getAction();
            super.setToolTipText(text == null && nameTip && action != null ? getTipName(action) : text);
        }

        @Override
        public String getToolTipText() {
            String tip = super.getToolTipText();
            Action action = getAction();
            if (action == null) {
                return tip;
            }
            if (nameTip && !Boolean.TRUE.equals(getClientProperty(DESCRIPTION_TIP))) {
                tip = getTipName(action);
            }
            KeyStroke key = action.getValue(Action.ACCELERATOR_KEY) instanceof KeyStroke accelerator ? accelerator
                : getClientProperty(TIP_SHORTCUT) instanceof KeyStroke shortcut ? shortcut : null;
            return tip == null || key == null ? tip : withShortcut(tip, key);
        }
    }

    /**
     * The name an icon button's tool tip shows: the action's name, which its menu item shows too, without the "…" that marks
     * a menu item opening a dialog ("New Connection…" is "New Connection").
     */
    @NotNull
    static String getTipName(@NotNull Action action) {
        String name = String.valueOf(action.getValue(Action.NAME));
        if (name.endsWith("…")) { //$NON-NLS-1$
            return name.substring(0, name.length() - 1);
        }
        return name.endsWith("...") ? name.substring(0, name.length() - 3) : name; //$NON-NLS-1$
    }

    /**
     * A tool tip with the shortcut text after it ("Hide Results (Ctrl+R)", "⌘R" on macOS), unless it already names it.
     * An HTML tool tip gets it before its end tag.
     */
    @NotNull
    static String withShortcut(@NotNull String tip, @NotNull KeyStroke key) {
        String keyText = UiKeys.getText(key);
        if (tip.contains(keyText)) {
            return tip;
        }
        String end = "</html>"; //$NON-NLS-1$
        int endIndex = tip.regionMatches(true, 0, "<html>", 0, 6) ? tip.toLowerCase().lastIndexOf(end) : -1; //$NON-NLS-1$
        if (endIndex >= 0) {
            return tip.substring(0, endIndex) + NLS.bind(CoreMessages.common_tip_shortcut, "", keyText) + tip.substring(endIndex); //$NON-NLS-1$
        }
        return NLS.bind(CoreMessages.common_tip_shortcut, tip, keyText);
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
     * A state dot alone (size.statusDot 6), in the state's theme color read when painting.
     */
    private record DotIcon(@NotNull Status status) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color color = UIManager.getColor(status.colorKey);
                g2.setColor(color != null ? color : Color.GRAY);
                g2.fill(new Ellipse2D.Double(x, y, DOT_SIZE, DOT_SIZE));
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return DOT_SIZE;
        }

        @Override
        public int getIconHeight() {
            return DOT_SIZE;
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
