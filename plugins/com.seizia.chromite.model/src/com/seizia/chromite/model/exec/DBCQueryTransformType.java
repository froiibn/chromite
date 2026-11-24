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

package com.seizia.chromite.model.exec;

/**
 * Query transformation type
 *
 * 설계: {@link #SCRIPT_ROW_LIMIT}는 생성한 스크립트만 쓰는 행 수 제한이다. {@link #RESULT_SET_LIMIT}을 새로 답하면 "SQL로 가져오기 크기
 * 제한" 설정을 켠 사용자의 쿼리까지 바뀌므로(DBUtils.makeStatement), 엔진이 묻지 않는 따로 된 자리를 둔다.
 *
 * 변경 이력:
 * - 2026-11-24 SCRIPT_ROW_LIMIT 추가: 변환기가 없던 Oracle·Altibase 계열의 "상위 1000개 행 선택"이 제한 없는 SELECT를 열었다
 *   (앱 변경 #183, docs/chromite/swing-explorer-object-actions.md)
 */
public enum DBCQueryTransformType {

    FETCH_ALL_TABLE,
    RESULT_SET_LIMIT,
    ORDER_BY,
    FILTER,
    /**
     * Row limit written into the text of a generated script ("Select Top N Rows"), for a data source whose
     * {@link #RESULT_SET_LIMIT} is absent. The execution engine never asks for it, so answering it does not change
     * how the user's own queries are run or limited (RESULT_SET_LIMIT is what the engine writes into them when
     * "use SQL to limit fetch size" is on).
     */
    SCRIPT_ROW_LIMIT
    
}
