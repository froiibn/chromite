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
package com.seizia.chromite.ext.mssql.model.agent;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;

/**
 * A SQL Server Agent proxy (read only), as one row of {@code msdb.dbo.sp_help_proxy}.
 *
 * 설계: 첫 단계(MVP)에서 프록시는 목록과 속성 보기만 한다(문서 "남긴 것").
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public class SQLServerAgentProxy implements DBSObject {

    private final SQLServerAgent agent;
    private final int proxyId;
    private final String name;
    @Nullable
    private final String credentialIdentity;
    private final boolean enabled;
    @Nullable
    private final String description;

    SQLServerAgentProxy(@NotNull SQLServerAgent agent, @NotNull ResultSet row) {
        this.agent = agent;
        this.proxyId = JDBCUtils.safeGetInt(row, "proxy_id"); //$NON-NLS-1$
        this.name = CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "name")); //$NON-NLS-1$
        this.credentialIdentity = JDBCUtils.safeGetString(row, "credential_identity"); //$NON-NLS-1$
        this.enabled = JDBCUtils.safeGetInt(row, "enabled") != 0; //$NON-NLS-1$
        this.description = CommonUtils.nullIfEmpty(JDBCUtils.safeGetString(row, "description")); //$NON-NLS-1$
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Property(viewable = true, order = 2)
    public int getProxyId() {
        return proxyId;
    }

    @Nullable
    @Property(viewable = true, order = 3)
    public String getCredentialIdentity() {
        return credentialIdentity;
    }

    @Property(viewable = true, order = 4)
    public boolean isEnabled() {
        return enabled;
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 5)
    public String getDescription() {
        return description;
    }

    @NotNull
    @Override
    public SQLServerAgent getParentObject() {
        return agent;
    }

    @NotNull
    @Override
    public SQLServerDataSource getDataSource() {
        return agent.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @Override
    public String toString() {
        return name;
    }
}
