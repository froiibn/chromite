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
package com.seizia.chromite.ext.oracle.model.plan;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.plan.DBCPlanCostNode;
import com.seizia.chromite.model.exec.plan.DBCPlanNode;
import com.seizia.chromite.model.exec.plan.DBCPlanSourceFormat;
import com.seizia.chromite.model.impl.local.CachedResultSet;
import com.seizia.chromite.model.impl.plan.AbstractExecutionPlan;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.IntKeyMap;
import org.jkiss.utils.SecurityUtils;
import org.jkiss.utils.xml.XMLException;
import org.jkiss.utils.xml.XMLUtils;
import org.w3c.dom.Element;

import java.io.StringReader;
import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle execution plan analyser
 *
 * 설계(Chromite): 계획 원문은 PLAN_TABLE 행({@link CachedResultSet})만 남았다. 실행 계획 원문 보기는 Oracle 사용자가 읽는 형태인
 * {@code DBMS_XPLAN.DISPLAY} 글자를 보여야 하므로, 계획 행을 읽은 뒤 같은 세션에서 같은 STATEMENT_ID의 DBMS_XPLAN 출력을 읽어 둔다.
 * 그 글자가 있으면 원문은 TEXT 형식의 그 글자이고, 읽지 못하면(권한 등) 예전처럼 계획 행이다.
 * 적응 계획(adaptive plan)은 PLAN_TABLE에 고르지 않은 대안(STATISTICS COLLECTOR, 다른 조인)까지 행으로 남긴다. 계획 노드는 Oracle이
 * 보이는 계획(DBMS_XPLAN 기본 출력)과 같아야 하므로 OTHER_XML의 display_map으로 쓰지 않는 행을 빼고 표시 부모로 다시 잇는다.
 * 트리·다이어그램·Chromite Agent가 모두 계획 노드를 읽으므로 모델에서 한 번 거른다.
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: DBMS_XPLAN 출력을 계획 원문으로 남김 (docs/chromite/swing-sql-editor-plus.md "실행 계획 세 보기")
 * - 2026-11-08 Chromite: 적응 계획의 쓰지 않는 행을 계획 노드에서 뺌 (docs/chromite/swing-sql-editor-plus.md "Oracle 적응 계획")
 */
public class OracleExecutionPlan extends AbstractExecutionPlan {

    private static final Log log = Log.getLog(OracleExecutionPlan.class);

    private OracleDataSource dataSource;
    private JDBCSession session;
    private final String query;
    private List<OraclePlanNode> rootNodes;
    private String planStmtId;
    private String planTableName;
    private CachedResultSet planResults;
    @Nullable
    private String planText;

    OracleExecutionPlan(OracleDataSource dataSource, JDBCSession session, String query) {
        this.dataSource = dataSource;
        this.session = session;
        this.query = query;
    }

    public OracleExecutionPlan(String query, List<OraclePlanNode> nodes) {
        this.query = query;
        this.rootNodes = nodes;
    }

    @Nullable
    @Override
    public Object getPlanFeature(@NotNull String feature) {
        if (DBCPlanCostNode.FEATURE_PLAN_COST.equals(feature) ||
            DBCPlanCostNode.FEATURE_PLAN_DURATION.equals(feature) ||
            DBCPlanCostNode.FEATURE_PLAN_ROWS.equals(feature)) {
            return true;
        } else if (DBCPlanCostNode.PLAN_DURATION_MEASURE.equals(feature)) {
            return "KC";
        }

        return super.getPlanFeature(feature);
    }

    @NotNull
    @Override
    public String getQueryString() {
        return query;
    }

    @NotNull
    @Override
    public String getPlanQueryString() throws DBException {
        if (planTableName == null) {
            // Detect plan table
            planTableName = dataSource.getPlanTableName(session);
            if (planTableName == null) {
                throw new DBCException("Plan table not found - query can't be explained");
            }
        }

        if (planStmtId == null) {
            planStmtId = SecurityUtils.generateUniqueId();
        }

        return "EXPLAIN PLAN " + "\n" +
            "SET STATEMENT_ID = '" + planStmtId + "'\n" +
            "INTO " + planTableName + "\n" +
            "FOR " + query;
    }

    @NotNull
    @Override
    public DBCPlanSourceFormat getPlanSourceDataFormat() {
        return planText != null ? DBCPlanSourceFormat.TEXT : DBCPlanSourceFormat.RESULT_SET;
    }

    @Nullable
    @Override
    public Object getPlanSourceData() {
        return planText != null ? planText : planResults;
    }

    @NotNull
    @Override
    public List<? extends DBCPlanNode> getPlanNodes(@NotNull Map<String, Object> options) {
        return rootNodes;
    }

    public void explain() throws DBException {
        String planQuery = getPlanQueryString();
        try {

            // Delete previous statement rows
            // (actually there should be no statement with this id -
            // but let's do it, just in case)
            JDBCPreparedStatement dbStat = session.prepareStatement(
                "DELETE FROM " + planTableName +
                    " WHERE STATEMENT_ID=? ");
            try {
                dbStat.setString(1, planStmtId);
                dbStat.execute();
            } finally {
                dbStat.close();
            }

            // Explain plan
            dbStat = session.prepareStatement(planQuery);
            try {
                try {
                    // Bind parameters if any
                    ParameterMetaData parameterMetaData = dbStat.getParameterMetaData();
                    if (parameterMetaData != null && parameterMetaData.getParameterCount() > 0) {
                        for (int i = 0; i < parameterMetaData.getParameterCount(); i++) {
                            dbStat.setNull(i + 1, Types.VARCHAR);
                        }
                    }
                } catch (Exception e) {
                    log.error(e);
                }
                dbStat.execute();
            } finally {
                dbStat.close();
            }

            readPlanNodes();

        } catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        }
    }
/*

    public void readHistoric() throws DBException {
        try {
            // Read explained plan
            JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT * FROM " + OracleUtils.getSysSchemaPrefix(dataSource) + "DBA_HIST_SQL_PLAN" +
                    " WHERE SQL_ID=? ORDER BY ID");
            readPlanNodes(dbStat);

        } catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        }
    }
*/

    private void readPlanNodes() throws SQLException {
        String planSQL = "SELECT * FROM " + planTableName +
            " WHERE STATEMENT_ID=? ORDER BY ID";
        try (JDBCPreparedStatement dbStat = session.prepareStatement(planSQL)) {
            dbStat.setString(1, planStmtId);
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                planResults = new CachedResultSet(planSQL, dbResult.getMetaData());
                rootNodes = new ArrayList<>();
                IntKeyMap<OraclePlanNode> allNodes = new IntKeyMap<>();
                List<OraclePlanNode> planNodes = new ArrayList<>();
                while (dbResult.next()) {
                    OraclePlanNode node = new OraclePlanNode(dataSource, allNodes, dbResult);
                    allNodes.put(node.getId(), node);
                    planNodes.add(node);
                    if (node.getParent() == null) {
                        rootNodes.add(node);
                    }
                    planResults.addRow(dbResult);
                }
                hideInactiveRows(planNodes);
            }
        }

        // Update costs
        for (OraclePlanNode node : rootNodes) {
            node.updateCosts();
        }
        readPlanText();
    }

    /**
     * Links the plan as Oracle shows it: an adaptive plan has rows for all its alternatives (STATISTICS COLLECTOR, the
     * join that was not chosen), and the display map in OTHER_XML marks the inactive ones ({@code skp="1"}) and gives
     * the displayed parent of each active row ({@code par}, a displayed row number {@code dis}). DBMS_XPLAN shows the
     * same plan unless asked for {@code +ADAPTIVE}. Plans without inactive rows stay as the plan table links them.
     *
     * @param planNodes the plan table rows in ID order
     */
    private void hideInactiveRows(@NotNull List<OraclePlanNode> planNodes) {
        Map<Integer, Element> displayMap = readDisplayMap(planNodes);
        if (displayMap == null) {
            return;
        }
        Map<Integer, OraclePlanNode> byDisplayId = new HashMap<>();
        List<OraclePlanNode> displayRoots = new ArrayList<>();
        for (OraclePlanNode node : planNodes) {
            node.clearNested();
        }
        for (OraclePlanNode node : planNodes) {
            Element row = displayMap.get(node.getId());
            OraclePlanNode displayParent;
            if (row == null) {
                // The statement row is not in the map: it keeps its place and its ID
                displayParent = node.getParent() == null ? null : byDisplayId.get(node.getParent().getId());
                byDisplayId.put(node.getId(), node);
            } else if (CommonUtils.toInt(row.getAttribute("skp")) != 0) { //$NON-NLS-1$
                continue;
            } else {
                displayParent = byDisplayId.get(CommonUtils.toInt(row.getAttribute("par"))); //$NON-NLS-1$
                byDisplayId.put(CommonUtils.toInt(row.getAttribute("dis")), node); //$NON-NLS-1$
            }
            node.linkToDisplayParent(displayParent);
            if (displayParent == null) {
                displayRoots.add(node);
            }
        }
        rootNodes = displayRoots;
    }

    /**
     * The display map rows of an adaptive plan by plan row ID, or null if the plan has no inactive rows.
     */
    @Nullable
    private static Map<Integer, Element> readDisplayMap(@NotNull List<OraclePlanNode> planNodes) {
        for (OraclePlanNode node : planNodes) {
            String otherXml = node.getOtherXml();
            if (CommonUtils.isEmpty(otherXml) || !otherXml.contains("<display_map>")) { //$NON-NLS-1$
                continue;
            }
            try {
                Element displayMap = XMLUtils.getChildElement(
                    XMLUtils.parseDocument(new StringReader(otherXml)).getDocumentElement(), "display_map"); //$NON-NLS-1$
                if (displayMap == null) {
                    return null;
                }
                Map<Integer, Element> rows = new HashMap<>();
                boolean skips = false;
                for (Element row : XMLUtils.getChildElementList(displayMap, "row")) { //$NON-NLS-1$
                    rows.put(CommonUtils.toInt(row.getAttribute("op")), row); //$NON-NLS-1$
                    skips |= CommonUtils.toInt(row.getAttribute("skp")) != 0; //$NON-NLS-1$
                }
                return skips ? rows : null;
            } catch (XMLException e) {
                log.debug("Can't read the display map of the adaptive plan, showing all its rows", e);
                return null;
            }
        }
        return null;
    }

    /**
     * Reads the plan as DBMS_XPLAN formats it, from the same plan table rows.
     */
    private void readPlanText() {
        try (JDBCPreparedStatement dbStat = session.prepareStatement(
            "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY(?, ?, 'TYPICAL'))")) {
            dbStat.setString(1, planTableName);
            dbStat.setString(2, planStmtId);
            try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                StringBuilder text = new StringBuilder();
                while (dbResult.next()) {
                    text.append(CommonUtils.notEmpty(dbResult.getString(1))).append('\n');
                }
                planText = text.isEmpty() ? null : text.toString();
            }
        } catch (SQLException e) {
            log.debug("Can't read the DBMS_XPLAN output, the plan table rows are the plan source", e);
        }
    }

}
