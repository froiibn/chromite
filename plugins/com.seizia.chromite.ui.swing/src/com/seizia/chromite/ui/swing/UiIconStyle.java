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
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import java.awt.*;

/**
 * 아이콘 팩(View > Theme > Icon Style)과 그 저장.
 *
 * 설계: 아이콘 팩은 {@code icons/ui/<folder>/<light|dark>/<id>.svg} 묶음 하나다({@link UiIcons}, 생성기 {@code tools/branding/gen_ui_icons.py}).
 * {@link UiIcons}의 아이콘은 그릴 때마다 현재 팩과 테마의 SVG를 고르므로, 바꿀 때는 값을 바꾸고 모든 창을 다시 그리기만 하면 된다
 * (Look and Feel을 다시 설치할 필요가 없다). 저장 위치는 테마와 같다({@link ChromiteTheme}).
 *
 * 변경 이력:
 * - 2026-11-06 디자인 적용: 최초 작성 (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 화면 이름에서 다른 제품 이름을 뺌: "Duo-tone", "Full color" (docs/chromite/design-spec.md "적용 현황 — 아이콘 팩")
 */
enum UiIconStyle {

    // Labels name no other product (DataGrip-like duo-tone, SSMS-like color): they are shown in menus and Options
    DUOTONE("Duo-tone", "duotone"), //$NON-NLS-2$
    COLOR("Full color", "color"); //$NON-NLS-2$

    private static final String PREF_ICON_STYLE = "ui.iconStyle"; //$NON-NLS-1$

    private static volatile UiIconStyle current;

    private final String label;
    private final String folder;

    UiIconStyle(@NotNull String label, @NotNull String folder) {
        this.label = label;
        this.folder = folder;
    }

    @NotNull
    String getLabel() {
        return label;
    }

    /**
     * Folder of the pack under {@code icons/ui}.
     */
    @NotNull
    String getFolder() {
        return folder;
    }

    /**
     * The pack in use: the saved one until {@link #apply()} changes it (duo-tone by default).
     */
    @NotNull
    static UiIconStyle getCurrent() {
        UiIconStyle style = current;
        if (style == null) {
            String name = getPreferences().getString(PREF_ICON_STYLE);
            style = CommonUtils.valueOf(UiIconStyle.class, name, DUOTONE);
            current = style;
        }
        return style;
    }

    /**
     * Uses this pack at runtime, saves it for the next start and repaints every window. Must be called on the EDT.
     */
    void apply() {
        current = this;
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_ICON_STYLE, name());
        PrefUtils.savePreferenceStore(preferences);
        for (Window window : Window.getWindows()) {
            window.repaint();
        }
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }
}
