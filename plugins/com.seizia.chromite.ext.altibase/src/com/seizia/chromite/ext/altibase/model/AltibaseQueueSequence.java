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
package com.seizia.chromite.ext.altibase.model;

import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;

/**
 * The internal sequence of a queue ({@code <queue>_NEXT_MSG_ID}): created by {@code CREATE QUEUE}, removed by
 * {@code DROP QUEUE}, and not to be changed by users (the family's manuals).
 *
 * 설계: 삭제·변경 명령이 이 시퀀스에는 해당하지 않으므로(docs/chromite/context-menus.md "해당하는가 → 보임") 다른 종류(클래스)로 둔다.
 * 객체 관리자 등록이 이 클래스에 정확히 맞는 관리자({@code AltibaseQueueSequenceManager}, 만들기·지우기·고치기·이름 바꾸기를 하지 않음)를
 * 먼저 고르므로, 보통 시퀀스의 관리자가 이어받지 않는다: Delete·Rename이 메뉴에 없고 속성은 읽기 전용이다. 이름으로 정하지 않는다: 시퀀스
 * 목록 질의가 같은 스키마에 그 이름의 큐({@code TABLE_TYPE = 'Q'})가 있는지를 카탈로그에서 읽는다({@link AltibaseMetaModel}).
 * Altibase 7.3은 이 시퀀스를 {@code TABLE_TYPE = 'W'}로 둔다(2026-11-17 확인, 전에는 시퀀스 목록에 나오지 않았음). 목록은 'S'와 큐의 것인
 * 'W'를 읽으므로 'S'로 두는 서버에서도 같은 판단이다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성(앱 변경 #139 수정 4, docs/chromite/altibase.md "큐의 내부 시퀀스")
 */
public class AltibaseQueueSequence extends AltibaseSequence {

    public AltibaseQueueSequence(GenericStructContainer container, JDBCResultSet dbResult) {
        super(container, dbResult);
    }

}
