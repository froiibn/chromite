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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.erd.ERDPersistedState;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.virtual.DBVObject;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.dbeaver.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.xml.XMLBuilder;
import org.jkiss.utils.xml.XMLException;
import org.jkiss.utils.xml.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;

/**
 * ER 다이어그램 배치의 저장 형식: DBeaver의 .erd 파일 형식(버전 1 XML)을 그대로 읽고 쓴다.
 *
 * 설계:
 * <ul>
 *     <li>형식의 태그·속성 이름은 모델 번들의 {@link ERDPersistedState}에 있지만, 읽고 쓰는 코드(DiagramLoader)는 SWT·draw2d에 묶인
 *     ui.editors.erd 번들에 있다. 그래서 같은 형식을 UI 없이 다시 구현했다: {@code diagram(version=1)} &gt;
 *     {@code entities} &gt; {@code data-source(id)} &gt; {@code entity(id, name, fq-name, x, y)} &gt; {@code path(name)}(가까운 부모부터),
 *     {@code relations} &gt; {@code relation(name, fq-name, type, pk-ref, fk-ref)}. 색·글꼴·꺾인 점은 Swing 화면에 없으므로 쓰지 않는다.</li>
 *     <li>저장 위치: SWT "View Diagram"(ERDEditorEmbedded.doSave)과 같이 루트 객체(스키마 또는 테이블)의 가상 모델 속성
 *     {@value #PROP_DIAGRAM_STATE}의 {@value #PROP_SERIALIZED} 값이다. 연결 설정(data-sources.json)과 함께 저장되므로
 *     SWT 화면과 Swing 화면이 같은 배치를 열고, 작업 공간 설정에 따로 두지 않는다.</li>
 *     <li>같은 XML을 파일(.erd)로도 쓸 수 있다(내보내기). SWT의 프로젝트 "Diagrams" 폴더에 두면 SWT 다이어그램 편집기가 연다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-erd.md)
 */
final class ErdDiagramState {

    private static final Log log = Log.getLog(ErdDiagramState.class);

    /**
     * Virtual model property of the SWT embedded diagram (ERDEditorEmbedded.PROP_DIAGRAM_STATE).
     */
    static final String PROP_DIAGRAM_STATE = "erd.diagram.state"; //$NON-NLS-1$
    static final String PROP_SERIALIZED = "serialized"; //$NON-NLS-1$

    private ErdDiagramState() {
    }

    /**
     * The saved layout of a diagram root, or null. Does not create virtual model objects.
     */
    @Nullable
    static String read(@NotNull DBSObject root) {
        DBVObject vObject = ErdDiagramModel.getVirtualObject(root, false);
        if (vObject == null) {
            return null;
        }
        Map<String, Object> state = vObject.getProperty(PROP_DIAGRAM_STATE);
        if (state == null) {
            return null;
        }
        Object serialized = state.get(PROP_SERIALIZED);
        return serialized instanceof String text && !text.isEmpty() ? text : null;
    }

    /**
     * Saves the layout with the connection configuration (virtual model of the root object).
     */
    static void save(@NotNull ErdDiagramModel model) throws IOException {
        DBVObject vObject = ErdDiagramModel.getVirtualObject(model.root, true);
        if (vObject == null) {
            throw new IOException("No virtual model for " + model.root.getName());
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(PROP_SERIALIZED, serialize(model, model.root.getName(), true));
        vObject.setProperty(PROP_DIAGRAM_STATE, state);
        vObject.persistConfiguration();
    }

    /**
     * The diagram as .erd XML.
     *
     * @param compact one line with a time stamp (the virtual model value, as SWT), otherwise indented (a file)
     */
    @NotNull
    static String serialize(@NotNull ErdDiagramModel model, @NotNull String name, boolean compact) throws IOException {
        StringWriter out = new StringWriter(1000);
        XMLBuilder xml = new XMLBuilder(out, GeneralUtils.UTF8_ENCODING, !compact);
        xml.setBeautify(!compact);
        xml.startElement(ERDPersistedState.TAG_DIAGRAM);
        xml.addAttribute(ERDPersistedState.ATTR_VERSION, ERDPersistedState.ERD_VERSION_1);
        xml.addAttribute(ERDPersistedState.ATTR_NAME, name);
        if (compact) {
            xml.addAttribute(ERDPersistedState.ATTR_TIME, RuntimeUtils.getCurrentTimeStamp());
        }

        // Entities by data source, numbered from 1 as SWT does
        Map<String, List<ErdDiagramModel.Entity>> byDataSource = new LinkedHashMap<>();
        for (ErdDiagramModel.Entity entity : model.entities) {
            byDataSource.computeIfAbsent(entity.dataSourceId, id -> new ArrayList<>()).add(entity);
        }
        Map<ErdDiagramModel.Entity, Integer> ids = new IdentityHashMap<>();
        xml.startElement(ERDPersistedState.TAG_ENTITIES);
        for (Map.Entry<String, List<ErdDiagramModel.Entity>> entry : byDataSource.entrySet()) {
            xml.startElement(ERDPersistedState.TAG_DATA_SOURCE);
            xml.addAttribute(ERDPersistedState.ATTR_ID, entry.getKey());
            for (ErdDiagramModel.Entity entity : entry.getValue()) {
                int id = ids.size() + 1;
                ids.put(entity, id);
                xml.startElement(ERDPersistedState.TAG_ENTITY);
                xml.addAttribute(ERDPersistedState.ATTR_ID, id);
                xml.addAttribute(ERDPersistedState.ATTR_NAME, entity.object.getName());
                if (entity.qualifiedName != null) {
                    xml.addAttribute(ERDPersistedState.ATTR_FQ_NAME, entity.qualifiedName);
                }
                xml.addAttribute(ERDPersistedState.ATTR_X, (int) Math.round(entity.x));
                xml.addAttribute(ERDPersistedState.ATTR_Y, (int) Math.round(entity.y));
                for (String parent : entity.path) {
                    xml.startElement(ERDPersistedState.TAG_PATH);
                    xml.addAttribute(ERDPersistedState.ATTR_NAME, parent);
                    xml.endElement();
                }
                xml.endElement();
            }
            xml.endElement();
        }
        xml.endElement();

        xml.startElement(ERDPersistedState.TAG_RELATIONS);
        for (ErdDiagramModel.Link link : model.links) {
            xml.startElement(ERDPersistedState.TAG_RELATION);
            xml.addAttribute(ERDPersistedState.ATTR_NAME, link.name());
            if (link.qualifiedName() != null) {
                xml.addAttribute(ERDPersistedState.ATTR_FQ_NAME, link.qualifiedName());
            }
            xml.addAttribute(ERDPersistedState.ATTR_TYPE, link.typeId());
            xml.addAttribute(ERDPersistedState.ATTR_PK_REF, ids.get(link.target()));
            xml.addAttribute(ERDPersistedState.ATTR_FK_REF, ids.get(link.source()));
            xml.endElement();
        }
        xml.endElement();

        xml.endElement();
        xml.flush();
        return out.toString();
    }

    /**
     * The table positions of a saved layout by {@link ErdDiagramModel#layoutKey}; tables saved without a position are left out.
     */
    @NotNull
    static Map<String, Point2D> parsePositions(@NotNull String serialized) throws DBException {
        Document document;
        try {
            document = XMLUtils.parseDocument(new StringReader(serialized));
        } catch (XMLException e) {
            throw new DBException("Can't parse the saved diagram", e);
        }
        Element diagramElem = document.getDocumentElement();
        if (!String.valueOf(ERDPersistedState.ERD_VERSION_1).equals(diagramElem.getAttribute(ERDPersistedState.ATTR_VERSION))) {
            throw new DBException("Unsupported diagram version: " + diagramElem.getAttribute(ERDPersistedState.ATTR_VERSION));
        }
        Map<String, Point2D> positions = new HashMap<>();
        Element entitiesElem = XMLUtils.getChildElement(diagramElem, ERDPersistedState.TAG_ENTITIES);
        if (entitiesElem == null) {
            return positions;
        }
        for (Element dsElem : XMLUtils.getChildElementList(entitiesElem, ERDPersistedState.TAG_DATA_SOURCE)) {
            String dsId = dsElem.getAttribute(ERDPersistedState.ATTR_ID);
            for (Element entityElem : XMLUtils.getChildElementList(dsElem, ERDPersistedState.TAG_ENTITY)) {
                String x = entityElem.getAttribute(ERDPersistedState.ATTR_X);
                String y = entityElem.getAttribute(ERDPersistedState.ATTR_Y);
                if (CommonUtils.isEmpty(x) || CommonUtils.isEmpty(y)) {
                    continue;
                }
                List<String> path = new ArrayList<>();
                for (Element pathElem : XMLUtils.getChildElementList(entityElem, ERDPersistedState.TAG_PATH)) {
                    path.add(pathElem.getAttribute(ERDPersistedState.ATTR_NAME));
                }
                try {
                    positions.put(
                        ErdDiagramModel.layoutKey(dsId, path, entityElem.getAttribute(ERDPersistedState.ATTR_NAME)),
                        new Point2D.Double(Integer.parseInt(x), Integer.parseInt(y)));
                } catch (NumberFormatException e) {
                    log.debug("Bad position of " + entityElem.getAttribute(ERDPersistedState.ATTR_NAME), e);
                }
            }
        }
        return positions;
    }
}
