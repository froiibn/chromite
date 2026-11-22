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
package com.seizia.chromite.model.struct;

import org.jkiss.code.Nullable;

/**
 * An object that is found by name or listed by its container but is not itself an object of the navigator tree:
 * the navigator shows another object for it.
 *
 * 설계: 개체 목록(자동 완성, 이름 찾기, 결과 그리드의 원본)에 쓰려고 만든 객체가 트리 노드 객체를 감싸는 경우가 있다. 노드 찾기
 * ({@code DBNModel.getNodeByObject(monitor, object, addFiltered)})는 객체의 부모를 따라 노드를 찾으므로 이런 객체에는 노드가 없다.
 * 그래서 객체가 "탐색기에서 나를 보이는 객체"를 알려 주고, 노드 찾기가 그 객체의 노드를 찾는다. 이 질문을 노드 찾기 한 곳에서
 * 물으므로 Ctrl+클릭, 마우스 올림의 종류 글, 다이어그램의 "탐색기에서 보기" 같은 호출처가 모두 같은 답을 받는다.
 * 기존 인터페이스를 쓰지 않은 이유: {@link DBSWrapper}는 객체가 아닌 것(노드)이 객체를 감쌀 때의 뜻이라 {@code DBUtils.getFromObject}가
 * 모든 곳에서 풀어 버리고, {@link DBSAlias}(시노님)는 그 자체가 트리 노드다. 메모리만 보는 노드 찾기({@code DBNModel.getNodeByObject(object)})와
 * 탐색기 이벤트는 이 질문을 따르지 않는다: 이 객체의 추가·삭제 알림이 다른 객체의 노드를 바꾸면 안 된다.
 *
 * 변경 이력:
 * - 2026-11-22 최초 작성(앱 변경 #166, docs/chromite/swing-sql-editor-ergonomics.md "같은 이름 강조, 마우스 올림 설명, Ctrl+클릭")
 */
public interface DBSNavigatorObjectProvider {

    /**
     * The object the navigator tree shows for this one, or null when the tree shows nothing for it. It is itself an
     * object of the tree (not another provider).
     */
    @Nullable
    DBSObject getNavigatorObject();

}
