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

import com.seizia.chromite.model.DBPNamedObject;
import com.seizia.chromite.model.messages.ModelMessages;
import org.jkiss.code.NotNull;

/**
 * Where the rows of a table are kept, for databases that have more than one storage engine per table (Altibase memory,
 * disk and volatile tablespaces).
 *
 * 설계: 탐색기가 DBMS 이름으로 분기하지 않고 테이블의 저장 방식을 표시하도록(색, 툴팁, 속성) 모델에 둔 공통 값이다.
 * {@link DBPNamedObject}라 속성 창은 {@link #getName()}의 지역화된 이름을 보여 준다. 파티션마다 저장 방식이 다른
 * 테이블(Altibase 하이브리드 파티션드 테이블)은 한 가지로 말할 수 없으므로 {@link #HYBRID}로 둔다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: Object Explorer의 Altibase 메모리/디스크 테이블 색 구분 (docs/chromite/altibase.md)
 */
public enum DBSTableStorageKind implements DBPNamedObject {
    /** Rows are kept in memory (and checkpointed to disk) */
    MEMORY(ModelMessages.model_struct_storage_Memory),
    /** Rows are kept in disk pages */
    DISK(ModelMessages.model_struct_storage_Disk),
    /** Rows are kept in memory only and are lost when the server restarts */
    VOLATILE(ModelMessages.model_struct_storage_Volatile),
    /** Partitions of the table use more than one of the other kinds */
    HYBRID(ModelMessages.model_struct_storage_Hybrid);

    private final String name;

    DBSTableStorageKind(@NotNull String name) {
        this.name = name;
    }

    @NotNull
    @Override
    public String getName() {
        return name;
    }
}
