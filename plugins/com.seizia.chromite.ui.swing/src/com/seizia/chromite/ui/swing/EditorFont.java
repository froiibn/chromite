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

import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import com.formdev.flatlaf.util.FontUtils;
import com.formdev.flatlaf.util.UIScale;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * SQL 편집기 글꼴(Tools &gt; Options &gt; Editor): 글꼴 이름과 크기, 그 저장과 기본값.
 *
 * 설계:
 * <ul>
 *     <li>기본 글꼴은 Cascadia Mono 13이다(사용자 결정 2026-11-06). SIL OFL 1.1이라 번들({@code fonts/cascadia-mono})에 넣었다.
 *     FlatLaf의 글꼴 jar와 같은 방식으로, 처음 쓸 때 등록한다({@link FontUtils#registerFontFamilyLoader}).
 *     운영체제에 같은 이름의 글꼴이 이미 있으면(Windows 11의 가변 글꼴 {@code CascadiaMono.ttf}) 등록은 실패하고 운영체제 글꼴을 쓴다.
 *     번들 글꼴을 찾지 못하면 JetBrains Mono(번들)로 대체한다.</li>
 *     <li>사용자가 고른 글꼴이 이 컴퓨터에 없으면(설정을 다른 컴퓨터로 옮김) 기본 글꼴을 쓴다. Lucida Sans Typewriter 같은
 *     설치된 고정폭 글꼴도 고를 수 있지만 번들에는 넣지 않는다(재배포할 수 없는 글꼴).</li>
 *     <li>글꼴은 합성 글꼴({@link FontUtils#getCompositeFont})로 만든다. 고정폭 글꼴에 없는 한글은 운영체제 글꼴
 *     (Windows: 맑은 고딕, macOS: Apple SD Gothic Neo)로 그린다. 편집기 행 높이는 글꼴에서 한 번 정해지므로 한글이 있는 줄도 높이가 같다.</li>
 *     <li>설정은 테마와 같은 번들 설정 저장소에 둔다({@link ChromiteSwingActivator#getPreferences()}).
 *     결과 그리드와 Messages는 Look and Feel의 {@code monospaced.font}(JetBrains Mono)를 그대로 쓴다(design-spec "글꼴 토큰").</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 편집기 글꼴 Cascadia Mono 13(번들), Options의 글꼴·크기 설정 (docs/chromite/design-spec.md "편집기 글꼴과 SQL 색")
 */
final class EditorFont {

    private static final Log log = Log.getLog(EditorFont.class);

    static final String DEFAULT_FAMILY = "Cascadia Mono"; //$NON-NLS-1$
    static final int DEFAULT_SIZE = 13;
    static final int MIN_SIZE = 6;
    static final int MAX_SIZE = 72;

    private static final String PREF_FAMILY = "sqlEditor.font.family"; //$NON-NLS-1$
    private static final String PREF_SIZE = "sqlEditor.font.size"; //$NON-NLS-1$

    private static final String BUNDLED_FONT_PATH = "/fonts/cascadia-mono/"; //$NON-NLS-1$
    private static final String[] BUNDLED_FONT_FILES = {
        "CascadiaMono-Regular.ttf", "CascadiaMono-Bold.ttf", "CascadiaMono-Italic.ttf", "CascadiaMono-BoldItalic.ttf" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    };
    /**
     * Java's logical families: not real fonts, so not offered in the family list.
     */
    private static final Set<String> LOGICAL_FAMILIES = Set.of(
        Font.DIALOG, Font.DIALOG_INPUT, Font.MONOSPACED, Font.SANS_SERIF, Font.SERIF);

    // EDT only
    @Nullable
    private static Font current;

    private EditorFont() {
    }

    /**
     * Registers the bundled Cascadia Mono to be loaded on first use. Must be called before the first editor font is created.
     */
    static void installLazy() {
        FontUtils.registerFontFamilyLoader(DEFAULT_FAMILY, EditorFont::installBundledFont);
    }

    private static void installBundledFont() {
        for (String file : BUNDLED_FONT_FILES) {
            URL url = EditorFont.class.getResource(BUNDLED_FONT_PATH + file);
            if (url == null) {
                log.warn("Bundled editor font " + file + " not found");
            } else if (!FontUtils.installFont(url)) {
                // Expected where the operating system has its own Cascadia Mono (Windows 11): that one is used
                log.debug("Bundled editor font " + file + " not registered (a font of the same name is installed)");
            }
        }
    }

    /**
     * The editor font from the preferences (composite font, size scaled like the look and feel's fonts).
     */
    @NotNull
    static Font get() {
        if (current == null) {
            current = create(getSavedFamily(), getSavedSize());
            log.info("SQL editor font: " + current.getFamily() + " " + getSavedSize()
                + " (font " + current.getFontName() + ", requested " + getSavedFamily() + ")");
        }
        return current;
    }

    /**
     * Saves the font. Callers apply it to the open editors ({@link SqlEditorPanel.SqlTextArea#applyThemeToAll()}).
     */
    static void save(@NotNull String family, int size) {
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_FAMILY, family);
        preferences.setValue(PREF_SIZE, size);
        PrefUtils.savePreferenceStore(preferences);
        current = null;
    }

    /**
     * The saved family, or the default; a saved family that is not installed here falls back to the default too.
     */
    @NotNull
    static String getSavedFamily() {
        String family = getPreferences().getString(PREF_FAMILY);
        return !CommonUtils.isEmpty(family) && isAvailable(family) ? family : getDefaultFamily();
    }

    static int getSavedSize() {
        int size = getPreferences().getInt(PREF_SIZE);
        return size <= 0 ? DEFAULT_SIZE : Math.clamp(size, MIN_SIZE, MAX_SIZE);
    }

    /**
     * Cascadia Mono (bundled), or JetBrains Mono (bundled with FlatLaf) when the bundled font is missing.
     */
    @NotNull
    static String getDefaultFamily() {
        return isAvailable(DEFAULT_FAMILY) ? DEFAULT_FAMILY : FlatJetBrainsMonoFont.FAMILY;
    }

    /**
     * Composite font of the family, so that characters the family lacks (Hangul) come from the operating system fonts.
     *
     * @param size size in points before UI scaling
     */
    @NotNull
    static Font create(@NotNull String family, int size) {
        return FontUtils.getCompositeFont(family, Font.PLAIN, Math.round(UIScale.scale((float) size)));
    }

    /**
     * Installed fixed-width families (Latin letters of equal width), and the bundled ones, sorted by name.
     */
    @NotNull
    static List<String> getMonospacedFamilies() {
        Set<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        result.add(DEFAULT_FAMILY);
        result.add(FlatJetBrainsMonoFont.FAMILY);
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
                // '@' families are the vertical variants of CJK fonts on Windows
                if (family.startsWith("@") || family.startsWith(".") || LOGICAL_FAMILIES.contains(family)) { //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }
                Font font = new Font(family, Font.PLAIN, 13);
                if (font.canDisplayUpTo("iWm0.") == -1 && isFixedWidth(g.getFontMetrics(font))) { //$NON-NLS-1$
                    result.add(family);
                }
            }
        } finally {
            g.dispose();
        }
        return new ArrayList<>(result);
    }

    private static boolean isFixedWidth(@NotNull FontMetrics metrics) {
        int width = metrics.charWidth('i');
        return width > 0 && metrics.charWidth('W') == width && metrics.charWidth('m') == width && metrics.charWidth('.') == width;
    }

    private static boolean isAvailable(@NotNull String family) {
        // Includes the lazily loaded bundled families
        return Arrays.stream(FontUtils.getAvailableFontFamilyNames()).anyMatch(family::equalsIgnoreCase);
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }
}
