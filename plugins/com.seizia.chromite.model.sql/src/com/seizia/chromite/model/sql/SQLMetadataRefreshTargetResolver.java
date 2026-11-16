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
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSSchema;

import java.util.Collection;
import java.util.List;

/**
 * Resolves the narrowest metadata container affected by a SQL object operation.
 *
 * 설계: 두 부분 이름({@code a.b})의 첫 부분이 카탈로그인지 스키마인지는 엔진의 이름 구조(데이터 원본의 맨 위가 카탈로그이고 카탈로그 아래에
 * 스키마가 없는지, {@link DBSObjectContainer#getPrimaryChildType})로 정한다. 세션이 카탈로그·스키마를 바꿀 수 있는지는 이름 구조와 다른
 * 질문이라 쓰지 않는다.
 *
 * 변경 이력:
 * - 2026-11-13 두 부분 이름을 이름 구조로 읽음(앱 변경 #108). upstream은 "스키마는 못 바꾸고 카탈로그는 바꿀 수 있는 세션"이면 첫 부분을
 *   카탈로그로 읽어, SQL Server의 {@code CREATE TABLE dbo.t}가 없는 DB {@code dbo}를 가리키고 연결 전체를 다시 읽었다
 *   (docs/chromite/swing-query-commands.md "DDL 뒤 탐색기 갱신")
 */
public final class SQLMetadataRefreshTargetResolver {
    private static final Log log = Log.getLog(SQLMetadataRefreshTargetResolver.class);

    private SQLMetadataRefreshTargetResolver() {
    }

    @NotNull
    public static RefreshTarget createTarget(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull SQLObjectOperation operation
    ) {
        return createTarget(monitor, executionContext, operation, DBUtils::refreshContextDefaultsAndReflect);
    }

    @NotNull
    public static RefreshTarget createTarget(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull SQLObjectOperation operation,
        @NotNull ContextDefaultsRefresher defaultsRefresher
    ) {
        try {
            return doCreateTarget(monitor, executionContext, operation, defaultsRefresher);
        } catch (DBException e) {
            log.debug("Error resolving metadata refresh target", e);
            return new RefreshTarget.DataSource();
        }
    }

    @NotNull
    private static RefreshTarget doCreateTarget(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull SQLObjectOperation operation,
        @NotNull ContextDefaultsRefresher defaultsRefresher
    ) throws DBException {
        SQLDialect dialect = executionContext.getDataSource().getSQLDialect();
        List<String> nameParts = operation.qualifiedNameParts().stream()
            .map(name -> SQLUtils.identifierToCanonicalForm(dialect, name, true, false))
            .toList();
        DBCExecutionContextDefaults<?, ?> defaults = executionContext.getContextDefaults();
        DBSCatalog defaultCatalog = defaults == null ? null : defaults.getDefaultCatalog();
        DBSSchema defaultSchema = defaults == null ? null : defaults.getDefaultSchema();

        if (operation.objectKind() == SQLObjectOperation.ObjectKind.DATABASE ||
            operation.objectKind() == SQLObjectOperation.ObjectKind.CATALOG) {
            if (operation.operation() != SQLObjectOperation.Operation.ALTER) {
                return new RefreshTarget.DataSource();
            }
            return nameParts.isEmpty()
                ? new RefreshTarget.DataSource()
                : new RefreshTarget.Catalog(nameParts.getLast());
        }
        if (operation.objectKind() == SQLObjectOperation.ObjectKind.SCHEMA) {
            if (nameParts.size() < 2 && defaults != null && defaults.supportsCatalogChange()) {
                defaultsRefresher.refresh(monitor, defaults, executionContext);
                defaultCatalog = defaults.getDefaultCatalog();
            }
            String catalogName = nameParts.size() > 1 ? nameParts.get(nameParts.size() - 2) :
                defaultCatalog == null ? null : defaultCatalog.getName();
            if (operation.operation() != SQLObjectOperation.Operation.ALTER) {
                return catalogName == null
                    ? new RefreshTarget.DataSource()
                    : new RefreshTarget.Catalog(catalogName);
            }
            return nameParts.isEmpty()
                ? new RefreshTarget.DataSource()
                : new RefreshTarget.Schema(catalogName, nameParts.getLast());
        }
        if (operation.objectKind() == SQLObjectOperation.ObjectKind.OTHER) {
            return new RefreshTarget.DataSource();
        }

        String catalogName = null;
        String schemaName = null;
        if (nameParts.size() >= 3) {
            catalogName = nameParts.get(nameParts.size() - 3);
            schemaName = nameParts.get(nameParts.size() - 2);
        } else if (nameParts.size() == 2) {
            if (hasCatalogsWithoutSchemas(monitor, executionContext, defaultCatalog)) {
                catalogName = nameParts.getFirst();
            } else {
                schemaName = nameParts.getFirst();
            }
        }
        if (defaults != null &&
            ((catalogName == null && defaults.supportsCatalogChange()) ||
                (schemaName == null && defaults.supportsSchemaChange()))) {
            defaultsRefresher.refresh(monitor, defaults, executionContext);
            defaultCatalog = defaults.getDefaultCatalog();
            defaultSchema = defaults.getDefaultSchema();
        }
        catalogName = catalogName != null ? catalogName : defaultCatalog == null ? null : defaultCatalog.getName();
        schemaName = schemaName != null ? schemaName : defaultSchema == null ? null : defaultSchema.getName();
        if (schemaName != null) {
            return new RefreshTarget.Schema(catalogName, schemaName);
        }
        if (catalogName != null) {
            return new RefreshTarget.Catalog(catalogName);
        }
        return new RefreshTarget.DataSource();
    }

    /**
     * Whether a two-part name is {@code catalog.object}: the data source's top level is catalogs and a catalog holds its
     * objects directly (MySQL databases). Where a catalog holds schemas (SQL Server, PostgreSQL) or the top level is
     * schemas (Oracle) the first part is a schema. This is the structure Object Explorer shows, read as
     * {@code ContextDefaultObjectsReader} reads it; whether the session can change its catalog or schema says nothing
     * about it (a SQL Server session cannot change its schema, yet {@code dbo.t} is schema.table).
     * The catalog looked at is the session's default one, else the first of the list (no database selected).
     */
    private static boolean hasCatalogsWithoutSchemas(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @Nullable DBSCatalog defaultCatalog
    ) throws DBException {
        DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, executionContext.getDataSource());
        if (root == null || !DBSCatalog.class.isAssignableFrom(root.getPrimaryChildType(monitor))) {
            return false;
        }
        DBSCatalog catalog = defaultCatalog;
        if (catalog == null) {
            Collection<? extends DBSObject> catalogs = root.getChildren(monitor);
            if (catalogs != null) {
                catalog = catalogs.stream()
                    .filter(DBSCatalog.class::isInstance)
                    .map(DBSCatalog.class::cast)
                    .findFirst()
                    .orElse(null);
            }
        }
        return catalog != null && !DBSSchema.class.isAssignableFrom(catalog.getPrimaryChildType(monitor));
    }

    /**
     * Identifies the narrowest metadata container affected by an SQL object operation.
     * The permitted states have the following interpretations:
     * <ul>
     *     <li>{@link DataSource} refreshes the entire data source and carries no object names;</li>
     *     <li>{@link Catalog} refreshes one catalog identified by its required name;</li>
     *     <li>{@link Schema} refreshes one schema identified by its required name and optional catalog name.</li>
     * </ul>
     */
    public sealed interface RefreshTarget {
        /** Refreshes the entire data source when no narrower container can be identified. */
        record DataSource() implements RefreshTarget {
        }

        /** Refreshes the catalog identified by the required {@code catalogName}. */
        record Catalog(@NotNull String catalogName) implements RefreshTarget {
        }

        /**
         * Refreshes the schema identified by the required {@code schemaName}. The {@code catalogName} is absent when
         * the data source has no catalog level or the containing catalog cannot be determined.
         */
        record Schema(@Nullable String catalogName, @NotNull String schemaName) implements RefreshTarget {
        }
    }

    @FunctionalInterface
    public interface ContextDefaultsRefresher {
        void refresh(
            @NotNull DBRProgressMonitor monitor,
            @NotNull DBCExecutionContextDefaults<?, ?> defaults,
            @NotNull DBCExecutionContext executionContext
        ) throws DBException;
    }
}
