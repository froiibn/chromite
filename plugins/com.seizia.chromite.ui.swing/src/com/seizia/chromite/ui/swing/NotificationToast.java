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
import com.formdev.flatlaf.ui.FlatLineBorder;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 메인 창 오른쪽 아래에 잠깐 떠 있는 알림(토스트).
 *
 * 설계: {@code DBPPlatformUI.showNotification}은 백그라운드 작업이 결과를 알릴 때 쓰므로 모달 대화상자로 막으면 안 된다
 * (SWT 버전도 비모달 알림 팝업을 쓴다). 별도 창 대신 메인 창의 레이어 창({@link JLayeredPane#POPUP_LAYER})에 올려
 * 포커스를 빼앗지 않고 창과 함께 움직이게 했다. 여러 알림은 위로 쌓이고, 일정 시간 뒤 사라진다(오류는 더 오래).
 * 알림을 누르면 {@code feedback} 동작(있으면)을 실행하고 닫는다. 모든 메서드는 EDT에서 호출한다.
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 새 토스트가 창 높이만큼 크게 그려지던 문제: 줄 바꿈 글 상자의 너비가 정해지기 전에 선호 높이를 재던 것을
 *   토스트를 먼저 배치한 뒤 재도록 고침 (docs/chromite/swing-frontend.md)
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 */
final class NotificationToast extends JPanel {

    enum Kind {
        INFO("OptionPane.informationIcon", 6_000), //$NON-NLS-1$
        WARNING("OptionPane.warningIcon", 8_000), //$NON-NLS-1$
        ERROR("OptionPane.errorIcon", 12_000); //$NON-NLS-1$

        private final String iconKey;
        private final int displayMillis;

        Kind(@NotNull String iconKey, int displayMillis) {
            this.iconKey = iconKey;
            this.displayMillis = displayMillis;
        }
    }

    private static final int MARGIN = 12;
    private static final int GAP = 8;
    private static final int WIDTH = 360;
    private static final String TOASTS_PROPERTY = "chromite.notificationToasts"; //$NON-NLS-1$

    private final JRootPane rootPane;
    private final Timer closeTimer;

    private NotificationToast(
        @NotNull JRootPane rootPane,
        @NotNull String title,
        @Nullable String message,
        @NotNull Kind kind,
        @Nullable Runnable feedback
    ) {
        super(new BorderLayout(10, 4));
        this.rootPane = rootPane;
        setBorder(BorderFactory.createCompoundBorder(
            new FlatLineBorder(new Insets(0, 0, 0, 0), UIManager.getColor("Component.borderColor"), 1, 10), //$NON-NLS-1$
            BorderFactory.createEmptyBorder(10, 12, 10, 8)));
        // Rounded background matching the rounded border (FlatLaf panel style)
        putClientProperty(FlatClientProperties.STYLE, "arc: 10"); //$NON-NLS-1$

        JLabel titleLabel = new JLabel(title);
        titleLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
        JButton closeButton = new JButton("×"); //$NON-NLS-1$
        closeButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        closeButton.setFocusable(false);
        closeButton.addActionListener(e -> close());

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(titleLabel, BorderLayout.CENTER);
        header.add(closeButton, BorderLayout.EAST);

        JPanel text = new JPanel(new BorderLayout(0, 4));
        text.setOpaque(false);
        text.add(header, BorderLayout.NORTH);
        if (!CommonUtils.isEmpty(message)) {
            JTextArea messageArea = new JTextArea(message);
            messageArea.setEditable(false);
            messageArea.setFocusable(false);
            messageArea.setOpaque(false);
            messageArea.setLineWrap(true);
            messageArea.setWrapStyleWord(true);
            messageArea.setFont(UIManager.getFont("Label.font")); //$NON-NLS-1$
            messageArea.setBorder(null);
            messageArea.setRows(Math.min(4, Math.max(1, message.length() / 45 + 1)));
            text.add(messageArea, BorderLayout.CENTER);
            if (feedback != null) {
                messageArea.addMouseListener(createFeedbackListener(feedback));
            }
        }
        add(new JLabel(UIManager.getIcon(kind.iconKey)), BorderLayout.WEST);
        add(text, BorderLayout.CENTER);
        if (feedback != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(CoreMessages.toast_click_details);
            addMouseListener(createFeedbackListener(feedback));
        }

        closeTimer = new Timer(kind.displayMillis, e -> close());
        closeTimer.setRepeats(false);
    }

    /**
     * Shows a notification in the lower right corner of the frame.
     */
    static void show(
        @NotNull JFrame frame,
        @NotNull String title,
        @Nullable String message,
        @NotNull Kind kind,
        @Nullable Runnable feedback
    ) {
        JRootPane rootPane = frame.getRootPane();
        NotificationToast toast = new NotificationToast(rootPane, title, message, kind, feedback);
        List<NotificationToast> toasts = getToasts(rootPane);
        toasts.add(toast);
        rootPane.getLayeredPane().add(toast, JLayeredPane.POPUP_LAYER);
        layoutToasts(rootPane);
        toast.closeTimer.start();
    }

    @NotNull
    private MouseAdapter createFeedbackListener(@NotNull Runnable feedback) {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                close();
                feedback.run();
            }
        };
    }

    private void close() {
        closeTimer.stop();
        if (getToasts(rootPane).remove(this)) {
            JLayeredPane layeredPane = rootPane.getLayeredPane();
            layeredPane.remove(this);
            layeredPane.repaint(getBounds());
            layoutToasts(rootPane);
        }
    }

    /**
     * The open toasts of a frame, newest last. Kept as a client property of the root pane, together with
     * the listener that moves them when the frame is resized.
     */
    @NotNull
    @SuppressWarnings("unchecked")
    private static List<NotificationToast> getToasts(@NotNull JRootPane rootPane) {
        Object toasts = rootPane.getClientProperty(TOASTS_PROPERTY);
        if (toasts == null) {
            toasts = new ArrayList<NotificationToast>();
            rootPane.putClientProperty(TOASTS_PROPERTY, toasts);
            rootPane.getLayeredPane().addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    layoutToasts(rootPane);
                }
            });
        }
        return (List<NotificationToast>) toasts;
    }

    /**
     * Stacks the toasts upwards from the lower right corner (newest at the bottom).
     */
    private static void layoutToasts(@NotNull JRootPane rootPane) {
        JLayeredPane layeredPane = rootPane.getLayeredPane();
        List<NotificationToast> toasts = getToasts(rootPane);
        int bottom = layeredPane.getHeight() - MARGIN - getStatusBarHeight(rootPane);
        for (int i = toasts.size() - 1; i >= 0; i--) {
            NotificationToast toast = toasts.get(i);
            // The wrapped message's preferred height follows its current width: lay the toast out at its width first.
            // A new toast has never been laid out, and a message area without a width wraps after every few characters
            // (a toast almost as tall as the window).
            toast.setSize(WIDTH, Short.MAX_VALUE);
            toast.validate();
            Dimension size = toast.getPreferredSize();
            toast.setBounds(layeredPane.getWidth() - WIDTH - MARGIN, bottom - size.height, WIDTH, size.height);
            toast.validate();
            bottom -= size.height + GAP;
        }
        layeredPane.repaint();
    }

    private static int getStatusBarHeight(@NotNull JRootPane rootPane) {
        if (rootPane.getContentPane() instanceof JComponent content && content.getLayout() instanceof BorderLayout layout) {
            Component statusBar = layout.getLayoutComponent(BorderLayout.SOUTH);
            return statusBar == null ? 0 : statusBar.getHeight();
        }
        return 0;
    }
}
