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
import org.jkiss.code.Nullable;

/**
 * An object that permissions are granted on (SSMS "securable"): a database, a schema, a table, a view, a sequence or a routine.
 *
 * 설계: 권한은 객체 이름으로 읽고 쓴다(카탈로그 조회 결과와 GRANT 문 모두 이름을 쓴다). 그래서 모델 객체 대신 이름 묶음을 키로 쓴다.
 * 같은 객체는 어느 경로(주체의 권한 조회, 객체의 권한 조회, 개체 추가 선택)로 와도 같은 값이 되어야 하므로 이름은 DB에 저장된 그대로다.
 * PostgreSQL 루틴은 이름이 겹칠 수 있어(오버로드) 인수 목록({@code pg_get_function_identity_arguments})을 {@link #signature}로 구별한다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 *
 * @param kind      object type
 * @param catalog   database (SQL Server and PostgreSQL: the database of the object; MySQL: the schema), or null
 * @param schema    schema, or null (MySQL, databases)
 * @param name      object name (for a schema or a database: its own name)
 * @param signature PostgreSQL routine arguments without parentheses, or null
 */
public record Securable(
    @NotNull Kind kind,
    @Nullable String catalog,
    @Nullable String schema,
    @NotNull String name,
    @Nullable String signature
) implements Comparable<Securable> {

    /**
     * Object types, in the order the permission lists show them.
     */
    public enum Kind {
        DATABASE,
        SCHEMA,
        TABLE,
        VIEW,
        SEQUENCE,
        FUNCTION,
        PROCEDURE
    }

    /**
     * Name shown in the lists: {@code schema.name(args)}, without quotes.
     */
    @NotNull
    public String getDisplayName() {
        StringBuilder text = new StringBuilder();
        if (schema != null && kind != Kind.SCHEMA) {
            text.append(schema).append('.');
        } else if (schema == null && catalog != null && kind != Kind.DATABASE && kind != Kind.SCHEMA) {
            text.append(catalog).append('.');
        }
        text.append(name);
        if (signature != null) {
            text.append('(').append(signature).append(')');
        }
        return text.toString();
    }

    @Override
    public int compareTo(@NotNull Securable other) {
        int result = kind.compareTo(other.kind);
        if (result == 0) {
            result = getDisplayName().compareToIgnoreCase(other.getDisplayName());
        }
        return result;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
