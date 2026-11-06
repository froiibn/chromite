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
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSObject;

/**
 * The "Job Activity Monitor" leaf under SQL Server Agent. It has no data of its own: opening it shows the job grid.
 *
 * 설계: SSMS처럼 작업 폴더 옆의 잎 노드로 보이게 탐색기 항목 하나로 만든다(목록은 {@link SQLServerAgent#getActivityMonitors}).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public class SQLServerAgentActivityMonitor implements DBSObject {

    private final SQLServerAgent agent;

    SQLServerAgentActivityMonitor(@NotNull SQLServerAgent agent) {
        this.agent = agent;
    }

    @NotNull
    public SQLServerAgent getAgent() {
        return agent;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return SQLServerAgentMessages.agent_activity_monitor_node_name;
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

    @Override
    public String toString() {
        return getName();
    }
}
