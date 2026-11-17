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
package com.seizia.chromite.ext.altibase.edit;

import com.seizia.chromite.ext.altibase.model.AltibaseQueueSequence;
import com.seizia.chromite.model.impl.edit.AbstractObjectManager;

/**
 * The object manager of a queue's internal sequence: no maker, editor or renamer.
 *
 * 설계: 객체 관리자 등록은 객체 클래스에 정확히 맞는 관리자를 상위 클래스의 관리자보다 먼저 고른다. 이 관리자가 없으면 보통 시퀀스의
 * {@link AltibaseSequenceManager}가 이어받아 Delete·Rename·속성 편집이 이 시퀀스에도 나온다. 사용자가 바꾸면 안 되는 큐의 내부 객체라
 * 바꾸는 명령이 해당하지 않는다({@link AltibaseQueueSequence}).
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성(앱 변경 #139 수정 4, docs/chromite/altibase.md "큐의 내부 시퀀스")
 */
public class AltibaseQueueSequenceManager extends AbstractObjectManager<AltibaseQueueSequence> {
}
