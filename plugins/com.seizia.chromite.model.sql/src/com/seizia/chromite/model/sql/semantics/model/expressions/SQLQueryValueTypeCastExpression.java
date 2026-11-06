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
package com.seizia.chromite.model.sql.semantics.model.expressions;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.sql.semantics.SQLQueryModelRecognizer.TypeReference;
import com.seizia.chromite.model.sql.semantics.SQLQueryRecognitionContext;
import com.seizia.chromite.model.sql.semantics.SQLQuerySemanticUtils;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryExprType;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryRowsDataContext;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryRowsSourceContext;
import com.seizia.chromite.model.sql.semantics.model.SQLQueryNodeModelVisitor;
import com.seizia.chromite.model.stm.STMTreeNode;

/**
 * Describes type cast expression
 */
public class SQLQueryValueTypeCastExpression extends SQLQueryValueExpression {

    @NotNull
    private final SQLQueryValueExpression value;
    @NotNull
    private final TypeReference typeReference;

    public SQLQueryValueTypeCastExpression(
        @NotNull STMTreeNode syntaxNode,
        @NotNull SQLQueryValueExpression value,
        @NotNull TypeReference typeReference
    ) {
        super(syntaxNode, value);
        this.value = value;
        this.typeReference = typeReference;
    }

    @NotNull
    public String getTypeRefString() {
        return this.typeReference.spelling();
    }

    @NotNull
    public SQLQueryValueExpression getValueExpr() {
        return this.value;
    }

    @Override
    protected void resolveRowSourcesImpl(@NotNull SQLQueryRowsSourceContext context, @NotNull SQLQueryRecognitionContext statistics) {
        this.value.resolveRowSources(context, statistics);
    }

    @NotNull
    @Override
    protected SQLQueryExprType resolveValueTypeImpl(
        @NotNull SQLQueryRowsDataContext context,
        @NotNull SQLQueryRecognitionContext statistics
    ) {
        return SQLQuerySemanticUtils.resolveTypeReference(context.getRowsSources(), statistics, this.typeReference);
    }
    
    @Override
    protected <R, T> R applyImpl(@NotNull SQLQueryNodeModelVisitor<T, R> visitor, @NotNull T arg) {
        return visitor.visitValueTypeCastExpr(this, arg);
    }

    @Override
    public String toString() {
        return "TypeCast[" + this.value.toString() + ", " + this.typeReference.spelling() + "]";
    }
}
