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
package com.seizia.chromite.model.sql.semantics.model.statements;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.sql.semantics.SQLQueryModelRecognizer;
import com.seizia.chromite.model.sql.semantics.SQLQueryRecognitionContext;
import com.seizia.chromite.model.sql.semantics.SQLQuerySymbolEntry;
import com.seizia.chromite.model.sql.SQLDialectFeatures;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryLocalVariableInfo;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryRowsDataContext;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryRowsSourceContext;
import com.seizia.chromite.model.sql.semantics.model.SQLQueryNodeModelVisitor;
import com.seizia.chromite.model.sql.semantics.model.expressions.SQLQueryValueExpression;
import com.seizia.chromite.model.stm.STMKnownRuleNames;
import com.seizia.chromite.model.stm.STMTreeNode;

import java.util.ArrayList;
import java.util.List;

/** Assignments parsed by the existing variableAssignmentStatement rule. */
public final class SQLQueryAssignmentStatement extends SQLQueryStatementModel {
    public record Assignment(@NotNull SQLQuerySymbolEntry target, @Nullable SQLQueryValueExpression value) { }

    @NotNull
    private final List<Assignment> assignments;

    private SQLQueryAssignmentStatement(@NotNull STMTreeNode node, @NotNull List<Assignment> assignments) {
        super(node, assignments.stream().map(Assignment::value).toArray(SQLQueryValueExpression[]::new));
        this.assignments = List.copyOf(assignments);
    }

    @NotNull
    public static SQLQueryAssignmentStatement recognize(@NotNull SQLQueryModelRecognizer recognizer, @NotNull STMTreeNode node) {
        List<Assignment> assignments = new ArrayList<>();
        for (STMTreeNode assignment : node.findChildrenOfName(STMKnownRuleNames.variableAssignment)) {
            STMTreeNode variable = assignment.findFirstChildOfName(STMKnownRuleNames.variableName);
            STMTreeNode expression = variable == null ? null : variable.findFirstChildOfName(STMKnownRuleNames.variableExpression);
            STMTreeNode identifier = variable == null ? null : variable.findFirstChildOfName(STMKnownRuleNames.identifier);
            SQLQuerySymbolEntry name = expression != null ? recognizer.collectTargetVariableName(expression)
                : identifier != null ? recognizer.collectIdentifier(identifier, null) : null;
            if (name != null) {
                STMTreeNode value = assignment.findFirstChildOfName(STMKnownRuleNames.valueExpression);
                assignments.add(new Assignment(name, value == null ? null : recognizer.collectValueExpression(value, null)));
            }
        }
        return new SQLQueryAssignmentStatement(node, assignments);
    }

    @NotNull
    public List<Assignment> getAssignments() {
        return this.assignments;
    }

    @NotNull
    @Override
    protected SQLQueryRowsSourceContext resolveStatementImpl(
        @NotNull SQLQueryRowsSourceContext context, @NotNull SQLQueryRecognitionContext statistics
    ) {
        for (Assignment assignment : this.assignments) {
            SQLQueryLocalVariableInfo binding = context.getLocalVariables().findLocalVariable(
                assignment.target(), SQLDialectFeatures.LocalVariableCapability.VALUE);
            if (binding != null && assignment.target().isNotClassified()) {
                assignment.target().setDefinition(binding.declaration());
            }
            if (assignment.value() != null) {
                assignment.value().resolveRowSources(context, statistics);
            }
        }
        return context;
    }

    @Override
    public boolean tryResolveValueRelations(@NotNull SQLQueryRowsDataContext context, @NotNull SQLQueryRecognitionContext statistics) {
        for (Assignment assignment : this.assignments) {
            if (statistics.getMonitor().isCanceled() || assignment.value() != null &&
                !assignment.value().tryResolveValueRelations(this.valueContext(context), statistics)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected <R, T> R applyImpl(@NotNull SQLQueryNodeModelVisitor<T, R> visitor, T arg) {
        return visitor.visitAssignmentStatement(this, arg);
    }
}
