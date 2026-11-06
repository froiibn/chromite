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
package com.seizia.chromite.model.sql.semantics.model.select;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.sql.semantics.SQLQueryLexicalScope;
import com.seizia.chromite.model.sql.semantics.SQLQueryRecognitionContext;
import com.seizia.chromite.model.sql.semantics.SQLQuerySymbolClass;
import com.seizia.chromite.model.sql.semantics.SQLQuerySymbolEntry;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryRowsDataContext;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryRowsSourceContext;
import com.seizia.chromite.model.sql.semantics.model.SQLQueryNodeModelVisitor;
import com.seizia.chromite.model.stm.STMTreeNode;

import java.util.List;

/**
 * Describes subquery of a common table expression
 */
public class SQLQueryRowsCteSubqueryModel extends SQLQueryRowsSourceModel {
    @Nullable
    public final SQLQuerySymbolEntry subqueryName;
    @NotNull
    public final List<SQLQuerySymbolEntry> columNames;
    @Nullable
    public final SQLQueryRowsSourceModel source;
    @Nullable
    private final SQLQueryLexicalScope sourceTailScope;

    public SQLQueryRowsCteSubqueryModel(
        @NotNull STMTreeNode syntaxNode,
        @Nullable SQLQuerySymbolEntry subqueryName,
        @NotNull List<SQLQuerySymbolEntry> columNames,
        @Nullable SQLQueryRowsSourceModel source,
        @Nullable SQLQueryLexicalScope sourceTailScope
    ) {
        super(syntaxNode, source);
        this.subqueryName = subqueryName;
        this.columNames = columNames;
        this.source = source;
        this.sourceTailScope = sourceTailScope;
        
        if (sourceTailScope != null) {
            this.registerLexicalScope(sourceTailScope);
        }
    }

    @Override
    protected SQLQueryRowsSourceContext resolveRowSourcesImpl(
        @NotNull SQLQueryRowsSourceContext context,
        @NotNull SQLQueryRecognitionContext statistics
    ) {
        if (this.subqueryName != null) {
            if (this.subqueryName.isNotClassified()) {
                this.subqueryName.getSymbol().setDefinition(this.subqueryName);
                if (this.subqueryName.isNotClassified()) {
                    this.subqueryName.getSymbol().setSymbolClass(SQLQuerySymbolClass.TABLE_ALIAS);
                }
            }
        }
        if (this.source != null) {
            this.source.resolveRowSources(context, statistics);
        }
        return context.reset();
    }

    @Override
    protected SQLQueryRowsDataContext resolveRowDataImpl(
        @NotNull SQLQueryRowsDataContext context,
        @NotNull SQLQueryRecognitionContext statistics
    ) {
        if (this.source != null) {
            this.setTailOrigin(this.source.getTailOrigin());
            if (this.sourceTailScope != null && this.source.getTailOrigin() != null) {
                this.sourceTailScope.setSymbolsOrigin(this.source.getTailOrigin());
            }

            if (!this.columNames.isEmpty()) {
                return SQLQueryRowsCorrelatedSourceModel.prepareColumnsCorrelation(this.source.getRowsDataContext(), this.columNames, this);
            } else {
                return this.source.getRowsDataContext();
            }
        } else {
            return context.getRowsSources().makeEmptyTuple();
        }
    }

    @Nullable
    @Override
    protected <R, T> R applyImpl(@NotNull SQLQueryNodeModelVisitor<T, R> visitor, @NotNull T node) {
        return visitor.visitRowsCteSubquery(this, node);
    }
}