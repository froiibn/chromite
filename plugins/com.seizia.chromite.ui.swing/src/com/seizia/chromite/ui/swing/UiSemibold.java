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

import com.formdev.flatlaf.util.FontUtils;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Whether the theme's semibold font draws the letters of the UI language itself, and the FlatLaf value that replaces it
 * when it does not.
 *
 * 설계: Java 합성 글꼴의 대체 글꼴은 글꼴 객체의 스타일(PLAIN/BOLD)을 따르고 Java에는 semibold 스타일이 없다. semibold 글꼴(PLAIN 스타일의
 * 별도 패밀리, Windows는 Segoe UI Semibold)에 없는 글자(한글)는 보통 굵기로 대체되어 옆 글자보다 가늘게 보인다. 그래서 화면 언어의 글자가
 * semibold 글꼴에 없으면 {@code semibold.font}를 기본 글꼴의 BOLD로 바꿔 대체 글꼴도 굵게 한다(docs/chromite/swing-l10n.md "한글 굵은 글꼴").
 * 앱({@link ChromiteTheme})과 업데이트 진행 창 프로그램({@code UpdateWindowApp}) 둘 다 쓴다. 업데이트 진행 창은 OSGi 없이 돌아
 * {@code Log}를 쓰는 {@link ChromiteTheme}을 부를 수 없으므로, 이 판단만 {@code Log} 없이 따로 둔다.
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: {@link ChromiteTheme}의 판단을 옮김. UI 글꼴이 운영체제 글꼴(Windows Segoe UI)이 되어 업데이트 진행 창도 한국어에서
 *   굵은 대체 글꼴이 필요해짐 (앱 변경 #67, docs/chromite/design-spec.md "적용 현황 — 운영체제 UI 글꼴 13")
 */
public final class UiSemibold {

    /**
     * FlatLaf key of the semibold font (theme properties, extra defaults).
     */
    public static final String SEMIBOLD_FONT_KEY = "semibold.font"; //$NON-NLS-1$
    /**
     * FlatLaf font value: the default font in the BOLD style, a composite font whose fallback fonts are bold too.
     */
    public static final String BOLD_FONT_VALUE = "bold"; //$NON-NLS-1$

    private UiSemibold() {
    }

    /**
     * Whether the installed theme's semibold font ({@code semibold.font}) has glyphs for all characters of the text,
     * i.e. the text needs no fallback font.
     *
     * @param text a semibold text of the UI language (a tool window title)
     */
    public static boolean isInSemiboldFont(@NotNull String text) {
        Font semibold = UIManager.getFont(SEMIBOLD_FONT_KEY);
        if (semibold == null) {
            return true;
        }
        // The family name the theme asked for (getFamily() of the composite font may be a localized name)
        String family = semibold.getName();
        FontUtils.loadFontFamily(family);
        // A physical font (not a composite one): canDisplayUpTo() sees only its own glyphs
        return new Font(family, Font.PLAIN, 1).canDisplayUpTo(text) < 0;
    }
}
