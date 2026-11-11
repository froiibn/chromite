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

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.formdev.flatlaf.util.FontUtils;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Swing 프런트엔드 테마(라이트/다크)와 그 저장.
 *
 * 설계: 테마는 FlatLaf Look and Feel 하나에 대응한다({@link ChromiteLightLaf}, {@link ChromiteDarkLaf}).
 * 선택한 테마는 이 번들의 설정 저장소({@link ChromiteSwingActivator#getPreferences()}, 작업 공간 {@code .metadata}의
 * 인스턴스 설정)에 저장한다. SWT 버전의 테마 설정과 같은 위치 계열이고, 레지스트리(Java Preferences)를 건드리지 않으며,
 * 작업 공간을 복사한 스모크 테스트가 사용자 설정을 바꾸지 않는다.
 * 실행 중 전환은 {@link FlatLaf#updateUI()}로 열린 모든 창을 다시 그린다.
 * 저장하는 것은 사용자의 선택({@link Choice}: 시스템 설정 따르기·라이트·다크)이고, 설치하는 것은 그 선택이 가리키는 테마다.
 * 저장값이 없으면 시스템 설정 따르기다(앱 변경 #38): 운영체제의 밝은/어두운 설정({@link SystemTheme})을 따르고, 앱이 켜진 동안 바뀌면
 * 따라간다({@link #followSystem()}). 라이트·다크를 직접 고른 사용자의 저장값({@code LIGHT}, {@code DARK})은 그대로 그 테마다.
 * 작업 공간을 잠그기 전(설정을 읽을 수 없을 때)에는 운영체제 설정의 테마를 설치한다. 기본값인 사용자는 메인 창을 열 때 다시 설치하지 않고,
 * 시작 오류·작업 공간 잠금 대화상자도 운영체제와 같은 모습이다.
 * 밀도(촘촘한 모드, {@link UiDensity})는 테마와 따로 저장하고, 설치할 때마다 그 치수 값을 FlatLaf extra defaults로 테마 값 위에 얹는다.
 * 글꼴은 Look and Feel을 설치하기 전에 등록한다: UI 글꼴 Pretendard({@link UiFont}, 번들), 고정폭 JetBrains Mono(번들에 넣은 FlatLaf 글꼴 jar),
 * SQL 편집기 글꼴 Cascadia Mono({@link EditorFont}). 테마 properties의 {@code defaultFont}, {@code semibold.font}, {@code monospaced.font}가 이 이름을 쓴다.
 * UI 글꼴 선택이 시스템 글꼴이면({@link UiFont.Choice}) {@code defaultFont}·{@code semibold.font}를 extra defaults로 바꾼다.
 * 테마 글꼴에 없는 글자는 Java 합성 글꼴이 운영체제 글꼴(맑은 고딕, Apple SD Gothic Neo)로 그린다. 합성 글꼴의 대체 글꼴은
 * 글꼴 객체의 스타일(PLAIN/BOLD)을 따르고 Java에는 semibold 스타일이 없다. 그래서 semibold 글꼴(PLAIN 스타일의 별도 패밀리)에 없는 글자는
 * 보통 굵기로 그려져 옆 글자보다 가늘게 보인다. 화면 언어의 글자가 테마의 semibold 글꼴에 없으면 semibold를 기본 글꼴의 BOLD로 바꿔
 * 대체 글꼴도 굵은 글꼴이 되게 한다(docs/chromite/swing-l10n.md "한글 굵은 글꼴"). Pretendard SemiBold는 한글을 가지므로 영어·한국어 화면에서는
 * 바꾸지 않고, Pretendard에 없는 한자·가나를 쓰는 화면 언어에서만 바꾼다.
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 디자인 적용: Inter·JetBrains Mono 글꼴 등록 (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 편집기 글꼴 Cascadia Mono 등록 (docs/chromite/design-spec.md "편집기 글꼴과 SQL 색")
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 * - 2026-11-06 화면 언어의 글자가 Inter SemiBold에 없으면 semibold를 BOLD로 만들어 한글도 굵은 대체 글꼴로 그림
 * - 2026-11-07 UI 글꼴을 Pretendard로 바꿈(Inter 등록 삭제). semibold 확인은 Inter 고정 이름 대신 설치한 테마의 {@code semibold.font}로 함
 * - 2026-11-07 FlatLaf 탭 넘침 단추의 도구 설명("Show Hidden Tabs")을 화면 언어로 바꿈 (docs/chromite/swing-followup-fixes.md "남은 영어 문구")
 * - 2026-11-08 촘촘한 모드: 설치할 때 {@link UiDensity}의 치수 값을 extra defaults로 넘김 (docs/chromite/design-spec.md "적용 현황 — 촘촘한 모드")
 * - 2026-11-11 테마 선택 {@link Choice}(시스템 설정 따르기·라이트·다크)를 저장하고 기본값을 시스템 설정 따르기로; 운영체제 설정이 바뀌면 따라감;
 *   인스턴스 {@code apply()}는 {@link Choice#apply()}로 바뀌어 지움 (앱 변경 #38, docs/chromite/swing-options.md "테마")
 * - 2026-11-12 설치할 때 UI 글꼴 선택({@link UiFont})의 extra defaults를 얹음 (앱 변경 #43, docs/chromite/design-spec.md "적용 현황 — 글자 선명도와 대비")
 */
enum ChromiteTheme {

    LIGHT(CoreMessages.theme_light, ChromiteLightLaf::new),
    DARK(CoreMessages.theme_dark, ChromiteDarkLaf::new);

    private static final Log log = Log.getLog(ChromiteTheme.class);

    private static final String PREF_THEME = "ui.theme"; //$NON-NLS-1$
    static final String SEMIBOLD_FONT_KEY = "semibold.font"; //$NON-NLS-1$
    /**
     * FlatLaf font value: the default font in the BOLD style, a composite font whose fallback fonts are bold too.
     */
    static final String BOLD_FONT_VALUE = "bold"; //$NON-NLS-1$
    /**
     * Tooltip of the button that lists the tabs scrolled out of a tab strip (documents, tool windows, settings pages).
     */
    private static final String MORE_TABS_TOOLTIP_KEY = "TabbedPane.moreTabsButtonToolTipText"; //$NON-NLS-1$

    private final String label;
    private final Supplier<FlatLaf> lafFactory;

    ChromiteTheme(@NotNull String label, @NotNull Supplier<FlatLaf> lafFactory) {
        this.label = label;
        this.lafFactory = lafFactory;
    }

    @NotNull
    String getLabel() {
        return label;
    }

    /**
     * Installs this theme's look and feel. Must be called on the EDT.
     *
     * @param updateWindows update all open windows (runtime switch); not needed before any window exists
     */
    void install(boolean updateWindows) {
        // The theme properties name the bundled families (defaultFont = Pretendard, semibold.font = Pretendard SemiBold,
        // monospaced.font = JetBrains Mono); FlatLaf loads them on first use. Registering twice is harmless.
        UiFont.installLazy();
        FlatJetBrainsMonoFont.installLazy();
        // SQL editor font (Cascadia Mono, bundled)
        EditorFont.installLazy();
        // The density (compact mode) replaces size values of the theme properties (UiDensity), the UI font choice the
        // theme's Pretendard by the system font (UiFont)
        Map<String, String> extraDefaults = new HashMap<>(UiDensity.getExtraDefaults());
        extraDefaults.putAll(UiFont.getExtraDefaults());
        if (!FlatLaf.setup(createLaf(extraDefaults))) {
            log.warn("Can't install look and feel of theme " + name());
            return;
        }
        if (!isInSemiboldFont(CoreMessages.main_object_explorer)) {
            // The semibold text of this UI language comes from the fallback font, which has the style of the font object:
            // the semibold family is PLAIN, so it would be the regular weight (lighter than the letters beside it).
            // Rare (no UI language needs it now), so the theme is simply set up again with the bold default font.
            extraDefaults.put(SEMIBOLD_FONT_KEY, BOLD_FONT_VALUE);
            if (!FlatLaf.setup(createLaf(extraDefaults))) {
                log.warn("Can't install look and feel of theme " + name());
                return;
            }
        }
        // FlatLaf's own texts come from its resource bundle, which has no Korean translation (de, es, fr only).
        // UIManager.put sets the developer defaults, which win over the look and feel defaults; tab panes read it on install.
        UIManager.put(MORE_TABS_TOOLTIP_KEY, CoreMessages.main_show_hidden_tabs);
        if (updateWindows) {
            FlatLaf.updateUI();
            // Parentless dialogs are owned by the shared root frame, which is not a visible window
            SwingUtilities.updateComponentTreeUI(JOptionPane.getRootFrame());
        }
    }

    @NotNull
    private FlatLaf createLaf(@NotNull Map<String, String> extraDefaults) {
        FlatLaf laf = lafFactory.get();
        if (!extraDefaults.isEmpty()) {
            laf.setExtraDefaults(extraDefaults);
        }
        return laf;
    }

    /**
     * Whether the installed theme's semibold font ({@code semibold.font} in the theme properties) has glyphs for all
     * characters of the text, i.e. the text needs no fallback font.
     *
     * @param text a semibold text of the UI language (a tool window title)
     */
    private static boolean isInSemiboldFont(@NotNull String text) {
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

    /**
     * The theme the operating system is set to ({@link SystemTheme}).
     */
    @NotNull
    static ChromiteTheme forSystem() {
        return SystemTheme.isDark() ? DARK : LIGHT;
    }

    /**
     * The theme of the installed look and feel.
     */
    @NotNull
    static ChromiteTheme getCurrent() {
        return UIManager.getLookAndFeel() instanceof ChromiteDarkLaf ? DARK : LIGHT;
    }

    /**
     * The theme of the saved choice.
     */
    @NotNull
    static ChromiteTheme getSaved() {
        return getSavedChoice().resolve();
    }

    /**
     * The choice saved in the preferences ({@link Choice#SYSTEM} when none is saved).
     */
    @NotNull
    static Choice getSavedChoice() {
        String name = getPreferences().getString(PREF_THEME);
        return CommonUtils.valueOf(Choice.class, name, Choice.SYSTEM);
    }

    /**
     * Follows changes of the operating system setting while the saved choice is {@link Choice#SYSTEM}. Once the
     * preferences can be read; on the EDT.
     */
    static void followSystem() {
        SystemTheme.watch(dark -> {
            ChromiteTheme theme = dark ? DARK : LIGHT;
            if (getSavedChoice() == Choice.SYSTEM && theme != getCurrent()) {
                theme.install(true);
            }
        });
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }

    /**
     * The theme the user chose (View &gt; Theme, Tools &gt; Options), saved by its name. The names {@code LIGHT} and
     * {@code DARK} are those saved before the choice {@code SYSTEM} existed.
     */
    enum Choice {
        SYSTEM(CoreMessages.theme_system, null),
        LIGHT(CoreMessages.theme_light, ChromiteTheme.LIGHT),
        DARK(CoreMessages.theme_dark, ChromiteTheme.DARK);

        private final String label;
        @Nullable
        private final ChromiteTheme theme;

        Choice(@NotNull String label, @Nullable ChromiteTheme theme) {
            this.label = label;
            this.theme = theme;
        }

        @NotNull
        String getLabel() {
            return label;
        }

        /**
         * The theme this choice stands for now.
         */
        @NotNull
        ChromiteTheme resolve() {
            return theme != null ? theme : forSystem();
        }

        /**
         * Saves the choice for the next start and installs its theme at runtime when it is not the installed one.
         */
        void apply() {
            DBPPreferenceStore preferences = getPreferences();
            preferences.setValue(PREF_THEME, name());
            PrefUtils.savePreferenceStore(preferences);
            ChromiteTheme resolved = resolve();
            if (resolved != getCurrent()) {
                resolved.install(true);
            }
        }
    }
}
