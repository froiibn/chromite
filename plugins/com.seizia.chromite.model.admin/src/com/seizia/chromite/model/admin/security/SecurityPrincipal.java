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
import com.seizia.chromite.model.struct.DBSObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Editable state of a login, user or role: general settings, role membership, database user mapping and explicit permissions.
 *
 * 설계: 속성 대화상자는 DB에서 읽은 원본({@link #copy()}로 떠 둔다)과 사용자가 고치는 편집본을 따로 들고 있다가,
 * {@link SecurityDialect#generateScript}로 두 벌의 차이만 SQL로 만든다(SSMS 속성 창과 같이 OK·Script에서 한 번에 적용).
 * 그래서 DB마다 지원하지 않는 항목은 null로 둔다(화면은 null인 칸을 숨긴다). 암호는 바꿀 때만 값이 있다(읽은 원본에는 없다).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 */
public final class SecurityPrincipal {

    /**
     * What the principal is: a server login (SQL Server), a user account (MySQL/MariaDB) or a role
     * (PostgreSQL roles, which are users when they can log in, and MariaDB roles).
     */
    public enum Kind {
        LOGIN,
        USER,
        ROLE
    }

    /**
     * A database user of a server login (SQL Server "User Mapping").
     */
    public static final class DatabaseMapping {
        private boolean mapped;
        @Nullable
        private String userName;
        @Nullable
        private String defaultSchema;
        private final Map<String, Boolean> roles = new LinkedHashMap<>();

        public boolean isMapped() {
            return mapped;
        }

        public void setMapped(boolean mapped) {
            this.mapped = mapped;
        }

        @Nullable
        public String getUserName() {
            return userName;
        }

        public void setUserName(@Nullable String userName) {
            this.userName = userName;
        }

        @Nullable
        public String getDefaultSchema() {
            return defaultSchema;
        }

        public void setDefaultSchema(@Nullable String defaultSchema) {
            this.defaultSchema = defaultSchema;
        }

        /**
         * Database roles of the database (role name → member).
         */
        @NotNull
        public Map<String, Boolean> getRoles() {
            return roles;
        }

        @NotNull
        DatabaseMapping copy() {
            DatabaseMapping copy = new DatabaseMapping();
            copy.mapped = mapped;
            copy.userName = userName;
            copy.defaultSchema = defaultSchema;
            copy.roles.putAll(roles);
            return copy;
        }
    }

    @NotNull
    private final Kind kind;
    private final boolean newPrincipal;
    @Nullable
    private final DBSObject object;
    @NotNull
    private String name;
    @Nullable
    private String host;
    @Nullable
    private String password;
    @Nullable
    private Boolean enabled;
    @Nullable
    private String defaultDatabase;
    @Nullable
    private String expiry;
    private final Map<String, Boolean> options = new LinkedHashMap<>();
    private final Map<String, Boolean> memberships = new LinkedHashMap<>();
    private final Map<String, DatabaseMapping> mappings = new LinkedHashMap<>();
    private PermissionGrid<Securable> permissions = new PermissionGrid<>();

    /**
     * @param object the model object of an existing principal (refreshed after saving), or null for a new one
     */
    public SecurityPrincipal(@NotNull Kind kind, boolean newPrincipal, @Nullable DBSObject object, @NotNull String name) {
        this.kind = kind;
        this.newPrincipal = newPrincipal;
        this.object = object;
        this.name = name;
    }

    @NotNull
    public Kind getKind() {
        return kind;
    }

    public boolean isNew() {
        return newPrincipal;
    }

    @Nullable
    public DBSObject getObject() {
        return object;
    }

    @NotNull
    public String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    /**
     * Host of a MySQL/MariaDB account ({@code 'user'@'host'}), or null.
     */
    @Nullable
    public String getHost() {
        return host;
    }

    public void setHost(@Nullable String host) {
        this.host = host;
    }

    /**
     * New password, or null to keep the current one.
     */
    @Nullable
    public String getPassword() {
        return password;
    }

    public void setPassword(@Nullable String password) {
        this.password = password;
    }

    /**
     * SQL Server: login enabled; PostgreSQL: can log in (LOGIN); MySQL/MariaDB: account not locked. Null if not supported.
     */
    @Nullable
    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(@Nullable Boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Default database of a SQL Server login, or null if not supported.
     */
    @Nullable
    public String getDefaultDatabase() {
        return defaultDatabase;
    }

    public void setDefaultDatabase(@Nullable String defaultDatabase) {
        this.defaultDatabase = defaultDatabase;
    }

    /**
     * PostgreSQL VALID UNTIL as {@code yyyy-MM-dd HH:mm:ss} ("" = no expiry), or null if not supported.
     */
    @Nullable
    public String getExpiry() {
        return expiry;
    }

    public void setExpiry(@Nullable String expiry) {
        this.expiry = expiry;
    }

    /**
     * Database specific switches (option id → on), e.g. PostgreSQL {@code SUPERUSER}, SQL Server {@code CHECK_POLICY}.
     */
    @NotNull
    public Map<String, Boolean> getOptions() {
        return options;
    }

    /**
     * Roles the principal can be a member of (role name → member).
     */
    @NotNull
    public Map<String, Boolean> getMemberships() {
        return memberships;
    }

    /**
     * Database users of a SQL Server login (database name → mapping); empty for other databases.
     */
    @NotNull
    public Map<String, DatabaseMapping> getMappings() {
        return mappings;
    }

    @NotNull
    public PermissionGrid<Securable> getPermissions() {
        return permissions;
    }

    @NotNull
    public SecurityPrincipal copy() {
        SecurityPrincipal copy = new SecurityPrincipal(kind, newPrincipal, object, name);
        copy.host = host;
        copy.password = password;
        copy.enabled = enabled;
        copy.defaultDatabase = defaultDatabase;
        copy.expiry = expiry;
        copy.options.putAll(options);
        copy.memberships.putAll(memberships);
        for (Map.Entry<String, DatabaseMapping> entry : mappings.entrySet()) {
            copy.mappings.put(entry.getKey(), entry.getValue().copy());
        }
        copy.permissions = permissions.copy();
        return copy;
    }
}
