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
package com.seizia.chromite.model.sql.semantics.model.programmaticals;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.sql.semantics.SQLQueryComplexName;
import com.seizia.chromite.model.sql.semantics.model.SQLQueryNodeModelVisitor;
import com.seizia.chromite.model.sql.semantics.model.ddl.SQLQueryObjectDataModel;
import com.seizia.chromite.model.sql.semantics.model.select.SQLQueryRowsTableDataModel;
import com.seizia.chromite.model.sql.semantics.model.statements.SQLQueryStatementModel;
import com.seizia.chromite.model.stm.STMTreeNode;

import java.util.List;

/** Alters an existing trigger, optionally replacing its procedural body. */
public final class SQLQueryTriggerAlterModel extends SQLQueryTriggerStatementModel {
    SQLQueryTriggerAlterModel(
        @NotNull STMTreeNode node, @Nullable SQLQueryComplexName name,
        @Nullable SQLQueryObjectDataModel existingObject, @Nullable SQLQueryRowsTableDataModel table,
        @NotNull List<TransitionAlias> aliases, @Nullable SQLQueryStatementModel body
    ) {
        super(node, name, existingObject, table, aliases, body);
    }

    @Nullable
    @Override
    protected <R, T> R applyImpl(@NotNull SQLQueryNodeModelVisitor<T, R> visitor, @Nullable T arg) {
        return visitor.visitTriggerAlter(this, arg);
    }
}
