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
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.StyleConstants;

/**
 * Lets only what can be part of a positive number into a text field: the digits 0-9 and, where the field takes
 * fractions, one dot. Used by the number fields of the storage dialogs ({@link AltibaseTablespaceDialogs},
 * {@link OracleStorageDialogs}, {@link SQLServerStorageDialogs}).
 *
 * 설계:
 * <ul>
 *     <li>문서 필터라서 타자, 붙여넣기, 끌어 놓기가 모두 같은 길로 걸린다(셋 다 문서의 insert/replace로 들어옴). 걸리는 글은 통째로 넣지 않고
 *     오류 신호(Look and Feel의 소리)만 낸다: 결과 그리드의 비트 문자열 필터({@code ResultSetGrid.BitStringFilter})와 같은 동작이다.
 *     "12abc"를 붙여넣으면 숫자만 골라 넣지 않는다(사용자가 무엇이 들어갔는지 알 수 없음).</li>
 *     <li>소수점은 마침표 하나다. 대화상자가 값을 {@code BigDecimal}로 읽고(로캘과 무관) 채울 때도 마침표로 쓰기 때문이다. 부호·공백·지수(1e3)·
 *     천 단위 쉼표는 받지 않는다. 숫자는 ASCII 0-9만이다: {@code Long.parseLong}과 {@code BigDecimal}은 전각·다른 문자 체계의 숫자도 읽지만
 *     문장에 그 글자가 그대로 들어간다.</li>
 *     <li>글을 지우는 것(백스페이스, Delete, 선택을 지우고 넣기의 지우기)은 막지 않는다. 그래서 채운 값이 받지 않는 모양이어도
 *     ({@link #setText}) 고쳐 쓸 수 있다.</li>
 *     <li>입력기: 숫자 칸은 입력기를 끈다({@link JComponent#enableInputMethods}, 암호 칸과 같음). 한글 모드에서도 칸에는 조합 없이 영문 글자가
 *     오고, 필터가 거절한다. 그래도 조합 중인 글(속성 {@link StyleConstants#ComposedTextAttribute})이 오면 통과시킨다: {@code JTextComponent}는
 *     조합 글을 넣은 뒤 그 길이만큼 앞을 조합 위치로 기억해, 넣지 않으면 다음 입력기 사건에서 이미 있던 숫자를 지운다. 조합이 끝나 확정된 글은
 *     보통 입력처럼 다시 들어오므로 여기서 걸린다.</li>
 *     <li>확인할 때의 검사(빈 칸, 0, KB로 나누어떨어지지 않는 크기)는 각 대화상자에 그대로 둔다. 입력 중에는 그것을 막을 수 없다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-16 최초 작성: 저장소 대화상자의 숫자 칸에 문자가 들어가던 문제(앱 변경 #133, docs/chromite/swing-create-objects.md "숫자 칸")
 */
final class NumberInputFilter extends DocumentFilter {

    private static final char DECIMAL_SEPARATOR = '.';

    private final boolean fraction;

    private NumberInputFilter(boolean fraction) {
        this.fraction = fraction;
    }

    /**
     * Makes the field a number field.
     *
     * @param fraction accept one dot (the value is read as a decimal)
     */
    static void install(@NotNull JTextField field, boolean fraction) {
        ((AbstractDocument) field.getDocument()).setDocumentFilter(new NumberInputFilter(fraction));
        field.enableInputMethods(false);
    }

    /**
     * Shows a value read from the server as it is, even when it is not one the user could type (SQL Server's current
     * size or growth in MB with a fraction, in a whole-number field).
     */
    static void setText(@NotNull JTextField field, @NotNull String text) {
        AbstractDocument document = (AbstractDocument) field.getDocument();
        DocumentFilter filter = document.getDocumentFilter();
        document.setDocumentFilter(null);
        try {
            field.setText(text);
        } finally {
            document.setDocumentFilter(filter);
        }
    }

    @Override
    public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
        if (isAccepted(fb, offset, 0, string, attr)) {
            super.insertString(fb, offset, string, attr);
        }
    }

    @Override
    public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
        if (isAccepted(fb, offset, length, text, attrs)) {
            super.replace(fb, offset, length, text, attrs);
        }
    }

    private boolean isAccepted(
        @NotNull FilterBypass fb,
        int offset,
        int length,
        @Nullable String text,
        @Nullable AttributeSet attr
    ) throws BadLocationException {
        if (text == null || text.isEmpty() || attr != null && attr.isDefined(StyleConstants.ComposedTextAttribute)) {
            return true;
        }
        int separators = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == DECIMAL_SEPARATOR && fraction) {
                separators++;
            } else if (c < '0' || c > '9') {
                return reject();
            }
        }
        if (separators > 0) {
            String document = fb.getDocument().getText(0, fb.getDocument().getLength());
            String kept = document.substring(0, offset) + document.substring(offset + length);
            if (separators + kept.chars().filter(c -> c == DECIMAL_SEPARATOR).count() > 1) {
                return reject();
            }
        }
        return true;
    }

    private static boolean reject() {
        UIManager.getLookAndFeel().provideErrorFeedback(null);
        return false;
    }
}
