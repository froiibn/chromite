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

import org.jkiss.code.NotNull;

import javax.swing.*;
import javax.swing.text.View;
import java.awt.*;

/**
 * An explanatory sentence that looks like a label and wraps at word boundaries when its place is narrower than the
 * sentence: one line where it fits, more lines where it does not, never cut.
 *
 * 설계:
 * <ul>
 *     <li>한 줄 {@link JLabel}은 배치가 준 폭이 글보다 좁으면 말줄임표로 잘린다. 옵션 페이지의 안내 글(MCP 서버 설명, 템플릿 안내)이
 *     대화상자 폭과 글꼴(13)에 따라 그렇게 잘렸다(앱 변경 #71 b). 이 글은 줄을 바꾼다.</li>
 *     <li>HTML 라벨은 접히지만 높이가 마지막으로 그린 폭을 따라가 배치가 한 번 늦거나(좁게 접힌 채 남음) 처음 배치에서 한 줄 높이만 받는다.
 *     그래서 읽기 전용 {@link JTextArea}(줄 바꿈은 단어 단위)를 쓰고, 선호 크기를 직접 정한다: 폭은 한 줄로 놓을 때의 폭(자리가 있으면
 *     한 줄), 높이는 배치가 준 지금 폭에서 접은 줄 수의 높이다. 폭이 바뀌면 다시 배치를 한 번 요청해 높이가 줄 수를 따라간다
 *     ({@code OptionsForm}의 카드와 같은 방법).</li>
 *     <li>최소 폭은 가장 긴 단어의 폭이다. 그보다 좁으면 단어 가운데서 끊긴다.</li>
 *     <li>글꼴은 라벨 글꼴(테마·밀도 전환을 따름), 색은 부르는 쪽이 FlatLaf 스타일로 정한다(보조 글자색, 경고 글자색).
 *     포커스를 받지 않고 고칠 수 없으며 바탕을 칠하지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: 옵션의 한 줄 안내가 잘리던 것(앱 변경 #71 b, docs/chromite/swing-options.md "잘리던 한 줄 안내")
 */
final class WrappingNote extends JTextArea {

    WrappingNote(@NotNull String text) {
        super(text);
        setEditable(false);
        setFocusable(false);
        setOpaque(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        setBorder(BorderFactory.createEmptyBorder());
        setCursor(Cursor.getDefaultCursor());
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // A label's font, also after a theme or density change (the text area's own font is the editor font)
        setFont(UIManager.getFont("Label.font")); //$NON-NLS-1$
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        Insets insets = getInsets();
        int oneLine = getTextWidth(getText()) + insets.left + insets.right;
        // The height at the width the layout gave (the lines it wraps into there); one line before it has a width
        return new Dimension(oneLine, getHeightAt(getWidth() > 0 ? getWidth() : oneLine));
    }

    @Override
    public Dimension getMinimumSize() {
        if (isMinimumSizeSet()) {
            return super.getMinimumSize();
        }
        Insets insets = getInsets();
        int word = 0;
        for (String part : getText().split("\\s+")) { //$NON-NLS-1$
            word = Math.max(word, getTextWidth(part));
        }
        return new Dimension(word + insets.left + insets.right, getPreferredSize().height);
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        int oldWidth = getWidth();
        super.setBounds(x, y, width, height);
        if (oldWidth != width) {
            // The height depends on the width: lay the parent out again for the new number of lines
            SwingUtilities.invokeLater(this::revalidate);
        }
    }

    // Widest line in the component's font rendering (fractional widths rounded up, so that the text area does not wrap
    // a line that was measured to fit)
    private int getTextWidth(@NotNull String text) {
        FontMetrics metrics = getFontMetrics(getFont());
        double width = 0;
        for (String line : text.split("\n", -1)) { //$NON-NLS-1$
            width = Math.max(width, getFont().getStringBounds(line, metrics.getFontRenderContext()).getWidth());
        }
        return (int) Math.ceil(width);
    }

    private int getHeightAt(int width) {
        Insets insets = getInsets();
        View root = getUI().getRootView(this);
        root.setSize(Math.max(1, width - insets.left - insets.right), Integer.MAX_VALUE);
        return (int) Math.ceil(root.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom;
    }
}
