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
package com.seizia.chromite.tools.compare.schema;

import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One difference of a schema compare: an object that is only in the source, only in the target, or in both but different.
 *
 * 설계: 같은 객체는 항목으로 만들지 않는다(결과 트리와 스크립트에 쓸 것이 없음). 테이블은 열·키·외래 키·인덱스·트리거의 차이를
 * 자식 항목으로 갖고, 스크립트는 다른 테이블의 자식 선택만 따른다(테이블 전체를 다시 만들지 않는다).
 * 선택 기본값은 "대상에서 무언가 지워지는 항목"(대상에만 있음)만 꺼져 있다. 사용자가 일부러 골라야 DROP이 스크립트에 들어간다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/schema-compare.md)
 */
public final class SchemaCompareItem {

    public enum Kind {
        TABLE,
        VIEW,
        ROUTINE,
        SEQUENCE,
        COLUMN,
        PRIMARY_KEY,
        FOREIGN_KEY,
        INDEX,
        TRIGGER
    }

    public enum Status {
        ONLY_IN_SOURCE,
        ONLY_IN_TARGET,
        DIFFERENT
    }

    /**
     * What differs between the source and the target object.
     */
    public enum Property {
        TYPE,
        NULLABLE,
        DEFAULT,
        COLUMNS,
        UNIQUE,
        REFERENCE,
        DELETE_RULE,
        UPDATE_RULE,
        DEFINITION,
        INCREMENT,
        MIN_VALUE,
        MAX_VALUE
    }

    /**
     * @param sourceValue the value in the source (the script makes the target like it)
     * @param targetValue the value in the target now
     */
    public record Difference(@NotNull Property property, @Nullable String sourceValue, @Nullable String targetValue) {
    }

    private final Kind kind;
    private final String name;
    private final Status status;
    @Nullable
    private final DBSObject source;
    @Nullable
    private final DBSObject target;
    @Nullable
    private SchemaCompareItem parent;
    private final List<SchemaCompareItem> children = new ArrayList<>();
    private final List<Difference> differences = new ArrayList<>();
    private boolean selected;

    SchemaCompareItem(@NotNull Kind kind, @NotNull String name, @NotNull Status status, @Nullable DBSObject source, @Nullable DBSObject target) {
        this.kind = kind;
        this.name = name;
        this.status = status;
        this.source = source;
        this.target = target;
        this.selected = status != Status.ONLY_IN_TARGET;
    }

    @NotNull
    public Kind getKind() {
        return kind;
    }

    @NotNull
    public String getName() {
        return name;
    }

    @NotNull
    public Status getStatus() {
        return status;
    }

    @Nullable
    public DBSObject getSource() {
        return source;
    }

    @Nullable
    public DBSObject getTarget() {
        return target;
    }

    @Nullable
    public SchemaCompareItem getParent() {
        return parent;
    }

    @NotNull
    public List<SchemaCompareItem> getChildren() {
        return Collections.unmodifiableList(children);
    }

    @NotNull
    public List<Difference> getDifferences() {
        return Collections.unmodifiableList(differences);
    }

    /**
     * True when the item goes into the script. A table with child differences is selected when one of its children is.
     */
    public boolean isSelected() {
        if (!children.isEmpty()) {
            return children.stream().anyMatch(SchemaCompareItem::isSelected);
        }
        return selected;
    }

    /**
     * Selects the item, or all its children.
     */
    public void setSelected(boolean selected) {
        this.selected = selected;
        for (SchemaCompareItem child : children) {
            child.setSelected(selected);
        }
    }

    void addChild(@NotNull SchemaCompareItem child) {
        child.parent = this;
        children.add(child);
    }

    void addDifference(@NotNull Property property, @Nullable String sourceValue, @Nullable String targetValue) {
        differences.add(new Difference(property, sourceValue, targetValue));
    }

    @Override
    public String toString() {
        return kind + " " + name + " " + status;
    }
}
