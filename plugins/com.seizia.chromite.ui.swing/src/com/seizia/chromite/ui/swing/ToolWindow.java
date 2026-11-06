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
import com.formdev.flatlaf.util.UIScale;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

/**
 * 메인 창 가장자리의 도구 창(Object Explorer, Properties, Results)과 그 크기·표시 여부 저장.
 *
 * 설계: 도구 창마다 {@link JSplitPane} 하나의 한쪽(앞쪽 = 왼쪽/위, 뒤쪽 = 오른쪽/아래)을 차지한다.
 * 도킹 라이브러리 대신 분할 창을 쓴 이유는 docs/chromite/swing-frontend.md "구현 현황 — B단계"에 적었다.
 * <ul>
 *     <li>숨길 때는 컴포넌트를 분할 창에서 빼지 않고 {@code setVisible(false)}로 감춘다. 그래야 테마 전환
 *     ({@code FlatLaf.updateUI()})이 숨은 창에도 적용된다. {@link JSplitPane}은 숨은 쪽에 공간을 주지 않지만
 *     구분선은 남기므로, 숨기는 동안 구분선 크기를 0으로 둔다.</li>
 *     <li>저장하는 값은 구분선 위치가 아니라 도구 창의 크기(가로 분할은 너비, 세로 분할은 높이)다. 창 크기가 바뀌어도
 *     도구 창 크기가 유지되도록 뒤쪽 도구 창의 분할 창은 늘어난 공간을 앞쪽(문서 영역)에 준다({@code resizeWeight = 1}).</li>
 *     <li>저장 위치는 테마와 같은 이 번들의 설정 저장소다({@link ChromiteTheme} 참고).</li>
 *     <li>머리말(DataGrip New UI): 높이 40 + 아래 1px 선, semibold 제목(왼쪽 12), 오른쪽에 숨기기(—) 아이콘 단추. 도구 창의 도구 막대는 내용 쪽에 둔다(Object Explorer, SSMS처럼 머리말 아래).</li>
 *     <li>도구 창은 머리말과 내용을 함께 Islands 카드({@link IslandPanel})에 담아 분할 창에 넣는다(숨기기·크기 저장은 카드 단위).</li>
 *     <li>두 도구 창이 한 세로 열을 나눠 쓸 수 있다({@link #stackBelow}: SSMS처럼 Registered Servers가 Object Explorer 위).
 *     아래 창이 열의 너비를, 위 창이 열 안의 높이를 저장한다. 둘 다 숨기면 열 전체를 숨기고, 하나만 보이면 그 창이 열을 채운다.
 *     도킹 라이브러리 없이 지금의 분할 창 배치와 Reset Window Layout을 그대로 쓰려고 이렇게 했다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 디자인 적용: 머리말 높이 40, 아이콘 숨기기 단추, 쓰지 않게 된 머리말 도구 인자 삭제 (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 Islands: 도구 창을 둥근 카드({@link IslandPanel})로 감쌈 (docs/chromite/design-spec.md "Islands 적용")
 * - 2026-11-06 P1: 기본 크기로 되돌리기(Window &gt; Reset Window Layout) (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 Registered Servers: 한 열에 두 도구 창 쌓기 (docs/chromite/swing-registered-servers.md)
 */
final class ToolWindow {

    private static final String PREF_VISIBLE_SUFFIX = ".visible"; //$NON-NLS-1$
    private static final String PREF_SIZE_SUFFIX = ".size"; //$NON-NLS-1$
    private static final int MIN_SIZE = 50;

    private final String id;
    private final JSplitPane splitPane;
    private final boolean leading;
    private final JComponent component;
    private final Action toggleAction;
    private final int defaultSize;
    private int size;
    /**
     * The tool window above this one in a shared column ({@link #stackBelow}); this one then owns the column's size.
     */
    @Nullable
    private ToolWindow upper;
    /**
     * The tool window below this one in a shared column; this one is sized within the column (its height).
     */
    @Nullable
    private ToolWindow lower;

    /**
     * @param id          preference key prefix
     * @param title       header title and View menu label
     * @param showHeader  {@code false} for content with its own header (it can embed {@link #createHideButton()})
     * @param content     tool window content
     * @param splitPane   split pane that hosts the tool window
     * @param leading     {@code true} for the left/top side of the split pane, {@code false} for the right/bottom side
     * @param defaultSize width or height used until the user resizes the tool window
     * @param accelerator shortcut of the View menu toggle
     */
    ToolWindow(
        @NotNull String id,
        @NotNull String title,
        boolean showHeader,
        @NotNull JComponent content,
        @NotNull JSplitPane splitPane,
        boolean leading,
        int defaultSize,
        @Nullable KeyStroke accelerator
    ) {
        this.id = id;
        this.splitPane = splitPane;
        this.leading = leading;
        this.defaultSize = defaultSize;
        this.size = defaultSize;
        this.toggleAction = new AbstractAction(title) {
            @Override
            public void actionPerformed(ActionEvent e) {
                setShown(Boolean.TRUE.equals(getValue(SELECTED_KEY)));
            }
        };
        toggleAction.putValue(Action.SELECTED_KEY, Boolean.TRUE);
        toggleAction.putValue(Action.ACCELERATOR_KEY, accelerator);

        // Islands: every tool window is a rounded card on the main window canvas
        if (!showHeader) {
            component = new IslandPanel(content);
        } else {
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(createHeader(title), BorderLayout.NORTH);
            panel.add(content, BorderLayout.CENTER);
            component = new IslandPanel(panel);
        }
        if (leading) {
            splitPane.setLeftComponent(component);
        } else {
            splitPane.setRightComponent(component);
            // Extra space goes to the document area, so the tool window keeps its size when the main window grows
            splitPane.setResizeWeight(1.0);
        }
    }

    /**
     * Check box action for the View menu; also used by the hide button.
     */
    @NotNull
    Action getToggleAction() {
        return toggleAction;
    }

    /**
     * The "hide" icon button (—) for the tool window header or for content with its own header.
     */
    @NotNull
    JButton createHideButton() {
        Action hideAction = new AbstractAction("Hide " + toggleAction.getValue(Action.NAME)) {
            @Override
            public void actionPerformed(ActionEvent e) {
                setShown(false);
            }
        };
        return UiIcons.createActionButton(hideAction, "hide"); //$NON-NLS-1$
    }

    /**
     * Stacks another tool window above this one in one column (Registered Servers above Object Explorer). The upper
     * tool window must have been created as the leading side of a vertical split pane (the column); this tool window's
     * card moves to the bottom of the column and the column takes its place. This tool window keeps the column's size
     * (width), the upper one its height in the column; the column is hidden only when both are.
     */
    void stackBelow(@NotNull ToolWindow upper) {
        JSplitPane column = upper.splitPane;
        // Adding the card to the column removes it from this split pane
        column.setRightComponent(component);
        if (leading) {
            splitPane.setLeftComponent(column);
        } else {
            splitPane.setRightComponent(column);
        }
        this.upper = upper;
        upper.lower = this;
        updateDividers();
    }

    boolean isShown() {
        return component.isVisible();
    }

    /**
     * Shows or hides the tool window. A shown tool window gets its last size back.
     */
    void setShown(boolean shown) {
        toggleAction.putValue(Action.SELECTED_KEY, shown);
        if (shown == component.isVisible()) {
            return;
        }
        if (shown) {
            boolean columnHidden = lower != null && !lower.getSlot().isVisible();
            component.setVisible(true);
            updateDividers();
            if (columnHidden) {
                // The column comes back with the lower tool window's width
                lower.applySize();
            }
            applySize();
        } else {
            size = getCurrentSize();
            component.setVisible(false);
            updateDividers();
        }
        splitPane.revalidate();
    }

    /**
     * What this tool window occupies in its split pane: its card, or the column it shares with an upper tool window.
     */
    @NotNull
    private JComponent getSlot() {
        return upper != null ? upper.splitPane : component;
    }

    /**
     * Divider sizes (0 next to a hidden side) and, for a shared column, whether the column is shown at all.
     */
    private void updateDividers() {
        int dividerSize = UIManager.getInt("SplitPane.dividerSize"); //$NON-NLS-1$
        ToolWindow columnOwner = lower != null ? lower : this;
        if (columnOwner.upper == null) {
            splitPane.setDividerSize(component.isVisible() ? dividerSize : 0);
            return;
        }
        boolean upperShown = columnOwner.upper.component.isVisible();
        boolean lowerShown = columnOwner.component.isVisible();
        JSplitPane column = columnOwner.upper.splitPane;
        column.setDividerSize(upperShown && lowerShown ? dividerSize : 0);
        column.setVisible(upperShown || lowerShown);
        columnOwner.splitPane.setDividerSize(upperShown || lowerShown ? dividerSize : 0);
    }

    /**
     * Window &gt; Reset Window Layout: shown, with the default size. Call for the outer split pane first, as
     * {@link #applySize()} lays it out for the inner ones.
     */
    void reset() {
        size = defaultSize;
        setShown(true);
        applySize();
    }

    /**
     * Reads visibility and size from the preferences. Call before the main window is shown;
     * the size is applied by {@link #applySize()} once the split pane has its real size.
     */
    void load(@NotNull DBPPreferenceStore preferences) {
        // The store drops values equal to their default (BundlePreferenceStore.putBoolean/putInt), so a hidden
        // tool window is only remembered if "shown" is the declared default
        preferences.setDefault(id + PREF_VISIBLE_SUFFIX, true);
        preferences.setDefault(id + PREF_SIZE_SUFFIX, size);
        size = Math.max(MIN_SIZE, preferences.getInt(id + PREF_SIZE_SUFFIX));
        setShown(preferences.getBoolean(id + PREF_VISIBLE_SUFFIX));
    }

    void save(@NotNull DBPPreferenceStore preferences) {
        if (getSlot().isVisible()) {
            size = getCurrentSize();
        }
        preferences.setValue(id + PREF_VISIBLE_SUFFIX, component.isVisible());
        preferences.setValue(id + PREF_SIZE_SUFFIX, size);
    }

    /**
     * Moves the divider so that the tool window has its remembered size, and lays out the split pane at once
     * (nested split panes are sized from the outer one, so they must see its new layout).
     */
    void applySize() {
        if (!getSlot().isVisible()) {
            return;
        }
        int total = isHorizontal() ? splitPane.getWidth() : splitPane.getHeight();
        if (total <= 0) {
            return;
        }
        int location = leading ? size : total - splitPane.getDividerSize() - size;
        splitPane.setDividerLocation(Math.max(MIN_SIZE, Math.min(location, total - MIN_SIZE)));
        splitPane.validate();
    }

    private int getCurrentSize() {
        if (lower != null && !lower.component.isVisible()) {
            // Alone in the column it fills it; that height is not its own size
            return size;
        }
        JComponent slot = getSlot();
        int current = isHorizontal() ? slot.getWidth() : slot.getHeight();
        return current >= MIN_SIZE ? current : size;
    }

    private boolean isHorizontal() {
        return splitPane.getOrientation() == JSplitPane.HORIZONTAL_SPLIT;
    }

    @NotNull
    private JComponent createHeader(@NotNull String title) {
        JLabel titleLabel = new JLabel(title);
        titleLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$

        // Icon buttons are centered vertically in the header (GridBagLayout centers its row)
        JPanel tools = new JPanel(new GridBagLayout());
        tools.setOpaque(false);
        tools.add(createHideButton());

        JPanel header = new SeparatedPanel();
        header.setLayout(new BorderLayout(UIScale.scale(8), 0));
        header.add(titleLabel, BorderLayout.CENTER);
        header.add(tools, BorderLayout.EAST);
        return header;
    }

    /**
     * Header (size.header.toolWindow: 40 + a 1px border.subtle line below, title 12px from the left). The border is
     * recreated on theme changes, because its color comes from the look and feel.
     */
    private static final class SeparatedPanel extends JPanel {
        private static final int HEIGHT = 40;

        @Override
        public void updateUI() {
            super.updateUI();
            Color separatorColor = UIManager.getColor("Separator.foreground"); //$NON-NLS-1$
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, separatorColor != null ? separatorColor : Color.GRAY),
                BorderFactory.createEmptyBorder(0, UIScale.scale(12), 0, UIScale.scale(8))));
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, UIScale.scale(HEIGHT) + 1);
        }
    }
}
