/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.virtual;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.utils.GeneralUtils;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.xml.XMLBuilder;

import java.io.IOException;
import java.util.Map;

/**
 * DBVModelSerializerLegacy
 *
 * 변경 이력:
 * - 2026-11-07 옛 XML 연결 설정 읽기({@code DataSourceSerializerLegacy})만 쓰던 가상 모델 XML 파서를 지움
 *   (docs/chromite/internal-rename.md "사용자 데이터 이전")
 */
@Deprecated
class DBVModelSerializerLegacy implements DBVModelSerializer {

    static void serializeContainer(@NotNull XMLBuilder xml, @NotNull DBVContainer object) throws IOException {
        if (!object.hasValuableData()) {
            // nothing to save
            return;
        }
        try (var ignored = xml.startElement(TAG_CONTAINER)) {
            xml.addAttribute(ATTR_NAME, object.getName());
            // Containers
            for (DBVContainer container : object.getContainers()) {
                serializeContainer(xml, container);
            }

            for (DBVEntity entity : object.getEntities()) {
                if (entity.hasValuableData()) {
                    serializeEntity(xml, entity);
                }
            }
        }
    }

    private static void serializeEntity(@NotNull XMLBuilder xml, @NotNull DBVEntity entity) throws IOException {
        try (var ignored0 = xml.startElement(TAG_ENTITY)) {
            xml.addAttribute(ATTR_NAME, entity.getName());
            if (!CommonUtils.isEmpty(entity.getDescriptionColumnNames())) {
                xml.addAttribute(ATTR_DESCRIPTION, entity.getDescriptionColumnNames());
            }
            if (!CommonUtils.isEmpty(entity.getProperties())) {
                for (Map.Entry<String, Object> prop : entity.getProperties().entrySet()) {
                    try (var ignored2 = xml.startElement(TAG_PROPERTY)) {
                        xml.addAttribute(ATTR_NAME, prop.getKey());
                        xml.addAttribute(ATTR_VALUE, CommonUtils.toString(prop.getValue()));
                    }
                }
            }
            // Attributes
            for (DBVEntityAttribute attr : CommonUtils.safeCollection(entity.getEntityAttributes())) {
                if (!attr.hasValuableData()) {
                    continue;
                }
                try (var ignored3 = xml.startElement(TAG_ATTRIBUTE)) {
                    xml.addAttribute(ATTR_NAME, attr.getName());
                    final DBVTransformSettings transformSettings = attr.getTransformSettings();
                    if (transformSettings != null && transformSettings.hasValuableData()) {
                        try (var ignored4 = xml.startElement(TAG_TRANSFORM)) {
                            if (!CommonUtils.isEmpty(transformSettings.getCustomTransformer())) {
                                xml.addAttribute(ATTR_CUSTOM, transformSettings.getCustomTransformer());
                            }
                            for (String id : CommonUtils.safeCollection(transformSettings.getIncludedTransformers())) {
                                try (var ignored5 = xml.startElement(TAG_INCLUDE)) {
                                    xml.addAttribute(ATTR_ID, id);
                                }
                            }
                            for (String id : CommonUtils.safeCollection(transformSettings.getExcludedTransformers())) {
                                try (var ignored5 = xml.startElement(TAG_EXCLUDE)) {
                                    xml.addAttribute(ATTR_ID, id);
                                }
                            }
                            final Map<String, Object> transformOptions = transformSettings.getTransformOptions();
                            if (transformOptions != null) {
                                for (Map.Entry<String, Object> prop : transformOptions.entrySet()) {
                                    try (var ignored5 = xml.startElement(TAG_PROPERTY)) {
                                        if (prop.getValue() != null) {
                                            xml.addAttribute(ATTR_NAME, prop.getKey());
                                            xml.addAttribute(ATTR_VALUE, CommonUtils.toString(prop.getValue()));
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!CommonUtils.isEmpty(attr.getProperties())) {
                        for (Map.Entry<String, Object> prop : attr.getProperties().entrySet()) {
                            try (var ignored = xml.startElement(TAG_PROPERTY)) {
                                xml.addAttribute(ATTR_NAME, prop.getKey());
                                xml.addAttribute(ATTR_VALUE, CommonUtils.toString(prop.getValue()));
                            }
                        }
                    }
                }
            }
            // Constraints
            for (DBVEntityConstraint c : CommonUtils.safeCollection(entity.getConstraints())) {
                if (c.hasAttributes()) {
                    try (var ignored = xml.startElement(TAG_CONSTRAINT)) {
                        xml.addAttribute(ATTR_NAME, c.getName());
                        xml.addAttribute(ATTR_TYPE, c.getConstraintType().getName());
                        for (DBVEntityConstraintColumn cc : CommonUtils.safeCollection(c.getAttributeReferences(null))) {
                            try (var ignored2 = xml.startElement(TAG_ATTRIBUTE)) {
                                xml.addAttribute(ATTR_NAME, cc.getAttributeName());
                            }
                        }
                    }
                }
            }
            // Foreign keys
            for (DBVEntityForeignKey fk : CommonUtils.safeCollection(entity.getForeignKeys())) {
                try (var ignored = xml.startElement(TAG_ASSOCIATION)) {
                    DBSEntity refEntity = fk.getAssociatedEntity();
                    if (refEntity != null) {
                        xml.addAttribute(ATTR_ENTITY, DBUtils.getObjectFullId(refEntity));
                    }
                    DBSEntityConstraint refConstraint = fk.getReferencedConstraint();
                    if (refConstraint != null) {
                        xml.addAttribute(ATTR_CONSTRAINT, refConstraint.getName());
                    }
                    for (DBVEntityForeignKeyColumn cc : CommonUtils.safeCollection(fk.getAttributes())) {
                        try (var ignored2 = xml.startElement(TAG_ATTRIBUTE)) {
                            xml.addAttribute(ATTR_NAME, cc.getAttributeName());
                        }
                    }
                }
            }
            // Colors
            if (!CommonUtils.isEmpty(entity.getColorOverrides())) {
                try (var ignored = xml.startElement(TAG_COLORS)) {
                    for (DBVColorOverride color : entity.getColorOverrides()) {
                        try (var ignored2 = xml.startElement(TAG_COLOR)) {
                            xml.addAttribute(ATTR_NAME, color.getAttributeName());
                            xml.addAttribute(ATTR_OPERATOR, color.getOperator().name());
                            if (color.isRange()) {
                                xml.addAttribute(ATTR_RANGE, true);
                            }
                            if (color.isSingleColumn()) {
                                xml.addAttribute(ATTR_SINGLE_COLUMN, true);
                            }
                            if (color.getColorForeground() != null) {
                                xml.addAttribute(ATTR_FOREGROUND, color.getColorForeground());
                            }
                            if (color.getColorForeground2() != null) {
                                xml.addAttribute(ATTR_FOREGROUND2, color.getColorForeground2());
                            }
                            if (color.getColorBackground() != null) {
                                xml.addAttribute(ATTR_BACKGROUND, color.getColorBackground());
                            }
                            if (color.getColorBackground2() != null) {
                                xml.addAttribute(ATTR_BACKGROUND2, color.getColorBackground2());
                            }
                            if (!ArrayUtils.isEmpty(color.getAttributeValues())) {
                                for (Object value : color.getAttributeValues()) {
                                    if (value == null) {
                                        continue;
                                    }
                                    try (var ignored3 = xml.startElement(TAG_VALUE)) {
                                        xml.addText(GeneralUtils.serializeObject(value));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}
