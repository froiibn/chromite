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
package com.seizia.chromite.ui.swing.internal;

import org.eclipse.osgi.util.NLS;

/**
 * Swing 텍스트 비교(두 창의 글을 나란히 비교하는 팝업)의 화면 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 텍스트 비교만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code TextCompareMessages.properties}), 한국어는 {@code TextCompareMessages_ko.properties}이고 OS 언어를 따른다. 키 접두사는
 * {@code textcompare_}다. 스키마 비교 문구({@link CompareMessages})와 나눈 것은 두 기능의 화면이 다르기 때문이다.
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성 (docs/chromite/text-compare.md)
 */
public class TextCompareMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.TextCompareMessages"; //$NON-NLS-1$

    public static String textcompare_command;
    public static String textcompare_command_tip;
    public static String textcompare_disabled;
    public static String textcompare_choose_title;
    public static String textcompare_choose_hint;
    public static String textcompare_left;
    public static String textcompare_right;
    public static String textcompare_ok;
    public static String textcompare_same_window;
    public static String textcompare_title;
    public static String textcompare_previous;
    public static String textcompare_next;
    public static String textcompare_swap;
    public static String textcompare_refresh;
    public static String textcompare_refresh_tip;
    public static String textcompare_ignore_whitespace;
    public static String textcompare_ignore_whitespace_tip;
    public static String textcompare_ignore_case;
    public static String textcompare_ignore_blank;
    public static String textcompare_count_one;
    public static String textcompare_count;
    public static String textcompare_identical;
    public static String textcompare_identical_options;
    public static String textcompare_comparing;
    public static String textcompare_too_large;
    public static String textcompare_selection;
    public static String textcompare_closed;

    static {
        NLS.initializeMessages(BUNDLE_NAME, TextCompareMessages.class);
    }

    private TextCompareMessages() {
    }
}
