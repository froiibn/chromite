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
package com.seizia.chromite.ext.altibase.extension;

import org.jkiss.code.NotNull;

import java.util.List;

/**
 * The CREATE templates of new objects for the folders under an object of a server extension's tree: Object Explorer's
 * "New &lt;kind&gt;…" on such a folder opens the template in a new query window, as for views, sequences and triggers.
 * The folder's parent object (the extension's node) implements it.
 *
 * 설계: 확장 번들이 넣은 폴더는 종류(type)가 없다(그 클래스를 Altibase 플러그인이 불러올 수 없음). 그래서 기존 "New …"가 폴더의 자식
 * 클래스로 고르는 틀(방언마다의 {@code SourceScripts.getTemplateKind})에 들지 못한다. 폴더를 가르는 것은 트리 정의의 항목 속성 이름
 * (예: {@code continuousTables})이고, 그 이름으로 확장의 노드에 틀을 묻는다. 한 폴더가 종류 둘을 담을 수 있어(연속 테이블과 연속 스트림
 * 테이블) 틀은 목록이다. 문장은 확장이 만든다(모델). 실행·새로 고침은 기존 틀과 같다(쿼리 창이 실행할 때마다 폴더를 다시 읽음).
 *
 * 변경 이력:
 * - 2026-11-22 최초 작성: 확장 폴더의 새 개체 틀(앱 변경 #156, docs/chromite/altibase.md "확장 객체의 명령 자리")
 */
public interface AltibaseObjectTemplates {

    /**
     * One "New …" template of a folder.
     *
     * @param kind      the extension's own key of the template
     * @param typeLabel the kind of object in words, for the menu item ("New {typeLabel}…")
     * @param baseName  the name of the new object before a number is added to make it unique in the folder
     */
    record Template(@NotNull String kind, @NotNull String typeLabel, @NotNull String baseName) {
    }

    /**
     * The templates of the folder whose items are read from this object's property; empty for another folder.
     */
    @NotNull
    List<Template> getTemplates(@NotNull String itemsProperty);

    /**
     * The template's script for a new object with this name (not in the folder yet), ready to run once completed.
     */
    @NotNull
    String getTemplateScript(@NotNull Template template, @NotNull String name);

}
