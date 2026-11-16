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
import com.seizia.chromite.model.exec.plan.DBCPlanCostNode;
import com.seizia.chromite.model.exec.plan.DBCPlanNodeKind;
import com.seizia.chromite.model.impl.plan.AbstractExecutionPlanNode;
import com.seizia.chromite.model.meta.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * One operator ({@code RelOp}) or statement ({@code StmtSimple}) of a SQL Server showplan.
 *
 * 설계: 열(viewable 속성)은 SQL Server 실행 계획 도구 설명의 주요 값(물리·논리 연산, 개체, 예상 행·비용, 비용 %, 실제 행·시간)이고,
 * 나머지(CPU·I/O 비용, 술어, 출력 열 등)는 상세 속성이다. 비용 %는 연산자 자신의 비용(하위 트리 비용 - 자식들의 하위 트리 비용)을
 * 문장 전체 비용으로 나눈 값이다(SQL Server 계획 도구의 "Cost: N %"와 같은 정의). 문장 노드의 %는 일괄 처리 전체 대비다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-06 속성 이름을 번들 지역화(OSGI-INF/l10n/bundle*.properties의 meta 키)로 옮김(OS 언어를 따름)
 * - 2026-11-07 경고({@link Warning}), 누락 인덱스({@link MissingIndex}), 예상 실행 수: 그래픽 계획의 배지·머리말과 예상/실제 행 비교
 *   (docs/chromite/swing-sql-editor-plus.md "실행 계획 세 보기")
 */
public class SQLServerPlanNode extends AbstractExecutionPlanNode implements DBCPlanCostNode {

    /**
     * What a showplan warning is about.
     */
    public enum WarningKind {
        IMPLICIT_CONVERSION,
        SPILL,
        NO_JOIN_PREDICATE,
        NO_STATISTICS,
        MEMORY_GRANT,
        MISSING_INDEX,
        OTHER
    }

    /**
     * A warning of the plan.
     *
     * @param element the showplan element or attribute that reported it
     * @param detail  the server's own details (expression, spill level, columns, ...), may be empty
     */
    public record Warning(@NotNull WarningKind kind, @NotNull String element, @NotNull String detail) {
    }

    /**
     * An index the optimizer would have used (showplan {@code MissingIndex}).
     *
     * @param impact estimated improvement of the statement cost, in percent
     * @param table  quoted schema and table name
     */
    public record MissingIndex(
        double impact,
        @NotNull String table,
        @NotNull List<String> equality,
        @NotNull List<String> inequality,
        @NotNull List<String> include
    ) {
        /**
         * The suggested index as a CREATE statement (key columns: equality columns, then inequality columns).
         */
        @NotNull
        public String toCreateStatement() {
            List<String> keys = new ArrayList<>(equality);
            keys.addAll(inequality);
            String statement = "CREATE NONCLUSTERED INDEX [<Name of Missing Index>] ON " + table + " (" + String.join(", ", keys) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return include.isEmpty() ? statement : statement + " INCLUDE (" + String.join(", ", include) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    final boolean statement;
    final String physicalOp;
    @Nullable
    final String logicalOp;
    @Nullable
    String objectName;
    @Nullable
    String predicate;
    @Nullable
    String outputList;
    @Nullable
    String statementText;
    @Nullable
    Double estimateRows;
    @Nullable
    Double estimateExecutions;
    @Nullable
    Double subtreeCost;
    @Nullable
    Double estimateCpu;
    @Nullable
    Double estimateIo;
    @Nullable
    Integer nodeId;
    @Nullable
    Long actualRows;
    @Nullable
    Long actualExecutions;
    @Nullable
    Long actualElapsedMs;
    @Nullable
    Double percent;
    @Nullable
    private final SQLServerPlanNode parent;
    final List<SQLServerPlanNode> nested = new ArrayList<>();
    final List<Warning> warnings = new ArrayList<>();
    final List<MissingIndex> missingIndexes = new ArrayList<>();

    SQLServerPlanNode(@Nullable SQLServerPlanNode parent, boolean statement, @NotNull String physicalOp, @Nullable String logicalOp) {
        this.parent = parent;
        this.statement = statement;
        this.physicalOp = physicalOp;
        this.logicalOp = logicalOp;
    }

    @NotNull
    @Override
    public DBCPlanNodeKind getNodeKind() {
        if (statement) {
            return DBCPlanNodeKind.SELECT;
        }
        String op = physicalOp.toLowerCase(Locale.ENGLISH);
        if (op.equals("table scan") || op.equals("clustered index scan")) { //$NON-NLS-1$ //$NON-NLS-2$
            // A full scan of the table (the clustered index is the table)
            return DBCPlanNodeKind.TABLE_SCAN;
        } else if (op.endsWith("index scan")) { //$NON-NLS-1$
            return DBCPlanNodeKind.INDEX_SCAN;
        } else if (op.contains("join") || op.equals("nested loops") || (op.equals("hash match") && logicalOp != null //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            && logicalOp.toLowerCase(Locale.ENGLISH).contains("join"))) { //$NON-NLS-1$
            return DBCPlanNodeKind.JOIN;
        } else if (op.contains("sort")) { //$NON-NLS-1$
            return DBCPlanNodeKind.SORT;
        } else if (op.contains("aggregate") || op.equals("hash match")) { //$NON-NLS-1$ //$NON-NLS-2$
            return DBCPlanNodeKind.AGGREGATE;
        } else if (op.equals("filter")) { //$NON-NLS-1$
            return DBCPlanNodeKind.FILTER;
        } else if (op.contains("insert") || op.contains("update") || op.contains("delete")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return DBCPlanNodeKind.MODIFY;
        }
        return DBCPlanNodeKind.DEFAULT;
    }

    @Nullable
    @Override
    public String getNodeName() {
        return objectName;
    }

    @NotNull
    @Override
    @Property(order = 1, viewable = true)
    public String getNodeType() {
        return physicalOp;
    }

    @Nullable
    @Property(order = 2, viewable = true)
    public String getLogicalOperation() {
        return logicalOp;
    }

    @Nullable
    @Property(order = 3, viewable = true)
    public String getObjectName() {
        return objectName;
    }

    @Nullable
    @Property(order = 4, viewable = true)
    public Double getEstimatedRows() {
        return estimateRows;
    }

    @Nullable
    @Property(order = 5, viewable = true)
    public Double getSubtreeCost() {
        return subtreeCost;
    }

    @Nullable
    @Property(order = 6, viewable = true)
    public Double getCostPercent() {
        return percent == null ? null : Math.round(percent * 10) / 10.0;
    }

    @Nullable
    @Property(order = 7, viewable = true)
    public Long getActualRows() {
        return actualRows;
    }

    @Nullable
    @Property(order = 8, viewable = true)
    public Long getActualElapsedMs() {
        return actualElapsedMs;
    }

    @Nullable
    @Property(order = 9)
    public Long getActualExecutions() {
        return actualExecutions;
    }

    @Nullable
    @Property(order = 10)
    public Double getEstimatedCpu() {
        return estimateCpu;
    }

    @Nullable
    @Property(order = 11)
    public Double getEstimatedIo() {
        return estimateIo;
    }

    @Nullable
    @Property(order = 12)
    public Integer getNodeId() {
        return nodeId;
    }

    @Nullable
    @Override
    @Property(order = 13)
    public String getNodeCondition() {
        return predicate;
    }

    @Nullable
    @Property(order = 14)
    public String getOutputList() {
        return outputList;
    }

    @Nullable
    @Property(order = 15)
    public String getStatementText() {
        return statementText;
    }

    @Nullable
    @Property(order = 16)
    public Double getEstimatedExecutions() {
        return estimateExecutions;
    }

    /**
     * The warnings as the server names them (element and details), for the property table.
     */
    @Nullable
    @Property(order = 17)
    public String getWarningText() {
        if (warnings.isEmpty()) {
            return null;
        }
        StringJoiner text = new StringJoiner("; "); //$NON-NLS-1$
        for (Warning warning : warnings) {
            text.add(warning.detail().isEmpty() ? warning.element() : warning.element() + " " + warning.detail()); //$NON-NLS-1$
        }
        return text.toString();
    }

    @Nullable
    @Property(order = 18)
    public String getMissingIndexStatements() {
        if (missingIndexes.isEmpty()) {
            return null;
        }
        StringJoiner text = new StringJoiner(";" + System.lineSeparator()); //$NON-NLS-1$
        for (MissingIndex index : missingIndexes) {
            text.add(index.toCreateStatement());
        }
        return text.toString();
    }

    @NotNull
    public List<Warning> getWarnings() {
        return warnings;
    }

    @NotNull
    public List<MissingIndex> getMissingIndexes() {
        return missingIndexes;
    }

    /**
     * Whether this is a statement node ({@code StmtSimple}) rather than an operator.
     */
    public boolean isStatement() {
        return statement;
    }

    @Nullable
    @Override
    public String getNodeDescription() {
        return statement ? statementText : predicate;
    }

    @Nullable
    @Override
    public SQLServerPlanNode getParent() {
        return parent;
    }

    @NotNull
    @Override
    public List<SQLServerPlanNode> getNested() {
        return nested;
    }

    @Override
    public Number getNodeCost() {
        return subtreeCost;
    }

    @Override
    public Number getNodePercent() {
        return percent;
    }

    @Override
    public Number getNodeDuration() {
        return actualElapsedMs;
    }

    @Override
    public Number getNodeRowCount() {
        return actualRows != null ? actualRows : estimateRows;
    }

    @Override
    public String toString() {
        return objectName == null ? physicalOp : physicalOp + " " + objectName;
    }
}
