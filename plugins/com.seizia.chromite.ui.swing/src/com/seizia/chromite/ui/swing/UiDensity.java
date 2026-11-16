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

import com.seizia.chromite.Log;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.code.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 화면 밀도: 기본 밀도 B와 촘촘한 모드 C(View &gt; Compact Mode)와 그 저장.
 *
 * 설계:
 * <ul>
 *     <li>밀도를 정하는 값은 모두 테마 properties의 "Sizes" 절(FlatLaf 키와 {@link UiSizes}가 읽는 {@code Chromite.size.*} 키)에 있다.
 *     촘촘한 모드는 그중 바뀌는 키만 {@code ChromiteCompact.properties}에 두고, 테마를 설치할 때 FlatLaf extra defaults로 넘긴다
 *     ({@link ChromiteTheme#install}). extra defaults는 테마 properties와 같은 방식으로 해석되고(변수 {@code @menuItemMargin}도 덮어씀)
 *     테마 값보다 우선한다. 그래서 두 테마가 같은 C 값을 쓰고, 컴포넌트 코드에는 밀도 값이 없다.</li>
 *     <li>바꿀 때는 테마 전환과 같이 Look and Feel을 다시 설치하고 모든 창을 다시 그린다(다시 시작하지 않음). 컴포넌트를 만들 때 한 번만
 *     읽던 치수(도구 막대 여백, Properties 행, 쿼리 상태 줄 칸, 편집기 도구 막대의 선택 상자, 글자 단추 여백, 결과 그리드의 행 번호와
 *     열 폭)는 그릴 때나 UI가 바뀔 때 다시 읽게 했다.</li>
 *     <li>저장 위치는 테마와 같다({@link ChromiteTheme}). 처음 설치할 때(작업 공간을 잠그기 전)는 설정을 읽을 수 없어 기본 밀도로 설치하고,
 *     주 창을 열기 전에 저장된 밀도가 다르면 다시 설치한다({@code ChromiteSwingApplication#openMainWindow}).</li>
 *     <li>대화상자의 입력·선택 상자(size.control 28)와 제목 표시줄(40)은 바꾸지 않는다: 시안 C도 도구 창·도구 막대·행만 줄였다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: View &gt; Compact Mode (docs/chromite/design-spec.md "적용 현황 — 촘촘한 모드")
 */
final class UiDensity {

    private static final Log log = Log.getLog(UiDensity.class);

    private static final String PREF_COMPACT = "ui.compactMode"; //$NON-NLS-1$
    private static final String COMPACT_DEFAULTS = "ChromiteCompact.properties"; //$NON-NLS-1$

    /**
     * The density of the installed look and feel (the next {@link ChromiteTheme#install} uses it).
     */
    private static volatile boolean compact;
    private static Map<String, String> compactDefaults;

    private UiDensity() {
    }

    /**
     * Whether the installed look and feel (or the next one installed) is in compact mode.
     */
    static boolean isCompact() {
        return compact;
    }

    /**
     * The density saved in the preferences (density B, not compact, by default).
     */
    static boolean isSavedCompact() {
        return getPreferences().getBoolean(PREF_COMPACT);
    }

    /**
     * Sets the density for the next {@link ChromiteTheme#install}, without installing or saving it.
     */
    static void setCompact(boolean compact) {
        UiDensity.compact = compact;
    }

    /**
     * Switches the density at runtime: installs the current theme again, which updates every window, and saves the
     * density for the next start. Must be called on the EDT.
     */
    static void apply(boolean compact) {
        setCompact(compact);
        ChromiteTheme.getCurrent().install(true);
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_COMPACT, compact);
        PrefUtils.savePreferenceStore(preferences);
    }

    /**
     * FlatLaf extra defaults of the density: the compact values, or none for the default density B (the theme values).
     */
    @NotNull
    static synchronized Map<String, String> getExtraDefaults() {
        if (!compact) {
            return Map.of();
        }
        if (compactDefaults == null) {
            compactDefaults = loadCompactDefaults();
        }
        return compactDefaults;
    }

    @NotNull
    private static Map<String, String> loadCompactDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        try (InputStream in = UiDensity.class.getResourceAsStream(COMPACT_DEFAULTS)) {
            if (in == null) {
                log.warn("Compact mode values not found: " + COMPACT_DEFAULTS);
                return defaults;
            }
            Properties properties = new Properties();
            properties.load(in);
            for (String key : properties.stringPropertyNames()) {
                defaults.put(key, properties.getProperty(key));
            }
        } catch (IOException e) {
            log.warn("Can't read compact mode values " + COMPACT_DEFAULTS, e);
        }
        return defaults;
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }
}
