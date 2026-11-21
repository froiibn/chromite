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
import com.seizia.chromite.model.impl.jdbc.struct.JDBCTableColumn;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * A column of a stream table or continuous table as the schema's object list shows it ({@link StreamRelation}).
 *
 * 설계: 열은 드라이버의 {@code DatabaseMetaData.getColumns}가 준 값 그대로다. 일반 테이블의 열도 같은 호출로 읽으므로({@code GenericMetaModel})
 * 형식 이름·JDBC 형식이 같은 규칙으로 나온다(2026-11-22 시험용 개체로 확인: 네 종류 모두 서버의 자동 열 {@code $INSERT_TIME}을 첫 열로,
 * 집계 있는 연속 테이블은 자기 열을 준다). 읽기 전용이다(객체 관리자 없음).
 *
 * 변경 이력:
 * - 2026-11-22 최초 작성(앱 변경 #163, docs/chromite/shardsphere.md "자동 완성의 스트림 객체")
 */
public class StreamColumn extends JDBCTableColumn<StreamRelation> {

    StreamColumn(
        @NotNull StreamRelation relation,
        @NotNull String name,
        @NotNull String typeName,
        int valueType,
        int ordinalPosition,
        long maxLength,
        @Nullable Integer scale,
        @Nullable Integer precision,
        boolean required,
        @Nullable String defaultValue
    ) {
        super(relation, true, name, typeName, valueType, ordinalPosition, maxLength, scale, precision, required, false, defaultValue);
    }

    @NotNull
    @Override
    public AltibaseDataSource getDataSource() {
        return getTable().getDataSource();
    }

}
