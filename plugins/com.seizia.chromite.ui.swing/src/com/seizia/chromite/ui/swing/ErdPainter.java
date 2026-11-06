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
import org.jkiss.dbeaver.Log;

import java.awt.*;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ER 다이어그램을 그리는 대상: 화면·PNG({@link Java2D})와 SVG 파일({@link Svg}).
 *
 * 설계: 다이어그램은 도형 채우기·선·글자·아이콘만으로 그린다({@link ErdRenderer}). 이 몇 가지 동작을 인터페이스로 두면
 * 같은 그리기 코드로 화면, PNG(BufferedImage), SVG를 만들 수 있다. 일반 Graphics2D를 SVG로 바꾸는 라이브러리(Batik 등)를
 * 들이지 않으려고 SVG는 필요한 동작만 직접 쓴다. 도형은 {@link Shape}의 경로를 그대로 SVG path로 옮기므로 모양이 같다.
 * 아이콘은 아이콘 팩의 SVG 파일을 {@code <symbol>}로 한 번씩 넣고 {@code <use>}로 쓴다(파일마다 같은 mask id를 쓰므로 이름을 바꾼다).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-erd.md)
 */
interface ErdPainter {

    void fill(@NotNull Shape shape, @NotNull Color color);

    void stroke(@NotNull Shape shape, @NotNull Color color, float width);

    /**
     * @param baseline y of the text base line
     */
    void text(@NotNull String text, double x, double baseline, @NotNull Font font, @NotNull Color color);

    /**
     * A 16px icon of the current icon pack and theme ({@link UiIcons}).
     */
    void icon(@NotNull String iconId, double x, double y);

    /**
     * Screen and PNG.
     */
    final class Java2D implements ErdPainter {
        private final Graphics2D g;

        Java2D(@NotNull Graphics2D g) {
            this.g = g;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        }

        @Override
        public void fill(@NotNull Shape shape, @NotNull Color color) {
            g.setColor(color);
            g.fill(shape);
        }

        @Override
        public void stroke(@NotNull Shape shape, @NotNull Color color, float width) {
            g.setColor(color);
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(shape);
        }

        @Override
        public void text(@NotNull String text, double x, double baseline, @NotNull Font font, @NotNull Color color) {
            g.setFont(font);
            g.setColor(color);
            g.drawString(text, (float) x, (float) baseline);
        }

        @Override
        public void icon(@NotNull String iconId, double x, double y) {
            Graphics2D ig = (Graphics2D) g.create();
            try {
                ig.translate(x, y);
                UiIcons.get(iconId).paintIcon(null, ig, 0, 0);
            } finally {
                ig.dispose();
            }
        }
    }

    /**
     * SVG document writer.
     */
    final class Svg implements ErdPainter {
        private static final Log log = Log.getLog(Svg.class);

        private final StringBuilder body = new StringBuilder();
        private final Map<String, String> symbols = new LinkedHashMap<>();

        @Override
        public void fill(@NotNull Shape shape, @NotNull Color color) {
            body.append("<path d=\"").append(pathData(shape)).append('"').append(paint("fill", color)) //$NON-NLS-1$
                .append(" stroke=\"none\"/>\n"); //$NON-NLS-1$
        }

        @Override
        public void stroke(@NotNull Shape shape, @NotNull Color color, float width) {
            body.append("<path d=\"").append(pathData(shape)).append("\" fill=\"none\"").append(paint("stroke", color)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(" stroke-width=\"").append(number(width)) //$NON-NLS-1$
                .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n"); //$NON-NLS-1$
        }

        @Override
        public void text(@NotNull String text, double x, double baseline, @NotNull Font font, @NotNull Color color) {
            // A weight family ("Inter SemiBold") is written as its base family with a font weight, which SVG viewers resolve
            String family = font.getFamily();
            boolean semibold = family.toLowerCase(Locale.ROOT).endsWith(" semibold"); //$NON-NLS-1$
            if (semibold) {
                family = family.substring(0, family.length() - " semibold".length()); //$NON-NLS-1$
            }
            body.append("<text x=\"").append(number(x)).append("\" y=\"").append(number(baseline)) //$NON-NLS-1$ //$NON-NLS-2$
                .append("\" font-family=\"").append(escape(family)).append(", 'Segoe UI', 'Malgun Gothic', sans-serif\"") //$NON-NLS-1$ //$NON-NLS-2$
                .append(" font-size=\"").append(number(font.getSize2D())).append('"'); //$NON-NLS-1$
            if (font.isBold() || semibold) {
                body.append(" font-weight=\"600\""); //$NON-NLS-1$
            }
            body.append(paint("fill", color)).append(" xml:space=\"preserve\">") //$NON-NLS-1$ //$NON-NLS-2$
                .append(escape(text)).append("</text>\n"); //$NON-NLS-1$
        }

        @Override
        public void icon(@NotNull String iconId, double x, double y) {
            String symbolId = "icon-" + iconId; //$NON-NLS-1$
            if (!symbols.containsKey(symbolId)) {
                symbols.put(symbolId, loadSymbol(iconId, symbolId));
            }
            if (symbols.get(symbolId) != null) {
                body.append("<use href=\"#").append(symbolId).append("\" x=\"").append(number(x)) //$NON-NLS-1$ //$NON-NLS-2$
                    .append("\" y=\"").append(number(y)).append("\" width=\"16\" height=\"16\"/>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        /**
         * The complete document.
         */
        @NotNull
        String toDocument(int width, int height, @NotNull Color background) {
            StringBuilder doc = new StringBuilder();
            doc.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n") //$NON-NLS-1$
                .append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width) //$NON-NLS-1$
                .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ").append(width).append(' ').append(height) //$NON-NLS-1$ //$NON-NLS-2$
                .append("\">\n<defs>\n"); //$NON-NLS-1$
            for (String symbol : symbols.values()) {
                if (symbol != null) {
                    doc.append(symbol).append('\n');
                }
            }
            doc.append("</defs>\n<rect width=\"100%\" height=\"100%\"").append(paint("fill", background)).append("/>\n") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .append(body).append("</svg>\n"); //$NON-NLS-1$
            return doc.toString();
        }

        /**
         * The icon file of the current pack and theme as a symbol; its mask ids are made unique.
         */
        private static String loadSymbol(@NotNull String iconId, @NotNull String symbolId) {
            String path = "/icons/ui/" + UiIconStyle.getCurrent().getFolder() + "/" //$NON-NLS-1$ //$NON-NLS-2$
                + (FlatLaf.isLafDark() ? "dark" : "light") + "/" + iconId + ".svg"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            URL url = ErdPainter.class.getResource(path);
            if (url == null) {
                log.warn("Icon not found: " + path);
                return null;
            }
            String svg;
            try (InputStream in = url.openStream()) {
                svg = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                log.warn("Can't read icon " + path, e);
                return null;
            }
            int open = svg.indexOf('>');
            int close = svg.lastIndexOf("</svg>"); //$NON-NLS-1$
            if (open < 0 || close < open) {
                return null;
            }
            String content = svg.substring(open + 1, close)
                .replace("id=\"cut\"", "id=\"" + symbolId + "-cut\"") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .replace("url(#cut)", "url(#" + symbolId + "-cut)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "<symbol id=\"" + symbolId + "\" viewBox=\"0 0 16 16\">" + content + "</symbol>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        @NotNull
        private static String pathData(@NotNull Shape shape) {
            StringBuilder d = new StringBuilder();
            double[] c = new double[6];
            for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
                switch (it.currentSegment(c)) {
                    case PathIterator.SEG_MOVETO -> d.append('M').append(number(c[0])).append(' ').append(number(c[1]));
                    case PathIterator.SEG_LINETO -> d.append('L').append(number(c[0])).append(' ').append(number(c[1]));
                    case PathIterator.SEG_QUADTO -> d.append('Q').append(number(c[0])).append(' ').append(number(c[1]))
                        .append(' ').append(number(c[2])).append(' ').append(number(c[3]));
                    case PathIterator.SEG_CUBICTO -> d.append('C').append(number(c[0])).append(' ').append(number(c[1]))
                        .append(' ').append(number(c[2])).append(' ').append(number(c[3]))
                        .append(' ').append(number(c[4])).append(' ').append(number(c[5]));
                    case PathIterator.SEG_CLOSE -> d.append('Z');
                    default -> {
                    }
                }
            }
            return d.toString();
        }

        @NotNull
        private static String paint(@NotNull String attribute, @NotNull Color color) {
            String value = " " + attribute + "=\"" + String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue()) + '"'; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (color.getAlpha() < 255) {
                value += " " + attribute + "-opacity=\"" + number(color.getAlpha() / 255.0) + '"'; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return value;
        }

        @NotNull
        private static String number(double value) {
            long rounded = Math.round(value);
            if (Math.abs(value - rounded) < 0.005) {
                return Long.toString(rounded);
            }
            return String.format(Locale.ROOT, "%.2f", value); //$NON-NLS-1$
        }

        @NotNull
        private static String escape(@NotNull String text) {
            StringBuilder s = new StringBuilder(text.length());
            for (char ch : text.toCharArray()) {
                switch (ch) {
                    case '<' -> s.append("&lt;"); //$NON-NLS-1$
                    case '>' -> s.append("&gt;"); //$NON-NLS-1$
                    case '&' -> s.append("&amp;"); //$NON-NLS-1$
                    case '"' -> s.append("&quot;"); //$NON-NLS-1$
                    default -> {
                        if (ch >= 0x20 || ch == '\t') {
                            s.append(ch);
                        }
                    }
                }
            }
            return s.toString();
        }
    }
}
