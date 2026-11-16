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
package com.seizia.chromite.ext.altibase.extension;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.model.struct.DBSObject;

import java.util.Collection;
import java.util.List;

/**
 * One connection's state of a detected {@link AltibaseServerExtension}: what the extension found on the server and
 * what it contributes to that connection.
 *
 * 설계: 데이터 원본 인스턴스가 연결마다 이 객체를 갖고({@link AltibaseDataSource#getAdapter}, {@link AltibaseDataSource#getExtensionObjects})
 * 새로 고침과 다시 연결에서 버린다. 그래서 같은 앱에 열린 Altibase 연결과 ShardSphere 연결이 서로의 답을 보지 않는다.
 * 메서드는 기본 구현이 "기여 없음"이라 확장은 쓰는 것만 구현한다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: 어댑터와 트리 객체(앱 변경 #139, docs/chromite/shardsphere.md)
 */
public interface AltibaseServerExtensionState {

    /**
     * An adapter of the connection's data source. Asked before the Altibase data source's own adapters, so an
     * extension can answer for its server what the family answers otherwise.
     *
     * @return the adapter, or null to leave the request to the next extension and then to the data source
     */
    @Nullable
    default <T> T getAdapter(@NotNull Class<T> adapter) {
        return null;
    }

    /**
     * Objects shown in Object Explorer under the connection's System Info folder (the tree host {@code serverExtension}
     * in ext.altibase's {@code plugin.xml}). Their {@code @Property} getters appear in the property view.
     */
    @NotNull
    default Collection<? extends DBSObject> getTreeObjects() {
        return List.of();
    }

}
