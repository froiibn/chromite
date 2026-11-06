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
import com.seizia.chromite.Log;

import java.net.URL;

/**
 * UI 글꼴 Pretendard(번들)의 등록.
 *
 * 설계:
 * <ul>
 *     <li>UI 글꼴은 Pretendard 12다(사용자 결정 2026-11-07: Pretendard 13으로 정한 뒤 "한단계 줄인다", docs/chromite/design-spec.md "글꼴 토큰"). 한글과 라틴 글자를 한 글꼴이 그려
 *     한글이 운영체제 글꼴(맑은 고딕)로 대체되지 않는다. SIL OFL 1.1이라 번들({@code fonts/pretendard})에 넣었다.</li>
 *     <li>테마 properties의 {@code defaultFont = 12 Pretendard}, {@code semibold.font = "Pretendard SemiBold"}가 이 이름을 쓴다.
 *     FlatLaf가 그 이름의 글꼴을 처음 만들 때 등록한다({@link FontUtils#registerFontFamilyLoader}, 편집기 글꼴 {@link EditorFont}와 같은 방식).
 *     Pretendard SemiBold는 별도 패밀리 이름("Pretendard SemiBold")을 가진 파일이라 패밀리마다 로더를 둔다.</li>
 *     <li>Regular와 SemiBold 두 굵기만 넣는다(디자인이 쓰는 굵기). {@code Font.BOLD}를 요청하는 곳은 Java가 Regular를 굵게 만든다.</li>
 *     <li>운영체제에 같은 이름의 글꼴이 이미 설치되어 있으면 등록은 실패하고 설치된 글꼴을 쓴다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: UI 글꼴을 Inter에서 Pretendard로 바꿈 (docs/chromite/design-spec.md "글꼴")
 * - 2026-11-07 UI 글꼴 크기 13 → 12 (사용자 요청 "앱 기본 폰트 사이즈를 한단계 줄인다", design-spec "적용 현황 — UI 글꼴 12")
 */
final class UiFont {

    private static final Log log = Log.getLog(UiFont.class);

    static final String FAMILY = "Pretendard"; //$NON-NLS-1$
    static final String FAMILY_SEMIBOLD = "Pretendard SemiBold"; //$NON-NLS-1$

    private static final String BUNDLED_FONT_PATH = "/fonts/pretendard/"; //$NON-NLS-1$

    private UiFont() {
    }

    /**
     * Registers the bundled Pretendard families to be loaded on first use. Must be called before the look and feel is set up.
     */
    static void installLazy() {
        FontUtils.registerFontFamilyLoader(FAMILY, () -> installBundledFont("Pretendard-Regular.otf")); //$NON-NLS-1$
        FontUtils.registerFontFamilyLoader(FAMILY_SEMIBOLD, () -> installBundledFont("Pretendard-SemiBold.otf")); //$NON-NLS-1$
    }

    private static void installBundledFont(@NotNull String file) {
        URL url = UiFont.class.getResource(BUNDLED_FONT_PATH + file);
        if (url == null) {
            log.warn("Bundled UI font " + file + " not found");
        } else if (!FontUtils.installFont(url)) {
            // Expected where the operating system has its own Pretendard: that one is used
            log.debug("Bundled UI font " + file + " not registered (a font of the same name is installed)");
        }
    }
}
