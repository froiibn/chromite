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
package com.seizia.chromite.model.admin.security;

import org.jkiss.code.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit permissions of one side of a grant: for a principal the rows are securables, for an object the rows are grantees.
 * Each row maps a privilege name ({@code SELECT}, {@code EXECUTE}) to its state.
 *
 * 설계: 편집 화면은 원본과 편집본 두 벌을 들고 있다가, 저장할 때 {@link SecurityDialect}가 두 벌의 차이를 GRANT·REVOKE·DENY 문으로 바꾼다.
 * 그래서 이 클래스는 상태만 들고 SQL은 모른다. 화면에 추가만 하고 아무 권한도 주지 않은 행도 남아 있어야 하므로(SSMS의 "개체 추가" 뒤 빈 행)
 * 빈 행을 지우지 않는다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 *
 * @param <K> row key: {@link Securable} or a grantee name
 */
public final class PermissionGrid<K> {

    /**
     * State of one privilege of one row. SSMS shows these as the Grant, With Grant and Deny check boxes.
     */
    public enum State {
        NONE,
        GRANT,
        GRANT_WITH,
        DENY
    }

    private final Map<K, Map<String, State>> rows = new LinkedHashMap<>();

    @NotNull
    public List<K> getRows() {
        return new ArrayList<>(rows.keySet());
    }

    public boolean hasRow(@NotNull K row) {
        return rows.containsKey(row);
    }

    public void addRow(@NotNull K row) {
        rows.computeIfAbsent(row, key -> new LinkedHashMap<>());
    }

    public void removeRow(@NotNull K row) {
        rows.remove(row);
    }

    @NotNull
    public State get(@NotNull K row, @NotNull String privilege) {
        Map<String, State> states = rows.get(row);
        State state = states == null ? null : states.get(privilege);
        return state == null ? State.NONE : state;
    }

    public void set(@NotNull K row, @NotNull String privilege, @NotNull State state) {
        Map<String, State> states = rows.computeIfAbsent(row, key -> new LinkedHashMap<>());
        if (state == State.NONE) {
            states.remove(privilege);
        } else {
            states.put(privilege, state);
        }
    }

    /**
     * Privileges of a row that are not {@link State#NONE}.
     */
    @NotNull
    public Map<String, State> getStates(@NotNull K row) {
        Map<String, State> states = rows.get(row);
        return states == null ? Map.of() : new LinkedHashMap<>(states);
    }

    @NotNull
    public PermissionGrid<K> copy() {
        PermissionGrid<K> copy = new PermissionGrid<>();
        for (Map.Entry<K, Map<String, State>> entry : rows.entrySet()) {
            copy.rows.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return copy;
    }
}
