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
package com.seizia.chromite.ext.shardsphere.internal;

import org.eclipse.osgi.util.NLS;

/**
 * Values the sharding explorer shows: the names of the sharding dictionary's codes and of the distribution kinds.
 *
 * 설계: 속성 이름은 {@code @Property}의 규칙대로 {@code OSGI-INF/l10n/bundle*.properties}에 있고, 여기에는 속성 값으로 보이는 낱말만 둔다
 * (사전의 한 글자 코드를 이름으로). 서버에서 본 코드와 매뉴얼의 식별자로만 아는 코드를 함께 둔다(docs/chromite/shardsphere.md "샤딩 탐색기").
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: 객체 종류, 분산 방식, 분산 행의 종류(앱 변경 #139 작업 9)
 */
public class ShardSphereMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ext.shardsphere.internal.ShardSphereMessages"; //$NON-NLS-1$

    // OBJECTS_.OBJECT_TYPE
    public static String shard_object_kind_table;
    public static String shard_object_kind_procedure;

    // OBJECTS_.SPLIT_METHOD and SUB_SPLIT_METHOD
    public static String shard_split_hash;
    public static String shard_split_range;
    public static String shard_split_list;
    public static String shard_split_clone;
    public static String shard_split_solo;

    // Where a distribution row comes from
    public static String shard_placement_value;
    public static String shard_placement_clone;
    public static String shard_placement_solo;
    public static String shard_placement_default;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ShardSphereMessages.class);
    }

    private ShardSphereMessages() {
    }
}
