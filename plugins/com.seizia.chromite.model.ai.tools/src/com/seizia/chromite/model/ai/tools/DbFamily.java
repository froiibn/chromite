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
package com.seizia.chromite.model.ai.tools;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;

import java.util.List;
import java.util.Locale;

/**
 * Database family of a connection, which picks the system views of diagnostic SQL.
 *
 * 설계: 드라이버 공급자 ID(plugin.xml의 {@code datasource id}: sqlserver, postgresql, mysql, oracle)를 먼저 보고, 같은 드라이버 계열이 여러 제품을
 * 다루는 경우(MySQL 드라이버의 MariaDB, PostgreSQL 드라이버의 Greenplum 등)는 서버가 알려 준 제품 이름으로 나눈다. MariaDB는 MySQL과 시스템 뷰가
 * 대부분 같아 진단 SQL을 찾을 때 {@code mariadb} 블록이 없으면 {@code mysql} 블록을 쓴다({@link #lookupChain()}).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md)
 */
public enum DbFamily {
    SQLSERVER("sqlserver", "SQL Server"),
    POSTGRESQL("postgresql", "PostgreSQL"),
    MYSQL("mysql", "MySQL"),
    MARIADB("mariadb", "MariaDB"),
    ORACLE("oracle", "Oracle"),
    OTHER("other", "other");

    private final String id;
    private final String displayName;

    DbFamily(@NotNull String id, @NotNull String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    @NotNull
    public String getId() {
        return id;
    }

    @NotNull
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Families whose diagnostic SQL fits this one, in order of preference.
     */
    @NotNull
    public List<DbFamily> lookupChain() {
        return this == MARIADB ? List.of(MARIADB, MYSQL) : List.of(this);
    }

    @NotNull
    public static DbFamily of(@NotNull DBPDataSourceContainer container) {
        String provider = container.getDriver().getProviderId().toLowerCase(Locale.ROOT);
        DBPDataSource dataSource = container.getDataSource();
        String product = "";
        if (dataSource != null) {
            String name = dataSource.getInfo().getDatabaseProductName();
            product = name == null ? "" : name.toLowerCase(Locale.ROOT);
        }
        String driverId = container.getDriver().getId().toLowerCase(Locale.ROOT);
        if (provider.equals("sqlserver") || product.contains("microsoft sql server")) {
            return SQLSERVER;
        }
        if (provider.equals("postgresql") || provider.equals("greenplum") || product.contains("postgresql")) {
            return POSTGRESQL;
        }
        if (product.contains("mariadb") || driverId.contains("mariadb")) {
            return MARIADB;
        }
        if (provider.equals("mysql") || product.contains("mysql")) {
            return MYSQL;
        }
        if (provider.equals("oracle") || product.contains("oracle")) {
            return ORACLE;
        }
        return OTHER;
    }

    @Nullable
    public static DbFamily fromId(@NotNull String id) {
        for (DbFamily family : values()) {
            if (family.id.equalsIgnoreCase(id.trim())) {
                return family;
            }
        }
        return null;
    }
}
