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
package com.seizia.chromite.model.sql.semantics.model.ddl;


import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.sql.semantics.*;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryRowsDataContext;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryRowsSourceContext;
import com.seizia.chromite.model.sql.semantics.model.SQLQueryNodeModelVisitor;
import com.seizia.chromite.model.sql.semantics.model.select.SQLQueryRowsSourceModel;
import com.seizia.chromite.model.stm.STMTreeNode;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectType;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.model.struct.rdb.DBSView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * Describes object reference
 * @apiNote
 * TODO remove objectType and treat this as non-table rows source like table-producing procedures, no matter builtin or not
 *      (see something like {@code SELECT * FROM proc()}  )
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: 끝나지 않은 이름({@code EXEC dbo.})을 객체 찾기에 넘기지 않음. 빈 이름 조각(null)이 방언의 따옴표 제거까지
 *   가서 NullPointerException이 났다. 테이블·함수 참조 모델처럼 앞쪽 조각만 부분 해석한다. 이름 원천의 거르기 방식을
 *   생성자로 받는다(호출문은 {@link SQLQuerySymbolOrigin.DbObjectFilterMode#CALL}) (docs/chromite/swing-sql-completion.md)
 */
public class SQLQueryObjectDataModel extends SQLQueryRowsSourceModel implements SQLQuerySymbolDefinition {

    private static final Log log = Log.getLog(SQLQueryObjectDataModel.class);
    @NotNull
    private final SQLQueryComplexName name;
    @NotNull
    private DBSObjectType objectType;
    @NotNull
    private Set<DBSObjectType> objectContainerTypes;
    @NotNull
    private final SQLQuerySymbolOrigin.DbObjectFilterMode filterMode;
    @Nullable
    private DBSObject object = null;

    private SQLQuerySymbolOrigin objectNameOrigin = null;

    public SQLQueryObjectDataModel(
        @NotNull STMTreeNode syntaxNode,
        @NotNull SQLQueryComplexName name,
        @NotNull DBSObjectType objectTypes,
        @NotNull Set<DBSObjectType> objectContainerTypes
    ) {
        this(syntaxNode, name, objectTypes, objectContainerTypes, SQLQuerySymbolOrigin.DbObjectFilterMode.OBJECT);
    }

    public SQLQueryObjectDataModel(
        @NotNull STMTreeNode syntaxNode,
        @NotNull SQLQueryComplexName name,
        @NotNull DBSObjectType objectTypes,
        @NotNull Set<DBSObjectType> objectContainerTypes,
        @NotNull SQLQuerySymbolOrigin.DbObjectFilterMode filterMode
    ) {
        super(syntaxNode);
        this.name = name;
        this.objectType = objectTypes;
        this.objectContainerTypes = objectContainerTypes;
        this.filterMode = filterMode;
    }

    @NotNull
    public DBSObjectType getObjectType() {
        return objectType;
    }

    @NotNull
    public SQLQueryComplexName getName() {
        return this.name;
    }

    @Nullable
    public DBSObject getObject() {
        return object;
    }

    @Nullable
    public SQLQuerySymbolOrigin getObjectNameOrigin() {
        return this.objectNameOrigin;
    }

    @NotNull
    @Override
    public SQLQuerySymbolClass getSymbolClass() {
        return this.object instanceof DBSTable || this.object instanceof DBSView
            ? SQLQuerySymbolClass.TABLE
            : this.object != null ? SQLQuerySymbolClass.OBJECT : SQLQuerySymbolClass.ERROR;
    }

    @Override
    protected SQLQueryRowsSourceContext resolveRowSourcesImpl(
        @NotNull SQLQueryRowsSourceContext context,
        @NotNull SQLQueryRecognitionContext statistics
    ) {
        Set<DBSObjectType> scopeMemberTypes = new HashSet<>();
        scopeMemberTypes.addAll(this.objectContainerTypes);
        scopeMemberTypes.add(this.objectType);
        this.objectNameOrigin = new SQLQuerySymbolOrigin.DbObjectRef(context, scopeMemberTypes, false, this.filterMode);

        if (this.name.invalidPartsCount > 0) {
            // An unfinished name (like "dbo." while typing) has empty parts, which object lookup cannot take
            SQLQuerySemanticUtils.performPartialResolution(
                context,
                statistics,
                this.name,
                this.objectNameOrigin,
                this.filterMode,
                SQLQuerySymbolClass.ERROR
            );
            statistics.appendError(this.getSyntaxNode(), "Invalid object reference");
            return context.resetAsUnresolved();
        }

        List<? extends DBSObject> candidates = context.getConnectionInfo().findRealObjects(statistics.getMonitor(), objectType, this.name.stringParts);
        if (candidates.isEmpty()) {
            SQLQuerySemanticUtils.performPartialResolution(
                context,
                statistics,
                this.name,
                this.objectNameOrigin,
                this.filterMode,
                SQLQuerySymbolClass.ERROR
            );
            statistics.appendError(this.getSyntaxNode(), "Object " + this.name.getNameString() + " not found in the database");
            return context.resetAsUnresolved();
        } else if (candidates.size() > 1) {
            statistics.appendError(this.name.syntaxNode, "Object name " + this.name.getNameString() + " is ambiguous");
            this.name.parts.forEach(p -> p.getSymbol().setSymbolClass(SQLQuerySymbolClass.ERROR));
            return context.resetAsUnresolved();
        } else {
            this.object = candidates.getFirst();
            SQLQuerySemanticUtils.setNamePartsDefinition(
                context,
                this.name,
                this.object,
                this.objectNameOrigin,
                this.filterMode
            );
            if (!this.objectType.getTypeClass().isAssignableFrom(this.object.getClass())) {
                statistics.appendError(
                    this.getSyntaxNode(),
                    SQLQuerySemanticUtils.getObjectTypeName(this.object) + " found while expecting " + this.objectType.getTypeName()
                );
            }
            return context.reset();
        }
    }

    @Override
    protected SQLQueryRowsDataContext resolveRowDataImpl(
        @NotNull SQLQueryRowsDataContext context,
        @NotNull SQLQueryRecognitionContext statistics
    ) {
        return this.getRowsSources().makeEmptyTuple();
    }

    @Override
    protected <R, T> R applyImpl(@NotNull SQLQueryNodeModelVisitor<T, R> visitor, @NotNull T arg) {
        return visitor.visitObjectReference(this, arg);
    }
}