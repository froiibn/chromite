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
package com.seizia.chromite.tools.compare.schema;

import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The result of {@link SchemaComparer#compare}: the differences and what the script generator needs to name target objects.
 *
 * 설계: 같은 테이블은 항목이 되지 않지만, 새 외래 키가 그 테이블을 가리킬 수 있으므로 원본 테이블 → 대상 테이블 짝을 모두 둔다
 * ({@link #getTargetTable}).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/schema-compare.md)
 */
public final class SchemaCompareResult {

    private final DBSObjectContainer source;
    private final DBSObjectContainer target;
    private final boolean sameDialect;
    private final List<SchemaCompareItem> items;
    private final Map<DBSEntity, DBSEntity> tablePairs;
    private final SchemaNameMapper nameMapper;
    private final int identical;
    private final int notCompared;

    SchemaCompareResult(
        @NotNull DBSObjectContainer source,
        @NotNull DBSObjectContainer target,
        boolean sameDialect,
        @NotNull List<SchemaCompareItem> items,
        @NotNull Map<DBSEntity, DBSEntity> tablePairs,
        @NotNull SchemaNameMapper nameMapper,
        int identical,
        int notCompared
    ) {
        this.source = source;
        this.target = target;
        this.sameDialect = sameDialect;
        this.items = items;
        this.tablePairs = tablePairs;
        this.nameMapper = nameMapper;
        this.identical = identical;
        this.notCompared = notCompared;
    }

    @NotNull
    public DBSObjectContainer getSource() {
        return source;
    }

    @NotNull
    public DBSObjectContainer getTarget() {
        return target;
    }

    /**
     * True when both sides are the same database type (the same data source class): definitions (views, routines,
     * triggers, sequences) are compared and copied only then.
     */
    public boolean isSameDialect() {
        return sameDialect;
    }

    /**
     * Top-level items: tables, views, routines and sequences, in source order, then the target-only ones.
     */
    @NotNull
    public List<SchemaCompareItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    /**
     * The target table matched to a source table, or null when the target has none.
     */
    @Nullable
    public DBSEntity getTargetTable(@NotNull DBSEntity sourceTable) {
        return tablePairs.get(sourceTable);
    }

    /**
     * Rewrites source definitions for the target (schema and database names).
     */
    @NotNull
    public SchemaNameMapper getNameMapper() {
        return nameMapper;
    }

    /**
     * Objects present on both sides without differences.
     */
    public int getIdenticalCount() {
        return identical;
    }

    /**
     * Objects present on both sides whose definitions were not compared (views, routines, triggers and sequences
     * between different database types).
     */
    public int getNotComparedCount() {
        return notCompared;
    }
}
