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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 결과 뷰어 위의 필터 줄: WHERE 조건 입력과 기록(SWT {@code ResultSetFilterPanel}).
 *
 * 설계: 입력란은 편집할 수 있는 콤보다. Enter나 적용 단추로 조건을 적용하고, 지우기 단추는 조건을 비워 적용한다.
 * 기록은 SWT 결과 뷰어의 기본 필터 관리자({@code ResultSetViewer} 안의 {@code filterHistory})처럼 쿼리 글자마다 메모리에 둔다
 * (앱을 끝내면 사라진다). 적용한 조건이 맨 앞에 오고 {@link #MAX_HISTORY}개까지 남긴다.
 * 쿼리를 다시 읽을 수 없는 결과는 입력란을 끄고 이유를 도구 설명으로 보인다. 오른쪽 끝에는 결과 뷰어가 단추(값 패널, 레코드)를 더한다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-result-viewer.md "정렬과 필터")
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 밀도 B: 치수를 테마의 Chromite.size.* 키({@link UiSizes})에서 읽음 (docs/chromite/design-spec.md "치수 토큰")
 */
final class ResultSetFilterBar extends JPanel {

    private static final int MAX_HISTORY = 20;
    private static final String HINT = EditorMessages.editor_filter_hint;

    /**
     * Applied filters by query text, newest first (this session).
     */
    private static final Map<String, List<String>> history = new HashMap<>();

    interface Listener {
        void applyFilter(@NotNull String where);

        /**
         * The query of the result, the key of the filter history.
         */
        @Nullable
        String getQueryText();
    }

    private final Listener listener;
    private final JComboBox<String> combo = new JComboBox<>() {
        @Override
        public void updateUI() {
            super.updateUI();
            // A look and feel switch makes a new editor: it gets the code font (font.code) again
            if (getEditor() != null && getEditor().getEditorComponent() instanceof JComponent editor) {
                editor.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
                editor.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, HINT);
            }
        }
    };
    private final JButton applyButton;
    private final JButton clearButton;
    private final JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
    private boolean canFilter = true;
    private boolean reading;

    ResultSetFilterBar(@NotNull Listener listener) {
        super(new BorderLayout(4, 0));
        this.listener = listener;
        setBorder(UiSizes.createPadding(UiSizes.FILTER_BAR_PADDING));
        combo.setEditable(true);
        combo.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, HINT);
        combo.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        combo.setPrototypeDisplayValue("x"); //$NON-NLS-1$
        combo.addActionListener(e -> {
            if ("comboBoxEdited".equals(e.getActionCommand())) { //$NON-NLS-1$
                apply();
            }
        });
        combo.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                String text = getText();
                combo.removeAllItems();
                for (String item : getHistory()) {
                    combo.addItem(item);
                }
                setText(text);
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            }
        });

        applyButton = UiIcons.createActionButton(ResultSetViewerPanel.createAction(EditorMessages.editor_filter_apply, null, this::apply), "check"); //$NON-NLS-1$
        applyButton.setToolTipText(EditorMessages.editor_filter_apply_tip);
        clearButton = UiIcons.createActionButton(ResultSetViewerPanel.createAction(EditorMessages.editor_common_clear_filter, null, () -> {
            setText("");
            apply();
        }), "close"); //$NON-NLS-1$
        clearButton.setToolTipText(EditorMessages.editor_filter_clear_tip);

        JLabel icon = new JLabel(UiIcons.get("filter")); //$NON-NLS-1$
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        buttons.setOpaque(false);
        buttons.add(applyButton);
        buttons.add(clearButton);
        trailing.setOpaque(false);
        JPanel east = new JPanel(new BorderLayout());
        east.setOpaque(false);
        east.add(buttons, BorderLayout.WEST);
        east.add(trailing, BorderLayout.EAST);
        add(icon, BorderLayout.WEST);
        add(combo, BorderLayout.CENTER);
        add(east, BorderLayout.EAST);
    }

    /**
     * Adds a component at the right end (the viewer's toggles).
     */
    void addTrailing(@NotNull JComponent component) {
        trailing.add(component);
    }

    /**
     * Enables the filter input, or disables it with the reason as tool tip.
     */
    void setEnabled(boolean canFilter, @Nullable String reason) {
        this.canFilter = canFilter;
        String tip = canFilter ? null : NLS.bind(EditorMessages.editor_filter_not_available, CommonUtils.notEmpty(reason));
        combo.setToolTipText(tip);
        getEditorField().setToolTipText(tip);
        updateEnabled();
    }

    void setReading(boolean reading) {
        this.reading = reading;
        updateEnabled();
    }

    private void updateEnabled() {
        boolean enabled = canFilter && !reading;
        combo.setEnabled(enabled);
        applyButton.setEnabled(enabled);
        clearButton.setEnabled(enabled);
    }

    /**
     * Shows the conditions of the filter in effect.
     */
    void setFilterText(@NotNull String text) {
        setText(text);
    }

    private void apply() {
        if (!canFilter || reading) {
            return;
        }
        String where = getText().trim();
        if (!where.isEmpty()) {
            addHistory(where);
        }
        listener.applyFilter(where);
    }

    @NotNull
    private String getText() {
        return getEditorField().getText();
    }

    private void setText(@NotNull String text) {
        getEditorField().setText(text);
    }

    @NotNull
    private JTextComponent getEditorField() {
        return (JTextComponent) combo.getEditor().getEditorComponent();
    }

    @NotNull
    private List<String> getHistory() {
        return history.getOrDefault(CommonUtils.notEmpty(listener.getQueryText()).trim(), List.of());
    }

    private void addHistory(@NotNull String where) {
        List<String> items = history.computeIfAbsent(CommonUtils.notEmpty(listener.getQueryText()).trim(), k -> new ArrayList<>());
        items.remove(where);
        items.addFirst(where);
        while (items.size() > MAX_HISTORY) {
            items.removeLast();
        }
    }
}
