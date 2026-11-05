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

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;
import org.eclipse.core.runtime.Platform;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBIconComposite;
import org.jkiss.dbeaver.model.DBPImage;
import org.osgi.framework.Bundle;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * 모델 계층의 이미지 참조({@link DBPImage})를 Swing {@link Icon}으로 바꾼다.
 *
 * 설계: 탐색기 노드 아이콘({@code DBNNode#getNodeIcon()})은 {@link DBIcon}이고, 위치는
 * {@code platform:/plugin/<번들>/<경로>} 형식이다. SWT 버전의 {@code DBeaverIcons}와 같은 규칙으로 해석한다.
 * <ul>
 *     <li>위치는 {@link Bundle#getEntry}로 번들 안의 파일을 직접 찾는다. {@code platform:} URL 처리기에 기대지 않아서
 *     같은 폴더의 {@code @2x} PNG도 같은 방법으로 찾을 수 있다. 다른 형식의 위치는 URL로 읽는다.</li>
 *     <li>DBeaver 트리 아이콘은 대부분 SVG다. SVG는 JSVG({@code com.github.weisj.jsvg}, MIT, Eclipse 대상 플랫폼에 들어 있는 OSGi 번들)로
 *     읽어 그릴 때마다 벡터로 렌더링한다. 그래서 화면 배율(HiDPI)과 관계없이 선명하다.</li>
 *     <li>PNG는 {@code @2x} 파일이 있으면 {@link BaseMultiResolutionImage}로 묶어 HiDPI 화면에서 큰 이미지를 쓴다.</li>
 *     <li>{@link DBIconComposite}(상태 겹침 아이콘)는 기본 아이콘 위 네 모서리에 겹침 아이콘을 그린다(SWT의 {@code OverlayImageDescriptor}와 같음).</li>
 *     <li>읽을 수 없는 아이콘은 {@link DBIcon#TYPE_UNKNOWN}, 그것도 없으면 Look and Feel의 트리 잎 아이콘으로 대신한다.
 *     실패한 위치도 캐시해서 로그가 반복되지 않게 한다.</li>
 * </ul>
 * 아이콘 파일은 DBeaver 번들의 것(Apache 2.0)을 그대로 쓰며, JetBrains/Microsoft 아이콘은 쓰지 않는다.
 * EDT에서만 호출한다(캐시를 동기화하지 않는다).
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 */
final class NodeIcons {

    private static final Log log = Log.getLog(NodeIcons.class);

    private static final String PLATFORM_PLUGIN_PREFIX = "platform:/plugin/"; //$NON-NLS-1$
    private static final int ICON_SIZE = 16;

    private static final Map<String, Icon> iconCache = new HashMap<>();

    private NodeIcons() {
    }

    /**
     * Icon for a model image; never {@code null}.
     */
    @NotNull
    static Icon getIcon(@Nullable DBPImage image) {
        if (image instanceof DBIconComposite composite) {
            Icon main = getIcon(composite.getMain());
            if (!composite.hasOverlays()) {
                return main;
            }
            return new CompositeIcon(
                main,
                getOverlay(composite.getTopLeft()),
                getOverlay(composite.getTopRight()),
                getOverlay(composite.getBottomLeft()),
                getOverlay(composite.getBottomRight()));
        }
        if (image == null) {
            return getUnknownIcon();
        }
        Icon icon = getIconByLocation(image.getLocation());
        return icon != null ? icon : getUnknownIcon();
    }

    @Nullable
    private static Icon getOverlay(@Nullable DBPImage image) {
        return image == null ? null : getIcon(image);
    }

    @NotNull
    private static Icon getUnknownIcon() {
        Icon icon = getIconByLocation(DBIcon.TYPE_UNKNOWN.getLocation());
        if (icon == null) {
            icon = UIManager.getIcon("Tree.leafIcon"); //$NON-NLS-1$
        }
        return icon;
    }

    /**
     * Loads and caches the icon at the given location. Failed locations are cached as {@code null}.
     */
    @Nullable
    private static Icon getIconByLocation(@NotNull String location) {
        if (iconCache.containsKey(location)) {
            return iconCache.get(location);
        }
        Icon icon = null;
        try {
            URL url = resolve(location);
            if (url == null) {
                log.debug("Icon not found: " + location);
            } else if (location.toLowerCase().endsWith(".svg")) { //$NON-NLS-1$
                SVGDocument document = new SVGLoader().load(url);
                if (document == null) {
                    log.debug("Can't parse SVG icon: " + location);
                } else {
                    icon = new SvgIcon(document);
                }
            } else {
                icon = loadRasterIcon(location, url);
            }
        } catch (Exception e) {
            log.debug("Error loading icon " + location, e);
        }
        iconCache.put(location, icon);
        return icon;
    }

    @Nullable
    private static Icon loadRasterIcon(@NotNull String location, @NotNull URL url) throws Exception {
        BufferedImage image = ImageIO.read(url);
        if (image == null) {
            return null;
        }
        // DBeaver ships HiDPI variants as name@2x.png next to the base image
        int extPos = location.lastIndexOf('.');
        URL hiDpiUrl = extPos > 0 ? resolve(location.substring(0, extPos) + "@2x" + location.substring(extPos)) : null; //$NON-NLS-1$
        BufferedImage hiDpiImage = hiDpiUrl == null ? null : ImageIO.read(hiDpiUrl);
        if (hiDpiImage != null) {
            return new ImageIcon(new BaseMultiResolutionImage(image, hiDpiImage));
        }
        return new ImageIcon(image);
    }

    /**
     * Finds the file of a {@code platform:/plugin/...} location in its bundle, or opens other locations as URLs.
     */
    @Nullable
    private static URL resolve(@NotNull String location) throws Exception {
        if (location.startsWith(PLATFORM_PLUGIN_PREFIX)) {
            String bundlePath = location.substring(PLATFORM_PLUGIN_PREFIX.length());
            int slashPos = bundlePath.indexOf('/');
            if (slashPos <= 0) {
                return null;
            }
            Bundle bundle = Platform.getBundle(bundlePath.substring(0, slashPos));
            return bundle == null ? null : bundle.getEntry(bundlePath.substring(slashPos + 1));
        }
        return URI.create(location).toURL();
    }

    /**
     * Renders an SVG document as vector graphics at the current scale, so icons stay sharp on HiDPI screens.
     */
    private static final class SvgIcon implements Icon {
        private final SVGDocument document;

        SvgIcon(@NotNull SVGDocument document) {
            this.document = document;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                document.render(c, g2, new ViewBox(0, 0, ICON_SIZE, ICON_SIZE));
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return ICON_SIZE;
        }

        @Override
        public int getIconHeight() {
            return ICON_SIZE;
        }
    }

    /**
     * Main icon with optional overlays in its corners, like the SWT OverlayImageDescriptor.
     */
    private record CompositeIcon(
        @NotNull Icon main,
        @Nullable Icon topLeft,
        @Nullable Icon topRight,
        @Nullable Icon bottomLeft,
        @Nullable Icon bottomRight
    ) implements Icon {

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            main.paintIcon(c, g, x, y);
            int width = getIconWidth();
            int height = getIconHeight();
            if (topLeft != null) {
                topLeft.paintIcon(c, g, x, y);
            }
            if (topRight != null) {
                topRight.paintIcon(c, g, x + width - topRight.getIconWidth(), y);
            }
            if (bottomLeft != null) {
                bottomLeft.paintIcon(c, g, x, y + height - bottomLeft.getIconHeight());
            }
            if (bottomRight != null) {
                bottomRight.paintIcon(c, g, x + width - bottomRight.getIconWidth(), y + height - bottomRight.getIconHeight());
            }
        }

        @Override
        public int getIconWidth() {
            return main.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return main.getIconHeight();
        }
    }
}
