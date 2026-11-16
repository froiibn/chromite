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
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.TextCompareMessages;
import com.seizia.chromite.utils.PrefUtils;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * 비교 문서 탭: 두 창의 글을 나란히 비교한다({@link TextCompareCommand}가 연다). 보기는 스키마 비교와 같은 {@link TextDiffView}다.
 *
 * 설계:
 * <ul>
 *     <li>사용자 결정(2026-11-12, 팝업을 본 뒤): "그냥 스키마 비교처럼 별도 탭으로". 그래서 {@link SchemaComparePanel}처럼 {@link ToolDocument}이고
 *     명령마다 새 탭이다(비교 여러 개를 나란히 둘 수 있다). 탭 제목은 "비교: 왼쪽 ↔ 오른쪽"(창의 파일·객체 이름), 도구 설명은 두 창의 탭 제목 전체다.
 *     다른 도구 문서처럼 다시 시작할 때 복원하지 않는다(창의 글을 그때 읽은 것이다).</li>
 *     <li>읽기 전용이다: 병합·편집·건너 복사 없음. 글은 창의 선택 영역이 있으면 그것, 없으면 전체이고({@link TextCompareCommand#read}), 머리글이
 *     선택 영역과 그 줄 범위를 말한다. 새로 고침은 두 창의 지금 글을 다시 읽는다. 닫힌 창은 마지막 글을 두고 머리글이 알린다.</li>
 *     <li>이전/다음 차이는 Alt+↑ / Alt+↓(WinMerge의 키)이고 이 탭 안에 초점이 있을 때만 걸린다(패널의 {@code WHEN_ANCESTOR_OF_FOCUSED_COMPONENT}).
 *     두 편집기는 같은 키를 줄 옮기기로 직접 묶고 있어(초점 컴포넌트의 키가 먼저) 편집기에도 같은 동작을 묶는다. 메인 창은 Alt+↑/↓를 묶지 않고
 *     (Query 메뉴는 Alt+PageUp/PageDown), F7은 Object Details라 쓰지 않았다. 끝에서 처음으로 돈다.</li>
 *     <li>옵션 셋(공백 무시, 대소문자 무시, 빈 줄 무시)은 Swing 번들의 설정 저장소({@code textCompare.*})에 기억한다. 줄 끝 문자와 마지막 줄 바꿈은
 *     늘 차이가 아니다({@link TextDiff}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성 (docs/chromite/text-compare.md)
 */
final class TextComparePanel extends JPanel implements ToolDocument {

    private static final String PREF_IGNORE_WHITESPACE = "textCompare.ignoreWhitespace"; //$NON-NLS-1$
    private static final String PREF_IGNORE_CASE = "textCompare.ignoreCase"; //$NON-NLS-1$
    private static final String PREF_IGNORE_BLANK_LINES = "textCompare.ignoreBlankLines"; //$NON-NLS-1$

    private static final KeyStroke NEXT_KEY = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK);
    private static final KeyStroke PREVIOUS_KEY = KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK);

    private static int documentCounter;

    /**
     * A side: the window, how it was described and the text read last.
     */
    private static final class Side {
        @NotNull
        private TextCompareCommand.Window window;
        @NotNull
        private TextCompareCommand.Snapshot snapshot;
        private boolean closed;

        Side(@NotNull TextCompareCommand.Window window) {
            this.window = window;
            this.snapshot = TextCompareCommand.read(window.getTextArea());
        }
    }

    private final JTabbedPane tabs;
    private final TextDiffView view = new TextDiffView();
    private final JCheckBox ignoreWhitespaceBox = new JCheckBox(TextCompareMessages.textcompare_ignore_whitespace);
    private final JCheckBox ignoreCaseBox = new JCheckBox(TextCompareMessages.textcompare_ignore_case);
    private final JCheckBox ignoreBlankBox = new JCheckBox(TextCompareMessages.textcompare_ignore_blank);
    private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$
    private final Action previousAction;
    private final Action nextAction;
    private Side left;
    private Side right;

    /**
     * Opens a new Compare tab for two windows (each command opens its own, as the schema compare does).
     *
     * @param tabs the main window's document tabs, where the windows are (Refresh finds them there again)
     */
    static void open(@NotNull ObjectExplorerPanel.QueryWindows host, @NotNull JTabbedPane tabs, @NotNull TextCompareCommand.Window leftWindow,
                     @NotNull TextCompareCommand.Window rightWindow) {
        host.openToolDocument("textCompare#" + (++documentCounter), () -> new TextComparePanel(tabs, leftWindow, rightWindow)); //$NON-NLS-1$
    }

    private TextComparePanel(@NotNull JTabbedPane tabs, @NotNull TextCompareCommand.Window leftWindow, @NotNull TextCompareCommand.Window rightWindow) {
        super(new BorderLayout());
        this.tabs = tabs;
        left = new Side(leftWindow);
        right = new Side(rightWindow);

        previousAction = createAction(TextCompareMessages.textcompare_previous, PREVIOUS_KEY, view::previousDifference);
        nextAction = createAction(TextCompareMessages.textcompare_next, NEXT_KEY, view::nextDifference);
        Action swapAction = createAction(TextCompareMessages.textcompare_swap, null, this::swap);
        Action refreshAction = createAction(TextCompareMessages.textcompare_refresh, null, this::refresh);
        refreshAction.putValue(Action.SHORT_DESCRIPTION, TextCompareMessages.textcompare_refresh_tip);

        DBPPreferenceStore preferences = getPreferences();
        ignoreWhitespaceBox.setSelected(preferences.getBoolean(PREF_IGNORE_WHITESPACE));
        ignoreWhitespaceBox.setToolTipText(TextCompareMessages.textcompare_ignore_whitespace_tip);
        ignoreCaseBox.setSelected(preferences.getBoolean(PREF_IGNORE_CASE));
        ignoreBlankBox.setSelected(preferences.getBoolean(PREF_IGNORE_BLANK_LINES));
        for (JCheckBox box : new JCheckBox[]{ignoreWhitespaceBox, ignoreCaseBox, ignoreBlankBox}) {
            box.setFocusable(false);
            box.addActionListener(e -> optionsChanged());
        }

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(8), UIScale.scale(4), UIScale.scale(8)));
        toolBar.add(createButton(previousAction));
        toolBar.add(createButton(nextAction));
        toolBar.addSeparator();
        toolBar.add(createButton(swapAction));
        toolBar.add(createButton(refreshAction));
        toolBar.addSeparator();
        toolBar.add(ignoreWhitespaceBox);
        toolBar.add(ignoreCaseBox);
        toolBar.add(ignoreBlankBox);
        toolBar.add(Box.createHorizontalGlue());
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(12), 0, UIScale.scale(4)));
        toolBar.add(statusLabel);

        add(toolBar, BorderLayout.NORTH);
        add(view, BorderLayout.CENTER);

        // Only while the focus is in this tab; the text areas bind Alt+arrows themselves (move line), so the keys go on them too
        bindKey(this, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, NEXT_KEY, nextAction);
        bindKey(this, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, PREVIOUS_KEY, previousAction);
        for (JComponent textArea : view.getTextAreas()) {
            bindKey(textArea, JComponent.WHEN_FOCUSED, NEXT_KEY, nextAction);
            bindKey(textArea, JComponent.WHEN_FOCUSED, PREVIOUS_KEY, previousAction);
        }

        showSides();
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }

    @NotNull
    private static Action createAction(@NotNull String name, @Nullable KeyStroke key, @NotNull Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
        if (key != null) {
            action.putValue(Action.SHORT_DESCRIPTION, NLS.bind(CoreMessages.common_tip_shortcut, name, UiKeys.getText(key)));
        }
        return action;
    }

    @NotNull
    private static JButton createButton(@NotNull Action action) {
        JButton button = new JButton(action);
        button.setFocusable(false);
        return button;
    }

    private static void bindKey(@NotNull JComponent component, int condition, @NotNull KeyStroke key, @NotNull Action action) {
        String actionKey = "chromite.textCompare." + action.getValue(Action.NAME); //$NON-NLS-1$
        component.getInputMap(condition).put(key, actionKey);
        component.getActionMap().put(actionKey, action);
    }

    //////////////////////////////////////////////////////////
    // Comparing

    private void refresh() {
        for (Side side : new Side[]{left, right}) {
            TextCompareCommand.Window window = TextCompareCommand.describe(tabs, side.window.component());
            if (window == null) {
                // Closed: keep the last text
                side.closed = true;
            } else {
                side.window = window;
                side.snapshot = TextCompareCommand.read(window.getTextArea());
            }
        }
        showSides();
    }

    private void swap() {
        Side side = left;
        left = right;
        right = side;
        showSides();
    }

    private void optionsChanged() {
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_IGNORE_WHITESPACE, ignoreWhitespaceBox.isSelected());
        preferences.setValue(PREF_IGNORE_CASE, ignoreCaseBox.isSelected());
        preferences.setValue(PREF_IGNORE_BLANK_LINES, ignoreBlankBox.isSelected());
        PrefUtils.savePreferenceStore(preferences);
        showSides();
    }

    private void showSides() {
        // The tab shows the title it had when it opened: Swap and Refresh can change it
        int index = tabs.indexOfComponent(this);
        if (index >= 0) {
            tabs.setTitleAt(index, getTitle());
            tabs.setToolTipTextAt(index, getToolTip());
        }
        setHeader(true, left);
        setHeader(false, right);
        statusLabel.setText(TextCompareMessages.textcompare_comparing);
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground"); //$NON-NLS-1$
        previousAction.setEnabled(false);
        nextAction.setEnabled(false);
        TextDiff.Options options = new TextDiff.Options(ignoreWhitespaceBox.isSelected() ? TextDiff.Whitespace.IGNORE : TextDiff.Whitespace.EXACT,
            ignoreCaseBox.isSelected(), ignoreBlankBox.isSelected());
        view.compare(left.snapshot.text(), right.snapshot.text(), left.window.connection(), right.window.connection(), "", options, //$NON-NLS-1$
            outcome -> showOutcome(outcome, options));
    }

    private void setHeader(boolean leftSide, @NotNull Side side) {
        String note = null;
        if (side.snapshot.isSelection()) {
            note = NLS.bind(TextCompareMessages.textcompare_selection, side.snapshot.firstLine(), side.snapshot.lastLine());
        }
        if (side.closed) {
            note = note == null ? TextCompareMessages.textcompare_closed : note + "; " + TextCompareMessages.textcompare_closed; //$NON-NLS-1$
        }
        TextCompareCommand.Window window = side.window;
        view.setHeader(leftSide, window.icon(), window.prefix(), window.connection(), window.suffix(), note);
    }

    private void showOutcome(@NotNull TextDiffView.Outcome outcome, @NotNull TextDiff.Options options) {
        String text;
        if (outcome.tooLarge()) {
            text = NLS.bind(TextCompareMessages.textcompare_too_large, TextDiff.MAX_LINES, TextDiff.MAX_CHARS);
        } else if (outcome.differences() == 0) {
            text = outcome.exactlyEqual() || !options.isAnySet() ? TextCompareMessages.textcompare_identical
                : TextCompareMessages.textcompare_identical_options;
        } else {
            text = outcome.differences() == 1 ? TextCompareMessages.textcompare_count_one
                : NLS.bind(TextCompareMessages.textcompare_count, outcome.differences());
        }
        statusLabel.setText(text);
        statusLabel.putClientProperty(FlatClientProperties.STYLE, outcome.tooLarge() ? "foreground: $Component.error.focusedBorderColor" : null); //$NON-NLS-1$
        previousAction.setEnabled(outcome.differences() > 0);
        nextAction.setEnabled(outcome.differences() > 0);
    }

    //////////////////////////////////////////////////////////
    // ToolDocument

    @NotNull
    @Override
    public String getTitle() {
        return NLS.bind(TextCompareMessages.textcompare_title, left.window.getName(), right.window.getName());
    }

    @NotNull
    @Override
    public String getToolTip() {
        return NLS.bind(TextCompareMessages.textcompare_title, left.window.getTitle(), right.window.getTitle());
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return UiIcons.get("compare"); //$NON-NLS-1$
    }

    @Override
    public void dispose() {
        view.dispose();
    }
}
