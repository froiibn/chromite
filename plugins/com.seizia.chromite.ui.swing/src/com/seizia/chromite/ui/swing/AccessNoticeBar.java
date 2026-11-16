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

import com.formdev.flatlaf.util.ColorFunctions;
import com.formdev.flatlaf.util.UIScale;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Activity Monitor 탭 위의 권한 안내 줄(예: "자신의 세션만 표시합니다…", "이 로그인은 V$LOCK을 읽을 수 없습니다…").
 *
 * 설계: 권한이 모자라면 서버 오류 대신 무엇이 없고 무엇을 받아야 하는지를 표 위에 계속 보인다(상태 줄은 새로 고칠 때마다 바뀐다).
 * 문구는 모델의 권한 확인({@code ServerAdminAccess#notice})이 만든다. 문구가 길어 창 너비에 맞춰 줄을 바꾸도록 글 상자를 쓰고,
 * 배경은 테마의 경고 색({@code Chromite.status.warning})을 패널 배경에 옅게 섞어 라이트·다크 모두에서 글자가 읽히게 한다.
 * 안내가 없으면 보이지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: PostgreSQL·Oracle 세션·잠금 권한 안내 (docs/chromite/swing-admin-tools.md)
 */
final class AccessNoticeBar extends JPanel {

    private final JLabel iconLabel = new JLabel();
    private final JTextArea textArea = new JTextArea() {
        @Override
        public Dimension getPreferredSize() {
            // Wrap at the width the bar has: a text area wraps only once it knows its width
            int width = AccessNoticeBar.this.getWidth() - AccessNoticeBar.this.getInsets().left - AccessNoticeBar.this.getInsets().right
                - iconLabel.getPreferredSize().width - UIScale.scale(8);
            if (width > 0 && getWidth() != width) {
                setSize(width, Short.MAX_VALUE);
            }
            return super.getPreferredSize();
        }
    };

    AccessNoticeBar() {
        super(new BorderLayout(UIScale.scale(8), 0));
        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setOpaque(false);
        textArea.setBorder(BorderFactory.createEmptyBorder());
        iconLabel.setVerticalAlignment(SwingConstants.TOP);
        setBorder(BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(10), UIScale.scale(6), UIScale.scale(10)));
        add(iconLabel, BorderLayout.WEST);
        add(textArea, BorderLayout.CENTER);
        setVisible(false);
        applyTheme();
    }

    /**
     * Shows the notice, or hides the bar when there is none.
     */
    void setNotice(@Nullable String notice) {
        textArea.setText(notice == null ? "" : notice); //$NON-NLS-1$
        setVisible(notice != null);
        revalidate();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Called by the constructor of JPanel before the fields exist
        if (textArea != null) {
            applyTheme();
        }
    }

    private void applyTheme() {
        Color panel = UIManager.getColor("Panel.background"); //$NON-NLS-1$
        Color warning = UIManager.getColor("Chromite.status.warning"); //$NON-NLS-1$
        if (panel != null && warning != null) {
            setBackground(ColorFunctions.mix(warning, panel, 0.18f));
        }
        textArea.setFont(UIManager.getFont("Label.font")); //$NON-NLS-1$
        textArea.setForeground(UIManager.getColor("Label.foreground")); //$NON-NLS-1$
        iconLabel.setIcon(UiIcons.get("warning")); //$NON-NLS-1$
    }
}
