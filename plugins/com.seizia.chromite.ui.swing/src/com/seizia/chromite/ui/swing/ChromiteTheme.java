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
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
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
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 디자인 적용: Inter·JetBrains Mono 글꼴 등록 (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 편집기 글꼴 Cascadia Mono 등록 (docs/chromite/design-spec.md "편집기 글꼴과 SQL 색")
 */
enum ChromiteTheme {

    LIGHT("Light", ChromiteLightLaf::new),
    DARK("Dark", ChromiteDarkLaf::new);

    private static final Log log = Log.getLog(ChromiteTheme.class);

    private static final String PREF_THEME = "ui.theme"; //$NON-NLS-1$

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
        if (!FlatLaf.setup(lafFactory.get())) {
            log.warn("Can't install look and feel of theme " + name());
            return;
        }
        if (updateWindows) {
            FlatLaf.updateUI();
            // Parentless dialogs are owned by the shared root frame, which is not a visible window
            SwingUtilities.updateComponentTreeUI(JOptionPane.getRootFrame());
        }
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
