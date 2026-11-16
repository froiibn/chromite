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
package com.seizia.chromite.ext.hsqldb.model.plan;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.exec.plan.DBCPlanNode;
import com.seizia.chromite.model.impl.plan.AbstractExecutionPlan;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class HSQLExecutionPlan extends AbstractExecutionPlan {
    private final String query;

    public HSQLExecutionPlan(@NotNull String query) {
        this.query = query;
    }

    /**
     * Retrieves execution plan for the given query.
     */
    public void explain(@NotNull DBCSession session) throws DBCException {
        final JDBCSession connection = (JDBCSession) session;
        try (JDBCStatement stmt = connection.createStatement()) {
            stmt.execute(getPlanQueryString());
        } catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        }
    }

    @NotNull
    @Override
    public String getQueryString() {
        return query;
    }

    @NotNull
    @Override
    public String getPlanQueryString() {
        return "EXPLAIN PLAN FOR " + query;
    }

    @NotNull
    @Override
    public List<? extends DBCPlanNode> getPlanNodes(@NotNull Map<String, Object> options) {
        return List.of();
    }
}
