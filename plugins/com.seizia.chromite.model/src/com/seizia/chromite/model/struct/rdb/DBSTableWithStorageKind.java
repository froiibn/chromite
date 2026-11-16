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
package com.seizia.chromite.model.struct.rdb;

import org.jkiss.code.Nullable;

/**
 * A table that knows its {@link DBSTableStorageKind}.
 *
 * 설계: 탐색기 트리가 노드마다 그리므로 구현은 목록을 읽을 때 함께 읽은 값을 돌려줘야 한다(질의 금지, 그래서 monitor
 * 인자가 없다). 화면 쪽은 이 인터페이스만 보고 색과 툴팁을 정하므로 다른 DBMS도 구현만 하면 같은 표시를 얻는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: Object Explorer의 Altibase 메모리/디스크 테이블 색 구분 (docs/chromite/altibase.md)
 */
public interface DBSTableWithStorageKind {

    /**
     * @return the storage kind read with the table list, or null when it is not known (a new table, an unknown tablespace)
     */
    @Nullable
    DBSTableStorageKind getStorageKind();
}
