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
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.regex.PatternSyntaxException;

/**
 * Edit &gt; Find / Replace (SSMS Ctrl+F, Ctrl+H, F3, Shift+F3): SQL 편집기와 DDL 탭의 글 찾기·바꾸기.
 *
 * 설계: 찾기·바꾸기·모두 표시는 RSyntaxTextArea의 {@link SearchEngine}과 {@link SearchContext}를 그대로 쓴다(대소문자, 단어 단위, 정규식,
 * 끝에서 처음으로 이어 찾기). RSTA의 찾기 대화상자는 별도 라이브러리(RSTAUI)라 넣지 않고 이 작은 대화상자만 만들었다.
 * <ul>
 *     <li>대화상자는 모달이 아니고 창마다 하나다. 대상은 열 때마다 받은 편집기(활성 문서 탭)라, 다른 탭으로 옮긴 뒤 다시 열면 그 탭을 찾는다.</li>
 *     <li>찾는 조건은 실행 중에 기억한다. F3·Shift+F3은 대화상자가 닫혀 있어도 마지막 조건으로 다음·이전을 찾는다.</li>
 *     <li>맞는 곳을 모두 표시하고(RSTA mark all), 대화상자를 닫으면 표시를 지운다. 읽기 전용 편집기(DDL 탭)에서는 바꾸기 단추를 끈다.</li>
 *     <li>열 때 편집기에 한 줄 안의 선택이 있으면 그 글을 찾는 글로 넣는다(SSMS).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 P1: 최초 작성 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 */
final class FindReplaceDialog extends JDialog {

    /**
     * Search options of the last find, kept for F3 / Shift+F3 while the application runs.
     */
    private static final SearchContext lastSearch = new SearchContext();
    @Nullable
    private static FindReplaceDialog dialog;

    static {
        lastSearch.setMarkAll(true);
        lastSearch.setSearchWrap(true);
    }

    private final JTextField findField = new JTextField(28);
    private final JTextField replaceField = new JTextField(28);
    private final JLabel replaceLabel = new JLabel(CoreMessages.find_replace_with);
    private final JCheckBox matchCaseBox = new JCheckBox(CoreMessages.find_match_case);
    private final JCheckBox wholeWordBox = new JCheckBox(CoreMessages.find_whole_word);
    private final JCheckBox regexBox = new JCheckBox(CoreMessages.find_regex);
    private final JButton findNextButton = new JButton(CoreMessages.find_find_next);
    private final JButton findPreviousButton = new JButton(CoreMessages.find_find_previous);
    private final JButton replaceButton = new JButton(CoreMessages.find_replace);
    private final JButton replaceAllButton = new JButton(CoreMessages.find_replace_all);
    private final JLabel statusLabel = new JLabel(" ");
    @Nullable
    private RTextArea target;

    private FindReplaceDialog(@NotNull Window owner) {
        super(owner, CoreMessages.find_title, ModalityType.MODELESS);
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        findNextButton.addActionListener(e -> find(true));
        findPreviousButton.addActionListener(e -> find(false));
        replaceButton.addActionListener(e -> replace());
        replaceAllButton.addActionListener(e -> replaceAll());
        JButton closeButton = new JButton(CoreMessages.find_close);
        closeButton.addActionListener(e -> setVisible(false));
        findField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateButtons();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateButtons();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });

        // Label column and field (space.6 between label and field and between rows), options, buttons on the right
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 6, 6);
        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel(CoreMessages.find_find_what), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(findField, c);
        c.gridx = 0;
        c.gridy = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        form.add(replaceLabel, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(replaceField, c);
        JPanel options = new JPanel(new GridLayout(0, 1, 0, 2));
        options.add(matchCaseBox);
        options.add(wholeWordBox);
        options.add(regexBox);
        c.gridx = 1;
        c.gridy = 2;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(6, 0, 6, 6);
        form.add(options, c);
        c.gridy = 3;
        c.insets = new Insets(0, 0, 0, 6);
        form.add(statusLabel, c);

        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        buttons.add(findNextButton);
        buttons.add(findPreviousButton);
        buttons.add(replaceButton);
        buttons.add(replaceAllButton);
        buttons.add(closeButton);
        JPanel buttonColumn = new JPanel(new BorderLayout());
        buttonColumn.add(buttons, BorderLayout.NORTH);

        JPanel content = new JPanel(new BorderLayout(12, 0));
        // Dialog insets 10/12 (design-spec space tokens)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(form, BorderLayout.CENTER);
        content.add(buttonColumn, BorderLayout.EAST);
        setContentPane(content);

        getRootPane().setDefaultButton(findNextButton);
        getRootPane().registerKeyboardAction(e -> setVisible(false),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> find(true),
            KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> find(false),
            KeyStroke.getKeyStroke(KeyEvent.VK_F3, KeyEvent.SHIFT_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                // A search done here is reflected in the options used by F3 in the editor
                readOptions();
            }
        });
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentHidden(ComponentEvent e) {
                if (target != null) {
                    target.clearMarkAllHighlights();
                }
            }
        });
    }

    /**
     * Edit &gt; Find (Ctrl+F) and Replace (Ctrl+H): opens the dialog for an editor.
     */
    static void open(@NotNull RTextArea textArea, boolean replace) {
        Window owner = SwingUtilities.getWindowAncestor(textArea);
        if (owner == null) {
            return;
        }
        if (dialog == null || dialog.getOwner() != owner) {
            if (dialog != null) {
                dialog.dispose();
            }
            dialog = new FindReplaceDialog(owner);
        }
        dialog.show(textArea, replace);
    }

    /**
     * F3 / Shift+F3: the next or previous match of the last search; opens the dialog if nothing was searched yet.
     */
    static void findAgain(@NotNull RTextArea textArea, boolean forward) {
        if (lastSearch.getSearchFor() == null || lastSearch.getSearchFor().isEmpty()) {
            open(textArea, false);
            return;
        }
        if (dialog != null && dialog.isVisible()) {
            dialog.target = textArea;
            dialog.find(forward);
            return;
        }
        SearchContext again = lastSearch.clone();
        again.setSearchForward(forward);
        try {
            SearchResult result = SearchEngine.find(textArea, again);
            if (!result.wasFound()) {
                UIManager.getLookAndFeel().provideErrorFeedback(textArea);
            }
        } catch (PatternSyntaxException e) {
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
        }
    }

    private void show(@NotNull RTextArea textArea, boolean replace) {
        if (target != null && target != textArea) {
            target.clearMarkAllHighlights();
        }
        target = textArea;
        String selection = textArea.getSelectedText();
        if (selection != null && !selection.isEmpty() && selection.indexOf('\n') < 0) {
            findField.setText(selection);
        } else if (lastSearch.getSearchFor() != null && findField.getText().isEmpty()) {
            findField.setText(lastSearch.getSearchFor());
        }
        matchCaseBox.setSelected(lastSearch.getMatchCase());
        wholeWordBox.setSelected(lastSearch.getWholeWord());
        regexBox.setSelected(lastSearch.isRegularExpression());
        setTitle(replace ? CoreMessages.find_replace : CoreMessages.find_find);
        replaceLabel.setVisible(replace);
        replaceField.setVisible(replace);
        replaceButton.setVisible(replace);
        replaceAllButton.setVisible(replace);
        statusLabel.setText(" ");
        updateButtons();
        pack();
        if (!isVisible()) {
            setLocationRelativeTo(textArea);
        }
        setVisible(true);
        findField.selectAll();
        findField.requestFocusInWindow();
    }

    private void updateButtons() {
        boolean hasText = !findField.getText().isEmpty();
        boolean editable = target != null && target.isEditable();
        findNextButton.setEnabled(hasText);
        findPreviousButton.setEnabled(hasText);
        replaceButton.setEnabled(hasText && editable);
        replaceAllButton.setEnabled(hasText && editable);
    }

    private void readOptions() {
        lastSearch.setSearchFor(findField.getText());
        lastSearch.setReplaceWith(replaceField.getText());
        lastSearch.setMatchCase(matchCaseBox.isSelected());
        lastSearch.setWholeWord(wholeWordBox.isSelected());
        lastSearch.setRegularExpression(regexBox.isSelected());
    }

    private void find(boolean forward) {
        if (target == null || findField.getText().isEmpty()) {
            return;
        }
        readOptions();
        lastSearch.setSearchForward(forward);
        try {
            SearchResult result = SearchEngine.find(target, lastSearch);
            if (!result.wasFound()) {
                statusLabel.setText(CoreMessages.find_not_found);
            } else if (result.isWrapped()) {
                statusLabel.setText(forward ? CoreMessages.find_wrapped_beginning : CoreMessages.find_wrapped_end);
            } else {
                statusLabel.setText(NLS.bind(result.getMarkedCount() == 1 ? CoreMessages.find_match : CoreMessages.find_matches, result.getMarkedCount()));
            }
        } catch (PatternSyntaxException e) {
            statusLabel.setText(NLS.bind(CoreMessages.find_invalid_regex, e.getDescription()));
        }
        lastSearch.setSearchForward(true);
    }

    private void replace() {
        if (target == null || !target.isEditable()) {
            return;
        }
        readOptions();
        lastSearch.setSearchForward(true);
        try {
            SearchResult result = SearchEngine.replace(target, lastSearch);
            statusLabel.setText(result.wasFound() ? " " : CoreMessages.find_not_found);
        } catch (PatternSyntaxException e) {
            statusLabel.setText(NLS.bind(CoreMessages.find_invalid_regex, e.getDescription()));
        }
    }

    private void replaceAll() {
        if (target == null || !target.isEditable()) {
            return;
        }
        readOptions();
        try {
            SearchResult result = SearchEngine.replaceAll(target, lastSearch);
            int count = result.getCount();
            statusLabel.setText(NLS.bind(count == 1 ? CoreMessages.find_replaced_one : CoreMessages.find_replaced_many, count));
        } catch (PatternSyntaxException e) {
            statusLabel.setText(NLS.bind(CoreMessages.find_invalid_regex, e.getDescription()));
        }
    }
}
