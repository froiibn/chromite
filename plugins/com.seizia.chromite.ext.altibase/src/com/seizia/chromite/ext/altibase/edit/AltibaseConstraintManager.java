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

import com.seizia.chromite.ext.altibase.model.AltibaseQueue;
import com.seizia.chromite.ext.generic.edit.GenericPrimaryKeyManager;
import com.seizia.chromite.ext.generic.model.GenericUniqueKey;
import org.jkiss.code.NotNull;

/**
 * Key manager of Altibase-family tables: the generic one, except for a queue's key.
 *
 * 설계: 큐의 기본 키(메시지 번호)는 서버가 큐와 함께 만드는 큐의 내부 구조다. 전에는 일반 키 관리자가 큐에도 새 키와 삭제를 내주었다.
 * 큐의 키는 만들거나 바꾸거나 지울 수 없게 하고, 다른 테이블은 일반 관리자 그대로다.
 *
 * 변경 이력:
 * - 2026-11-21 최초 작성(앱 변경 #154, docs/chromite/altibase.md "큐")
 */
public class AltibaseConstraintManager extends GenericPrimaryKeyManager {

    @Override
    public boolean canCreateObject(@NotNull Object container) {
        return !(container instanceof AltibaseQueue) && super.canCreateObject(container);
    }

    @Override
    public boolean canEditObject(@NotNull GenericUniqueKey object) {
        return !(object.getParentObject() instanceof AltibaseQueue) && super.canEditObject(object);
    }

    @Override
    public boolean canDeleteObject(@NotNull GenericUniqueKey object) {
        return !(object.getParentObject() instanceof AltibaseQueue) && super.canDeleteObject(object);
    }
}
