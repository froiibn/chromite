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
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.ObjectExplorerMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * A list to choose one item from, with a filter box: Query &gt; Select Connection… (Ctrl+9) and Select Database/Schema…
 * (Ctrl+0), SWT {@code SelectDataSourceDialog} and the active schema selector ({@code SelectActiveSchemaHandler}).
 *
 * 설계: 콤보보다 긴 목록을 키보드로 빨리 고르게 하는 것이 목적이다. 글자를 치면 이름에 그 글자가 든 항목만 남고(대소문자 무시),
 * ↑/↓로 고르고 Enter나 두 번 누르기로 정한다. 고른 결과를 무엇에 쓸지는 부르는 쪽이 정한다(쿼리 창의 콤보에 넣어 그 창의 기존 경로,
 * 즉 세션 질문과 기본 DB 바꾸기를 그대로 지나게 한다). 처음에는 지금 항목을 고른 채로 연다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-explorer-productivity.md "Ctrl+9 / Ctrl+0")
 */
final class ObjectChooserDialog<T> extends JDialog {

    private final List<T> items;
    private final Function<T, String> text;
    private final DefaultListModel<T> listModel = new DefaultListModel<>();
    private final JList<T> list = new JList<>(listModel);
    private final JTextField filterField = new JTextField(28);
    @Nullable
    private T chosen;

    private ObjectChooserDialog(
        @NotNull Component owner,
        @NotNull String title,
        @NotNull List<T> items,
        @Nullable T current,
        @NotNull Function<T, String> text,
        @NotNull Function<T, Icon> icon
    ) {
        super(SwingUtilities.getWindowAncestor(owner), title, ModalityType.APPLICATION_MODAL);
        setIconImages(ChromiteIcons.getWindowIcons());
        this.items = items;
        this.text = text;

        filterField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, ObjectExplorerMessages.oe_choose_filter);
        filterField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter(null);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter(null);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter(null);
            }
        });
        // The arrow keys move in the list while typing in the filter box
        for (int key : new int[]{KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN}) {
            filterField.getInputMap().put(KeyStroke.getKeyStroke(key, 0), "chromite.moveInList." + key); //$NON-NLS-1$
            filterField.getActionMap().put("chromite.moveInList." + key, new AbstractAction() { //$NON-NLS-1$
                @Override
                public void actionPerformed(ActionEvent e) {
                    int size = listModel.getSize();
                    if (size == 0) {
                        return;
                    }
                    int step = key == KeyEvent.VK_UP ? -1 : key == KeyEvent.VK_DOWN ? 1 : key == KeyEvent.VK_PAGE_UP ? -10 : 10;
                    int index = Math.clamp(list.getSelectedIndex() + step, 0, size - 1);
                    list.setSelectedIndex(index);
                    list.ensureIndexIsVisible(index);
                }
            });
        }
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(14);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            @SuppressWarnings("unchecked")
            public Component getListCellRendererComponent(JList<?> jList, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(jList, text.apply((T) value), index, isSelected, cellHasFocus);
                setIcon(icon.apply((T) value));
                return this;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    choose();
                }
            }
        });

        JButton okButton = new JButton(CoreMessages.common_ok);
        okButton.addActionListener(e -> choose());
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        buttons.add(okButton);
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(filterField, BorderLayout.NORTH);
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        applyFilter(current);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the list and returns the chosen item, or null when canceled.
     *
     * @param current the item selected at first
     */
    @Nullable
    static <T> T choose(
        @NotNull Component owner,
        @NotNull String title,
        @NotNull List<T> items,
        @Nullable T current,
        @NotNull Function<T, String> text,
        @NotNull Function<T, Icon> icon
    ) {
        ObjectChooserDialog<T> dialog = new ObjectChooserDialog<>(owner, title, items, current, text, icon);
        dialog.setVisible(true);
        return dialog.chosen;
    }

    /**
     * Lists the items whose text contains the filter text and selects the given item, or the first one.
     */
    private void applyFilter(@Nullable T select) {
        String filter = filterField.getText().trim().toLowerCase(Locale.ROOT);
        listModel.clear();
        for (T item : items) {
            if (filter.isEmpty() || text.apply(item).toLowerCase(Locale.ROOT).contains(filter)) {
                listModel.addElement(item);
            }
        }
        int index = select == null ? -1 : listModel.indexOf(select);
        if (index < 0 && !listModel.isEmpty()) {
            index = 0;
        }
        if (index >= 0) {
            list.setSelectedIndex(index);
            list.ensureIndexIsVisible(index);
        }
    }

    private void choose() {
        chosen = list.getSelectedValue();
        if (chosen != null) {
            dispose();
        }
    }
}
