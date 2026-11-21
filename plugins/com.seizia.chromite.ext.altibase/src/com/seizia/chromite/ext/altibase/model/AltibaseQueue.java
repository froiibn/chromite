/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ext.altibase.model;

import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericTable;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.Arrays;

/**
 * A queue ({@code SYS_TABLES_.TABLE_TYPE = 'Q'}).
 *
 * 설계: 새 큐(Object Explorer의 New Queue)는 이름과 메시지 크기만 받는다. 크기는 만들 때까지 여기에 둔다
 * ({@code AltibaseQueueManager}가 문장을 만든다).
 * 큐는 메시지를 ENQUEUE로 넣고 DEQUEUE로 꺼낸다. 행을 넣거나 바꾸는 문장은 받지 않으므로 데이터 기능에서 넣기·바꾸기를 뺀다
 * ({@link #getSupportedFeatures}). 가져오기, Generate SQL, 결과 그리드 편집이 이 답을 묻는다.
 *
 * 변경 이력:
 * - 2026-11-21 Chromite: 새 큐의 메시지 크기, 목록 종류 이름 상수(앱 변경 #154, docs/chromite/altibase.md "큐")
 * - 2026-11-22 Chromite: 데이터 기능에서 넣기·바꾸기를 뺌. 두 서버가 INSERT(201248)·UPDATE(200753 / 201854)·MERGE를 거절하는데
 *   가져오기·Generate SQL·그리드 편집이 그 문장을 만들었다 (앱 변경 #157, docs/chromite/altibase.md "큐")
 */
public class AltibaseQueue extends GenericTable implements DBPNamedObject2 {

    // The table type the table list gives a queue (AltibaseMetaModel)
    public static final String TABLE_TYPE_QUEUE = "QUEUE";

    // A new queue's message size (bytes) until it is created
    @Nullable
    private Integer initialMessageSize;

    public AltibaseQueue(GenericStructContainer container, String tableName, String tableType, JDBCResultSet dbResult) {
        super(container, tableName, tableType, dbResult);
    }

    @Override
    protected boolean isTruncateSupported() {
        return false;
    }

    /**
     * The table's features without inserted and updated rows: the servers refuse INSERT, UPDATE and MERGE on a queue
     * (messages go in with ENQUEUE). DELETE is accepted.
     */
    @NotNull
    @Override
    public String[] getSupportedFeatures() {
        return Arrays.stream(super.getSupportedFeatures())
            .filter(feature -> !FEATURE_DATA_INSERT.equals(feature) && !FEATURE_DATA_UPDATE.equals(feature))
            .toArray(String[]::new);
    }

    @Nullable
    public Integer getInitialMessageSize() {
        return initialMessageSize;
    }

    public void setInitialMessageSize(@Nullable Integer initialMessageSize) {
        this.initialMessageSize = initialMessageSize;
    }
}
