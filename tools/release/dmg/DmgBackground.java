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

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Draws the background of the macOS disk image window: tools/release/dmg/background.png (1x) and background@2x.png.
 * Run from the repository root: {@code java tools/release/dmg/DmgBackground.java}. The images are committed; run this
 * only to change them.
 *
 * 설계 (docs/chromite/distribution.md "macOS 디스크 이미지 창", 앱 변경 #26):
 * <ul>
 *   <li>창 안 위에 앱, 아래에 Applications 폴더, 그 사이에 아래로 향하는 화살표(위에서 아래로 끌어 설치).
 *   자리와 크기는 dmg-settings.py와 같다: 창 {@value #WIDTH}×{@value #HEIGHT}pt, 아이콘 144pt, 앱 중심 y {@value #APP_Y},
 *   Applications 중심 y {@value #APPLICATIONS_Y}.</li>
 *   <li>바탕은 로고 팩의 파랑(chromite_icon_macos.svg의 물결 {@code #0A86F2})을 같은 색상에서 상대 휘도 {@value #TARGET_LUMINANCE}로
 *   맞춘 단색이다. Finder는 아이콘 이름을 밝은 모드에서 검정, 어두운 모드에서 흰색으로 쓰고 바탕 그림에 맞춰 바꾸지 않는다. 이 휘도에서는
 *   검정과 흰 글자가 모두 4.5:1 이상이다(실행할 때 두 대비를 출력한다). 그래서 밝은·어두운 Finder 모두 읽힌다.</li>
 *   <li>화살표는 로고 팩의 밝은 하늘색({@code #D3E8FC}, 원기둥 그라데이션)이다.</li>
 * </ul>
 * 변경 이력:
 * <ul>
 *   <li>2026-11-09 Created (app change #26).</li>
 * </ul>
 */
public class DmgBackground {

    static final int WIDTH = 480;
    static final int HEIGHT = 580;
    static final int APP_Y = 150;
    static final int APPLICATIONS_Y = 430;
    static final int ICON = 144;
    static final double TARGET_LUMINANCE = 0.179;
    /** The wave blue of chromite_logo_pack/chromite_icon_macos.svg. */
    static final Color BRAND = new Color(0x0A, 0x86, 0xF2);
    /** The light sky of the cylinder gradient of the same icon. */
    static final Color ARROW = new Color(0xD3, 0xE8, 0xFC);

    public static void main(String[] args) throws IOException {
        Path dir = Path.of("tools", "release", "dmg");
        Color background = atLuminance(BRAND, TARGET_LUMINANCE);
        double l = luminance(background);
        System.out.printf("background #%02X%02X%02X, luminance %.4f, black text %.2f:1, white text %.2f:1, arrow %.2f:1%n",
            background.getRed(), background.getGreen(), background.getBlue(), l,
            (l + 0.05) / 0.05, 1.05 / (l + 0.05), (luminance(ARROW) + 0.05) / (l + 0.05));
        write(dir.resolve("background.png"), background, 1);
        write(dir.resolve("background@2x.png"), background, 2);
    }

    private static void write(Path file, Color background, int scale) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH * scale, HEIGHT * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(scale, scale);
            g.setColor(background);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            // Between the app's name (under its icon) and the top of the Applications icon
            double x = WIDTH / 2.0;
            double top = APP_Y + ICON / 2.0 + 40;
            double tip = APPLICATIONS_Y - ICON / 2.0 - 14;
            double head = 22;
            g.setColor(ARROW);
            g.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D shaft = new Path2D.Double();
            shaft.moveTo(x, top);
            shaft.lineTo(x, tip - head / 2);
            g.draw(shaft);
            Path2D arrow = new Path2D.Double();
            arrow.moveTo(x - head, tip - head);
            arrow.lineTo(x, tip);
            arrow.lineTo(x + head, tip - head);
            g.draw(arrow);
        } finally {
            g.dispose();
        }
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
        System.out.println(file + ": " + image.getWidth() + "x" + image.getHeight());
    }

    /**
     * The colour of the same hue whose relative luminance (WCAG) is {@code target}: the linear channels scaled.
     */
    static Color atLuminance(Color c, double target) {
        double k = target / luminance(c);
        return new Color(encode(linear(c.getRed()) * k), encode(linear(c.getGreen()) * k), encode(linear(c.getBlue()) * k));
    }

    static double luminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    private static double linear(int v) {
        double s = v / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static int encode(double linear) {
        double l = Math.max(0, Math.min(1, linear));
        double s = l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1 / 2.4) - 0.055;
        return (int) Math.round(s * 255);
    }
}
