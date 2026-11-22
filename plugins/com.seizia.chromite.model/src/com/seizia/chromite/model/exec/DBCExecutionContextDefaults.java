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
package com.seizia.chromite.model.exec;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSSchema;

/**
 * Execution context defaults.
 * Provides access to default context catalog/schema.
 * Note: metadata context defaults are datasource defaults.
 *
 * 설계(Chromite): {@link #getDefaultSchema}는 공급자에 따라 스키마 목록을 서버에서 읽는다(Oracle 스키마 목록, SQL Server·Snowflake
 * 기본 데이터베이스의 스키마 목록). 화면을 그리는 코드(개체 탐색기의 굵은 기본값)는 이벤트 스레드에서 이것을 묻는데, 그 읽기는 메타데이터
 * 연결이 바쁘면 창 전체를 멈추고 무효화와 잠금 순서가 엇갈리면 교착에 빠진다. 그래서 읽지 않고 답할 수 있는지를 먼저 묻는
 * {@link #isDefaultSchemaLoaded}를 둔다. 공급자의 기본값 조회 자체(다른 호출처는 작업 스레드에서 읽기를 기대함)는 바꾸지 않는다.
 *
 * 변경 이력:
 * - 2026-11-22 {@link #isDefaultSchemaLoaded} 추가(앱 변경 #164, docs/chromite/swing-explorer-productivity.md "기본값 굵게 표시")
 */
public interface DBCExecutionContextDefaults<CATALOG extends DBSCatalog, SCHEMA extends DBSSchema> {

    /**
     * The default catalog. Answers from memory in every provider (callers on the event thread rely on it).
     */
    @Nullable
    CATALOG getDefaultCatalog();

    /**
     * The default schema. May read the schema list from the database first: see {@link #isDefaultSchemaLoaded}.
     */
    @Nullable
    SCHEMA getDefaultSchema();

    /**
     * True when {@link #getDefaultSchema} answers from what the model already holds, without reading the database.
     * False while the list it looks in is not read yet or was cleared (Refresh, a list read again after DDL): code on the
     * event thread then must not call it.
     */
    default boolean isDefaultSchemaLoaded() {
        return true;
    }

    @NotNull
    default DBCCachedContextDefaults getCachedDefault() {
        return new DBCCachedContextDefaults(null, null);
    }

    boolean supportsCatalogChange();

    boolean supportsSchemaChange();

    void setDefaultCatalog(DBRProgressMonitor monitor, CATALOG catalog, @Nullable SCHEMA schema) throws DBCException;

    void setDefaultSchema(DBRProgressMonitor monitor, SCHEMA schema) throws DBCException;

    boolean refreshDefaults(DBRProgressMonitor monitor, boolean useBootstrapSettings) throws DBException;

    /**
     * Returns {@code true} if catalog/schema change is transactional (i.e. can be rolled back)
     */
    default boolean isDefaultsChangeTransactional() {
        return false;
    }
}
