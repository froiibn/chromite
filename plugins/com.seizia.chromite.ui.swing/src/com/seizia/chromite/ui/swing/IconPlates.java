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
import com.formdev.flatlaf.util.HiDPIUtils;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;
import com.seizia.chromite.Log;
import com.seizia.chromite.utils.RuntimeUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.util.HashSet;
import java.util.Set;

/**
 * 어두운 테마에서 밝은 판 위에 그려야 하는 아이콘(어두운 벤더 로고)을 그 판과 함께 그린다.
 *
 * 설계:
 * <ul>
 *     <li>선언: 아이콘을 가진 플러그인이 자기 {@code plugin.xml}에서 확장 지점 {@value #EXTENSION_POINT_ID}에
 *     {@code <lightPlate icon="icons/..."/>}를 둔다. 경로는 그 플러그인 기준이라 모델이 아이콘에 주는 위치
 *     ({@code platform:/plugin/<번들>/<경로>}, {@code AbstractDescriptor.iconToImage})와 같은 문자열이 된다. 선언은 XML뿐이라
 *     모델 플러그인이 Swing 번들에 의존하지 않고(Require-Bundle 없음), 다른 아이콘을 표시할 때 Java 코드를 고치지 않는다.
 *     드라이버·유형 선언의 속성으로 두지 않은 이유: 아이콘은 여러 곳에서 {@code DBPImage}만 넘어와({@link NodeIcons#getIcon})
 *     드라이버를 모르므로, 속성을 쓰려면 모델의 이미지 형식까지 바꿔야 한다.</li>
 *     <li>판: 이미지 파일은 바꾸지 않는다(벤더 상표: 다시 칠하기, 윤곽, 반전 없음). 그릴 때 아이콘 칸 전체에 테마 색
 *     {@value #PLATE_COLOR_KEY}의 둥근 사각형을 깔고, 그 안쪽 {@value #INSET}px 여백 안에 원래 그림을 같은 비율로 줄여 그린다.
 *     아이콘 크기({@link #getIconWidth()})는 원래 아이콘과 같아서 행·콤보·탭의 높이와 글자 기준선이 바뀌지 않는다.</li>
 *     <li>테마: 판 색은 그릴 때마다 읽는다. 밝은 테마는 이 키를 투명으로 두어 판도, 줄이기도 없이 원래 아이콘 그대로 그린다.
 *     그래서 실행 중 테마를 바꾸면 다시 그리기만으로 맞고, 캐시를 비울 필요가 없다({@link UiIcons}의 테마 아이콘과 같은 방식).</li>
 *     <li>선명도: 판과 그림은 장치 픽셀에서 그린다({@link HiDPIUtils#paintAtScale1x}). 래스터 아이콘은 가장 큰 해상도 변형(@2x)을
 *     면적 평균(알파 곱한 값)으로 장치 크기에 맞춰 줄여 캐시한다. 벡터 아이콘은 줄인 좌표계에 그대로 그린다.</li>
 *     <li>비활성({@link FlatLaf.DisabledIconProvider}): 판이 있으면 판과 원래 그림을 함께 투명도 {@value #DISABLED_ALPHA}로 그린다(아이콘 팩의
 *     비활성 아이콘과 같은 값). 판을 그대로 두면 비활성 줄에서 판만 밝게 남고, 판만 흐리게 하고 FlatLaf의 회색 그림을 올리면 그림이 판과
 *     구별되지 않는다. 판이 없는 테마는 전처럼 FlatLaf가 만든 비활성(회색) 그림이다.</li>
 * </ul>
 * 문서: docs/chromite/design-spec.md "벤더 아이콘의 밝은 판".
 * EDT에서만 호출한다(캐시를 동기화하지 않는다).
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성: 어두운 테마에서 남색 ShardSphere 로고가 바탕에 묻힘(앱 변경 #114)
 */
final class IconPlates {

    private static final Log log = Log.getLog(IconPlates.class);

    private static final String EXTENSION_POINT_ID = "com.seizia.chromite.ui.swing.iconPlate"; //$NON-NLS-1$
    private static final String ELEMENT_LIGHT_PLATE = "lightPlate"; //$NON-NLS-1$
    private static final String ATTR_ICON = "icon"; //$NON-NLS-1$
    private static final String PLATFORM_PREFIX = "platform:"; //$NON-NLS-1$

    // Theme color of the plate; transparent (or missing) means no plate
    private static final String PLATE_COLOR_KEY = "Chromite.iconPlate.background"; //$NON-NLS-1$
    // Space between the plate's edge and the mark, per 16 px of icon (unscaled)
    private static final int INSET = 1;
    // Corner arc of a 16 px plate: radius.small (arc 4, design-spec.md "모서리 토큰"); larger icons scale it
    private static final int ARC = 4;
    private static final int BASE_SIZE = 16;
    // Disabled plate icons: the opacity of the icon pack's disabled icons (UiIcons)
    private static final float DISABLED_ALPHA = 0.4f;

    @Nullable
    private static Set<String> lightPlateLocations;

    private IconPlates() {
    }

    /**
     * The icon to show for a model icon location: the icon on a light plate when its plugin declared one, else the icon itself.
     */
    @NotNull
    static Icon apply(@NotNull String location, @NotNull Icon icon) {
        return getLightPlateLocations().contains(location) ? new PlateIcon(icon) : icon;
    }

    @NotNull
    private static Set<String> getLightPlateLocations() {
        if (lightPlateLocations == null) {
            Set<String> locations = new HashSet<>();
            for (IConfigurationElement element : Platform.getExtensionRegistry().getConfigurationElementsFor(EXTENSION_POINT_ID)) {
                String icon = element.getAttribute(ATTR_ICON);
                if (!ELEMENT_LIGHT_PLATE.equals(element.getName()) || CommonUtils.isEmpty(icon)) {
                    log.debug("Ignored icon plate declaration of " + element.getContributor().getName());
                    continue;
                }
                // Same location string as the model's icon of that plugin (AbstractDescriptor.iconToImage)
                locations.add(icon.startsWith(PLATFORM_PREFIX) ? icon : RuntimeUtils.makePlatformResourceUrl(element.getContributor().getName(), icon));
            }
            lightPlateLocations = locations;
        }
        return lightPlateLocations;
    }

    /**
     * The mark on a plate of the theme color, inside the mark's own box; the plain icon when the theme has no plate.
     */
    private static final class PlateIcon implements Icon, FlatLaf.DisabledIconProvider {
        @NotNull
        private final Icon mark;
        // Painted instead when the theme has no plate: the mark, or the look and feel's disabled form of it
        @NotNull
        private final Icon plainIcon;
        private final float alpha;
        // Largest raster variant of the mark (null: not a raster image, painted scaled instead)
        @Nullable
        private final BufferedImage source;
        // The source resampled to the last device size
        @Nullable
        private BufferedImage scaled;
        @Nullable
        private LookAndFeel disabledLaf;
        @Nullable
        private Icon disabledIcon;

        PlateIcon(@NotNull Icon mark) {
            this(mark, mark, 1f);
        }

        private PlateIcon(@NotNull Icon mark, @NotNull Icon plainIcon, float alpha) {
            this.mark = mark;
            this.plainIcon = plainIcon;
            this.alpha = alpha;
            this.source = getLargestImage(mark);
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Color plate = UIManager.getColor(PLATE_COLOR_KEY);
            if (plate == null || plate.getAlpha() == 0) {
                plainIcon.paintIcon(c, g, x, y);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                if (alpha < 1f) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                }
                HiDPIUtils.paintAtScale1x(g2, x, y, getIconWidth(), getIconHeight(),
                    (g2d, px, py, width, height, scale) -> paintOnPlate(c, g2d, px, py, width, height, scale, plate));
            } finally {
                g2.dispose();
            }
        }

        // Device pixels from here on
        private void paintOnPlate(@Nullable Component c, @NotNull Graphics2D g, int x, int y, int width, int height, double scale,
                                  @NotNull Color plate) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double arc = ARC * scale * Math.min(getIconWidth(), getIconHeight()) / BASE_SIZE;
            g.setColor(plate);
            g.fill(new RoundRectangle2D.Double(x, y, width, height, arc, arc));

            int inset = Math.max(1, (int) Math.round(INSET * scale * Math.min(getIconWidth(), getIconHeight()) / BASE_SIZE));
            // The mark keeps its proportions and is centered in the space inside the inset
            double factor = Math.min((width - 2.0 * inset) / getIconWidth(), (height - 2.0 * inset) / getIconHeight());
            int markWidth = Math.max(1, (int) Math.round(getIconWidth() * factor));
            int markHeight = Math.max(1, (int) Math.round(getIconHeight() * factor));
            int markX = x + (width - markWidth) / 2;
            int markY = y + (height - markHeight) / 2;
            if (source != null) {
                g.drawImage(getScaled(markWidth, markHeight), markX, markY, null);
            } else {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.translate(markX, markY);
                g.scale((double) markWidth / getIconWidth(), (double) markHeight / getIconHeight());
                mark.paintIcon(c, g, 0, 0);
            }
        }

        @NotNull
        private BufferedImage getScaled(int width, int height) {
            BufferedImage image = scaled;
            if (image == null || image.getWidth() != width || image.getHeight() != height) {
                image = resample(source, width, height);
                scaled = image;
            }
            return image;
        }

        @Override
        public int getIconWidth() {
            return mark.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return mark.getIconHeight();
        }

        // Plate and mark faded; without a plate the look and feel's disabled form of the mark (its gray filter), as before.
        // Rebuilt when the look and feel changes, since the gray filter depends on the theme.
        @Override
        public Icon getDisabledIcon() {
            LookAndFeel laf = UIManager.getLookAndFeel();
            if (disabledIcon == null || disabledLaf != laf) {
                Icon disabledMark = laf.getDisabledIcon(null, mark);
                disabledIcon = new PlateIcon(mark, disabledMark != null ? disabledMark : mark, DISABLED_ALPHA);
                disabledLaf = laf;
            }
            return disabledIcon;
        }
    }

    @Nullable
    private static BufferedImage getLargestImage(@NotNull Icon icon) {
        if (!(icon instanceof ImageIcon imageIcon)) {
            return null;
        }
        Image image = imageIcon.getImage();
        if (image instanceof MultiResolutionImage variants) {
            // NodeIcons pairs name.png with name@2x.png
            Image largest = null;
            for (Image variant : variants.getResolutionVariants()) {
                if (largest == null || variant.getWidth(null) > largest.getWidth(null)) {
                    largest = variant;
                }
            }
            image = largest;
        }
        return image instanceof BufferedImage buffered ? buffered : null;
    }

    /**
     * Area-average resampling with premultiplied alpha: every target pixel is the mean of the source area it covers, so a
     * reduced mark keeps its edges without the dark fringes that averaging unpremultiplied transparent pixels gives.
     */
    @NotNull
    private static BufferedImage resample(@NotNull BufferedImage source, int width, int height) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int[] pixels = source.getRGB(0, 0, sourceWidth, sourceHeight, null, 0, sourceWidth);
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        double stepX = (double) sourceWidth / width;
        double stepY = (double) sourceHeight / height;
        double area = stepX * stepY;
        for (int ty = 0; ty < height; ty++) {
            double top = ty * stepY;
            double bottom = top + stepY;
            for (int tx = 0; tx < width; tx++) {
                double left = tx * stepX;
                double right = left + stepX;
                double a = 0;
                double r = 0;
                double gr = 0;
                double b = 0;
                for (int sy = (int) top; sy < Math.min(sourceHeight, (int) Math.ceil(bottom)); sy++) {
                    double weightY = Math.min(bottom, sy + 1) - Math.max(top, sy);
                    for (int sx = (int) left; sx < Math.min(sourceWidth, (int) Math.ceil(right)); sx++) {
                        double weight = weightY * (Math.min(right, sx + 1) - Math.max(left, sx));
                        int argb = pixels[sy * sourceWidth + sx];
                        double alpha = (argb >>> 24) * weight;
                        a += alpha;
                        r += ((argb >> 16) & 0xff) * alpha;
                        gr += ((argb >> 8) & 0xff) * alpha;
                        b += (argb & 0xff) * alpha;
                    }
                }
                if (a > 0) {
                    int alpha = (int) Math.round(Math.min(255, a / area));
                    target.setRGB(tx, ty, (alpha << 24) | ((int) Math.round(r / a) << 16) | ((int) Math.round(gr / a) << 8)
                        | (int) Math.round(b / a));
                }
            }
        }
        return target;
    }
}
