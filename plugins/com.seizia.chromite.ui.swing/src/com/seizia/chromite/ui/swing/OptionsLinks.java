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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * 설정 화면을 글로만 가리키던 곳("Tools &gt; Options &gt; …")에서 그 Options 페이지를 바로 여는 링크와 메뉴 동작.
 *
 * 설계:
 * <ul>
 *     <li>여는 방법은 {@link OptionsDialog#open(Frame, String)} 하나만 쓴다. 페이지는 화면 이름이 아니라 페이지 식별자로 고른다
 *     (설정 페이지는 클래스 이름, 그 밖은 {@link #PAGE_GENERAL}, {@link OptionsDialog#PAGE_AGENT}).</li>
 *     <li>Options의 주인은 프레임이어야 한다. 대화상자(쿼리 기록, 연결 대화상자, Agent 확인)에서 열면 그 대화상자의 주인 사슬에서 프레임을 찾고,
 *     주인이 없는 창(MCP 요청의 확인 창 등)이면 열린 메인 창을 쓴다.</li>
 *     <li>링크 모양은 결과 표의 링크 단추와 같다(테두리 없는 단추, {@code Component.linkColor}, 손 모양 커서).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: 설정을 글로만 가리키던 다섯 화면(B12) (docs/chromite/swing-options.md "특정 페이지로 여는 곳")
 */
final class OptionsLinks {

    /**
     * The id of the General page of {@link OptionsDialog} (Environment, Object Explorer, Query Editor, Query History).
     */
    static final String PAGE_GENERAL = "general"; //$NON-NLS-1$

    private OptionsLinks() {
    }

    /**
     * A link-styled button that opens the Options page {@code pageId}.
     */
    @NotNull
    static JButton createLink(@NotNull String pageId) {
        return createLink(pageId, null);
    }

    /**
     * A link-styled button that opens the Options page {@code pageId}.
     *
     * @param closed runs after the Options dialog is closed (it is modal), to show the settings it may have changed
     */
    @NotNull
    static JButton createLink(@NotNull String pageId, @Nullable Runnable closed) {
        JButton button = new JButton(CoreMessages.main_open_settings_link);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        button.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.linkColor; margin: 0,2,0,2"); //$NON-NLS-1$
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> {
            open(button, pageId);
            if (closed != null) {
                closed.run();
            }
        });
        return button;
    }

    /**
     * Opens the Options dialog at the page {@code pageId}, owned by the frame of {@code from}.
     */
    static void open(@Nullable Component from, @NotNull String pageId) {
        Frame owner = findFrame(from);
        if (owner != null) {
            OptionsDialog.open(owner, pageId);
        }
    }

    @Nullable
    private static Frame findFrame(@Nullable Component from) {
        Window window = from == null ? null : (from instanceof Window w ? w : SwingUtilities.getWindowAncestor(from));
        while (window != null) {
            // Not the hidden shared owner frame of the windows created without an owner
            if (window instanceof Frame frame && frame.isShowing()) {
                return frame;
            }
            window = window.getOwner();
        }
        for (Frame frame : Frame.getFrames()) {
            if (frame instanceof MainFrame && frame.isDisplayable()) {
                return frame;
            }
        }
        return null;
    }
}
