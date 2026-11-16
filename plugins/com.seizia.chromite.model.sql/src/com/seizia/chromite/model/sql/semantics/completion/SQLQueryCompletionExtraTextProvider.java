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
package com.seizia.chromite.model.sql.semantics.completion;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPOverloadedObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.sql.semantics.SQLQuerySemanticUtils;
import com.seizia.chromite.model.sql.semantics.completion.SQLQueryCompletionItem.*;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryExprType;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Gray text shown after a completion proposal
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: 호출문의 루틴 이름에 매개변수 형식 목록을 붙임. 이미 읽어 둔 시그니처가 있는 경우
 *   ({@link DBPOverloadedObject}, PostgreSQL)만 쓰고, 다른 데이터베이스는 루틴마다 질의가 필요해 붙이지 않는다
 *   (docs/chromite/swing-sql-completion.md)
 */
public class SQLQueryCompletionExtraTextProvider implements SQLQueryCompletionItemVisitor<String> {

    public static SQLQueryCompletionExtraTextProvider INSTANCE = new SQLQueryCompletionExtraTextProvider();

    private SQLQueryCompletionExtraTextProvider() {
    }

    @NotNull
    @Override
    public String visitSubqueryAlias(@NotNull SQLRowsSourceAliasCompletionItem rowsSourceAlias) {
        return (rowsSourceAlias.sourceInfo.tableOrNull != null ? " - Table alias" : " - Subquery alias")
             + (rowsSourceAlias.isRelated ? " (related)" : "");
    }

    @Nullable
    public static String prepareTypeNameString(@NotNull SQLQueryExprType type) {
        return type == null || type == SQLQueryExprType.UNKNOWN ? null : type.getDisplayName();
    }

    @NotNull
    public String visitCompositeField(@NotNull SQLCompositeFieldCompletionItem compositeField) {
        String typeName = this.prepareTypeNameString(compositeField.memberInfo.type());
        return typeName == null ? " - Composite attribute" : (" : " + typeName);
    }

    @Nullable
    @Override
    public String visitSpecialCompositeField(@NotNull SQLSpecialCompositeFieldCompletionItem compositeField) {
        String typeName = this.prepareTypeNameString(compositeField.memberInfo.type());
        return typeName == null ? " - Pseudo composite attribute" : (" : " + typeName);
    }

    @NotNull
    @Override
    public String visitColumnName(@NotNull SQLColumnNameCompletionItem columnName) {
        String typeName = this.prepareTypeNameString(columnName.columnInfo.type);
        return typeName == null ? " - Column" : (" : " + typeName);
    }

    @NotNull
    @Override
    public String visitGlobalPseudoColumn(@NotNull SQLGlobalPseudoColumnCompletionItem pseudoColumn) {
        String typeName = this.prepareTypeNameString(pseudoColumn.columnInfo.type);
        return typeName == null ? " - Global pseudo-column" : (" : " + typeName);
    }

    @NotNull
    @Override
    public String visitTableName(@NotNull SQLTableNameCompletionItem tableName) {
        String tail;
        if (tableName.isRelated || tableName.isUsed) {
            List<String> tags = new ArrayList<>();
            if (tableName.isRelated) {
                tags.add("related");
            }
            if (tableName.isUsed) {
                tags.add("used");
            }
            tail = "(" +  String.join(", ", tags) + ")";
        } else {
            tail = "";
        }
        return (DBUtils.isView(tableName.object) ? " - View " : " - Table ") + tail;
    }

    @Nullable
    @Override
    public String visitReservedWord(@Nullable SQLReservedWordCompletionItem reservedWord) {
        return null;
    }

    @NotNull
    @Override
    public String visitNamedObject(@NotNull SQLDbNamedObjectCompletionItem namedObject) {
        String typeName = SQLQuerySemanticUtils.getObjectTypeName(namedObject.object);
        return CommonUtils.isEmpty(typeName) ? "" : (" - " + typeName);
    }

    @NotNull
    @Override
    public String visitJoinCondition(@NotNull SQLJoinConditionCompletionItem joinCondition) {
        return " - Known foreign key relation";
    }

    @NotNull
    @Override
    public String visitProcedure(@NotNull SQLProcedureCompletionItem procedure) {
        String kind = switch (procedure.getObject().getProcedureType()) {
            case FUNCTION -> " - Function";
            case PROCEDURE -> " - Procedure";
            default -> " - Stored routine";
        };
        // A call target inserts only the name, so the parameter types are shown here when they are already loaded
        if (procedure.isCallTarget() && procedure.getObject() instanceof DBPOverloadedObject overloaded) {
            String signature = overloaded.getOverloadedName();
            int parametersStart = signature.indexOf('(');
            if (parametersStart >= 0) {
                return " " + signature.substring(parametersStart) + kind;
            }
        }
        return kind;
    }

    @Nullable
    @Override
    public String visitBuiltinFunction(@NotNull SQLBuiltinFunctionCompletionItem function) {
        return " - Builtin function";
    }

    @Nullable
    @Override
    public String  visitSpecialText(@NotNull SQLSpecialTextCompletionItem specialText) {
        return specialText.kind == SQLQueryCompletionItemKind.TYPE
            ? " - " + specialText.description : " - Special substitution";
    }

    @Nullable
    public String visitScriptVariable(@NotNull SQLScriptVariableCompletionItem scriptVariable) {
        String typeName = prepareTypeNameString(scriptVariable.variableDefinition.type());
        return typeName == null ? " - SQL Script variable" : (" : " + typeName);
    }

    @Nullable
    @Override
    public String visitLocalVariable(@NotNull SQLLocalVariableCompletionItem localVariable) {
        String typeName = prepareTypeNameString(localVariable.binding.type());
        return typeName == null ? " - Local variable" : " : " + typeName;
    }
}
