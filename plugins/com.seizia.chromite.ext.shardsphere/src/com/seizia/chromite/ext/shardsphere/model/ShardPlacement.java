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
package com.seizia.chromite.ext.shardsphere.model;

import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.shardsphere.internal.ShardSphereMessages;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * One row of a shard object's distribution: a value (and sub value) and its node ({@code SYS_SHARD.RANGES_}), a clone
 * ({@code CLONES_}), a solo ({@code SOLOS_}) or the object's default node ({@code OBJECTS_.DEFAULT_NODE_ID}).
 *
 * 설계: 네 표가 같은 질문("이 객체의 데이터가 어느 노드에 있나")에 답하므로 한 목록으로 보인다. 이름은 값 행이면 값(트리에서 값의 순서가
 * 보이게), 그 밖의 행이면 노드 이름이다(복제·독립·기본 노드는 값이 없다). 노드는 이름이 없으면(사전에 노드 행이 없음) 번호로 보인다.
 * 서브 값의 {@code $$N/A}(서브 샤드 키가 없다는 표시)는 빈 값이다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성(앱 변경 #139 작업 9, docs/chromite/shardsphere.md "샤딩 탐색기")
 */
public class ShardPlacement implements DBSObject {

    /**
     * Where a row comes from, in the order the distribution lists them.
     */
    public enum Kind {
        VALUE,
        CLONE,
        SOLO,
        DEFAULT_NODE;

        @NotNull
        String getLabel() {
            return switch (this) {
                case VALUE -> ShardSphereMessages.shard_placement_value;
                case CLONE -> ShardSphereMessages.shard_placement_clone;
                case SOLO -> ShardSphereMessages.shard_placement_solo;
                case DEFAULT_NODE -> ShardSphereMessages.shard_placement_default;
            };
        }
    }

    @NotNull
    private final ShardObject shardObject;
    @NotNull
    private final Kind kind;
    @Nullable
    private final String value;
    @Nullable
    private final String subValue;
    @Nullable
    private final Integer nodeId;
    @Nullable
    private final String nodeName;

    ShardPlacement(
        @NotNull ShardObject shardObject,
        @NotNull Kind kind,
        @Nullable String value,
        @Nullable String subValue,
        @Nullable Integer nodeId,
        @Nullable String nodeName
    ) {
        this.shardObject = shardObject;
        this.kind = kind;
        this.value = value;
        this.subValue = subValue;
        this.nodeId = nodeId;
        this.nodeName = nodeName;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        if (kind == Kind.VALUE) {
            return value == null ? "" : value;
        }
        return getNode();
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public String getKind() {
        return kind.getLabel();
    }

    @Nullable
    @Property(viewable = true, order = 3)
    public String getValue() {
        return value;
    }

    @Nullable
    @Property(viewable = true, order = 4)
    public String getSubValue() {
        return subValue;
    }

    @NotNull
    @Property(viewable = true, order = 5)
    public String getNode() {
        if (nodeName != null) {
            return nodeName;
        }
        return nodeId == null ? "" : String.valueOf(nodeId);
    }

    @NotNull
    Kind getPlacementKind() {
        return kind;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public ShardObject getParentObject() {
        return shardObject;
    }

    @NotNull
    @Override
    public AltibaseDataSource getDataSource() {
        return shardObject.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

}
