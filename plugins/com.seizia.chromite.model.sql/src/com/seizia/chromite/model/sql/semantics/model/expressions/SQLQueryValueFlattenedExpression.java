/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.model.sql.semantics.model.expressions;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.sql.semantics.SQLQueryRecognitionContext;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryExprType;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryRowsDataContext;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryRowsSourceContext;
import com.seizia.chromite.model.sql.semantics.model.SQLQueryNodeModelVisitor;
import com.seizia.chromite.model.stm.STMTreeNode;

import java.util.List;

/**
 * Describes value expressions tree hierarchy
 */
public class SQLQueryValueFlattenedExpression extends SQLQueryValueExpression {
    @NotNull
    private final List<SQLQueryValueExpression> operands;

    public SQLQueryValueFlattenedExpression(
        @NotNull STMTreeNode syntaxNode,
        @NotNull List<SQLQueryValueExpression> operands
    ) {
        super(syntaxNode, operands.toArray(SQLQueryValueExpression[]::new));
        this.operands = operands;
    }

    @NotNull
    public List<SQLQueryValueExpression> getOperands() {
        return operands;
    }

    @Override
    protected void resolveRowSourcesImpl(@NotNull SQLQueryRowsSourceContext context, @NotNull SQLQueryRecognitionContext statistics) {
        for (SQLQueryValueExpression expr : this.operands) {
            expr.resolveRowSources(context, statistics);
        }
    }

    @NotNull
    @Override
    protected SQLQueryExprType resolveValueTypeImpl(
        @NotNull SQLQueryRowsDataContext context,
        @NotNull SQLQueryRecognitionContext statistics
    ) {
        return SQLQueryExprType.UNKNOWN;
    }

    @Override
    protected <R, T> R applyImpl(@NotNull SQLQueryNodeModelVisitor<T, R> visitor, @NotNull T arg) {
        return visitor.visitValueFlatExpr(this, arg);
    }
}