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
import com.seizia.chromite.ui.swing.internal.ErrorMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.function.Supplier;

/**
 * 오류 대화상자의 본문: 오류 문구와 접힌 "자세히"(스택 추적), "복사" 단추.
 *
 * 설계: 처음 보이는 문구는 {@link ErrorText}로 만든 메시지뿐이고, 클래스·패키지 이름이 들어 있는 스택 추적은
 * 접힌 "자세히" 영역에만 둔다. 사용자가 원할 때만 펼쳐 볼 수 있고 "복사"로 문구와 스택 추적을 함께 클립보드에 넣어
 * 버그 보고에 붙일 수 있다(SWT의 {@code StandardErrorDialog}가 "Details" 단추로 스택 추적을 보여 주는 것과 같은 역할).
 * 대화상자 틀은 {@link JOptionPane}을 그대로 쓰고 이 본문만 메시지 자리에 넣는다. 그래서 확인 단추나
 * 중지·다시 시도 같은 선택 단추(SwingPlatformUI#showErrorStopRetryIgnore)는 호출하는 쪽이 정한다.
 * 펼치거나 접으면 창 크기를 다시 맞춘다({@link Window#pack()}).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-frontend.md "오류 문구")
 */
final class ErrorDialog {

    private static final int DETAILS_ROWS = 14;

    private ErrorDialog() {
    }

    /**
     * Shows a modal error message box; with an error, the box also has the collapsed Details and a Copy button.
     */
    static void show(@Nullable Component parent, @NotNull String title, @Nullable String text, @Nullable Throwable error) {
        JOptionPane.showMessageDialog(parent, createContent(text, error), title, JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Message component for a {@link JOptionPane}: the text, and for an error the Details/Copy row and the
     * collapsed stack trace.
     */
    @NotNull
    static JComponent createContent(@Nullable String text, @Nullable Throwable error) {
        JComponent message = SwingPlatformUI.createMessageComponent(text);
        if (error == null) {
            return message;
        }
        String stackTrace = ErrorText.stackTrace(error);

        JTextArea detailsArea = new JTextArea(stackTrace, DETAILS_ROWS, 60);
        detailsArea.setEditable(false);
        detailsArea.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
        detailsArea.setCaretPosition(0);
        JScrollPane detailsScroll = new JScrollPane(detailsArea);
        detailsScroll.setPreferredSize(new Dimension(message.getPreferredSize().width + 20,
            detailsArea.getPreferredScrollableViewportSize().height));
        detailsScroll.setVisible(false);

        JToggleButton detailsButton = new JToggleButton(ErrorMessages.dialog_details, UIManager.getIcon("Tree.collapsedIcon")); //$NON-NLS-1$
        detailsButton.setSelectedIcon(UIManager.getIcon("Tree.expandedIcon")); //$NON-NLS-1$
        detailsButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        detailsButton.addActionListener(e -> {
            detailsScroll.setVisible(detailsButton.isSelected());
            Window window = SwingUtilities.getWindowAncestor(detailsButton);
            if (window != null) {
                window.pack();
            }
        });
        JButton copyButton = new JButton(ErrorMessages.dialog_copy);
        copyButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        copyButton.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
            new StringSelection(CommonUtils.isEmpty(text) ? stackTrace : text + "\n\n" + stackTrace), null)); //$NON-NLS-1$

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(detailsButton);
        buttons.add(copyButton);
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.setOpaque(false);
        south.add(buttons, BorderLayout.NORTH);
        south.add(detailsScroll, BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        panel.add(message, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * A borderless "Details…" button for inline error areas (wizard and task pages) that opens the error box with the
     * stack trace; hidden until {@link #setError} gets an error.
     */
    static final class DetailsButton extends JButton {
        @Nullable
        private Throwable error;
        @Nullable
        private String text;

        DetailsButton(@NotNull Supplier<String> title) {
            super(ErrorMessages.dialog_details_button);
            putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
            setVisible(false);
            addActionListener(e -> {
                if (error != null) {
                    ErrorDialog.show(SwingUtilities.getWindowAncestor(this), title.get(), text, error);
                }
            });
        }

        /**
         * The error to show (with its text as shown inline), or null to hide the button.
         */
        void setError(@Nullable String text, @Nullable Throwable error) {
            this.text = text;
            this.error = error;
            setVisible(error != null);
        }
    }
}
