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

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPConnectionType;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * 서버 이름을 감싸는 연결 색 알약(사용자 결정 2026-11-06 "B안: 이름 감싸기"). Registered Servers와 Object Explorer의 서버 행이 함께 쓴다.
 *
 * 설계:
 * <ul>
 *     <li>색은 {@link ConnectionColors}(연결에 정한 색, 없으면 연결 종류의 라이트/다크 색)다. 두 창이 같은 규칙을 쓰므로 같은 서버는 두 창에서 같은 색이다.
 *     색이 없는 연결(흰색, 기본 Development)은 알약이 없다.</li>
 *     <li>줄 전체를 칠하지 않고 이름만 감싼다. 줄 바탕은 선택·마우스 올림 표시가 쓰고, 알약은 그 위에 그려 선택한 줄과 마우스를 올린 줄에서도 보인다.
 *     글자 색은 테마 색 그대로다. 다만 그 색이 알약 바탕과 4.5:1이 안 되면(다크 테마의 밝은 연결 지정 색 등) 그 라벨만 대비 색으로 바꾼다
 *     ({@link ConnectionColors#getTextColor}). 기본 연결 종류 색은 테마별로 옅게/어둡게 정해져 있어 바뀌지 않는다.</li>
 *     <li>크기는 시안(D:\Tools\_scratch\conn-color-mock, {@code ConnColorMock} B안) 그대로다: 좌우 여백 6, 위는 대문자 높이 + 4, 아래는 descent + 1,
 *     둥글기(arc) 10. 위아래를 글꼴 줄 높이가 아니라 대문자 높이와 descent로 정해 글자 위아래 여백이 눈으로 같아 보인다.
 *     아이콘→알약, 알약→호스트 간격 6은 렌더러가 아이콘-글자 간격에 {@link #getPaddingX()}를 더해 맞춘다.</li>
 *     <li>Production 종류는 1px 테두리를 더한다(색을 구별하기 어려운 사용자가 Test와 Production을 모양으로 구별하도록).
 *     테두리 색은 바탕색에서 끌어낸다: 같은 색상(hue)에서 채도를 올리고 밝기를 바탕과 먼 쪽(밝은 바탕이면 어둡게, 어두운 바탕이면 밝게)으로 옮겨
 *     바탕과 3:1(WCAG 비텍스트 대비)이 되는 첫 색이다. 다크 테마의 어두운 바탕에서는 더 어두운 색으로 3:1을 만들 수 없어서 밝은 쪽으로 간다.
 *     연결에 정한 색도 같은 규칙이다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 연결 색을 줄 바탕 대신 이름 알약으로 (docs/chromite/swing-registered-servers.md "연결 색 알약")
 * - 2026-11-06 글자가 바탕과 4.5:1이 안 되면 대비 색, 대비 계산은 {@link ConnectionColors}로 옮김
 */
final class ConnectionPill {

    // Mock B: horizontal padding, arc, space above the cap height and below the descent (unscaled)
    private static final int PADDING_X = 6;
    private static final int ARC = 10;
    private static final int PADDING_TOP = 4;
    private static final int PADDING_BOTTOM = 1;
    private static final float BORDER_WIDTH = 1f;
    // WCAG non-text contrast
    private static final double BORDER_CONTRAST = 3;
    private static final float BORDER_MIN_SATURATION = 0.6f;
    private static final float BORDER_BRIGHTNESS_STEP = 0.01f;

    @NotNull
    private final Color fill;
    @Nullable
    private final Color border;

    private ConnectionPill(@NotNull Color fill, @Nullable Color border) {
        this.fill = fill;
        this.border = border;
    }

    /**
     * The pill of a server for the current theme, or {@code null} when the connection has no color.
     */
    @Nullable
    static ConnectionPill of(@NotNull DBPDataSourceContainer container) {
        DBPConnectionConfiguration configuration = container.getConnectionConfiguration();
        Color fill = ConnectionColors.getColor(configuration);
        if (fill == null) {
            return null;
        }
        boolean production = DBPConnectionType.PROD.getId().equals(configuration.getConnectionType().getId());
        return new ConnectionPill(fill, production ? deriveBorder(fill) : null);
    }

    /**
     * Color of the name on the pill: the label's own color, or a contrasting one when it is unreadable on the fill
     * ({@link ConnectionColors#getTextColor}).
     */
    @NotNull
    Color getTextColor(@NotNull Color foreground) {
        return ConnectionColors.getTextColor(fill, foreground);
    }

    /**
     * Horizontal padding inside the pill (scaled); renderers add it to the icon-text gap and after the name.
     */
    static int getPaddingX() {
        return UIScale.scale(PADDING_X);
    }

    /**
     * Paints the pill around the label's text, before the label paints itself (the text stays on top).
     *
     * @param textX     x of the label's text
     * @param textWidth width of the label's text
     * @param baseline  the label's text baseline ({@link JLabel#getBaseline})
     */
    void paint(@NotNull Graphics g, @NotNull JLabel label, int textX, int textWidth, int baseline) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            FlatUIUtils.setRenderingHints(g2);
            Font font = label.getFont();
            double capHeight = font.createGlyphVector(g2.getFontRenderContext(), "H").getVisualBounds().getHeight(); //$NON-NLS-1$
            double top = baseline - capHeight - UIScale.scale(PADDING_TOP);
            double bottom = baseline + label.getFontMetrics(font).getDescent() + UIScale.scale(PADDING_BOTTOM);
            double x = textX - getPaddingX();
            double width = textWidth + 2 * getPaddingX();
            double height = bottom - top;
            double arc = UIScale.scale((float) ARC);
            if (border == null) {
                g2.setColor(fill);
                g2.fill(new RoundRectangle2D.Double(x, top, width, height, arc, arc));
                return;
            }
            // Border inside the pill's bounds: outer shape in the border color, inner shape in the fill
            double line = UIScale.scale(BORDER_WIDTH);
            g2.setColor(border);
            g2.fill(new RoundRectangle2D.Double(x, top, width, height, arc, arc));
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Double(x + line, top + line, width - 2 * line, height - 2 * line,
                Math.max(0, arc - 2 * line), Math.max(0, arc - 2 * line)));
        } finally {
            g2.dispose();
        }
    }

    /**
     * Border of a fill: the fill's hue, more saturated, shifted in brightness away from the fill (darker on a light
     * fill, lighter on a dark one) until it reaches 3:1 against the fill; the end of that range when it never does.
     */
    @NotNull
    private static Color deriveBorder(@NotNull Color fill) {
        float[] hsb = Color.RGBtoHSB(fill.getRed(), fill.getGreen(), fill.getBlue(), null);
        // A gray fill has no hue to saturate
        float saturation = hsb[1] < 0.05f ? 0f : Math.max(hsb[1], BORDER_MIN_SATURATION);
        boolean darker = ConnectionColors.contrast(fill, Color.BLACK) >= ConnectionColors.contrast(fill, Color.WHITE);
        Color candidate = fill;
        for (float brightness = hsb[2]; brightness >= 0f && brightness <= 1f;
             brightness += darker ? -BORDER_BRIGHTNESS_STEP : BORDER_BRIGHTNESS_STEP) {
            candidate = new Color(Color.HSBtoRGB(hsb[0], saturation, brightness));
            if (ConnectionColors.contrast(candidate, fill) >= BORDER_CONTRAST) {
                return candidate;
            }
        }
        return candidate;
    }

}
