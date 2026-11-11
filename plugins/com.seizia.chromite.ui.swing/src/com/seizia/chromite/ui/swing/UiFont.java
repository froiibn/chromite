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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.code.NotNull;
import org.jkiss.utils.CommonUtils;

import java.awt.*;
import java.net.URL;
import java.util.Map;

/**
 * UI 글꼴: 번들 Pretendard의 등록과 UI 글꼴 선택(Tools &gt; Options &gt; General의 UI 글꼴)과 그 저장.
 *
 * 설계:
 * <ul>
 *     <li>테마의 UI 글꼴은 Pretendard 12다(사용자 결정 2026-11-07: Pretendard 13으로 정한 뒤 "한단계 줄인다", docs/chromite/design-spec.md "글꼴 토큰"). 한글과 라틴 글자를 한 글꼴이 그려
 *     한글이 운영체제 글꼴(맑은 고딕)로 대체되지 않는다. SIL OFL 1.1이라 번들({@code fonts/pretendard})에 넣었다.</li>
 *     <li>테마 properties의 {@code defaultFont = 12 Pretendard}, {@code semibold.font = "Pretendard SemiBold"}가 이 이름을 쓴다.
 *     FlatLaf가 그 이름의 글꼴을 처음 만들 때 등록한다({@link FontUtils#registerFontFamilyLoader}, 편집기 글꼴 {@link EditorFont}와 같은 방식).
 *     Pretendard SemiBold는 별도 패밀리 이름("Pretendard SemiBold")을 가진 파일이라 패밀리마다 로더를 둔다.</li>
 *     <li>Regular와 SemiBold 두 굵기만 넣는다(디자인이 쓰는 굵기). {@code Font.BOLD}를 요청하는 곳은 Java가 Regular를 굵게 만든다.</li>
 *     <li>운영체제에 같은 이름의 글꼴이 이미 설치되어 있으면 등록은 실패하고 설치된 글꼴을 쓴다.</li>
 *     <li>UI 글꼴 선택({@link Choice}, 앱 변경 #43 2026-11-12): 자동(기본값)·Pretendard·시스템 글꼴. 번들 글꼴(파일에서 등록한 글꼴)은 Java가
 *     FreeType으로 그리고, 설치된 TrueType 글꼴은 ClearType일 때 Windows GDI가 그린다(JDK {@code FileFontStrike}의 native 경로). 100% 배율의
 *     12px에서 Pretendard(CFF 윤곽, FreeType)는 획이 두 화소에 걸쳐 흐리고, 맑은 고딕은 Windows 기본 앱과 같은 화소로 그려진다(측정:
 *     design-spec "적용 현황 — 글자 선명도와 대비"). 150%(18px)에서는 차이가 작다. 그래서 자동은 기본 화면 배율이 150% 미만이면 시스템 글꼴,
 *     그 이상이면 Pretendard다(사용자가 Pretendard를 고른 화면은 150%였다). 시스템 글꼴은 FlatLaf의 운영체제 UI 글꼴(Windows는
 *     {@code win.messagebox.font}: 한국어 Windows는 맑은 고딕, 그 밖에는 Segoe UI)이고 크기는 테마와 같은 12, semibold는 그 글꼴의 BOLD다.</li>
 *     <li>선택은 테마 설치 때 FlatLaf extra defaults로 넘긴다({@link #getExtraDefaults()}, 밀도 {@link UiDensity}와 같은 방식). 저장 위치는
 *     테마와 같고, 처음 설치(작업 공간을 잠그기 전)는 자동으로 하고 주 창을 열기 전에 저장값이 다르면 다시 설치한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: UI 글꼴을 Inter에서 Pretendard로 바꿈 (docs/chromite/design-spec.md "글꼴")
 * - 2026-11-07 UI 글꼴 크기 13 → 12 (사용자 요청 "앱 기본 폰트 사이즈를 한단계 줄인다", design-spec "적용 현황 — UI 글꼴 12")
 * - 2026-11-12 UI 글꼴 선택(자동·Pretendard·시스템 글꼴), 기본값 자동: 100% 배율 화면에서 글자가 흐림(앱 변경 #43)
 *   (docs/chromite/design-spec.md "적용 현황 — 글자 선명도와 대비")
 */
final class UiFont {

    private static final Log log = Log.getLog(UiFont.class);

    static final String FAMILY = "Pretendard"; //$NON-NLS-1$
    static final String FAMILY_SEMIBOLD = "Pretendard SemiBold"; //$NON-NLS-1$

    private static final String BUNDLED_FONT_PATH = "/fonts/pretendard/"; //$NON-NLS-1$

    private static final String PREF_UI_FONT = "ui.font"; //$NON-NLS-1$
    private static final String DEFAULT_FONT_KEY = "defaultFont"; //$NON-NLS-1$
    /**
     * FlatLaf font value of the system font: a size without a family keeps the operating system's UI font. The size is
     * that of {@code font.ui} ({@code defaultFont = 12 Pretendard} in the theme properties).
     */
    private static final String SYSTEM_FONT_VALUE = "12"; //$NON-NLS-1$
    /**
     * {@link Choice#AUTO} takes the system font below this scale of the default screen (100%, 125%).
     */
    private static final double AUTO_PRETENDARD_MIN_SCALE = 1.5;

    /**
     * The choice of the installed look and feel (the next {@link ChromiteTheme#install} uses it).
     */
    private static volatile Choice choice = Choice.AUTO;

    private UiFont() {
    }

    /**
     * The choice the installed look and feel (or the next one installed) uses.
     */
    @NotNull
    static Choice getChoice() {
        return choice;
    }

    /**
     * Sets the choice for the next {@link ChromiteTheme#install}, without installing or saving it.
     */
    static void setChoice(@NotNull Choice choice) {
        UiFont.choice = choice;
    }

    /**
     * The choice saved in the preferences ({@link Choice#AUTO} when none is saved).
     */
    @NotNull
    static Choice getSavedChoice() {
        return CommonUtils.valueOf(Choice.class, getPreferences().getString(PREF_UI_FONT), Choice.AUTO);
    }

    /**
     * FlatLaf extra defaults of the choice: the system font, or none for Pretendard (the theme values).
     */
    @NotNull
    static Map<String, String> getExtraDefaults() {
        if (!choice.usesSystemFont()) {
            return Map.of();
        }
        return Map.of(DEFAULT_FONT_KEY, SYSTEM_FONT_VALUE, ChromiteTheme.SEMIBOLD_FONT_KEY, ChromiteTheme.BOLD_FONT_VALUE);
    }

    /**
     * Whether the default screen is scaled below {@link #AUTO_PRETENDARD_MIN_SCALE}.
     */
    private static boolean isLowDensityScreen() {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        GraphicsConfiguration configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        return configuration.getDefaultTransform().getScaleX() < AUTO_PRETENDARD_MIN_SCALE;
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }

    /**
     * The UI font the user chose (Tools &gt; Options), saved by its name.
     */
    enum Choice {
        AUTO(CoreMessages.ui_font_auto),
        PRETENDARD(FAMILY),
        SYSTEM(CoreMessages.ui_font_system);

        private final String label;

        Choice(@NotNull String label) {
            this.label = label;
        }

        @NotNull
        String getLabel() {
            return label;
        }

        /**
         * Whether this choice draws the UI in the operating system's font now (for {@link #AUTO}: on the default screen's
         * scale).
         */
        boolean usesSystemFont() {
            return this == SYSTEM || this == AUTO && isLowDensityScreen();
        }

        /**
         * Saves the choice for the next start and installs the current theme again when the font changes. On the EDT.
         */
        void apply() {
            DBPPreferenceStore preferences = getPreferences();
            preferences.setValue(PREF_UI_FONT, name());
            PrefUtils.savePreferenceStore(preferences);
            boolean changed = usesSystemFont() != choice.usesSystemFont();
            choice = this;
            if (changed) {
                ChromiteTheme.getCurrent().install(true);
            }
        }
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
