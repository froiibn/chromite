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
package com.seizia.chromite.ext.h2.plan;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.h2.model.H2DataSource;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.plan.DBCPlan;
import com.seizia.chromite.model.exec.plan.DBCPlanStyle;
import com.seizia.chromite.model.exec.plan.DBCQueryPlanner;
import com.seizia.chromite.model.exec.plan.DBCQueryPlannerConfiguration;

public class H2QueryPlanner implements DBCQueryPlanner {
    private final H2DataSource dataSource;

    public H2QueryPlanner(@NotNull H2DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public DBPDataSource getDataSource() {
        return dataSource;
    }

    @NotNull
    @Override
    public DBCPlan planQueryExecution(
        @NotNull DBCSession session,
        @NotNull String query,
        @NotNull DBCQueryPlannerConfiguration configuration
    ) throws DBException {
        H2ExecutionPlan plan = new H2ExecutionPlan(query);
        plan.explain(session);
        return plan;
    }

    @NotNull
    @Override
    public DBCPlanStyle getPlanStyle() {
        return DBCPlanStyle.QUERY;
    }
}
