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
 * (사전의 한 글자 코드를 이름으로). 코드의 뜻은 시스피어 답변(2026-11-17)과 매뉴얼에 있는 것이다(docs/chromite/shardsphere.md "샤딩 탐색기").
 * 테이블 속성 기여의 이름은 {@code @Property}가 아니라 기여하는 쪽이 옮긴 글이라 여기에 둔다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: 객체 종류, 분산 방식, 분산 행의 종류(앱 변경 #139 작업 9)
 * - 2026-11-17 테이블 속성(TTL, 샤드 객체)의 이름과 TTL 단위(앱 변경 #139 수정 6)
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

    // A table's properties contributed by the ShardSphere bundle: TTL and shard object
    public static String table_category_ttl;
    public static String table_ttl_column;
    public static String table_ttl_period;
    public static String table_category_sharding;
    public static String table_split_method;
    public static String table_shard_key;
    public static String table_sub_split_method;
    public static String table_sub_shard_key;

    // SYS_TTL_.UNIT ({0}: the amount)
    public static String ttl_unit_year;
    public static String ttl_unit_quarter;
    public static String ttl_unit_month;
    public static String ttl_unit_week;
    public static String ttl_unit_day;
    public static String ttl_unit_hour;
    public static String ttl_unit_minute;
    public static String ttl_unit_second;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ShardSphereMessages.class);
    }

    private ShardSphereMessages() {
    }
}
