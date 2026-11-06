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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPConnectionType;
import org.jkiss.utils.CommonUtils;

import java.awt.*;

/**
 * 연결 색(SSMS "Use custom color": 쿼리 창 상태 줄 색). 연결에 정한 색, 없으면 연결 종류(Development / Test / Production)의 색이다.
 *
 * 설계: SWT {@code UIUtils.getConnectionColor}·{@code getConnectionTypeColor}·{@code getConnectionColorByRGB}와 같은 규칙을 Swing 색으로 옮겼다.
 * <ul>
 *     <li>연결 설정의 {@code connectionColor}("r,g,b")가 있으면 그 색, 없으면 연결 종류의 라이트/다크 색(다크 테마면 {@code getColorDark}).</li>
 *     <li>흰색(255,255,255, 기본 Development 종류)은 "색 없음"이다(SWT도 흰색이면 색을 칠하지 않는다. 다크 테마에서 흰 바탕이 되지 않게).</li>
 *     <li>SWT 테마 색 상수 이름(글자로 시작하는 값)은 Swing에 그 색 레지스트리가 없으므로 색 없음으로 본다.</li>
 * </ul>
 * 이 색은 쿼리 창 상태 줄({@link QueryStatusLine}) 바탕과 Registered Servers·Object Explorer 서버 이름의 알약({@link ConnectionPill})에 쓴다.
 * 모든 곳이 이 클래스 하나로 색을 정하므로 같은 연결은 어디서나 같은 색(다크 테마면 같은 어두운 색)이다.
 * <p>
 * 이 색 위의 글자({@link #getTextColor}): 테마 글자 색이 바탕과 4.5:1(WCAG AA 본문 대비) 이상이면 그대로, 아니면 SWT
 * {@code UIStyles.getContrastColor}(org.jkiss.dbeaver.ui, UIStyles.java 159–168행; 탐색기는 {@code DatabaseNavigatorLabelProvider} 173–174행에서
 * 연결 색 바탕의 글자에 씀)와 같은 규칙으로 검정·흰색을 고른다. 연결에 정한 색은 모델에 다크 값이 없어 다크 테마에서도 밝은 색이라, 테마 글자 색(밝은 색)이 읽히지 않기 때문이다.
 * SWT는 항상 대비 색을 쓰지만 여기서는 읽히지 않을 때만 바꿔, 테마별로 정해진 기본 연결 종류 색에서는 지금 모양(테마 글자 색)을 그대로 둔다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 고급 연결 설정 (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-06 Registered Servers도 이 색을 씀, Object Explorer는 노드 바탕 대신 이름 알약 (docs/chromite/swing-registered-servers.md "연결 색 알약")
 * - 2026-11-06 연결 색 위 글자 색({@link #getTextColor})과 WCAG 대비 계산 (docs/chromite/swing-registered-servers.md "연결 색 알약")
 */
final class ConnectionColors {

    // WCAG AA contrast for normal text
    private static final double MIN_TEXT_CONTRAST = 4.5;
    // UIStyles.COLOR_WHITE_DARK: SWT's "white" text in the dark theme
    private static final Color WHITE_DARK = new Color(192, 192, 192);

    private ConnectionColors() {
    }

    /**
     * The color of a connection, or {@code null} when it has none (white).
     */
    @Nullable
    static Color getColor(@NotNull DBPConnectionConfiguration configuration) {
        String rgb = configuration.getConnectionColor();
        if (CommonUtils.isEmpty(rgb)) {
            return getTypeColor(configuration.getConnectionType());
        }
        return parse(rgb);
    }

    /**
     * The color of a connection type for the current theme, or {@code null} when it has none.
     */
    @Nullable
    static Color getTypeColor(@NotNull DBPConnectionType type) {
        return parse(FlatLaf.isLafDark() ? type.getColorDark() : type.getColorLight());
    }

    /**
     * "r,g,b" → color; white, empty and theme color names → {@code null}.
     */
    @Nullable
    static Color parse(@Nullable String rgb) {
        if (CommonUtils.isEmpty(rgb) || Character.isAlphabetic(rgb.charAt(0))) {
            return null;
        }
        String[] parts = rgb.split(","); //$NON-NLS-1$
        if (parts.length != 3) {
            return null;
        }
        int red = CommonUtils.toInt(parts[0].trim());
        int green = CommonUtils.toInt(parts[1].trim());
        int blue = CommonUtils.toInt(parts[2].trim());
        if (red == 255 && green == 255 && blue == 255) {
            return null;
        }
        return new Color(clamp(red), clamp(green), clamp(blue));
    }

    /**
     * Text color on a connection color: the given (theme) foreground when it is readable (4.5:1), otherwise black or
     * white by the background's luma like SWT {@code UIStyles.getContrastColor}.
     */
    @NotNull
    static Color getTextColor(@NotNull Color background, @NotNull Color foreground) {
        if (contrast(background, foreground) >= MIN_TEXT_CONTRAST) {
            return foreground;
        }
        // UIStyles.getContrastColor: Rec. 601 luma; a dark background gets white (light gray in the dark theme)
        double darkness = 1 - (0.299 * background.getRed() + 0.587 * background.getGreen() + 0.114 * background.getBlue()) / 255;
        if (darkness > 0.5) {
            return FlatLaf.isLafDark() ? WHITE_DARK : Color.WHITE;
        }
        return Color.BLACK;
    }

    /**
     * WCAG 2 contrast ratio of two colors.
     */
    static double contrast(@NotNull Color a, @NotNull Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /**
     * WCAG 2 relative luminance.
     */
    private static double luminance(@NotNull Color color) {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen()) + 0.0722 * linear(color.getBlue());
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    /**
     * Color → "r,g,b" (the format of the connection setting).
     */
    @NotNull
    static String format(@NotNull Color color) {
        return color.getRed() + "," + color.getGreen() + "," + color.getBlue(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
