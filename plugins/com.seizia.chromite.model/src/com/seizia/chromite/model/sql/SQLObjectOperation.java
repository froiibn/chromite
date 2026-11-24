/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.model.sql;

import org.jkiss.code.NotNull;

import java.util.List;

/**
 * Semantic description of an operation on a database object.
 *
 * 변경 이력:
 * - 2026-11-24 {@link ObjectKind#PROGRAM_UNIT}: 문법에 없는 저장 단위의 CREATE 뒤 그 스키마만 다시 읽기 위해(앱 변경 181,
 *   docs/chromite/swing-query-commands.md "저장 루틴 DDL 뒤 (앱 변경 181)")
 */
public record SQLObjectOperation(
    @NotNull Operation operation,
    @NotNull ObjectKind objectKind,
    @NotNull List<String> qualifiedNameParts
) {
    public SQLObjectOperation {
        qualifiedNameParts = List.copyOf(qualifiedNameParts);
    }

    public enum Operation {
        CREATE,
        DROP,
        ALTER,
        RENAME
    }

    public enum ObjectKind {
        TABLE,
        VIEW,
        INDEX,
        SCHEMA,
        DATABASE,
        CATALOG,
        FUNCTION,
        PROCEDURE,
        SEQUENCE,
        SYNONYM,
        /**
         * A stored program unit of a kind the SQL grammar does not have (package, type, typeset, trigger): like the
         * named kinds above it is in the container its qualifier names, unlike {@link #OTHER}
         */
        PROGRAM_UNIT,
        OTHER
    }
}
