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
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.DBPStatefulObject;
import org.jkiss.dbeaver.model.navigator.DBNDataSource;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNLocalFolder;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraint;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraintType;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectState;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedure;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureType;
import org.jkiss.dbeaver.model.struct.rdb.DBSSequence;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndex;
import org.jkiss.dbeaver.model.struct.rdb.DBSTrigger;
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
 * 디자인 적용(2026-11-06): 자주 보는 모델 아이콘(폴더, 테이블, 뷰, 열, 키, 인덱스, 루틴, 사용자 등)은 위치로 Chromite 아이콘 팩({@link UiIcons})에
 * 대응시켜 그린다({@link #UI_ICON_IDS}). 대응이 없는 아이콘(드라이버 로고, 드물게 쓰는 객체)만 아래 규칙으로 모델의 파일을 읽는다.
 * Object Explorer는 {@link #getNodeIcon}으로 모델 아이콘만으로는 구분되지 않는 종류(기본 키와 고유 키, 함수와 프로시저)와
 * 펼친 폴더를 고르고, 연결은 드라이버 로고에 상태 점을 붙인다(DBeaver의 PNG 겹침 아이콘 대신).
 * <ul>
 *     <li>위치는 {@link Bundle#getEntry}로 번들 안의 파일을 직접 찾는다. {@code platform:} URL 처리기에 기대지 않아서
 *     같은 폴더의 {@code @2x} PNG도 같은 방법으로 찾을 수 있다. 다른 형식의 위치는 URL로 읽는다.</li>
 *     <li>DBeaver 아이콘은 대부분 SVG다. SVG는 JSVG({@code com.github.weisj.jsvg}, MIT, Eclipse 대상 플랫폼에 들어 있는 OSGi 번들)로
 *     읽어 그릴 때마다 벡터로 렌더링한다. 그래서 화면 배율(HiDPI)과 관계없이 선명하다.</li>
 *     <li>PNG는 {@code @2x} 파일이 있으면 {@link BaseMultiResolutionImage}로 묶어 HiDPI 화면에서 큰 이미지를 쓴다.</li>
 *     <li>{@link DBIconComposite}(상태 겹침 아이콘)는 기본 아이콘 위 네 모서리에 겹침 아이콘을 그린다(SWT의 {@code OverlayImageDescriptor}와 같음).
 *     열의 키 겹침(기본 키·참조)은 겹침 대신 열 아이콘 팩의 키 열 아이콘으로 바꾼다.</li>
 *     <li>읽을 수 없는 아이콘은 {@link DBIcon#TYPE_UNKNOWN}, 그것도 없으면 Look and Feel의 트리 잎 아이콘으로 대신한다.
 *     실패한 위치도 캐시해서 로그가 반복되지 않게 한다.</li>
 * </ul>
 * 모델 아이콘 파일은 DBeaver 번들의 것(Apache 2.0)이고, Chromite 아이콘 팩은 Tabler Icons(MIT) 모양을 바탕으로 새로 그린 것이다.
 * JetBrains/Microsoft 아이콘은 쓰지 않는다.
 * EDT에서만 호출한다(캐시를 동기화하지 않는다).
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 디자인 적용: 모델 아이콘을 Chromite 아이콘 팩에 대응, Object Explorer 노드 아이콘과 연결 상태 점
 *   (docs/chromite/design-spec.md "적용 현황")
 */
final class NodeIcons {

    private static final Log log = Log.getLog(NodeIcons.class);

    private static final String PLATFORM_PLUGIN_PREFIX = "platform:/plugin/"; //$NON-NLS-1$
    private static final int ICON_SIZE = 16;
    private static final String COLUMN_ICON = "column"; //$NON-NLS-1$

    /**
     * Model icons drawn with Chromite icons (docs/chromite/design-spec.md "필요한 아이콘 개념", DBIcon column).
     * Data type icons stand for columns: the model gives attributes the icon of their type.
     */
    private static final Map<String, DBIcon[]> UI_ICON_IDS = Map.ofEntries(
        Map.entry("connection", new DBIcon[]{DBIcon.DATABASE_DEFAULT, DBIcon.TREE_SERVER, DBIcon.TREE_SERVERS}), //$NON-NLS-1$
        Map.entry("database", new DBIcon[]{DBIcon.TREE_DATABASE, DBIcon.TREE_DATABASE_CATEGORY, DBIcon.TREE_DATABASE_SHARED}), //$NON-NLS-1$
        Map.entry("schema", new DBIcon[]{DBIcon.TREE_SCHEMA, DBIcon.TREE_SCHEMA_SHARED, DBIcon.TREE_SCHEMA_SYSTEM, DBIcon.TREE_SCHEMA_UTIL}), //$NON-NLS-1$
        Map.entry("folder", new DBIcon[]{DBIcon.TREE_FOLDER, DBIcon.TREE_FOLDER_LINK, DBIcon.TREE_FOLDER_DATABASE, //$NON-NLS-1$
            DBIcon.TREE_FOLDER_SCHEMA, DBIcon.TREE_FOLDER_TABLE, DBIcon.TREE_FOLDER_VIEW, DBIcon.TREE_FOLDER_USER,
            DBIcon.TREE_FOLDER_ADMIN, DBIcon.TREE_FOLDER_CONSTRAINT, DBIcon.TREE_FOLDER_INFO, DBIcon.TREE_FOLDER_KEY}),
        Map.entry("table", new DBIcon[]{DBIcon.TREE_TABLE, DBIcon.TREE_TABLE_ALIAS, DBIcon.TREE_TABLE_LINK, //$NON-NLS-1$
            DBIcon.TREE_TABLE_SYSTEM, DBIcon.TREE_TABLE_EXTERNAL, DBIcon.TREE_TABLE_SHARED, DBIcon.TREE_PARTITION}),
        Map.entry("view", new DBIcon[]{DBIcon.TREE_VIEW, DBIcon.TREE_VIEW_SHARED}), //$NON-NLS-1$
        Map.entry(COLUMN_ICON, new DBIcon[]{DBIcon.TREE_COLUMN, DBIcon.TREE_COLUMNS, DBIcon.TREE_DERIVED_COLUMN, DBIcon.TREE_ATTRIBUTE,
            DBIcon.TYPE_BOOLEAN, DBIcon.TYPE_NUMBER, DBIcon.TYPE_STRING, DBIcon.TYPE_DATETIME, DBIcon.TYPE_BINARY, DBIcon.TYPE_TEXT,
            DBIcon.TYPE_JSON, DBIcon.TYPE_XML, DBIcon.TYPE_LOB, DBIcon.TYPE_ARRAY, DBIcon.TYPE_STRUCT, DBIcon.TYPE_DOCUMENT,
            DBIcon.TYPE_OBJECT, DBIcon.TYPE_IMAGE, DBIcon.TYPE_REFERENCE, DBIcon.TYPE_ROWID, DBIcon.TYPE_ANY, DBIcon.TYPE_UUID,
            DBIcon.TYPE_UNKNOWN}),
        Map.entry("column-fk", new DBIcon[]{DBIcon.TREE_FOREIGN_KEY_COLUMN}), //$NON-NLS-1$
        Map.entry("unique", new DBIcon[]{DBIcon.TREE_UNIQUE_KEY, DBIcon.TREE_KEY}), //$NON-NLS-1$
        Map.entry("foreign-key", new DBIcon[]{DBIcon.TREE_FOREIGN_KEY, DBIcon.TREE_REFERENCE, DBIcon.TREE_ASSOCIATION}), //$NON-NLS-1$
        Map.entry("check", new DBIcon[]{DBIcon.TREE_CONSTRAINT}), //$NON-NLS-1$
        Map.entry("index", new DBIcon[]{DBIcon.TREE_INDEX}), //$NON-NLS-1$
        Map.entry("function", new DBIcon[]{DBIcon.TREE_FUNCTION}), //$NON-NLS-1$
        Map.entry("procedure", new DBIcon[]{DBIcon.TREE_PROCEDURE}), //$NON-NLS-1$
        Map.entry("trigger", new DBIcon[]{DBIcon.TREE_TRIGGER}), //$NON-NLS-1$
        Map.entry("sequence", new DBIcon[]{DBIcon.TREE_SEQUENCE}), //$NON-NLS-1$
        Map.entry("datatype", new DBIcon[]{DBIcon.TREE_DATA_TYPE}), //$NON-NLS-1$
        Map.entry("user", new DBIcon[]{DBIcon.TREE_USER}), //$NON-NLS-1$
        Map.entry("role", new DBIcon[]{DBIcon.TREE_USER_GROUP}), //$NON-NLS-1$
        Map.entry("error", new DBIcon[]{DBIcon.STATUS_ERROR}), //$NON-NLS-1$
        Map.entry("warning", new DBIcon[]{DBIcon.STATUS_WARNING}), //$NON-NLS-1$
        Map.entry("info", new DBIcon[]{DBIcon.STATUS_INFO}) //$NON-NLS-1$
    );

    private static final Map<String, Icon> iconCache = new HashMap<>();
    // Model icon location -> Chromite icon id, built from UI_ICON_IDS on first use
    @Nullable
    private static Map<String, String> uiIconIds;

    private NodeIcons() {
    }

    /**
     * Icon for a model image; never {@code null}. Model icons with a Chromite icon ({@link #UI_ICON_IDS}) use the
     * current icon pack ({@link UiIcons}); others (driver logos, rare objects) are the model's own files.
     */
    @NotNull
    static Icon getIcon(@Nullable DBPImage image) {
        if (image instanceof DBIconComposite composite) {
            String uiIconId = getUiIconId(composite.getMain());
            DBPImage bottomRight = composite.getBottomRight();
            if (COLUMN_ICON.equals(uiIconId) && (DBIcon.OVER_KEY.equals(bottomRight) || DBIcon.OVER_REFERENCE.equals(bottomRight))) {
                // Key columns have their own icons instead of the key overlay (JDBCAttribute.getOverlayImage)
                uiIconId = DBIcon.OVER_KEY.equals(bottomRight) ? "column-pk" : "column-fk"; //$NON-NLS-1$ //$NON-NLS-2$
                bottomRight = null;
            }
            Icon main = uiIconId != null ? UiIcons.get(uiIconId) : getIcon(composite.getMain());
            if (composite.getTopLeft() == null && composite.getTopRight() == null && composite.getBottomLeft() == null && bottomRight == null) {
                return main;
            }
            return new CompositeIcon(
                main,
                getOverlay(composite.getTopLeft()),
                getOverlay(composite.getTopRight()),
                getOverlay(composite.getBottomLeft()),
                getOverlay(bottomRight));
        }
        if (image == null) {
            return getUnknownIcon();
        }
        String uiIconId = getUiIconId(image);
        if (uiIconId != null) {
            return UiIcons.get(uiIconId);
        }
        Icon icon = getIconByLocation(image.getLocation());
        return icon != null ? icon : getUnknownIcon();
    }

    /**
     * Object Explorer icon of a navigator node: the node's model icon mapped to the icon pack, refined by the kind of
     * object where the model icon does not tell (primary key or unique key, function or procedure), object folders as
     * open or closed folders, and connections as their driver logo with a state dot instead of DBeaver's overlays.
     *
     * @param expanded the tree node is expanded (folders show an open folder)
     */
    @NotNull
    static Icon getNodeIcon(@NotNull DBNNode node, boolean expanded) {
        if (node instanceof DBNDataSource dataSourceNode) {
            return getDataSourceIcon(dataSourceNode.getDataSourceContainer());
        }
        if (node instanceof DBNDatabaseFolder || node instanceof DBNLocalFolder) {
            return UiIcons.get(expanded ? "folder-open" : "folder"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (node instanceof DBNDatabaseNode databaseNode) {
            String uiIconId = getObjectIconId(databaseNode.getObject());
            if (uiIconId != null) {
                return UiIcons.get(uiIconId);
            }
        }
        return getIcon(node.getNodeIcon());
    }

    /**
     * Driver logo (nominative use; the generic connection icon when the driver has none) with the connection state:
     * green connected, amber connecting, red after a failed connect.
     */
    @NotNull
    static Icon getDataSourceIcon(@NotNull DBPDataSourceContainer container) {
        Icon icon = getIcon(container.getDriver().getIcon());
        UiIcons.Status status = null;
        if (container.isConnecting()) {
            status = UiIcons.Status.CONNECTING;
        } else if (container.isConnected()) {
            status = UiIcons.Status.CONNECTED;
        } else if (container instanceof DBPStatefulObject stateful && stateful.getObjectState() == DBSObjectState.INVALID) {
            status = UiIcons.Status.ERROR;
        }
        return status == null ? icon : UiIcons.withStatusDot(icon, status);
    }

    /**
     * Icon id for objects whose model icon is shared by different kinds.
     */
    @Nullable
    private static String getObjectIconId(@Nullable DBSObject object) {
        if (object instanceof DBSEntityConstraint constraint && !(object instanceof DBSTableIndex)) {
            DBSEntityConstraintType type = constraint.getConstraintType();
            if (type == DBSEntityConstraintType.PRIMARY_KEY || type == DBSEntityConstraintType.VIRTUAL_KEY) {
                return "primary-key"; //$NON-NLS-1$
            } else if (type.isAssociation()) {
                return "foreign-key"; //$NON-NLS-1$
            } else if (type == DBSEntityConstraintType.CHECK) {
                return "check"; //$NON-NLS-1$
            } else if (type.isUnique()) {
                return "unique"; //$NON-NLS-1$
            }
            return null;
        }
        if (object instanceof DBSTableIndex) {
            return "index"; //$NON-NLS-1$
        }
        if (object instanceof DBSProcedure procedure) {
            return procedure.getProcedureType() == DBSProcedureType.FUNCTION ? "function" : "procedure"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (object instanceof DBSTrigger) {
            return "trigger"; //$NON-NLS-1$
        }
        if (object instanceof DBSSequence) {
            return "sequence"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The Chromite icon of a model icon, or null if it has none.
     */
    @Nullable
    private static String getUiIconId(@Nullable DBPImage image) {
        return image == null ? null : getUiIconIds().get(image.getLocation());
    }

    @NotNull
    private static Map<String, String> getUiIconIds() {
        if (uiIconIds == null) {
            Map<String, String> ids = new HashMap<>();
            for (Map.Entry<String, DBIcon[]> entry : UI_ICON_IDS.entrySet()) {
                for (DBIcon icon : entry.getValue()) {
                    ids.put(icon.getLocation(), entry.getKey());
                }
            }
            uiIconIds = ids;
        }
        return uiIconIds;
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
