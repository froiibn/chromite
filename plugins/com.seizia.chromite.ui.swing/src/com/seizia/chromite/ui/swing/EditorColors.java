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

import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * SQL 편집기 색(Tools &gt; Options &gt; Editor Colors): 테마마다 항목별 색과 굵게·기울임, 그 저장과 적용.
 *
 * 설계:
 * <ul>
 *     <li>기본값은 테마 XML({@code SqlEditorLight.xml}, {@code SqlEditorDark.xml}) 하나뿐이다. 라이트는 SSMS 기본 색, 다크는
 *     SSMS 다크를 맞춘 값이다(design-spec "SQL 구문 색"). 사용자가 바꾼 항목만 설정에 저장하고, 편집기에 적용할 때 XML을 읽은
 *     {@link Theme}에 덮어쓴다. "Reset to Defaults"(SSMS 기본 색으로 되돌림)는 저장한 값을 지운다.</li>
 *     <li>항목은 {@link SqlTokenClassifier}와 RSyntaxTextArea SQL 토크나이저가 실제로 내는 토큰 종류만 묶는다. 표·열·스키마 이름 색은
 *     의미 분석이 필요해서(SWT 편집기의 semantic 색) 아직 없다.</li>
 *     <li>설정 키: {@code sqlEditor.color.<light|dark>.<항목>} = {@code rrggbb[,bold][,italic]}. 테마와 같은 번들 설정 저장소에 둔다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 사용자 편집기 색(SWT의 Colors and Fonts &gt; SQL Editor에 해당) (docs/chromite/design-spec.md "편집기 글꼴과 SQL 색")
 */
final class EditorColors {

    private static final Log log = Log.getLog(EditorColors.class);

    private static final String PREF_PREFIX = "sqlEditor.color."; //$NON-NLS-1$
    private static final String BOLD = "bold"; //$NON-NLS-1$
    private static final String ITALIC = "italic"; //$NON-NLS-1$

    /**
     * A customizable color. Token items list the token types they color; the first one holds the default.
     */
    enum Item {
        TEXT("Text (identifiers)", TokenTypes.IDENTIFIER, TokenTypes.LITERAL_BACKQUOTE, TokenTypes.PREPROCESSOR,
            TokenTypes.ANNOTATION, TokenTypes.ERROR_IDENTIFIER, TokenTypes.ERROR_NUMBER_FORMAT, TokenTypes.ERROR_STRING_DOUBLE,
            TokenTypes.ERROR_CHAR),
        KEYWORD("Keyword", TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2, TokenTypes.LITERAL_BOOLEAN),
        OPERATOR("Operator (AND, OR, IS, NULL, punctuation)", TokenTypes.OPERATOR, TokenTypes.SEPARATOR),
        FUNCTION("Function (incl. date parts, @@ variables)", TokenTypes.FUNCTION),
        STRING("String", TokenTypes.LITERAL_CHAR, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE),
        NUMBER("Number", TokenTypes.LITERAL_NUMBER_DECIMAL_INT, TokenTypes.LITERAL_NUMBER_FLOAT,
            TokenTypes.LITERAL_NUMBER_HEXADECIMAL),
        COMMENT("Comment", TokenTypes.COMMENT_EOL, TokenTypes.COMMENT_MULTILINE, TokenTypes.COMMENT_DOCUMENTATION,
            TokenTypes.COMMENT_KEYWORD, TokenTypes.COMMENT_MARKUP),
        DATA_TYPE("Data type", TokenTypes.DATA_TYPE),
        VARIABLE("Variable / parameter (@x, :x, ?)", TokenTypes.VARIABLE),
        BACKGROUND("Background"),
        CURRENT_LINE("Current line"),
        SELECTION("Selection"),
        LINE_NUMBER("Line numbers"),
        CURRENT_LINE_NUMBER("Current line number");

        private final String label;
        private final int[] tokenTypes;

        Item(@NotNull String label, int... tokenTypes) {
            this.label = label;
            this.tokenTypes = tokenTypes;
        }

        @NotNull
        String getLabel() {
            return label;
        }

        /**
         * Token items can be bold or italic; editor items are only a color.
         */
        boolean isToken() {
            return tokenTypes.length > 0;
        }

        @NotNull
        private String getKey(@NotNull ChromiteTheme theme) {
            return PREF_PREFIX + theme.name().toLowerCase(Locale.ROOT) + "." + name().toLowerCase(Locale.ROOT); //$NON-NLS-1$
        }
    }

    /**
     * Color, bold and italic of one item.
     */
    record Setting(@NotNull Color color, boolean bold, boolean italic) {
        @NotNull
        private String format() {
            String value = String.format("%06x", color.getRGB() & 0xFFFFFF); //$NON-NLS-1$
            return value + (bold ? "," + BOLD : "") + (italic ? "," + ITALIC : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        @Nullable
        private static Setting parse(@NotNull String value) {
            String[] parts = value.split(","); //$NON-NLS-1$
            try {
                Color color = new Color(Integer.parseInt(parts[0].trim(), 16));
                boolean bold = false;
                boolean italic = false;
                for (int i = 1; i < parts.length; i++) {
                    bold |= BOLD.equals(parts[i].trim());
                    italic |= ITALIC.equals(parts[i].trim());
                }
                return new Setting(color, bold, italic);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private EditorColors() {
    }

    /**
     * The colors of the theme XML, without the user's changes.
     */
    @NotNull
    static Map<Item, Setting> getDefaults(@NotNull ChromiteTheme theme) {
        Theme defaults = loadTheme(theme, null);
        Map<Item, Setting> result = new EnumMap<>(Item.class);
        for (Item item : Item.values()) {
            result.put(item, readSetting(defaults, item));
        }
        return result;
    }

    /**
     * The colors in use: the defaults with the saved changes.
     */
    @NotNull
    static Map<Item, Setting> getSaved(@NotNull ChromiteTheme theme) {
        Map<Item, Setting> result = getDefaults(theme);
        DBPPreferenceStore preferences = getPreferences();
        for (Item item : Item.values()) {
            String value = preferences.getString(item.getKey(theme));
            Setting setting = CommonUtils.isEmpty(value) ? null : Setting.parse(value);
            if (setting != null) {
                result.put(item, setting);
            }
        }
        return result;
    }

    /**
     * Saves the colors of a theme; items equal to the default are removed from the preferences.
     * Callers apply them to the open editors ({@link SqlEditorPanel.SqlTextArea#applyThemeToAll()}).
     */
    static void save(@NotNull ChromiteTheme theme, @NotNull Map<Item, Setting> settings) {
        Map<Item, Setting> defaults = getDefaults(theme);
        DBPPreferenceStore preferences = getPreferences();
        for (Item item : Item.values()) {
            Setting setting = settings.get(item);
            if (setting == null || setting.equals(defaults.get(item))) {
                preferences.setToDefault(item.getKey(theme));
            } else {
                preferences.setValue(item.getKey(theme), setting.format());
            }
        }
        PrefUtils.savePreferenceStore(preferences);
    }

    /**
     * The theme XML with the given colors, its styles derived from the editor font.
     *
     * @param settings colors to apply, or null for the XML values
     */
    @NotNull
    static Theme createTheme(@NotNull ChromiteTheme theme, @Nullable Map<Item, Setting> settings, @Nullable Font font) {
        Theme result = loadTheme(theme, font);
        if (settings != null) {
            for (Map.Entry<Item, Setting> entry : settings.entrySet()) {
                writeSetting(result, entry.getKey(), entry.getValue(), font);
            }
        }
        return result;
    }

    @NotNull
    private static Theme loadTheme(@NotNull ChromiteTheme theme, @Nullable Font font) {
        String resource = theme == ChromiteTheme.DARK ? "SqlEditorDark.xml" : "SqlEditorLight.xml"; //$NON-NLS-1$ //$NON-NLS-2$
        try (InputStream in = EditorColors.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("SQL editor theme " + resource + " not found");
            }
            return Theme.load(in, font);
        } catch (IOException e) {
            // The XML is part of this bundle: a failure here is a packaging error
            log.error("Can't load SQL editor theme " + resource, e);
            throw new IllegalStateException(e);
        }
    }

    @NotNull
    private static Setting readSetting(@NotNull Theme theme, @NotNull Item item) {
        if (item.isToken()) {
            Style style = theme.scheme.getStyle(item.tokenTypes[0]);
            Font font = style.font;
            return new Setting(style.foreground, font != null && font.isBold(), font != null && font.isItalic());
        }
        Color color = switch (item) {
            case BACKGROUND -> theme.bgColor;
            case CURRENT_LINE -> theme.currentLineHighlight;
            case SELECTION -> theme.selectionBG;
            case LINE_NUMBER -> theme.lineNumberColor;
            case CURRENT_LINE_NUMBER -> theme.currentLineNumberColor;
            default -> throw new IllegalArgumentException(item.name());
        };
        return new Setting(color, false, false);
    }

    private static void writeSetting(@NotNull Theme theme, @NotNull Item item, @NotNull Setting setting, @Nullable Font font) {
        if (item.isToken()) {
            int fontStyle = (setting.bold() ? Font.BOLD : 0) | (setting.italic() ? Font.ITALIC : 0);
            for (int tokenType : item.tokenTypes) {
                Style style = theme.scheme.getStyle(tokenType);
                style.foreground = setting.color();
                // No font: the token uses the text area's font (plain)
                style.font = fontStyle == Font.PLAIN || font == null ? null : font.deriveFont(fontStyle);
            }
            return;
        }
        Color color = setting.color();
        switch (item) {
            case BACKGROUND -> {
                // The gutter and its border are the editor background (Islands: no line between them)
                theme.bgColor = color;
                theme.gutterBackgroundColor = color;
                theme.gutterBorderColor = color;
            }
            case CURRENT_LINE -> theme.currentLineHighlight = color;
            case SELECTION -> theme.selectionBG = color;
            case LINE_NUMBER -> theme.lineNumberColor = color;
            case CURRENT_LINE_NUMBER -> theme.currentLineNumberColor = color;
            default -> throw new IllegalArgumentException(item.name());
        }
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }
}
