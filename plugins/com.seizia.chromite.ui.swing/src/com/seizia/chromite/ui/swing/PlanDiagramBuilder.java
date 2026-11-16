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
package com.seizia.chromite.ui.swing;

import com.seizia.chromite.ext.mssql.model.plan.SQLServerPlanNode;
import com.seizia.chromite.ext.mysql.model.plan.MySQLPlanNodeJSON;
import com.seizia.chromite.ext.oracle.model.plan.OracleActualPlanNode;
import com.seizia.chromite.ext.oracle.model.plan.OraclePlanNode;
import com.seizia.chromite.ext.postgresql.model.plan.PostgrePlanNodeBase;
import com.seizia.chromite.model.exec.plan.DBCPlanCostNode;
import com.seizia.chromite.model.exec.plan.DBCPlanNode;
import com.seizia.chromite.ui.swing.internal.PlanMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the plans of the plan tab into what the graphical plan draws ({@link PlanDiagram}): operator names and icons,
 * objects, cost percent, estimated and actual rows, warnings.
 *
 * 설계: 공통 계획 모델({@link DBCPlanNode}, {@link DBCPlanCostNode})에는 이름·비용·행 수 하나씩만 있고 예상/실제 행, 연산자 자신의
 * 비용, 경고는 DB마다 다른 곳에 있다. 그래서 DB별로 읽고(이 번들은 이미 각 DB 모델 번들에 의존한다), 그 밖의 DB는 공통 모델로 읽는다.
 * <ul>
 *     <li>SQL Server({@link SQLServerPlanNode}): 문장 노드가 뿌리 상자(SELECT 등)다. 비용 %는 모델의 연산자 비용 %(SQL Server 도구의
 *     "Cost: N %"), 예상 행은 실행 1회 예상 × 예상 실행 수(모든 실행), 실제 행은 스레드 합계다. 경고·누락 인덱스는 모델이 showplan에서 읽은 것이다.</li>
 *     <li>PostgreSQL({@link PostgrePlanNodeBase}의 계획 속성): 비용 %는 자기 Total-Cost에서 자식들의 Total-Cost를 뺀 값을 뿌리의
 *     Total-Cost로 나눈 값, 예상·실제 행은 루프 1회 값 × Actual-Loops(모든 실행)다. 디스크 정렬(Sort-Space-Type=Disk), 해시 일괄
 *     처리 증가(Hash-Batches &gt; 1), 해시 집계 디스크 사용(Disk-Usage)을 넘침 경고로 보인다. PostgreSQL 계획에는 문장 노드가 없으므로
 *     문장 종류(SQL 첫 단어)로 뿌리 상자를 만든다.</li>
 *     <li>그 밖({@link OraclePlanNode}, {@link MySQLPlanNodeJSON}, 다른 DB): 이름·개체·조건과 {@link DBCPlanCostNode}의 비용·행 수다.
 *     비용은 하위 트리 비용으로 보고 PostgreSQL과 같이 자식 비용을 빼 연산자 비용 %를 만든다(Oracle COST가 이 정의다).
 *     실제 행이 없으므로 예상만 보인다. 다만 Oracle 실제 계획({@link OracleActualPlanNode})은 실제 행(A-Rows, 모든 시작의 합)과
 *     예상 행(E-Rows는 시작 1회 값이라 × Starts로 모든 실행), 실행 횟수, 시간을 더한다.</li>
 *     <li>예상이 크게 어긋남: 실제와 예상 중 큰 값이 작은 값(1 이상)의 {@value #MISESTIMATE_FACTOR}배 이상이고 차이가
 *     {@value #MISESTIMATE_ROWS}행 이상이면 표시한다(몇 행 차이로 표시가 넘치지 않게).</li>
 *     <li>아이콘은 연산 이름의 낱말로 고른다({@link #classify}). 같은 연산이라도 DB마다 이름이 달라 종류(DBCPlanNodeKind)만으로는
 *     Seek/Scan/Lookup을 나누지 못한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sql-editor-plus.md "실행 계획 세 보기")
 * - 2026-11-08 Oracle 실제 계획의 실제 행·실행 횟수·시간 (docs/chromite/swing-sql-editor-plus.md "실제 계획 토글을 Altibase·Oracle로")
 */
final class PlanDiagramBuilder {

    private static final double MISESTIMATE_FACTOR = 10;
    private static final double MISESTIMATE_ROWS = 100;
    // Bracketed SQL Server name parts: [db].[schema].[table].[index] [alias]
    private static final Pattern BRACKETED = Pattern.compile("\\[[^\\]]*\\]"); //$NON-NLS-1$
    // MySQL access types that read rows by an index key (the others read the whole table or index)
    private static final Set<String> MYSQL_KEY_ACCESS = Set.of(
        "system", "const", "eq_ref", "ref", "ref_or_null", "range", "index_merge", "unique_subquery", "index_subquery", "fulltext"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$

    private PlanDiagramBuilder() {
    }

    /**
     * One diagram per statement, numbered across the entries like the tree; the statement cost is relative to all of them.
     */
    @NotNull
    static List<PlanDiagram.Statement> build(@NotNull List<ExecutionPlanView.Entry> entries) {
        record Item(@NotNull ExecutionPlanView.Entry entry, @Nullable DBCPlanNode root, @Nullable Double cost, boolean single) {
        }
        List<Item> items = new ArrayList<>();
        double batchCost = 0;
        for (ExecutionPlanView.Entry entry : entries) {
            List<? extends DBCPlanNode> roots = entry.plan() == null ? List.of() : ExecutionPlanView.getRootNodes(entry.plan());
            if (roots.isEmpty()) {
                items.add(new Item(entry, null, null, true));
                continue;
            }
            for (DBCPlanNode root : roots) {
                Double cost = getCost(root);
                items.add(new Item(entry, root, cost, roots.size() == 1));
                batchCost += cost != null ? cost : 0;
            }
        }
        List<PlanDiagram.Statement> statements = new ArrayList<>();
        int number = 1;
        for (Item item : items) {
            String title = item.cost() != null && batchCost > 0
                ? NLS.bind(PlanMessages.plan_query_cost, number, PlanDiagramView.formatPercent(item.cost() * 100 / batchCost))
                : NLS.bind(PlanMessages.plan_query, number);
            number++;
            String text = item.root() instanceof SQLServerPlanNode node && !CommonUtils.isEmpty(node.getStatementText())
                ? node.getStatementText() : item.entry().query();
            // A batch planned at once (SQL Server) is one entry with a statement node per statement: its header selects that node
            Object header = item.single() || item.root() == null ? item.entry() : item.root();
            PlanDiagram.Statement statement = new PlanDiagram.Statement(title, firstLine(text), header);
            if (item.root() == null) {
                String error = item.entry().error();
                if (error != null) {
                    statement.notes.add(NLS.bind(PlanMessages.plan_no_plan, error));
                    statement.error = true;
                }
            } else {
                statement.root = createRoot(item.entry(), item.root(), statement);
            }
            statements.add(statement);
        }
        return statements;
    }

    /**
     * The statement text on one line. SQL Server starts the text of a statement after the first one of a batch with the
     * terminator of the one before (";"), which is dropped.
     */
    @NotNull
    private static String firstLine(@NotNull String text) {
        String line = text.strip().replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
        while (line.startsWith(";")) { //$NON-NLS-1$
            line = line.substring(1).strip();
        }
        return CommonUtils.truncateString(line, 300);
    }

    /**
     * The left box: the statement node of plans that have one (SQL Server, Oracle, MySQL), otherwise a box made for the statement.
     */
    @NotNull
    private static PlanDiagram.Op createRoot(@NotNull ExecutionPlanView.Entry entry, @NotNull DBCPlanNode root, @NotNull PlanDiagram.Statement statement) {
        if (root instanceof SQLServerPlanNode node && node.isStatement()) {
            String type = CommonUtils.notEmpty(node.getNodeType());
            PlanDiagram.Op op = new PlanDiagram.Op(type, null, statementIcon(type), node);
            addSqlServerWarnings(node, op);
            for (SQLServerPlanNode.MissingIndex index : node.getMissingIndexes()) {
                statement.notes.add(NLS.bind(PlanMessages.plan_missing_index,
                    NumberFormat.getNumberInstance().format(Math.round(index.impact() * 10) / 10.0), index.toCreateStatement()));
            }
            double statementCost = node.getSubtreeCost() != null ? node.getSubtreeCost() : 0;
            for (SQLServerPlanNode child : node.getNested()) {
                op.children.add(createOp(child, statementCost));
            }
            return op;
        }
        if (isStatementNode(getTitle(root))) {
            Double cost = getCost(root);
            PlanDiagram.Op op = createOp(root, cost != null ? cost : 0);
            // The statement box has no cost of its own (its cost is the whole statement, in the header)
            op.costPercent = null;
            op.properties.removeIf(property -> property[0].equals(PlanMessages.plan_tip_cost));
            return op;
        }
        String keyword = entry.query().strip().split("\\s+", 2)[0].toUpperCase(Locale.ENGLISH); //$NON-NLS-1$
        if (keyword.isEmpty() || keyword.equals("EXPLAIN")) { //$NON-NLS-1$
            keyword = "SELECT"; //$NON-NLS-1$
        }
        PlanDiagram.Op op = new PlanDiagram.Op(keyword, null, statementIcon(keyword), entry);
        Double cost = getCost(root);
        PlanDiagram.Op child = createOp(root, cost != null ? cost : 0);
        op.children.add(child);
        return op;
    }

    @NotNull
    private static PlanDiagram.Op createOp(@NotNull DBCPlanNode node, double statementCost) {
        PlanDiagram.Op op;
        if (node instanceof SQLServerPlanNode sqlServer) {
            op = createSqlServerOp(sqlServer);
        } else if (node instanceof PostgrePlanNodeBase<?> postgres) {
            op = createPostgresOp(postgres, statementCost);
        } else {
            op = createGenericOp(node, statementCost);
        }
        for (DBCPlanNode child : CommonUtils.safeCollection(node.getNested())) {
            op.children.add(createOp(child, statementCost));
        }
        if (op.actualRows != null && op.estimatedRows != null) {
            double high = Math.max(op.actualRows, op.estimatedRows);
            double low = Math.max(1, Math.min(op.actualRows, op.estimatedRows));
            if (high / low >= MISESTIMATE_FACTOR && high - low >= MISESTIMATE_ROWS) {
                op.misestimate = high / low;
            }
        }
        addRowProperties(op);
        return op;
    }

    //////////////////////////////////////////////////////////
    // SQL Server

    @NotNull
    private static PlanDiagram.Op createSqlServerOp(@NotNull SQLServerPlanNode node) {
        String physical = CommonUtils.notEmpty(node.getNodeType());
        String logical = node.getLogicalOperation();
        String detail = shortSqlServerObject(node.getObjectName());
        if (detail == null && logical != null && !logical.equals(physical)) {
            detail = "(" + logical + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        PlanDiagram.Op op = new PlanDiagram.Op(physical, detail, classify(physical + " " + CommonUtils.notEmpty(logical)), node); //$NON-NLS-1$
        op.costPercent = node.getNodePercent() != null ? node.getNodePercent().doubleValue() : null;
        if (node.getEstimatedRows() != null) {
            double executions = node.getEstimatedExecutions() != null ? node.getEstimatedExecutions() : 1;
            op.estimatedRows = node.getEstimatedRows() * executions;
        }
        op.actualRows = node.getActualRows() != null ? node.getActualRows().doubleValue() : null;
        op.properties.add(new String[]{PlanMessages.plan_tip_operation, physical});
        addProperty(op, PlanMessages.plan_tip_logical_operation, logical);
        addProperty(op, PlanMessages.plan_tip_object, node.getObjectName());
        if (op.costPercent != null) {
            op.properties.add(new String[]{PlanMessages.plan_tip_cost, PlanDiagramView.formatPercent(op.costPercent)});
        }
        addProperty(op, PlanMessages.plan_tip_subtree_cost, formatNumber(node.getSubtreeCost()));
        addProperty(op, PlanMessages.plan_tip_executions, node.getActualExecutions() == null ? null : formatNumber(node.getActualExecutions()));
        addProperty(op, PlanMessages.plan_tip_time, node.getActualElapsedMs() == null ? null : node.getActualElapsedMs() + " ms"); //$NON-NLS-1$
        addProperty(op, PlanMessages.plan_tip_condition, node.getNodeCondition());
        addSqlServerWarnings(node, op);
        return op;
    }

    private static void addSqlServerWarnings(@NotNull SQLServerPlanNode node, @NotNull PlanDiagram.Op op) {
        for (SQLServerPlanNode.Warning warning : node.getWarnings()) {
            String label = switch (warning.kind()) {
                case IMPLICIT_CONVERSION -> PlanMessages.plan_warning_implicit_conversion;
                case SPILL -> PlanMessages.plan_warning_spill;
                case NO_JOIN_PREDICATE -> PlanMessages.plan_warning_no_join_predicate;
                case NO_STATISTICS -> PlanMessages.plan_warning_no_statistics;
                case MEMORY_GRANT -> PlanMessages.plan_warning_memory_grant;
                case MISSING_INDEX -> PlanMessages.plan_warning_missing_index;
                case OTHER -> PlanMessages.plan_warning_other + " (" + warning.element() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            };
            op.warnings.add(warning.detail().isEmpty() ? label : label + ": " + warning.detail()); //$NON-NLS-1$
        }
    }

    /**
     * {@code [db].[schema].[table].[index] [alias]} without the database and schema, as the box has little room
     * (the tool tip and the property table show the full name).
     */
    @Nullable
    private static String shortSqlServerObject(@Nullable String objectName) {
        if (CommonUtils.isEmpty(objectName)) {
            return null;
        }
        String first = objectName.split(", ", 2)[0]; //$NON-NLS-1$
        int space = first.indexOf("] ["); //$NON-NLS-1$
        String name = space < 0 ? first : first.substring(0, space + 1);
        String alias = space < 0 ? "" : first.substring(space + 2); //$NON-NLS-1$
        List<String> parts = new ArrayList<>();
        Matcher matcher = BRACKETED.matcher(name);
        while (matcher.find()) {
            parts.add(matcher.group());
        }
        if (parts.size() < 3) {
            return first;
        }
        String shortName = String.join(".", parts.subList(Math.max(0, parts.size() - (parts.size() >= 4 ? 2 : 1)), parts.size())); //$NON-NLS-1$
        return alias.isEmpty() ? shortName : shortName + " " + alias; //$NON-NLS-1$
    }

    //////////////////////////////////////////////////////////
    // PostgreSQL

    @NotNull
    private static PlanDiagram.Op createPostgresOp(@NotNull PostgrePlanNodeBase<?> node, double statementCost) {
        String type = CommonUtils.notEmpty(node.getNodeType());
        String relation = attribute(node, "Relation-Name"); //$NON-NLS-1$
        String index = attribute(node, "Index-Name"); //$NON-NLS-1$
        String detail;
        if (index != null) {
            detail = relation != null ? relation + " (" + index + ")" : index; //$NON-NLS-1$ //$NON-NLS-2$
        } else if (node.getEntity() != null) {
            detail = node.getEntity();
        } else {
            String joinType = attribute(node, "Join-Type"); //$NON-NLS-1$
            detail = joinType != null ? "(" + joinType + ")" : null; //$NON-NLS-1$ //$NON-NLS-2$
        }
        String alias = attribute(node, "Alias"); //$NON-NLS-1$
        if (detail != null && alias != null && !alias.equals(relation)) {
            detail = detail + " " + alias; //$NON-NLS-1$
        }
        String iconText = type;
        if (type.equals("ModifyTable") && attribute(node, "Operation") != null) { //$NON-NLS-1$ //$NON-NLS-2$
            iconText = attribute(node, "Operation"); //$NON-NLS-1$
        } else if (type.contains("Index") && attribute(node, "Index-Cond") != null) { //$NON-NLS-1$ //$NON-NLS-2$
            // An index scan with a condition reads only the matching keys
            iconText = "seek"; //$NON-NLS-1$
        }
        PlanDiagram.Op op = new PlanDiagram.Op(type, detail, classify(iconText), node);

        Double total = toDouble(attribute(node, "Total-Cost")); //$NON-NLS-1$
        if (total != null && statementCost > 0) {
            double own = total;
            for (Object child : node.getNested()) {
                if (child instanceof PostgrePlanNodeBase<?> postgresChild) {
                    Double childTotal = toDouble(attribute(postgresChild, "Total-Cost")); //$NON-NLS-1$
                    own -= childTotal != null ? childTotal : 0;
                }
            }
            op.costPercent = Math.max(0, own) * 100 / statementCost;
        }
        Double planRows = toDouble(attribute(node, "Plan-Rows")); //$NON-NLS-1$
        Double actualRows = toDouble(attribute(node, "Actual-Rows")); //$NON-NLS-1$
        Double loops = toDouble(attribute(node, "Actual-Loops")); //$NON-NLS-1$
        double executions = loops != null ? loops : 1;
        op.estimatedRows = planRows == null ? null : planRows * executions;
        op.actualRows = actualRows == null ? null : actualRows * executions;

        op.properties.add(new String[]{PlanMessages.plan_tip_operation, type});
        addProperty(op, PlanMessages.plan_tip_object, detail);
        if (op.costPercent != null) {
            op.properties.add(new String[]{PlanMessages.plan_tip_cost, PlanDiagramView.formatPercent(op.costPercent)});
        }
        addProperty(op, PlanMessages.plan_tip_subtree_cost, node.getCost());
        addProperty(op, PlanMessages.plan_tip_executions, loops == null ? null : formatNumber(loops));
        String time = attribute(node, "Actual-Total-Time"); //$NON-NLS-1$
        addProperty(op, PlanMessages.plan_tip_time, time == null ? null : time + " ms"); //$NON-NLS-1$
        addProperty(op, PlanMessages.plan_tip_condition, node.getNodeCondition());

        // Spills: a sort on disk, a hash join that needed more batches, a hash aggregate that used the disk
        if ("Disk".equalsIgnoreCase(attribute(node, "Sort-Space-Type"))) { //$NON-NLS-1$ //$NON-NLS-2$
            op.warnings.add(PlanMessages.plan_warning_spill + ": " + CommonUtils.notEmpty(attribute(node, "Sort-Method")) //$NON-NLS-1$ //$NON-NLS-2$
                + ", " + CommonUtils.notEmpty(attribute(node, "Sort-Space-Used")) + " kB"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        Double batches = toDouble(attribute(node, "Hash-Batches")); //$NON-NLS-1$
        if (batches != null && batches > 1) {
            op.warnings.add(PlanMessages.plan_warning_spill + ": Hash-Batches " + formatNumber(batches)); //$NON-NLS-1$
        }
        Double diskUsage = toDouble(attribute(node, "Disk-Usage")); //$NON-NLS-1$
        if (diskUsage != null && diskUsage > 0) {
            op.warnings.add(PlanMessages.plan_warning_spill + ": Disk-Usage " + formatNumber(diskUsage) + " kB"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return op;
    }

    /**
     * A plan property of a PostgreSQL node (the EXPLAIN XML element name), or null.
     */
    @Nullable
    private static String attribute(@NotNull PostgrePlanNodeBase<?> node, @NotNull String name) {
        return CommonUtils.nullIfEmpty(CommonUtils.toString(node.getPropertyValue(null, name), null));
    }

    //////////////////////////////////////////////////////////
    // Other databases

    @NotNull
    private static PlanDiagram.Op createGenericOp(@NotNull DBCPlanNode node, double statementCost) {
        String title = getTitle(node);
        String detail = node.getNodeName();
        String iconText = title;
        if (node instanceof OraclePlanNode oracle && !CommonUtils.isEmpty(oracle.getObjectName())) {
            detail = CommonUtils.isEmpty(oracle.getObjectOwner()) ? oracle.getObjectName() : oracle.getObjectOwner() + "." + oracle.getObjectName(); //$NON-NLS-1$
        } else if (node instanceof MySQLPlanNodeJSON mysql && mysql.getProperty("access_type") != null) { //$NON-NLS-1$
            iconText = MYSQL_KEY_ACCESS.contains(String.valueOf(mysql.getProperty("access_type"))) ? "seek" : "scan"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        PlanDiagram.Op op = new PlanDiagram.Op(title, detail, classify(iconText), node);
        if (node instanceof DBCPlanCostNode costNode) {
            if (costNode.getNodePercent() != null) {
                op.costPercent = costNode.getNodePercent().doubleValue();
            } else if (costNode.getNodeCost() != null && statementCost > 0) {
                double own = costNode.getNodeCost().doubleValue();
                for (DBCPlanNode child : CommonUtils.safeCollection(node.getNested())) {
                    if (child instanceof DBCPlanCostNode costChild && costChild.getNodeCost() != null) {
                        own -= costChild.getNodeCost().doubleValue();
                    }
                }
                op.costPercent = Math.max(0, own) * 100 / statementCost;
            }
            op.estimatedRows = costNode.getNodeRowCount() == null ? null : costNode.getNodeRowCount().doubleValue();
        }
        OracleActualPlanNode oracleActual = node instanceof OracleActualPlanNode actual ? actual : null;
        if (oracleActual != null) {
            // E-Rows is for one start, A-Rows for all of them: both as all executions, like the other databases
            Long starts = oracleActual.getStarts();
            if (op.estimatedRows != null && starts != null && starts > 1) {
                op.estimatedRows = op.estimatedRows * starts;
            }
            op.actualRows = oracleActual.getActualRows() == null ? null : oracleActual.getActualRows().doubleValue();
        }
        op.properties.add(new String[]{PlanMessages.plan_tip_operation, title});
        addProperty(op, PlanMessages.plan_tip_object, detail);
        if (op.costPercent != null) {
            op.properties.add(new String[]{PlanMessages.plan_tip_cost, PlanDiagramView.formatPercent(op.costPercent)});
        }
        if (node instanceof DBCPlanCostNode costNode && costNode.getNodeCost() != null) {
            addProperty(op, PlanMessages.plan_tip_subtree_cost, formatNumber(costNode.getNodeCost().doubleValue()));
        }
        if (oracleActual != null) {
            addProperty(op, PlanMessages.plan_tip_executions, formatNumber(oracleActual.getStarts()));
            addProperty(op, PlanMessages.plan_tip_time, oracleActual.getActualTime() == null ? null : formatNumber(oracleActual.getActualTime()) + " ms"); //$NON-NLS-1$
        }
        addProperty(op, PlanMessages.plan_tip_condition, node.getNodeCondition());
        return op;
    }

    /**
     * Operation name: an Oracle node type is the operation without its options ({@code TABLE ACCESS}), its
     * {@code getOperation} adds them ({@code TABLE ACCESS (FULL)}).
     */
    @NotNull
    private static String getTitle(@NotNull DBCPlanNode node) {
        String type = node instanceof OraclePlanNode oracle ? oracle.getOperation() : node.getNodeType();
        return CommonUtils.isEmpty(type) ? CommonUtils.notEmpty(node.getNodeName()) : type;
    }

    /**
     * The statement cost compared across the statements of the tab (header "relative to the batch").
     */
    @Nullable
    private static Double getCost(@NotNull DBCPlanNode root) {
        if (root instanceof SQLServerPlanNode node) {
            return node.getSubtreeCost();
        }
        if (root instanceof PostgrePlanNodeBase<?> postgres) {
            return toDouble(attribute(postgres, "Total-Cost")); //$NON-NLS-1$
        }
        if (root instanceof DBCPlanCostNode costNode && costNode.getNodeCost() != null) {
            return costNode.getNodeCost().doubleValue();
        }
        return null;
    }

    //////////////////////////////////////////////////////////
    // Shared

    private static void addRowProperties(@NotNull PlanDiagram.Op op) {
        int index = Math.min(op.properties.size(), 4);
        NumberFormat format = NumberFormat.getIntegerInstance();
        if (op.actualRows != null) {
            String actual = format.format(op.actualRows);
            if (op.misestimate != null) {
                actual += " - " + NLS.bind(PlanMessages.plan_misestimate, format.format(op.misestimate)); //$NON-NLS-1$
            }
            op.properties.add(index, new String[]{PlanMessages.plan_tip_actual_rows, actual});
        }
        if (op.estimatedRows != null) {
            op.properties.add(index, new String[]{PlanMessages.plan_tip_estimated_rows, format.format(op.estimatedRows)});
        }
        if (!op.warnings.isEmpty()) {
            op.properties.add(new String[]{PlanMessages.plan_tip_warnings, String.valueOf(op.warnings.size())});
        }
    }

    private static void addProperty(@NotNull PlanDiagram.Op op, @NotNull String name, @Nullable String value) {
        if (!CommonUtils.isEmpty(value)) {
            op.properties.add(new String[]{name, value});
        }
    }

    @Nullable
    private static String formatNumber(@Nullable Number value) {
        if (value == null) {
            return null;
        }
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setMaximumFractionDigits(4);
        return format.format(value);
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
    private static String statementIcon(@NotNull String statementType) {
        String type = statementType.toUpperCase(Locale.ENGLISH);
        if (type.startsWith("INSERT")) { //$NON-NLS-1$
            return "plan-insert"; //$NON-NLS-1$
        } else if (type.startsWith("UPDATE") || type.startsWith("MERGE")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "plan-update"; //$NON-NLS-1$
        } else if (type.startsWith("DELETE")) { //$NON-NLS-1$
            return "plan-delete"; //$NON-NLS-1$
        }
        return "plan-select"; //$NON-NLS-1$
    }

    /**
     * Whether a plan node stands for the whole statement: Oracle {@code SELECT STATEMENT}, MySQL {@code query_block}
     * (the root of MariaDB/MySQL JSON plans is named {@code select}).
     */
    private static boolean isStatementNode(@NotNull String operation) {
        String op = operation.toLowerCase(Locale.ENGLISH);
        return op.contains("statement") || op.startsWith("query_block") || op.equals("select"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The operator icon for an operation name (with the logical operation or options), by the words the databases use:
     * SQL Server ({@code Clustered Index Seek}, {@code Hash Match (Aggregate)}), PostgreSQL ({@code Seq Scan},
     * {@code HashAggregate}), Oracle ({@code TABLE ACCESS BY INDEX ROWID}, {@code SORT GROUP BY}), MySQL ({@code nested_loop}).
     * The order matters: an aggregate done by hashing is an aggregate, a parallel scan is a scan.
     */
    @NotNull
    static String classify(@NotNull String operation) {
        String op = operation.toLowerCase(Locale.ENGLISH);
        if (isStatementNode(operation)) {
            return "plan-select"; //$NON-NLS-1$
        } else if (op.contains("insert")) { //$NON-NLS-1$
            return "plan-insert"; //$NON-NLS-1$
        } else if (op.contains("update")) { //$NON-NLS-1$
            return "plan-update"; //$NON-NLS-1$
        } else if (op.contains("delete")) { //$NON-NLS-1$
            return "plan-delete"; //$NON-NLS-1$
        } else if (op.contains("lookup") || op.contains("rowid")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "plan-lookup"; //$NON-NLS-1$
        } else if (op.contains("nested loop") || op.contains("nested_loop")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "plan-join-nested"; //$NON-NLS-1$
        } else if (op.contains("merge") && op.contains("join")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "plan-join-merge"; //$NON-NLS-1$
        } else if (op.contains("aggregate") || op.contains("group")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "plan-aggregate"; //$NON-NLS-1$
        } else if (op.contains("hash")) { //$NON-NLS-1$
            return "plan-join-hash"; //$NON-NLS-1$
        } else if (op.contains("sort") || op.contains("ordering")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "plan-sort"; //$NON-NLS-1$
        } else if (op.contains("seek") || op.contains("range scan") || op.contains("unique scan")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "plan-seek"; //$NON-NLS-1$
        } else if (op.contains("scan") || op.contains("table access") || op.startsWith("table")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "plan-scan"; //$NON-NLS-1$
        } else if (op.contains("filter")) { //$NON-NLS-1$
            return "plan-filter"; //$NON-NLS-1$
        } else if (op.contains("parallel") || op.contains("gather") || op.contains("exchange") || op.startsWith("px ")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return "plan-parallelism"; //$NON-NLS-1$
        } else if (op.contains("top") || op.contains("limit")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "plan-top"; //$NON-NLS-1$
        } else if (op.contains("compute") || op.contains("result") || op.contains("project")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "plan-compute"; //$NON-NLS-1$
        }
        return "plan-operator"; //$NON-NLS-1$
    }
}
