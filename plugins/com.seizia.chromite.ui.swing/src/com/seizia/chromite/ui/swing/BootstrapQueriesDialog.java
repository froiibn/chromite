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
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 연결 직후 실행할 SQL(bootstrap queries) 목록 편집 대화상자.
 *
 * 설계: SWT {@code EditBootstrapQueriesDialog}와 같은 내용이다: 쿼리 목록, Add / Edit / Remove, "Ignore SQL errors".
 * 값은 {@code DBPConnectionBootstrap}의 {@code initQueries}·{@code ignoreErrors}이고 저장은 연결 화면이 한다
 * ({@code ConnectionPageInitialization.saveSettings}와 같음). 쿼리 하나는 여러 줄일 수 있어 편집은 여러 줄 입력 칸으로 한다.
 * 목록에는 첫 줄만 보인다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 고급 연결 설정 (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 */
final class BootstrapQueriesDialog extends JDialog {

    private final DefaultListModel<String> queriesModel = new DefaultListModel<>();
    private final JList<String> queriesList = new JList<>(queriesModel);
    private final JCheckBox ignoreErrorsCheck = new JCheckBox(ConnectionMessages.bootstrap_ignore_errors);
    private boolean accepted;

    private BootstrapQueriesDialog(@Nullable Window owner, @NotNull List<String> queries, boolean ignoreErrors) {
        super(owner, ConnectionMessages.bootstrap_title, ModalityType.APPLICATION_MODAL);
        for (String query : queries) {
            queriesModel.addElement(query);
        }
        ignoreErrorsCheck.setSelected(ignoreErrors);
        queriesList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                String text = String.valueOf(value);
                int lineEnd = text.indexOf('\n');
                super.getListCellRendererComponent(list, lineEnd < 0 ? text : text.substring(0, lineEnd) + " …", index, isSelected, cellHasFocus);
                setToolTipText(text);
                return this;
            }
        });
        queriesList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    editQuery();
                }
            }
        });

        JButton addButton = new JButton(ConnectionMessages.common_add);
        JButton editButton = new JButton(ConnectionMessages.common_edit);
        JButton removeButton = new JButton(ConnectionMessages.common_remove);
        addButton.addActionListener(e -> addQuery());
        editButton.addActionListener(e -> editQuery());
        removeButton.addActionListener(e -> {
            int index = queriesList.getSelectedIndex();
            if (index >= 0) {
                queriesModel.remove(index);
            }
        });
        Runnable updateButtons = () -> {
            boolean selected = queriesList.getSelectedIndex() >= 0;
            editButton.setEnabled(selected);
            removeButton.setEnabled(selected);
        };
        queriesList.addListSelectionListener(e -> updateButtons.run());
        updateButtons.run();

        JPanel listButtons = new JPanel(new GridLayout(0, 1, 0, 6));
        listButtons.add(addButton);
        listButtons.add(editButton);
        listButtons.add(removeButton);
        JPanel listButtonsHolder = new JPanel(new BorderLayout());
        listButtonsHolder.add(listButtons, BorderLayout.NORTH);

        JLabel hint = new JLabel(ConnectionMessages.bootstrap_hint);
        JPanel center = new JPanel(new BorderLayout(12, 6));
        center.add(hint, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(queriesList);
        scrollPane.setPreferredSize(new Dimension(460, 220));
        center.add(scrollPane, BorderLayout.CENTER);
        center.add(listButtonsHolder, BorderLayout.EAST);
        center.add(ignoreErrorsCheck, BorderLayout.SOUTH);

        JButton okButton = new JButton(ConnectionMessages.common_ok);
        JButton cancelButton = new JButton(ConnectionMessages.common_cancel);
        okButton.addActionListener(e -> {
            accepted = true;
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(okButton);
        buttons.add(cancelButton);
        buttons.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0));

        JPanel content = new JPanel(new BorderLayout());
        // Dialog insets of docs/chromite/design-spec.md "11. 대화상자" (10, 12)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(center, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the dialog; {@code null} when it was canceled.
     */
    @Nullable
    static BootstrapQueriesDialog edit(@NotNull Component parent, @NotNull List<String> queries, boolean ignoreErrors) {
        BootstrapQueriesDialog dialog = new BootstrapQueriesDialog(SwingUtilities.getWindowAncestor(parent), queries, ignoreErrors);
        dialog.setVisible(true);
        return dialog.accepted ? dialog : null;
    }

    @NotNull
    List<String> getQueries() {
        List<String> queries = new ArrayList<>();
        for (int i = 0; i < queriesModel.size(); i++) {
            queries.add(queriesModel.get(i));
        }
        return queries;
    }

    boolean isIgnoreErrors() {
        return ignoreErrorsCheck.isSelected();
    }

    private void addQuery() {
        String query = editText("");
        if (query != null) {
            queriesModel.addElement(query);
            queriesList.setSelectedIndex(queriesModel.size() - 1);
        }
    }

    private void editQuery() {
        int index = queriesList.getSelectedIndex();
        if (index < 0) {
            return;
        }
        String query = editText(queriesModel.get(index));
        if (query != null) {
            queriesModel.set(index, query);
        }
    }

    /**
     * Multi-line SQL text; {@code null} when canceled or empty.
     */
    @Nullable
    private String editText(@NotNull String text) {
        JTextArea textArea = new JTextArea(text, 8, 50);
        // The theme's monospaced font (JetBrains Mono), like the Messages pane
        textArea.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        JScrollPane scrollPane = new JScrollPane(textArea);
        // The option pane focuses its OK button; the query is typed right away
        textArea.addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
                SwingUtilities.invokeLater(textArea::requestFocusInWindow);
            }

            @Override
            public void ancestorRemoved(AncestorEvent event) {
            }

            @Override
            public void ancestorMoved(AncestorEvent event) {
            }
        });
        int result = JOptionPane.showConfirmDialog(this, scrollPane, ConnectionMessages.bootstrap_query_title, JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION || textArea.getText().isBlank()) {
            return null;
        }
        return textArea.getText().trim();
    }
}
