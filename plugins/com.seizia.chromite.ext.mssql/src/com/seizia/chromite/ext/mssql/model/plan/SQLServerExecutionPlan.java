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
package com.seizia.chromite.ext.mssql.model.plan;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.exec.plan.DBCPlanSourceFormat;
import com.seizia.chromite.model.impl.plan.AbstractExecutionPlan;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.xml.XMLException;
import org.jkiss.utils.xml.XMLUtils;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * A SQL Server showplan (XML), estimated ({@code SET SHOWPLAN_XML}) or actual ({@code SET STATISTICS XML}).
 *
 * 설계: showplan XML은 {@code BatchSequence/Batch/Statements/StmtSimple} 아래 {@code QueryPlan/RelOp}가 연산자 트리다.
 * 자식 연산자는 연산자별 요소({@code NestedLoops}, {@code Hash} 등) 안에 있으므로, 요소를 내려가다 처음 만나는 {@code RelOp}를 자식으로 삼는다.
 * 개체·술어·출력 열도 같은 규칙으로, 다음 {@code RelOp} 아래로는 내려가지 않고 자기 연산자 요소 안에서만 찾는다.
 * 실제 계획의 실행 값({@code RunTimeCountersPerThread})은 스레드별로 나뉘어 있어 행 수·실행 수는 합치고 경과 시간은 가장 큰 값을 쓴다.
 * 파서는 네임스페이스를 보지 않는 DOM이라 요소 이름에서 접두사를 떼고 비교한다. 원문 XML은 {@link #getPlanSourceData}로 남긴다.
 * <ul>
 *     <li>경고는 서버가 계획에 적은 것만 읽는다: 연산자의 {@code RelOp/Warnings}(분산·조인 조건 없음·통계 없음 등)와 문장의
 *     {@code QueryPlan/Warnings}(계획에 영향을 주는 암시적 변환, 메모리 부여). 누락 인덱스 제안({@code QueryPlan/MissingIndexes})은
 *     문장 노드에 둔다. 그래픽 계획이 배지와 머리말을 그리는 데 쓴다.</li>
 *     <li>키 조회는 XML에서 {@code PhysicalOp="Clustered Index Seek"}이고 연산자 요소 {@code IndexScan}의 {@code Lookup} 속성으로만
 *     구별된다. SQL Server 도구처럼 "Key Lookup"으로 보이게 연산 이름을 바꾼다(그대로 두면 일반 Seek와 구별되지 않는다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-07 경고·누락 인덱스·예상 실행 수를 읽고 키 조회를 "Key Lookup"으로 (docs/chromite/swing-sql-editor-plus.md "실행 계획 세 보기")
 */
public class SQLServerExecutionPlan extends AbstractExecutionPlan {

    private final String query;
    private final String planXml;
    private final List<SQLServerPlanNode> rootNodes;

    private SQLServerExecutionPlan(@NotNull String query, @NotNull String planXml, @NotNull List<SQLServerPlanNode> rootNodes) {
        this.query = query;
        this.planXml = planXml;
        this.rootNodes = rootNodes;
    }

    /**
     * Parses a showplan XML document: one root node per statement.
     *
     * @param query the planned text, or null to use the statement texts in the plan (an actual plan arrives per statement
     *              while the whole batch is executed)
     */
    @NotNull
    public static SQLServerExecutionPlan parse(@Nullable String query, @NotNull String planXml) throws DBException {
        Element root;
        try {
            root = XMLUtils.parseDocument(new StringReader(planXml)).getDocumentElement();
        } catch (XMLException e) {
            throw new DBException("Can't parse the showplan XML", e);
        }
        List<SQLServerPlanNode> statements = new ArrayList<>();
        collectStatements(root, statements);
        double batchCost = 0;
        for (SQLServerPlanNode statement : statements) {
            batchCost += statement.subtreeCost != null ? statement.subtreeCost : 0;
        }
        for (SQLServerPlanNode statement : statements) {
            if (batchCost > 0 && statement.subtreeCost != null) {
                statement.percent = statement.subtreeCost * 100 / batchCost;
            }
        }
        if (query == null) {
            StringJoiner texts = new StringJoiner("\n"); //$NON-NLS-1$
            for (SQLServerPlanNode statement : statements) {
                if (!CommonUtils.isEmpty(statement.statementText)) {
                    texts.add(statement.statementText.strip());
                }
            }
            query = texts.toString();
        }
        return new SQLServerExecutionPlan(query, planXml, statements);
    }

    @NotNull
    @Override
    public String getQueryString() {
        return query;
    }

    @NotNull
    @Override
    public String getPlanQueryString() {
        return query;
    }

    @NotNull
    @Override
    public DBCPlanSourceFormat getPlanSourceDataFormat() {
        return DBCPlanSourceFormat.XML;
    }

    @NotNull
    @Override
    public Object getPlanSourceData() {
        return planXml;
    }

    @NotNull
    @Override
    public List<SQLServerPlanNode> getPlanNodes(@NotNull Map<String, Object> options) {
        return rootNodes;
    }

    private static void collectStatements(@NotNull Element element, @NotNull List<SQLServerPlanNode> statements) {
        for (Element child : childElements(element)) {
            if (localName(child).equals("StmtSimple")) { //$NON-NLS-1$
                statements.add(parseStatement(child));
            } else {
                // Batches, statement lists and conditional statements (StmtCond) hold the statements
                collectStatements(child, statements);
            }
        }
    }

    @NotNull
    private static SQLServerPlanNode parseStatement(@NotNull Element element) {
        String type = CommonUtils.toString(element.getAttribute("StatementType"), "Statement"); //$NON-NLS-1$ //$NON-NLS-2$
        SQLServerPlanNode node = new SQLServerPlanNode(null, true, CommonUtils.isEmpty(type) ? "Statement" : type, null); //$NON-NLS-1$
        node.statementText = CommonUtils.nullIfEmpty(element.getAttribute("StatementText")); //$NON-NLS-1$
        node.subtreeCost = toDouble(element.getAttribute("StatementSubTreeCost")); //$NON-NLS-1$
        node.estimateRows = toDouble(element.getAttribute("StatementEstRows")); //$NON-NLS-1$
        for (Element queryPlan : childElements(element)) {
            if (localName(queryPlan).equals("QueryPlan")) { //$NON-NLS-1$
                for (Element child : childElements(queryPlan)) {
                    if (localName(child).equals("Warnings")) { //$NON-NLS-1$
                        readWarnings(node, child);
                    } else if (localName(child).equals("MissingIndexes")) { //$NON-NLS-1$
                        readMissingIndexes(node, child);
                    }
                }
                collectOperators(node, queryPlan, node);
            }
        }
        return node;
    }

    /**
     * Warnings of an operator or statement: the warning elements and the flag attributes of {@code Warnings}.
     */
    private static void readWarnings(@NotNull SQLServerPlanNode node, @NotNull Element warnings) {
        if (isTrue(warnings.getAttribute("NoJoinPredicate"))) { //$NON-NLS-1$
            node.warnings.add(new SQLServerPlanNode.Warning(SQLServerPlanNode.WarningKind.NO_JOIN_PREDICATE, "NoJoinPredicate", "")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (isTrue(warnings.getAttribute("UnmatchedIndexes"))) { //$NON-NLS-1$
            node.warnings.add(new SQLServerPlanNode.Warning(SQLServerPlanNode.WarningKind.OTHER, "UnmatchedIndexes", "")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (Element warning : childElements(warnings)) {
            String name = localName(warning);
            SQLServerPlanNode.WarningKind kind = switch (name) {
                case "PlanAffectingConvert" -> SQLServerPlanNode.WarningKind.IMPLICIT_CONVERSION; //$NON-NLS-1$
                case "SpillToTempDb", "SortSpillDetails", "HashSpillDetails", "ExchangeSpillDetails", "SpillOccurred" -> //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                    SQLServerPlanNode.WarningKind.SPILL;
                case "ColumnsWithNoStatistics" -> SQLServerPlanNode.WarningKind.NO_STATISTICS; //$NON-NLS-1$
                case "MemoryGrantWarning" -> SQLServerPlanNode.WarningKind.MEMORY_GRANT; //$NON-NLS-1$
                default -> SQLServerPlanNode.WarningKind.OTHER;
            };
            String detail;
            if (kind == SQLServerPlanNode.WarningKind.IMPLICIT_CONVERSION) {
                detail = warning.getAttribute("ConvertIssue") + ": " + warning.getAttribute("Expression"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            } else if (kind == SQLServerPlanNode.WarningKind.NO_STATISTICS) {
                StringJoiner columns = new StringJoiner(", "); //$NON-NLS-1$
                for (Element column : childElements(warning)) {
                    columns.add(describeColumn(column));
                }
                detail = columns.toString();
            } else {
                // The attributes tell the details (SpillLevel, GrantWarningKind, WaitType, ...)
                StringJoiner attributes = new StringJoiner(", "); //$NON-NLS-1$
                for (int i = 0; i < warning.getAttributes().getLength(); i++) {
                    Node attribute = warning.getAttributes().item(i);
                    attributes.add(attribute.getNodeName() + "=" + attribute.getNodeValue()); //$NON-NLS-1$
                }
                detail = attributes.toString();
            }
            node.warnings.add(new SQLServerPlanNode.Warning(kind, name, detail));
        }
    }

    private static void readMissingIndexes(@NotNull SQLServerPlanNode statement, @NotNull Element missingIndexes) {
        for (Element group : childElements(missingIndexes)) {
            if (!localName(group).equals("MissingIndexGroup")) { //$NON-NLS-1$
                continue;
            }
            Double impact = toDouble(group.getAttribute("Impact")); //$NON-NLS-1$
            for (Element index : childElements(group)) {
                if (!localName(index).equals("MissingIndex")) { //$NON-NLS-1$
                    continue;
                }
                List<String> equality = new ArrayList<>();
                List<String> inequality = new ArrayList<>();
                List<String> include = new ArrayList<>();
                for (Element columnGroup : childElements(index)) {
                    List<String> target = switch (columnGroup.getAttribute("Usage")) { //$NON-NLS-1$
                        case "EQUALITY" -> equality; //$NON-NLS-1$
                        case "INEQUALITY" -> inequality; //$NON-NLS-1$
                        default -> include;
                    };
                    for (Element column : childElements(columnGroup)) {
                        target.add(column.getAttribute("Name")); //$NON-NLS-1$
                    }
                }
                statement.missingIndexes.add(new SQLServerPlanNode.MissingIndex(impact == null ? 0 : impact,
                    index.getAttribute("Schema") + "." + index.getAttribute("Table"), equality, inequality, include)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        if (!statement.missingIndexes.isEmpty()) {
            statement.warnings.add(new SQLServerPlanNode.Warning(SQLServerPlanNode.WarningKind.MISSING_INDEX, "MissingIndexes", //$NON-NLS-1$
                statement.getMissingIndexStatements()));
        }
    }

    private static boolean isTrue(@Nullable String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Whether the operator reads a clustered index (or heap) row by row for keys found in another index:
     * its {@code IndexScan} element has {@code Lookup="1"}.
     */
    private static boolean isLookup(@NotNull Element relOp) {
        for (Element child : childElements(relOp)) {
            if (localName(child).equals("IndexScan") && isTrue(child.getAttribute("Lookup"))) { //$NON-NLS-1$ //$NON-NLS-2$
                return true;
            }
        }
        return false;
    }

    /**
     * Adds the nearest {@code RelOp} elements below {@code element} as children of {@code parent}.
     */
    private static void collectOperators(@NotNull SQLServerPlanNode parent, @NotNull Element element, @NotNull SQLServerPlanNode statement) {
        for (Element child : childElements(element)) {
            if (localName(child).equals("RelOp")) { //$NON-NLS-1$
                parent.nested.add(parseOperator(parent, child, statement));
            } else {
                collectOperators(parent, child, statement);
            }
        }
    }

    @NotNull
    private static SQLServerPlanNode parseOperator(@NotNull SQLServerPlanNode parent, @NotNull Element relOp, @NotNull SQLServerPlanNode statement) {
        String physicalOp = CommonUtils.toString(relOp.getAttribute("PhysicalOp"), "Operator"); //$NON-NLS-1$ //$NON-NLS-2$
        if (physicalOp.equals("Clustered Index Seek") && isLookup(relOp)) { //$NON-NLS-1$
            physicalOp = "Key Lookup"; //$NON-NLS-1$
        }
        SQLServerPlanNode node = new SQLServerPlanNode(parent, false, physicalOp,
            CommonUtils.nullIfEmpty(relOp.getAttribute("LogicalOp"))); //$NON-NLS-1$
        node.estimateRows = toDouble(relOp.getAttribute("EstimateRows")); //$NON-NLS-1$
        // Estimated number of executions: one plus the rebinds and rewinds (the inner side of a nested loops join)
        Double rebinds = toDouble(relOp.getAttribute("EstimateRebinds")); //$NON-NLS-1$
        Double rewinds = toDouble(relOp.getAttribute("EstimateRewinds")); //$NON-NLS-1$
        node.estimateExecutions = 1 + (rebinds == null ? 0 : rebinds) + (rewinds == null ? 0 : rewinds);
        node.subtreeCost = toDouble(relOp.getAttribute("EstimatedTotalSubtreeCost")); //$NON-NLS-1$
        node.estimateCpu = toDouble(relOp.getAttribute("EstimateCPU")); //$NON-NLS-1$
        node.estimateIo = toDouble(relOp.getAttribute("EstimateIO")); //$NON-NLS-1$
        Double nodeId = toDouble(relOp.getAttribute("NodeId")); //$NON-NLS-1$
        node.nodeId = nodeId == null ? null : nodeId.intValue();

        List<String> objects = new ArrayList<>();
        List<String> predicates = new ArrayList<>();
        StringJoiner outputs = new StringJoiner(", "); //$NON-NLS-1$
        for (Element child : childElements(relOp)) {
            String name = localName(child);
            if (name.equals("OutputList")) { //$NON-NLS-1$
                for (Element column : childElements(child)) {
                    outputs.add(describeColumn(column));
                }
            } else if (name.equals("RunTimeInformation")) { //$NON-NLS-1$
                readRuntime(node, child);
            } else if (name.equals("Warnings")) { //$NON-NLS-1$
                readWarnings(node, child);
            } else if (!name.equals("RelOp")) { //$NON-NLS-1$
                // The operator element (IndexScan, NestedLoops, Hash, ...): its own object and predicates
                scanOperatorElement(child, objects, predicates);
            }
        }
        node.objectName = objects.isEmpty() ? null : String.join(", ", objects); //$NON-NLS-1$
        node.predicate = predicates.isEmpty() ? null : String.join(" AND ", predicates); //$NON-NLS-1$
        node.outputList = outputs.length() == 0 ? null : outputs.toString();

        collectOperators(node, relOp, statement);
        if (node.subtreeCost != null && statement.subtreeCost != null && statement.subtreeCost > 0) {
            double own = node.subtreeCost;
            for (SQLServerPlanNode child : node.nested) {
                own -= child.subtreeCost != null ? child.subtreeCost : 0;
            }
            node.percent = Math.max(0, own) * 100 / statement.subtreeCost;
        }
        return node;
    }

    /**
     * Objects and predicate texts of an operator element, without descending into nested operators.
     */
    private static void scanOperatorElement(@NotNull Element element, @NotNull List<String> objects, @NotNull List<String> predicates) {
        for (Element child : childElements(element)) {
            String name = localName(child);
            if (name.equals("RelOp")) { //$NON-NLS-1$
                continue;
            }
            if (name.equals("Object")) { //$NON-NLS-1$
                String object = describeObject(child);
                if (!object.isEmpty() && !objects.contains(object)) {
                    objects.add(object);
                }
            } else if (name.equals("ScalarOperator") && localName(element).equals("Predicate")) { //$NON-NLS-1$ //$NON-NLS-2$
                addPredicate(predicates, child.getAttribute("ScalarString")); //$NON-NLS-1$
                continue;
            } else if (name.equals("Prefix") || name.equals("StartRange") || name.equals("EndRange")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                // Seek keys: columns (RangeColumns) compared with values (RangeExpressions) by the scan type
                addPredicate(predicates, describeSeekRange(child));
                continue;
            }
            scanOperatorElement(child, objects, predicates);
        }
    }

    private static void addPredicate(@NotNull List<String> predicates, @Nullable String text) {
        if (!CommonUtils.isEmpty(text) && !predicates.contains(text)) {
            predicates.add(text);
        }
    }

    @Nullable
    private static String describeSeekRange(@NotNull Element range) {
        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (Element part : childElements(range)) {
            for (Element item : childElements(part)) {
                if (localName(part).equals("RangeColumns") && localName(item).equals("ColumnReference")) { //$NON-NLS-1$ //$NON-NLS-2$
                    columns.add(describeColumn(item));
                } else if (localName(part).equals("RangeExpressions") && localName(item).equals("ScalarOperator")) { //$NON-NLS-1$ //$NON-NLS-2$
                    values.add(item.getAttribute("ScalarString")); //$NON-NLS-1$
                }
            }
        }
        String operator = switch (range.getAttribute("ScanType")) { //$NON-NLS-1$
            case "GT" -> ">"; //$NON-NLS-1$ //$NON-NLS-2$
            case "GE" -> ">="; //$NON-NLS-1$ //$NON-NLS-2$
            case "LT" -> "<"; //$NON-NLS-1$ //$NON-NLS-2$
            case "LE" -> "<="; //$NON-NLS-1$ //$NON-NLS-2$
            case "NE" -> "<>"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> "="; //$NON-NLS-1$
        };
        StringJoiner text = new StringJoiner(" AND "); //$NON-NLS-1$
        for (int i = 0; i < columns.size() && i < values.size(); i++) {
            text.add(columns.get(i) + " " + operator + " " + values.get(i)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return text.length() == 0 ? null : text.toString();
    }

    private static void readRuntime(@NotNull SQLServerPlanNode node, @NotNull Element runtime) {
        long rows = 0;
        long executions = 0;
        long elapsed = -1;
        boolean any = false;
        for (Element counters : childElements(runtime)) {
            if (!localName(counters).equals("RunTimeCountersPerThread")) { //$NON-NLS-1$
                continue;
            }
            any = true;
            rows += CommonUtils.toLong(counters.getAttribute("ActualRows")); //$NON-NLS-1$
            executions += CommonUtils.toLong(counters.getAttribute("ActualExecutions")); //$NON-NLS-1$
            if (!CommonUtils.isEmpty(counters.getAttribute("ActualElapsedms"))) { //$NON-NLS-1$
                elapsed = Math.max(elapsed, CommonUtils.toLong(counters.getAttribute("ActualElapsedms"))); //$NON-NLS-1$
            }
        }
        if (any) {
            node.actualRows = rows;
            node.actualExecutions = executions;
            node.actualElapsedMs = elapsed >= 0 ? elapsed : null;
        }
    }

    @NotNull
    private static String describeObject(@NotNull Element object) {
        StringJoiner name = new StringJoiner("."); //$NON-NLS-1$
        for (String part : new String[]{"Database", "Schema", "Table", "Index"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            String value = object.getAttribute(part);
            if (!CommonUtils.isEmpty(value)) {
                name.add(value);
            }
        }
        String alias = object.getAttribute("Alias"); //$NON-NLS-1$
        return CommonUtils.isEmpty(alias) ? name.toString() : name + " " + alias; //$NON-NLS-1$
    }

    @NotNull
    private static String describeColumn(@NotNull Element column) {
        String table = column.getAttribute("Table"); //$NON-NLS-1$
        String name = column.getAttribute("Column"); //$NON-NLS-1$
        return CommonUtils.isEmpty(table) ? name : table + "." + name; //$NON-NLS-1$
    }

    @Nullable
    private static Double toDouble(@Nullable String value) {
        if (CommonUtils.isEmpty(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @NotNull
    private static String localName(@NotNull Element element) {
        String name = element.getTagName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    @NotNull
    private static List<Element> childElements(@NotNull Element element) {
        List<Element> children = new ArrayList<>();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                children.add(child);
            }
        }
        return children;
    }
}
