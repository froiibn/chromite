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
package com.seizia.chromite.model.admin.maintenance;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * 유지 관리 동작의 대상: 데이터베이스, 스키마, 테이블, 인덱스, 그 밖의 객체(Oracle의 무효 객체).
 *
 * 설계: 대상은 이름만 가진다(모델 객체가 아니다). Object Explorer의 노드에서도({@link MaintenanceDialect#getTarget}),
 * Activity Monitor 유지 관리 탭의 후보 행에서도(시스템 뷰 질의 결과, 모델 객체를 읽지 않음) 같은 대화상자를 열기 위해서다.
 * 이름은 서버에 있는 그대로이고 문장을 만들 때 방언이 따옴표로 감싼다.
 *
 * @param kind       what the target is
 * @param database   database (SQL Server, PostgreSQL); null where the connection has one database (Oracle) or the name is the schema (MySQL)
 * @param schema     schema (MySQL database, Oracle owner); null for a database
 * @param name       name of the target itself (database, schema, table, index or object)
 * @param table      table of an index, otherwise null
 * @param objectType type of an {@link Kind#OBJECT} as the server names it (e.g. "PACKAGE BODY"), otherwise null
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
public record MaintenanceTarget(
    @NotNull Kind kind,
    @Nullable String database,
    @Nullable String schema,
    @NotNull String name,
    @Nullable String table,
    @Nullable String objectType
) {

    public enum Kind {
        DATABASE,
        SCHEMA,
        TABLE,
        INDEX,
        OBJECT
    }

    @NotNull
    public static MaintenanceTarget database(@NotNull String database) {
        return new MaintenanceTarget(Kind.DATABASE, database, null, database, null, null);
    }

    @NotNull
    public static MaintenanceTarget schema(@Nullable String database, @NotNull String schema) {
        return new MaintenanceTarget(Kind.SCHEMA, database, schema, schema, null, null);
    }

    @NotNull
    public static MaintenanceTarget table(@Nullable String database, @NotNull String schema, @NotNull String table) {
        return new MaintenanceTarget(Kind.TABLE, database, schema, table, table, null);
    }

    @NotNull
    public static MaintenanceTarget index(@Nullable String database, @NotNull String schema, @NotNull String table, @NotNull String index) {
        return new MaintenanceTarget(Kind.INDEX, database, schema, index, table, null);
    }

    @NotNull
    public static MaintenanceTarget object(@NotNull String schema, @NotNull String name, @NotNull String objectType) {
        return new MaintenanceTarget(Kind.OBJECT, null, schema, name, null, objectType);
    }

    /**
     * Name shown in the dialog title: {@code schema.table}, {@code index (schema.table)}, {@code schema.object}, the database or schema.
     */
    @NotNull
    public String getDisplayName() {
        return switch (kind) {
            case DATABASE, SCHEMA -> name;
            case TABLE, OBJECT -> schema + "." + name; //$NON-NLS-1$
            case INDEX -> name + " (" + schema + "." + table + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        };
    }
}
