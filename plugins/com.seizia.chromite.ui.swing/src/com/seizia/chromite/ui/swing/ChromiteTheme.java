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
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.formdev.flatlaf.util.FontUtils;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.jkiss.code.NotNull;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
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
 * 글꼴(Inter, JetBrains Mono)은 번들에 넣은 FlatLaf 글꼴 jar를 Look and Feel을 설치하기 전에 등록한다
 * (테마 properties의 {@code defaultFont}, {@code monospaced.font}가 이 이름을 쓴다). SQL 편집기 글꼴(Cascadia Mono)도 같은 때 등록한다({@link EditorFont}).
 * 한글 같은, Inter에 없는 글자는 Java 합성 글꼴이 운영체제 글꼴(맑은 고딕, Apple SD Gothic Neo)로 그린다. 합성 글꼴의 대체 글꼴은
 * 글꼴 객체의 스타일(PLAIN/BOLD)을 따르고 Java에는 semibold 스타일이 없다. 그래서 {@code semibold.font = "Inter SemiBold"}(PLAIN)의
 * 한글은 보통 굵기로 그려져 라틴 글자보다 가늘게 보였다. 화면 언어의 글자가 Inter SemiBold에 없으면 semibold를 기본 글꼴의 BOLD로 바꿔
 * 대체 글꼴도 굵은 글꼴(맑은 고딕 Bold)이 되게 한다. 영어 화면은 지금처럼 Inter SemiBold를 쓴다(docs/chromite/swing-l10n.md "한글 굵은 글꼴").
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 디자인 적용: Inter·JetBrains Mono 글꼴 등록 (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 편집기 글꼴 Cascadia Mono 등록 (docs/chromite/design-spec.md "편집기 글꼴과 SQL 색")
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 * - 2026-11-06 화면 언어의 글자가 Inter SemiBold에 없으면 semibold를 BOLD로 만들어 한글도 굵은 대체 글꼴로 그림
 * - 2026-11-07 FlatLaf 탭 넘침 단추의 도구 설명("Show Hidden Tabs")을 화면 언어로 바꿈 (docs/chromite/swing-followup-fixes.md "남은 영어 문구")
 */
enum ChromiteTheme {

    LIGHT(CoreMessages.theme_light, ChromiteLightLaf::new),
    DARK(CoreMessages.theme_dark, ChromiteDarkLaf::new);

    private static final Log log = Log.getLog(ChromiteTheme.class);

    private static final String PREF_THEME = "ui.theme"; //$NON-NLS-1$
    private static final String SEMIBOLD_FONT_KEY = "semibold.font"; //$NON-NLS-1$
    /**
     * FlatLaf font value: the default font (Inter) in the BOLD style, a composite font whose fallback fonts are bold too.
     */
    private static final String BOLD_FONT_VALUE = "bold"; //$NON-NLS-1$
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
        // The theme properties name the bundled families (defaultFont = Inter, monospaced.font = JetBrains Mono);
        // FlatLaf loads them on first use. Registering twice is harmless.
        FlatInterFont.installLazy();
        FlatJetBrainsMonoFont.installLazy();
        // SQL editor font (Cascadia Mono, bundled)
        EditorFont.installLazy();
        FlatLaf laf = lafFactory.get();
        if (!isInSemiboldFont(CoreMessages.main_object_explorer)) {
            // The semibold text of this UI language comes from the fallback font, which has the style of the font object:
            // "Inter SemiBold" is PLAIN, so it would be the regular weight (lighter than the Latin letters beside it)
            laf.setExtraDefaults(Map.of(SEMIBOLD_FONT_KEY, BOLD_FONT_VALUE));
        }
        if (!FlatLaf.setup(laf)) {
            log.warn("Can't install look and feel of theme " + name());
            return;
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

    /**
     * Whether the theme's semibold family ({@code semibold.font} in the theme properties) has glyphs for all characters
     * of the text, i.e. the text needs no fallback font.
     *
     * @param text a semibold text of the UI language (a tool window title)
     */
    private static boolean isInSemiboldFont(@NotNull String text) {
        FontUtils.loadFontFamily(FlatInterFont.FAMILY_SEMIBOLD);
        // A physical font (not a composite one): canDisplayUpTo() sees only its own glyphs
        return new Font(FlatInterFont.FAMILY_SEMIBOLD, Font.PLAIN, 1).canDisplayUpTo(text) < 0;
    }

    /**
     * The theme of the installed look and feel.
     */
    @NotNull
    static ChromiteTheme getCurrent() {
        return UIManager.getLookAndFeel() instanceof ChromiteDarkLaf ? DARK : LIGHT;
    }

    /**
     * The theme saved in the preferences (light by default).
     */
    @NotNull
    static ChromiteTheme getSaved() {
        String name = getPreferences().getString(PREF_THEME);
        return CommonUtils.valueOf(ChromiteTheme.class, name, LIGHT);
    }

    /**
     * Installs the theme at runtime and saves it for the next start.
     */
    void apply() {
        install(true);
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_THEME, name());
        PrefUtils.savePreferenceStore(preferences);
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }
}
