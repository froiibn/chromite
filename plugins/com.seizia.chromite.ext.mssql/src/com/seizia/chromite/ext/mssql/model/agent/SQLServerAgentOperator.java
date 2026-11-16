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
import com.seizia.chromite.model.DBPStatefulObject;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectState;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.util.Date;

/**
 * A SQL Server Agent operator, as one row of {@code msdb.dbo.sp_help_operator}.
 *
 * 설계: 편집 창은 이 객체의 값(이름, 사용, 전자 메일 이름)으로 시작하므로 따로 읽지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public class SQLServerAgentOperator implements DBSObject, DBPStatefulObject {

    private final SQLServerAgent agent;
    private final String name;
    private final boolean enabled;
    @Nullable
    private final String emailAddress;
    @Nullable
    private final Date lastEmailDate;

    SQLServerAgentOperator(@NotNull SQLServerAgent agent, @NotNull ResultSet row) {
        this.agent = agent;
        this.name = CommonUtils.notEmpty(JDBCUtils.safeGetString(row, "name")); //$NON-NLS-1$
        this.enabled = JDBCUtils.safeGetInt(row, "enabled") != 0; //$NON-NLS-1$
        this.emailAddress = CommonUtils.nullIfEmpty(JDBCUtils.safeGetString(row, "email_address")); //$NON-NLS-1$
        this.lastEmailDate = SQLServerAgentSql.toTimestamp(
            JDBCUtils.safeGetInt(row, "last_email_date"), JDBCUtils.safeGetInt(row, "last_email_time")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    public SQLServerAgent getAgent() {
        return agent;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Property(viewable = true, order = 2)
    public boolean isEnabled() {
        return enabled;
    }

    @Nullable
    @Property(viewable = true, order = 3)
    public String getEmailAddress() {
        return emailAddress;
    }

    @Nullable
    @Property(viewable = true, order = 4)
    public Date getLastEmailDate() {
        return lastEmailDate;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
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

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        return enabled ? DBSObjectState.NORMAL : DBSObjectState.INVALID;
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) {
    }

    @Override
    public String toString() {
        return name;
    }
}
