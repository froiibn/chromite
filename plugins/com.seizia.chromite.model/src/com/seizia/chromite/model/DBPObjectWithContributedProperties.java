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
package com.seizia.chromite.model;

import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.util.List;

/**
 * An object whose property view shows, after its {@code @Property} getters, read-only properties that are not part of
 * its class: properties another bundle contributes for this object on this connection.
 *
 * 설계: 속성 보기는 클래스의 {@code @Property} 주석으로 속성을 모은다. 주석은 클래스마다 고정이라, 어느 연결에서만 있는 속성(서버 확장이
 * 그 서버의 카탈로그에서 읽는 값)을 기존 객체에 더할 길이 없었다. 그래서 객체가 이 인터페이스로 "이 연결에서 더 보일 것"을 준다. 값은
 * 속성 보기가 그 객체를 보일 때만 읽는다(목록 열·트리 읽기에는 쓰지 않음): 고른 객체 하나에만 질의가 간다. 읽기 전용이다(편집기 없음).
 * 이름은 이미 번역된 글이다(기여하는 쪽의 번역). {@code DBPPropertySource}를 쓰지 않은 이유: 속성 보기는 그 인터페이스를 가진 객체를
 * 주석 대신 그 원본으로만 읽으므로, 기존 객체가 구현하면 주석 속성이 사라진다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: 서버 확장이 Altibase 계열 객체에 읽기 전용 속성을 더하는 자리(앱 변경 #139 수정 6, docs/chromite/altibase.md
 *   "기존 객체의 추가 속성")
 */
public interface DBPObjectWithContributedProperties extends DBPObject {

    /**
     * One contributed property.
     *
     * @param category    the heading of the property view's group, null for the ungrouped properties
     * @param name        the displayed (translated) name
     * @param value       the value as the property view formats any property value
     * @param description the tooltip, null for none
     */
    record ContributedProperty(@Nullable String category, @NotNull String name, @Nullable Object value, @Nullable String description) {
    }

    /**
     * The properties contributed for this object on its connection, in display order; empty when nothing contributes.
     * Called by the property view when it shows this object, off the UI thread.
     */
    @NotNull
    List<ContributedProperty> getContributedProperties(@NotNull DBRProgressMonitor monitor) throws DBException;

}
