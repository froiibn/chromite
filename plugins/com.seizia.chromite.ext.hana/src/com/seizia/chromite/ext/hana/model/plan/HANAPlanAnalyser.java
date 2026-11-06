/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ext.hana.model.plan;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.hana.model.HANADataSource;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.plan.DBCPlanNode;
import com.seizia.chromite.model.exec.plan.DBCPlanSourceFormat;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.local.CachedResultSet;
import com.seizia.chromite.model.impl.plan.AbstractExecutionPlan;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HANAPlanAnalyser extends AbstractExecutionPlan {

    private static final Log log = Log.getLog(HANAPlanAnalyser.class);

    private final HANADataSource dataSource;
    private final String query;
    private List<HANAPlanNode> rootNodes;
    private CachedResultSet planResults;

    public HANAPlanAnalyser(HANADataSource dataSource, String query) {
        this.dataSource = dataSource;
        this.query = query;
    }

    @NotNull
    @Override
    public String getQueryString() {
        return query;
    }

    @NotNull
    @Override
    public String getPlanQueryString() {
        return "SELECT * FROM SYS.EXPLAIN_PLAN_TABLE";
    }

    @NotNull
    @Override
    public List<? extends DBCPlanNode> getPlanNodes(@NotNull Map<String, Object> options) {
        return rootNodes;
    }

    public void explain(DBCSession session)  throws DBCException {
        rootNodes = new ArrayList<>();
        List<HANAPlanNode> allNodes = new ArrayList<>();
        JDBCSession connection = (JDBCSession) session;
        boolean oldAutoCommit = false;
        try {
            oldAutoCommit = connection.getAutoCommit();
            if (oldAutoCommit)
                connection.setAutoCommit(false);

            JDBCUtils.executeSQL(connection, "DELETE FROM SYS.EXPLAIN_PLAN_TABLE");
            JDBCUtils.executeSQL(connection, "EXPLAIN PLAN FOR "+ query);

            String planQueryString = getPlanQueryString();
            try (JDBCPreparedStatement stmt = connection.prepareStatement(planQueryString)) {
                try (JDBCResultSet dbResult = stmt.executeQuery()) {
                    planResults = new CachedResultSet(planQueryString, dbResult.getMetaData());
                    while (dbResult.next()) {
                        HANAPlanNode node = new HANAPlanNode(dbResult);
                        for (HANAPlanNode parent : allNodes) {
                            if (node.getParentOperatorId() == parent.getOperatorId()) {
                                node.setParent(parent);
                                parent.addNested(node);
                            }
                        }
                        allNodes.add(node);
                        planResults.addRow(dbResult);
                    }
                }
            }
            connection.rollback();
            for (HANAPlanNode node : allNodes) {
                if (node.getParent() == null)
                    rootNodes.add(node);
            }

        } catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        } finally {
            //rollback temporary EXPLAIN_PLAN_TABLE content
            try {
                connection.rollback();
                if (oldAutoCommit)
                    connection.setAutoCommit(true);
            } catch (SQLException e) {
                log.error("Error closing plan analyser", e);
            }
        }
    }

    public HANADataSource getDataSource() {
        return this.dataSource;
    }

    @NotNull
    @Override
    public DBCPlanSourceFormat getPlanSourceDataFormat() {
        return DBCPlanSourceFormat.RESULT_SET;
    }

    @Nullable
    @Override
    public Object getPlanSourceData() {
        return planResults;
    }
}
