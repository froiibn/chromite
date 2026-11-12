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
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.TextCompareMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * "비교" 명령: 열린 창 두 개를 고르게 하고 그 글을 나란히 비교하는 문서 탭({@link TextComparePanel})을 연다. SQL Editor 도구 막대의 단추와
 * Query 메뉴 항목이 같은 동작을 쓴다.
 *
 * 설계:
 * <ul>
 *     <li>사용자 결정(2026-11-12): "diff는 버튼을 누르면 열려있는 창을 두개 선택하게 하고 선택된 창의 내용을 비교하는 팝업화면으로 만든다",
 *     팝업을 본 뒤 "스키마 비교처럼 별도 탭으로". 그래서 비교 대상은 열린 문서 탭의 글이고 결과는 새 문서 탭이다: 쿼리 창({@link SqlEditorPanel}, Modify·New 창과 Query Library 창도 쿼리 창이다)을 먼저,
 *     다음에 DDL 탭({@link DdlViewerPanel})을 탭 순서대로. 둘 다 같은 {@link SqlEditorPanel.SqlTextArea}로 글을 그대로 내준다.
 *     다른 문서 탭(비교 탭과 스키마 비교를 포함한 도구 화면, 디버거의 소스, 테이블 디자이너)은 글을 내주는 창이 아니라 넣지 않았다.</li>
 *     <li>고르기는 "왼쪽", "오른쪽" 콤보 두 개다. 목록 하나에 확인란 두 개보다 어느 쪽에 놓일지가 바로 보인다. 처음 값은 활성 창(왼쪽)과 그 전에
 *     활성이던 창(오른쪽)이다. 그래서 이 명령이 탭 선택을 따라간다. 창이 꼭 두 개여도 묻는다: 늘 같은 흐름이고 좌우를 고를 수 있다.</li>
 *     <li>목록의 행은 탭처럼 보인다: 탭 아이콘과 제목(고침 표시 {@code *} 포함), 제목 안의 연결 이름은 Object Explorer와 같은 연결 색 알약.</li>
 *     <li>켜짐: 비교할 창이 두 개 이상일 때. 꺼졌을 때 도구 설명은 이름과 다음 줄의 이유다(앱 변경 #103의 꺼진 이유 규칙).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성 (docs/chromite/text-compare.md)
 */
final class TextCompareCommand {

    /**
     * An open window that holds text, described as its tab shows it.
     *
     * @param prefix     the tab's title up to the connection's name (all of it when there is none)
     * @param connection the connection whose name follows the prefix in its colour pill, or null
     * @param suffix     the rest of the title (the modified mark)
     */
    record Window(@NotNull Component component, @Nullable Icon icon, @NotNull String prefix, @Nullable DBPDataSourceContainer connection,
                  @NotNull String suffix) {
        /**
         * The window's own name for the Compare tab's title: the file or object, without the connection and the modified mark.
         */
        @NotNull
        String getName() {
            String title = getTitle();
            if (component instanceof SqlEditorPanel) {
                // "SQLQuery1.sql - connection*", "SQLQuery1.sql - not connected*": the file name
                int at = title.lastIndexOf(" - "); //$NON-NLS-1$
                return at > 0 ? title.substring(0, at) : title;
            }
            // A DDL tab: its title, without the connection added after it
            return connection == null ? title : prefix.substring(0, prefix.length() - 3);
        }

        @NotNull
        String getTitle() {
            return prefix + (connection == null ? "" : connection.getName()) + suffix; //$NON-NLS-1$
        }

        @NotNull
        SqlEditorPanel.SqlTextArea getTextArea() {
            return Objects.requireNonNull(TextCompareCommand.getTextArea(component));
        }
    }

    /**
     * The text of a window: its selection when it has one, with the selection's first and last line (1-based), else all of it.
     */
    record Snapshot(@NotNull String text, int firstLine, int lastLine) {
        boolean isSelection() {
            return firstLine > 0;
        }
    }

    private final JFrame owner;
    private final ObjectExplorerPanel.QueryWindows host;
    private final JTabbedPane tabs;
    private final Action action;
    @Nullable
    private Component active;
    @Nullable
    private Component previous;

    /**
     * @param host the main window, which opens the Compare tab
     * @param tabs the main window's document tabs: the windows to choose from
     */
    TextCompareCommand(@NotNull JFrame owner, @NotNull ObjectExplorerPanel.QueryWindows host, @NotNull JTabbedPane tabs) {
        this.owner = owner;
        this.host = host;
        this.tabs = tabs;
        action = new AbstractAction(TextCompareMessages.textcompare_command) {
            @Override
            public void actionPerformed(ActionEvent e) {
                run();
            }
        };
        // The window active before the current one is the first choice for the right side
        tabs.addChangeListener(e -> {
            Component selected = tabs.getSelectedComponent();
            if (selected != active) {
                if (getTextArea(active) != null) {
                    previous = active;
                }
                active = selected;
            }
        });
        tabs.addContainerListener(new ContainerAdapter() {
            @Override
            public void componentAdded(ContainerEvent e) {
                update();
            }

            @Override
            public void componentRemoved(ContainerEvent e) {
                update();
            }
        });
        update();
    }

    /**
     * Compare (the SQL Editor tool bar button and the Query menu item).
     */
    @NotNull
    Action getAction() {
        return action;
    }

    private void update() {
        boolean enabled = listWindows(tabs).size() >= 2;
        action.setEnabled(enabled);
        // The reason on its own line below the name (app change #103)
        action.putValue(Action.SHORT_DESCRIPTION, enabled ? null
            : "<html>" + CommonUtils.escapeHtml(UiIcons.getTipName(action)) + "<br>" //$NON-NLS-1$ //$NON-NLS-2$
                + CommonUtils.escapeHtml(TextCompareMessages.textcompare_disabled) + "</html>"); //$NON-NLS-1$
    }

    private void run() {
        List<Window> windows = listWindows(tabs);
        if (windows.size() < 2) {
            return;
        }
        Window left = find(windows, active);
        if (left == null) {
            left = windows.getFirst();
        }
        Window right = find(windows, previous);
        if (right == null || right.component() == left.component()) {
            Window first = left;
            right = windows.stream().filter(w -> w.component() != first.component()).findFirst().orElseThrow();
        }
        Window[] chosen = ChooserDialog.choose(owner, windows, left, right);
        if (chosen != null) {
            TextComparePanel.open(host, tabs, chosen[0], chosen[1]);
        }
    }

    @Nullable
    private static Window find(@NotNull List<Window> windows, @Nullable Component component) {
        return windows.stream().filter(w -> w.component() == component).findFirst().orElse(null);
    }

    //////////////////////////////////////////////////////////
    // Windows

    /**
     * The text of a window that Compare reads, or null for a window without one.
     */
    @Nullable
    static SqlEditorPanel.SqlTextArea getTextArea(@Nullable Component component) {
        if (component instanceof SqlEditorPanel editor) {
            return editor.getTextArea();
        }
        if (component instanceof DdlViewerPanel viewer) {
            return viewer.getTextArea();
        }
        return null;
    }

    /**
     * The open windows with text: query windows, then DDL tabs, each in tab order.
     */
    @NotNull
    static List<Window> listWindows(@NotNull JTabbedPane tabs) {
        List<Window> queryWindows = new ArrayList<>();
        List<Window> others = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Component component = tabs.getComponentAt(i);
            if (component instanceof SqlEditorPanel) {
                queryWindows.add(describe(tabs, i));
            } else if (getTextArea(component) != null) {
                others.add(describe(tabs, i));
            }
        }
        queryWindows.addAll(others);
        return queryWindows;
    }

    /**
     * The window at its tab as the tab shows it now, or null when the window is no longer open.
     */
    @Nullable
    static Window describe(@NotNull JTabbedPane tabs, @NotNull Component component) {
        int index = tabs.indexOfComponent(component);
        return index < 0 ? null : describe(tabs, index);
    }

    @NotNull
    private static Window describe(@NotNull JTabbedPane tabs, int index) {
        Component component = tabs.getComponentAt(index);
        String title = tabs.getTitleAt(index);
        Icon icon = tabs.getIconAt(index);
        if (component instanceof SqlEditorPanel editor) {
            // "SQLQuery1.sql - connection*": the connection's name gets its pill
            DBPDataSourceContainer connection = editor.getDataSourceContainer();
            int at = connection == null ? -1 : title.lastIndexOf(" - " + connection.getName()); //$NON-NLS-1$
            if (connection != null && at >= 0) {
                int end = at + 3 + connection.getName().length();
                return new Window(component, icon, title.substring(0, at + 3), connection, title.substring(end));
            }
            return new Window(component, icon, title, null, ""); //$NON-NLS-1$
        }
        DBPDataSourceContainer connection = component instanceof DdlViewerPanel viewer ? viewer.getDataSourceContainer() : null;
        return connection == null ? new Window(component, icon, title, null, "") //$NON-NLS-1$
            : new Window(component, icon, title + " - ", connection, ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reads a window's text (EDT): its selection when there is one.
     */
    @NotNull
    static Snapshot read(@NotNull SqlEditorPanel.SqlTextArea textArea) {
        int start = textArea.getSelectionStart();
        int end = textArea.getSelectionEnd();
        if (start == end) {
            return new Snapshot(textArea.getText(), 0, 0);
        }
        try {
            int first = textArea.getLineOfOffset(start);
            // A selection that ends at the start of a line (whole lines selected) ends on the line before
            int last = textArea.getLineOfOffset(end);
            if (last > first && textArea.getLineStartOffset(last) == end) {
                last--;
            }
            return new Snapshot(textArea.getText(start, end - start), first + 1, last + 1);
        } catch (BadLocationException e) {
            return new Snapshot(textArea.getText(), 0, 0);
        }
    }

    //////////////////////////////////////////////////////////
    // Choosing two windows

    /**
     * Two combos, Left and Right, listing the windows; Compare is enabled with two different windows.
     */
    private static final class ChooserDialog extends JDialog {
        private final JComboBox<Window> leftCombo;
        private final JComboBox<Window> rightCombo;
        private final JButton okButton = new JButton(TextCompareMessages.textcompare_ok);
        private final JLabel hintLabel = new JLabel(TextCompareMessages.textcompare_choose_hint);
        @Nullable
        private Window[] chosen;

        private ChooserDialog(@NotNull JFrame owner, @NotNull List<Window> windows, @NotNull Window left, @NotNull Window right) {
            super(owner, TextCompareMessages.textcompare_choose_title, ModalityType.APPLICATION_MODAL);
            setIconImages(ChromiteIcons.getWindowIcons());
            Window[] items = windows.toArray(new Window[0]);
            leftCombo = createCombo(items, left);
            rightCombo = createCombo(items, right);

            JPanel form = new JPanel(new GridBagLayout());
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = 0;
            constraints.gridy = 0;
            constraints.gridwidth = 2;
            constraints.anchor = GridBagConstraints.WEST;
            constraints.insets = new Insets(0, 0, 10, 0);
            form.add(hintLabel, constraints);
            addRow(form, 1, TextCompareMessages.textcompare_left, leftCombo);
            addRow(form, 2, TextCompareMessages.textcompare_right, rightCombo);

            okButton.addActionListener(e -> {
                chosen = new Window[]{(Window) leftCombo.getSelectedItem(), (Window) rightCombo.getSelectedItem()};
                dispose();
            });
            JButton cancelButton = new JButton(CoreMessages.common_cancel);
            cancelButton.addActionListener(e -> dispose());
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            buttons.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
            buttons.add(okButton);
            buttons.add(cancelButton);

            JPanel content = new JPanel(new BorderLayout());
            content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            content.add(form, BorderLayout.CENTER);
            content.add(buttons, BorderLayout.SOUTH);
            setContentPane(content);
            getRootPane().setDefaultButton(okButton);
            getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
            leftCombo.addActionListener(e -> updateOk());
            rightCombo.addActionListener(e -> updateOk());
            updateOk();
            pack();
            setLocationRelativeTo(owner);
        }

        @Nullable
        static Window[] choose(@NotNull JFrame owner, @NotNull List<Window> windows, @NotNull Window left, @NotNull Window right) {
            ChooserDialog dialog = new ChooserDialog(owner, windows, left, right);
            dialog.setVisible(true);
            return dialog.chosen;
        }

        @NotNull
        private static JComboBox<Window> createCombo(@NotNull Window[] items, @NotNull Window selected) {
            JComboBox<Window> combo = new JComboBox<>(items);
            combo.setSelectedItem(selected);
            combo.setRenderer(new WindowRenderer());
            combo.setMaximumRowCount(16);
            return combo;
        }

        private static void addRow(@NotNull JPanel form, int row, @NotNull String label, @NotNull JComboBox<Window> combo) {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridy = row;
            constraints.anchor = GridBagConstraints.WEST;
            constraints.insets = new Insets(2, 0, 2, 8);
            constraints.gridx = 0;
            form.add(new JLabel(label), constraints);
            constraints.gridx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.weightx = 1;
            constraints.insets = new Insets(2, 0, 2, 0);
            form.add(combo, constraints);
        }

        private void updateOk() {
            boolean different = leftCombo.getSelectedItem() != rightCombo.getSelectedItem();
            okButton.setEnabled(different);
            hintLabel.setText(different ? TextCompareMessages.textcompare_choose_hint : TextCompareMessages.textcompare_same_window);
            hintLabel.putClientProperty(FlatClientProperties.STYLE, different ? null : "foreground: $Component.error.focusedBorderColor"); //$NON-NLS-1$
        }
    }

    /**
     * A window as its tab shows it: icon, title and the connection's name in its pill.
     */
    private static final class WindowRenderer extends JPanel implements ListCellRenderer<Window> {
        private final TextDiffView.HeaderLabel label = new TextDiffView.HeaderLabel();

        WindowRenderer() {
            super(new BorderLayout());
            add(label, BorderLayout.CENTER);
            setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Window> list, Window value, int index, boolean isSelected,
                                                      boolean cellHasFocus) {
            if (value == null) {
                label.set(null, " ", null, null, null); //$NON-NLS-1$
            } else {
                label.set(value.icon(), value.prefix(), value.connection(), value.suffix(), null);
            }
            // The combo's own box (index -1) is not a selected row
            boolean selectedRow = isSelected && index >= 0;
            setOpaque(selectedRow);
            setBackground(list.getSelectionBackground());
            label.setTextForeground(selectedRow ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }
}
