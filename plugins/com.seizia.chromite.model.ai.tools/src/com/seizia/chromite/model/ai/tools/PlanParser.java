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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns native execution plans into one structured JSON shape the model can compare across databases.
 *
 * 설계:
 * <ul>
 *     <li>노드 모양(모든 DB 같음): {@code op}(물리 연산자), {@code logicalOp}, {@code object}(테이블·인덱스), {@code estRows}, {@code actualRows},
 *     {@code executions}, {@code cost}(하위 트리 누적 비용), {@code actualTimeMs}, {@code reads}, {@code predicate}, {@code seekPredicate},
 *     {@code warnings[]}, {@code children[]}.</li>
 *     <li>모델이 트리를 다 읽지 않아도 되게 {@code hotspots}(비용·실제 시간이 큰 노드 상위 10개)와 {@code findings}(스캔, 키 조회, 디스크로 넘친 정렬·해시,
 *     암시적 변환, 통계 없음, 추정과 실제 행 차이 10배 이상, 누락 인덱스, 매개변수 컴파일 값과 실행 값 차이)를 함께 만든다. 시나리오 1·3
 *     (docs/chromite/ai-agent.md)의 원인 순위의 근거가 된다.</li>
 *     <li>SQL Server는 showplan XML(네임스페이스 {@code http://schemas.microsoft.com/sqlserver/2004/07/showplan})을 DOM으로 읽는다. 외부 엔티티를
 *     끈다(XXE 방지). PostgreSQL은 {@code EXPLAIN (FORMAT JSON)}의 "Plan" 트리를 같은 모양으로 바꾼다. Oracle은 계획 행(PLAN_TABLE 또는
 *     {@code V$SQL_PLAN_STATISTICS_ALL})의 id/parent_id로 트리를 만든다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-08 Oracle 계획 행 해석({@link #parseOracle}): 예상 계획(PlanTools에서 옮김)과 실제 계획(last_* 실행 통계), 전체 테이블 스캔 소견
 */
public final class PlanParser {

    private static final int HOTSPOTS = 10;

    private PlanParser() {
    }

    // ----------------------------------------------------------------- SQL Server

    /**
     * Parses a SQL Server showplan XML document: {@code {statements:[{text, cost, estRows, root, missingIndexes, parameters,
     * warnings, ...}], hotspots, findings}}.
     */
    @NotNull
    public static JsonObject parseSqlServer(@NotNull String xml) throws DBException {
        Document document = parseXml(xml);
        JsonArray statements = new JsonArray();
        List<JsonObject> nodes = new ArrayList<>();
        JsonArray findings = new JsonArray();
        for (Element stmt : descendants(document.getDocumentElement(), "StmtSimple")) {
            JsonObject statement = new JsonObject();
            statement.addProperty("text", attr(stmt, "StatementText"));
            addNumber(statement, "cost", attr(stmt, "StatementSubTreeCost"));
            addNumber(statement, "estRows", attr(stmt, "StatementEstRows"));
            statement.addProperty("optimizationLevel", attr(stmt, "StatementOptmLevel"));
            statement.addProperty("earlyAbortReason", attr(stmt, "StatementOptmEarlyAbortReason"));
            statement.addProperty("queryHash", attr(stmt, "QueryHash"));
            Element queryPlan = child(stmt, "QueryPlan");
            if (queryPlan != null) {
                addNumber(statement, "degreeOfParallelism", attr(queryPlan, "DegreeOfParallelism"));
                addNumber(statement, "compileTimeMs", attr(queryPlan, "CompileTime"));
                Element grant = child(queryPlan, "MemoryGrantInfo");
                if (grant != null) {
                    JsonObject memory = new JsonObject();
                    addNumber(memory, "requestedKb", attr(grant, "RequestedMemory"));
                    addNumber(memory, "grantedKb", attr(grant, "GrantedMemory"));
                    addNumber(memory, "maxUsedKb", attr(grant, "MaxUsedMemory"));
                    statement.add("memoryGrant", memory);
                }
                JsonArray warnings = warnings(child(queryPlan, "Warnings"));
                if (!warnings.isEmpty()) {
                    statement.add("warnings", warnings);
                    warnings.forEach(w -> findings.add("Statement warning: " + w.getAsString()));
                }
                JsonArray missing = new JsonArray();
                for (Element group : descendants(queryPlan, "MissingIndexGroup")) {
                    for (Element index : children(group, "MissingIndex")) {
                        JsonObject m = new JsonObject();
                        m.addProperty("impactPercent", attr(group, "Impact"));
                        m.addProperty("table", attr(index, "Database") + "." + attr(index, "Schema") + "." + attr(index, "Table"));
                        for (Element columnGroup : children(index, "ColumnGroup")) {
                            List<String> columns = new ArrayList<>();
                            for (Element column : children(columnGroup, "Column")) {
                                columns.add(attr(column, "Name"));
                            }
                            m.addProperty(columnGroup.getAttribute("Usage").toLowerCase(Locale.ROOT), String.join(", ", columns));
                        }
                        missing.add(m);
                        findings.add("Missing index suggested by the optimizer on " + m.get("table").getAsString() +
                            " (impact " + attr(group, "Impact") + "%)");
                    }
                }
                if (!missing.isEmpty()) {
                    statement.add("missingIndexes", missing);
                }
                JsonArray parameters = new JsonArray();
                Element parameterList = child(queryPlan, "ParameterList");
                if (parameterList != null) {
                    for (Element reference : children(parameterList, "ColumnReference")) {
                        JsonObject p = new JsonObject();
                        p.addProperty("name", attr(reference, "Column"));
                        p.addProperty("compiledValue", attr(reference, "ParameterCompiledValue"));
                        p.addProperty("runtimeValue", attr(reference, "ParameterRuntimeValue"));
                        p.addProperty("dataType", attr(reference, "ParameterDataType"));
                        parameters.add(p);
                        String compiled = attr(reference, "ParameterCompiledValue");
                        String runtime = attr(reference, "ParameterRuntimeValue");
                        if (compiled != null && runtime != null && !compiled.equals(runtime)) {
                            findings.add("Parameter " + attr(reference, "Column") + " was compiled for " + compiled +
                                " but ran with " + runtime + " (possible parameter sniffing)");
                        }
                    }
                }
                if (!parameters.isEmpty()) {
                    statement.add("parameters", parameters);
                }
                Element relOp = child(queryPlan, "RelOp");
                if (relOp != null) {
                    statement.add("root", sqlServerNode(relOp, nodes, findings));
                }
            }
            statements.add(statement);
        }
        JsonObject result = new JsonObject();
        result.add("statements", statements);
        result.add("hotspots", hotspots(nodes));
        result.add("findings", findings);
        return result;
    }

    @NotNull
    private static JsonObject sqlServerNode(@NotNull Element relOp, @NotNull List<JsonObject> nodes, @NotNull JsonArray findings) {
        JsonObject node = new JsonObject();
        node.addProperty("nodeId", attr(relOp, "NodeId"));
        node.addProperty("op", attr(relOp, "PhysicalOp"));
        node.addProperty("logicalOp", attr(relOp, "LogicalOp"));
        addNumber(node, "estRows", attr(relOp, "EstimateRows"));
        addNumber(node, "estExecutions", attr(relOp, "EstimateRewinds"));
        addNumber(node, "cost", attr(relOp, "EstimatedTotalSubtreeCost"));
        addNumber(node, "estIO", attr(relOp, "EstimateIO"));
        addNumber(node, "estCPU", attr(relOp, "EstimateCPU"));
        if ("1".equals(attr(relOp, "Parallel")) || "true".equals(attr(relOp, "Parallel"))) {
            node.addProperty("parallel", true);
        }
        // Actual values (actual plan): sum over threads
        Element runtime = child(relOp, "RunTimeInformation");
        if (runtime != null) {
            double rows = 0, executions = 0, elapsed = 0, reads = 0;
            boolean hasElapsed = false;
            for (Element thread : children(runtime, "RunTimeCountersPerThread")) {
                rows += number(attr(thread, "ActualRows"));
                executions += number(attr(thread, "ActualExecutions"));
                reads += number(attr(thread, "ActualLogicalReads"));
                String ms = attr(thread, "ActualElapsedms");
                if (ms != null) {
                    elapsed = Math.max(elapsed, number(ms));
                    hasElapsed = true;
                }
            }
            node.addProperty("actualRows", rows);
            node.addProperty("executions", executions);
            if (reads > 0) {
                node.addProperty("reads", reads);
            }
            if (hasElapsed) {
                node.addProperty("actualTimeMs", elapsed);
            }
        }
        // The operator element (IndexScan, TableScan, NestedLoops, Hash, Sort, ...) is the first element child after the
        // optional Warnings/MemoryFractions/RunTimeInformation children
        String object = null;
        String predicate = null;
        String seek = null;
        boolean lookup = false;
        for (Element e : children(relOp, null)) {
            String name = e.getLocalName();
            if (name.equals("OutputList") || name.equals("Warnings") || name.equals("MemoryFractions") ||
                name.equals("RunTimeInformation") || name.equals("RunTimePartitionSummary") || name.equals("InternalInfo")) {
                continue;
            }
            Element objectElement = child(e, "Object");
            if (objectElement != null) {
                object = joinName(attr(objectElement, "Schema"), attr(objectElement, "Table"), attr(objectElement, "Index"));
            }
            lookup = "1".equals(e.getAttribute("Lookup")) || "true".equals(e.getAttribute("Lookup"));
            Element predicateElement = child(e, "Predicate");
            if (predicateElement != null) {
                predicate = scalarString(predicateElement);
            }
            Element seekElement = child(e, "SeekPredicates");
            if (seekElement != null) {
                seek = scalarStrings(seekElement);
            }
            break;
        }
        if (object != null) {
            node.addProperty("object", object);
        }
        if (predicate != null) {
            node.addProperty("predicate", predicate);
            if (predicate.contains("CONVERT_IMPLICIT")) {
                findings.add("Implicit conversion in a predicate of " + node.get("op").getAsString() +
                    (object == null ? "" : " on " + object) + ": " + predicate);
            }
        }
        if (seek != null) {
            node.addProperty("seekPredicate", seek);
        }
        if (lookup) {
            node.addProperty("keyLookup", true);
            findings.add("Key lookup on " + object + " (consider covering the columns with INCLUDE)");
        }
        JsonArray warnings = warnings(child(relOp, "Warnings"));
        if (!warnings.isEmpty()) {
            node.add("warnings", warnings);
            for (JsonElement w : warnings) {
                findings.add(node.get("op").getAsString() + " (node " + attr(relOp, "NodeId") + "): " + w.getAsString());
            }
        }
        addScanFinding(node, findings);
        addEstimateFinding(node, findings);
        nodes.add(node);
        JsonArray childNodes = new JsonArray();
        for (Element nested : directRelOps(relOp)) {
            childNodes.add(sqlServerNode(nested, nodes, findings));
        }
        if (!childNodes.isEmpty()) {
            node.add("children", childNodes);
        }
        return node;
    }

    // RelOp children of a RelOp are nested inside its operator element (e.g. NestedLoops/RelOp)
    @NotNull
    private static List<Element> directRelOps(@NotNull Element relOp) {
        List<Element> result = new ArrayList<>();
        for (Element e : children(relOp, null)) {
            collectRelOps(e, result);
        }
        return result;
    }

    private static void collectRelOps(@NotNull Element element, @NotNull List<Element> result) {
        for (Element e : children(element, null)) {
            if (e.getLocalName().equals("RelOp")) {
                result.add(e);
            } else {
                collectRelOps(e, result);
            }
        }
    }

    @NotNull
    private static JsonArray warnings(@Nullable Element warnings) {
        JsonArray result = new JsonArray();
        if (warnings == null) {
            return result;
        }
        for (int i = 0; i < warnings.getAttributes().getLength(); i++) {
            Node a = warnings.getAttributes().item(i);
            if ("true".equals(a.getNodeValue()) || "1".equals(a.getNodeValue())) {
                result.add(a.getNodeName());
            }
        }
        for (Element w : children(warnings, null)) {
            StringBuilder text = new StringBuilder(w.getLocalName());
            for (int i = 0; i < w.getAttributes().getLength(); i++) {
                Node a = w.getAttributes().item(i);
                text.append(' ').append(a.getNodeName()).append('=').append(a.getNodeValue());
            }
            if (w.getLocalName().equals("ColumnsWithNoStatistics")) {
                List<String> columns = new ArrayList<>();
                for (Element c : descendants(w, "ColumnReference")) {
                    columns.add(joinName(attr(c, "Table"), attr(c, "Column"), null));
                }
                text.append(": ").append(String.join(", ", columns));
            }
            result.add(text.toString());
        }
        return result;
    }

    @Nullable
    private static String scalarString(@NotNull Element element) {
        for (Element e : descendants(element, "ScalarOperator")) {
            String s = attr(e, "ScalarString");
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    @Nullable
    private static String scalarStrings(@NotNull Element element) {
        List<String> parts = new ArrayList<>();
        for (Element e : descendants(element, "RangeColumns")) {
            List<String> columns = new ArrayList<>();
            for (Element c : children(e, "ColumnReference")) {
                columns.add(attr(c, "Column"));
            }
            parts.add(String.join(", ", columns));
        }
        return parts.isEmpty() ? null : "seek on " + String.join(" / ", parts);
    }

    // ----------------------------------------------------------------- PostgreSQL

    /**
     * Parses the output of {@code EXPLAIN (FORMAT JSON)} (an array with one object per statement).
     */
    @NotNull
    public static JsonObject parsePostgres(@NotNull String json) {
        JsonElement parsed = JsonParser.parseString(json);
        JsonArray input = parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        if (parsed.isJsonObject()) {
            input.add(parsed);
        }
        JsonArray statements = new JsonArray();
        List<JsonObject> nodes = new ArrayList<>();
        JsonArray findings = new JsonArray();
        for (JsonElement element : input) {
            JsonObject source = element.getAsJsonObject();
            JsonObject statement = new JsonObject();
            JsonObject plan = source.getAsJsonObject("Plan");
            if (plan != null) {
                JsonObject root = postgresNode(plan, nodes, findings);
                statement.add("root", root);
                statement.add("cost", root.get("cost"));
                statement.add("estRows", root.get("estRows"));
            }
            copy(source, statement, "Planning Time", "planningTimeMs");
            copy(source, statement, "Execution Time", "executionTimeMs");
            if (source.has("Triggers")) {
                statement.add("triggers", source.get("Triggers"));
            }
            if (source.has("JIT")) {
                statement.add("jit", source.get("JIT"));
            }
            statements.add(statement);
        }
        JsonObject result = new JsonObject();
        result.add("statements", statements);
        result.add("hotspots", hotspots(nodes));
        result.add("findings", findings);
        return result;
    }

    @NotNull
    private static JsonObject postgresNode(@NotNull JsonObject plan, @NotNull List<JsonObject> nodes, @NotNull JsonArray findings) {
        JsonObject node = new JsonObject();
        String op = string(plan, "Node Type");
        String strategy = string(plan, "Strategy");
        node.addProperty("op", op);
        if (strategy != null) {
            node.addProperty("logicalOp", strategy);
        }
        String relation = string(plan, "Relation Name");
        String index = string(plan, "Index Name");
        if (relation != null || index != null) {
            node.addProperty("object", joinName(string(plan, "Schema"), relation, index));
        }
        copy(plan, node, "Plan Rows", "estRows");
        copy(plan, node, "Total Cost", "cost");
        copy(plan, node, "Startup Cost", "startupCost");
        copy(plan, node, "Actual Rows", "actualRows");
        copy(plan, node, "Actual Loops", "executions");
        copy(plan, node, "Actual Total Time", "actualTimeMs");
        if (plan.has("Shared Hit Blocks") || plan.has("Shared Read Blocks")) {
            JsonObject buffers = new JsonObject();
            copy(plan, buffers, "Shared Hit Blocks", "sharedHit");
            copy(plan, buffers, "Shared Read Blocks", "sharedRead");
            copy(plan, buffers, "Temp Read Blocks", "tempRead");
            copy(plan, buffers, "Temp Written Blocks", "tempWritten");
            node.add("buffers", buffers);
        }
        String filter = string(plan, "Filter");
        if (filter != null) {
            node.addProperty("predicate", filter);
        }
        String condition = firstNonNull(string(plan, "Index Cond"), string(plan, "Hash Cond"), string(plan, "Merge Cond"),
            string(plan, "Join Filter"), string(plan, "Recheck Cond"));
        if (condition != null) {
            node.addProperty("seekPredicate", condition);
        }
        copy(plan, node, "Rows Removed by Filter", "rowsRemovedByFilter");
        JsonArray warnings = new JsonArray();
        if ("Disk".equals(string(plan, "Sort Space Type")) || plan.has("Sort Method") && string(plan, "Sort Method").contains("external")) {
            warnings.add("Sort spilled to disk (" + string(plan, "Sort Method") + ", " + string(plan, "Sort Space Used") + " kB)");
        }
        if (plan.has("Batches") && plan.get("Batches").getAsInt() > 1) {
            warnings.add("Hash used " + plan.get("Batches").getAsInt() + " batches (spilled to disk; raise work_mem)");
        }
        if (plan.has("Temp Written Blocks") && plan.get("Temp Written Blocks").getAsLong() > 0) {
            warnings.add("Wrote " + plan.get("Temp Written Blocks").getAsLong() + " temp blocks");
        }
        if (!warnings.isEmpty()) {
            node.add("warnings", warnings);
            for (JsonElement w : warnings) {
                findings.add(op + (relation == null ? "" : " on " + relation) + ": " + w.getAsString());
            }
        }
        addScanFinding(node, findings);
        addEstimateFinding(node, findings);
        nodes.add(node);
        JsonArray children = new JsonArray();
        JsonArray plans = plan.getAsJsonArray("Plans");
        if (plans != null) {
            for (JsonElement p : plans) {
                children.add(postgresNode(p.getAsJsonObject(), nodes, findings));
            }
        }
        if (!children.isEmpty()) {
            node.add("children", children);
        }
        return node;
    }

    // ----------------------------------------------------------------- Oracle

    /**
     * Builds the plan from Oracle plan rows (lower-case column names): PLAN_TABLE rows of {@code EXPLAIN PLAN}, or
     * {@code V$SQL_PLAN_STATISTICS_ALL} rows of an executed cursor with the {@code last_*} run-time columns.
     *
     * @param text the DBMS_XPLAN output, kept as the plan text Oracle users know
     */
    @NotNull
    public static JsonObject parseOracle(@NotNull JsonArray rows, @NotNull String text) {
        Map<String, JsonObject> byId = new HashMap<>();
        List<JsonObject> nodes = new ArrayList<>();
        JsonArray findings = new JsonArray();
        JsonObject root = null;
        for (JsonElement r : rows) {
            JsonObject row = r.getAsJsonObject();
            JsonObject node = new JsonObject();
            String id = string(row, "id");
            String op = string(row, "operation");
            String options = string(row, "options");
            node.addProperty("nodeId", id);
            node.addProperty("op", options == null ? op : op + " " + options);
            String object = string(row, "object_name");
            if (object != null) {
                node.addProperty("object", joinName(string(row, "object_owner"), object, null));
            }
            copy(row, node, "cost", "cost");
            // E-Rows: rows per start of the operation
            copy(row, node, "cardinality", "estRows");
            copy(row, node, "access_predicates", "seekPredicate");
            copy(row, node, "filter_predicates", "predicate");
            // A-Rows: the total over all starts of the last execution, like SQL Server's actual rows
            copy(row, node, "last_output_rows", "actualRows");
            copy(row, node, "last_starts", "executions");
            copy(row, node, "last_cr_buffer_gets", "reads");
            copy(row, node, "last_disk_reads", "physicalReads");
            copy(row, node, "last_tempseg_size", "tempBytes");
            String elapsed = string(row, "last_elapsed_time");
            if (elapsed != null) {
                // Microseconds, including the children (like actualTimeMs of the other databases)
                node.addProperty("actualTimeMs", Math.round(number(elapsed) / 10) / 100.0);
            }
            if (node.has("tempBytes") && node.get("tempBytes").getAsDouble() > 0) {
                findings.add(node.get("op").getAsString() + (object == null ? "" : " on " + object) + ": spilled " +
                    node.get("tempBytes").getAsLong() + " bytes to temp");
            }
            addScanFinding(node, findings);
            addEstimateFinding(node, findings);
            nodes.add(node);
            byId.put(id, node);
            JsonObject parent = byId.get(string(row, "parent_id"));
            if (parent == null) {
                if (root == null) {
                    root = node;
                }
            } else {
                if (!parent.has("children")) {
                    parent.add("children", new JsonArray());
                }
                parent.getAsJsonArray("children").add(node);
            }
        }
        JsonObject plan = new JsonObject();
        JsonArray statements = new JsonArray();
        JsonObject statement = new JsonObject();
        if (root != null) {
            statement.add("root", root);
            statement.add("cost", root.get("cost"));
            statement.add("estRows", root.get("estRows"));
        }
        statements.add(statement);
        plan.add("statements", statements);
        plan.add("hotspots", hotspots(nodes));
        plan.add("findings", findings);
        plan.addProperty("text", text);
        return plan;
    }

    // ----------------------------------------------------------------- common

    /**
     * Compares two parsed plans: total cost, the operator multiset and scan/seek changes.
     */
    @NotNull
    public static JsonObject compare(@NotNull JsonObject a, @NotNull JsonObject b) {
        JsonObject diff = new JsonObject();
        double costA = totalCost(a);
        double costB = totalCost(b);
        diff.addProperty("costA", costA);
        diff.addProperty("costB", costB);
        if (costA > 0) {
            diff.addProperty("costChangePercent", Math.round((costB - costA) / costA * 1000) / 10.0);
        }
        double timeA = totalTime(a);
        double timeB = totalTime(b);
        if (timeA >= 0 && timeB >= 0) {
            diff.addProperty("actualTimeMsA", timeA);
            diff.addProperty("actualTimeMsB", timeB);
        }
        List<String> opsA = operators(a);
        List<String> opsB = operators(b);
        JsonArray onlyA = new JsonArray();
        JsonArray onlyB = new JsonArray();
        List<String> restB = new ArrayList<>(opsB);
        for (String op : opsA) {
            if (!restB.remove(op)) {
                onlyA.add(op);
            }
        }
        List<String> restA = new ArrayList<>(opsA);
        for (String op : opsB) {
            if (!restA.remove(op)) {
                onlyB.add(op);
            }
        }
        diff.add("operatorsOnlyInA", onlyA);
        diff.add("operatorsOnlyInB", onlyB);
        diff.addProperty("findingsA", a.getAsJsonArray("findings") == null ? 0 : a.getAsJsonArray("findings").size());
        diff.addProperty("findingsB", b.getAsJsonArray("findings") == null ? 0 : b.getAsJsonArray("findings").size());
        return diff;
    }

    private static double totalCost(@NotNull JsonObject plan) {
        double total = 0;
        JsonArray statements = plan.getAsJsonArray("statements");
        if (statements != null) {
            for (JsonElement s : statements) {
                JsonElement cost = s.getAsJsonObject().get("cost");
                if (cost != null && !cost.isJsonNull()) {
                    total += cost.getAsDouble();
                }
            }
        }
        return total;
    }

    private static double totalTime(@NotNull JsonObject plan) {
        JsonArray statements = plan.getAsJsonArray("statements");
        double total = -1;
        if (statements != null) {
            for (JsonElement s : statements) {
                JsonObject root = s.getAsJsonObject().getAsJsonObject("root");
                if (root != null && root.has("actualTimeMs")) {
                    total = Math.max(total, 0) + root.get("actualTimeMs").getAsDouble();
                }
            }
        }
        return total;
    }

    @NotNull
    private static List<String> operators(@NotNull JsonObject plan) {
        List<String> result = new ArrayList<>();
        JsonArray statements = plan.getAsJsonArray("statements");
        if (statements != null) {
            for (JsonElement s : statements) {
                JsonObject root = s.getAsJsonObject().getAsJsonObject("root");
                if (root != null) {
                    collectOperators(root, result);
                }
            }
        }
        return result;
    }

    private static void collectOperators(@NotNull JsonObject node, @NotNull List<String> result) {
        String op = node.has("op") ? node.get("op").getAsString() : "?";
        result.add(node.has("object") ? op + " " + node.get("object").getAsString() : op);
        JsonArray children = node.getAsJsonArray("children");
        if (children != null) {
            for (JsonElement c : children) {
                collectOperators(c.getAsJsonObject(), result);
            }
        }
    }

    private static void addScanFinding(@NotNull JsonObject node, @NotNull JsonArray findings) {
        String op = node.has("op") ? node.get("op").getAsString() : "";
        boolean scan = op.equals("Table Scan") || op.equals("Clustered Index Scan") || op.equals("Index Scan") && !node.has("seekPredicate")
            || op.equals("Seq Scan") || op.startsWith("TABLE ACCESS") && op.endsWith(" FULL");
        if (scan && node.has("object")) {
            double rows = node.has("actualRows") ? node.get("actualRows").getAsDouble() : node.has("estRows") ? node.get("estRows").getAsDouble() : 0;
            String predicate = node.has("predicate") ? " with filter " + node.get("predicate").getAsString() : "";
            findings.add(op + " on " + node.get("object").getAsString() + " (" + Math.round(rows) + " rows)" + predicate);
        }
    }

    private static void addEstimateFinding(@NotNull JsonObject node, @NotNull JsonArray findings) {
        if (!node.has("actualRows") || !node.has("estRows")) {
            return;
        }
        double executions = node.has("executions") ? Math.max(1, node.get("executions").getAsDouble()) : 1;
        double estimated = Math.max(1, node.get("estRows").getAsDouble());
        // PostgreSQL reports rows per loop; SQL Server reports the total over executions
        double actual = Math.max(1, node.get("actualRows").getAsDouble() / (node.has("buffers") || node.has("startupCost") ? 1 : executions));
        double ratio = Math.max(estimated, actual) / Math.min(estimated, actual);
        if (ratio >= 10 && Math.max(estimated, actual) >= 100) {
            node.addProperty("estimateErrorFactor", Math.round(ratio));
            findings.add("Row estimate off by " + Math.round(ratio) + "x at " + node.get("op").getAsString() +
                (node.has("object") ? " on " + node.get("object").getAsString() : "") + " (estimated " + Math.round(estimated) +
                ", actual " + Math.round(actual) + "; check statistics)");
        }
    }

    @NotNull
    private static JsonArray hotspots(@NotNull List<JsonObject> nodes) {
        List<JsonObject> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparingDouble(PlanParser::selfWeight).reversed());
        JsonArray result = new JsonArray();
        for (JsonObject node : sorted.subList(0, Math.min(HOTSPOTS, sorted.size()))) {
            JsonObject h = new JsonObject();
            for (String key : List.of("nodeId", "op", "object", "estRows", "actualRows", "executions", "cost", "actualTimeMs",
                "predicate", "seekPredicate", "warnings")) {
                if (node.has(key)) {
                    h.add(key, node.get(key));
                }
            }
            result.add(h);
        }
        return result;
    }

    // Own cost of a node: subtree cost (or time) minus its children's
    private static double selfWeight(@NotNull JsonObject node) {
        String key = node.has("actualTimeMs") ? "actualTimeMs" : "cost";
        if (!node.has(key)) {
            return 0;
        }
        double own = node.get(key).getAsDouble();
        JsonArray children = node.getAsJsonArray("children");
        if (children != null) {
            for (JsonElement c : children) {
                JsonObject child = c.getAsJsonObject();
                if (child.has(key)) {
                    own -= child.get(key).getAsDouble();
                }
            }
        }
        return own;
    }

    @NotNull
    static Document parseXml(@NotNull String xml) throws DBException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new DBException("Can't parse the XML: " + e.getMessage(), e);
        }
    }

    @NotNull
    static List<Element> children(@NotNull Element parent, @Nullable String localName) {
        List<Element> result = new ArrayList<>();
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element e && (localName == null || localName.equals(e.getLocalName()))) {
                result.add(e);
            }
        }
        return result;
    }

    @Nullable
    static Element child(@Nullable Element parent, @NotNull String localName) {
        if (parent == null) {
            return null;
        }
        List<Element> list = children(parent, localName);
        return list.isEmpty() ? null : list.getFirst();
    }

    @NotNull
    static List<Element> descendants(@NotNull Element parent, @NotNull String localName) {
        List<Element> result = new ArrayList<>();
        NodeList list = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < list.getLength(); i++) {
            result.add((Element) list.item(i));
        }
        return result;
    }

    @Nullable
    static String attr(@NotNull Element element, @NotNull String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }

    private static void addNumber(@NotNull JsonObject json, @NotNull String name, @Nullable String value) {
        if (value != null && !value.isEmpty()) {
            try {
                json.addProperty(name, Double.parseDouble(value));
            } catch (NumberFormatException e) {
                json.addProperty(name, value);
            }
        }
    }

    private static double number(@Nullable String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Nullable
    private static String joinName(@Nullable String a, @Nullable String b, @Nullable String c) {
        List<String> parts = new ArrayList<>();
        for (String part : new String[] {a, b, c}) {
            if (part != null && !part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? null : String.join(".", parts);
    }

    @Nullable
    private static String string(@NotNull JsonObject json, @NotNull String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static void copy(@NotNull JsonObject from, @NotNull JsonObject to, @NotNull String fromName, @NotNull String toName) {
        JsonElement value = from.get(fromName);
        if (value != null && !value.isJsonNull()) {
            to.add(toName, value);
        }
    }

    @Nullable
    private static String firstNonNull(@Nullable String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
