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

import com.seizia.chromite.ext.altibase.model.AltibaseProperty;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import org.jkiss.code.NotNull;

/**
 * A sharding property ({@code SHARD_*} of {@code V$PROPERTY}) under the ShardSphere node.
 *
 * 설계: 값·동적 여부·이름은 System Info의 Properties와 같은 {@link AltibaseProperty}가 읽고 보인다(같은 행, 같은 열 이름과 번역). 부모만
 * ShardSphere 노드로 바꾼다: 부모가 데이터 원본이면 트리가 이 노드를 다른 부모의 객체로 보고 이름 앞에 연결 이름을 붙인다
 * ({@code DBNDatabaseNode.getPlainNodeName}의 가상 노드 규칙).
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성(앱 변경 #139 작업 9, docs/chromite/shardsphere.md "샤딩 탐색기")
 */
public class ShardingProperty extends AltibaseProperty {

    @NotNull
    private final ShardSphereServer server;

    ShardingProperty(@NotNull ShardSphereServer server, @NotNull JDBCResultSet dbResult) {
        super(server.getDataSource(), dbResult);
        this.server = server;
    }

    @NotNull
    @Override
    public ShardSphereServer getParentObject() {
        return server;
    }

}
